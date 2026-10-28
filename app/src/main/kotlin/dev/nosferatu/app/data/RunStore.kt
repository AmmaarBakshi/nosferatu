package dev.nosferatu.app.data

import android.content.Context
import dev.nosferatu.engine.trace.TraceEvent
import dev.nosferatu.engine.trace.TraceFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File

/** What the run log shows for one run. Positions are `[lat, lon]` pairs of the corrected track. */
@Serializable
data class RunSummary(
    val id: String,
    val startedAtWallMs: Long,
    val durationMs: Long,
    val distanceM: Double,
    val runningDistanceM: Double,
    val movingTimeMs: Long,
    val runningTimeMs: Long,
    val steps: Int,
    val rejectedFixes: Int,
    val roadCorrections: Int,
    val gpsLosses: Int,
    val track: List<List<List<Double>>>,
    val usedMap: Boolean,
)

/**
 * Runs on disk: `runs/<id>/summary.json` and `runs/<id>/trace.txt`, the raw sensor stream in Nosferatu's trace format,
 * so any run can be replayed through a newer engine and raw and corrected results compared.
 */
class RunStore(context: Context) {
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "runs").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _runs = MutableStateFlow<List<RunSummary>>(emptyList())
    val runs: StateFlow<List<RunSummary>> = _runs.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _runs.value = root.listFiles().orEmpty()
            .mapNotNull { dir -> File(dir, "summary.json").takeIf { it.isFile }?.let { runCatching { json.decodeFromString(RunSummary.serializer(), it.readText()) }.getOrNull() } }
            .sortedByDescending { it.startedAtWallMs }
    }

    fun traceFile(id: String): File = File(File(root, id), "trace.txt")

    fun openTrace(id: String): TraceWriter {
        val dir = File(root, id).apply { mkdirs() }
        return TraceWriter(File(dir, "trace.txt").bufferedWriter())
    }

    suspend fun save(summary: RunSummary) = withContext(Dispatchers.IO) {
        val dir = File(root, summary.id).apply { mkdirs() }
        File(dir, "summary.json").writeText(json.encodeToString(RunSummary.serializer(), summary))
        refresh()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        refresh()
    }
}

/** Appends trace events; only used from the tracking thread. */
class TraceWriter(private val out: BufferedWriter) : AutoCloseable {
    private var count = 0

    init {
        out.write(TraceFormat.HEADER)
        out.newLine()
    }

    fun write(e: TraceEvent) {
        TraceFormat.write(e, out)
        if (++count % 2_000 == 0) out.flush()
    }

    override fun close() {
        out.flush()
        out.close()
    }
}
