package com.tsyz.autoledger.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tsyz.autoledger.AutoLedgerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MonthlyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? AutoLedgerApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = app.container.settings.current()
                if (settings.monthlyReportEnabled) {
                    app.container.scheduler.enqueueImmediateReport()
                    app.container.scheduler.scheduleNext()
                    app.container.health.beatNow(
                        com.tsyz.autoledger.health.HealthChannel.WORKER,
                        "alarm fired"
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
