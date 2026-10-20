package com.selfuse.cellmap.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.selfuse.cellmap.core.model.ImportSummary
import com.selfuse.cellmap.core.model.StationField
import com.selfuse.cellmap.data.db.AppDatabase
import com.selfuse.cellmap.data.db.BatchStatus
import com.selfuse.cellmap.data.db.ImportBatchEntity
import com.selfuse.cellmap.data.db.LogLevel
import com.selfuse.cellmap.data.db.LogModule
import com.selfuse.cellmap.data.db.StationWriter
import com.selfuse.cellmap.data.db.toSummary
import com.selfuse.cellmap.data.importer.ImportCancelledException
import com.selfuse.cellmap.data.importer.SheetPreview
import com.selfuse.cellmap.data.importer.XlsxFormatException
import com.selfuse.cellmap.data.importer.XlsxParser
import com.selfuse.cellmap.data.prefs.AppPreferences
import com.selfuse.cellmap.data.prefs.ImportSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

data class StagedFile(val file: File, val displayName: String, val bytes: Long)

data class ImportCounters(
    val processed: Int = 0,
    val inserted: Int = 0,
    val duplicates: Int = 0,
    val failed: Int = 0,
    val reasons: Map<String, Int> = emptyMap()
)

data class ImportProgress(
    val percent: Int?,
    val processed: Int,
    val inserted: Int,
    val duplicates: Int,
    val failed: Int,
    val message: String
)

data class ImportRunResult(
    val counters: ImportCounters,
    val insertedThisRun: Int,
    val totalRows: Int
)

class ImportRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val writer: StationWriter,
    private val prefs: AppPreferences,
    private val readiness: ReadinessRepository,
    private val logger: AppLogger,
    private val parser: XlsxParser = XlsxParser()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val batchDao = db.importBatchDao()

    val batches: Flow<List<ImportBatchEntity>> = batchDao.observeAll()
    val latestBatch: Flow<ImportBatchEntity?> = batchDao.observeLatest()

    suspend fun stageFile(uri: Uri): StagedFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(uri) ?: "import_${System.currentTimeMillis()}.xlsx"
        if (!displayName.lowercase(Locale.ROOT).endsWith(".xlsx")) {
            throw XlsxFormatException("请选择 .xlsx 文件（旧版 .xls 不支持）")
        }
        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        val target = File(dir, "${System.currentTimeMillis()}_$safeName")
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_FILE_BYTES) {
                        target.delete()
                        throw XlsxFormatException("文件超过 200MB，请先拆分")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw XlsxFormatException("无法读取所选文件")
        StagedFile(target, displayName, total)
    }

    suspend fun scanHeader(file: File): SheetPreview = withContext(Dispatchers.IO) {
        parser.scanHeader(file)
    }

    fun totalRows(preview: SheetPreview, headerRow: Int): Int {
        val last = preview.dimensionLastRow ?: return 0
        return (last - headerRow).coerceAtLeast(0)
    }

    suspend fun ensureBatch(spec: ImportSpec, dataVersion: Int): Long {
        if (spec.batchId > 0L) {
            val existing = batchDao.byId(spec.batchId)
            if (existing != null) return existing.id
        }
        // WorkManager 自动重试会复用原始 inputData（batchId=0），这里接续仍处于 RUNNING 的批次，
        // 避免重复创建批次或把已有插入误算成重复。
        val active = batchDao.activeBatch()
        if (active != null && active.fileName == spec.fileName) {
            prefs.updateImportBatchId(active.id)
            return active.id
        }
        val id = batchDao.insert(
            ImportBatchEntity(
                fileName = spec.fileName,
                sheetName = spec.sheetName,
                headerRow = spec.headerRow,
                dataVersion = dataVersion,
                totalRows = spec.totalRows,
                status = BatchStatus.RUNNING,
                startedAt = System.currentTimeMillis()
            )
        )
        prefs.updateImportBatchId(id)
        logger.log(LogModule.IMPORT, LogLevel.INFO, "创建导入批次 #$id", spec.fileName)
        return id
    }

    suspend fun execute(
        spec: ImportSpec,
        batchId: Long,
        mapping: Map<StationField, Int>,
        isCancelled: () -> Boolean,
        onProgress: (ImportProgress) -> Unit
    ): ImportRunResult = withContext(Dispatchers.IO) {
        val file = File(spec.filePath)
        if (!file.exists()) throw XlsxFormatException("暂存文件已不存在，请重新选择")
        val batch = batchDao.byId(batchId) ?: throw IllegalStateException("导入批次不存在")
        val initial = ImportCounters(
            processed = batch.processedRows,
            inserted = batch.successRows,
            duplicates = batch.duplicateRows,
            failed = batch.failedRows,
            reasons = runCatching {
                json.decodeFromString<Map<String, Int>>(batch.failedReasonsJson)
            }.getOrDefault(emptyMap())
        )
        var insertedThisRun = 0
        val reasons = initial.reasons.toMutableMap()

        fun persist(counters: ImportCounters, processedAbsolute: Int) {
            val updated = batch.copy(
                processedRows = processedAbsolute,
                successRows = counters.inserted,
                duplicateRows = counters.duplicates,
                failedRows = counters.failed,
                failedReasonsJson = json.encodeToString(counters.reasons),
                totalRows = if (spec.totalRows > 0) spec.totalRows else batch.totalRows
            )
            runBlocking {
                batchDao.update(updated)
            }
            val percent = spec.totalRows.takeIf { it > 0 }?.let { total ->
                (processedAbsolute * 100 / total).coerceIn(0, 99)
            }
            onProgress(
                ImportProgress(
                    percent = percent,
                    processed = processedAbsolute,
                    inserted = counters.inserted,
                    duplicates = counters.duplicates,
                    failed = counters.failed,
                    message = "已处理 ${processedAbsolute} 行 · 成功 ${counters.inserted} · 失败 ${counters.failed}"
                )
            )
        }

        var counters = initial
        try {
            parser.parse(
                file = file,
                headerRow = spec.headerRow,
                mapping = mapping,
                skipRows = initial.processed,
                batchSize = 800,
                isCancelled = isCancelled,
                onBatch = { drafts, failures, processedAbsolute ->
                    val write = runBlocking { writer.insertBatch(batchId, drafts, System.currentTimeMillis()) }
                    insertedThisRun += write.inserted
                    failures.forEach { reason ->
                        reasons[reason] = (reasons[reason] ?: 0) + 1
                    }
                    counters = ImportCounters(
                        processed = processedAbsolute,
                        inserted = counters.inserted + write.inserted,
                        duplicates = counters.duplicates + write.duplicates,
                        failed = counters.failed + failures.size,
                        reasons = reasons.toMap()
                    )
                    persist(counters, processedAbsolute)
                }
            )
        } catch (e: ImportCancelledException) {
            throw e
        }
        persist(counters, counters.processed)
        logger.log(
            LogModule.IMPORT,
            LogLevel.INFO,
            "批次 #$batchId 读取结束",
            "processed=${counters.processed} inserted=${counters.inserted} duplicate=${counters.duplicates} failed=${counters.failed}"
        )
        ImportRunResult(counters, insertedThisRun, spec.totalRows)
    }

    suspend fun finishSuccess(batchId: Long, insertedThisRun: Int): ImportSummary? {
        val now = System.currentTimeMillis()
        val batch = batchDao.byId(batchId) ?: return null
        val hasData = writer.stationCount() > 0
        if (insertedThisRun > 0) {
            writer.clearOptimized()
            readiness.onDataMutated(hasData)
        } else {
            readiness.setDataReadyWithoutInvalidating(hasData)
        }
        readiness.setImportedAt(now)
        val status = if (batch.failedRows > 0 || batch.duplicateRows > 0) BatchStatus.PARTIAL else BatchStatus.COMPLETED
        val finished = batch.copy(
            status = status,
            dataVersion = readiness.dataVersion(),
            importedAt = now,
            note = if (insertedThisRun == 0) "本次没有新增记录（全部重复或为空）" else null
        )
        batchDao.update(finished)
        val summary = finished.toSummary()
        logger.log(
            LogModule.IMPORT,
            LogLevel.INFO,
            "导入完成：${summary.fileName}",
            "total=${summary.totalRows} success=${summary.successRows} duplicate=${summary.duplicateRows} failed=${summary.failedRows}"
        )
        return summary
    }

    suspend fun failBatch(batchId: Long, reason: String) {
        withContext(Dispatchers.IO) {
            runCatching { writer.deleteBatch(batchId) }
            batchDao.finish(batchId, BatchStatus.FAILED, reason.take(500), System.currentTimeMillis())
        }
        logger.log(LogModule.IMPORT, LogLevel.ERROR, "导入失败，已回滚本批次数据", reason)
    }

    suspend fun abandonBatch(batchId: Long) {
        withContext(Dispatchers.IO) {
            runCatching { writer.deleteBatch(batchId) }
            batchDao.finish(batchId, BatchStatus.CANCELLED, "用户取消", System.currentTimeMillis())
        }
        logger.log(LogModule.IMPORT, LogLevel.WARN, "导入任务已取消，批次 #$batchId 已清理")
    }

    suspend fun markBatchCancelledWithoutDelete(batchId: Long) {
        batchDao.finish(batchId, BatchStatus.CANCELLED, "暂停等待继续", System.currentTimeMillis())
    }

    suspend fun batch(batchId: Long): ImportBatchEntity? = batchDao.byId(batchId)

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else null
                }
        }.getOrNull()
    }

    private companion object {
        const val MAX_FILE_BYTES = 200L * 1024 * 1024
    }
}
