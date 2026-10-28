package dev.nosferatu.engine.validation

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.sim.Leg
import dev.nosferatu.engine.sim.RunSimulator
import dev.nosferatu.engine.trace.TraceEvent
import dev.nosferatu.engine.trace.TraceFormat
import dev.nosferatu.engine.track.Tracker
import kotlin.test.Test
import kotlin.test.assertTrue

class ReplayPauseTest {
    private val start = LatLon(19.0600, 72.8400)
    private val route = listOf(start, Geo.destination(start, 90.0, 1_500.0))

    @Test
    fun pausedTimeIsLeftOut() {
        val events = RunSimulator(route, listOf(Leg.stand(10), Leg.run(220, 3.3))).generate()
        val t0 = events.first().timeMs
        val pauseMs = t0 + 100_000
        val resumeMs = t0 + 160_000
        // While paused the app records nothing; the runner keeps going for 60 s (about 200 m) meanwhile.
        val paused = events.filter { it.timeMs !in pauseMs until resumeMs } +
            TraceEvent.Note(pauseMs, TraceFormat.PAUSE) + TraceEvent.Note(resumeMs, TraceFormat.RESUME)

        val truths = paused.filterIsInstance<TraceEvent.Truth>().sortedBy { it.timeMs }
        val expected = truths.zipWithNext().filter { (a, b) -> b.timeMs - a.timeMs < 1_500 }.sumOf { (a, b) -> Geo.distance(a.position, b.position) }

        val steps = Replay.run(paused, Tracker())
        val tracked = steps.last().snapshot.distanceM
        assertTrue(kotlin.math.abs(tracked - expected) / expected < 0.05, "tracked $tracked m, expected $expected m")
        assertTrue(steps.none { it.timeMs in pauseMs + 1_000 until resumeMs }, "ticks while paused")
    }
}
