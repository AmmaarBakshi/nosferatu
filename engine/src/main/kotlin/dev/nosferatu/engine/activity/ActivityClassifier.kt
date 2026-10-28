package dev.nosferatu.engine.activity

import dev.nosferatu.engine.motion.MotionFeatures
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

enum class Activity { Stationary, Walking, Jogging, Running, Unknown }

/** One piece of evidence behind a classification, for the UI and for debugging. */
data class Evidence(val label: String, val value: String, val favours: Activity?)

data class ActivityEstimate(
    val activity: Activity,
    /** Posterior probability of [activity]. */
    val confidence: Double,
    val posterior: Map<Activity, Double>,
    val evidence: List<Evidence>,
) {
    /** Jogging and running are both "running" for distance and the run log. */
    val isRunning: Boolean get() = activity == Activity.Jogging || activity == Activity.Running

    companion object {
        val UNKNOWN = ActivityEstimate(Activity.Unknown, 1.0, mapOf(Activity.Unknown to 1.0), emptyList())
    }
}

/**
 * Classifies activity with a hidden Markov model filtered forward once per update (about once a second).
 *
 * Gait, not speed, separates walking from running: a running gait has a cadence around 150–190 steps per minute and
 * hard landings (vertical peaks several times a walk's), while a brisk walk at slow-jog speed keeps a walking cadence
 * and soft landings. GPS speed is used as soft evidence with a wide, flat range for running, so a fast runner at
 * 15 km/h and a slow one at 8 km/h are both running. Jogging is a running gait at an easy pace (under ~10 km/h).
 * Moving fast with no steps at all is *unknown* (a vehicle, a bicycle, a phone on a dashboard), never running.
 *
 * The sticky transition matrix means one odd second doesn't flip the state; a real change takes 2–4 seconds of
 * consistent evidence.
 */
class ActivityClassifier {
    private val states = Activity.entries
    private var belief = DoubleArray(states.size) { 1.0 / states.size }

    fun reset() {
        belief = DoubleArray(states.size) { 1.0 / states.size }
    }

    /**
     * @param gpsSpeedMps Doppler speed from a fix judged trustworthy, or null.
     * @param gpsSpeedSigma its uncertainty.
     */
    fun update(motion: MotionFeatures?, gpsSpeedMps: Double?, gpsSpeedSigma: Double? = null): ActivityEstimate {
        val evidence = ArrayList<Evidence>()
        val logL = DoubleArray(states.size)

        if (motion != null) {
            for ((i, s) in states.withIndex()) logL[i] += ln(pStill(s, motion.still))
            if (motion.still) evidence += Evidence("Phone", "still", Activity.Stationary)

            val cadence = motion.cadenceSpm
            for ((i, s) in states.withIndex()) logL[i] += ln(pHasCadence(s, cadence != null))
            if (cadence != null) {
                val w = 0.5 + 0.5 * motion.cadenceRegularity
                for ((i, s) in states.withIndex()) logL[i] += w * logCadence(s, cadence)
                evidence += Evidence("Cadence", "${cadence.toInt()} spm", if (cadence >= 145) Activity.Running else Activity.Walking)
            } else {
                evidence += Evidence("Cadence", "no steps", null)
            }
            val peak = motion.stepPeak
            if (peak != null) {
                for ((i, s) in states.withIndex()) logL[i] += logImpact(s, peak)
                evidence += Evidence("Landing impact", "%.1f m/s²".format(peak), if (peak >= 5.0) Activity.Running else Activity.Walking)
            }
        }

        if (gpsSpeedMps != null) {
            // Uncertain speeds weigh less; speed never outweighs a clear gait.
            val sigma = gpsSpeedSigma ?: 0.5
            val w = (0.8 / sqrt(1.0 + sigma * sigma)).coerceIn(0.2, 0.8)
            for ((i, s) in states.withIndex()) logL[i] += w * logSpeed(s, gpsSpeedMps, sigma)
            evidence += Evidence("GPS speed", "%.1f km/h".format(gpsSpeedMps * 3.6), speedFavours(gpsSpeedMps))
        }

        if (motion == null && gpsSpeedMps == null) return ActivityEstimate.UNKNOWN.copy(evidence = listOf(Evidence("Sensors", "no data", null)))

        // Forward step: predict with the transition matrix, then weigh by the evidence.
        val predicted = DoubleArray(states.size)
        for (to in states.indices) for (from in states.indices) predicted[to] += belief[from] * TRANSITION[from][to]
        val maxL = logL.max()
        var total = 0.0
        for (i in states.indices) {
            predicted[i] *= kotlin.math.exp(logL[i] - maxL)
            total += predicted[i]
        }
        for (i in states.indices) predicted[i] = max(predicted[i] / total, 1e-6)
        val norm = predicted.sum()
        belief = DoubleArray(states.size) { predicted[it] / norm }

        val best = belief.indices.maxBy { belief[it] }
        val posterior = states.associateWith { belief[it.ordinal] }
        val activity = if (belief[best] < 0.45) Activity.Unknown else states[best]
        return ActivityEstimate(activity, belief[best], posterior, evidence)
    }

