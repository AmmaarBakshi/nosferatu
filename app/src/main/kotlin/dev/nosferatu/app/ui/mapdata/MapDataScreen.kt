package dev.nosferatu.app.ui.mapdata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.nosferatu.app.container
import dev.nosferatu.app.data.GraphState
import dev.nosferatu.app.data.TilesState
import dev.nosferatu.app.ui.component.EvidenceRow
import dev.nosferatu.app.ui.component.NfCard
import dev.nosferatu.app.ui.component.SectionLabel
import dev.nosferatu.app.ui.component.StatePill
import dev.nosferatu.app.ui.theme.NfTheme
import dev.nosferatu.bombaychitra.BombayChitraDataset
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun MapDataScreen(modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    val container = LocalContext.current.container
    val tiles by container.mapData.tiles.collectAsState()
    val scope = rememberCoroutineScope()
    // Tiles may have been copied in by hand (adb push) since the app started.
    LaunchedEffect(Unit) { container.mapData.refresh() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionLabel("Map foundation")
        Text("BombayChitra", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Nosferatu draws BombayChitra's open map of the Mumbai Metropolitan Region and uses its road and path " +
                "network to check every GPS fix. The tiles are downloaded once and work offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = NfTheme.colors.subtle,
        )

        NfCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SectionLabel("Vector tiles")
                when (val t = tiles) {
                    is TilesState.Ready -> StatePill("Ready", NfTheme.colors.good)
                    is TilesState.Downloading -> StatePill("Downloading", NfTheme.colors.moderate)
                    TilesState.Verifying -> StatePill("Verifying", NfTheme.colors.moderate)
                    is TilesState.Failed -> StatePill("Failed", NfTheme.colors.bad)
                    TilesState.Missing -> StatePill("Missing", NfTheme.colors.unknown)
                }
            }
            when (val t = tiles) {
                is TilesState.Ready -> {
                    EvidenceRow("Size", mb(t.sizeBytes))
                    EvidenceRow("Source", BombayChitraDataset.RELEASE)
                    OutlinedButton(onClick = { container.mapData.delete() }) { Text("Delete tiles") }
                }
                is TilesState.Downloading -> {
                    val f = t.fraction
                    if (f != null) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${mb(t.bytes)} of ${t.total?.let(::mb) ?: "?"}", style = MaterialTheme.typography.bodySmall)
                }
                TilesState.Verifying -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is TilesState.Failed -> {
                    Text(t.message, style = MaterialTheme.typography.bodyMedium, color = NfTheme.colors.bad)
                    Button(onClick = { scope.launch { container.mapData.download() } }) { Text("Retry") }
                }
                TilesState.Missing -> {
                    Text(
                        "About 96 MB from github.com/${BombayChitraDataset.REPO}. Use Wi-Fi if you can.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { scope.launch { container.mapData.download() } }) { Text("Download map") }
                }
            }
        }

        RoadGraphCard()

        extra()

        Text(
            "Map data © OpenStreetMap contributors (ODbL) · Overture Maps Foundation · BombayChitra (MIT)",
            style = MaterialTheme.typography.bodySmall,
            color = NfTheme.colors.subtle,
        )
    }
}

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1e6)

/** The road and path graph Nosferatu checks fixes against: BombayChitra's routing tables, loaded on device. */
@Composable
private fun RoadGraphCard() {
    val container = LocalContext.current.container
    val graph by container.graph.state.collectAsState()
    LaunchedEffect(Unit) { container.graph.load() }
    NfCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("Roads and paths")
            when (graph) {
                is GraphState.Ready -> StatePill("Loaded", NfTheme.colors.good)
                GraphState.Loading -> StatePill("Loading", NfTheme.colors.moderate)
                GraphState.NotBundled -> StatePill("Not bundled", NfTheme.colors.unknown)
                is GraphState.Failed -> StatePill("Failed", NfTheme.colors.bad)
            }
        }
        when (val g = graph) {
            is GraphState.Ready -> {
                val net = g.adapter.network.graph
                EvidenceRow("Junctions", String.format(Locale.US, "%,d", net.vertexCount))
                EvidenceRow("Way segments", String.format(Locale.US, "%,d", net.segmentCount))
                EvidenceRow("Load time", "${g.loadMs} ms")
                g.builtAt?.let { EvidenceRow("Built", it) }
                Text(
                    "Used to tell a GPS error from a real route: fixes are pulled towards the way you are on only when " +
                        "the evidence says so, and paths missing from the map are kept as measured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NfTheme.colors.subtle,
                )
            }
            GraphState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            GraphState.NotBundled -> Text(
                "This build has no road graph, so tracking runs on GPS and motion alone. Build with " +
                    "scripts/bombaychitra_assets.py graph, or place bombaychitra.db next to the tiles.",
                style = MaterialTheme.typography.bodyMedium,
            )
            is GraphState.Failed -> Text(g.message, style = MaterialTheme.typography.bodyMedium, color = NfTheme.colors.bad)
        }
    }
}
