package com.selfuse.cellmap.data.repo

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.selfuse.cellmap.core.model.TaskItem
import com.selfuse.cellmap.core.model.TaskStatus
import com.selfuse.cellmap.core.model.TaskType
import com.selfuse.cellmap.data.db.LogLevel
import com.selfuse.cellmap.data.db.LogModule
import com.selfuse.cellmap.data.prefs.AppPreferences
import com.selfuse.cellmap.work.WorkScheduler
import com.selfuse.cellmap.work.WorkerKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class TaskCenterRepository(
    context: Context,
    private val prefs: AppPreferences,
    private val scheduler: WorkScheduler,
    private val importRepository: ImportRepository,
    private val logger: AppLogger
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * 由 WorkManager 的数据变更推送任务状态，替代定时轮询，避免主页持续的
     * 后台查询与无意义重组。
     */
    fun observeTasks(): Flow<List<TaskItem>> = combine(
        workManager.getWorkInfosByTagFlow(WorkScheduler.TAG_ALL),
        prefs.importSpecFlow
    ) { infos, spec ->
        infos.mapNotNull { info -> info.toTaskItem(importResumable = spec != null) }
            .groupBy { it.type }
            .map { (_, list) -> list.minByOrNull { statusPriority(it.status) } ?: list.first() }
            .sortedBy { statusPriority(it.status) }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    suspend fun latest(type: TaskType): WorkInfo? = withContext(Dispatchers.IO) {
        workManager.getWorkInfosByTagFlow(WorkScheduler.TAG_ALL).first()
            .filter { TaskType.fromTag(it.tags) == type }
            .minByOrNull { statusPriority(it.state.toStatus()) }
    }

    suspend fun pause(type: TaskType) {
        val info = latest(type) ?: return
        if (info.state == WorkInfo.State.RUNNING) {
            if (type == TaskType.IMPORT) {
                val batchId = info.progress.getLong(WorkerKeys.BATCH_ID, 0L)
                if (batchId > 0) prefs.updateImportBatchId(batchId)
            }
            scheduler.cancel(type)
            logger.log(LogModule.TASK, LogLevel.WARN, "${type.title}已暂停", "可从任务中心继续")
        }
    }

    suspend fun cancel(type: TaskType) {
        val info = latest(type)
        val batchId = info?.progress?.getLong(WorkerKeys.BATCH_ID, 0L)
            ?.takeIf { it > 0 }
            ?: prefs.importSpec()?.batchId?.takeIf { it > 0 }
        scheduler.cancel(type)
        if (type == TaskType.IMPORT) {
            delay(700)
            if (batchId != null) runCatching { importRepository.abandonBatch(batchId) }
            prefs.writeImportSpec(null)
        }
        logger.log(LogModule.TASK, LogLevel.WARN, "${type.title}已取消")
    }

    suspend fun retry(type: TaskType) {
        when (type) {
            TaskType.IMPORT -> scheduler.resumeImport()
            TaskType.LABEL -> scheduler.enqueueLabel()
            TaskType.OPTIMIZE -> scheduler.enqueueOptimize()
        }
        logger.log(LogModule.TASK, LogLevel.INFO, "${type.title}已重新入队")
    }

    private fun WorkInfo.toTaskItem(importResumable: Boolean): TaskItem? {
        val type = TaskType.fromTag(tags) ?: return null
        val status = state.toStatus()
        val percent = progress.getInt(WorkerKeys.PERCENT, -1).takeIf { it in 0..100 }
        val message = when (status) {
            TaskStatus.RUNNING -> progress.getString(WorkerKeys.MESSAGE) ?: "运行中"
            TaskStatus.ENQUEUED -> "等待执行"
            TaskStatus.BLOCKED -> "等待前置条件"
            TaskStatus.SUCCEEDED -> "已完成"
            TaskStatus.FAILED -> outputData.getString(WorkerKeys.ERROR) ?: "执行失败"
            TaskStatus.CANCELLED -> progress.getString(WorkerKeys.MESSAGE) ?: "已取消"
        }
        val resumable = when (status) {
            TaskStatus.FAILED -> true
            TaskStatus.CANCELLED -> type != TaskType.IMPORT || importResumable
            else -> false
        }
        return TaskItem(
            workId = id.toString(),
            type = type,
            status = status,
            percent = if (status == TaskStatus.SUCCEEDED) 100 else percent,
            message = message,
            attempt = runAttemptCount,
            batchId = progress.getLong(WorkerKeys.BATCH_ID, 0L).takeIf { it > 0 },
            failureReason = if (status == TaskStatus.FAILED) outputData.getString(WorkerKeys.ERROR) else null,
            runnable = resumable,
            pausable = status == TaskStatus.RUNNING
        )
    }

    private fun WorkInfo.State.toStatus(): TaskStatus = when (this) {
        WorkInfo.State.ENQUEUED -> TaskStatus.ENQUEUED
        WorkInfo.State.RUNNING -> TaskStatus.RUNNING
        WorkInfo.State.SUCCEEDED -> TaskStatus.SUCCEEDED
        WorkInfo.State.FAILED -> TaskStatus.FAILED
        WorkInfo.State.CANCELLED -> TaskStatus.CANCELLED
        WorkInfo.State.BLOCKED -> TaskStatus.BLOCKED
    }

    private fun statusPriority(status: TaskStatus): Int = when (status) {
        TaskStatus.RUNNING -> 0
        TaskStatus.ENQUEUED -> 1
        TaskStatus.BLOCKED -> 2
        TaskStatus.FAILED -> 3
        TaskStatus.CANCELLED -> 4
        TaskStatus.SUCCEEDED -> 5
    }
}
