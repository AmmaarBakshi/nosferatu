package dev.nosferatu.bombaychitra

import dev.nosferatu.engine.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BombayChitraDatasetTest {
    @Test
    fun parsesSha256sumOutput() {
        val a = "a".repeat(64)
        val b = "0123456789abcdef".repeat(4)
        val sums = BombayChitraDataset.parseChecksums(
            """
            $a  bombaychitra.pmtiles
            ${b.uppercase()} *bombaychitra.db
            not-a-hash  junk
            # comment
            """.trimIndent(),
        )
        assertEquals(mapOf("bombaychitra.pmtiles" to a, "bombaychitra.db" to b), sums)
    }

    @Test
    fun releaseUrlMatchesBombayChitrasDownloader() {
        assertEquals(
            "https://github.com/AmmaarBakshi/BombayChitra/releases/download/data-latest",
            BombayChitraDataset.releaseUrl(),
        )
    }

    @Test
    fun boundsParseAndContain() {
        val b = Bounds.parse("[72.7,18.8,73.85,19.82]")
        assertEquals(Bounds.MMR, b)
        assertTrue(LatLon(19.076, 72.8777) in Bounds.MMR) // Mumbai
        assertFalse(LatLon(18.5204, 73.8567) in Bounds.MMR) // Pune
        assertNull(Bounds.parse("[1,2,3]"))
        assertNull(Bounds.parse("[3,2,1,4]"))
    }
}
