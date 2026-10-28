package dev.nosferatu.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import dev.nosferatu.engine.model.AccelSample
import dev.nosferatu.engine.model.GyroSample

/**
 * Accelerometer and gyroscope at ~50 Hz. Sensor timestamps are nanoseconds since boot, the same clock as
 * `Location.elapsedRealtimeNanos`, so samples and GPS fixes line up without conversion.
 *
 * Samples are batched by the sensor hub for up to [batchLatencyUs]: the CPU wakes twice a second instead of fifty
 * times, which matters over an hour's run, and the tracker orders everything by timestamp anyway.
 */
class ImuSource(context: Context) {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var listener: SensorEventListener? = null

    val hasAccelerometer: Boolean get() = accelerometer != null
    val hasGyroscope: Boolean get() = gyroscope != null

    fun start(
        handler: Handler,
        onAccel: (AccelSample) -> Unit,
        onGyro: (GyroSample) -> Unit,
        periodUs: Int = 20_000,
        batchLatencyUs: Int = 500_000,
    ) {
        stop()
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val t = e.timestamp / 1_000_000
                val v = e.values
                when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> onAccel(AccelSample(t, v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
                    Sensor.TYPE_GYROSCOPE -> onGyro(GyroSample(t, v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        accelerometer?.let { manager?.registerListener(l, it, periodUs, batchLatencyUs, handler) }
        gyroscope?.let { manager?.registerListener(l, it, periodUs, batchLatencyUs, handler) }
        listener = l
    }

    fun stop() {
        listener?.let { manager?.unregisterListener(it) }
        listener = null
    }
}
