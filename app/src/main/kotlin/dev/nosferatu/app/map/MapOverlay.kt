package dev.nosferatu.app.map

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon

/** A correction worth showing on the map: a GPS fix that was rejected or pulled back. */
data class CorrectionMarker(val position: LatLon, val kind: Kind) {
    enum class Kind { Rejected, DownWeighted }
}

/**
 * Everything Nosferatu draws on top of BombayChitra's map. A plain value: the tracking engine produces it, the
 * renderer draws it, and neither knows about the other.
 */
data class MapOverlayState(
    /** The estimated position and its 1-sigma uncertainty in metres. */
    val position: LatLon? = null,
    val uncertaintyM: Double? = null,
    val headingDeg: Double? = null,
    /** The corrected route so far, one list per continuous stretch. */
    val trail: List<List<LatLon>> = emptyList(),
    /** Raw GPS fixes, shown only with diagnostics on. */
    val rawFixes: List<LatLon> = emptyList(),
    /** The road or path segment the position is matched to, if any. */
    val matchedRoad: List<LatLon> = emptyList(),
    val corrections: List<CorrectionMarker> = emptyList(),
    val diagnostics: Boolean = false,
) {
    companion object {
        val EMPTY = MapOverlayState()
    }
}

/** A polygon approximating a circle of [radiusM] metres, for drawing GPS uncertainty at the right scale. */
fun circlePolygon(center: LatLon, radiusM: Double, points: Int = 48): List<LatLon> =
    (0..points).map { i -> Geo.destination(center, 360.0 * i / points, radiusM) }
