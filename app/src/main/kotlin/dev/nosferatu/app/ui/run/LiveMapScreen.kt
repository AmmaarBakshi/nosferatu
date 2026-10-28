package dev.nosferatu.app.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.nosferatu.app.container
import dev.nosferatu.app.data.TilesState
import dev.nosferatu.app.map.BombayChitraMap
import dev.nosferatu.app.map.MapOverlayState
import dev.nosferatu.app.map.OverlayColors
import dev.nosferatu.app.ui.component.EmptyState
import dev.nosferatu.app.ui.component.MapPanel
import dev.nosferatu.app.ui.component.MetricTile
import dev.nosferatu.app.ui.component.StatePill
import dev.nosferatu.app.ui.format.NfFormat
import dev.nosferatu.app.ui.theme.NfTheme
import dev.nosferatu.bombaychitra.MapTheme
import dev.nosferatu.engine.model.GpsFix

/** The map with the raw GPS position: the minimum viable BombayChitra integration. */
@Composable
fun LiveMapScreen(onOpenMapData: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContext.current.container
    val tiles by container.mapData.tiles.collectAsState()
    val permissions = rememberTrackingPermissions()
    var fix by remember { mutableStateOf<GpsFix?>(null) }
    var follow by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { container.mapData.refresh() }
    LaunchedEffect(permissions.granted) {
        if (permissions.granted) container.gps.fixes().collect { fix = it }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val ready = tiles as? TilesState.Ready
        if (ready == null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState("No map yet", "Download BombayChitra's map of the MMR to start.")
                Button(onClick = onOpenMapData) { Text("Get the map") }
            }
        } else {
            val style = remember(ready.file) { container.mapStyles.offlineStyle(MapTheme.Dark, ready.file) }
            val f = fix
            BombayChitraMap(
                styleJson = style,
                overlay = MapOverlayState(position = f?.position, uncertaintyM = f?.accuracyM),
                colors = overlayColors(),
                follow = follow,
                onUserGesture = { follow = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(Modifier.statusBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                !permissions.granted -> StatePill("No location", NfTheme.colors.bad)
                fix == null -> StatePill("Waiting for GPS", NfTheme.colors.moderate)
                else -> StatePill("GPS", NfTheme.colors.good, detail = "±" + NfFormat.metres(fix?.accuracyM))
            }
        }

        MapPanel(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            if (!permissions.granted) {
                Text("Nosferatu needs your location to show you on the map.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = permissions.request) { Text("Allow location") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricTile("Speed", NfFormat.kmh(fix?.speedMps), detail = "km/h")
                    MetricTile("Accuracy", NfFormat.metres(fix?.accuracyM))
                    MetricTile("Follow", if (follow) "ON" else "OFF")
                }
                if (!follow) Button(onClick = { follow = true }) { Text("Recentre") }
            }
        }
    }
}

@Composable
fun overlayColors() = OverlayColors(
    accent = MaterialTheme.colorScheme.primary,
    raw = NfTheme.colors.rawGps,
    matched = NfTheme.colors.matchedRoad,
    rejected = NfTheme.colors.moderate,
    halo = MaterialTheme.colorScheme.background,
)
