package dev.nosferatu.engine.gps

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.model.GpsFix
import kotlin.math.hypot

/** Why a fix was rejected or trusted less. */
enum class FixIssue(val label: String) {
    OutOfOrder("older than the previous fix"),
    Duplicate("repeated fix"),
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
}

/**
 * Checks each fix on its own and against the previous accepted fix, before the filter sees it. It catches what is
 * wrong regardless of motion: bad timestamps, repeats, missing or terrible accuracy, and teleports faster than any
 * human. Model-based checks (innovation, map) come after, in the tracker.
 *
 * @param maxHumanSpeedMps fastest believable on-foot speed; elite sprinters touch 12 m/s for seconds.
 */
class GpsGate(private val maxHumanSpeedMps: Double = 12.0) {
    private var last: GpsFix? = null

    fun reset() {
        last = null
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
            val dt = (fix.timeMs - prev.timeMs) / 1000.0
            val d = Geo.distance(prev.position, fix.position)
            // Allow for both fixes' errors before calling it a teleport.
            val slack = 2.0 * hypot(acc, prev.accuracyM ?: acc)
            if (dt > 0 && (d - slack) / dt > maxHumanSpeedMps) return FixVerdict.Rejected(FixIssue.ImpossibleSpeed)
        }
        last = fix
        return if (issues.isEmpty()) FixVerdict.Accepted(sigma) else FixVerdict.DownWeighted(sigma, issues)
    }
}
