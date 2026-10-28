package dev.nosferatu.engine.track

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.model.GpsFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackerResumeTest {
    private val a = LatLon(19.06, 72.84)

    private fun walk(t: Tracker, from: LatLon, startMs: Long, seconds: Int) {
        for (i in 0 until seconds) {
            val p = Geo.destination(from, 90.0, 1.4 * i)
            t.onGps(GpsFix(startMs + i * 1000L, p.lat, p.lon, 4.0, 1.4, 0.3, 90.0))
        }
    }

    @Test
    fun resumingFarAwayAddsNoDistanceAndStartsANewStretch() {
        val t = Tracker()
        walk(t, a, 0, 60)
        val before = t.tick(60_000).distanceM
        t.resume()
        // Five minutes later, 2 km away (the runner took a train, with tracking paused).
        val b = Geo.destination(a, 0.0, 2_000.0)
        walk(t, b, 360_000, 30)
        val after = t.tick(390_000)
        assertTrue(after.distanceM - before < 50.0, "added ${after.distanceM - before} m across the pause")
        assertEquals(2, t.trail.size)
        assertTrue(Geo.distance(after.position!!, Geo.destination(b, 90.0, 1.4 * 29)) < 10.0)
    }
}
