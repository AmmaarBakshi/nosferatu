package dev.nosferatu.engine.geo

import kotlin.math.cos

/**
 * A flat east/north frame in metres around [origin]. The filters work here because a constant-velocity model is
 * linear in metres and not in degrees.
 *
 * It is an equirectangular projection: within 20 km of the origin (a long run) the error is under 0.1 %, far below
 * GPS noise. The tracker re-centres the frame when it drifts further than that.
 */
class LocalFrame(val origin: LatLon) {
    private val metresPerDegLat = Geo.toRadians(1.0) * Geo.EARTH_RADIUS_M
    private val metresPerDegLon = metresPerDegLat * cos(Geo.toRadians(origin.lat))

    fun toLocal(p: LatLon): Vec2 = Vec2((p.lon - origin.lon) * metresPerDegLon, (p.lat - origin.lat) * metresPerDegLat)

    fun toLocal(lat: Double, lon: Double): Vec2 =
        Vec2((lon - origin.lon) * metresPerDegLon, (lat - origin.lat) * metresPerDegLat)

    fun toLatLon(v: Vec2): LatLon = LatLon(origin.lat + v.y / metresPerDegLat, origin.lon + v.x / metresPerDegLon)

    fun toLatLon(x: Double, y: Double): LatLon = LatLon(origin.lat + y / metresPerDegLat, origin.lon + x / metresPerDegLon)
}

/** A 2-D vector in metres: x east, y north. */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    fun dot(o: Vec2) = x * o.x + y * o.y
    fun length() = kotlin.math.hypot(x, y)

    /** Compass heading of this vector, degrees clockwise from north. */
    fun heading(): Double = Geo.normalizeDegrees(Geo.toDegrees(kotlin.math.atan2(x, y)))

    companion object {
        val ZERO = Vec2(0.0, 0.0)

        /** A unit vector pointing along a compass heading. */
        fun ofHeading(deg: Double): Vec2 {
            val r = Geo.toRadians(deg)
            return Vec2(kotlin.math.sin(r), kotlin.math.cos(r))
        }
    }
}
