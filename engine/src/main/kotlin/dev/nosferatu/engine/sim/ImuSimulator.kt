package dev.nosferatu.engine.sim

import dev.nosferatu.engine.model.AccelSample
import dev.nosferatu.engine.model.GyroSample
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** How a body moves: steps per minute and how hard each step lands (peak vertical acceleration, m/s²). */
data class Gait(val cadenceSpm: Double, val verticalPeak: Double, val name: String) {
    companion object {
        val STILL = Gait(0.0, 0.0, "still")
        val WALK = Gait(108.0, 2.2, "walk")
        val BRISK_WALK = Gait(124.0, 3.0, "brisk walk")
        val JOG = Gait(158.0, 7.5, "jog")
        val RUN = Gait(172.0, 10.5, "run")
        /** Sitting in a vehicle: vibration and sway, no steps. */
        val VEHICLE = Gait(0.0, 0.0, "vehicle")
    }
}

/**
 * Synthesises accelerometer and gyroscope samples for a phone carried by someone moving with a [Gait].
 *
 * World frame is east-north-up. The phone's orientation is given by roll/pitch/yaw, so the same body motion appears
 * on different phone axes depending on how the phone is held: tests use it to check that step detection and turn
 * rates do not depend on orientation. Gyroscope bias and sensor noise are included.
 */
class ImuSimulator(
    seed: Long = 1,
    var rollDeg: Double = 0.0,
    var pitchDeg: Double = 0.0,
    var yawDeg: Double = 0.0,
    private val accelNoise: Double = 0.08,
    private val gyroNoise: Double = 0.004,
    val gyroBias: DoubleArray = doubleArrayOf(0.012, -0.008, 0.006),
) {
    private val rnd = Random(seed)
    private var phase = 0.0

    /**
     * Samples for [durationMs] starting at [startMs], every [periodMs].
     * @param turnRateDps how fast the body turns, degrees per second, positive clockwise seen from above.
     * @param rollRateDps the phone rotating in the hand or pocket, about its own x axis.
     */
    fun generate(
        startMs: Long,
        durationMs: Long,
        gait: Gait,
        turnRateDps: Double = 0.0,
        rollRateDps: Double = 0.0,
        periodMs: Long = 20,
        out: (AccelSample?, GyroSample?) -> Unit,
    ) {
        var t = startMs
        val dt = periodMs / 1000.0
        val freq = gait.cadenceSpm / 60.0
        while (t < startMs + durationMs) {
            phase += 2 * PI * freq * dt
            // Vertical body acceleration: a sharp landing peak per step plus a softer harmonic.
            var up = 0.0
            if (gait.verticalPeak > 0) {
                val s = sin(phase)
                up = gait.verticalPeak * (0.75 * s + 0.25 * sin(2 * phase + 0.6))
            }
            var east = 0.0; var north = 0.0
            if (gait === Gait.VEHICLE) {
                up = 0.35 * rnd.nextGaussian(); east = 0.25 * rnd.nextGaussian(); north = 0.25 * rnd.nextGaussian()
            }
            val world = doubleArrayOf(east, north, GRAVITY + up)
            val r = rotation()
            // Phone axes see Rᵀ · world (R maps phone → world).
            val ax = r[0] * world[0] + r[3] * world[1] + r[6] * world[2]
            val ay = r[1] * world[0] + r[4] * world[1] + r[7] * world[2]
            val az = r[2] * world[0] + r[5] * world[1] + r[8] * world[2]
            val accel = AccelSample(t, ax + rnd.nextGaussian() * accelNoise, ay + rnd.nextGaussian() * accelNoise, az + rnd.nextGaussian() * accelNoise)

            // Angular velocity: body turn about world up (clockwise = negative about +z), plus roll about phone x.
            val wz = -Math.toRadians(turnRateDps)
            val gx = r[6] * wz + Math.toRadians(rollRateDps)
            val gy = r[7] * wz
            val gz = r[8] * wz
            val gyro = GyroSample(
                t,
                gx + gyroBias[0] + rnd.nextGaussian() * gyroNoise,
                gy + gyroBias[1] + rnd.nextGaussian() * gyroNoise,
                gz + gyroBias[2] + rnd.nextGaussian() * gyroNoise,
            )
            out(accel, gyro)
            // Body yaw does not change how gravity looks to the phone, so only the roll is integrated.
            rollDeg += rollRateDps * dt
            t += periodMs
        }
    }

    /** Row-major rotation phone → world from roll (x), pitch (y), yaw (z). */
    private fun rotation(): DoubleArray {
        val a = Math.toRadians(rollDeg); val b = Math.toRadians(pitchDeg); val c = Math.toRadians(yawDeg)
        val ca = cos(a); val sa = sin(a); val cb = cos(b); val sb = sin(b); val cc = cos(c); val sc = sin(c)
        // R = Rz(c) · Ry(b) · Rx(a)
        return doubleArrayOf(
            cc * cb, cc * sb * sa - sc * ca, cc * sb * ca + sc * sa,
            sc * cb, sc * sb * sa + cc * ca, sc * sb * ca - cc * sa,
            -sb, cb * sa, cb * ca,
        )
    }

    companion object {
        const val GRAVITY = 9.80665
    }
}
