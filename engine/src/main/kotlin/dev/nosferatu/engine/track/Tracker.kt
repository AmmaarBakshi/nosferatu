package dev.nosferatu.engine.track

import dev.nosferatu.engine.activity.Activity
import dev.nosferatu.engine.activity.ActivityClassifier
import dev.nosferatu.engine.activity.ActivityEstimate
import dev.nosferatu.engine.filter.KalmanFilter
import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.geo.LocalFrame
import dev.nosferatu.engine.geo.Vec2
import dev.nosferatu.engine.gps.FixIssue
import dev.nosferatu.engine.gps.FixVerdict
import dev.nosferatu.engine.gps.GpsGate
import dev.nosferatu.engine.matching.HmmMapMatcher
import dev.nosferatu.engine.matching.MatchResult
import dev.nosferatu.engine.matching.MatchState
import dev.nosferatu.engine.model.AccelSample
import dev.nosferatu.engine.model.GpsFix
import dev.nosferatu.engine.model.GyroSample
import dev.nosferatu.engine.motion.InertialProcessor
import dev.nosferatu.engine.motion.MotionFeatures
import dev.nosferatu.engine.road.RoadNetwork
import dev.nosferatu.engine.road.WayClass
import kotlin.math.sqrt

data class TrackerConfig(
    /** Without an accepted fix for this long, GPS counts as lost and dead reckoning takes over. */
    val gpsLostAfterMs: Long = 4_000,
    /** Dead reckoning drifts (step length and heading errors add up); stop trusting it after this long. */
    val maxDeadReckoningMs: Long = 60_000,
    /** Consecutive mutually consistent rejected fixes that prove the filter, not the GPS, is wrong. */
    val recoveryFixes: Int = 3,
    /** Use the road network to pull the estimate towards the matched way (never a hard snap). */
    val roadAware: Boolean = true,
    /** Minimum matcher confidence before the map may influence the position. */
    val roadConfidence: Double = 0.7,
)

/**
 * Nosferatu's tracking engine: GPS, accelerometer, gyroscope and BombayChitra's road network in, one corrected
 * position, activity and distance out.
 *
 * Per GPS fix:
 * 1. [GpsGate] rejects broken fixes (stale, repeated, inaccurate, teleporting).
 * 2. The Kalman filter predicts to the fix time with process noise suited to the current activity, and the fix's
 *    innovation is gated: beyond the 99.9 % bound it is rejected, between 99 % and 99.9 % it is down-weighted.
 *    If several rejected fixes in a row agree with each other, the filter was wrong (after an outage, say), so it
 *    is re-initialised on the GPS instead of fighting it.
 * 3. Doppler speed and course update the velocity; a still phone gives a zero-velocity update, which stops
 *    stationary GPS drift from adding distance.
 * 4. The map matcher judges the fix against the network. Only when it is confident is the estimate pulled towards
 *    the matched way, and only across the way, by an amount set by the way's width and the confidence. Off-network
 *    and ambiguous matches leave the position alone.
 *
 * Without GPS, steps (cadence × learned step length) and the gyroscope's turn rate carry the estimate for up to
 * [TrackerConfig.maxDeadReckoningMs], with uncertainty growing all the while; after that the position is held and
 * reported as such rather than invented.
 */
