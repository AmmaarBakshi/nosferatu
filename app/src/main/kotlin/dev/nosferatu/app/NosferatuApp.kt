package dev.nosferatu.app

import android.app.Application
import android.content.Context
import dev.nosferatu.app.data.MapDataRepository
import dev.nosferatu.app.map.MapStyleProvider
import dev.nosferatu.app.sensors.GpsSource

/** Hand-wired dependencies; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val mapData = MapDataRepository(context)
    val mapStyles = MapStyleProvider(context)
    val gps = GpsSource(context)
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
