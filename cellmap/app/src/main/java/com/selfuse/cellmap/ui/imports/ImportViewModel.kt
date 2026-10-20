package com.selfuse.cellmap.ui.imports

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.ImportSummary
import com.selfuse.cellmap.core.model.StationField
import com.selfuse.cellmap.core.model.TaskItem
import com.selfuse.cellmap.core.model.TaskType
import com.selfuse.cellmap.data.db.ImportBatchEntity
import com.selfuse.cellmap.data.db.toSummary
import com.selfuse.cellmap.data.importer.ColumnMapping
import com.selfuse.cellmap.data.importer.SheetPreview
import com.selfuse.cellmap.data.prefs.ImportSpec
import com.selfuse.cellmap.data.repo.StagedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ImportUiState(
    val staging: Boolean = false,
    val fileName: String? = null,
    val fileBytes: Long = 0,
    val sheetName: String = "",
    val previewRows: List<List<String>> = emptyList(),
    val rowNumbers: List<Int> = emptyList(),
    val headerRow: Int = 0,
    val mapping: Map<StationField, Int> = emptyMap(),
    val totalRows: Int = 0,
    val busy: Boolean = false,
    val queued: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val latest: ImportSummary? = null,
    val batches: List<ImportSummary> = emptyList(),
    val task: TaskItem? = null
) {
    val missingRequired: List<StationField>
        get() = StationField.entries.filter { it.required && !mapping.containsKey(it) }
}

private data class ImportDraft(
    val staging: Boolean = false,
    val fileName: String? = null,
    val fileBytes: Long = 0,
    val sheetName: String = "",
    val previewRows: List<List<String>> = emptyList(),
    val rowNumbers: List<Int> = emptyList(),
    val headerRow: Int = 0,
    val mapping: Map<StationField, Int> = emptyMap(),
    val totalRows: Int = 0,
    val busy: Boolean = false,
    val queued: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

class ImportViewModel(app: CellMapApp) : ViewModel() {
    private val c = app.container
    private var stagedFile: StagedFile? = null
    private var preview: SheetPreview? = null
    private val draft = MutableStateFlow(ImportDraft())

    val state: StateFlow<ImportUiState> = combine(
        draft,
        c.importRepository.latestBatch,
        c.importRepository.batches,
        c.taskCenter.observeTasks()
    ) { draft, latest, batches, tasks ->
        ImportUiState(
            staging = draft.staging,
            fileName = draft.fileName,
            fileBytes = draft.fileBytes,
            sheetName = draft.sheetName,
            previewRows = draft.previewRows,
            rowNumbers = draft.rowNumbers,
            headerRow = draft.headerRow,
            mapping = draft.mapping,
            totalRows = draft.totalRows,
            busy = draft.busy,
            queued = draft.queued,
            error = draft.error,
            message = draft.message,
            latest = (latest as ImportBatchEntity?)?.toSummary(),
            batches = batches.map { it.toSummary() },
            task = tasks.firstOrNull { it.type == TaskType.IMPORT }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportUiState())

    fun select(uri: Uri) {
        if (draft.value.busy) return
        viewModelScope.launch {
            draft.update { it.copy(busy = true, error = null, queued = false) }
            runCatching {
                val staged = c.importRepository.stageFile(uri)
                val scanned = c.importRepository.scanHeader(staged.file)
                stagedFile = staged
                preview = scanned
                val headerIndex = scanned.rowNumbers.indexOfFirst { it >= 0 }.coerceAtLeast(0)
                val headerRow = scanned.rowNumbers.getOrElse(headerIndex) { 0 }
                val columns = scanned.rows.getOrElse(headerIndex) { emptyList() }
                draft.update {
                    it.copy(
                        staging = false,
                        busy = false,
                        fileName = staged.displayName,
                        fileBytes = staged.bytes,
                        sheetName = scanned.sheetName,
                        previewRows = scanned.rows,
                        rowNumbers = scanned.rowNumbers,
                        headerRow = headerRow,
                        mapping = ColumnMapping.autoDetect(columns),
                        totalRows = c.importRepository.totalRows(scanned, headerRow)
                    )
                }
            }.onFailure { error ->
                draft.update {
                    it.copy(busy = false, error = error.message ?: "读取文件失败")
                }
            }
        }
    }

    fun chooseHeader(previewIndex: Int) {
        val scanned = preview ?: return
        val rowNumber = scanned.rowNumbers.getOrElse(previewIndex) { previewIndex }
        val columns = scanned.rows.getOrElse(previewIndex) { emptyList() }
        draft.update {
            it.copy(
                headerRow = rowNumber,
                mapping = ColumnMapping.autoDetect(columns),
                totalRows = c.importRepository.totalRows(scanned, rowNumber)
            )
        }
    }

    fun setColumn(field: StationField, column: Int?) {
        draft.update {
            val next = it.mapping.toMutableMap()
            if (column == null) next.remove(field) else next[field] = column
            it.copy(mapping = next)
        }
    }

    fun start() {
        val current = draft.value
        val staged = stagedFile ?: return
        if (current.busy || current.queued) return
        val missing = StationField.entries.filter { it.required && !current.mapping.containsKey(it) }
        if (missing.isNotEmpty()) {
            draft.update { it.copy(error = "缺少必填字段：${missing.joinToString { f -> f.displayName }}") }
            return
        }
        viewModelScope.launch {
            draft.update { it.copy(busy = true, error = null) }
            val running = runCatching { c.taskCenter.latest(TaskType.IMPORT) }.getOrNull()
            if (running?.state == WorkInfo.State.RUNNING || running?.state == WorkInfo.State.ENQUEUED) {
                draft.update { it.copy(busy = false, error = "已有导入任务在进行，请先在任务中心处理") }
                return@launch
            }
            val spec = ImportSpec(
                filePath = staged.file.absolutePath,
                fileName = staged.displayName,
                sheetName = current.sheetName,
                headerRow = current.headerRow,
                totalRows = current.totalRows,
                mapping = current.mapping.entries.associate { it.key.name to it.value }
            )
            runCatching { c.scheduler.enqueueImport(spec) }
                .onSuccess {
                    draft.update {
                        it.copy(
                            busy = false,
                            queued = true,
                            message = "已提交导入任务，可在任务中心查看进度"
                        )
                    }
                }
                .onFailure { error ->
                    draft.update {
                        it.copy(busy = false, error = error.message ?: "提交导入任务失败")
                    }
                }
        }
    }

    fun cancelTask() {
        viewModelScope.launch { c.taskCenter.cancel(TaskType.IMPORT) }
    }

    fun retryTask() {
        viewModelScope.launch { c.taskCenter.retry(TaskType.IMPORT) }
    }

    fun clearFile() {
        stagedFile?.file?.delete()
        stagedFile = null
        preview = null
        draft.update { ImportDraft() }
    }

    fun consumeMessage() {
        draft.update { it.copy(message = null) }
    }

    fun consumeError() {
        draft.update { it.copy(error = null) }
    }

    companion object {
        fun factory(app: CellMapApp) = viewModelFactory {
            initializer { ImportViewModel(app) }
        }
    }
}
