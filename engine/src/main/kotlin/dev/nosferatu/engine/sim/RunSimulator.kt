package dev.nosferatu.engine.sim

import dev.nosferatu.engine.activity.Activity
import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.model.GpsFix
import dev.nosferatu.engine.trace.TraceEvent
import java.util.Random
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** A stretch of the run: how the person moves and for how long. */
data class Leg(val gait: Gait, val activity: Activity, val speedMps: Double, val durationS: Int) {
    companion object {
        fun stand(s: Int) = Leg(Gait.STILL, Activity.Stationary, 0.0, s)
        fun walk(s: Int, speed: Double = 1.35) = Leg(Gait.WALK, Activity.Walking, speed, s)
        fun briskWalk(s: Int, speed: Double = 2.0) = Leg(Gait.BRISK_WALK, Activity.Walking, speed, s)
        fun jog(s: Int, speed: Double = 2.4) = Leg(Gait.JOG, Activity.Jogging, speed, s)
        fun run(s: Int, speed: Double = 3.5) = Leg(Gait.RUN, Activity.Running, speed, s)
        fun ride(s: Int, speed: Double = 9.0) = Leg(Gait.VEHICLE, Activity.Unknown, speed, s)
    }
}

/**
 * GPS error as phones show it: white noise plus a bias that wanders slowly (multipath and atmosphere change over
 * tens of seconds), so consecutive fixes are wrong in the same direction — which is what makes GPS tracks wander
 * and what a filter cannot average away.
 */
data class GpsErrorModel(
    val noiseM: Double = 3.0,
    val biasM: Double = 3.0,
    val biasTauS: Double = 40.0,
    val reportedAccuracyM: Double = 5.0,
)

sealed interface GpsFault {
    val fromS: Int
    val toS: Int

    /** No fixes at all (a tunnel, an underpass, a dense canopy). */
    data class Outage(override val fromS: Int, override val toS: Int) : GpsFault

    /** Fixes displaced by a fixed offset while the platform still reports good accuracy (a multipath jump). */
    data class Jump(override val fromS: Int, override val toS: Int, val eastM: Double, val northM: Double) : GpsFault

    /** Noisier fixes with honestly reported poor accuracy (between tall buildings). */
    data class Degraded(override val fromS: Int, override val toS: Int, val noiseM: Double, val accuracyM: Double) : GpsFault
}

/**
 * Generates a complete trace (GPS at 1 Hz, accelerometer and gyroscope at 50 Hz, ground truth at 1 Hz) for a person
 * following [route] through [legs].
 */
class RunSimulator(
    private val route: List<LatLon>,
    private val legs: List<Leg>,
    private val gps: GpsErrorModel = GpsErrorModel(),
    private val faults: List<GpsFault> = emptyList(),
    private val seed: Long = 1,
    private val phoneRollDeg: Double = 80.0,
    private val phonePitchDeg: Double = 10.0,
    /** Seconds at which the phone is rotated in the hand (90° over 3 s). */
    private val phoneRotationsAtS: List<Int> = emptyList(),
    private val startMs: Long = 1_000_000,
) {
    init {
        require(route.size >= 2) { "a route needs two points" }
    }

    private val cumulative = DoubleArray(route.size).also { c ->
        for (i in 1 until route.size) c[i] = c[i - 1] + Geo.distance(route[i - 1], route[i])
    }
    val routeLengthM: Double get() = cumulative.last()

    /** Point and heading at [d] metres along the route (clamped to its ends). */
    fun along(d: Double): Pair<LatLon, Double> {
        val x = d.coerceIn(0.0, routeLengthM)
        var i = cumulative.indexOfLast { it <= x }.coerceIn(0, route.size - 2)
        while (i < route.size - 2 && cumulative[i + 1] - cumulative[i] < 1e-6) i++
        val segLen = cumulative[i + 1] - cumulative[i]
        val bearing = Geo.bearing(route[i], route[i + 1])
        return Geo.destination(route[i], bearing, (x - cumulative[i]).coerceAtMost(segLen)) to bearing
    }

    fun generate(): List<TraceEvent> {
        val rnd = Random(seed)
        val imu = ImuSimulator(seed = seed + 1, rollDeg = phoneRollDeg, pitchDeg = phonePitchDeg)
        val out = ArrayList<TraceEvent>()
        out += TraceEvent.Note(startMs, "simulated: ${legs.joinToString(" → ") { "${it.activity.name.lowercase()} ${it.durationS}s" }}")
        var distance = 0.0
        var biasE = 0.0
        var biasN = 0.0
        var second = 0
        var lastHeading = along(0.0).second
        for (leg in legs) {
            repeat(leg.durationS) {
                val t = startMs + second * 1000L
                val (pos, heading) = along(distance)
                out += TraceEvent.Truth(t, pos, leg.activity)

                // GPS fix for this second.
                val a = exp(-1.0 / gps.biasTauS)
                val drive = gps.biasM * sqrt(1 - a * a)
                biasE = a * biasE + drive * rnd.nextGaussian()
                biasN = a * biasN + drive * rnd.nextGaussian()
                val active = faults.filter { second >= it.fromS && second < it.toS }
                if (active.none { it is GpsFault.Outage }) {
                    var noise = gps.noiseM
                    var acc = gps.reportedAccuracyM
                    var e = biasE; var n = biasN
                    for (f in active) when (f) {
                        is GpsFault.Jump -> { e += f.eastM; n += f.northM }
                        is GpsFault.Degraded -> { noise = f.noiseM; acc = f.accuracyM }
                        is GpsFault.Outage -> Unit
                    }
                    e += noise * rnd.nextGaussian(); n += noise * rnd.nextGaussian()
                    val p = Geo.destination(Geo.destination(pos, 90.0, e), 0.0, n)
                    val speed = max(0.0, leg.speedMps + 0.15 * rnd.nextGaussian())
                    out += TraceEvent.Gps(
                        GpsFix(
                            timeMs = t + 950, // fixes arrive just before the next second, as on phones
                            lat = p.lat, lon = p.lon,
                            accuracyM = acc * (0.85 + 0.3 * rnd.nextDouble()),
                            speedMps = speed,
                            speedAccuracyMps = 0.3,
                            bearingDeg = if (speed > 0.5) Geo.normalizeDegrees(heading + 4 * rnd.nextGaussian()) else null,
                            altitudeM = 12.0,
                        ),
                    )
                }

                // IMU for this second: the turn needed to follow the route, and any rotation of the phone.
                val turn = Geo.headingDifference(lastHeading, heading).let { if (leg.speedMps > 0) it else 0.0 }
                lastHeading = heading
                val rotating = phoneRotationsAtS.any { second in it until it + 3 }
                imu.generate(t, 1000, leg.gait, turnRateDps = turn, rollRateDps = if (rotating) 30.0 else 0.0) { acc, gyro ->
                    acc?.let { out += TraceEvent.Accel(it) }
                    gyro?.let { out += TraceEvent.Gyro(it) }
                }
                distance += leg.speedMps
                second++
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
