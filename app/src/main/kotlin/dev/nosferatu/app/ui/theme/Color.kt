package dev.nosferatu.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The Nosferatu palette: one crimson accent on warm, near-black neutrals. The structure (ink scale, a single
 * accent, state tones) follows OrangeXP's design system so the two apps feel like siblings.
 */
internal object NfPalette {
    val Blood50 = Color(0xFFFFF0F0)
    val Blood100 = Color(0xFFFFD9DA)
    val Blood200 = Color(0xFFFFADB0)
    val Blood300 = Color(0xFFFA7C80)
    val Blood400 = Color(0xFFF2555A)
    val Blood500 = Color(0xFFE5383B)
    val Blood600 = Color(0xFFC4262A)
    val Blood700 = Color(0xFF961C20)
    val Blood800 = Color(0xFF5E1416)
    val Blood900 = Color(0xFF330B0C)

    val Ink950 = Color(0xFF0E0C0C)
    val Ink900 = Color(0xFF161313)
    val Ink850 = Color(0xFF1C1918)
    val Ink800 = Color(0xFF24201F)
    val Ink700 = Color(0xFF342F2D)
    val Ink500 = Color(0xFF6F6662)
    val Ink300 = Color(0xFFB3A9A5)
    val Ink100 = Color(0xFFEDE6E3)
    val Paper = Color(0xFFFBF8F6)
    val PaperDim = Color(0xFFF2ECE9)

    val Green = Color(0xFF34B36A)
    val Amber = Color(0xFFF29A2E)
    val Red = Color(0xFFE5484D)
    val Sky = Color(0xFF4EA8DE)
    val Violet = Color(0xFF9D7CEB)
}

/** Colours beyond Material's scheme: activity tones and the map overlay. */
@Immutable
data class NfExtendedColors(
    val stationary: Color,
    val walking: Color,
    val jogging: Color,
    val running: Color,
    val unknown: Color,
    val good: Color,
    val moderate: Color,
    val bad: Color,
    /** Raw GPS fixes on the map: deliberately not the accent, so the corrected track stands out. */
    val rawGps: Color,
    val matchedRoad: Color,
    val subtle: Color,
    val divider: Color,
    /** Translucent panel over the map. */
    val scrim: Color,
)

internal val DarkExtendedColors = NfExtendedColors(
    stationary = NfPalette.Ink300,
    walking = NfPalette.Sky,
    jogging = NfPalette.Amber,
    running = NfPalette.Blood500,
    unknown = NfPalette.Ink500,
    good = NfPalette.Green,
    moderate = NfPalette.Amber,
    bad = NfPalette.Red,
    rawGps = NfPalette.Sky,
    matchedRoad = NfPalette.Violet,
    subtle = NfPalette.Ink300,
    divider = NfPalette.Ink800,
    scrim = Color(0xE60E0C0C),
)

internal val LightExtendedColors = NfExtendedColors(
    stationary = NfPalette.Ink500,
    walking = Color(0xFF1F7FB8),
    jogging = Color(0xFFD9800F),
    running = NfPalette.Blood600,
    unknown = NfPalette.Ink300,
    good = Color(0xFF218A4F),
    moderate = Color(0xFFD9800F),
    bad = Color(0xFFCC2F35),
    rawGps = Color(0xFF1F7FB8),
    matchedRoad = Color(0xFF6B4FC7),
    subtle = NfPalette.Ink500,
    divider = NfPalette.Ink100,
    scrim = Color(0xEBFBF8F6),
)

val LocalNfColors = staticCompositionLocalOf { DarkExtendedColors }
