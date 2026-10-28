package dev.nosferatu.engine.track

import dev.nosferatu.engine.activity.ActivityEstimate
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.gps.FixIssue
import dev.nosferatu.engine.matching.MatchResult
import dev.nosferatu.engine.model.GpsFix
import dev.nosferatu.engine.motion.MotionFeatures

/** Where the current position estimate comes from. */
enum class PositionSource(val label: String) {
    None("no position"),
    Gps("GPS"),
    Fused("GPS + motion"),
    RoadAware("GPS + motion + map"),
    DeadReckoning("steps and gyroscope"),
    Held("last known position"),
}

enum class GpsQuality(val label: String) { Good("good"), Fair("fair"), Poor("poor"), Lost("lost"), Off("off") }

/** Something the tracker did to the raw data, kept so corrections are visible and auditable. */
data class CorrectionEvent(
    val timeMs: Long,
    val position: LatLon,
    val kind: Kind,
    val issue: FixIssue? = null,
    val detail: String = "",
) {
    enum class Kind { Rejected, DownWeighted, Recovered, GpsLost, GpsRegained, RoadPull }
}

/** The tracker's complete view of the moment, produced after every input. */
data class TrackSnapshot(
    val timeMs: Long,
    val position: LatLon?,
    /** 1-sigma horizontal uncertainty, metres. */
    val uncertaintyM: Double?,
    val speedMps: Double?,
    val headingDeg: Double?,
    val source: PositionSource,
    val gpsQuality: GpsQuality,
    val activity: ActivityEstimate,
    val motion: MotionFeatures?,
    val match: MatchResult?,
    val lastFix: GpsFix?,
    /** Distance covered while moving (any gait), metres, from the corrected track. */
    val distanceM: Double,
    /** Distance covered while jogging or running. */
    val runningDistanceM: Double,
    val movingTimeMs: Long,
    val runningTimeMs: Long,
    val stepsTotal: Int,
) {
    companion object {
        fun empty(timeMs: Long) = TrackSnapshot(
            timeMs, null, null, null, null, PositionSource.None, GpsQuality.Off, ActivityEstimate.UNKNOWN, null, null, null,
            0.0, 0.0, 0, 0, 0,
        )
    }
}
