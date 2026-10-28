package dev.nosferatu.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nosferatu.app.ui.theme.NfTheme
import dev.nosferatu.app.ui.theme.NumeralFamily

@Composable
fun NfCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = container)
    val border = BorderStroke(1.dp, NfTheme.colors.divider)
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, border = border, content = inner)
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors, border = border, content = inner)
    }
}

/** Small uppercase label used for section headers and captions. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = NfTheme.colors.subtle) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A measured value: caption above, monospace value, optional detail below. */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    accent: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(label)
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = NumeralFamily),
            color = accent,
            maxLines = 1,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = NfTheme.colors.subtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One line of evidence: label on the left, value on the right. */
@Composable
fun EvidenceRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    emphasized: Boolean = false,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (emphasized) FontWeight.SemiBold else null,
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = NfTheme.colors.subtle)
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = NumeralFamily),
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Int = 10) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

/** A rounded tag with a coloured dot: the current activity, GPS quality, map-match state. */
@Composable
fun StatePill(label: String, color: Color, modifier: Modifier = Modifier, detail: String? = null) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        // Opaque, so the pill reads the same on a card and over a busy map.
        color = color.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, color),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Dot(color = color)
            Text(text = label.uppercase(), style = MaterialTheme.typography.labelLarge, color = color)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = NumeralFamily),
                    color = color.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = NfTheme.colors.subtle)
    }
}

/** A thin bordered panel used for overlays on the map. */
@Composable
fun MapPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(NfTheme.colors.scrim)
            .border(1.dp, NfTheme.colors.divider, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
