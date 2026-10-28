package dev.nosferatu.app.ui.run

import dev.nosferatu.app.map.CorrectionMarker
import dev.nosferatu.app.tracking.LiveState
import dev.nosferatu.app.tracking.SessionStatus
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.matching.MatchResult
import dev.nosferatu.engine.matching.MatchState
import dev.nosferatu.engine.model.GpsFix
import dev.nosferatu.engine.track.CorrectionEvent
import dev.nosferatu.engine.track.TrackSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunPresentationTest {
    private val here = LatLon(19.0596, 72.8295)
    private val live = LiveState(
        status = SessionStatus.Tracking,
        snapshot = TrackSnapshot.empty(0).copy(position = here, uncertaintyM = 4.0),
        trail = listOf(listOf(here, LatLon(19.0600, 72.8295))),
        rawFixes = listOf(here),
        events = listOf(
            CorrectionEvent(1, here, CorrectionEvent.Kind.Rejected),
            CorrectionEvent(2, here, CorrectionEvent.Kind.RoadPull),
            CorrectionEvent(3, here, CorrectionEvent.Kind.DownWeighted),
        ),
        matchedRoad = listOf(here, LatLon(19.0600, 72.8295)),
    )

    @Test
    fun `idle overlay shows the raw fix and its accuracy`() {
        val o = RunPresentation.idleOverlay(GpsFix(0, here.lat, here.lon, 6.0))
        assertEquals(here, o.position)
        assertEquals(6.0, o.uncertaintyM)
        assertTrue(o.trail.isEmpty())
    }

    @Test
    fun `diagnostics are hidden by default`() {
        val o = RunPresentation.runOverlay(live, diagnostics = false)
        assertEquals(here, o.position)
        assertEquals(1, o.trail.size)
        assertTrue(o.rawFixes.isEmpty())
        assertTrue(o.corrections.isEmpty())
        assertTrue(o.matchedRoad.isEmpty())
    }

    @Test
    fun `diagnostics show raw fixes, corrections and the matched way`() {
        val o = RunPresentation.runOverlay(live, diagnostics = true)
        assertEquals(1, o.rawFixes.size)
        assertEquals(2, o.matchedRoad.size)
        // Road pulls are routine and would cover the trail; only rejected and down-weighted fixes are marked.
        assertEquals(listOf(CorrectionMarker.Kind.Rejected, CorrectionMarker.Kind.DownWeighted), o.corrections.map { it.kind })
    }

    @Test
    fun `match labels`() {
        assertEquals("On way" to "Hill Road", RunPresentation.matchLabel(MatchResult(MatchState.Matched, null, 0.9, 3, "Hill Road")))
        assertEquals("Unmapped path" to null, RunPresentation.matchLabel(MatchResult(MatchState.OffNetwork, null, 0.8, 2, null)))
        assertEquals("Outside map", RunPresentation.matchLabel(MatchResult.NO_COVERAGE).first)
        assertEquals("No map", RunPresentation.matchLabel(null).first)
    }

    @Test
    fun `average speed waits for enough data`() {
        assertNull(RunPresentation.averageSpeed(10.0, 60_000))
        assertNull(RunPresentation.averageSpeed(500.0, 5_000))
        assertEquals(2.5, RunPresentation.averageSpeed(1000.0, 400_000)!!, 1e-9)
    }
}
