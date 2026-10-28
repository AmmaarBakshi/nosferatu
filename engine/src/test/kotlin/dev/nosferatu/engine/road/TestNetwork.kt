package dev.nosferatu.engine.road

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.geo.LocalFrame
import dev.nosferatu.engine.geo.Vec2
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A tiny [RoadNetwork] built from straight streets in a local metric frame, for engine tests. Streets are given in
 * metres (east, north) from [origin]; streets meeting at the same point are connected.
 */
class TestNetwork(val origin: LatLon, private val coverRadiusM: Double = 5_000.0) : RoadNetwork {
    val frame = LocalFrame(origin)
    private data class Street(val a: Vec2, val b: Vec2, val name: String, val bridge: Boolean)
    private val streets = ArrayList<Street>()

    fun street(ax: Double, ay: Double, bx: Double, by: Double, name: String, bridge: Boolean = false): TestNetwork {
        streets += Street(Vec2(ax, ay), Vec2(bx, by), name, bridge)
        return this
    }

    fun at(x: Double, y: Double): LatLon = frame.toLatLon(x, y)

    override fun covers(p: LatLon) = Geo.distance(origin, p) <= coverRadiusM

    override fun candidates(p: LatLon, radiusM: Double, limit: Int): List<RoadCandidate> {
        if (!covers(p)) return emptyList()
        val q = frame.toLocal(p)
        return streets.indices.map { i -> project(i, q) }.filter { it.distanceM <= radiusM }.sortedBy { it.distanceM }.take(limit)
    }

    private fun project(i: Int, q: Vec2): RoadCandidate {
        val s = streets[i]
        val d = s.b - s.a
        val t = ((q - s.a).dot(d) / d.dot(d)).coerceIn(0.0, 1.0)
        val n = s.a + d * t
        return RoadCandidate(i, frame.toLatLon(n), (q - n).length(), t * d.length(), d.heading())
    }

    override fun segment(id: Int) = streets[id].let {
        RoadSegment(id, it.name, WayClass.Minor, (it.b - it.a).length(), footAllowed = true, isBridge = it.bridge, isTunnel = false, isSteps = false)
    }

    override fun geometry(id: Int) = listOf(frame.toLatLon(streets[id].a), frame.toLatLon(streets[id].b))

    /** Dijkstra over street endpoints; ends closer than 0.5 m are the same junction. */
    override fun networkDistance(from: RoadCandidate, to: RoadCandidate, maxM: Double): Double? {
        if (from.segment == to.segment) return abs(from.offsetM - to.offsetM).takeIf { it <= maxM }
        val nodes = ArrayList<Vec2>()
        fun node(v: Vec2): Int {
            val i = nodes.indexOfFirst { hypot(it.x - v.x, it.y - v.y) < 0.5 }
            if (i >= 0) return i
            nodes += v; return nodes.size - 1
        }
        val ends = streets.map { node(it.a) to node(it.b) }
        val dist = HashMap<Int, Double>()
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        fun push(n: Int, d: Double) {
            if (d <= maxM && d < (dist[n] ?: Double.MAX_VALUE)) { dist[n] = d; pq += d to n }
        }
        val fl = segment(from.segment).lengthM
        push(ends[from.segment].first, from.offsetM)
        push(ends[from.segment].second, fl - from.offsetM)
        while (pq.isNotEmpty()) {
            val (d, n) = pq.poll()
            if (d > (dist[n] ?: Double.MAX_VALUE)) continue
            ends.forEachIndexed { s, (a, b) ->
                val len = segment(s).lengthM
                if (a == n) push(b, d + len)
                if (b == n) push(a, d + len)
            }
        }
        val tl = segment(to.segment).lengthM
        val (ta, tb) = ends[to.segment]
        val best = minOf((dist[ta] ?: Double.MAX_VALUE) + to.offsetM, (dist[tb] ?: Double.MAX_VALUE) + tl - to.offsetM)
        return best.takeIf { it <= maxM }
    }
}
