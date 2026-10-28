package dev.nosferatu.engine.trace

import dev.nosferatu.engine.activity.Activity
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.model.AccelSample
import dev.nosferatu.engine.model.GpsFix
import dev.nosferatu.engine.model.GyroSample
import java.io.BufferedReader
import java.io.Writer
import java.util.Locale

/** One recorded input, in time order. */
sealed interface TraceEvent {
    val timeMs: Long

    data class Gps(val fix: GpsFix) : TraceEvent { override val timeMs get() = fix.timeMs }
    data class Accel(val sample: AccelSample) : TraceEvent { override val timeMs get() = sample.timeMs }
    data class Gyro(val sample: GyroSample) : TraceEvent { override val timeMs get() = sample.timeMs }

    /** Ground truth, present in simulated traces and in annotated real ones. */
    data class Truth(override val timeMs: Long, val position: LatLon, val activity: Activity) : TraceEvent

    /** A free-text note: the scenario name, the phone model, "phone moved to pocket"… */
    data class Note(override val timeMs: Long, val text: String) : TraceEvent
}

/**
 * Nosferatu's trace format: UTF-8 text, one event per line, comma separated, so traces diff, grep and compress well.
 *
 * ```
 * # nosferatu-trace 1
 * n,0,scenario: parallel roads
 * g,<t>,<lat>,<lon>,<accuracy>,<speed>,<speedAccuracy>,<bearing>,<altitude>   empty field = not reported
 * a,<t>,<x>,<y>,<z>        accelerometer, m/s², phone frame
 * y,<t>,<x>,<y>,<z>        gyroscope, rad/s, phone frame
 * T,<t>,<lat>,<lon>,<activity>
 * n,<t>,pause / n,<t>,resume   the runner paused and resumed tracking
 * ```
 * Times are milliseconds on the device's monotonic clock (elapsedRealtime).
 */
object TraceFormat {
    const val HEADER = "# nosferatu-trace 1"

    /** Notes the app writes when the runner pauses and resumes, so a replay leaves the paused time out. */
    const val PAUSE = "pause"
    const val RESUME = "resume"

    fun write(e: TraceEvent, out: Writer) {
        out.write(encode(e))
        out.write("\n")
    }

    fun encode(e: TraceEvent): String = when (e) {
        is TraceEvent.Gps -> with(e.fix) {
            "g,$timeMs,${f(lat, 7)},${f(lon, 7)},${o(accuracyM, 1)},${o(speedMps, 2)},${o(speedAccuracyMps, 2)},${o(bearingDeg, 1)},${o(altitudeM, 1)}"
        }
        is TraceEvent.Accel -> with(e.sample) { "a,$timeMs,${f(x, 3)},${f(y, 3)},${f(z, 3)}" }
        is TraceEvent.Gyro -> with(e.sample) { "y,$timeMs,${f(x, 4)},${f(y, 4)},${f(z, 4)}" }
        is TraceEvent.Truth -> "T,${e.timeMs},${f(e.position.lat, 7)},${f(e.position.lon, 7)},${e.activity.name}"
        is TraceEvent.Note -> "n,${e.timeMs},${e.text.replace('\n', ' ')}"
    }

    /** Parses one line; returns null for comments, blank lines and unknown record types (forward compatible). */
    fun decode(line: String): TraceEvent? {
        if (line.isBlank() || line.startsWith("#")) return null
        val p = line.split(',')
        val t = p.getOrNull(1)?.toLongOrNull() ?: return null
        fun d(i: Int) = p.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        return when (p[0]) {
            "g" -> TraceEvent.Gps(GpsFix(t, d(2) ?: return null, d(3) ?: return null, d(4), d(5), d(6), d(7), d(8)))
            "a" -> TraceEvent.Accel(AccelSample(t, d(2) ?: return null, d(3) ?: return null, d(4) ?: return null))
            "y" -> TraceEvent.Gyro(GyroSample(t, d(2) ?: return null, d(3) ?: return null, d(4) ?: return null))
            "T" -> TraceEvent.Truth(t, LatLon(d(2) ?: return null, d(3) ?: return null), runCatching { Activity.valueOf(p[4]) }.getOrDefault(Activity.Unknown))
            "n" -> TraceEvent.Note(t, line.substringAfter(',').substringAfter(','))
            else -> null
        }
    }

    fun read(reader: BufferedReader): List<TraceEvent> = reader.lineSequence().mapNotNull(::decode).toList()

    fun writeAll(events: List<TraceEvent>, out: Writer) {
        out.write(HEADER)
        out.write("\n")
        for (e in events) write(e, out)
    }

    private fun f(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)
    private fun o(v: Double?, digits: Int) = v?.let { f(it, digits) } ?: ""
}
