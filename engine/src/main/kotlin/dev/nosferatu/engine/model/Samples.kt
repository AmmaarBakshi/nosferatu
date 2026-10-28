package dev.nosferatu.engine.model

import dev.nosferatu.engine.geo.LatLon

/**
 * One GPS fix as the platform reports it. Times are milliseconds on a monotonic clock shared with the inertial
 * samples (Android's elapsedRealtime), never wall-clock time, so fixes and sensor samples can be ordered.
 *
 * @param accuracyM the platform's horizontal accuracy (68 % radius) in metres, or null when unknown.
 * @param speedMps Doppler speed, which is far more reliable than speed derived from positions.
 * @param bearingDeg course over ground, meaningful only while moving.
 */
data class GpsFix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val speedMps: Double? = null,
    val speedAccuracyMps: Double? = null,
    val bearingDeg: Double? = null,
    val altitudeM: Double? = null,
) {
    val position: LatLon get() = LatLon(lat, lon)
}

/** Accelerometer sample in the phone's frame, m/s², gravity included (Android TYPE_ACCELEROMETER). */
data class AccelSample(val timeMs: Long, val x: Double, val y: Double, val z: Double)

/** Gyroscope sample in the phone's frame, rad/s (Android TYPE_GYROSCOPE, uncalibrated or calibrated). */
data class GyroSample(val timeMs: Long, val x: Double, val y: Double, val z: Double)
