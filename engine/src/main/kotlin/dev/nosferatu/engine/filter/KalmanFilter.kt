package dev.nosferatu.engine.filter

import dev.nosferatu.engine.geo.Vec2
import kotlin.math.sqrt

/**
 * A 2-D constant-velocity Kalman filter in metres: state `[x, y, vx, vy]` (east, north).
 *
 * Motion between updates is modelled as white-noise acceleration with standard deviation [accelNoise] (m/s²);
 * a runner's real accelerations (strides average out, turns, stops) sit around 0.5–2 m/s². Measurements are GPS
 * positions (with the platform's accuracy as standard deviation), Doppler velocity, or a zero-velocity update when
 * the phone is known to be still.
 *
 * Every update returns its normalised innovation (squared Mahalanobis distance) *before* applying it, so the caller
 * can reject a fix that the model finds implausible. For 2 degrees of freedom the 99 % chi-square bound is 9.21.
 */
class KalmanFilter(var accelNoise: Double = 1.5) {
    private val x = DoubleArray(4)
    private val p = DoubleArray(16) // row-major 4×4

    var initialized = false
        private set

    val position: Vec2 get() = Vec2(x[0], x[1])
    val velocity: Vec2 get() = Vec2(x[2], x[3])

    /** 1-sigma position uncertainty in metres (root of the mean of the two position variances). */
    val positionSigma: Double get() = sqrt(((p[0] + p[5]) / 2).coerceAtLeast(0.0))
    val velocitySigma: Double get() = sqrt(((p[10] + p[15]) / 2).coerceAtLeast(0.0))

    fun reset(position: Vec2, positionSigma: Double, velocity: Vec2 = Vec2.ZERO, velocitySigma: Double = 3.0) {
        x[0] = position.x; x[1] = position.y; x[2] = velocity.x; x[3] = velocity.y
        p.fill(0.0)
        p[0] = positionSigma * positionSigma; p[5] = p[0]
        p[10] = velocitySigma * velocitySigma; p[15] = p[10]
        initialized = true
    }

    /** Moves the frame origin: used when the local frame is re-centred during a long run. */
    fun shift(by: Vec2) {
        x[0] -= by.x; x[1] -= by.y
    }

    fun predict(dtS: Double) {
        if (!initialized || dtS <= 0) return
        val dt = dtS
        // x = F x
        x[0] += dt * x[2]
        x[1] += dt * x[3]
        // P = F P Fᵀ + Q, written out for F = [[I, dt·I], [0, I]].
        val n = p.copyOf()
        for (axis in 0..1) {
            val i = axis; val j = axis + 2
            for (axis2 in 0..1) {
                val k = axis2; val l = axis2 + 2
                n[i * 4 + k] = p[i * 4 + k] + dt * (p[j * 4 + k] + p[i * 4 + l]) + dt * dt * p[j * 4 + l]
                n[i * 4 + l] = p[i * 4 + l] + dt * p[j * 4 + l]
                n[j * 4 + k] = p[j * 4 + k] + dt * p[j * 4 + l]
                n[j * 4 + l] = p[j * 4 + l]
            }
        }
        val q = accelNoise * accelNoise
        val dt2 = dt * dt; val dt3 = dt2 * dt; val dt4 = dt3 * dt
        for (axis in 0..1) {
            val i = axis; val j = axis + 2
            n[i * 4 + i] += q * dt4 / 4
            n[i * 4 + j] += q * dt3 / 2
            n[j * 4 + i] += q * dt3 / 2
            n[j * 4 + j] += q * dt2
        }
        n.copyInto(p)
    }

    /** Squared Mahalanobis distance of a position measurement, without applying it. */
    fun positionInnovation(z: Vec2, sigma: Double): Double = innovation(0, z, sigma)

    fun velocityInnovation(v: Vec2, sigma: Double): Double = innovation(2, v, sigma)

    /** Applies a position measurement; returns its normalised innovation. */
    fun updatePosition(z: Vec2, sigma: Double): Double = update(0, z, sigma)

    fun updateVelocity(v: Vec2, sigma: Double): Double = update(2, v, sigma)

    /** The phone is still: velocity is zero, which also stops GPS drift from moving the estimate. */
    fun zeroVelocity(sigma: Double = 0.05): Double = update(2, Vec2.ZERO, sigma)

    /** Predicted position after [dtS] seconds, without changing the filter. */
    fun peek(dtS: Double): Vec2 = Vec2(x[0] + dtS * x[2], x[1] + dtS * x[3])

    private fun innovation(offset: Int, z: Vec2, sigma: Double): Double {
        val y0 = z.x - x[offset]; val y1 = z.y - x[offset + 1]
        val r = sigma * sigma
        val s00 = p[offset * 4 + offset] + r
        val s01 = p[offset * 4 + offset + 1]
        val s10 = p[(offset + 1) * 4 + offset]
        val s11 = p[(offset + 1) * 4 + offset + 1] + r
        val det = s00 * s11 - s01 * s10
        if (det <= 0) return Double.POSITIVE_INFINITY
        return (y0 * (s11 * y0 - s01 * y1) + y1 * (-s10 * y0 + s00 * y1)) / det
    }

    /** Standard Kalman update for H selecting state components [offset] and [offset]+1. */
    private fun update(offset: Int, z: Vec2, sigma: Double): Double {
        val d2 = innovation(offset, z, sigma)
        if (!d2.isFinite()) return d2
        val a = offset; val b = offset + 1
        val r = sigma * sigma
        val s00 = p[a * 4 + a] + r; val s01 = p[a * 4 + b]
        val s10 = p[b * 4 + a]; val s11 = p[b * 4 + b] + r
        val det = s00 * s11 - s01 * s10
        val i00 = s11 / det; val i01 = -s01 / det; val i10 = -s10 / det; val i11 = s00 / det
        // K = P Hᵀ S⁻¹ (4×2): column c of P Hᵀ is P[:, a or b].
        val k = DoubleArray(8)
        for (row in 0..3) {
            val pa = p[row * 4 + a]; val pb = p[row * 4 + b]
            k[row * 2] = pa * i00 + pb * i10
            k[row * 2 + 1] = pa * i01 + pb * i11
        }
        val y0 = z.x - x[a]; val y1 = z.y - x[b]
        for (row in 0..3) x[row] += k[row * 2] * y0 + k[row * 2 + 1] * y1
        // P = (I − K H) P
        val n = p.copyOf()
        for (row in 0..3) for (col in 0..3) {
            n[row * 4 + col] = p[row * 4 + col] - (k[row * 2] * p[a * 4 + col] + k[row * 2 + 1] * p[b * 4 + col])
        }
        // Keep P symmetric against rounding.
        for (row in 0..3) for (col in row + 1..3) {
            val m = (n[row * 4 + col] + n[col * 4 + row]) / 2
            n[row * 4 + col] = m; n[col * 4 + row] = m
        }
        n.copyInto(p)
        return d2
    }

    companion object {
        /** 99 % bound of chi-square with 2 degrees of freedom. */
        const val GATE_99 = 9.21
        /** 99.9 % bound. */
        const val GATE_999 = 13.82
    }
}
