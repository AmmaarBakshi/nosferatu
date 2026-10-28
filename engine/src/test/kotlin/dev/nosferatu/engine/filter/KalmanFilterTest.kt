package dev.nosferatu.engine.filter

import dev.nosferatu.engine.geo.Vec2
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalmanFilterTest {
    @Test
    fun tracksConstantVelocityAndReducesNoise() {
        val rnd = Random(7)
        val kf = KalmanFilter(accelNoise = 0.5)
        kf.reset(Vec2(0.0, 0.0), positionSigma = 10.0)
        var rawErr = 0.0; var kfErr = 0.0; var n = 0
        for (t in 1..120) {
            val truth = Vec2(3.0 * t, 1.0 * t) // 3.2 m/s, a steady run
            val z = Vec2(truth.x + rnd.nextGaussian() * 5, truth.y + rnd.nextGaussian() * 5)
            kf.predict(1.0)
            kf.updatePosition(z, 5.0)
            // Positions alone pin velocity down to only ~1 m/s at 5 m noise; GPS Doppler speed is what makes it tight.
            kf.updateVelocity(Vec2(3.0 + rnd.nextGaussian() * 0.3, 1.0 + rnd.nextGaussian() * 0.3), 0.3)
            if (t > 20) {
                rawErr += (z - truth).length(); kfErr += (kf.position - truth).length(); n++
            }
        }
        assertEquals(3.0, kf.velocity.x, 0.3)
        assertEquals(1.0, kf.velocity.y, 0.3)
        assertTrue(kfErr / n < 0.6 * rawErr / n, "filter error ${kfErr / n} should be well under raw ${rawErr / n}")
    }

    @Test
    fun innovationFlagsAJump() {
        val kf = KalmanFilter()
        kf.reset(Vec2.ZERO, 5.0)
        for (t in 1..30) {
            kf.predict(1.0); kf.updatePosition(Vec2(3.0 * t, 0.0), 5.0)
        }
        kf.predict(1.0)
        val normal = kf.positionInnovation(Vec2(93.0, 0.0), 5.0)
        val jump = kf.positionInnovation(Vec2(93.0, 150.0), 5.0)
        assertTrue(normal < KalmanFilter.GATE_99, "normal fix d²=$normal")
        assertTrue(jump > KalmanFilter.GATE_999, "jump d²=$jump")
    }

    @Test
    fun uncertaintyGrowsWithoutMeasurements() {
        val kf = KalmanFilter()
        kf.reset(Vec2.ZERO, 4.0)
        val before = kf.positionSigma
        repeat(10) { kf.predict(1.0) }
        assertTrue(kf.positionSigma > 2 * before)
        kf.updatePosition(Vec2.ZERO, 4.0)
        assertTrue(kf.positionSigma < 4.5)
    }

    @Test
    fun zeroVelocityStopsDrift() {
        val kf = KalmanFilter()
        kf.reset(Vec2.ZERO, 5.0, velocity = Vec2(1.0, 0.0))
        repeat(20) { kf.predict(1.0); kf.zeroVelocity() }
        assertTrue(kf.velocity.length() < 0.05)
        assertTrue(kf.position.length() < 2.0)
    }
}
