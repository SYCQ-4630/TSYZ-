package com.tsyz.autoledger.report

import android.content.Context
import com.tsyz.autoledger.core.log.SafeLogger
import com.tsyz.autoledger.core.util.TimeUtils
import com.tsyz.autoledger.data.db.AppDatabase
import com.tsyz.autoledger.data.prefs.SettingsStore
import com.tsyz.autoledger.decision.ReconciliationManager
import com.tsyz.autoledger.health.HealthChannel
import com.tsyz.autoledger.health.HealthMonitor
import com.tsyz.autoledger.notification.Notifications
import java.time.YearMonth

class MonthlyReportGenerator(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val health: HealthMonitor,
    private val reconciliation: ReconciliationManager,
    private val logger: SafeLogger
) {
    /**
     * 生成指定月份月报：先自动复核待核对队列，再聚合、落库并推送通知。
     */
    suspend fun generate(month: YearMonth, notify: Boolean): ReportSummary {
        reconciliation.reconcilePending()
        val currentSettings = settings.current()
        val allHealth = health.statuses().filter { status ->
            when (status.channel) {
                HealthChannel.NOTIFICATION.name -> currentSettings.notificationCaptureEnabled
                HealthChannel.OCR.name -> currentSettings.ocrEnabled
                HealthChannel.APP.name -> false
                else -> true
            }
        }
        health.checkStaleChannels()

        val from = TimeUtils.monthStart(month.minusMonths(13))
        val to = TimeUtils.monthEndExclusive(month.plusMonths(1))
        val transactions = db.transactionDao().getInRange(from, to)
        val pendingCount = db.pendingDao().getUnresolved().size
        val captureCount = db.rawCaptureDao().countInRange(
            TimeUtils.monthStart(month),
            TimeUtils.monthEndExclusive(month)
        )
        val summary = ReportMath.build(month, transactions, pendingCount, captureCount, allHealth)

        val now = System.currentTimeMillis()
        settings.saveReport(ReportCodec.encode(summary), month.toString(), now)
        health.beatNow(HealthChannel.WORKER, "monthly report")
        logger.info(
            "REPORT",
            "月报生成 month=${summary.month} pending=${summary.pendingCount} " +
                "captures=${summary.captureCount} autoPosted=${summary.autoPostedCount}"
        )
        if (notify) Notifications.postMonthlyReport(context, summary)
        return summary
    }

    suspend fun liveSummary(month: YearMonth): ReportSummary {
        val currentSettings = settings.current()
        val allHealth = health.statuses().filter { status ->
            when (status.channel) {
                HealthChannel.NOTIFICATION.name -> currentSettings.notificationCaptureEnabled
                HealthChannel.OCR.name -> currentSettings.ocrEnabled
                HealthChannel.APP.name -> false
                else -> true
            }
        }
        val from = TimeUtils.monthStart(month.minusMonths(13))
        val to = TimeUtils.monthEndExclusive(month.plusMonths(1))
        val transactions = db.transactionDao().getInRange(from, to)
        val pendingCount = db.pendingDao().getUnresolved().size
        val captureCount = db.rawCaptureDao().countInRange(
            TimeUtils.monthStart(month),
            TimeUtils.monthEndExclusive(month)
        )
        return ReportMath.build(month, transactions, pendingCount, captureCount, allHealth)
    }
}
