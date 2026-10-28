package dev.nosferatu.app.data

import android.content.Context
import dev.nosferatu.bombaychitra.MapTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The few choices the app remembers. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _mapTheme = MutableStateFlow(
        runCatching { MapTheme.valueOf(prefs.getString(MAP_THEME, null) ?: "") }.getOrDefault(MapTheme.Dark),
    )

    /** BombayChitra's dark style by default; its light one reads better in direct sun. */
    val mapTheme: StateFlow<MapTheme> = _mapTheme.asStateFlow()

    fun setMapTheme(theme: MapTheme) {
        prefs.edit().putString(MAP_THEME, theme.name).apply()
        _mapTheme.value = theme
    }

    private companion object {
        const val MAP_THEME = "map_theme"
    }
}
