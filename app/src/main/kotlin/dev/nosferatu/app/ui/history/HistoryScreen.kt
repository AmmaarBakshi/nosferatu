package dev.nosferatu.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.nosferatu.app.container
import dev.nosferatu.app.data.RunSummary
import dev.nosferatu.app.ui.component.EmptyState
import dev.nosferatu.app.ui.component.MetricTile
import dev.nosferatu.app.ui.component.NfCard
import dev.nosferatu.app.ui.component.SectionLabel
import dev.nosferatu.app.ui.component.StatePill
import dev.nosferatu.app.ui.format.NfFormat
import dev.nosferatu.app.ui.run.RunPresentation
import dev.nosferatu.app.ui.theme.NfTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every saved run, newest first. */
@Composable
fun HistoryScreen(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContext.current.container
    val runs by container.runs.runs.collectAsState()
    LaunchedEffect(Unit) { container.runs.refresh() }

    LazyColumn(
        modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionLabel("Run log")
            Text("History", style = MaterialTheme.typography.headlineMedium)
        }
        val total = runs.sumOf { it.distanceM }
        if (runs.isNotEmpty()) {
            item {
                NfCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MetricTile("Runs", runs.size.toString())
                        MetricTile("Distance", NfFormat.km(total), detail = "km")
                        MetricTile("Running", NfFormat.km(runs.sumOf { it.runningDistanceM }), detail = "km")
                    }
                }
            }
        } else {
            item { EmptyState("No runs yet", "Finished runs appear here with their route and corrections.") }
        }
        items(runs, key = { it.id }) { run -> RunRow(run, onClick = { onOpen(run.id) }) }
    }
}

@Composable
private fun RunRow(run: RunSummary, onClick: () -> Unit) {
    NfCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel(DATE.format(Date(run.startedAtWallMs)))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (run.recovered) StatePill("Recovered", NfTheme.colors.moderate)
                if (!run.usedMap) StatePill("No map", NfTheme.colors.unknown)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricTile("Distance", NfFormat.km(run.distanceM), detail = "km")
            MetricTile("Time", NfFormat.duration(run.durationMs))
            MetricTile("Pace", NfFormat.pace(averageSpeed(run)), detail = "/km")
        }
    }
}

internal fun averageSpeed(run: RunSummary): Double? = RunPresentation.averageSpeed(run.distanceM, run.movingTimeMs)

internal val DATE = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.getDefault())
