package dev.nosferatu.app.data

import dev.nosferatu.engine.road.RoadNetwork
import dev.nosferatu.engine.trace.TraceEvent
import dev.nosferatu.engine.trace.TraceFormat
import dev.nosferatu.engine.track.Tracker
import dev.nosferatu.engine.validation.Replay
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A run whose process died before it was saved (revoking the location permission kills the app, and the system may
 * kill it for memory) still has its trace on disk. Replaying the trace through the tracker gives back the run as it
 * would have been saved, up to the trace's last flush.
 */
object RunRecovery {
    /** Parses a trace that may end mid-line; the unterminated last line is dropped rather than half-read. */
    fun readTrace(text: String): List<TraceEvent> {
        val complete = if (text.endsWith('\n')) text else text.substringBeforeLast('\n', "")
        return complete.lineSequence().mapNotNull(TraceFormat::decode).toList()
    }

    /** Time spent tracking: from the start, or a resume, to the next pause or the end of the trace. */
    fun activeMs(events: List<TraceEvent>): Long {
        if (events.isEmpty()) return 0
        var total = 0L
        var since: Long? = events.first().timeMs
        for (e in events) {
            if (e !is TraceEvent.Note) continue
            when (e.text) {
                TraceFormat.PAUSE -> {
                    since?.let { total += e.timeMs - it }
                    since = null
                }
                TraceFormat.RESUME -> if (since == null) since = e.timeMs
            }
        }
        since?.let { total += events.last().timeMs - it }
        return total
    }

    /** The run in [events], or null when the trace ends before the tracker produced anything. */
    fun recover(id: String, startedAtWallMs: Long, events: List<TraceEvent>, network: RoadNetwork?): RunSummary? {
        val tracker = Tracker(network)
        val last = Replay.run(events, tracker).lastOrNull()?.snapshot ?: return null
        return summaryOf(id, startedAtWallMs, activeMs(events), last, tracker, usedMap = network != null, recovered = true)
    }

    /** Whether the run was recorded with BombayChitra's graph, from the note the app writes first. */
    fun usedMap(events: List<TraceEvent>): Boolean =
        events.firstOrNull { it is TraceEvent.Note && it.text.startsWith("nosferatu run") }
            ?.let { (it as TraceEvent.Note).text.contains("map BombayChitra") } ?: false

    /** The wall-clock start in a run id (`yyyyMMdd-HHmmss`, local time), or null for another kind of id. */
    fun startedAt(id: String): Long? = runCatching { SimpleDateFormat(ID_FORMAT, Locale.US).parse(id)?.time }.getOrNull()

    const val ID_FORMAT = "yyyyMMdd-HHmmss"
}
