package com.selfuse.cellmap.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.TaskType
import com.selfuse.cellmap.data.db.LogLevel
import com.selfuse.cellmap.data.db.LogModule
import kotlinx.coroutines.CancellationException

class OptimizeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CellMapApp
        val c = app.container
        try {
            setForeground(
                TaskNotifications.foregroundInfo(
                    applicationContext,
                    TaskType.OPTIMIZE,
                    "正在压缩优化标注数据",
                    "裁剪属性、6 位坐标、格网聚合与分块",
                    null
                )
            )
            val version = c.readiness.dataVersion()
            val summary = c.optimizationRepository.run(
                dataVersion = version,
                isCancelled = { isStopped },
                onProgress = { progress ->
                    setProgressAsync(progressData(progress.percent, progress.phase))
                    setForegroundAsync(
                        TaskNotifications.foregroundInfo(
                            applicationContext,
                            TaskType.OPTIMIZE,
                            "正在压缩优化标注数据",
                            progress.phase,
                            progress.percent
                        )
                    )
                }
            )
            setProgressAsync(progressData(100, "优化完成"))
            return Result.success(
                workDataOf(
                    WorkerKeys.MESSAGE to
                        "点位 ${summary.markerCount} · 分块 ${summary.chunkCount} · 原始 ${summary.rawBytes}B → 优化 ${summary.optimizedBytes}B"
                )
            )
        } catch (e: CancellationException) {
            c.logger.log(LogModule.OPTIMIZE, LogLevel.WARN, "优化任务已暂停，原始数据未改动")
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            if (e.isRetryable() && runAttemptCount < MAX_RETRIES) {
                c.logger.log(LogModule.OPTIMIZE, LogLevel.WARN, "优化可重试失败", message)
                return Result.retry()
            }
            c.logger.log(LogModule.OPTIMIZE, LogLevel.ERROR, "优化失败，地图将回退原始数据", message)
            return Result.failure(errorData(message))
        }
    }

    private companion object {
        const val MAX_RETRIES = 2
    }
}
