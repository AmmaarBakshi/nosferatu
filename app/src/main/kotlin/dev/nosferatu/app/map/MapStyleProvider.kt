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
    private val cache = HashMap<Triple<MapTheme, String, Boolean>, String>()

    /** @param quiet hide BombayChitra's places and house numbers, which crowd out the route while running. */
    fun offlineStyle(theme: MapTheme, tiles: File, quiet: Boolean = false): String =
        cache.getOrPut(Triple(theme, tiles.absolutePath, quiet)) {
            val template = context.assets.open("bombaychitra/styles/${theme.styleName}.json").bufferedReader().use { it.readText() }
            val offline = BombayChitraStyle.offline(
                template = template,
                pmtilesUri = BombayChitraStyle.pmtilesFileUri(tiles.absolutePath),
                glyphsUri = GLYPHS,
            )
            if (quiet) BombayChitraStyle.hidingSourceLayers(offline, BombayChitraStyle.QUIET_HIDDEN) else offline
        }

    companion object {
        /** Glyphs copied from BombayChitra's `web/public/fonts`. */
        const val GLYPHS = "asset://fonts/{fontstack}/{range}.pbf"
    }
}
