package dev.nosferatu.bombaychitra

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.road.WayClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BombayChitraRoadNetworkTest {
    private val origin = LatLon(19.0600, 72.8400) // Bandra
    private val fixture = GraphFixture.grid(origin)
    private val network = BombayChitraRoadNetwork(BombayChitraGraph.load(fixture.source()))
    private fun east(m: Double) = Geo.destination(origin, 90.0, m)
    private fun at(eastM: Double, northM: Double) = Geo.destination(east(eastM), 0.0, northM)

    @Test
    fun decodesShapesFromDeltas() {
        val g = network.graph
        assertEquals(16, g.vertexCount)
        assertEquals(24, g.segmentCount)
        assertEquals(48, g.edgeCount)
        val shape = network.geometry(0)
        assertEquals(3, shape.size)
        assertEquals(0.0, Geo.distance(shape.first(), origin), 0.05)
        assertEquals(0.0, Geo.distance(shape.last(), east(100.0)), 0.05)
    }

    @Test
    fun findsNearestCandidatesWithOffsetsAndBearings() {
        // 8 m north of the first row, 30 m along it.
        val c = network.candidates(at(30.0, 8.0), radiusM = 25.0)
        val best = c.first()
        assertEquals("Row 0", network.segment(best.segment).name)
        assertEquals(8.0, best.distanceM, 0.3)
        assertEquals(30.0, best.offsetM, 0.5)
        assertEquals(90.0, best.bearingDeg, 0.5)
        assertTrue(c.all { it.distanceM <= 25.0 })
        assertTrue(c.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
    }

    @Test
    fun nothingNearbyMeansNoCandidates() {
        assertTrue(network.candidates(at(50.0, 50.0), radiusM = 20.0).isEmpty()) // the middle of a block
        assertTrue(network.candidates(LatLon(18.52, 73.85), radiusM = 100.0).isEmpty()) // Pune: outside the map
    }

    @Test
    fun walkingDistanceFollowsTheStreets() {
        val a = network.candidates(at(50.0, 0.0), 5.0).first() // middle of Row 0, first block
        val b = network.candidates(at(100.0, 50.0), 5.0).first() // middle of Column 1, first block
        // 50 m east to the corner, then 50 m north.
        assertEquals(100.0, network.networkDistance(a, b, 1000.0)!!, 1.0)
        val sameSeg = network.candidates(at(80.0, 0.0), 5.0).first()
        assertEquals(30.0, network.networkDistance(a, sameSeg, 1000.0)!!, 1.0)
        assertNull(network.networkDistance(a, b, maxM = 60.0))
    }

    @Test
    fun pedestriansAreNeverMatchedToMotorways() {
        val f = GraphFixture.grid(origin, n = 2)
        f.add(GraphFixture.Seg(listOf(at(0.0, -20.0), at(100.0, -20.0)), access = BombayChitraGraph.ACCESS_CAR, cls = 1, name = "Expressway"))
        val n = BombayChitraRoadNetwork(BombayChitraGraph.load(f.source()))
        val c = n.candidates(at(50.0, -19.0), 30.0)
        assertTrue(c.none { n.segment(it.segment).name == "Expressway" })
        assertNotNull(c.firstOrNull { n.segment(it.segment).name == "Row 0" })
        assertEquals(WayClass.Minor, n.segment(c.first().segment).wayClass)
    }

    @Test
    fun rejectsInconsistentGraphs() {
        val good = fixture.source()
        val broken = object : RoutingBlobSource {
            override fun blob(name: String) = if (name == "offsets") good.blob("seg_shape") else good.blob(name)
            override fun meta() = good.meta()
        }
        assertFailsWith<RoutingGraphException> { BombayChitraGraph.load(broken) }
        val missing = object : RoutingBlobSource {
            override fun blob(name: String) = if (name == "shape") null else good.blob(name)
            override fun meta() = good.meta()
        }
        assertFailsWith<RoutingGraphException> { BombayChitraGraph.load(missing) }
    }
}
