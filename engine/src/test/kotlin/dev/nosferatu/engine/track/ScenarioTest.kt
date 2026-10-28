package dev.nosferatu.engine.track

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.model.GpsFix
import dev.nosferatu.engine.road.RoadNetwork
import dev.nosferatu.engine.road.TestNetwork
import dev.nosferatu.engine.sim.GpsErrorModel
import dev.nosferatu.engine.sim.GpsFault
import dev.nosferatu.engine.sim.Leg
import dev.nosferatu.engine.sim.RunSimulator
import dev.nosferatu.engine.trace.TraceEvent
import dev.nosferatu.engine.validation.Metrics
import dev.nosferatu.engine.validation.Replay
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end scenarios from the validation plan: simulated runs replayed through the whole tracker and scored
 * against ground truth. Thresholds are deliberately loose bounds on behaviour (better than raw GPS, no false
 * running…), not accuracy claims; measured numbers go to docs/validation.md.
 */
class ScenarioTest {
    private val origin = LatLon(19.0600, 72.8400)

    /** A 4×4 block grid, 120 m blocks: Bandra-like streets. */
    private val grid: TestNetwork = TestNetwork(origin).apply {
        for (j in 0..4) street(0.0, j * 120.0, 480.0, j * 120.0, "Street $j")
        for (i in 0..4) street(i * 120.0, 0.0, i * 120.0, 480.0, "Avenue $i")
    }

    private fun route(vararg xy: Pair<Double, Double>) = xy.map { (x, y) -> grid.at(x, y) }

    private fun scenario(
        name: String,
        route: List<LatLon>,
        legs: List<Leg>,
        network: RoadNetwork? = grid,
        gps: GpsErrorModel = GpsErrorModel(),
        faults: List<GpsFault> = emptyList(),
        rotations: List<Int> = emptyList(),
        seed: Long = 11,
        tamper: (TraceEvent) -> TraceEvent = { it },
        drop: (TraceEvent) -> Boolean = { false },
        faultEndS: Int? = faults.maxOfOrNull { it.toS },
    ): Metrics {
        val events = RunSimulator(route, legs, gps, faults, seed = seed, phoneRotationsAtS = rotations).generate().filterNot(drop).map(tamper)
        val steps = Replay.run(events, Tracker(network))
        val m = Metrics.of(steps, faultEndS = faultEndS)
        println(
            "%-28s err mean %5.1f p95 %5.1f (raw %5.1f / %5.1f) | dist %7.0f m vs %7.0f (%+.1f%%, raw %+.1f%%) | run P %s R %s | act %3.0f%% | false run %3d s | recovery %s".format(
                name, m.correctedErrorMeanM, m.correctedErrorP95M, m.rawErrorMeanM, m.rawErrorP95M,
                m.trackedDistanceM, m.truthDistanceM, m.distanceErrorPct, m.rawDistanceErrorPct,
                m.runningPrecision?.let { "%.2f".format(it) } ?: "-", m.runningRecall?.let { "%.2f".format(it) } ?: "-",
                100 * m.activityAccuracy, m.falseRunningS, m.recoveryS?.let { "$it s" } ?: "-",
            ),
        )
        return m
    }

    private val loop = route(0.0 to 0.0, 480.0 to 0.0, 480.0 to 480.0, 0.0 to 480.0, 0.0 to 0.0)

    @Test
    fun normalRunOnMappedRoads() {
        val m = scenario("normal run", loop, listOf(Leg.stand(10), Leg.run(520, 3.4), Leg.stand(10)))
        assertTrue(m.correctedErrorMeanM < m.rawErrorMeanM, "corrected must beat raw GPS")
        assertTrue(kotlin.math.abs(m.distanceErrorPct) < 4.0)
        assertTrue(m.runningRecall!! > 0.95)
    }

    @Test
    fun theBoundsHoldAcrossSeeds() {
        // One seed per scenario could be a lucky one: the GPS noise, the gait and the faults vary with the seed.
        val faults = listOf(GpsFault.Jump(100, 104, 80.0, -60.0), GpsFault.Outage(200, 225))
        val runs = (1L..10L).map { seed -> scenario("seed $seed: jump + outage", loop, listOf(Leg.stand(10), Leg.run(400, 3.4)), faults = faults, seed = seed) }
        println(
            "    across seeds: err mean %.1f–%.1f m, p95 %.1f–%.1f m, distance %+.1f%% to %+.1f%%, recall %.2f–%.2f".format(
                runs.minOf { it.correctedErrorMeanM }, runs.maxOf { it.correctedErrorMeanM },
                runs.minOf { it.correctedErrorP95M }, runs.maxOf { it.correctedErrorP95M },
                runs.minOf { it.distanceErrorPct }, runs.maxOf { it.distanceErrorPct },
                runs.minOf { it.runningRecall!! }, runs.maxOf { it.runningRecall!! },
            ),
        )
        for (m in runs) {
            assertTrue(m.correctedErrorMeanM < m.rawErrorMeanM)
            assertTrue(kotlin.math.abs(m.distanceErrorPct) < 5.0, "distance error ${m.distanceErrorPct}%")
            assertTrue(m.runningRecall!! > 0.95)
            assertTrue(m.recoveryS!! <= 10, "recovery ${m.recoveryS} s")
        }
    }

