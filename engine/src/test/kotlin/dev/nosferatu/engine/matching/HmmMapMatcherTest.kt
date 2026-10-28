package dev.nosferatu.engine.matching

import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.road.TestNetwork
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HmmMapMatcherTest {
    private val origin = LatLon(19.06, 72.84)

    /** Feeds noisy fixes along a path given in local metres, once a second; returns results. */
    private fun feed(net: TestNetwork, path: List<Pair<Double, Double>>, noise: Double, seed: Long = 1, heading: (Int) -> Double? = { null }): List<MatchResult> {
        val m = HmmMapMatcher(net)
        val rnd = Random(seed)
        return path.mapIndexed { i, (x, y) ->
            m.update(i * 1000L, net.at(x + rnd.nextGaussian() * noise, y + rnd.nextGaussian() * noise), noise, heading(i))
        }
    }

    private fun line(x0: Double, y0: Double, x1: Double, y1: Double, steps: Int) =
        (0..steps).map { i -> x0 + (x1 - x0) * i / steps to y0 + (y1 - y0) * i / steps }

    @Test
    fun staysOnTheStreetBeingRun() {
        val net = TestNetwork(origin).street(0.0, 0.0, 600.0, 0.0, "Linking Road").street(300.0, -200.0, 300.0, 200.0, "Cross Road")
        val r = feed(net, line(0.0, 0.0, 600.0, 0.0, 180), noise = 6.0) // 3.3 m/s
        val onLinking = r.count { it.wayName == "Linking Road" && it.state != MatchState.OffNetwork }
        assertTrue(onLinking >= r.size * 0.95, "only $onLinking of ${r.size}")
    }

    @Test
    fun keepsTheRightOneOfTwoParallelRoads() {
        // Two parallel roads 22 m apart; the runner is on the south one, the GPS noise often lands nearer the north.
        val net = TestNetwork(origin)
            .street(0.0, 0.0, 800.0, 0.0, "South Road")
            .street(0.0, 22.0, 800.0, 22.0, "North Road")
            .street(0.0, 0.0, 0.0, 22.0, "Link West")
            .street(800.0, 0.0, 800.0, 22.0, "Link East")
        val path = line(20.0, 0.0, 780.0, 0.0, 240)
        var nearestWrong = 0
        val rnd = Random(5)
        val m = HmmMapMatcher(net)
        val results = path.mapIndexed { i, (x, y) ->
            val fy = y + rnd.nextGaussian() * 9.0
            if (fy > 11.0) nearestWrong++
            m.update(i * 1000L, net.at(x + rnd.nextGaussian() * 9.0, fy), 9.0, 90.0)
        }
        val right = results.drop(10).count { it.wayName == "South Road" }
        assertTrue(nearestWrong > 20, "test needs fixes that snap to the wrong road ($nearestWrong)")
        assertTrue(right >= (results.size - 10) * 0.9, "nearest-road snapping would be wrong $nearestWrong times; HMM right $right of ${results.size - 10}")
    }

    @Test
    fun unmappedPathIsLeftAlone() {
        // A path through a park, 70 m from the nearest street.
        val net = TestNetwork(origin).street(0.0, 0.0, 500.0, 0.0, "Park Road").street(0.0, 140.0, 500.0, 140.0, "Top Road")
        val r = feed(net, line(50.0, 70.0, 450.0, 70.0, 120), noise = 5.0)
        val off = r.count { it.state == MatchState.OffNetwork }
        assertTrue(off >= r.size * 0.9, "off-network $off of ${r.size}")
    }

    @Test
    fun followsATurnAtAJunction() {
        val net = TestNetwork(origin).street(0.0, 0.0, 300.0, 0.0, "East Street").street(300.0, 0.0, 300.0, 300.0, "North Street")
        val path = line(0.0, 0.0, 300.0, 0.0, 90) + line(300.0, 0.0, 300.0, 300.0, 90).drop(1)
        val r = feed(net, path, noise = 5.0)
        assertTrue(r.subList(5, 80).count { it.wayName == "East Street" } >= 70)
        assertTrue(r.subList(105, r.size).count { it.wayName == "North Street" } >= (r.size - 105) * 0.95)
    }

    @Test
    fun reportsAmbiguityWhenTheMapCannotDecide() {
        // Starting right between two equally close parallel roads, with no history yet.
        val net = TestNetwork(origin).street(0.0, 0.0, 300.0, 0.0, "A Road").street(0.0, 12.0, 300.0, 12.0, "B Road")
        val first = HmmMapMatcher(net).update(0, net.at(100.0, 6.0), 8.0)
        assertEquals(MatchState.Ambiguous, first.state)
        assertTrue(first.confidence < 0.6)
    }

    @Test
    fun outsideCoverage() {
        val net = TestNetwork(origin, coverRadiusM = 1000.0).street(0.0, 0.0, 100.0, 0.0, "X")
        assertEquals(MatchState.NoCoverage, HmmMapMatcher(net).update(0, LatLon(18.52, 73.85), 5.0).state)
    }

    @Test
    fun prefersTheGroundToAFlyoverAbove() {
        val net = TestNetwork(origin).street(0.0, 0.0, 400.0, 0.0, "SV Road").street(0.0, 3.0, 400.0, 3.0, "SV Road Flyover", bridge = true)
        val r = feed(net, line(0.0, 0.0, 400.0, 0.0, 120), noise = 6.0)
        assertTrue(r.drop(5).count { it.wayName == "SV Road" } >= (r.size - 5) * 0.85)
    }
}
