package com.tsyz.autoledger.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tsyz.autoledger.AutoLedgerApp
import com.tsyz.autoledger.core.util.TimeUtils
import java.time.YearMonth

class MonthlyReportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AutoLedgerApp ?: return Result.success()
        val container = app.container
        val settings = container.settings.current()
        if (!settings.monthlyReportEnabled) {
            container.scheduler.cancelAll()
            return Result.success()
        }

        val targetMonth = YearMonth.now(TimeUtils.zone()).minusMonths(1)
        val currentMonthStart = TimeUtils.monthStart(YearMonth.now(TimeUtils.zone()))
        val alreadyGenerated = settings.lastReportMonth == targetMonth.toString() &&
            settings.lastReportGeneratedAt >= currentMonthStart
        if (alreadyGenerated) {
            container.scheduler.scheduleNext()
            return Result.success()
        }

        return try {
            container.reportGenerator.generate(targetMonth, notify = true)
            container.scheduler.scheduleNext()
            Result.success()
        } catch (error: Exception) {
            container.logger.error("REPORT", "月报生成失败 ${error.javaClass.simpleName}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
