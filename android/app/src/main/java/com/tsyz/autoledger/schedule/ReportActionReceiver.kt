package com.tsyz.autoledger.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tsyz.autoledger.AutoLedgerApp

/** 月报通知上的“重新生成”动作，只触发本地 WorkManager，不涉及网络。 */
class ReportActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH_REPORT) return
        val app = context.applicationContext as? AutoLedgerApp ?: return
        val pending = goAsync()
        try {
            app.container.scheduler.enqueueImmediateReport()
        } finally {
            pending.finish()
        }
    }

    companion object {
        const val ACTION_REFRESH_REPORT = "com.tsyz.autoledger.action.REFRESH_REPORT"
    }
}
