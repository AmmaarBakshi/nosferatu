package dev.nosferatu.engine.track

import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.model.GpsFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackerEventCountTest {
    @Test
    fun countsCoverTheWholeRunNotJustRetainedEvents() {
        val t = Tracker()
        val p = LatLon(19.06, 72.84)
        // A phone indoors for over ten minutes: every fix is too inaccurate to use.
        val n = 700
        for (i in 0 until n) t.onGps(GpsFix(i * 1000L, p.lat, p.lon, 80.0))
        assertEquals(n, t.eventCount(CorrectionEvent.Kind.Rejected))
        assertTrue(t.events.size < n, "events should be capped, got ${t.events.size}")
    }
}
