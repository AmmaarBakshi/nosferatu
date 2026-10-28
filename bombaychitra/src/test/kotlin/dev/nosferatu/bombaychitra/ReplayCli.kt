package dev.nosferatu.bombaychitra

import dev.nosferatu.engine.track.CorrectionEvent
import dev.nosferatu.engine.track.Tracker
import dev.nosferatu.engine.trace.TraceEvent
import dev.nosferatu.engine.trace.TraceFormat
import dev.nosferatu.engine.validation.Metrics
import dev.nosferatu.engine.validation.Replay
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Replays a saved run through the tracker on a laptop, with BombayChitra's real graph, and prints what happened.
 *
 *     ./gradlew :bombaychitra:replay --args="path/to/trace.txt [--no-map] [--geojson out.geojson] [--timeline 5]"
 *
 * Pull a run from a phone with `adb pull /sdcard/Android/data/dev.nosferatu/files/runs/<id>/trace.txt`. If the
 * trace has ground truth (`T` lines, as simulated traces do), accuracy metrics are printed too.
 */
fun main(args: Array<String>) {
    val positional = args.filterIndexed { i, a -> !a.startsWith("--") && (i == 0 || args[i - 1] !in setOf("--geojson", "--timeline")) }
    val trace = positional.firstOrNull()?.let(::resolve) ?: usage()
    val useMap = "--no-map" !in args
    val geojson = args.indexOf("--geojson").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) ?: usage() }?.let(::resolve)
    val timeline = args.indexOf("--timeline").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() ?: usage() }

    val events = trace.bufferedReader().use { TraceFormat.read(it) }
    val adapter = if (useMap) JdbcBlobSource.locate()?.let { f -> JdbcBlobSource(f).use { BombayChitraAdapter.load(it) } } else null
    if (useMap && adapter == null) System.err.println("No BombayChitra graph found; replaying without the map (see README).")

    val tracker = Tracker(adapter)
    val steps = Replay.run(events, tracker)
    val last = steps.lastOrNull()?.snapshot ?: run { println("Empty trace."); return }

    val gps = events.count { it is TraceEvent.Gps }
    val accel = events.count { it is TraceEvent.Accel }
    val gyro = events.count { it is TraceEvent.Gyro }
    val seconds = steps.size
    println("Trace     ${trace.name}: $gps GPS fixes, $accel accelerometer and $gyro gyroscope samples, ${seconds}s")
    events.filterIsInstance<TraceEvent.Note>().forEach { println("Note      ${it.text}") }
    println("Map       ${if (adapter != null) "BombayChitra (${adapter.network.graph.segmentCount} segments)" else "none"}")
    println("Distance  %.0f m on foot, %.0f m running, %d steps".format(Locale.US, last.distanceM, last.runningDistanceM, last.stepsTotal))
    println("Activity  " + share(steps.map { it.snapshot.activity.activity.name }))
    println("Position  " + share(steps.map { it.snapshot.source.label }))
    println("Map match " + share(steps.map { it.snapshot.match?.state?.name ?: "none" }))
    println("Corrected " + CorrectionEvent.Kind.entries.joinToString(", ") { "${it.name} ${tracker.eventCount(it)}" })

    if (steps.any { it.truth != null }) {
        val m = Metrics.of(steps)
        println(
            "Accuracy  error mean %.1f m, p95 %.1f m (raw %.1f / %.1f); distance %+.1f%% (raw %+.1f%%); running P %s R %s; false running %d s".format(
                Locale.US, m.correctedErrorMeanM, m.correctedErrorP95M, m.rawErrorMeanM, m.rawErrorP95M, m.distanceErrorPct,
                m.rawDistanceErrorPct, m.runningPrecision?.let { "%.2f".format(Locale.US, it) } ?: "-",
                m.runningRecall?.let { "%.2f".format(Locale.US, it) } ?: "-", m.falseRunningS,
            ),
        )
    }

    if (timeline != null) {
        println()
        println("     t   dist  speed  activity          source               match        σ")
        val t0 = steps.first().timeMs
        for (st in steps.filterIndexed { i, _ -> i % timeline == 0 }) {
            val s = st.snapshot
            println(
                "%6d %6.0f %6s  %-16s  %-19s  %-11s %5s".format(
                    Locale.US, (st.timeMs - t0) / 1000, s.distanceM, s.speedMps?.let { "%.1f".format(Locale.US, it) } ?: "-",
                    "${s.activity.activity.name} ${(100 * s.activity.confidence).toInt()}%", s.source.label,
                    s.match?.state?.name ?: "-", s.uncertaintyM?.let { "%.1f".format(Locale.US, it) } ?: "-",
                ),
            )
        }
    }

    if (geojson != null) {
        geojson.writeText(geoJson(events, tracker))
        println("GeoJSON   ${geojson.path} (raw fixes and corrected track)")
    }
}

/** Relative paths are taken from where Gradle was started, not the module directory. */
private fun resolve(path: String): File {
    val f = File(path)
    if (f.isAbsolute) return f
    return File(System.getProperty("nosferatu.root") ?: ".", path)
}

private fun share(labels: List<String>): String {
    val n = labels.size.coerceAtLeast(1)
    return labels.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        .joinToString(", ") { (k, v) -> "$k ${100 * v / n}%" }
}

private fun geoJson(events: List<TraceEvent>, tracker: Tracker): String {
    fun pt(lat: Double, lon: Double) = String.format(Locale.US, "[%.7f,%.7f]", lon, lat)
    val raw = events.filterIsInstance<TraceEvent.Gps>().joinToString(",") { pt(it.fix.lat, it.fix.lon) }
    val track = tracker.trail.filter { it.size >= 2 }.joinToString(",") { s -> s.joinToString(",", "[", "]") { pt(it.lat, it.lon) } }
    return """{"type":"FeatureCollection","features":[""" +
        """{"type":"Feature","properties":{"name":"raw GPS"},"geometry":{"type":"MultiPoint","coordinates":[$raw]}},""" +
        """{"type":"Feature","properties":{"name":"corrected"},"geometry":{"type":"MultiLineString","coordinates":[$track]}}]}"""
}

private fun usage(): Nothing {
    System.err.println("usage: replay TRACE [--no-map] [--geojson OUT] [--timeline SECONDS]")
    exitProcess(2)
}