    @Test
    fun briskWalkIsNotCountedAsRunning() {
        val m = scenario("brisk walk 7.2 km/h", loop, listOf(Leg.stand(10), Leg.briskWalk(400, 2.0)))
        assertTrue(m.falseRunningS <= 20, "false running ${m.falseRunningS} s")
    }

    @Test
    fun fastRunningAbove8KmhIsRunning() {
        for (speed in listOf(2.6, 3.8, 5.0)) {
            val m = scenario("run %.1f km/h".format(speed * 3.6), loop, listOf(Leg.stand(10), Leg.run(300, speed)))
            assertTrue(m.runningRecall!! > 0.9, "recall at ${speed * 3.6} km/h was ${m.runningRecall}")
        }
    }

    @Test
    fun aStuckDopplerBearingDoesNotDragTheTrack() {
        // Some chipsets (and the Android emulator) report a speed with a bearing that never changes.
        val stuck: (TraceEvent) -> TraceEvent = { e ->
            if (e is TraceEvent.Gps && e.fix.speedMps != null) TraceEvent.Gps(e.fix.copy(bearingDeg = 0.0)) else e
        }
        val honest = scenario("honest bearing", loop, listOf(Leg.stand(10), Leg.run(520, 3.4)))
        val m = scenario("bearing stuck at 0°", loop, listOf(Leg.stand(10), Leg.run(520, 3.4)), tamper = stuck)
        assertTrue(m.correctedErrorMeanM < m.rawErrorMeanM, "corrected ${m.correctedErrorMeanM} m vs raw ${m.rawErrorMeanM} m")
        assertTrue(m.correctedErrorMeanM < honest.correctedErrorMeanM + 1.5, "stuck bearing costs too much accuracy")
        assertTrue(kotlin.math.abs(m.distanceErrorPct) < 4.0, "distance error ${m.distanceErrorPct}%")
    }

    @Test
    fun aWrongFirstFixDoesNotLockOutTheRealOnes() {
        // The first three fixes of a run come from 350 m away (a stale position from before the run).
        var fixes = 0
        val wrongStart: (TraceEvent) -> TraceEvent = { e ->
            if (e is TraceEvent.Gps && fixes++ < 3) {
                val p = Geo.destination(e.fix.position, 45.0, 350.0)
                TraceEvent.Gps(e.fix.copy(lat = p.lat, lon = p.lon))
            } else {
                e
            }
        }
        val m = scenario("wrong first fixes 350 m off", loop, listOf(Leg.run(400, 3.2)), tamper = wrongStart, faultEndS = 3)
        assertTrue(m.recoveryS != null && m.recoveryS!! <= 10, "back on the real track after ${m.recoveryS} s")
        assertTrue(kotlin.math.abs(m.distanceErrorPct) < 5.0, "distance error ${m.distanceErrorPct}%")
    }

    @Test
    fun frozenFixesWhileRunningAreBridgedNotBelieved() {
        // For 20 fixes the receiver repeats one fix (speed and all) while the runner keeps going.
        var fixes = 0
        var frozen: GpsFix? = null
        val freeze: (TraceEvent) -> TraceEvent = { e ->
            if (e is TraceEvent.Gps && fixes++ in 100 until 120) {
                val f = frozen ?: e.fix.also { frozen = it }
                TraceEvent.Gps(f.copy(timeMs = e.timeMs))
            } else {
                e
            }
        }
        val m = scenario("GPS frozen for 20 s", loop, listOf(Leg.stand(10), Leg.run(300, 3.2)), tamper = freeze)
        assertTrue(kotlin.math.abs(m.distanceErrorPct) < 4.0, "distance error ${m.distanceErrorPct}%")
        assertTrue(m.correctedErrorP95M < 15.0, "p95 ${m.correctedErrorP95M} m")
    }

    @Test
    fun suddenGpsJumpsAreRejected() {
        val m = scenario(
            "GPS jumps", loop, listOf(Leg.stand(10), Leg.run(400, 3.4)),
            faults = listOf(GpsFault.Jump(100, 104, 80.0, -60.0), GpsFault.Jump(250, 253, -120.0, 40.0)),
        )
        assertTrue(m.correctedErrorP95M < m.rawErrorP95M)
        assertTrue(m.distanceErrorPct < 5.0, "jumps inflated distance by ${m.distanceErrorPct}%")
    }

    @Test
    fun stationaryDriftAddsNoDistance() {
        val m = scenario("standing still, drifting GPS", loop, listOf(Leg.stand(300)), gps = GpsErrorModel(noiseM = 4.0, biasM = 8.0, biasTauS = 20.0))
        assertTrue(m.trackedDistanceM < 10.0, "standing still for 5 min counted ${m.trackedDistanceM} m (raw GPS: ${m.rawDistanceM} m)")
        assertTrue(m.falseRunningS == 0)
    }

