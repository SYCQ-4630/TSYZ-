package com.tsyz.autoledger.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tsyz.autoledger.core.util.TimeUtils
import com.tsyz.autoledger.data.prefs.AppSettings
import java.util.concurrent.TimeUnit

/**
 * 精确闹钟负责每月 1 日准点，WorkManager 在当天稍晚兜底，
 * 开机与时间变更后重建。Android 12+ 若未授予精确闹钟权限则降级为 setAndAllowWhileIdle。
 */
class MonthlyReportScheduler(private val context: Context) {

    fun apply(settings: AppSettings) {
        if (settings.monthlyReportEnabled) {
            scheduleNext()
        } else {
            cancelAll()
        }
    }

    fun scheduleNext() {
        scheduleAlarm()
        scheduleFallback()
    }

    fun scheduleAlarm() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = TimeUtils.nextMonthlyRun()
        val pendingIntent = alarmIntent()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    fun scheduleFallback() {
        val delay = (TimeUtils.nextMonthlyFallback() - System.currentTimeMillis()).coerceAtLeast(60_000L)
        val request = OneTimeWorkRequestBuilder<MonthlyReportWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FALLBACK_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun enqueueImmediateReport() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MonthlyReportWorker>().build()
        )
    }

    fun cancelAll() {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent())
        WorkManager.getInstance(context).cancelUniqueWork(FALLBACK_WORK)
    }

    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }

    private fun alarmIntent(): PendingIntent {
        val intent = Intent(context, MonthlyAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val ALARM_REQUEST_CODE = 3001
        const val FALLBACK_WORK = "monthly_report_fallback"
        const val IMMEDIATE_WORK = "monthly_report_now"
    }
}
