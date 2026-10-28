package dev.nosferatu.engine.activity

import dev.nosferatu.engine.motion.InertialProcessor
import dev.nosferatu.engine.sim.Gait
import dev.nosferatu.engine.sim.ImuSimulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityClassifierTest {
    /** Simulates [seconds] of a gait, classifying once a second; returns the per-second activities. */
    private fun simulate(
        gait: Gait,
        seconds: Int,
        speed: Double?,
        classifier: ActivityClassifier = ActivityClassifier(),
        imu: InertialProcessor = InertialProcessor(),
        sim: ImuSimulator = ImuSimulator(seed = 21, rollDeg = 85.0),
        start: Long = 0,
    ): List<ActivityEstimate> {
        val out = ArrayList<ActivityEstimate>()
        for (s in 0 until seconds) {
            val t0 = start + s * 1000L
            sim.generate(t0, 1000, gait) { a, g -> a?.let(imu::onAccel); g?.let(imu::onGyro) }
            out += classifier.update(imu.features(t0 + 1000), speed, if (speed != null) 0.3 else null)
        }
        return out
    }

    private fun List<ActivityEstimate>.settled() = drop(8)

    @Test
    fun stationary() {
        val r = simulate(Gait.STILL, 20, speed = 0.1).settled()
        assertTrue(r.all { it.activity == Activity.Stationary }, r.map { it.activity }.toString())
    }

    @Test
    fun walking() {
        val r = simulate(Gait.WALK, 25, speed = 1.3).settled()
        assertTrue(r.all { it.activity == Activity.Walking }, r.map { it.activity }.toString())
    }

    @Test
    fun briskWalkAtSlowJogSpeedIsStillWalking() {
        // 7.6 km/h: as fast as an easy jog, but walking cadence and soft landings.
        val r = simulate(Gait.BRISK_WALK, 25, speed = 2.1).settled()
        val walking = r.count { it.activity == Activity.Walking }
        assertTrue(walking >= r.size * 0.9, r.map { it.activity }.toString())
        assertTrue(r.none { it.isRunning })
    }

    @Test
    fun runningAbove8KmhIsRunning() {
        for (speed in listOf(2.5, 3.2, 4.2, 5.5)) { // 9, 11.5, 15 and 20 km/h
            val r = simulate(Gait.RUN, 25, speed = speed).settled()
            assertTrue(r.all { it.isRunning }, "at ${speed * 3.6} km/h: ${r.map { it.activity }}")
        }
        val fast = simulate(Gait.RUN, 25, speed = 4.2).settled()
        assertTrue(fast.count { it.activity == Activity.Running } >= fast.size * 0.9)
    }

    @Test
    fun easyPaceRunningGaitIsJogging() {
        val r = simulate(Gait.JOG, 25, speed = 2.2).settled()
        assertTrue(r.count { it.activity == Activity.Jogging } >= r.size * 0.8, r.map { it.activity }.toString())
    }

    @Test
    fun runningWithoutGpsIsStillRecognisedFromGait() {
        val r = simulate(Gait.RUN, 25, speed = null).settled()
        assertTrue(r.all { it.isRunning }, r.map { it.activity }.toString())
    }

    @Test
    fun fastWithoutStepsIsNotRunning() {
        val r = simulate(Gait.VEHICLE, 25, speed = 9.0).settled()
        assertTrue(r.none { it.isRunning }, r.map { it.activity }.toString())
        assertTrue(r.count { it.activity == Activity.Unknown } >= r.size * 0.9)
    }

    @Test
    fun aSingleOddSecondDoesNotFlipTheState() {
        val c = ActivityClassifier()
        val imu = InertialProcessor()
        val sim = ImuSimulator(seed = 22, rollDeg = 85.0)
        simulate(Gait.RUN, 20, 3.5, c, imu, sim)
        // One second of a standing reading (as if GPS briefly reported zero speed) in the middle of a run.
        val glitch = c.update(imu.features(20_000), 0.0, 0.3)
        assertTrue(glitch.isRunning, "one zero-speed fix flipped the state to ${glitch.activity}")
    }

    @Test
    fun transitionsTakeAFewSeconds() {
        val c = ActivityClassifier()
        val imu = InertialProcessor()
        val sim = ImuSimulator(seed = 23, rollDeg = 85.0)
        simulate(Gait.WALK, 20, 1.3, c, imu, sim)
        val after = simulate(Gait.RUN, 15, 3.3, c, imu, sim, start = 20_000)
        val firstRunning = after.indexOfFirst { it.isRunning }
        assertTrue(firstRunning in 1..6, "switched after $firstRunning s: ${after.map { it.activity }}")
        assertTrue(after.last().isRunning)
    }

    @Test
    fun exposesEvidence() {
        val last = simulate(Gait.RUN, 12, speed = 3.4).last()
        val labels = last.evidence.map { it.label }
        assertTrue("Cadence" in labels && "Landing impact" in labels && "GPS speed" in labels, labels.toString())
        assertEquals(1.0, last.posterior.values.sum(), 1e-6)
    }
}