    @Test
    fun temporaryGpsLossIsBridgedBySteps() {
        val m = scenario(
            "GPS outage 25 s", route(0.0 to 0.0, 480.0 to 0.0, 480.0 to 480.0), listOf(Leg.stand(10), Leg.run(280, 3.3)),
            faults = listOf(GpsFault.Outage(90, 115)),
        )
        assertTrue(m.correctedErrorP95M < 30.0, "p95 ${m.correctedErrorP95M} m through the outage")
        assertTrue(m.recoveryS!! <= 10)
        assertTrue(kotlin.math.abs(m.distanceErrorPct) < 6.0)
    }

    @Test
    fun aPhoneWithoutAGyroscopeStillTracks() {
        // Budget phones often ship without a gyroscope. The outage covers the corner at 480 m, where a turn rate matters.
        val corner = route(0.0 to 0.0, 480.0 to 0.0, 480.0 to 480.0)
        val legs = listOf(Leg.stand(10), Leg.run(280, 3.3))
        val outage = listOf(GpsFault.Outage(140, 165))
        val withGyro = scenario("outage at a corner", corner, legs, faults = outage)
        val m = scenario("outage at a corner, no gyro", corner, legs, faults = outage, drop = { it is TraceEvent.Gyro })
        // The same without the map, for the report: a measurement, not a requirement.
        scenario("  … and no map either", corner, legs, network = null, faults = outage, drop = { it is TraceEvent.Gyro })
        println("    (with gyroscope p95 ${"%.1f".format(withGyro.correctedErrorP95M)} m)")
        assertTrue(m.runningRecall!! > 0.9, "recall ${m.runningRecall}")
        assertTrue(kotlin.math.abs(m.distanceErrorPct) < 6.0, "distance error ${m.distanceErrorPct}%")
        assertTrue(m.recoveryS!! <= 10, "recovery ${m.recoveryS} s")
    }

    @Test
    fun parallelRoadsAndJunctions() {
        val zigzag = route(0.0 to 0.0, 120.0 to 0.0, 120.0 to 120.0, 240.0 to 120.0, 240.0 to 240.0, 360.0 to 240.0, 360.0 to 360.0, 480.0 to 360.0)
        val m = scenario("zigzag through junctions", zigzag, listOf(Leg.stand(10), Leg.run(260, 3.3)), gps = GpsErrorModel(noiseM = 6.0, biasM = 6.0, reportedAccuracyM = 9.0))
        assertTrue(m.correctedErrorMeanM < m.rawErrorMeanM)
    }

    @Test
    fun unmappedPathIsNotSnappedToARoad() {
        // A diagonal path through the middle of the blocks: mapped nowhere.
        val diagonal = route(10.0 to 10.0, 470.0 to 470.0)
        val m = scenario("unmapped diagonal path", diagonal, listOf(Leg.stand(10), Leg.run(200, 3.2)))
        assertTrue(m.correctedErrorMeanM <= m.rawErrorMeanM * 1.1, "map made the off-road track worse: ${m.correctedErrorMeanM} vs raw ${m.rawErrorMeanM}")
    }

    @Test
    fun sharpTurnsAndStops() {
        val m = scenario(
            // 960 m of route for 720 m of legs: the runner must never be pinned at the route's end while "running".
            "sharp turns and stops", route(0.0 to 0.0, 240.0 to 0.0, 240.0 to 120.0, 120.0 to 120.0, 120.0 to 240.0, 480.0 to 240.0),
            listOf(Leg.stand(10), Leg.run(80, 3.4), Leg.stand(20), Leg.run(60, 3.4), Leg.walk(30), Leg.stand(15), Leg.run(60, 3.4)),
        )
        assertTrue(m.correctedErrorMeanM < m.rawErrorMeanM * 1.1)
        assertTrue(kotlin.math.abs(m.distanceErrorPct) < 8.0)
    }

    @Test
    fun phoneOrientationChanges() {
        val m = scenario("phone rotated mid-run", loop, listOf(Leg.stand(10), Leg.run(300, 3.4)), rotations = listOf(60, 120, 200))
        assertTrue(m.runningRecall!! > 0.9)
    }

    @Test
    fun worksWithoutMapData() {
        val m = scenario("no map data", loop, listOf(Leg.stand(10), Leg.run(300, 3.4)), network = null)
        assertTrue(m.correctedErrorMeanM < m.rawErrorMeanM * 1.05)
        assertTrue(m.runningRecall!! > 0.9)
    }

    @Test
    fun vehicleRideIsNotRunning() {
        val m = scenario("bus ride", loop, listOf(Leg.stand(10), Leg.ride(150, 8.0)))
        assertTrue(m.falseRunningS <= 5, "a bus ride counted ${m.falseRunningS} s of running")
        assertTrue(m.trackedDistanceM < 50.0, "a bus ride added ${m.trackedDistanceM} m of on-foot distance")
    }
}
