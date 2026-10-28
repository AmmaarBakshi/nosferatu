package dev.nosferatu.engine.gps

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.model.GpsFix
import kotlin.math.hypot

/** Why a fix was rejected or trusted less. */
enum class FixIssue(val label: String) {
    OutOfOrder("older than the previous fix"),
    Duplicate("repeated fix"),
    Stale("repeats the last position while moving"),
    Invalid("invalid coordinates"),
    NoAccuracy("no accuracy reported"),
    VeryPoorAccuracy("accuracy worse than 50 m"),
    PoorAccuracy("accuracy worse than 20 m"),
    ImpossibleSpeed("implies an impossible speed"),
    Inconsistent("disagrees with the predicted position"),
    OffNetwork("far from any mapped way"),
    ImplausibleRoute("no walkable route explains the move"),
}

/** The verdict on one fix. [sigmaM] is the standard deviation the filter should use for it. */
sealed interface FixVerdict {
    data class Accepted(val sigmaM: Double) : FixVerdict
    data class DownWeighted(val sigmaM: Double, val issues: List<FixIssue>) : FixVerdict
    data class Rejected(val issue: FixIssue) : FixVerdict

    /**
     * Several fixes in a row were "teleports" from the last good fix but agree with each other: the last good fix
     * (or the start) was the outlier, not them. The filter should start again from this fix.
     */
    data class Reanchored(val sigmaM: Double) : FixVerdict
}

/**
 * Checks each fix on its own and against the previous accepted fix, before the filter sees it. It catches what is
 * wrong regardless of motion: bad timestamps, repeats, missing or terrible accuracy, and teleports faster than any
 * human. Model-based checks (innovation, map) come after, in the tracker.
 *
 * A teleport is judged against the last good fix, so if that fix was the bad one (a wrong first fix, a long
 * multipath excursion that ended) every correct fix after it would look like a teleport until enough time passed
 * to make the jump "possible". To avoid locking the truth out, [reanchorFixes] consecutive teleports that agree
 * with each other at human speed become the new reference.
 *
 * @param maxHumanSpeedMps fastest believable on-foot speed; elite sprinters touch 12 m/s for seconds.
 */
class GpsGate(private val maxHumanSpeedMps: Double = 12.0, private val reanchorFixes: Int = 5) {
    private var last: GpsFix? = null
    private val teleports = ArrayList<GpsFix>()

    fun reset() {
        last = null
        teleports.clear()
    }

    fun assess(fix: GpsFix): FixVerdict {
        val prev = last
        if (prev != null && fix.timeMs < prev.timeMs) return FixVerdict.Rejected(FixIssue.OutOfOrder)
        if (prev != null && fix.timeMs == prev.timeMs) return FixVerdict.Rejected(FixIssue.Duplicate)
        if (!fix.lat.isFinite() || !fix.lon.isFinite() || fix.lat !in -90.0..90.0 || fix.lon !in -180.0..180.0 ||
            (fix.lat == 0.0 && fix.lon == 0.0)
        ) {
            return FixVerdict.Rejected(FixIssue.Invalid)
        }
        val acc = fix.accuracyM ?: return FixVerdict.Rejected(FixIssue.NoAccuracy)
        if (acc > 50.0) return FixVerdict.Rejected(FixIssue.VeryPoorAccuracy)

        val issues = ArrayList<FixIssue>()
        // Android's accuracy is a 68 % radius, so it serves as a 1-sigma in each axis; never trust below 3 m.
        var sigma = acc.coerceAtLeast(3.0)
        if (acc > 20.0) {
            issues += FixIssue.PoorAccuracy
            sigma *= 1.5
        }
        if (prev != null) {
            // A receiver that is moving cannot report exactly the same position twice: the fix is a stale copy
            // (the emulator does this during "outages", and so do some chipsets). Static hold reports speed ~0.
            if (fix.lat == prev.lat && fix.lon == prev.lon && (fix.speedMps ?: 0.0) > 0.5) {
                return FixVerdict.Rejected(FixIssue.Stale)
            }
            if (!possible(prev, fix)) {
                teleports += fix
                if (teleports.size > reanchorFixes) teleports.removeAt(0)
                if (teleports.size == reanchorFixes && teleports.zipWithNext().all { (a, b) -> possible(a, b) }) {
                    teleports.clear()
                    last = fix
                    return FixVerdict.Reanchored(sigma)
                }
                return FixVerdict.Rejected(FixIssue.ImpossibleSpeed)
            }
        }
        teleports.clear()
        last = fix
        return if (issues.isEmpty()) FixVerdict.Accepted(sigma) else FixVerdict.DownWeighted(sigma, issues)
    }

    /** Could a person on foot have moved from [a] to [b], allowing for both fixes' errors? */
    private fun possible(a: GpsFix, b: GpsFix): Boolean {
        val dt = (b.timeMs - a.timeMs) / 1000.0
        val accA = a.accuracyM ?: 20.0
        val accB = b.accuracyM ?: 20.0
        val slack = 2.0 * hypot(accA, accB)
        return dt <= 0 || (Geo.distance(a.position, b.position) - slack) / dt <= maxHumanSpeedMps
    }
}
