package dev.nosferatu.bombaychitra

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.road.RoadCandidate
import dev.nosferatu.engine.road.RoadNetwork
import dev.nosferatu.engine.road.RoadSegment
import dev.nosferatu.engine.road.WayClass
import java.util.PriorityQueue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * [RoadNetwork] over BombayChitra's graph, restricted to ways a pedestrian may use (`seg_access` bit 4). Motorways,
 * sea links and freeways are closed to pedestrians in BombayChitra's data, so a runner is never matched to them.
 */
class BombayChitraRoadNetwork(
    val graph: BombayChitraGraph,
    private val bounds: Bounds = graph.meta["bounds"]?.let(Bounds::parse) ?: Bounds.MMR,
) : RoadNetwork {

    override fun covers(p: LatLon): Boolean = p in bounds

    override fun candidates(p: LatLon, radiusM: Double, limit: Int): List<RoadCandidate> {
        if (!covers(p)) return emptyList()
        val ky = Geo.toRadians(1.0) * Geo.EARTH_RADIUS_M
        val kx = ky * cos(Geo.toRadians(p.lat))
        val radiusDeg = radiusM / kx
        val out = ArrayList<RoadCandidate>()
        graph.segmentsNear(p.lon, p.lat, radiusDeg) { s ->
            if (!graph.footAllowed(s)) return@segmentsNear
            project(s, p, kx, ky)?.takeIf { it.distanceM <= radiusM }?.let(out::add)
        }
        out.sortBy { it.distanceM }
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    /** Nearest point of segment [s] to [p], in a local flat frame around [p]. */
    private fun project(s: Int, p: LatLon, kx: Double, ky: Double): RoadCandidate? {
        val range = graph.shapeRange(s)
        if (range.count() < 2) return null
        var best = Double.MAX_VALUE
        var bestX = 0.0; var bestY = 0.0; var bestOffset = 0.0; var bestBearing = 0.0
        var walked = 0.0
        var ax = (graph.lon(range.first) - p.lon) * kx
        var ay = (graph.lat(range.first) - p.lat) * ky
        for (i in range.first + 1..range.last) {
            val bx = (graph.lon(i) - p.lon) * kx
            val by = (graph.lat(i) - p.lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val nx = ax + t * dx; val ny = ay + t * dy
            val d = hypot(nx, ny)
            val len = kotlin.math.sqrt(len2)
            if (d < best) {
                best = d; bestX = nx; bestY = ny
                bestOffset = walked + t * len
                bestBearing = Geo.normalizeDegrees(Geo.toDegrees(atan2(dx, dy)))
            }
            walked += len
            ax = bx; ay = by
        }
        // Report offsets on BombayChitra's measured length so network distances add up consistently.
        val scale = if (walked > 0) graph.segLen[s] / walked else 1.0
        return RoadCandidate(
            segment = s,
            point = LatLon(p.lat + bestY / ky, p.lon + bestX / kx),
            distanceM = best,
            offsetM = bestOffset * scale,
            bearingDeg = bestBearing,
        )
    }

    override fun segment(id: Int): RoadSegment {
        val flags = graph.segFlags[id].toInt()
        return RoadSegment(
            id = id,
            name = graph.name(id),
            wayClass = wayClass(graph.segClass[id].toInt()),
            lengthM = graph.segLen[id].toDouble(),
            footAllowed = graph.footAllowed(id),
            isBridge = flags and BombayChitraGraph.FLAG_BRIDGE != 0,
            isTunnel = flags and BombayChitraGraph.FLAG_TUNNEL != 0,
            isSteps = flags and BombayChitraGraph.FLAG_STEPS != 0,
        )
    }

    override fun geometry(id: Int): List<LatLon> = graph.shapeRange(id).map { LatLon(graph.lat(it), graph.lon(it)) }

    override fun networkDistance(from: RoadCandidate, to: RoadCandidate, maxM: Double): Double? {
        val fromLen = graph.segLen[from.segment].toDouble()
        val toLen = graph.segLen[to.segment].toDouble()
        val fromOff = from.offsetM.coerceIn(0.0, fromLen)
        val toOff = to.offsetM.coerceIn(0.0, toLen)
        var best = if (from.segment == to.segment) kotlin.math.abs(toOff - fromOff) else Double.MAX_VALUE

        // Dijkstra from both ends of the starting segment; pedestrians may walk every foot segment both ways.
        val dist = HashMap<Int, Double>()
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        fun push(v: Int, d: Double) {
            if (d <= maxM && d < (dist[v] ?: Double.MAX_VALUE)) {
                dist[v] = d
                queue.add(d to v)
            }
        }
        push(graph.segFrom[from.segment], fromOff)
        push(graph.segTo[from.segment], fromLen - fromOff)
        val targetFrom = graph.segFrom[to.segment]
        val targetTo = graph.segTo[to.segment]
        while (queue.isNotEmpty()) {
            val (d, v) = queue.poll()
            if (d > (dist[v] ?: Double.MAX_VALUE) || d >= best) continue
            if (v == targetFrom) best = minOf(best, d + toOff)
            if (v == targetTo) best = minOf(best, d + (toLen - toOff))
            for (e in graph.offsets[v] until graph.offsets[v + 1]) {
                val s = graph.edgeSeg[e].let { if (it < 0) it.inv() else it }
                if (!graph.footAllowed(s)) continue
                push(graph.edgeTo[e], d + graph.segLen[s])
            }
        }
        return best.takeIf { it <= maxM }
    }

    companion object {
        /** BombayChitra's `seg_class`: 1 motorway … 5 tertiary, 6 minor, 7 service, 8 pedestrian, 9 track, 10 path. */
        fun wayClass(code: Int): WayClass = when (code) {
            in 1..5 -> WayClass.Major
            6 -> WayClass.Minor
            7 -> WayClass.Service
            8 -> WayClass.Pedestrian
            9 -> WayClass.Track
            10 -> WayClass.Path
            else -> WayClass.Other
        }
    }
}
