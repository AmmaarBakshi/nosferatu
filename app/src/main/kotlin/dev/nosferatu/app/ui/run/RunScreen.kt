package dev.nosferatu.app.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.nosferatu.app.container
import dev.nosferatu.app.data.GraphState
import dev.nosferatu.app.data.RunSummary
import dev.nosferatu.app.data.TilesState
import dev.nosferatu.app.map.BombayChitraMap
import dev.nosferatu.app.map.OverlayColors
import dev.nosferatu.app.tracking.LiveState
import dev.nosferatu.app.tracking.SessionStatus
import dev.nosferatu.app.ui.component.EmptyState
import dev.nosferatu.app.ui.component.MapPanel
import dev.nosferatu.app.ui.component.MetricTile
import dev.nosferatu.app.ui.component.SectionLabel
import dev.nosferatu.app.ui.component.StatePill
import dev.nosferatu.app.ui.format.NfFormat
import dev.nosferatu.app.ui.theme.NfTheme
import dev.nosferatu.bombaychitra.MapTheme
import dev.nosferatu.engine.activity.Activity
import dev.nosferatu.engine.model.GpsFix
import kotlinx.coroutines.flow.catch

/**
 * The run: BombayChitra's map with the corrected track, live metrics, what the tracker believes and why, and the
 * controls. Before a run it shows the raw GPS fix so the runner can wait for a good one.
 */
