package dev.nosferatu.app.data

import android.content.Context
import dev.nosferatu.engine.track.CorrectionEvent
import dev.nosferatu.engine.track.TrackSnapshot
import dev.nosferatu.engine.track.Tracker
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
    /** Rebuilt from its trace after the app was closed mid-run; the last few seconds may be missing. */
    val recovered: Boolean = false,
)

/** The summary of a run as [tracker] ended it; [last] is the tracker's last output. */
fun summaryOf(
    id: String,
    startedAtWallMs: Long,
    durationMs: Long,
    last: TrackSnapshot,
    tracker: Tracker,
    usedMap: Boolean,
    recovered: Boolean = false,
) = RunSummary(
    id = id,
    startedAtWallMs = startedAtWallMs,
    durationMs = durationMs,
    distanceM = last.distanceM,
    runningDistanceM = last.runningDistanceM,
    movingTimeMs = last.movingTimeMs,
    runningTimeMs = last.runningTimeMs,
    steps = last.stepsTotal,
    rejectedFixes = tracker.eventCount(CorrectionEvent.Kind.Rejected),
    roadCorrections = tracker.eventCount(CorrectionEvent.Kind.RoadPull),
    gpsLosses = tracker.eventCount(CorrectionEvent.Kind.GpsLost),
    track = tracker.trail.map { stretch -> stretch.map { p -> listOf(p.lat, p.lon) } },
    usedMap = usedMap,
    recovered = recovered,
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

    /** Runs with a trace but no summary: the app died before saving them. */
    suspend fun unfinished(): List<String> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().filter { File(it, "trace.txt").isFile && !File(it, "summary.json").isFile }.map { it.name }
    }

    suspend fun readTrace(id: String): List<TraceEvent> = withContext(Dispatchers.IO) { RunRecovery.readTrace(traceFile(id).readText()) }

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

/**
 * Appends trace events; only used from the tracking thread. Flushes every few seconds of trace time, so if the process
 * is killed mid-run (a revoked permission does that) the run can still be recovered from what reached the disk.
 */
class TraceWriter(private val out: BufferedWriter) : AutoCloseable {
    private var flushedAtMs = 0L

    init {
        out.write(TraceFormat.HEADER)
        out.newLine()
    }

    fun write(e: TraceEvent) {
        TraceFormat.write(e, out)
        if (e.timeMs - flushedAtMs >= FLUSH_MS) {
            flush()
            flushedAtMs = e.timeMs
        }
    }

    fun flush() = out.flush()

    override fun close() {
        out.flush()
        out.close()
    }
}

private const val FLUSH_MS = 5_000L
