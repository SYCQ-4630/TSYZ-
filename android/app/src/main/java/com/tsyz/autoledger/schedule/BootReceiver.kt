package com.tsyz.autoledger.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tsyz.autoledger.AutoLedgerApp
import com.tsyz.autoledger.capture.ForegroundGuardService
import com.tsyz.autoledger.health.HealthChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? AutoLedgerApp ?: return
        val action = intent.action
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = app.container.settings.current()
                app.container.scheduler.apply(settings)
                app.container.health.beatNow(HealthChannel.APP, "boot/$action")
                if (settings.foregroundServiceEnabled &&
                    (action == Intent.ACTION_BOOT_COMPLETED ||
                        action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                        action == Intent.ACTION_LOCKED_BOOT_COMPLETED)
                ) {
                    ForegroundGuardService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
