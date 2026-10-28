package dev.nosferatu.app.tracking

import android.content.Context
import dev.nosferatu.app.data.RoadGraphRepository
import dev.nosferatu.app.data.RunRecovery
import dev.nosferatu.app.data.RunStore
import dev.nosferatu.app.data.RunSummary
import dev.nosferatu.app.sensors.GpsSource
import dev.nosferatu.app.sensors.ImuSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's single run: creates a [TrackingSession] on start, keeps the foreground service alive while it runs, and
 * saves it on stop. Lives in the application, not a screen, so a run survives the UI being destroyed.
 */
class TrackingController(
    private val context: Context,
    private val gps: GpsSource,
    private val imu: ImuSource,
    private val runs: RunStore,
    private val graph: RoadGraphRepository,
    private val scope: CoroutineScope,
) {
    private var session: TrackingSession? = null
    private var forward: Job? = null

    private val _live = MutableStateFlow<LiveState?>(null)
    /** The current run, or null when none is in progress. */
    val live: StateFlow<LiveState?> = _live.asStateFlow()

    private val _lastSaved = MutableStateFlow<RunSummary?>(null)
    /** The run most recently finished, for the summary shown after stopping. */
    val lastSaved: StateFlow<RunSummary?> = _lastSaved.asStateFlow()

    fun start() {
        if (session != null || _live.value != null) return
        _live.value = LiveState(status = SessionStatus.Starting)
        // Start the service first so the run is protected even if the user leaves the app while the graph loads.
        TrackingService.start(context)
        scope.launch {
            val adapter = graph.load()
            val s = TrackingSession(gps, imu, runs, adapter)
            session = s
            forward = launch { s.state.collect { _live.value = it } }
            s.start()
        }
    }

    fun pause() {
        session?.pause()
    }

    fun resume() {
        session?.resume()
    }

    fun stop() {
        val s = session ?: return
        session = null
        s.finish { summary ->
            scope.launch {
                if (summary != null) runs.save(summary)
                _lastSaved.value = summary
                forward?.cancel()
                forward = null
                _live.value = null
                TrackingService.stop(context)
            }
        }
    }

    fun dismissSummary() {
        _lastSaved.value = null
    }

    /**
     * Saves the runs the app died in the middle of, rebuilt from their traces (see [RunRecovery]); called once at
     * launch. A trace too short to give the tracker a single second is removed. Returns how many runs were saved.
     */
    suspend fun recoverUnfinished(): Int {
        val ids = runs.unfinished().filter { it != session?.runId && it != _live.value?.runId }
        var saved = 0
        for (id in ids) {
            val events = runs.readTrace(id)
            val adapter = if (RunRecovery.usedMap(events)) graph.load() else null
            val startedAt = RunRecovery.startedAt(id) ?: runs.traceFile(id).lastModified()
            val summary = withContext(Dispatchers.Default) { RunRecovery.recover(id, startedAt, events, adapter) }
            if (summary != null) {
                runs.save(summary)
                saved++
            } else {
                runs.delete(id)
            }
        }
        return saved
    }
}
