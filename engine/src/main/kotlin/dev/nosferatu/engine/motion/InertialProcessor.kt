package dev.nosferatu.engine.motion

import dev.nosferatu.engine.model.AccelSample
import dev.nosferatu.engine.model.GyroSample
import kotlin.math.abs
import kotlin.math.sqrt

/** What the accelerometer and gyroscope say about how the body is moving, over the last few seconds. */
data class MotionFeatures(
    val timeMs: Long,
    /** Steps per minute from the median step interval; null when there are too few recent steps. */
    val cadenceSpm: Double?,
    /** 0 (erratic) – 1 (metronome): how evenly spaced the recent steps are. */
    val cadenceRegularity: Double,
    /** RMS of vertical (gravity-axis) acceleration with gravity removed, m/s². Low walking, high running. */
    val verticalRms: Double,
    /** Mean height of recent step peaks, m/s²: how hard the feet land. */
    val stepPeak: Double?,
    val stepsTotal: Int,
    /** No meaningful acceleration or rotation: the phone is lying still or held still. */
    val still: Boolean,
    /** Body turn rate, degrees per second, clockwise positive; null without a gyroscope. */
    val turnRateDps: Double?,
    /** Whether a gyroscope bias estimate exists (needs a moment of stillness). */
    val gyroCalibrated: Boolean,
)

/**
 * Turns raw accelerometer and gyroscope samples into orientation-independent motion features.
 *
 * - **Gravity** is tracked with a 1 s low-pass of the accelerometer. Projecting acceleration onto it gives the
 *   *vertical* acceleration of the body whatever way the phone is held, in a pocket, a hand or an armband, and
 *   keeps working while the phone rotates.
 * - **Steps** are peaks of smoothed vertical acceleration above an adaptive threshold, with hysteresis and a
 *   250 ms refractory period (no human steps faster than 240 per minute).
 * - **Turning** is the gyroscope's angular velocity projected onto gravity: rotation of the body about the vertical,
 *   again independent of how the phone is held. The gyroscope's bias is estimated whenever the phone is still and
 *   subtracted; until then the turn rate is reported but marked uncalibrated, because bias integrates into heading
 *   drift (about 0.6°/s for a typical 0.01 rad/s bias).
 */
class InertialProcessor(private val windowMs: Long = 5_000) {
    private var gx = 0.0; private var gy = 0.0; private var gz = 0.0
    private var hasGravity = false
    private var lastAccelMs = -1L
    private var smoothed = 0.0

    private val vertical = ArrayDeque<Pair<Long, Double>>()
    private val magnitude = ArrayDeque<Pair<Long, Double>>()
    private val steps = ArrayDeque<Long>()
    private val peaks = ArrayDeque<Double>()
    private var stepsTotal = 0

    // Peak detector
    private var inPeak = false
    private var peakValue = 0.0
    private var peakTime = 0L
    private var lastStepMs = Long.MIN_VALUE / 2
    private var typicalPeak = 3.0

    // Gyroscope
    private val bias = DoubleArray(3)
    private var biasSamples = 0
    private var lastGyroMs = -1L
    private var gyroSeen = false
    private var turnRate = 0.0
    private val gyroMagnitude = ArrayDeque<Pair<Long, Double>>()
    private var headingChange = 0.0

    /** Feeds an accelerometer sample; returns true when it completes a step. */
    fun onAccel(s: AccelSample): Boolean {
        val dt = if (lastAccelMs < 0) 0.02 else ((s.timeMs - lastAccelMs) / 1000.0).coerceIn(0.0, 0.5)
        lastAccelMs = s.timeMs
        if (!hasGravity) {
            gx = s.x; gy = s.y; gz = s.z; hasGravity = true
        } else {
            val a = dt / (GRAVITY_TAU_S + dt)
            gx += a * (s.x - gx); gy += a * (s.y - gy); gz += a * (s.z - gz)
        }
        val g = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6)
        val v = (s.x * gx + s.y * gy + s.z * gz) / g - g
        val b = dt / (SMOOTH_TAU_S + dt)
        smoothed += b * (v - smoothed)

