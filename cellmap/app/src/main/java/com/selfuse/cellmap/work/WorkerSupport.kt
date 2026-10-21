package com.selfuse.cellmap.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import com.selfuse.cellmap.MainActivity
import com.selfuse.cellmap.R
import com.selfuse.cellmap.core.model.TaskType
import kotlinx.coroutines.CancellationException
import java.io.IOException

object WorkerKeys {
    const val PERCENT = "percent"
    const val MESSAGE = "message"
    const val PHASE = "phase"
    const val BATCH_ID = "batch_id"
    const val ERROR = "error"
    const val SUMMARY = "summary"
}

class RetryableWorkerException(message: String, cause: Throwable? = null) : Exception(message, cause)
class FatalWorkerException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun progressData(
    percent: Int?,
    message: String,
    phase: String? = null,
    batchId: Long? = null
): Data = Data.Builder()
    .putInt(WorkerKeys.PERCENT, percent ?: -1)
    .putString(WorkerKeys.MESSAGE, message)
    .putString(WorkerKeys.PHASE, phase)
    .putLong(WorkerKeys.BATCH_ID, batchId ?: 0L)
    .build()

fun errorData(message: String): Data = Data.Builder()
    .putString(WorkerKeys.ERROR, message.take(500))
    .build()

fun Throwable.isRetryable(): Boolean = when (this) {
    is IOException, is RetryableWorkerException -> true
    is FatalWorkerException, is SecurityException -> false
    else -> false
}

object TaskNotifications {
    const val CHANNEL_ID = "cellmap_tasks"
    private const val CHANNEL_NAME = "后台任务"

    fun ensureChannel(context: Context) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            val channel = NotificationChannelCompat.Builder(
                CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
                .setName(CHANNEL_NAME)
                .setDescription(context.getString(R.string.notification_channel_tasks_desc))
                .setShowBadge(false)
                .build()
            manager.createNotificationChannel(channel)
        }.onFailure { Log.w("CellMapWorker", "Unable to create notification channel", it) }
    }

    fun foregroundInfo(
        context: Context,
        type: TaskType,
        title: String,
        text: String,
        percent: Int?
    ): ForegroundInfo {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            type.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cellmap)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, percent ?: 0, percent == null)
            .build()
        val id = 1000 + type.ordinal
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }
}


/**
 * 卓易通等容器可能禁止启动前台服务或关闭通知权限。前台通知失败时继续执行任务，
 * 避免整个导入/标注/优化直接失败。
 */
suspend fun CoroutineWorker.setForegroundQuietly(info: ForegroundInfo): Boolean = try {
    setForeground(info)
    true
} catch (cancel: CancellationException) {
    throw cancel
} catch (error: Throwable) {
    Log.w("CellMapWorker", "Foreground service unavailable", error)
    false
}

fun CoroutineWorker.updateForegroundQuietly(info: ForegroundInfo) {
    runCatching { setForegroundAsync(info) }
        .onFailure { Log.w("CellMapWorker", "Unable to update foreground notification", it) }
}
