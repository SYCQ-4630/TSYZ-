package com.tsyz.autoledger.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tsyz.autoledger.AutoLedgerApp
import com.tsyz.autoledger.health.HealthChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AutoLedgerApp ?: return Result.success()
        val container = app.container
        val settings = container.settings.current()
        return try {
            container.logger.cleanup(settings.logRetentionDays)
            container.drafts.cleanupExpired()
            container.db.pendingDao().deleteResolvedOlderThan(
                System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            )
            container.db.rawCaptureDao().deleteOlderThan(
                System.currentTimeMillis() - 180L * 24 * 60 * 60 * 1000
            )
            container.health.checkStaleChannels()
            container.health.beatNow(HealthChannel.APP, "maintenance")
            maybeVacuum()
            Result.success()
        } catch (error: Exception) {
            container.logger.error("MAINT", "定期维护失败 ${error.javaClass.simpleName}")
            Result.success()
        }
    }

    private suspend fun maybeVacuum() = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("auto_ledger_maintenance", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_vacuum_at", 0L)
        val now = System.currentTimeMillis()
        if (now - last < 7L * 24 * 60 * 60 * 1000) return@withContext
        val app = applicationContext as? AutoLedgerApp ?: return@withContext
        runCatching { app.container.db.openHelper.writableDatabase.execSQL("VACUUM") }
            .onSuccess { prefs.edit().putLong("last_vacuum_at", now).apply() }
            .onFailure { app.container.logger.warn("MAINT", "VACUUM 失败 ${it.javaClass.simpleName}") }
    }
}