        push(vertical, s.timeMs to v, windowMs)
        push(magnitude, s.timeMs to sqrt(s.x * s.x + s.y * s.y + s.z * s.z), STILL_WINDOW_MS)
        return detectStep(s.timeMs, dt)
    }

    private fun detectStep(t: Long, dt: Double): Boolean {
        // When steps stop, let the threshold relax towards walking levels so a slower gait is picked up again.
        if (t - lastStepMs > 1_500) typicalPeak -= (typicalPeak - 2.0) * (dt / 1.0).coerceAtMost(1.0)
        val threshold = maxOf(MIN_PEAK, 0.45 * typicalPeak)
        var step = false
        if (!inPeak) {
            if (smoothed > threshold) {
                inPeak = true; peakValue = smoothed; peakTime = t
            }
        } else {
            if (smoothed > peakValue) {
                peakValue = smoothed; peakTime = t
            }
            if (smoothed < threshold * 0.4) {
                inPeak = false
                if (peakTime - lastStepMs >= MIN_STEP_INTERVAL_MS) {
                    lastStepMs = peakTime
                    stepsTotal++
                    push(steps, peakTime, windowMs)
                    peaks.addLast(peakValue)
                    while (peaks.size > 8) peaks.removeFirst()
                    typicalPeak = 0.8 * typicalPeak + 0.2 * peakValue
                    step = true
                }
            }
        }
        while (steps.isNotEmpty() && steps.first() < t - windowMs) steps.removeFirst()
        return step
    }

    fun onGyro(s: GyroSample) {
        gyroSeen = true
        val dt = if (lastGyroMs < 0) 0.0 else ((s.timeMs - lastGyroMs) / 1000.0).coerceIn(0.0, 0.5)
        lastGyroMs = s.timeMs
        push(gyroMagnitude, s.timeMs to sqrt(s.x * s.x + s.y * s.y + s.z * s.z), STILL_WINDOW_MS)
        if (isStill(s.timeMs, requireGyro = false)) {
            // Average the gyroscope while still: whatever it reads then is bias.
            biasSamples++
            val k = 1.0 / minOf(biasSamples, 250)
            bias[0] += k * (s.x - bias[0]); bias[1] += k * (s.y - bias[1]); bias[2] += k * (s.z - bias[2])
        }
        if (!hasGravity) return
        val g = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6)
        val wx = s.x - bias[0]; val wy = s.y - bias[1]; val wz = s.z - bias[2]
        // Positive rotation about "up" is counter-clockwise from above; compass headings grow clockwise.
        val clockwise = -Math.toDegrees((wx * gx + wy * gy + wz * gz) / g)
        turnRate += (dt / (0.25 + dt)) * (clockwise - turnRate)
        headingChange += clockwise * dt
    }

    /** Heading change since the last call, degrees clockwise, from the gyroscope; 0 without one. */
    fun takeHeadingChangeDeg(): Double {
        val h = headingChange
        headingChange = 0.0
        return h
    }

    fun features(nowMs: Long): MotionFeatures {
        val recent = steps.filter { it >= nowMs - windowMs }
        var cadence: Double? = null
        var regularity = 0.0
        if (recent.size >= 4) {
            val intervals = recent.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
            val median = intervals[intervals.size / 2]
            // Stale if the steps stopped: nothing for two intervals plus a second.
            if (nowMs - recent.last() <= 2 * median + 1_000) {
                cadence = 60_000.0 / median
                val mad = intervals.map { abs(it - median) }.sorted()[intervals.size / 2]
                regularity = (1.0 - (mad / median) / 0.25).coerceIn(0.0, 1.0)
            }
        }
        val window = vertical.filter { it.first >= nowMs - windowMs }
        val rms = if (window.isEmpty()) 0.0 else sqrt(window.sumOf { it.second * it.second } / window.size)
        return MotionFeatures(
            timeMs = nowMs,
            cadenceSpm = cadence,
            cadenceRegularity = regularity,
            verticalRms = rms,
            stepPeak = if (cadence != null && peaks.isNotEmpty()) peaks.average() else null,
            stepsTotal = stepsTotal,
            still = isStill(nowMs, requireGyro = gyroSeen),
            turnRateDps = if (gyroSeen) turnRate else null,
            gyroCalibrated = biasSamples >= 50,
        )
    }

    private fun isStill(nowMs: Long, requireGyro: Boolean): Boolean {
        val m = magnitude.filter { it.first >= nowMs - STILL_WINDOW_MS }
        if (m.size < 10 || m.last().first - m.first().first < STILL_WINDOW_MS * 0.8) return false
        val mean = m.sumOf { it.second } / m.size
        val sd = sqrt(m.sumOf { (it.second - mean) * (it.second - mean) } / m.size)
        if (sd > STILL_ACCEL_SD) return false
        if (requireGyro) {
            // Bias is a few hundredths of a rad/s at most; real rotation is far larger.
            val gm = gyroMagnitude.filter { it.first >= nowMs - STILL_WINDOW_MS }
            if (gm.isNotEmpty() && gm.sumOf { it.second } / gm.size > STILL_GYRO_RAD_S) return false
        }
        return true
    }

    private fun <T> push(q: ArrayDeque<Pair<Long, T>>, item: Pair<Long, T>, keepMs: Long) {
        q.addLast(item)
        while (q.isNotEmpty() && q.first().first < item.first - keepMs) q.removeFirst()
    }

    private fun push(q: ArrayDeque<Long>, t: Long, keepMs: Long) {
        q.addLast(t)
        while (q.isNotEmpty() && q.first() < t - keepMs) q.removeFirst()
    }

    companion object {
        private const val GRAVITY_TAU_S = 1.0
        private const val SMOOTH_TAU_S = 0.04
        /** Smallest vertical peak that counts as a step, m/s². Gentle walking lands at 1.5–3. */
        private const val MIN_PEAK = 1.0
        private const val MIN_STEP_INTERVAL_MS = 250L
        private const val STILL_WINDOW_MS = 1_500L
        private const val STILL_ACCEL_SD = 0.15
        private const val STILL_GYRO_RAD_S = 0.06
    }
}
