package dev.nosferatu.app.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nosferatu.app.tracking.LiveState
import dev.nosferatu.app.ui.component.Dot
import dev.nosferatu.app.ui.component.EvidenceRow
import dev.nosferatu.app.ui.component.SectionLabel
import dev.nosferatu.app.ui.format.NfFormat
import dev.nosferatu.app.ui.theme.NfTheme
import dev.nosferatu.app.ui.theme.NumeralFamily
import dev.nosferatu.engine.activity.Activity
import dev.nosferatu.engine.activity.ActivityEstimate
import dev.nosferatu.engine.track.CorrectionEvent
import dev.nosferatu.engine.track.GpsQuality

@Composable
fun activityColor(activity: Activity): Color = when (activity) {
    Activity.Stationary -> NfTheme.colors.stationary
    Activity.Walking -> NfTheme.colors.walking
    Activity.Jogging -> NfTheme.colors.jogging
    Activity.Running -> NfTheme.colors.running
    Activity.Unknown -> NfTheme.colors.unknown
}

@Composable
fun gpsColor(quality: GpsQuality): Color = when (quality) {
    GpsQuality.Good -> NfTheme.colors.good
    GpsQuality.Fair -> NfTheme.colors.moderate
    GpsQuality.Poor, GpsQuality.Lost -> NfTheme.colors.bad
    GpsQuality.Off -> NfTheme.colors.unknown
}

/** The classifier's belief over every activity, as bars, so a 55/45 call looks like one. */
@Composable
fun PosteriorBars(estimate: ActivityEstimate, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (a in Activity.entries) {
            val p = estimate.posterior[a] ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    a.name.uppercase(),
                    Modifier.width(92.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (a == estimate.activity) activityColor(a) else NfTheme.colors.subtle,
                )
                Box(
                    Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(NfTheme.colors.divider),
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(p.toFloat().coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(4.dp)).background(activityColor(a)),
                    )
                }
                Text(
                    NfFormat.percent(p),
                    Modifier.width(40.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = NumeralFamily),
                    color = NfTheme.colors.subtle,
                )
            }
        }
    }
}

/** Why the classifier thinks what it thinks: each signal, its value, and which activity it points to. */
@Composable
fun EvidenceList(estimate: ActivityEstimate, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (estimate.evidence.isEmpty()) {
            Text("No evidence yet.", style = MaterialTheme.typography.bodySmall, color = NfTheme.colors.subtle)
        }
        for (e in estimate.evidence) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dot(e.favours?.let { activityColor(it) } ?: NfTheme.colors.divider, size = 8)
                Text(e.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = NumeralFamily))
            }
        }
    }
}

/** Everything behind the numbers: what the GPS said, what the tracker did about it, and what the map matched. */
@Composable
fun DiagnosticsBlock(live: LiveState, modifier: Modifier = Modifier) {
    val snap = live.snapshot
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Activity")
        if (snap != null) {
            PosteriorBars(snap.activity)
            EvidenceList(snap.activity)
        }

        SectionLabel("Position")
        EvidenceRow("Source", snap?.source?.label ?: "--")
        EvidenceRow("GPS", snap?.gpsQuality?.label ?: "--", detail = snap?.lastFix?.let { "last fix ±" + NfFormat.metres(it.accuracyM) })
        EvidenceRow("Uncertainty", NfFormat.metres(snap?.uncertaintyM))
        val motion = snap?.motion
        EvidenceRow("Gyroscope", if (!live.hasGyroscope) "none" else if (motion?.gyroCalibrated == true) "calibrated" else "calibrating")

        SectionLabel("Map")
        val (label, way) = RunPresentation.matchLabel(snap?.match)
        EvidenceRow(label, snap?.match?.let { NfFormat.percent(it.confidence) } ?: "--", detail = way ?: if (!live.usingMap) "road graph not loaded" else null)
        snap?.match?.let { EvidenceRow("Ways considered", it.considered.toString()) }

        SectionLabel("Corrections")
        val counts = live.eventCounts
        EvidenceRow("Rejected fixes", (counts[CorrectionEvent.Kind.Rejected] ?: 0).toString())
        EvidenceRow("Down-weighted", (counts[CorrectionEvent.Kind.DownWeighted] ?: 0).toString())
        EvidenceRow("Road pulls", (counts[CorrectionEvent.Kind.RoadPull] ?: 0).toString())
        EvidenceRow("GPS lost", (counts[CorrectionEvent.Kind.GpsLost] ?: 0).toString())
        for (e in live.events.asReversed().filter { it.kind != CorrectionEvent.Kind.RoadPull }.take(5)) {
            Text(
                "${e.kind.name}${e.issue?.let { " · ${it.name}" } ?: ""}${if (e.detail.isNotEmpty()) " · ${e.detail}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = NfTheme.colors.subtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
