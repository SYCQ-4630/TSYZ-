package com.selfuse.cellmap

import android.app.Application
import com.selfuse.cellmap.di.AppContainer
import com.selfuse.cellmap.work.TaskNotifications
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.WellKnownTileServer

class CellMapApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // MapLibre 13 的 multiBackend 默认选择 Vulkan，部分设备/驱动会在
        // MapRenderer 初始化时抛出 vk::createInstanceUnique 错误，强制使用 OpenGL ES。
        MapLibre.getInstance(
            this,
            null,
            WellKnownTileServer.MapLibre,
            RenderingEngine.Type.OPENGL
        )
        TaskNotifications.ensureChannel(this)
        container = AppContainer(this)
        container.start()
    }
}
