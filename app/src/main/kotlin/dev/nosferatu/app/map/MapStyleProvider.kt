package dev.nosferatu.app.map

import android.content.Context
import dev.nosferatu.bombaychitra.BombayChitraStyle
import dev.nosferatu.bombaychitra.MapTheme
import java.io.File

/**
 * Produces the style MapLibre loads: BombayChitra's generated style (shipped in assets) pointed at the local
 * PMTiles archive and the bundled glyphs.
 */
class MapStyleProvider(private val context: Context) {
    private val cache = HashMap<Pair<MapTheme, String>, String>()

    fun offlineStyle(theme: MapTheme, tiles: File): String = cache.getOrPut(theme to tiles.absolutePath) {
        val template = context.assets.open("bombaychitra/styles/${theme.styleName}.json").bufferedReader().use { it.readText() }
        BombayChitraStyle.offline(
            template = template,
            pmtilesUri = BombayChitraStyle.pmtilesFileUri(tiles.absolutePath),
            glyphsUri = GLYPHS,
        )
    }

    companion object {
        /** Glyphs copied from BombayChitra's `web/public/fonts`. */
        const val GLYPHS = "asset://fonts/{fontstack}/{range}.pbf"
    }
}
