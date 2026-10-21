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

class LabelWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CellMapApp
        val c = app.container
        try {
            val hasForeground = setForegroundQuietly(
                TaskNotifications.foregroundInfo(
                    applicationContext,
                    TaskType.LABEL,
                    "正在生成分类标注",
                    "读取基站数据并归一化承建方/共享状态",
                    null
                )
            )
            if (!hasForeground) {
                c.logger.log(
                    LogModule.LABEL,
                    LogLevel.WARN,
                    "前台通知不可用",
                    "继续在后台生成标注，离开应用可能被系统暂停"
                )
            }
            c.readiness.beginLabelRun()
            val result = c.labelRepository.run(
                isCancelled = { isStopped },
                onProgress = { percent, processed, total, message ->
                    setProgressAsync(progressData(percent, message))
                    updateForegroundQuietly(
                        TaskNotifications.foregroundInfo(
                            applicationContext,
                            TaskType.LABEL,
                            "正在生成分类标注",
                            message,
                            percent
                        )
                    )
                }
            )
            c.readiness.markLabelRunCompleted(
                com.selfuse.cellmap.data.prefs.LabelRunState(
                    total = result.stats.total,
                    classified = result.stats.classified,
                    unclassified = result.stats.unclassified,
                    unicomShared = result.stats.unicomShared,
                    telecomShared = result.stats.telecomShared,
                    unicomPrivate = result.stats.unicomPrivate,
                    telecomPrivate = result.stats.telecomPrivate,
                    completedAt = result.stats.completedAt
                )
            )
            c.prefs.setOptimizeReady(false)
            setProgressAsync(progressData(100, "标注完成"))
            if (result.stats.classified > 0) {
                c.scheduler.enqueueOptimize()
                c.logger.log(LogModule.LABEL, LogLevel.INFO, "已自动触发压缩优化任务")
            } else {
                c.logger.log(
                    LogModule.LABEL,
                    LogLevel.WARN,
                    "没有任何记录匹配四色规则，跳过自动优化"
                )
            }
            return Result.success(
                workDataOf(
                    WorkerKeys.MESSAGE to "可渲染 ${result.stats.classified}，未归类 ${result.stats.unclassified}"
                )
            )
        } catch (e: CancellationException) {
            c.logger.log(LogModule.LABEL, LogLevel.WARN, "标注任务已暂停")
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            if (e.isRetryable() && runAttemptCount < MAX_RETRIES) {
                c.logger.log(LogModule.LABEL, LogLevel.WARN, "标注可重试失败", message)
                return Result.retry()
            }
            c.logger.log(LogModule.LABEL, LogLevel.ERROR, "标注失败", message)
            return Result.failure(errorData(message))
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
