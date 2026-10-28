package dev.nosferatu.app.tracking

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dev.nosferatu.app.data.RunStore
import dev.nosferatu.app.data.RunSummary
import dev.nosferatu.app.data.TraceWriter
import dev.nosferatu.app.sensors.GpsSource
import dev.nosferatu.app.sensors.ImuSource
import dev.nosferatu.bombaychitra.BombayChitraAdapter
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.track.CorrectionEvent
import dev.nosferatu.engine.track.TrackSnapshot
import dev.nosferatu.engine.track.Tracker
import dev.nosferatu.engine.trace.TraceEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** [Starting] covers the gap between pressing start and the first sensor registration (loading the road graph). */
enum class SessionStatus { Starting, Tracking, Paused, Idle }

/** Everything the UI shows about the current run. */
data class LiveState(
    val status: SessionStatus = SessionStatus.Starting,
    val snapshot: TrackSnapshot? = null,
    val trail: List<List<LatLon>> = emptyList(),
    val rawFixes: List<LatLon> = emptyList(),
    val events: List<CorrectionEvent> = emptyList(),
    val matchedRoad: List<LatLon> = emptyList(),
    val elapsedMs: Long = 0,
    val runId: String? = null,
    val usingMap: Boolean = false,
    val gpsEnabled: Boolean = true,
    val permissionMissing: Boolean = false,
    val hasGyroscope: Boolean = true,
)

/**
 * One run, start to finish. All engine work happens on one dedicated thread — sensor callbacks, GPS callbacks and
 * the one-second tick are delivered there — so the tracker needs no locking and sees events in order.
 */
class TrackingSession(
    private val gps: GpsSource,
    private val imu: ImuSource,
    private val runs: RunStore,
    private val adapter: BombayChitraAdapter?,
) {
    private val thread = HandlerThread("nosferatu-tracking").apply { start() }
    private val handler = Handler(thread.looper)
    private val tracker = Tracker(adapter)
    private var gpsHandle: AutoCloseable? = null
    private var trace: TraceWriter? = null
    private val raw = ArrayDeque<LatLon>()

    private var startedWallMs = 0L
    private var activeSinceMs = 0L
    private var accumulatedMs = 0L
    private val runId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private val _state = MutableStateFlow(LiveState(runId = runId, usingMap = adapter != null, hasGyroscope = imu.hasGyroscope))
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val tick = object : Runnable {
        override fun run() {
            publish(tracker.tick(SystemClock.elapsedRealtime()))
            handler.postDelayed(this, 1000)
        }
    }

    fun start() = handler.post {
        startedWallMs = System.currentTimeMillis()
        trace = runs.openTrace(runId).also {
            it.write(TraceEvent.Note(SystemClock.elapsedRealtime(), "nosferatu run $runId; map ${if (adapter != null) "BombayChitra" else "none"}; gyro ${imu.hasGyroscope}"))
        }
        listen()
    }

    fun pause() = handler.post {
        if (_state.value.status != SessionStatus.Tracking) return@post
        stopListening()
        accumulatedMs += SystemClock.elapsedRealtime() - activeSinceMs
        _state.value = _state.value.copy(status = SessionStatus.Paused, elapsedMs = accumulatedMs)
    }

    fun resume() = handler.post {
        if (_state.value.status != SessionStatus.Paused) return@post
        tracker.resume()
        listen()
    }

    /** Stops tracking, saves the run, and releases the thread. [onSaved] runs on the tracking thread. */
    fun finish(onSaved: (RunSummary?) -> Unit) = handler.post {
        if (_state.value.status == SessionStatus.Tracking) accumulatedMs += SystemClock.elapsedRealtime() - activeSinceMs
        stopListening()
        trace?.close()
        trace = null
        val s = _state.value.snapshot
        val summary = s?.let {
            RunSummary(
                id = runId,
                startedAtWallMs = startedWallMs,
                durationMs = accumulatedMs,
                distanceM = it.distanceM,
                runningDistanceM = it.runningDistanceM,
                movingTimeMs = it.movingTimeMs,
                runningTimeMs = it.runningTimeMs,
                steps = it.stepsTotal,
                rejectedFixes = tracker.events.count { e -> e.kind == CorrectionEvent.Kind.Rejected },
                roadCorrections = tracker.events.count { e -> e.kind == CorrectionEvent.Kind.RoadPull },
                gpsLosses = tracker.events.count { e -> e.kind == CorrectionEvent.Kind.GpsLost },
                track = tracker.trail.map { stretch -> stretch.map { p -> listOf(p.lat, p.lon) } },
                usedMap = adapter != null,
            )
        }
        _state.value = _state.value.copy(status = SessionStatus.Idle, elapsedMs = accumulatedMs)
        onSaved(summary)
        thread.quitSafely()
    }

    private fun listen() {
        activeSinceMs = SystemClock.elapsedRealtime()
        gpsHandle = gps.register(
            thread.looper, 1000,
            onFix = { fix ->
                trace?.write(TraceEvent.Gps(fix))
                raw.addLast(fix.position)
                while (raw.size > MAX_RAW) raw.removeFirst()
                publish(tracker.onGps(fix))
            },
            onEnabledChanged = { enabled -> _state.value = _state.value.copy(gpsEnabled = enabled) },
        )
        imu.start(
            handler,
            onAccel = { s -> trace?.write(TraceEvent.Accel(s)); tracker.onAccel(s) },
            onGyro = { s -> trace?.write(TraceEvent.Gyro(s)); tracker.onGyro(s) },
        )
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
        _state.value = _state.value.copy(
            status = SessionStatus.Tracking,
            permissionMissing = gpsHandle == null,
            gpsEnabled = gps.isEnabled(),
        )
    }

    private fun stopListening() {
        handler.removeCallbacks(tick)
        gpsHandle?.close()
        gpsHandle = null
        imu.stop()
    }

    private fun publish(s: TrackSnapshot) {
        val segment = s.match?.candidate?.segment
        val now = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(
            snapshot = s,
            trail = tracker.trail,
            rawFixes = raw.toList(),
            events = tracker.events.takeLast(MAX_EVENTS),
            matchedRoad = if (segment != null && adapter != null) adapter.geometry(segment) else emptyList(),
            elapsedMs = accumulatedMs + if (_state.value.status == SessionStatus.Tracking) now - activeSinceMs else 0,
        )
    }

    private companion object {
        const val MAX_RAW = 600
        const val MAX_EVENTS = 120
    }
}
