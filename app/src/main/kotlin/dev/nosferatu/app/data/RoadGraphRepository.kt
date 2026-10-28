package dev.nosferatu.app.data

import android.content.Context
import android.os.SystemClock
import dev.nosferatu.bombaychitra.BombayChitraAdapter
import dev.nosferatu.bombaychitra.RoutingGraphException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface GraphState {
    /** The APK was built without the graph (see scripts/bombaychitra_assets.py); tracking works without the map. */
    data object NotBundled : GraphState
    data object Loading : GraphState
    data class Ready(val adapter: BombayChitraAdapter, val loadMs: Long, val builtAt: String?) : GraphState
    data class Failed(val message: String) : GraphState
}

/**
 * Loads BombayChitra's road and path graph for map matching. The graph ships in the APK as `graph.db` (BombayChitra's
 * `meta` and `routing` tables); SQLite needs a real file, so it is copied out of the APK once.
 *
 * A user may also place a full `bombaychitra.db` next to the tiles; it has the same tables and is preferred, so a
 * newer BombayChitra dataset can be used without rebuilding the app.
 */
class RoadGraphRepository(private val context: Context, private val mapData: MapDataRepository) {
    private val _state = MutableStateFlow<GraphState>(if (bundled()) GraphState.Loading else GraphState.NotBundled)
    val state: StateFlow<GraphState> = _state.asStateFlow()
    private val mutex = Mutex()

    private fun bundled(): Boolean = runCatching { context.assets.openFd(ASSET).close(); true }.getOrDefault(false) ||
        File(mapData.directory, FULL_DB).isFile

    /** Loads once; later calls return the loaded graph. */
    suspend fun load(): BombayChitraAdapter? = mutex.withLock {
        (_state.value as? GraphState.Ready)?.let { return it.adapter }
        withContext(Dispatchers.IO) {
            val file = File(mapData.directory, FULL_DB).takeIf { it.isFile } ?: extract() ?: run {
                _state.value = GraphState.NotBundled
                return@withContext null
            }
            _state.value = GraphState.Loading
            try {
                val t0 = SystemClock.elapsedRealtime()
                val adapter = AndroidBlobSource(file).use { BombayChitraAdapter.load(it) }
                val ms = SystemClock.elapsedRealtime() - t0
                _state.value = GraphState.Ready(adapter, ms, adapter.network.graph.meta["built_at"])
                adapter
            } catch (e: RoutingGraphException) {
                _state.value = GraphState.Failed(e.message ?: "invalid graph")
                null
            } catch (e: Exception) {
                _state.value = GraphState.Failed(e.message ?: e.javaClass.simpleName)
                null
            }
        }
    }

    private fun extract(): File? {
        val out = File(context.noBackupFilesDir, "graph.db")
        return try {
            context.assets.openFd(ASSET).use { fd ->
                if (out.isFile && out.length() == fd.length) return out
                val tmp = File(out.path + ".tmp")
                fd.createInputStream().use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                if (!tmp.renameTo(out)) throw IOException("could not move graph into place")
            }
            out
        } catch (e: IOException) {
            null
        }
    }

    private companion object {
        const val ASSET = "bombaychitra/graph.db"
        const val FULL_DB = "bombaychitra.db"
    }
}
