package dev.nosferatu.app.data

import android.content.Context
import dev.nosferatu.bombaychitra.BombayChitraDataset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What the phone has of BombayChitra's map tiles. */
sealed interface TilesState {
    data object Missing : TilesState
    data class Downloading(val bytes: Long, val total: Long?) : TilesState {
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat() }
    }
    data object Verifying : TilesState
    data class Ready(val file: File, val sizeBytes: Long) : TilesState
    data class Failed(val message: String) : TilesState
}

/**
 * Keeps `bombaychitra.pmtiles` on the phone. Like `bombaychitra download`, it fetches from the `data-latest`
 * release, checks the file against the release's SHA256SUMS and only moves it into place once it is intact.
 * Interrupted downloads resume from the partial file.
 *
 * Files live in the app's external files directory when there is one, so a developer can also
 * `adb push bombaychitra.pmtiles /sdcard/Android/data/dev.nosferatu/files/bombaychitra/` instead of downloading.
 */
class MapDataRepository(context: Context) {
    val directory: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "bombaychitra").apply { mkdirs() }
    val tilesFile: File = File(directory, BombayChitraDataset.TILES_FILE)
    private val partFile = File(directory, BombayChitraDataset.TILES_FILE + ".part")

    private val _tiles = MutableStateFlow(currentState())
    val tiles: StateFlow<TilesState> = _tiles.asStateFlow()

    fun refresh() {
        if (_tiles.value !is TilesState.Downloading && _tiles.value !is TilesState.Verifying) _tiles.value = currentState()
    }

    private fun currentState(): TilesState =
        if (tilesFile.isFile && tilesFile.length() > 0) TilesState.Ready(tilesFile, tilesFile.length()) else TilesState.Missing

    /** Downloads the tiles; safe to call again after a failure. Cancelling the coroutine keeps the partial file. */
    suspend fun download(baseUrl: String = BombayChitraDataset.releaseUrl()) = withContext(Dispatchers.IO) {
        try {
            val sums = BombayChitraDataset.parseChecksums(fetchText("$baseUrl/${BombayChitraDataset.CHECKSUM_FILE}"))
            val expected = sums[BombayChitraDataset.TILES_FILE]
            fetchResumable("$baseUrl/${BombayChitraDataset.TILES_FILE}")
            _tiles.value = TilesState.Verifying
            if (expected != null) {
                val actual = sha256(partFile)
                if (actual != expected) {
                    partFile.delete()
                    throw IOException("checksum mismatch; the download was corrupted and has been discarded")
                }
            }
            if (tilesFile.exists()) tilesFile.delete()
            if (!partFile.renameTo(tilesFile)) throw IOException("could not move the tiles into place")
            _tiles.value = currentState()
        } catch (e: IOException) {
            _tiles.value = TilesState.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: CancellationException) {
            _tiles.value = currentState()
            throw e
        }
    }

    fun delete() {
        tilesFile.delete()
        partFile.delete()
        _tiles.value = TilesState.Missing
    }

    private fun open(url: String, from: Long = 0): HttpURLConnection {
        var current = URL(url)
        // GitHub release assets redirect to a storage host; follow up to five hops explicitly.
        repeat(5) {
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Nosferatu (+https://github.com/AmmaarBakshi/nosferatu)")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            when (c.responseCode) {
                in 300..399 -> {
                    val next = c.getHeaderField("Location") ?: throw IOException("redirect without location")
                    c.disconnect()
                    current = URL(current, next)
                }
                else -> return c
            }
        }
        throw IOException("too many redirects")
    }

    private fun fetchText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode == 404) return "" // No checksums published; the file is still usable, just unverified.
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun fetchResumable(url: String) {
        val have = if (partFile.isFile) partFile.length() else 0L
        val c = open(url, have)
        try {
            val resumed = c.responseCode == HttpURLConnection.HTTP_PARTIAL
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} downloading the map")
            val start = if (resumed) have else 0L
            val length = c.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0 }
            val total = length?.let { it + start }
            var done = start
            _tiles.value = TilesState.Downloading(done, total)
            c.inputStream.use { input ->
                FileOutputStream(partFile, resumed).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            _tiles.value = TilesState.Downloading(done, total)
                        }
                    }
                }
            }
            if (total != null && done != total) throw IOException("download ended early ($done of $total bytes)")
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
