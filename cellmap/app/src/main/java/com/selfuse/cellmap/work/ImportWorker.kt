package com.selfuse.cellmap.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.StationField
import com.selfuse.cellmap.core.model.TaskType
import com.selfuse.cellmap.data.db.LogLevel
import com.selfuse.cellmap.data.db.LogModule
import com.selfuse.cellmap.data.importer.ImportCancelledException
import com.selfuse.cellmap.data.importer.XlsxFormatException
import com.selfuse.cellmap.data.prefs.ImportSpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class ImportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CellMapApp
        val c = app.container
        val json = Json { ignoreUnknownKeys = true }
        val spec = inputData.getString("spec")
            ?.let { runCatching { json.decodeFromString<ImportSpec>(it) }.getOrNull() }
            ?: return Result.failure(errorData("导入参数缺失"))

        var batchId = 0L
        var lastPercent = -1
        try {
            setForeground(
                TaskNotifications.foregroundInfo(
                    applicationContext,
                    TaskType.IMPORT,
                    "正在导入 XLSX",
                    spec.fileName,
                    null
                )
            )
            c.logger.log(LogModule.IMPORT, LogLevel.INFO, "开始导入 ${spec.fileName}", spec.filePath)
            val version = c.readiness.dataVersion()
            batchId = c.importRepository.ensureBatch(spec, version)
            val mapping = spec.mapping.mapNotNull { (name, index) ->
                StationField.fromName(name)?.let { it to index }
            }.toMap()
            val missing = StationField.entries.filter { it.required && !mapping.containsKey(it) }
            if (missing.isNotEmpty()) {
                throw XlsxFormatException("缺少必填字段映射：${missing.joinToString { it.displayName }}")
            }
            setProgressAsync(progressData(0, "准备读取表格", batchId = batchId))
            val result = c.importRepository.execute(
                spec = spec,
                batchId = batchId,
                mapping = mapping,
                isCancelled = { isStopped },
                onProgress = { progress ->
                    setProgressAsync(
                        progressData(progress.percent, progress.message, batchId = batchId)
                    )
                    val percent = progress.percent
                    if (percent != null && (lastPercent < 0 || percent - lastPercent >= 5)) {
                        lastPercent = percent
                        setForegroundAsync(
                            TaskNotifications.foregroundInfo(
                                applicationContext,
                                TaskType.IMPORT,
                                "正在导入 XLSX",
                                "${progress.message} · ${spec.fileName}",
                                percent
                            )
                        )
                    }
                }
            )
            val summary = c.importRepository.finishSuccess(batchId, result.insertedThisRun)
            c.prefs.writeImportSpec(null)
            setProgressAsync(progressData(100, "导入完成", batchId = batchId))
            c.logger.log(
                LogModule.IMPORT,
                LogLevel.INFO,
                "导入任务成功",
                "batch=$batchId inserted=${result.insertedThisRun} total=${result.counters.inserted}"
            )
            return Result.success(
                workDataOf(
                    WorkerKeys.SUMMARY to buildString {
                        append("成功 ${result.counters.inserted}")
                        append(" · 重复 ${result.counters.duplicates}")
                        append(" · 失败 ${result.counters.failed}")
                        summary?.fileName?.let { append(" · $it") }
                    }
                )
            )
        } catch (e: ImportCancelledException) {
            c.logger.log(LogModule.IMPORT, LogLevel.WARN, "导入暂停", "批次 #$batchId 可从任务中心继续")
            return Result.failure(errorData("已暂停，可从任务中心继续"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: XlsxFormatException) {
            if (batchId > 0) c.importRepository.failBatch(batchId, e.message ?: "表格格式错误")
            c.logger.log(LogModule.IMPORT, LogLevel.ERROR, "导入格式错误", e.message)
            return Result.failure(errorData(e.message ?: "表格格式错误"))
        } catch (e: SecurityException) {
            if (batchId > 0) c.importRepository.failBatch(batchId, "没有读取文件的权限")
            return Result.failure(errorData("没有读取文件的权限"))
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            if (e.isRetryable() && runAttemptCount < MAX_RETRIES) {
                c.logger.log(
                    LogModule.IMPORT,
                    LogLevel.WARN,
                    "导入可重试失败（第 ${runAttemptCount + 1} 次）",
                    message
                )
                return Result.retry()
            }
            if (batchId > 0) c.importRepository.failBatch(batchId, message)
            c.logger.log(LogModule.IMPORT, LogLevel.ERROR, "导入失败且重试耗尽", message)
            return Result.failure(errorData(message))
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
