package dev.nosferatu.bombaychitra

import dev.nosferatu.engine.geo.LatLon
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs against the real BombayChitra graph when it has been extracted (`scripts/bombaychitra_assets.py graph`). */
class RealGraphTest {
    companion object {
        private var network: BombayChitraRoadNetwork? = null
        private var loadMs = 0L

        @BeforeClass
        @JvmStatic
        fun load() {
            val file = JdbcBlobSource.locate() ?: return
            val t0 = System.nanoTime()
            JdbcBlobSource(file).use { network = BombayChitraRoadNetwork(BombayChitraGraph.load(it)) }
            loadMs = (System.nanoTime() - t0) / 1_000_000
        }
    }

    private fun net(): BombayChitraRoadNetwork {
        assumeTrue("BombayChitra graph not extracted; skipping", network != null)
        return network!!
    }

    @Test
    fun matchesTheDocumentedSize() {
        val g = net().graph
        // docs/database.md and meta: these are the counts the pipeline records.
        assertEquals(g.meta["routing_vertices"]?.toInt(), g.vertexCount)
        assertEquals(g.meta["routing_segments"]?.toInt(), g.segmentCount)
        assertEquals(g.meta["routing_edges"]?.toInt(), g.edgeCount)
        assertEquals(2, g.version)
        println("graph loaded in $loadMs ms: ${g.vertexCount} vertices, ${g.segmentCount} segments")
    }

    @Test
    fun marineLinesHasWalkableWaysNearby() {
        // By Marine Lines station; the flyover above is open to pedestrians in OpenStreetMap, the road below too.
        val c = net().candidates(LatLon(18.9440, 72.8235), radiusM = 40.0)
        assertTrue(c.isNotEmpty(), "expected ways at Marine Lines")
        val names = c.mapNotNull { net().segment(it.segment).name }
        println("near Marine Lines: $names")
        assertTrue(c.first().distanceM < 40.0)
    }

    @Test
    fun walkingDistanceIsAtLeastTheStraightLine() {
        val a = net().candidates(LatLon(19.0596, 72.8295), 60.0).firstOrNull() // Bandra, Hill Road
        val b = net().candidates(LatLon(19.0544, 72.8402), 60.0).firstOrNull() // Bandra station (west)
        assertNotNull(a); assertNotNull(b)
        val straight = dev.nosferatu.engine.geo.Geo.distance(a.point, b.point)
        val walk = net().networkDistance(a, b, 5000.0)
        assertNotNull(walk, "Hill Road should reach Bandra station on foot")
        println("straight ${straight.toInt()} m, walk ${walk.toInt()} m")
        assertTrue(walk >= straight - 1.0)
        assertTrue(walk < straight * 2.0)
    }

    @Test
    fun candidateQueriesAreFastEnoughForOneHertz() {
        val n = net()
        val t0 = System.nanoTime()
        repeat(1000) { i -> n.candidates(LatLon(19.0 + i * 0.0001, 72.84), 50.0) }
        val perQueryUs = (System.nanoTime() - t0) / 1000 / 1000
        println("candidates: $perQueryUs µs per query")
        assertTrue(perQueryUs < 20_000)
    }
}
