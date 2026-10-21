package com.selfuse.cellmap.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.ImportSummary
import com.selfuse.cellmap.core.model.KeyUi
import com.selfuse.cellmap.core.model.LabelStats
import com.selfuse.cellmap.core.model.OptimizationSummary
import com.selfuse.cellmap.core.model.ReadinessState
import com.selfuse.cellmap.core.model.TaskItem
import com.selfuse.cellmap.core.model.TaskStatus
import com.selfuse.cellmap.core.model.TaskType
import com.selfuse.cellmap.data.db.BatchStatus
import com.selfuse.cellmap.data.db.ImportBatchEntity
import com.selfuse.cellmap.data.db.OptimizationStatus
import com.selfuse.cellmap.data.db.toSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class HomeUiState(
    val readiness: ReadinessState = ReadinessState(),
    val keys: List<KeyUi> = emptyList(),
    val stationCount: Int = 0,
    val latestBatch: ImportSummary? = null,
    val labelStats: LabelStats = LabelStats(),
    val optimization: OptimizationSummary? = null,
    val dataVersion: Int = 0
) {
    val verifiedKeys: Int get() = keys.count { it.verified }
    val keySummary: String
        get() = if (keys.isEmpty()) "未配置" else "${keys.size} 个 Key · ${verifiedKeys} 个已验证"
    val dataSummary: String
        get() = if (stationCount == 0) "尚未导入" else "${stationCount} 条基站记录"
    val labelSummary: String
        get() = when {
            readiness.labelReady -> "已启用 · ${labelStats.classified} 条可渲染"
            labelStats.completedAt > 0L && labelStats.unclassified > 0 -> "待确认 · ${labelStats.unclassified} 条未归类"
            else -> "未完成"
        }
    val optimizeSummary: String
        get() = when {
            readiness.optimizeReady && optimization != null ->
                "已优化 ${optimization.markerCount} 点 · ${optimization.chunkCount} 块"
            optimization == null -> "未优化"
            else -> "未优化"
        }
}

private data class HomeCore(
    val readiness: ReadinessState,
    val keys: List<KeyUi>,
    val labelStats: LabelStats,
    val latestBatch: ImportBatchEntity?,
    val dataVersion: Int
)

class HomeViewModel(app: CellMapApp) : ViewModel() {
    private val c = app.container

    private val core = combine(
        c.readiness.state,
        c.keyRepository.keys,
        c.prefs.labelStatsFlow,
        c.importRepository.latestBatch
    ) { readiness, keys, labels, batch ->
        HomeCore(readiness, keys, labels, batch, 0)
    }

    /**
     * 任务列表单独暴露，避免进度刷新时驱动整个首页重组。
     */
    val tasks: StateFlow<List<TaskItem>> = c.taskCenter.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val importStatus: StateFlow<TaskStatus?> = tasks
        .map { list -> list.firstOrNull { it.type == TaskType.IMPORT }?.status }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<HomeUiState> = combine(
        core,
        c.database.stationDao().observeCount(),
        c.optimizationRepository.runs
    ) { core, count, runs ->
        val success = runs.firstOrNull { it.status == OptimizationStatus.SUCCESS }
        HomeUiState(
            readiness = core.readiness,
            keys = core.keys,
            stationCount = count,
            latestBatch = core.latestBatch?.toSummary(),
            labelStats = core.labelStats,
            optimization = success?.toSummary()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun pause(type: TaskType) = viewModelScope.launch { c.taskCenter.pause(type) }
    fun cancel(type: TaskType) = viewModelScope.launch { c.taskCenter.cancel(type) }
    fun retry(type: TaskType) = viewModelScope.launch { c.taskCenter.retry(type) }

    fun latestBatchStatus(): String? = null

    companion object {
        fun factory(app: CellMapApp) = viewModelFactory {
            initializer { HomeViewModel(app) }
        }
    }
}
