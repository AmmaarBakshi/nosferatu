package dev.nosferatu.app.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.nosferatu.engine.geo.LatLon
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * BombayChitra's map, drawn natively by MapLibre from BombayChitra's own style and tiles, with Nosferatu's overlay
 * on top.
 *
 * @param styleJson the rewritten BombayChitra style (see [MapStyleProvider]).
 * @param follow keep the camera on the estimated position.
 */
@Composable
fun BombayChitraMap(
    styleJson: String,
    overlay: MapOverlayState,
    colors: OverlayColors,
    follow: Boolean,
    modifier: Modifier = Modifier,
    onUserGesture: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val layers = remember(colors) { NosferatuLayers(colors) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    DisposableEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isAttributionEnabled = false // Shown by the app with BombayChitra's credit instead.
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isCompassEnabled = true
            m.cameraPosition = CameraPosition.Builder()
                // BombayChitra's own default centre (meta.center), not the middle of the region's bounding box.
                .target(LatLng(MUMBAI.lat, MUMBAI.lon))
                .zoom(11.0)
                .build()
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserGesture()
            }
            map = m
        }
        onDispose { }
    }

    LaunchedEffect(map, styleJson, layers) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromJson(styleJson)) { loaded ->
            layers.install(loaded)
            style = loaded
        }
    }

    LaunchedEffect(style, overlay) {
        val s = style ?: return@LaunchedEffect
        if (s.isFullyLoaded) layers.update(s, overlay)
    }

    LaunchedEffect(map, follow, overlay.position) {
        val m = map ?: return@LaunchedEffect
        val p = overlay.position ?: return@LaunchedEffect
        if (!follow) return@LaunchedEffect
        val zoom = m.cameraPosition.zoom.coerceAtLeast(15.5)
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), zoom), 600)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private val MUMBAI = LatLon(19.076, 72.8777)
