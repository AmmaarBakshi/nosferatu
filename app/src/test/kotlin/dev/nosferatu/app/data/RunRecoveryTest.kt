package dev.nosferatu.app.data

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.sim.Leg
import dev.nosferatu.engine.sim.RunSimulator
import dev.nosferatu.engine.trace.TraceEvent
import dev.nosferatu.engine.trace.TraceFormat
import java.io.StringWriter
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunRecoveryTest {
    private val start = LatLon(19.0600, 72.8400)
    private val route = listOf(start, Geo.destination(start, 90.0, 1_500.0))

    /** A 4-minute run with a 60 s pause, as the app writes it, cut off mid-line when the process died. */
    private fun killedRun(): Triple<String, List<TraceEvent>, Long> {
        val simulated = RunSimulator(route, listOf(Leg.stand(10), Leg.run(230, 3.3))).generate()
        val t0 = simulated.first().timeMs
        val events = listOf(TraceEvent.Note(t0, "nosferatu run 20261028-234728; map none; gyro true")) +
            simulated.filter { it.timeMs !in t0 + 100_000 until t0 + 160_000 } +
            TraceEvent.Note(t0 + 100_000, TraceFormat.PAUSE) + TraceEvent.Note(t0 + 160_000, TraceFormat.RESUME)
        val sorted = events.sortedBy { it.timeMs }
        val text = StringWriter().also { TraceFormat.writeAll(sorted, it) }.toString()
        return Triple(text.dropLast(7), sorted, t0)
    }

    @Test
    fun aTraceCutOffMidLineLosesOnlyThatLine() {
        val (text, written, _) = killedRun()
        val read = RunRecovery.readTrace(text)
        assertEquals(written.size - 1, read.size)
        assertEquals(written[written.size - 2].timeMs, read.last().timeMs)
    }

    @Test
    fun pausedTimeIsNotCounted() {
        val (_, written, t0) = killedRun()
        assertEquals(written.last().timeMs - t0 - 60_000, RunRecovery.activeMs(written))
    }

    @Test
    fun theRecoveredRunMatchesWhatWasRun() {
        val (text, _, _) = killedRun()
        val events = RunRecovery.readTrace(text)
        val truths = events.filterIsInstance<TraceEvent.Truth>()
        val ran = truths.zipWithNext().filter { (a, b) -> b.timeMs - a.timeMs < 1_500 }.sumOf { (a, b) -> Geo.distance(a.position, b.position) }

        val run = assertNotNull(RunRecovery.recover("20261028-234728", 0, events, network = null))
        assertTrue(run.recovered)
        assertFalse(RunRecovery.usedMap(events))
        assertEquals(RunRecovery.activeMs(events), run.durationMs)
        assertTrue(kotlin.math.abs(run.distanceM - ran) / ran < 0.05, "recovered ${run.distanceM} m of $ran m")
        assertEquals(2, run.track.size, "the pause splits the track")
    }

    @Test
    fun aTraceWithNothingInItRecoversNothing() {
        assertNull(RunRecovery.recover("x", 0, RunRecovery.readTrace(TraceFormat.HEADER + "\n"), network = null))
    }

    @Test
    fun theStartTimeComesFromTheRunId() {
        val c = Calendar.getInstance().apply { timeInMillis = RunRecovery.startedAt("20261028-234728")!! }
        assertEquals(listOf(2026, Calendar.OCTOBER, 28, 23, 47, 28), listOf(c[Calendar.YEAR], c[Calendar.MONTH], c[Calendar.DAY_OF_MONTH], c[Calendar.HOUR_OF_DAY], c[Calendar.MINUTE], c[Calendar.SECOND]))
        assertNull(RunRecovery.startedAt("imported-run"))
    }
}
