package com.selfuse.cellmap

import android.app.Application
import com.selfuse.cellmap.di.AppContainer
import com.selfuse.cellmap.diagnostics.CrashDiagnostics
import com.selfuse.cellmap.work.TaskNotifications

class CellMapApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
        CrashDiagnostics.log(this, "creating notification channel")
        runCatching { TaskNotifications.ensureChannel(this) }
            .onFailure { CrashDiagnostics.log(this, "notification channel failed: $it") }
        CrashDiagnostics.log(this, "creating AppContainer")
        container = AppContainer(this)
        container.start()
        CrashDiagnostics.log(this, "Application ready")
    }
}
