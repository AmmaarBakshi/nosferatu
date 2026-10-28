package dev.nosferatu.app.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.nosferatu.app.MainActivity
import dev.nosferatu.app.R
import dev.nosferatu.app.container
import dev.nosferatu.app.ui.format.NfFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a run alive with the screen off: a foreground service of type `location` (required on Android 14+) with a
 * partial wake lock so sensor batches and the one-second tick keep arriving. The notification shows distance and
 * time and is the way back into the app.
 */
class TrackingService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var updates: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Starting…"), type)
        } catch (e: SecurityException) {
            // Location permission was revoked: Android refuses a location foreground service. Stop cleanly.
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nosferatu:tracking")
                .apply { acquire(4 * 60 * 60 * 1000L) } // an upper bound; released when the run ends
        }
        if (updates == null) {
            updates = lifecycleScope.launch {
                container.tracking.live
                    .map { s -> s?.let(::describe) }
                    .distinctUntilChanged()
                    .collect { text ->
                        // No run any more (it was stopped, or the process restarted without one): nothing to keep alive.
                        if (text == null) stopSelf()
                        else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun describe(s: LiveState): String = when (s.status) {
        SessionStatus.Starting -> "Starting…"
        SessionStatus.Paused -> "Paused · ${NfFormat.km(s.snapshot?.distanceM ?: 0.0)} km"
        else -> {
            val snap = s.snapshot
            "${NfFormat.km(snap?.distanceM ?: 0.0)} km · ${NfFormat.duration(s.elapsedMs)} · " +
                (snap?.activity?.activity?.name?.lowercase() ?: "waiting for GPS")
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Nosferatu is tracking")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Run tracking", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL = "tracking"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