@Composable
fun RunScreen(onOpenMapData: () -> Unit, onOpenRun: (String) -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContext.current.container
    val tiles by container.mapData.tiles.collectAsState()
    val graph by container.graph.state.collectAsState()
    val live by container.tracking.live.collectAsState()
    val saved by container.tracking.lastSaved.collectAsState()
    val permissions = rememberTrackingPermissions()
    var fix by remember { mutableStateOf<GpsFix?>(null) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val idle = live == null

    // Tiles may have been copied in by hand (adb push) since the app started.
    LaunchedEffect(Unit) { container.mapData.refresh() }
    // Load the road graph while the runner waits for GPS, so starting is instant.
    LaunchedEffect(Unit) { container.graph.load() }
    // Before a run, show raw fixes. During one, the session owns the GPS.
    LaunchedEffect(permissions.granted, idle) {
        if (permissions.granted && idle) container.gps.fixes().catch { }.collect { fix = it }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val ready = tiles as? TilesState.Ready
        if (ready == null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState("No map yet", "Download BombayChitra's map of the MMR. Runs are tracked either way.")
                Button(onClick = onOpenMapData) { Text("Get the map") }
            }
        } else {
            val style = remember(ready.file) { container.mapStyles.offlineStyle(MapTheme.Dark, ready.file) }
            val l = live
            BombayChitraMap(
                styleJson = style,
                overlay = if (l != null) RunPresentation.runOverlay(l, diagnostics) else RunPresentation.idleOverlay(fix),
                colors = overlayColors(),
                follow = follow,
                onUserGesture = { follow = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(Modifier.statusBarsPadding().padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                StatusPills(live, fix, graph, permissions.granted, Modifier.weight(1f))
                FilledIconToggleButton(
                    checked = diagnostics,
                    onCheckedChange = { diagnostics = it },
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        containerColor = NfTheme.colors.scrim,
                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) { Icon(Icons.Outlined.Insights, contentDescription = "Diagnostics") }
            }
            Warnings(live)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.End) {
            if (!follow) {
                SmallFloatingActionButton(onClick = { follow = true }, containerColor = NfTheme.colors.scrim) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = "Recentre")
                }
            }
            MapPanel(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                val l = live
                val s = saved
                when {
                    !permissions.granted -> {
                        Text("Nosferatu needs your location to track a run.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = permissions.request, Modifier.fillMaxWidth()) { Text("Allow location") }
                    }
                    l == null && s != null -> SavedPanel(s, onDone = container.tracking::dismissSummary, onOpen = {
                        container.tracking.dismissSummary()
                        onOpenRun(s.id)
                    })
                    l == null -> ReadyPanel(fix, onStart = container.tracking::start)
                    l.status == SessionStatus.Starting -> {
                        SectionLabel("Starting")
                        Text("Loading BombayChitra's roads and paths…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    else -> RunPanel(
                        l, diagnostics,
                        onPause = container.tracking::pause,
                        onResume = container.tracking::resume,
                        onStop = { confirmStop = true },
                    )
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Finish run?") },
            text = { Text("The run is saved with its raw sensor trace so it can be replayed later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    container.tracking.stop()
                }) { Text("Finish") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep going") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusPills(live: LiveState?, fix: GpsFix?, graph: GraphState, granted: Boolean, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val snap = live?.snapshot
        when {
            !granted -> StatePill("No location", NfTheme.colors.bad)
            live == null && fix == null -> StatePill("Waiting for GPS", NfTheme.colors.moderate)
            live == null -> StatePill("GPS", NfTheme.colors.good, detail = "±" + NfFormat.metres(fix?.accuracyM))
            snap == null || snap.position == null -> StatePill("Waiting for GPS", NfTheme.colors.moderate)
            RunPresentation.isDeadReckoning(snap.source) -> StatePill("Dead reckoning", NfTheme.colors.moderate, detail = "±" + NfFormat.metres(snap.uncertaintyM))
            else -> StatePill("GPS ${snap.gpsQuality.label}", gpsColor(snap.gpsQuality), detail = "±" + NfFormat.metres(snap.uncertaintyM))
        }
        if (live != null && live.status == SessionStatus.Paused) StatePill("Paused", NfTheme.colors.unknown)
        if (snap != null && live.status == SessionStatus.Tracking) {
            val a = snap.activity
            StatePill(a.activity.name, activityColor(a.activity), detail = if (a.activity == Activity.Unknown) null else NfFormat.percent(a.confidence))
            val (label, way) = RunPresentation.matchLabel(snap.match)
            if (live.usingMap) StatePill(label, NfTheme.colors.matchedRoad, detail = way)
        }
        if (live == null) {
            when (graph) {
                is GraphState.Ready -> StatePill("Roads", NfTheme.colors.good)
                GraphState.Loading -> StatePill("Loading roads", NfTheme.colors.moderate)
                GraphState.NotBundled -> StatePill("No road data", NfTheme.colors.unknown)
                is GraphState.Failed -> StatePill("Road data failed", NfTheme.colors.bad)
            }
        }
    }
}

@Composable
private fun Warnings(live: LiveState?) {
    val l = live ?: return
    val text = when {
        l.permissionMissing -> "Location permission is off. Distance comes from your steps until it is back."
        !l.gpsEnabled -> "Location is turned off in settings. Turn it on to keep the route accurate."
        else -> null
    } ?: return
    MapPanel(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = NfTheme.colors.moderate)
    }
}

@Composable
private fun ReadyPanel(fix: GpsFix?, onStart: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MetricTile("Accuracy", NfFormat.metres(fix?.accuracyM))
        MetricTile("Speed", NfFormat.kmh(fix?.speedMps), detail = "km/h")
    }
    val hint = when {
        fix == null -> "Waiting for a GPS fix. Step outside for a clear view of the sky."
        (fix.accuracyM ?: 99.0) > 15 -> "GPS is still settling. You can start now; the route sharpens as it improves."
        else -> "GPS is ready."
    }
    Text(hint, style = MaterialTheme.typography.bodySmall, color = NfTheme.colors.subtle)
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("START RUN", style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun RunPanel(live: LiveState, diagnostics: Boolean, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val snap = live.snapshot
    SectionLabel("Distance")
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(NfFormat.km(snap?.distanceM ?: 0.0), style = MaterialTheme.typography.displayMedium)
        Text("km", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = NfTheme.colors.subtle)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MetricTile("Time", NfFormat.duration(live.elapsedMs))
        val avg = snap?.let { RunPresentation.averageSpeed(it.distanceM, it.movingTimeMs) }
        MetricTile("Pace", NfFormat.pace(snap?.speedMps), detail = "avg ${NfFormat.pace(avg)} /km")
        MetricTile("Cadence", NfFormat.cadence(snap?.motion?.cadenceSpm), detail = "spm")
    }
    if (diagnostics) {
        DiagnosticsBlock(live, Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()))
    } else if (snap != null) {
        Text(
            snap.activity.evidence.take(2).joinToString(" · ") { "${it.label} ${it.value}" }.ifEmpty { "Gathering motion data…" },
            style = MaterialTheme.typography.bodySmall,
            color = NfTheme.colors.subtle,
            maxLines = 1,
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (live.status == SessionStatus.Paused) {
            Button(onClick = onResume, Modifier.weight(1f)) { Text("RESUME", style = MaterialTheme.typography.labelLarge) }
        } else {
            OutlinedButton(onClick = onPause, Modifier.weight(1f)) { Text("PAUSE", style = MaterialTheme.typography.labelLarge) }
        }
        Button(
            onClick = onStop,
            Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface),
        ) { Text("FINISH", style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun SavedPanel(run: RunSummary, onDone: () -> Unit, onOpen: () -> Unit) {
    SectionLabel("Run saved")
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(NfFormat.km(run.distanceM), style = MaterialTheme.typography.displayMedium)
        Text("km", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = NfTheme.colors.subtle)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MetricTile("Time", NfFormat.duration(run.durationMs))
        MetricTile("Running", NfFormat.km(run.runningDistanceM), detail = "km")
        MetricTile("Steps", run.steps.toString())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onDone, Modifier.weight(1f)) { Text("DONE", style = MaterialTheme.typography.labelLarge) }
        Button(onClick = onOpen, Modifier.weight(1f)) { Text("DETAILS", style = MaterialTheme.typography.labelLarge) }
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
