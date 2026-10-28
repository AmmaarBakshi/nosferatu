package dev.nosferatu.engine.matching

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.road.RoadCandidate
import dev.nosferatu.engine.road.RoadNetwork
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

enum class MatchState {
    /** One way explains the recent fixes well. */
    Matched,
    /** Several ways remain plausible (parallel roads, a junction); the best is reported with low confidence. */
    Ambiguous,
    /** No mapped way explains the fixes: an unmapped path, a park, a compound. The position is kept as measured. */
    OffNetwork,
    /** Outside the map's coverage. */
    NoCoverage,
}

data class MatchResult(
    val state: MatchState,
    val candidate: RoadCandidate?,
    /** Posterior probability of the reported state (the best way, or off-network). */
    val confidence: Double,
    /** How many ways were considered for this fix. */
    val considered: Int,
    val wayName: String?,
) {
    companion object {
        val NO_COVERAGE = MatchResult(MatchState.NoCoverage, null, 1.0, 0, null)
    }
}

/**
 * Online hidden-Markov map matching over a [RoadNetwork] (after Newson & Krumm, "Hidden Markov Map Matching Through
 * Noise and Sparseness", 2009), filtered forward one fix at a time so it runs live.
 *
 * Hidden states are the walkable segments near each fix plus one *off-network* state. Emission: a Gaussian of the
 * distance from the fix to the segment (σ = the fix's accuracy) and agreement between the direction of travel and the
 * segment (walkers may go either way, so only the line matters). Transition: how well the walking distance along the
 * network between two candidates matches the straight-line distance between the fixes — a runner does not cross a
 * block between two fixes a second apart, which is what keeps the match on the right one of two parallel roads.
 *
 * The off-network state is always available. Its emission equals that of a way [offRoadSigmas] standard deviations
 * away, so when every way is further than that from the fixes, the matcher stops pretending.
 */
class HmmMapMatcher(
    private val network: RoadNetwork,
    private val searchRadiusM: Double = 40.0,
    /** Newson & Krumm's β: tolerance for route-vs-straight-line difference, metres. */
    private val beta: Double = 6.0,
    private val offRoadSigmas: Double = 2.2,
    private val maxCandidates: Int = 8,
) {
    private class Hyp(val candidate: RoadCandidate?, val logP: Double)

    private var previous: List<Hyp> = emptyList()
    private var previousFix: LatLon? = null
    private var previousTimeMs = 0L

    fun reset() {
        previous = emptyList()
        previousFix = null
    }

    /**
     * @param fix the GPS position (not the filtered one: the matcher must judge the measurement, not our model).
     * @param sigmaM its standard deviation.
     * @param headingDeg direction of travel if known (moving fast enough for a reliable course).
     */
    fun update(timeMs: Long, fix: LatLon, sigmaM: Double, headingDeg: Double? = null): MatchResult {
        if (!network.covers(fix)) {
            reset()
            return MatchResult.NO_COVERAGE
        }
        val sigma = sigmaM.coerceAtLeast(3.0)
        val radius = max(searchRadiusM, 3 * sigma).coerceAtMost(80.0)
        val candidates = network.candidates(fix, radius, maxCandidates)

        val emissions = candidates.map { emission(it, sigma, headingDeg) }
        val offEmission = logGauss(offRoadSigmas * sigma, sigma)

        val prevFix = previousFix
        val gap = (timeMs - previousTimeMs) / 1000.0
        val restart = prevFix == null || previous.isEmpty() || gap > MAX_GAP_S
        val straight = if (prevFix != null) Geo.distance(prevFix, fix) else 0.0
        val maxRoute = straight * 2 + 4 * sigma + 30

        val next = ArrayList<Hyp>(candidates.size + 1)
        for ((j, c) in candidates.withIndex()) {
            val logP = if (restart) emissions[j] else emissions[j] + previous.maxOf { h -> h.logP + transition(h.candidate, c, straight, maxRoute) }
            next += Hyp(c, logP)
        }
        val offLogP = if (restart) offEmission + ln(0.3) else offEmission + previous.maxOf { h -> h.logP + transition(h.candidate, null, straight, maxRoute) }
        next += Hyp(null, offLogP)

        // Normalise to posteriors (log-sum-exp) so values stay bounded over a long run.
        val m = next.maxOf { it.logP }
        val z = m + ln(next.sumOf { exp(it.logP - m) })
        val normalized = next.map { Hyp(it.candidate, it.logP - z) }
        if (normalized.all { it.logP.isInfinite() }) {
            reset()
            return update(timeMs, fix, sigmaM, headingDeg)
        }
        previous = normalized
        previousFix = fix
        previousTimeMs = timeMs

        val best = normalized.maxBy { it.logP }
        val pBest = exp(best.logP)
        val c = best.candidate ?: return MatchResult(MatchState.OffNetwork, null, pBest, candidates.size, null)
        val bestName = network.segment(c.segment).name
        // A rival is another way (not just the next piece of the same street) holding real probability.
        val rival = normalized.filter { it !== best && it.candidate != null }
            .filter { network.segment(it.candidate!!.segment).name.let { n -> n == null || n != bestName } }
            .maxOfOrNull { exp(it.logP) } ?: 0.0
        val state = if (pBest < 0.6 && rival > 0.2) MatchState.Ambiguous else MatchState.Matched
        return MatchResult(state, c, pBest, candidates.size, bestName)
    }

    private fun emission(c: RoadCandidate, sigma: Double, headingDeg: Double?): Double {
        var e = logGauss(c.distanceM, sigma)
        if (headingDeg != null) {
            // Bidirectional: compare lines, not directions. sin² is 0 when parallel, 1 when perpendicular.
            val d = Math.toRadians(Geo.headingDifference(c.bearingDeg, headingDeg))
            val s = sin(d)
            e -= HEADING_WEIGHT * s * s
        }
        val seg = network.segment(c.segment)
        // A flyover above the street looks the same to GPS; prefer the ground unless continuity says otherwise.
        if (seg.isBridge || seg.isTunnel) e -= 0.7
        return e
    }

    private fun transition(from: RoadCandidate?, to: RoadCandidate?, straight: Double, maxRoute: Double): Double = when {
        from == null && to == null -> ln(0.9)
        from == null || to == null -> ln(0.04)
        else -> {
            val route = network.networkDistance(from, to, maxRoute)
            if (route == null) UNREACHABLE else -abs(route - straight) / beta
        }
    }

    private fun logGauss(d: Double, sigma: Double): Double = -0.5 * (d / sigma) * (d / sigma) - ln(sigma)

    private companion object {
        const val MAX_GAP_S = 30.0
        const val HEADING_WEIGHT = 2.0
        /** Not impossible (the graph can be missing a link), just very unlikely. */
        const val UNREACHABLE = -14.0
    }
}
