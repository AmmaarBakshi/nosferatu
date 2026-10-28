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
    fun quietStyleHidesOnlyPlacesAndHouseNumbers() {
        val original = Json.parseToJsonElement(shipped(MapTheme.Dark)).jsonObject["layers"]!!.jsonArray
        val quiet = Json.parseToJsonElement(BombayChitraStyle.hidingSourceLayers(shipped(MapTheme.Dark), BombayChitraStyle.QUIET_HIDDEN))
            .jsonObject["layers"]!!.jsonArray
        assertEquals(original.size, quiet.size)
        var hidden = 0
        for ((before, after) in original.zip(quiet)) {
            val b = before.jsonObject
            val a = after.jsonObject
            assertEquals(b["id"], a["id"])
            val sourceLayer = b["source-layer"]?.jsonPrimitive?.content
            if (sourceLayer in BombayChitraStyle.QUIET_HIDDEN) {
                hidden++
                assertEquals("none", a["layout"]!!.jsonObject["visibility"]!!.jsonPrimitive.content)
                // Only visibility changes; the rest of the layout (text fields, fonts) is BombayChitra's.
                assertEquals(b["layout"]?.jsonObject.orEmpty(), a["layout"]!!.jsonObject - "visibility")
                assertEquals(b["paint"], a["paint"])
            } else {
                assertEquals(b, a)
            }
        }
        assertEquals(3, hidden, "poi-dot, poi-label, housenumbers")
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
