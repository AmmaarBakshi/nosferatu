package dev.nosferatu.app.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.nosferatu.engine.model.GpsFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Raw GPS from Android's [LocationManager]. Deliberately not the fused provider: Nosferatu does its own fusion and
 * wants unfiltered satellite fixes with their real accuracy, and the app must work without Google Play services.
 */
class GpsSource(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(): Boolean = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    /** Fixes at [intervalMs]; the flow ends with an error if permission is missing. */
    @SuppressLint("MissingPermission")
    fun fixes(intervalMs: Long = 1000): Flow<GpsFix> = callbackFlow {
        val lm = manager ?: run { close(IllegalStateException("no location service")); return@callbackFlow }
        if (!hasPermission()) {
            close(SecurityException("location permission not granted"))
            return@callbackFlow
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toFix())
            }

            override fun onProviderDisabled(provider: String) = Unit
            override fun onProviderEnabled(provider: String) = Unit
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
        awaitClose { lm.removeUpdates(listener) }
    }

    /**
     * Registers for fixes on [looper] (the tracking thread). Returns a handle that unregisters; null when permission
     * is missing, so the caller can report it instead of crashing.
     */
    @SuppressLint("MissingPermission")
    fun register(looper: Looper, intervalMs: Long, onFix: (GpsFix) -> Unit, onEnabledChanged: (Boolean) -> Unit): AutoCloseable? {
        val lm = manager ?: return null
        if (!hasPermission()) return null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location.toFix())
            override fun onProviderDisabled(provider: String) = onEnabledChanged(false)
            override fun onProviderEnabled(provider: String) = onEnabledChanged(true)
        }
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, looper)
            AutoCloseable { lm.removeUpdates(listener) }
        } catch (e: SecurityException) {
            null
        }
    }

    companion object {
        fun Location.toFix(): GpsFix = GpsFix(
            timeMs = elapsedRealtimeNanos / 1_000_000,
            lat = latitude,
            lon = longitude,
            accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
            speedMps = if (hasSpeed()) speed.toDouble() else null,
            speedAccuracyMps = if (Build.VERSION.SDK_INT >= 26 && hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
            bearingDeg = if (hasBearing()) bearing.toDouble() else null,
            altitudeM = if (hasAltitude()) altitude else null,
        )
    }
}
