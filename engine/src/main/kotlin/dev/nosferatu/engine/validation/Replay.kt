package dev.nosferatu.engine.validation

import dev.nosferatu.engine.activity.Activity
import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.track.TrackSnapshot
import dev.nosferatu.engine.track.Tracker
import dev.nosferatu.engine.trace.TraceEvent
import dev.nosferatu.engine.trace.TraceFormat
import kotlin.math.ceil

/** The tracker's output next to the truth, once per second. */
data class ReplayStep(val timeMs: Long, val truth: TraceEvent.Truth?, val snapshot: TrackSnapshot, val rawFix: LatLon?)

/**
 * Feeds a trace through a [Tracker] exactly as the app does: sensor samples as they come, GPS fixes as they come,
 * and a tick every second so time advances during GPS outages. Between [TraceFormat.PAUSE] and [TraceFormat.RESUME]
 * notes there are no ticks, and the tracker is resumed as the app resumes it.
 */
object Replay {
    fun run(events: List<TraceEvent>, tracker: Tracker, tickMs: Long = 1000): List<ReplayStep> {
        val sorted = events.sortedBy { it.timeMs }
        if (sorted.isEmpty()) return emptyList()
        val steps = ArrayList<ReplayStep>()
        var nextTick = sorted.first().timeMs + tickMs
        var pendingTruth: TraceEvent.Truth? = null
        var lastRaw: LatLon? = null
        var rawFresh = false
        var last: TrackSnapshot = TrackSnapshot.empty(sorted.first().timeMs)
        var paused = false
        for (e in sorted) {
            while (!paused && e.timeMs >= nextTick) {
                last = tracker.tick(nextTick)
                steps += ReplayStep(nextTick, pendingTruth, last, if (rawFresh) lastRaw else null)
                rawFresh = false
                nextTick += tickMs
            }
            when (e) {
                is TraceEvent.Accel -> tracker.onAccel(e.sample)
                is TraceEvent.Gyro -> tracker.onGyro(e.sample)
                is TraceEvent.Gps -> {
                    last = tracker.onGps(e.fix)
                    lastRaw = e.fix.position
                    rawFresh = true
                }
                is TraceEvent.Truth -> pendingTruth = e
                is TraceEvent.Note -> when (e.text) {
                    TraceFormat.PAUSE -> paused = true
                    TraceFormat.RESUME -> if (paused) {
                        paused = false
                        tracker.resume()
                        nextTick = e.timeMs + tickMs
                    }
                }
            }
        }
        if (paused) return steps
        last = tracker.tick(nextTick)
        steps += ReplayStep(nextTick, pendingTruth, last, if (rawFresh) lastRaw else null)
        return steps
    }
}

/** Accuracy figures for one replay; every number is measured, none assumed. */
data class Metrics(
    val seconds: Int,
    val correctedErrorMeanM: Double,
    val correctedErrorP50M: Double,
    val correctedErrorP95M: Double,
    val rawErrorMeanM: Double,
    val rawErrorP95M: Double,
    val truthDistanceM: Double,
    val trackedDistanceM: Double,
    /** Distance computed by summing raw GPS fixes, the naive approach, for comparison. */
    val rawDistanceM: Double,
    val runningPrecision: Double?,
    val runningRecall: Double?,
    val activityAccuracy: Double,
    val falseRunningS: Int,
    /** Seconds from the end of the last GPS fault until the error falls below 10 m; null without faults. */
    val recoveryS: Int?,
) {
    val distanceErrorPct: Double get() = if (truthDistanceM > 0) 100 * (trackedDistanceM - truthDistanceM) / truthDistanceM else 0.0
    val rawDistanceErrorPct: Double get() = if (truthDistanceM > 0) 100 * (rawDistanceM - truthDistanceM) / truthDistanceM else 0.0

    companion object {
        /**
         * @param faultEndS seconds (from the start) at which the last GPS fault ended, for recovery time.
         * @param warmupS seconds to skip before scoring classification (the filter needs a few seconds).
         */
        fun of(steps: List<ReplayStep>, faultEndS: Int? = null, warmupS: Int = 8): Metrics {
            val scored = steps.filter { it.truth != null && it.snapshot.position != null }
            val err = scored.map { Geo.distance(it.truth!!.position, it.snapshot.position!!) }.sorted()
            val raw = steps.filter { it.truth != null && it.rawFix != null }.map { Geo.distance(it.truth!!.position, it.rawFix!!) }.sorted()

            val truths = steps.mapNotNull { it.truth }.distinctBy { it.timeMs }
            val truthDistance = truths.zipWithNext().sumOf { (a, b) -> Geo.distance(a.position, b.position) }
            val rawFixes = steps.mapNotNull { it.rawFix }
            val rawDistance = rawFixes.zipWithNext().sumOf { (a, b) -> Geo.distance(a, b) }

            val classified = steps.drop(warmupS).filter { it.truth != null }
            var tp = 0; var fp = 0; var fn = 0; var correct = 0
            for (s in classified) {
                val truthRun = s.truth!!.activity == Activity.Jogging || s.truth.activity == Activity.Running
                val predRun = s.snapshot.activity.isRunning
                if (truthRun && predRun) tp++
                if (!truthRun && predRun) fp++
                if (truthRun && !predRun) fn++
                if (sameClass(s.truth.activity, s.snapshot.activity.activity)) correct++
            }

            var recovery: Int? = null
            if (faultEndS != null && steps.isNotEmpty()) {
                val t0 = steps.first().timeMs + faultEndS * 1000L
                val after = scored.filter { it.timeMs >= t0 }
                val firstGood = after.firstOrNull { Geo.distance(it.truth!!.position, it.snapshot.position!!) < 10.0 }
                recovery = firstGood?.let { ceil((it.timeMs - t0) / 1000.0).toInt() } ?: after.size
            }

            return Metrics(
                seconds = steps.size,
                correctedErrorMeanM = err.averageOrZero(),
                correctedErrorP50M = err.percentile(0.5),
                correctedErrorP95M = err.percentile(0.95),
                rawErrorMeanM = raw.averageOrZero(),
                rawErrorP95M = raw.percentile(0.95),
                truthDistanceM = truthDistance,
                trackedDistanceM = steps.lastOrNull()?.snapshot?.distanceM ?: 0.0,
                rawDistanceM = rawDistance,
                runningPrecision = if (tp + fp > 0) tp.toDouble() / (tp + fp) else null,
                runningRecall = if (tp + fn > 0) tp.toDouble() / (tp + fn) else null,
                activityAccuracy = if (classified.isEmpty()) 0.0 else correct.toDouble() / classified.size,
                falseRunningS = fp,
                recoveryS = recovery,
            )
        }

        /** Unknown (vehicle) truth is matched by an Unknown prediction; jogging and running are told apart. */
        private fun sameClass(truth: Activity, predicted: Activity) = truth == predicted

        private fun List<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()
        private fun List<Double>.percentile(p: Double) = if (isEmpty()) 0.0 else this[((size - 1) * p).toInt()]
    }
}
