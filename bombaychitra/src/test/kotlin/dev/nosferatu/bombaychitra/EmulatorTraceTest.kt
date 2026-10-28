package dev.nosferatu.bombaychitra

import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.road.RoadNetwork
import dev.nosferatu.engine.track.PositionSource
import dev.nosferatu.engine.track.Tracker
import dev.nosferatu.engine.trace.TraceFormat
import dev.nosferatu.engine.validation.Replay
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Replays the run the app recorded on an emulator along Hill Road (see traces/README.md). About 450 m were driven;
 * before the stuck-bearing, re-anchoring and stale-fix fixes the app measured 330 m.
 */
class EmulatorTraceTest {
    private val trace = File("../traces/emulator-hill-road.txt")
    private val drivenM = 450.0

    private fun replay(network: RoadNetwork?): Pair<Double, Int> {
        val events = trace.bufferedReader().use { TraceFormat.read(it) }
        val steps = Replay.run(events, Tracker(network))
        // The runner starts ~12 s into the trace; the first fixes there are the wrong, pre-run position.
        val firstMoving = steps.indexOfFirst { it.snapshot.distanceM > 0 }
        return steps.last().snapshot.distanceM to firstMoving
    }

    @Test
    fun withoutTheMap() {
        val (distance, firstMoving) = replay(null)
        assertTrue(distance in drivenM * 0.95..drivenM * 1.08, "distance $distance m")
        assertTrue(firstMoving in 10..20, "distance starts counting at $firstMoving s")
    }

    @Test
    fun withBombayChitrasGraph() {
        val file = JdbcBlobSource.locate()
        assumeTrue("BombayChitra graph not extracted; skipping", file != null)
        val adapter = JdbcBlobSource(file!!).use { BombayChitraAdapter.load(it) }
        // The route is inside BombayChitra's coverage, on a mapped road.
        assertTrue(adapter.covers(LatLon(19.0548, 72.8270)))
        val (distance, firstMoving) = replay(adapter)
        assertTrue(distance in drivenM * 0.95..drivenM * 1.08, "distance $distance m")
        assertTrue(firstMoving in 10..20, "distance starts counting at $firstMoving s")
    }

    @Test
    fun theOutageIsBridgedByDeadReckoning() {
        val events = trace.bufferedReader().use { TraceFormat.read(it) }
        val sources = Replay.run(events, Tracker()).map { it.snapshot.source }
        assertTrue(sources.count { it == PositionSource.DeadReckoning } >= 10, "dead reckoning ${sources.count { it == PositionSource.DeadReckoning }} s")
    }
}
