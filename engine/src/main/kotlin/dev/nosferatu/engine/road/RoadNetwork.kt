package dev.nosferatu.engine.road

import dev.nosferatu.engine.geo.LatLon

/** What kind of way a segment is, coarsely, for weighing how likely a runner is to be on it. */
enum class WayClass { Major, Minor, Service, Pedestrian, Path, Track, Other }

/** A road or path segment: the way between two junctions. */
data class RoadSegment(
    val id: Int,
    val name: String?,
    val wayClass: WayClass,
    val lengthM: Double,
    val footAllowed: Boolean,
    val isBridge: Boolean,
    val isTunnel: Boolean,
    val isSteps: Boolean,
)

/** A position projected onto a segment. */
data class RoadCandidate(
    val segment: Int,
    /** The nearest point on the segment. */
    val point: LatLon,
    /** Distance from the query position to [point], metres. */
    val distanceM: Double,
    /** Distance from the segment's start to [point] along its shape, metres. */
    val offsetM: Double,
    /** Direction of the segment's shape at [point], degrees from north. Walkers can go both ways. */
    val bearingDeg: Double,
)

/**
 * The geographic knowledge the tracking engine may use. The engine only ever talks to this interface; the
 * BombayChitra adapter implements it from BombayChitra's routing graph, and tests implement it with toy grids.
 *
 * Every method must be honest about missing data: [covers] is false outside the map, and an empty candidate list
 * means "no mapped way here", which the matcher treats as possibly-unmapped, never as an error.
 */
interface RoadNetwork {
    /** True when the network has data for this position (inside the map's region). */
    fun covers(p: LatLon): Boolean

    /** Segments within [radiusM] of [p] that a pedestrian may use, nearest first. */
    fun candidates(p: LatLon, radiusM: Double, limit: Int = 12): List<RoadCandidate>

    fun segment(id: Int): RoadSegment

    /** The segment's shape from start to end. */
    fun geometry(id: Int): List<LatLon>

    /**
     * Shortest walking distance from [from] to [to] along the network, in metres, or null when [to] is not reachable
     * within [maxM]. Both points may lie mid-segment.
     */
    fun networkDistance(from: RoadCandidate, to: RoadCandidate, maxM: Double): Double?
}
