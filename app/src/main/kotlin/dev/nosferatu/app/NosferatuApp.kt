package dev.nosferatu.app

import android.app.Application
import android.content.Context
import dev.nosferatu.app.data.MapDataRepository
import dev.nosferatu.app.data.RoadGraphRepository
import dev.nosferatu.app.data.RunStore
import dev.nosferatu.app.map.MapStyleProvider
import dev.nosferatu.app.sensors.GpsSource
import dev.nosferatu.app.sensors.ImuSource
import dev.nosferatu.app.tracking.TrackingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-wired dependencies; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val mapData = MapDataRepository(context)
    val mapStyles = MapStyleProvider(context)
    val gps = GpsSource(context)
    val imu = ImuSource(context)
    val runs = RunStore(context)
    val graph = RoadGraphRepository(context, mapData)
    val tracking = TrackingController(context, gps, imu, runs, graph, scope)
}

class NosferatuApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as NosferatuApp).container
