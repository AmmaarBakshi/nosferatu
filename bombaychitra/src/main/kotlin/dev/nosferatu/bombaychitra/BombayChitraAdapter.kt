package dev.nosferatu.bombaychitra

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.road.RoadCandidate
import dev.nosferatu.engine.road.RoadNetwork

/** The answer to "where on the map is this?", including "I can't tell". */
sealed interface MapLookup {
    /** Outside BombayChitra's region: there is no map data to use. */
    data object OutsideCoverage : MapLookup

    /** Inside the region but no walkable way within the search radius: possibly an unmapped path. */
    data object NoWayNearby : MapLookup

    /** One clear nearest way. */
    data class OnWay(val candidate: RoadCandidate, val name: String?) : MapLookup

    /** Several ways are about equally close (parallel roads, junctions); the map alone cannot decide. */
    data class Ambiguous(val candidates: List<RoadCandidate>) : MapLookup
}

/** Whether a movement between two positions could have happened on foot along BombayChitra's network. */
data class MovePlausibility(
    val straightM: Double,
    /** Walking distance along the network, or null when not reachable within the bound or not on the network. */
    val networkM: Double?,
    val requiredSpeedMps: Double?,
    val plausible: Boolean,
)

/**
 * Nosferatu's single entry point into BombayChitra. It hides how BombayChitra stores its data (SQLite blobs, styles,
 * releases) and exposes only map questions, each of which can answer "unknown".
 */
class BombayChitraAdapter private constructor(val network: BombayChitraRoadNetwork) : RoadNetwork by network {

    /** Nearest walkable way, or an honest "outside / nothing / ambiguous". */
    fun lookup(p: LatLon, radiusM: Double = 30.0, ambiguityM: Double = 4.0): MapLookup {
        if (!covers(p)) return MapLookup.OutsideCoverage
        val c = candidates(p, radiusM)
        if (c.isEmpty()) return MapLookup.NoWayNearby
        val near = c.filter { it.distanceM - c.first().distanceM <= ambiguityM }
        // Two pieces of the same named street are not a real ambiguity.
        val distinctWays = near.distinctBy { segment(it.segment).name ?: "#${it.segment}" }
        return if (distinctWays.size > 1) MapLookup.Ambiguous(near) else MapLookup.OnWay(c.first(), segment(c.first().segment).name)
    }

    /**
     * Could someone on foot have gone from [a] to [b] in [elapsedS] seconds at no more than [maxSpeedMps]?
     * When either end is off the network, only the straight line can be judged.
     */
    fun plausibleMove(a: LatLon, b: LatLon, elapsedS: Double, maxSpeedMps: Double = 9.0, snapM: Double = 25.0): MovePlausibility {
        val straight = Geo.distance(a, b)
        val budget = maxSpeedMps * elapsedS.coerceAtLeast(0.5)
        val ca = candidates(a, snapM, limit = 3).firstOrNull()
        val cb = candidates(b, snapM, limit = 3).firstOrNull()
        val walk = if (ca != null && cb != null) networkDistance(ca, cb, budget * 1.5 + 50) else null
        val used = walk ?: straight
        val speed = used / elapsedS.coerceAtLeast(0.5)
        return MovePlausibility(straight, walk, speed, plausible = straight <= budget && (walk == null || walk <= budget + 2 * snapM))
    }

    companion object {
        fun load(source: RoutingBlobSource): BombayChitraAdapter =
            BombayChitraAdapter(BombayChitraRoadNetwork(BombayChitraGraph.load(source)))
    }
}
