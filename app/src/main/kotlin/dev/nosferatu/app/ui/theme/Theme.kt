package dev.nosferatu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

private val DarkScheme = darkColorScheme(
    primary = NfPalette.Blood500,
    onPrimary = NfPalette.Paper,
    primaryContainer = NfPalette.Blood800,
    onPrimaryContainer = NfPalette.Blood100,
    secondary = NfPalette.Blood300,
    onSecondary = NfPalette.Ink950,
    secondaryContainer = NfPalette.Blood900,
    onSecondaryContainer = NfPalette.Blood100,
    tertiary = NfPalette.Blood200,
    tertiaryContainer = NfPalette.Blood800,
    onTertiaryContainer = NfPalette.Blood50,
    background = NfPalette.Ink950,
    onBackground = NfPalette.Ink100,
    surface = NfPalette.Ink950,
    onSurface = NfPalette.Ink100,
    surfaceVariant = NfPalette.Ink850,
    onSurfaceVariant = NfPalette.Ink300,
    surfaceContainerLowest = NfPalette.Ink950,
    surfaceContainerLow = NfPalette.Ink900,
    surfaceContainer = NfPalette.Ink850,
    surfaceContainerHigh = NfPalette.Ink800,
    surfaceContainerHighest = NfPalette.Ink700,
    outline = NfPalette.Ink700,
    outlineVariant = NfPalette.Ink800,
    error = NfPalette.Red,
)

private val LightScheme = lightColorScheme(
    primary = NfPalette.Blood600,
    onPrimary = NfPalette.Paper,
    primaryContainer = NfPalette.Blood100,
    onPrimaryContainer = NfPalette.Blood900,
    secondary = NfPalette.Blood700,
    secondaryContainer = NfPalette.Blood100,
    onSecondaryContainer = NfPalette.Blood900,
    tertiary = NfPalette.Blood400,
    tertiaryContainer = NfPalette.Blood50,
    onTertiaryContainer = NfPalette.Blood800,
    background = NfPalette.Paper,
    onBackground = NfPalette.Ink950,
    surface = NfPalette.Paper,
    onSurface = NfPalette.Ink950,
    surfaceVariant = NfPalette.PaperDim,
    onSurfaceVariant = NfPalette.Ink500,
    surfaceContainerLowest = NfPalette.Paper,
    surfaceContainerLow = NfPalette.Blood50,
    surfaceContainer = NfPalette.PaperDim,
    surfaceContainerHigh = NfPalette.Ink100,
    surfaceContainerHighest = NfPalette.Ink100,
    outline = NfPalette.Ink300,
    outlineVariant = NfPalette.Ink100,
    error = NfPalette.Red,
)

private val NfShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Nosferatu's theme. Dark is the signature look and pairs with BombayChitra's dark map; light follows the system.
 * Dynamic colour is intentionally not used: the accent is the identity.
 */
@Composable
fun NosferatuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalNfColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = NfTypography,
            shapes = NfShapes,
            content = content,
        )
    }
}

object NfTheme {
    val colors: NfExtendedColors
        @Composable @ReadOnlyComposable get() = LocalNfColors.current
}
