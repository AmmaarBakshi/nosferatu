package dev.nosferatu.bombaychitra

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

/**
 * Builds a routing table in BombayChitra's on-disk format (docs/database.md, "Routing graph") from a list of
 * segments, so decoding can be tested without the 230 MB database.
 */
class GraphFixture {
    data class Seg(val shape: List<LatLon>, val access: Int = BombayChitraGraph.ACCESS_FOOT or BombayChitraGraph.ACCESS_CAR, val cls: Int = 6, val name: String? = null, val flags: Int = 0)

    private val vertices = ArrayList<LatLon>()
    private val segs = ArrayList<Pair<Seg, IntArray>>() // segment, [from, to]

    fun vertex(p: LatLon): Int {
        val i = vertices.indexOfFirst { Geo.distance(it, p) < 0.01 }
        if (i >= 0) return i
        vertices += p
        return vertices.size - 1
    }

    fun add(seg: Seg): Int {
        segs += seg to intArrayOf(vertex(seg.shape.first()), vertex(seg.shape.last()))
        return segs.size - 1
    }

    fun source(meta: Map<String, String> = mapOf("bounds" to "[72.7,18.8,73.85,19.82]", "routing_version" to "2")): RoutingBlobSource {
        val n = vertices.size
        // Every segment gets an edge each way (pedestrians may walk both ways), from the vertex it leaves.
        val out = Array(n) { ArrayList<Pair<Int, Int>>() } // (to, edgeSeg)
        segs.forEachIndexed { s, (_, ft) ->
            out[ft[0]] += ft[1] to s
            out[ft[1]] += ft[0] to s.inv()
        }
        val offsets = IntArray(n + 1)
        for (v in 0 until n) offsets[v + 1] = offsets[v] + out[v].size
        val edgeTo = out.flatMap { l -> l.map { it.first } }.toIntArray()
        val edgeSeg = out.flatMap { l -> l.map { it.second } }.toIntArray()
        val segShape = IntArray(segs.size + 1)
        val shape = ArrayList<Int>()
        segs.forEachIndexed { s, (seg, _) ->
            segShape[s + 1] = segShape[s] + seg.shape.size
            var px = 0; var py = 0
            seg.shape.forEachIndexed { i, p ->
                val x = (p.lon * 1e7).roundToInt(); val y = (p.lat * 1e7).roundToInt()
                if (i == 0) { shape += x; shape += y } else { shape += x - px; shape += y - py }
                px = x; py = y
            }
        }
        val names = segs.mapNotNull { it.first.name }.distinct()
        val blobs = mapOf(
            "nodes" to ints(vertices.flatMap { listOf((it.lon * 1e7).roundToInt(), (it.lat * 1e7).roundToInt()) }.toIntArray()),
            "offsets" to ints(offsets),
            "edge_to" to ints(edgeTo),
            "edge_seg" to ints(edgeSeg),
            "edge_time" to floats(FloatArray(edgeTo.size) { 10f }),
            "seg_len" to floats(segs.map { (seg, _) -> seg.shape.zipWithNext { a, b -> Geo.distance(a, b) }.sum().toFloat() }.toFloatArray()),
            "seg_shape" to ints(segShape),
            "shape" to ints(shape.toIntArray()),
            "seg_class" to segs.map { it.first.cls.toByte() }.toByteArray(),
            "seg_dir" to ByteArray(segs.size) { 3 },
            "seg_access" to segs.map { it.first.access.toByte() }.toByteArray(),
            "seg_speed" to floats(FloatArray(segs.size) { 20f }),
            "seg_flags" to segs.map { it.first.flags.toByte() }.toByteArray(),
            "seg_name" to ints(segs.map { (seg, _) -> seg.name?.let(names::indexOf) ?: -1 }.toIntArray()),
            "names" to names.joinToString("\n").toByteArray(),
        ).mapValues { gzip(it.value) }
        return object : RoutingBlobSource {
            override fun blob(name: String) = blobs[name]
            override fun meta() = meta
        }
    }

    companion object {
        private fun gzip(b: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(b) }
            return bos.toByteArray()
        }

        private fun ints(v: IntArray): ByteArray {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asIntBuffer().put(v)
            return bb.array()
        }

        private fun floats(v: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(v)
            return bb.array()
        }

        /** A grid of streets [spacingM] apart, [n]×[n] junctions, south-west corner at [origin]. */
        fun grid(origin: LatLon, n: Int = 4, spacingM: Double = 100.0): GraphFixture {
            val f = GraphFixture()
            fun at(i: Int, j: Int) = Geo.destination(Geo.destination(origin, 90.0, i * spacingM), 0.0, j * spacingM)
            for (j in 0 until n) for (i in 0 until n - 1) {
                // A midpoint makes every shape exercise the delta decoding.
                val a = at(i, j); val b = at(i + 1, j)
                f.add(Seg(listOf(a, LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2), b), name = "Row $j"))
            }
            for (i in 0 until n) for (j in 0 until n - 1) {
                f.add(Seg(listOf(at(i, j), at(i, j + 1)), name = "Column $i"))
            }
            return f
        }
    }
}