    private fun pStill(s: Activity, still: Boolean): Double {
        val p = when (s) {
            Activity.Stationary -> 0.8
            Activity.Walking -> 0.03
            Activity.Jogging, Activity.Running -> 0.01
            Activity.Unknown -> 0.1
        }
        return if (still) p else 1 - p
    }

    private fun pHasCadence(s: Activity, has: Boolean): Double {
        val p = when (s) {
            Activity.Stationary -> 0.03
            Activity.Walking -> 0.9
            Activity.Jogging, Activity.Running -> 0.95
            Activity.Unknown -> 0.15
        }
        return if (has) p else 1 - p
    }

    private fun logCadence(s: Activity, spm: Double): Double = when (s) {
        Activity.Stationary -> logGauss(spm, 110.0, 40.0)
        Activity.Walking -> logGauss(spm, 112.0, 12.0)
        Activity.Jogging -> logGauss(spm, 160.0, 11.0)
        Activity.Running -> logGauss(spm, 172.0, 13.0)
        Activity.Unknown -> logGauss(spm, 130.0, 45.0)
    }

    /** Landing impact on a log scale: a run lands three to four times harder than a walk. */
    private fun logImpact(s: Activity, peak: Double): Double {
        val x = ln(peak.coerceAtLeast(0.1))
        return when (s) {
            Activity.Stationary -> logGauss(x, ln(1.5), 0.8)
            Activity.Walking -> logGauss(x, ln(2.6), 0.4)
            Activity.Jogging -> logGauss(x, ln(6.5), 0.38)
            Activity.Running -> logGauss(x, ln(9.0), 0.38)
            Activity.Unknown -> logGauss(x, ln(3.0), 0.9)
        }
    }

    /**
     * Speed likelihoods. Running is flat from 2.8 to 7.5 m/s (10–27 km/h): any pace in that range is equally
     * runner-like, so speed alone never decides that someone fast is not running.
     */
    private fun logSpeed(s: Activity, v: Double, sigma: Double): Double {
        val sd = { base: Double -> sqrt(base * base + sigma * sigma) }
        return when (s) {
            Activity.Stationary -> logGauss(v, 0.0, sd(0.35))
            Activity.Walking -> logGauss(v, 1.35, sd(0.4))
            Activity.Jogging -> logGauss(v, 2.4, sd(0.45))
            Activity.Running -> {
                // A plateau between 2.8 and 7.5 m/s with Gaussian shoulders on either side.
                val low = sd(0.5)
                val plateau = -ln(low)
                when {
                    v < 2.8 -> logGauss(v, 2.8, low)
                    v > 7.5 -> plateau - 0.5 * ((v - 7.5) / sd(1.0)).let { it * it }
                    else -> plateau
                }
            }
            Activity.Unknown -> logGauss(v, 6.0, sd(6.0))
        }
    }

    private fun speedFavours(v: Double): Activity = when {
        v < 0.4 -> Activity.Stationary
        v < 1.9 -> Activity.Walking
        v < 2.8 -> Activity.Jogging
        v < 8.0 -> Activity.Running
        else -> Activity.Unknown
    }

    private fun logGauss(x: Double, mean: Double, sd: Double): Double {
        val z = (x - mean) / sd
        return -0.5 * z * z - ln(sd)
    }

    private companion object {
        /** Per-second transitions; order Stationary, Walking, Jogging, Running, Unknown. */
        val TRANSITION = arrayOf(
            doubleArrayOf(0.90, 0.06, 0.015, 0.005, 0.02),
            doubleArrayOf(0.05, 0.88, 0.04, 0.01, 0.02),
            doubleArrayOf(0.015, 0.04, 0.88, 0.045, 0.02),
            doubleArrayOf(0.01, 0.015, 0.05, 0.905, 0.02),
            doubleArrayOf(0.05, 0.05, 0.03, 0.03, 0.84),
        )
    }
}
