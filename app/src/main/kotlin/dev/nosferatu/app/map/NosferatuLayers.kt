package dev.nosferatu.app.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.nosferatu.engine.geo.LatLon
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Colours for the overlay, taken from the Compose theme so the map matches the UI. */
data class OverlayColors(
    val accent: Color,
    val raw: Color,
    val matched: Color,
    val rejected: Color,
    val halo: Color,
)

/**
 * Nosferatu's layers, added on top of every BombayChitra layer (so BombayChitra's look is unchanged underneath).
 * All ids start with `nf-` to keep them apart from BombayChitra's.
 */
class NosferatuLayers(private val colors: OverlayColors) {
    private val sources = listOf(SRC_ACCURACY, SRC_TRAIL, SRC_MATCHED, SRC_RAW, SRC_CORRECTIONS, SRC_POSITION)

    fun install(style: Style) {
        for (id in sources) if (style.getSource(id) == null) style.addSource(GeoJsonSource(id))
        if (style.getLayer("nf-accuracy") != null) return
        style.addLayer(
            FillLayer("nf-accuracy", SRC_ACCURACY).withProperties(fillColor(colors.accent.toArgb()), fillOpacity(0.14f)),
        )
        style.addLayer(
            LineLayer("nf-matched", SRC_MATCHED).withProperties(
                lineColor(colors.matched.toArgb()), lineWidth(9f), lineOpacity(0.45f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer("nf-trail-halo", SRC_TRAIL).withProperties(
                lineColor(colors.halo.toArgb()), lineWidth(7f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer("nf-trail", SRC_TRAIL).withProperties(
                lineColor(colors.accent.toArgb()), lineWidth(4f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            CircleLayer("nf-raw", SRC_RAW).withProperties(
                circleColor(colors.raw.toArgb()), circleRadius(3f), circleOpacity(0.8f),
            ),
        )
        style.addLayer(
            CircleLayer("nf-corrections", SRC_CORRECTIONS).withProperties(
                circleColor(colors.rejected.toArgb()),
                circleRadius(Expression.switchCase(Expression.eq(Expression.get("kind"), "rejected"), Expression.literal(5f), Expression.literal(3.5f))),
                circleStrokeColor(colors.halo.toArgb()), circleStrokeWidth(1.5f),
            ),
        )
        style.addLayer(
            CircleLayer("nf-position", SRC_POSITION).withProperties(
                circleColor(colors.accent.toArgb()), circleRadius(8f),
                circleStrokeColor(Color.White.toArgb()), circleStrokeWidth(3f),
            ),
        )
    }

    fun update(style: Style, state: MapOverlayState) {
        set(style, SRC_POSITION, state.position?.let { listOf(Feature.fromGeometry(it.point())) }.orEmpty())
        set(
            style, SRC_ACCURACY,
            if (state.position != null && state.uncertaintyM != null && state.uncertaintyM > 1) {
                listOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(circlePolygon(state.position, state.uncertaintyM).map { it.point() }))))
            } else {
                emptyList()
            },
        )
        set(style, SRC_TRAIL, state.trail.filter { it.size >= 2 }.map { Feature.fromGeometry(LineString.fromLngLats(it.map { p -> p.point() })) })
        set(
            style, SRC_MATCHED,
            if (state.matchedRoad.size >= 2) listOf(Feature.fromGeometry(LineString.fromLngLats(state.matchedRoad.map { it.point() }))) else emptyList(),
        )
        set(style, SRC_RAW, if (state.diagnostics) state.rawFixes.map { Feature.fromGeometry(it.point()) } else emptyList())
        set(
            style, SRC_CORRECTIONS,
            if (state.diagnostics) {
                state.corrections.map { c ->
                    Feature.fromGeometry(c.position.point()).also { f ->
                        f.addStringProperty("kind", if (c.kind == CorrectionMarker.Kind.Rejected) "rejected" else "down")
                    }
                }
            } else {
                emptyList()
            },
        )
    }

    private fun set(style: Style, id: String, features: List<Feature>) {
        style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun LatLon.point(): Point = Point.fromLngLat(lon, lat)

    private companion object {
        const val SRC_ACCURACY = "nf-accuracy"
        const val SRC_TRAIL = "nf-trail"
        const val SRC_MATCHED = "nf-matched"
        const val SRC_RAW = "nf-raw"
        const val SRC_CORRECTIONS = "nf-corrections"
        const val SRC_POSITION = "nf-position"
    }
}
