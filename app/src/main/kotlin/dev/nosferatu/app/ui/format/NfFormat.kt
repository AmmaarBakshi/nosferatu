package dev.nosferatu.app.ui.format

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Display formatting for run metrics. Pure functions so they are unit-tested. */
object NfFormat {
    /** `0.00` km with two decimals; under 10 m shows `0.00`. */
    fun km(metres: Double): String = String.format(Locale.US, "%.2f", metres / 1000.0)

    /** `h:mm:ss` or `mm:ss`. */
    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** Pace as `m:ss` per km, or `--:--` when not moving (under 1.8 km/h, slower than any walk). */
    fun pace(metresPerSecond: Double?): String {
        if (metresPerSecond == null || metresPerSecond < 0.5) return "--:--"
        val secPerKm = (1000.0 / metresPerSecond).roundToLong()
        if (secPerKm >= 60 * 60) return "--:--"
        return String.format(Locale.US, "%d:%02d", secPerKm / 60, secPerKm % 60)
    }

    fun kmh(metresPerSecond: Double?): String =
        if (metresPerSecond == null) "--" else String.format(Locale.US, "%.1f", metresPerSecond * 3.6)

    fun cadence(stepsPerMinute: Double?): String =
        if (stepsPerMinute == null || stepsPerMinute < 1) "--" else stepsPerMinute.roundToInt().toString()

    fun metres(m: Double?): String = when {
        m == null -> "--"
        m < 10 -> String.format(Locale.US, "%.1f m", m)
        m < 1000 -> "${m.roundToInt()} m"
        else -> String.format(Locale.US, "%.2f km", m / 1000)
    }

    fun percent(p: Double): String = "${(p * 100).roundToInt()}%"
}
