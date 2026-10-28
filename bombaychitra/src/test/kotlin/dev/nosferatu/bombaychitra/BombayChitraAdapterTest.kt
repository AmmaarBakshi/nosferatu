package dev.nosferatu.bombaychitra

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BombayChitraAdapterTest {
    private val origin = LatLon(19.0600, 72.8400)
    private val adapter = BombayChitraAdapter.load(GraphFixture.grid(origin, n = 4).source())
    private fun at(eastM: Double, northM: Double) = Geo.destination(Geo.destination(origin, 90.0, eastM), 0.0, northM)

    @Test
    fun lookupIsHonestAboutWhatItKnows() {
        assertIs<MapLookup.OutsideCoverage>(adapter.lookup(LatLon(28.61, 77.21))) // Delhi
        assertIs<MapLookup.NoWayNearby>(adapter.lookup(at(50.0, 50.0), radiusM = 20.0))
        val on = adapter.lookup(at(40.0, 3.0))
        assertIs<MapLookup.OnWay>(on)
        assertEquals("Row 0", on.name)
        // At a junction, two different streets are equally close.
        assertIs<MapLookup.Ambiguous>(adapter.lookup(at(102.0, 102.0)))
    }

    @Test
    fun movementAlongTheStreetsIsPlausible() {
        val m = adapter.plausibleMove(at(50.0, 0.0), at(100.0, 50.0), elapsedS = 30.0)
        assertTrue(m.plausible)
        assertEquals(100.0, m.networkM!!, 1.5)
    }

    @Test
    fun teleportingIsNot() {
        // 300 m in 5 s is 60 m/s.
        val m = adapter.plausibleMove(at(0.0, 0.0), at(300.0, 0.0), elapsedS = 5.0)
        assertFalse(m.plausible)
    }
}
