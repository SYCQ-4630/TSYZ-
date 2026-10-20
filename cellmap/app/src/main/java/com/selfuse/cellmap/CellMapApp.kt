package com.selfuse.cellmap

import android.app.Application
import com.selfuse.cellmap.di.AppContainer
import com.selfuse.cellmap.work.TaskNotifications
import org.maplibre.android.MapLibre

class CellMapApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        TaskNotifications.ensureChannel(this)
        container = AppContainer(this)
        container.start()
    }
}