class Tracker(
    private val network: RoadNetwork? = null,
    private val config: TrackerConfig = TrackerConfig(),
) {
    private val gate = GpsGate()
    private val kf = KalmanFilter()
    private val imu = InertialProcessor()
    private val classifier = ActivityClassifier()
    private val matcher = network?.let { HmmMapMatcher(it) }

    private var frame: LocalFrame? = null
    private var lastTimeMs = -1L
    private var lastFix: GpsFix? = null
    private var lastAcceptedMs = Long.MIN_VALUE / 2
    private var lastAcceptedSigma = 10.0
    private var gpsLost = false
    private val rejectedRun = ArrayList<GpsFix>()

    private var heading: Double? = null
    private var stepLengthM: Double? = null
    private var activity: ActivityEstimate = ActivityEstimate.UNKNOWN
    private var motion: MotionFeatures? = null
    private var lastClassifyMs = Long.MIN_VALUE / 2
    private var match: MatchResult? = null
    private var source = PositionSource.None
    private var imuSeen = false

    private var lastCountedPos: Vec2? = null
    private var distanceM = 0.0
    private var runningDistanceM = 0.0
    private var movingTimeMs = 0L
    private var runningTimeMs = 0L

    private val _events = ArrayDeque<CorrectionEvent>()
    /** The most recent corrections (at most [MAX_EVENTS]); use [eventCount] for totals over the whole run. */
    val events: List<CorrectionEvent> get() = _events.toList()
    private val eventCounts = IntArray(CorrectionEvent.Kind.entries.size)

    /** How many corrections of [kind] were made since the tracker was created, including ones no longer in [events]. */
    fun eventCount(kind: CorrectionEvent.Kind): Int = eventCounts[kind.ordinal]

    /** The corrected track: one list per continuous stretch (a recovery after an outage starts a new one). */
    private val _trail = ArrayList<MutableList<LatLon>>()
    val trail: List<List<LatLon>> get() = _trail.map { it.toList() }

    /**
     * After a pause the runner may be anywhere: the next accepted fix re-initialises the position and starts a new
     * stretch of the trail, and nothing between the pause and that fix counts as distance.
     */
    fun resume() {
        kf.resetUninitialized()
        gate.reset()
        matcher?.reset()
        classifier.reset()
        rejectedRun.clear()
        lastCountedPos = null
        gpsLost = false
    }

    fun onAccel(s: AccelSample) {
        imuSeen = true
        imu.onAccel(s)
    }

    fun onGyro(s: GyroSample) {
        imuSeen = true
        imu.onGyro(s)
    }

    fun onGps(fix: GpsFix): TrackSnapshot {
        lastFix = fix
        val verdict = gate.assess(fix)
        var sigma = when (verdict) {
            is FixVerdict.Rejected -> {
                event(fix.timeMs, fix.position, CorrectionEvent.Kind.Rejected, verdict.issue)
                return advance(fix.timeMs)
            }
            is FixVerdict.Accepted -> verdict.sigmaM
            is FixVerdict.DownWeighted -> {
                event(fix.timeMs, fix.position, CorrectionEvent.Kind.DownWeighted, verdict.issues.first())
                verdict.sigmaM
            }
        }

        val f = frame ?: LocalFrame(fix.position).also { frame = it }
        val z = f.toLocal(fix.position)
        if (!kf.initialized) {
            kf.reset(z, sigma, dopplerVelocity(fix) ?: Vec2.ZERO, if (dopplerVelocity(fix) != null) 0.5 else 2.0)
            lastTimeMs = fix.timeMs
            accepted(fix, sigma)
            newTrailStretch()
            return advance(fix.timeMs)
        }

        predictTo(fix.timeMs)
        val d2 = kf.positionInnovation(z, sigma)
        if (d2 > KalmanFilter.GATE_999) {
            rejectedRun += fix
            if (rejectedRun.size >= config.recoveryFixes && consistent(rejectedRun)) {
                // The fixes agree with each other and not with us: trust them.
                kf.reset(z, sigma, dopplerVelocity(fix) ?: Vec2.ZERO, 1.0)
                matcher?.reset()
                rejectedRun.clear()
                event(fix.timeMs, fix.position, CorrectionEvent.Kind.Recovered, detail = "filter re-initialised on GPS")
                newTrailStretch()
                lastCountedPos = kf.position
                accepted(fix, sigma)
                return advance(fix.timeMs)
            }
            event(fix.timeMs, fix.position, CorrectionEvent.Kind.Rejected, FixIssue.Inconsistent, "d²=%.0f".format(d2))
            return advance(fix.timeMs)
        }
        rejectedRun.clear()
        if (d2 > KalmanFilter.GATE_99) {
            sigma *= sqrt(d2 / KalmanFilter.GATE_99)
            event(fix.timeMs, fix.position, CorrectionEvent.Kind.DownWeighted, FixIssue.Inconsistent)
        }

        val still = motion?.still == true
        if (still) {
            // A still phone is not moving, whatever the GPS says; only let the fix nudge the position.
            kf.zeroVelocity()
            kf.updatePosition(z, sigma * 3)
        } else {
            kf.updatePosition(z, sigma)
            dopplerVelocity(fix)?.let { v -> kf.updateVelocity(v, (fix.speedAccuracyMps ?: 0.5).coerceIn(0.2, 2.0)) }
        }
        accepted(fix, sigma)

        val speed = kf.velocity.length()
        val courseOk = !still && speed > 1.0
        if (courseOk) heading = kf.velocity.heading()

        match = matcher?.update(fix.timeMs, fix.position, sigma, if (courseOk) heading else null)
        if (config.roadAware) applyRoad(f)
        learnStepLength(fix)
        return advance(fix.timeMs)
    }

    /** Advances time without a fix (call about once a second): dead reckoning, classification, bookkeeping. */
    fun tick(nowMs: Long): TrackSnapshot = advance(nowMs)

    private fun advance(nowMs: Long): TrackSnapshot {
        motion = if (imuSeen) imu.features(nowMs) else null
        val headingChange = imu.takeHeadingChangeDeg()
        heading = heading?.let { Geo.normalizeDegrees(it + headingChange) }

        val sinceFix = nowMs - lastAcceptedMs
        if (kf.initialized && sinceFix > config.gpsLostAfterMs) {
            if (!gpsLost) {
                gpsLost = true
                frame?.let { event(nowMs, it.toLatLon(kf.position), CorrectionEvent.Kind.GpsLost) }
            }
            deadReckon(nowMs, sinceFix)
        } else if (kf.initialized && gpsLost) {
            gpsLost = false
            frame?.let { event(nowMs, it.toLatLon(kf.position), CorrectionEvent.Kind.GpsRegained) }
        }

        if (nowMs - lastClassifyMs >= 900) {
            val fix = lastFix
            val fresh = fix != null && nowMs - lastAcceptedMs < 2_500 && fix.speedMps != null && (fix.accuracyM ?: 99.0) <= 25.0
            val gpsSpeed = if (fresh) fix!!.speedMps else if (kf.initialized && !gpsLost && kf.velocitySigma < 1.0) kf.velocity.length() else null
            val gpsSigma = if (fresh) (fix!!.speedAccuracyMps ?: 0.5) else kf.velocitySigma
            val dtMs = if (lastClassifyMs < 0) 0L else (nowMs - lastClassifyMs).coerceAtMost(5_000)
            activity = classifier.update(motion, gpsSpeed, gpsSigma)
            if (activity.activity != Activity.Stationary && activity.activity != Activity.Unknown) movingTimeMs += dtMs
            if (activity.isRunning) runningTimeMs += dtMs
            lastClassifyMs = nowMs
        }
        countDistance()
        return snapshot(nowMs)
    }

    private fun predictTo(t: Long) {
        val dt = ((t - lastTimeMs) / 1000.0).coerceIn(0.0, 30.0)
        kf.accelNoise = when (activity.activity) {
            Activity.Stationary -> 0.3
            Activity.Walking -> 1.0
            Activity.Jogging, Activity.Running -> 1.8
            Activity.Unknown -> 3.0
        }
        kf.predict(dt)
        lastTimeMs = t
    }

    private fun deadReckon(nowMs: Long, sinceFixMs: Long) {
        predictTo(nowMs)
        val m = motion
        if (m?.still == true) {
            kf.zeroVelocity()
            source = PositionSource.DeadReckoning
            return
        }
        val cadence = m?.cadenceSpm
        val h = heading
        if (sinceFixMs <= config.maxDeadReckoningMs && cadence != null && h != null) {
            val stride = stepLengthM ?: defaultStepLength()
            val v = Vec2.ofHeading(h) * (stride * cadence / 60.0)
            // Heading and step-length errors grow with time: so must our doubt about this velocity.
            val sigma = 0.3 + 0.02 * (sinceFixMs / 1000.0)
            kf.updateVelocity(v, sigma)
            source = PositionSource.DeadReckoning
        } else {
            // No steps or no heading: we do not know where the runner is going. Hold rather than invent.
            kf.updateVelocity(Vec2.ZERO, 3.0)
            source = PositionSource.Held
        }
    }

    private fun applyRoad(f: LocalFrame) {
        val m = match ?: return
        val net = network ?: return
        source = if (imuSeen) PositionSource.Fused else PositionSource.Gps
        if (m.state != MatchState.Matched || m.confidence < config.roadConfidence) return
        val c = m.candidate ?: return
        // Project our estimate (not the fix) onto the matched way: the correction is purely across the way.
        val est = f.toLatLon(kf.position)
        val onWay = net.candidates(est, 60.0, 16).firstOrNull { it.segment == c.segment } ?: return
        val seg = net.segment(c.segment)
        val halfWidth = when (seg.wayClass) {
            WayClass.Path, WayClass.Track -> 2.0
            WayClass.Pedestrian -> 3.0
            WayClass.Minor, WayClass.Service -> 4.0
            WayClass.Major -> 8.0 // pavements on both sides of a wide road
            WayClass.Other -> 5.0
        }
        val sigma = halfWidth / m.confidence
        if (onWay.distanceM > 3 * (sigma + kf.positionSigma)) return
        kf.updatePosition(f.toLocal(onWay.point), sigma)
        source = PositionSource.RoadAware
        if (onWay.distanceM > 3.0) event(lastTimeMs, onWay.point, CorrectionEvent.Kind.RoadPull, detail = "%.1f m towards %s".format(onWay.distanceM, seg.name ?: "the path"))
    }

    private fun accepted(fix: GpsFix, sigma: Double) {
        lastAcceptedMs = fix.timeMs
        lastAcceptedSigma = sigma
        source = if (imuSeen) PositionSource.Fused else PositionSource.Gps
    }

    private fun dopplerVelocity(fix: GpsFix): Vec2? {
        val s = fix.speedMps ?: return null
        val b = fix.bearingDeg
        return when {
            s < 0.3 -> Vec2.ZERO
            b == null -> null
            else -> Vec2.ofHeading(b) * s
        }
    }

    /** Learns this person's step length from good GPS speed and cadence, for dead reckoning later. */
    private fun learnStepLength(fix: GpsFix) {
        val cadence = motion?.cadenceSpm ?: return
        val speed = fix.speedMps ?: return
        if ((fix.accuracyM ?: 99.0) > 12 || speed < 0.8 || (motion?.cadenceRegularity ?: 0.0) < 0.5) return
        val step = speed / (cadence / 60.0)
        if (step !in 0.3..2.2) return
        stepLengthM = stepLengthM?.let { 0.9 * it + 0.1 * step } ?: step
    }

    private fun defaultStepLength() = if (activity.isRunning) 1.1 else 0.72

    private fun consistent(fixes: List<GpsFix>): Boolean = fixes.zipWithNext().all { (a, b) ->
        val dt = ((b.timeMs - a.timeMs) / 1000.0).coerceAtLeast(0.5)
        val slack = (a.accuracyM ?: 20.0) + (b.accuracyM ?: 20.0)
        Geo.distance(a.position, b.position) <= 10.0 * dt + slack
    }

    private fun countDistance() {
        if (!kf.initialized) return
        val p = kf.position
        val last = lastCountedPos
        if (last == null) {
            lastCountedPos = p
            return
        }
        // Only distance on foot counts: a bus ride is movement, but not part of a run or a walk. While the state is
        // unknown, detected steps still prove the person is on foot (transitions start out unknown).
        val onFoot = when (activity.activity) {
            Activity.Walking, Activity.Jogging, Activity.Running -> true
            Activity.Unknown -> motion?.cadenceSpm != null || !imuSeen
            Activity.Stationary -> false
        }
        val moving = onFoot && motion?.still != true
        val step = (p - last).length()
        // Ignore wobble below the noise floor; it is drift, not progress.
        val floor = (0.5 * kf.positionSigma).coerceIn(1.0, 6.0)
        if (!moving) {
            lastCountedPos = p
            return
        }
        if (step >= floor) {
            distanceM += step
            if (activity.isRunning) runningDistanceM += step
            lastCountedPos = p
            frame?.let { appendTrail(it.toLatLon(p)) }
        }
    }

    private fun newTrailStretch() {
        if (_trail.isEmpty() || _trail.last().isNotEmpty()) _trail.add(ArrayList())
        frame?.let { _trail.last().add(it.toLatLon(kf.position)) }
    }

    private fun appendTrail(p: LatLon) {
        if (_trail.isEmpty()) _trail.add(ArrayList())
        _trail.last().add(p)
    }

    private fun event(t: Long, p: LatLon, kind: CorrectionEvent.Kind, issue: FixIssue? = null, detail: String = "") {
        _events.addLast(CorrectionEvent(t, p, kind, issue, detail))
        eventCounts[kind.ordinal]++
        while (_events.size > MAX_EVENTS) _events.removeFirst()
    }

    private fun snapshot(nowMs: Long): TrackSnapshot {
        val f = frame
        val has = kf.initialized && f != null
        val sinceFix = nowMs - lastAcceptedMs
        val quality = when {
            lastFix == null -> GpsQuality.Off
            sinceFix > config.gpsLostAfterMs -> GpsQuality.Lost
            lastAcceptedSigma <= 10 -> GpsQuality.Good
            lastAcceptedSigma <= 20 -> GpsQuality.Fair
            else -> GpsQuality.Poor
        }
        return TrackSnapshot(
            timeMs = nowMs,
            position = if (has) f!!.toLatLon(kf.position) else null,
            uncertaintyM = if (has) kf.positionSigma else null,
            speedMps = if (has) kf.velocity.length() else null,
            headingDeg = heading,
            source = if (has) source else PositionSource.None,
            gpsQuality = quality,
            activity = activity,
            motion = motion,
            match = match,
            lastFix = lastFix,
            distanceM = distanceM,
            runningDistanceM = runningDistanceM,
            movingTimeMs = movingTimeMs,
            runningTimeMs = runningTimeMs,
            stepsTotal = motion?.stepsTotal ?: 0,
        )
    }

    private companion object {
        const val MAX_EVENTS = 500
    }
}
