package dev.nosferatu.engine.motion

import dev.nosferatu.engine.sim.Gait
import dev.nosferatu.engine.sim.ImuSimulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InertialProcessorTest {
    private fun run(sim: ImuSimulator, gait: Gait, seconds: Int, p: InertialProcessor = InertialProcessor(), start: Long = 0, turn: Double = 0.0, roll: Double = 0.0): InertialProcessor {
        sim.generate(start, seconds * 1000L, gait, turnRateDps = turn, rollRateDps = roll) { a, g ->
            a?.let(p::onAccel); g?.let(p::onGyro)
        }
        return p
    }

    /** Phone orientations: flat on a table, upright in a front pocket, tilted in a hand, sideways in an armband. */
    private val orientations = listOf(0.0 to 0.0, 90.0 to 0.0, 35.0 to -20.0, 0.0 to 90.0)

    @Test
    fun walkingCadenceInAnyOrientation() {
        for ((roll, pitch) in orientations) {
            val p = run(ImuSimulator(seed = 3, rollDeg = roll, pitchDeg = pitch), Gait.WALK, 30)
            val f = p.features(30_000)
            assertNotNull(f.cadenceSpm, "orientation $roll/$pitch")
            assertEquals(108.0, f.cadenceSpm!!, 5.0, "orientation $roll/$pitch")
            assertEquals(54.0, p.features(30_000).stepsTotal.toDouble(), 4.0)
            assertTrue(f.cadenceRegularity > 0.6)
        }
    }

    @Test
    fun runningCadenceAndImpactAreHigher() {
        val walk = run(ImuSimulator(seed = 4, rollDeg = 80.0), Gait.WALK, 20).features(20_000)
        val runF = run(ImuSimulator(seed = 4, rollDeg = 80.0), Gait.RUN, 20).features(20_000)
        assertEquals(172.0, runF.cadenceSpm!!, 7.0)
        assertTrue(runF.verticalRms > 2.5 * walk.verticalRms, "run ${runF.verticalRms} vs walk ${walk.verticalRms}")
        assertTrue(runF.stepPeak!! > 2 * walk.stepPeak!!)
    }

    @Test
    fun stillAndVehicleProduceNoCadence() {
        val still = run(ImuSimulator(seed = 5, rollDeg = 10.0), Gait.STILL, 10).features(10_000)
        assertTrue(still.still)
        assertNull(still.cadenceSpm)
        assertEquals(0, still.stepsTotal)
        val car = run(ImuSimulator(seed = 6, rollDeg = 60.0), Gait.VEHICLE, 30).features(30_000)
        assertFalse(car.still)
        assertNull(car.cadenceSpm, "vehicle vibration must not look like steps")
    }

    @Test
    fun cadenceDisappearsWhenSteppingStops() {
        val sim = ImuSimulator(seed = 7, rollDeg = 90.0)
        val p = run(sim, Gait.JOG, 15)
        assertNotNull(p.features(15_000).cadenceSpm)
        run(sim, Gait.STILL, 6, p, start = 15_000)
        assertNull(p.features(21_000).cadenceSpm)
        assertTrue(p.features(21_000).still)
    }

    @Test
    fun gaitChangesFromRunToWalkAreFollowed() {
        val sim = ImuSimulator(seed = 8, rollDeg = 90.0)
        val p = run(sim, Gait.RUN, 15)
        run(sim, Gait.WALK, 15, p, start = 15_000)
        assertEquals(108.0, p.features(30_000).cadenceSpm!!, 6.0)
    }

    @Test
    fun turnRateIsIndependentOfOrientationOnceBiasIsKnown() {
        for ((roll, pitch) in orientations) {
            val sim = ImuSimulator(seed = 9, rollDeg = roll, pitchDeg = pitch)
            val p = run(sim, Gait.STILL, 5) // learn the gyroscope bias
            assertTrue(p.features(5_000).gyroCalibrated)
            p.takeHeadingChangeDeg()
            run(sim, Gait.WALK, 10, p, start = 5_000, turn = 9.0) // a 90° right turn over 10 s while walking
            assertEquals(90.0, p.takeHeadingChangeDeg(), 6.0, "orientation $roll/$pitch")
        }
    }

    @Test
    fun stepsSurviveThePhoneRotatingInTheHand() {
        val sim = ImuSimulator(seed = 10)
        val p = InertialProcessor()
        run(sim, Gait.JOG, 10, p)
        run(sim, Gait.JOG, 4, p, start = 10_000, roll = 25.0) // turning the phone 100° mid-run
        run(sim, Gait.JOG, 6, p, start = 14_000)
        val f = p.features(20_000)
        assertEquals(158.0, f.cadenceSpm!!, 7.0)
        assertEquals(158.0 / 60 * 20, f.stepsTotal.toDouble(), 6.0)
    }
}
