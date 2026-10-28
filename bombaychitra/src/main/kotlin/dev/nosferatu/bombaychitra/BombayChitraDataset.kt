package dev.nosferatu.bombaychitra

import dev.nosferatu.engine.geo.LatLon

/**
 * Where BombayChitra publishes its data and what area it covers.
 *
 * Mirrors `server/internal/download`: the weekly `data-latest` release on GitHub carries `bombaychitra.pmtiles`,
 * `bombaychitra.db` and a `SHA256SUMS` file. The phone only needs the tiles; the road graph ships in the APK
 * (see `scripts/extract-graph.py`).
 */
object BombayChitraDataset {
    const val REPO = "AmmaarBakshi/BombayChitra"
    const val RELEASE = "data-latest"
    const val TILES_FILE = "bombaychitra.pmtiles"
    const val DATABASE_FILE = "bombaychitra.db"
    const val CHECKSUM_FILE = "SHA256SUMS"

    fun releaseUrl(repo: String = REPO, tag: String = RELEASE): String =
        "https://github.com/$repo/releases/download/$tag"

    /**
     * Parses `sha256  filename` lines (the `sha256sum` format; a `*` before the name marks binary mode).
     * Malformed lines are ignored, as BombayChitra's own downloader does.
     */
    fun parseChecksums(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val hash = parts[0].lowercase()
            if (hash.length != 64 || !hash.all { it in '0'..'9' || it in 'a'..'f' }) return@mapNotNull null
            parts[1].removePrefix("*").trim() to hash
        }
        .toMap()
}

/** A lon/lat bounding box, as BombayChitra stores it in `meta.bounds` (`[west, south, east, north]`). */
data class Bounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    operator fun contains(p: LatLon): Boolean = p.lon in west..east && p.lat in south..north

    val center: LatLon get() = LatLon((south + north) / 2, (west + east) / 2)

    companion object {
        /** The Mumbai Metropolitan Region, BombayChitra's `config/region.json` bounds. */
        val MMR = Bounds(72.70, 18.80, 73.85, 19.82)

        /** Parses `[72.7,18.8,73.85,19.82]`. */
        fun parse(text: String): Bounds? {
            val v = text.trim().removePrefix("[").removeSuffix("]").split(',').mapNotNull { it.trim().toDoubleOrNull() }
            return if (v.size == 4 && v[0] < v[2] && v[1] < v[3]) Bounds(v[0], v[1], v[2], v[3]) else null
        }
    }
}
