package dev.nosferatu.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.nosferatu.app.container
import dev.nosferatu.app.data.TilesState
import dev.nosferatu.app.map.BombayChitraMap
import dev.nosferatu.app.map.MapOverlayState
import dev.nosferatu.app.ui.component.EmptyState
import dev.nosferatu.app.ui.component.EvidenceRow
import dev.nosferatu.app.ui.component.MetricTile
import dev.nosferatu.app.ui.component.NfCard
import dev.nosferatu.app.ui.component.SectionLabel
import dev.nosferatu.app.ui.format.NfFormat
import dev.nosferatu.app.ui.run.overlayColors
import dev.nosferatu.app.ui.theme.NfTheme
import dev.nosferatu.bombaychitra.MapTheme
import dev.nosferatu.engine.geo.LatLon
import kotlinx.coroutines.launch
import java.util.Date

/** One finished run: the corrected route on BombayChitra's map, the numbers, and what was corrected. */
@Composable
fun RunDetailScreen(id: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContext.current.container
    val runs by container.runs.runs.collectAsState()
    val tiles by container.mapData.tiles.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (runs.isEmpty()) container.runs.refresh() }
    val run = runs.firstOrNull { it.id == id }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Column {
                SectionLabel("Run")
                Text(run?.let { DATE.format(Date(it.startedAtWallMs)) } ?: id, style = MaterialTheme.typography.titleLarge)
            }
        }
        if (run == null) {
            EmptyState("Run not found", "It may have been deleted.")
            return@Column
        }

        val track = remember(run) { run.track.map { stretch -> stretch.map { LatLon(it[0], it[1]) } } }
        val ready = tiles as? TilesState.Ready
        Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(20.dp))) {
            if (ready != null && track.any { it.isNotEmpty() }) {
                val style = remember(ready.file) { container.mapStyles.offlineStyle(MapTheme.Dark, ready.file, quiet = true) }
                BombayChitraMap(
                    styleJson = style,
                    overlay = MapOverlayState(trail = track),
                    colors = overlayColors(),
                    follow = false,
                    frame = track.flatten(),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                EmptyState(
                    if (ready == null) "No map" else "No route",
                    if (ready == null) "Download BombayChitra's map to see the route." else "This run has no GPS track.",
                    Modifier.align(Alignment.Center),
                )
            }
        }

        NfCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricTile("Distance", NfFormat.km(run.distanceM), detail = "km")
                MetricTile("Time", NfFormat.duration(run.durationMs))
                MetricTile("Pace", NfFormat.pace(averageSpeed(run)), detail = "/km")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricTile("Running", NfFormat.km(run.runningDistanceM), detail = "km")
                MetricTile("Run time", NfFormat.duration(run.runningTimeMs))
                MetricTile("Steps", run.steps.toString())
            }
        }

        NfCard {
            SectionLabel("Corrections")
            EvidenceRow("Rejected GPS fixes", run.rejectedFixes.toString(), detail = "jumps, teleports and very inaccurate fixes")
            EvidenceRow("Pulled towards a way", run.roadCorrections.toString(), detail = "GPS drifted off a road or path the run was on")
            EvidenceRow("GPS outages", run.gpsLosses.toString(), detail = "bridged with steps and the gyroscope")
            EvidenceRow("Map", if (run.usedMap) "BombayChitra" else "none")
        }

        if (run.recovered) {
            NfCard {
                SectionLabel("Recovered")
                Text(
                    "The app was closed during this run (revoking location access does that), so it was rebuilt from " +
                        "its raw trace. The last few seconds before the app closed may be missing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NfTheme.colors.subtle,
                )
            }
        }

        NfCard {
            SectionLabel("Raw trace")
            Text(
                "Every GPS fix and motion sample of this run is kept, so it can be replayed through a newer engine.",
                style = MaterialTheme.typography.bodyMedium,
                color = NfTheme.colors.subtle,
            )
            Text(container.runs.traceFile(run.id).path, style = MaterialTheme.typography.bodySmall)
        }

        OutlinedButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) { Text("DELETE RUN", style = MaterialTheme.typography.labelLarge) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete run?") },
            text = { Text("The route and the raw trace are removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        container.runs.delete(id)
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
