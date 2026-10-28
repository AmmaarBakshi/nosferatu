package dev.nosferatu.engine.geo

import kotlin.test.Test
import kotlin.test.assertEquals

class GeoTest {
    private val churchgate = LatLon(18.9322, 72.8264)
    private val cst = LatLon(18.9400, 72.8355)

    @Test
    fun distanceMatchesKnownValue() {
        // One degree along a meridian is R·π/180 = 111,195 m.
        assertEquals(111_195.0, Geo.distance(LatLon(19.0, 72.8), LatLon(20.0, 72.8)), 1.0)
        // Churchgate to CST: 867 m north and 958 m east, about 1.29 km.
        assertEquals(1292.0, Geo.distance(churchgate, cst), 3.0)
        assertEquals(0.0, Geo.distance(cst, cst), 1e-9)
    }

    @Test
    fun destinationInvertsDistanceAndBearing() {
        for (bearing in listOf(0.0, 45.0, 137.0, 270.0, 359.0)) {
            val p = Geo.destination(churchgate, bearing, 850.0)
            assertEquals(850.0, Geo.distance(churchgate, p), 0.01)
            assertEquals(0.0, Geo.headingDifference(bearing, Geo.bearing(churchgate, p)), 0.01)
        }
    }

    @Test
    fun headingDifferenceWrapsAround() {
        assertEquals(20.0, Geo.headingDifference(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Geo.headingDifference(10.0, 350.0), 1e-9)
        assertEquals(180.0, Geo.headingDifference(0.0, 180.0), 1e-9)
    }

    @Test
    fun localFrameRoundTripsAndKeepsDistances() {
        val frame = LocalFrame(churchgate)
        val local = frame.toLocal(cst)
        assertEquals(Geo.distance(churchgate, cst), local.length(), 2.0)
        val back = frame.toLatLon(local)
        assertEquals(cst.lat, back.lat, 1e-9)
        assertEquals(cst.lon, back.lon, 1e-9)
        assertEquals(Geo.bearing(churchgate, cst), local.heading(), 0.2)
    }
}
