package dev.nosferatu.bombaychitra

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.floor

/**
 * BombayChitra's road and path graph, decoded from its `routing` table exactly as BombayChitra's server does it
 * (`server/internal/routing/graph.go`): gzip-compressed little-endian arrays, segment shapes stored as a first point
 * plus deltas, edges in compressed sparse row form and `~s` marking an edge that runs against its segment's shape.
 *
 * Immutable after [load]; safe to share between threads.
 */
class BombayChitraGraph private constructor(
    /** lon, lat × 1e7 per vertex. */
    val nodes: IntArray,
    /** CSR: edges of vertex v are offsets[v] until offsets[v+1]. */
    val offsets: IntArray,
    val edgeTo: IntArray,
    /** Segment index, or its bitwise not when the edge runs against the shape. */
    val edgeSeg: IntArray,
    val segLen: FloatArray,
    /** CSR offsets into [shape], in points. */
    val segShape: IntArray,
    /** Absolute lon, lat × 1e7 per shape point (deltas already resolved). */
    val shape: IntArray,
    val segClass: ByteArray,
    val segAccess: ByteArray,
    val segFlags: ByteArray,
    val segName: IntArray,
    val names: List<String>,
    val segFrom: IntArray,
    val segTo: IntArray,
    val version: Int,
    val meta: Map<String, String>,
    private val grid: HashMap<Long, IntArray>,
) {
    val vertexCount: Int get() = offsets.size - 1
    val segmentCount: Int get() = segLen.size
    val edgeCount: Int get() = edgeTo.size

    fun shapeRange(s: Int): IntRange = segShape[s] until segShape[s + 1]
    fun lon(point: Int): Double = shape[2 * point] / E7
    fun lat(point: Int): Double = shape[2 * point + 1] / E7
    fun vertexLon(v: Int): Double = nodes[2 * v] / E7
    fun vertexLat(v: Int): Double = nodes[2 * v + 1] / E7

    fun name(s: Int): String? = segName[s].takeIf { it >= 0 && it < names.size }?.let { names[it] }?.ifEmpty { null }
    fun footAllowed(s: Int): Boolean = segAccess[s].toInt() and ACCESS_FOOT != 0

    /** Segments whose bounding box touches the grid cells around [lon], [lat] within [radiusDeg]. */
    fun segmentsNear(lon: Double, lat: Double, radiusDeg: Double, visit: (Int) -> Unit) {
        val x0 = cell(lon - radiusDeg)
        val x1 = cell(lon + radiusDeg)
        val y0 = cell(lat - radiusDeg)
        val y1 = cell(lat + radiusDeg)
        val seen = HashSet<Int>()
        for (cx in x0..x1) for (cy in y0..y1) {
            val list = grid[key(cx, cy)] ?: continue
            for (s in list) if (seen.add(s)) visit(s)
        }
    }

    companion object {
        const val E7 = 1e7
        /** Snap index cell, ~550 m; the same as BombayChitra's server. */
        const val GRID_DEG = 0.005

        const val ACCESS_CAR = 1
        const val ACCESS_TWO_WHEELER = 2
        const val ACCESS_FOOT = 4

        const val FLAG_TOLL = 1
        const val FLAG_STEPS = 2
        const val FLAG_BRIDGE = 4
        const val FLAG_TUNNEL = 8

        private fun cell(deg: Double): Long = floor(deg / GRID_DEG).toLong()
        private fun key(cx: Long, cy: Long): Long = (cx shl 32) or (cy and 0xffffffffL)

        /** Reads and validates the graph. */
        fun load(source: RoutingBlobSource): BombayChitraGraph {
            val meta = source.meta()
            fun raw(name: String, required: Boolean = true): ByteArray? {
                val gz = source.blob(name) ?: if (required) throw RoutingGraphException("routing blob '$name' missing") else return null
                return try {
                    GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
                } catch (e: java.io.IOException) {
                    throw RoutingGraphException("routing blob '$name' is not valid gzip", e)
                }
            }
            val nodes = ints(raw("nodes")!!)
            val offsets = ints(raw("offsets")!!)
            val edgeTo = ints(raw("edge_to")!!)
            val edgeSeg = ints(raw("edge_seg")!!)
            val segLen = floats(raw("seg_len")!!)
            val segShape = ints(raw("seg_shape")!!)
            val shape = ints(raw("shape")!!)
            val segClass = raw("seg_class")!!
            val segDir = raw("seg_dir")!!
            val segNameArr = ints(raw("seg_name")!!)
            val segAccessRaw = raw("seg_access", required = false)
            val segFlagsRaw = raw("seg_flags", required = false)
            val names = raw("names", required = false)?.toString(Charsets.UTF_8)?.split('\n') ?: emptyList()

            val vertices = offsets.size - 1
            val segments = segLen.size
            val edges = edgeTo.size
            if (vertices < 1 || nodes.size != 2 * vertices || edgeSeg.size != edges || segShape.size != segments + 1 ||
                segClass.size != segments || segDir.size != segments || segNameArr.size != segments ||
                offsets[vertices] != edges || segShape[segments] * 2 != shape.size
            ) {
                throw RoutingGraphException("routing graph arrays are inconsistent")
            }
            // Version 2 graphs carry access and flags; version 1 graphs were cars only and have no footpaths.
            val version = if (segAccessRaw != null) 2 else 1
            val segAccess = segAccessRaw ?: ByteArray(segments) { ACCESS_CAR.toByte() }
            val segFlags = segFlagsRaw ?: ByteArray(segments)
            if (segAccess.size != segments || segFlags.size != segments) {
                throw RoutingGraphException("routing graph access arrays are inconsistent")
            }

            val segFrom = IntArray(segments)
            val segTo = IntArray(segments)
            for (v in 0 until vertices) {
                for (e in offsets[v] until offsets[v + 1]) {
                    val s = edgeSeg[e]
                    if (s >= 0) {
                        segFrom[s] = v; segTo[s] = edgeTo[e]
                    } else {
                        val r = s.inv()
                        segFrom[r] = edgeTo[e]; segTo[r] = v
                    }
                }
            }
            // Shapes are stored as a first point plus deltas.
            for (s in 0 until segments) {
                for (p in segShape[s] + 1 until segShape[s + 1]) {
                    shape[2 * p] += shape[2 * p - 2]
                    shape[2 * p + 1] += shape[2 * p - 1]
                }
            }
            val grid = buildGrid(segShape, shape, segments)
            return BombayChitraGraph(
                nodes, offsets, edgeTo, edgeSeg, segLen, segShape, shape, segClass, segAccess, segFlags,
                segNameArr, names, segFrom, segTo, version, meta, grid,
            )
        }

        private fun buildGrid(segShape: IntArray, shape: IntArray, segments: Int): HashMap<Long, IntArray> {
            val lists = HashMap<Long, IntList>(segments / 2)
            for (s in 0 until segments) {
                var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
                for (p in segShape[s] until segShape[s + 1]) {
                    val x = shape[2 * p]; val y = shape[2 * p + 1]
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                for (cx in cell(minX / E7)..cell(maxX / E7)) for (cy in cell(minY / E7)..cell(maxY / E7)) {
                    lists.getOrPut(key(cx, cy)) { IntList() }.add(s)
                }
            }
            val grid = HashMap<Long, IntArray>(lists.size * 2)
            for ((k, v) in lists) grid[k] = v.toArray()
            return grid
        }

        private fun ints(b: ByteArray): IntArray {
            val out = IntArray(b.size / 4)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out)
            return out
        }

        private fun floats(b: ByteArray): FloatArray {
            val out = FloatArray(b.size / 4)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }
    }
}

/** A growable int list without boxing. */
internal class IntList {
    private var data = IntArray(4)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray(): IntArray = data.copyOf(size)
}
