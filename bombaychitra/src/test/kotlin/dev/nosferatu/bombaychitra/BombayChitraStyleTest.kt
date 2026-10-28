package dev.nosferatu.bombaychitra

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BombayChitraStyleTest {
    /** The styles the app ships, exactly as BombayChitra's server generated them. */
    private fun shipped(theme: MapTheme): String =
        File("../app/src/main/assets/bombaychitra/styles/${theme.styleName}.json").readText()

    private val pmtiles = BombayChitraStyle.pmtilesFileUri("/data/user/0/dev.nosferatu/files/bombaychitra.pmtiles")
    private val glyphs = "asset://fonts/{fontstack}/{range}.pbf"

    @Test
    fun rewritesOnlyTheSourceAndGlyphs() {
        for (theme in MapTheme.entries) {
            val original = Json.parseToJsonElement(shipped(theme)).jsonObject
            val rewritten = Json.parseToJsonElement(BombayChitraStyle.offline(shipped(theme), pmtiles, glyphs)).jsonObject

            val bc = rewritten["sources"]!!.jsonObject["bc"]!!.jsonObject
            assertEquals("vector", bc["type"]!!.jsonPrimitive.content)
            assertEquals("pmtiles://file:///data/user/0/dev.nosferatu/files/bombaychitra.pmtiles", bc["url"]!!.jsonPrimitive.content)
            assertEquals(glyphs, rewritten["glyphs"]!!.jsonPrimitive.content)

            // BombayChitra's layers, their order and paint are untouched.
            assertEquals(original["layers"], rewritten["layers"])
            assertTrue(rewritten["layers"]!!.jsonArray.size >= 40)
            assertEquals(original.keys, rewritten.keys)
        }
    }

    @Test
    fun rejectsStylesThatAreNotBombayChitras() {
        assertFailsWith<UnsupportedStyleException> {
            BombayChitraStyle.offline("""{"version":8,"sources":{"osm":{"type":"raster"}},"layers":[]}""", pmtiles, glyphs)
        }
        assertFailsWith<UnsupportedStyleException> {
            BombayChitraStyle.offline("""{"version":8,"sources":{"bc":{"type":"raster"}},"layers":[]}""", pmtiles, glyphs)
        }
    }

    @Test
    fun buildsFileAndServerUris() {
        assertEquals("pmtiles://file:///sdcard/x.pmtiles", BombayChitraStyle.pmtilesFileUri("/sdcard/x.pmtiles"))
        assertEquals("pmtiles://file:///C:/maps/x.pmtiles", BombayChitraStyle.pmtilesFileUri("C:\\maps\\x.pmtiles"))
        assertEquals(
            "https://maps.example.com/styles/dark.json",
            BombayChitraStyle.serverStyleUrl("https://maps.example.com/", MapTheme.Dark),
        )
    }
}
