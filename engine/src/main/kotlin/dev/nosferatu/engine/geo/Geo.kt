package dev.nosferatu.engine.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 position in degrees. */
data class LatLon(val lat: Double, val lon: Double) {
    override fun toString(): String = "%.6f,%.6f".format(lat, lon)
}

object Geo {
    /** Mean Earth radius in metres; the same constant BombayChitra's router uses. */
    const val EARTH_RADIUS_M = 6_371_008.8

    fun toRadians(deg: Double): Double = deg * PI / 180.0
    fun toDegrees(rad: Double): Double = rad * 180.0 / PI

    /** Great-circle distance in metres. */
    fun distance(a: LatLon, b: LatLon): Double = distance(a.lat, a.lon, b.lat, b.lon)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = toRadians(lat2 - lat1)
        val dLon = toRadians(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(toRadians(lat1)) * cos(toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** Initial bearing from [a] to [b], degrees clockwise from north in [0, 360). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = toRadians(a.lat)
        val p2 = toRadians(b.lat)
        val dl = toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalizeDegrees(toDegrees(atan2(y, x)))
    }

    /** The point [distanceM] metres from [from] along [bearingDeg]. */
    fun destination(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val d = distanceM / EARTH_RADIUS_M
        val b = toRadians(bearingDeg)
        val p1 = toRadians(from.lat)
        val l1 = toRadians(from.lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLon(toDegrees(p2), toDegrees(l2))
    }

    fun normalizeDegrees(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Smallest signed difference [b] − [a] between two headings, in (−180, 180]. */
    fun headingDifference(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d <= -180) d += 360.0
        return d
    }
}
