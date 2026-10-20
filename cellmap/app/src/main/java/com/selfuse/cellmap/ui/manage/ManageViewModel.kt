package com.selfuse.cellmap.ui.manage

import android.content.Context
import android.os.PowerManager
import android.os.StatFs
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.KeyHealth
import com.selfuse.cellmap.core.model.KeyUi
import com.selfuse.cellmap.core.model.LabelStats
import com.selfuse.cellmap.core.model.OptimizationSummary
import com.selfuse.cellmap.core.model.ReadinessState
import com.selfuse.cellmap.core.model.RiskInput
import com.selfuse.cellmap.core.model.RiskItem
import com.selfuse.cellmap.core.model.TaskItem
import com.selfuse.cellmap.core.model.TaskStatus
import com.selfuse.cellmap.core.model.TaskType
import com.selfuse.cellmap.data.db.AppLogEntity
import com.selfuse.cellmap.data.db.ImportBatchEntity
import com.selfuse.cellmap.data.db.OptimizationRunEntity
import com.selfuse.cellmap.data.db.OptimizationStatus
import com.selfuse.cellmap.data.risk.RiskDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class SystemSnapshot(
    val freeStorageBytes: Long = 0L,
    val batteryWhitelisted: Boolean = true,
    val rtreeCount: Long = 0L,
    val stationCountForRtree: Long = 0L,
    val optimizedMarkerCount: Int = 0,
    val optimizedRtreeCount: Long = 0L
)

@Immutable
data class ManageUiState(
    val readiness: ReadinessState = ReadinessState(),
    val keys: List<KeyUi> = emptyList(),
    val keyHealth: KeyHealth = KeyHealth(total = 0, verified = 0, maxDailyUsage = 0),
    val stationCount: Int = 0,
    val labelStats: LabelStats = LabelStats(),
    val optimization: OptimizationSummary? = null,
    val optimizationRuns: List<OptimizationRunEntity> = emptyList(),
    val batches: List<ImportBatchEntity> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val risks: List<RiskItem> = emptyList(),
    val logs: List<AppLogEntity> = emptyList(),
    val dataVersion: Int = 0,
    val system: SystemSnapshot = SystemSnapshot(),
    val busy: Boolean = false,
    val message: String? = null
)

private data class ManageCore(
    val readiness: ReadinessState,
    val keys: List<KeyUi>,
    val health: KeyHealth,
    val labels: LabelStats
)

private data class ManageData(
    val stationCount: Int,
    val batches: List<ImportBatchEntity>,
    val tasks: List<TaskItem>,
    val runs: List<OptimizationRunEntity>,
    val logs: List<AppLogEntity>,
    val version: Int,
    val system: SystemSnapshot,
    val extra: ManageExtra
)

private data class ManageBase(
    val stationCount: Int,
    val batches: List<ImportBatchEntity>,
    val tasks: List<TaskItem>,
    val runs: List<OptimizationRunEntity>
)

private data class ManageSide(
    val logs: List<AppLogEntity>,
    val version: Int,
    val system: SystemSnapshot,
    val extra: ManageExtra
)

private data class ManageExtra(val busy: Boolean = false, val message: String? = null)

class ManageViewModel(app: CellMapApp) : ViewModel() {
    private val c = app.container
    private val context = app.applicationContext
    private val extra = MutableStateFlow(ManageExtra())
    private val system = MutableStateFlow(SystemSnapshot())

    private val coreFlow = combine(
        c.readiness.state,
        c.keyRepository.keys,
        c.keyRepository.keyHealth,
        c.prefs.labelStatsFlow
    ) { readiness, keys, health, labels ->
        ManageCore(readiness, keys, health, labels)
    }

    private val dataBaseFlow = combine(
        c.database.stationDao().observeCount(),
        c.importRepository.batches,
        c.taskCenter.observeTasks(),
        c.optimizationRepository.runs
    ) { count, batches, tasks, runs ->
        ManageBase(count, batches, tasks, runs)
    }

    private val dataSideFlow = combine(
        c.database.logDao().observeRecent(null, 200),
        c.prefs.dataVersionFlow,
        system,
        extra
    ) { logs, version, snapshot, ex ->
        ManageSide(logs, version, snapshot, ex)
    }

    private val dataFlow = combine(dataBaseFlow, dataSideFlow) { base, side ->
        ManageData(
            stationCount = base.stationCount,
            batches = base.batches,
            tasks = base.tasks,
            runs = base.runs,
            logs = side.logs,
            version = side.version,
            system = side.system,
            extra = side.extra
        )
    }

    val state: StateFlow<ManageUiState> = combine(coreFlow, dataFlow) { core, data ->
        val tasks = data.tasks
        val failed = tasks.count { it.status == TaskStatus.FAILED }
        val success = data.runs.firstOrNull { it.status == OptimizationStatus.SUCCESS }
        val risks = RiskDetector.detect(
            RiskInput(
                readiness = core.readiness,
                totalKeys = core.keys.size,
                verifiedKeys = core.health.verified,
                maxDailyUsage = core.health.maxDailyUsage,
                totalStations = data.stationCount,
                unclassifiedCount = core.labels.unclassified,
                labeledCount = core.labels.classified,
                optimizeReady = core.readiness.optimizeReady,
                optimizedMarkerCount = data.system.optimizedMarkerCount,
                optimizationAvailable = success != null,
                failedTasks = failed,
                rtreeCount = data.system.rtreeCount,
                stationCountForRtree = data.system.stationCountForRtree,
                batteryWhitelisted = data.system.batteryWhitelisted,
                freeStorageBytes = data.system.freeStorageBytes
            )
        )
        ManageUiState(
            readiness = core.readiness,
            keys = core.keys,
            keyHealth = core.health,
            stationCount = data.stationCount,
            labelStats = core.labels,
            optimization = success?.toSummary(),
            optimizationRuns = data.runs.take(8),
            batches = data.batches,
            tasks = tasks,
            risks = risks,
            logs = data.logs,
            dataVersion = data.version,
            system = data.system,
            busy = data.extra.busy,
            message = data.extra.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageUiState())

    init {
        refresh()
        viewModelScope.launch {
            c.taskCenter.observeTasks().collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val freeStorage = runCatching {
                StatFs(context.filesDir.absolutePath).availableBytes
            }.getOrDefault(0L)
            val battery = runCatching {
                val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                power.isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(true)
            val spatial = runCatching { c.stationRepository.spatialCounts() }.getOrNull()
            val optimized = runCatching { c.stationRepository.optimizeCounts() }.getOrNull()
            system.update {
                it.copy(
                    freeStorageBytes = freeStorage,
                    batteryWhitelisted = battery,
                    rtreeCount = spatial?.first ?: 0L,
                    stationCountForRtree = spatial?.second ?: 0L,
                    optimizedMarkerCount = optimized?.first ?: 0,
                    optimizedRtreeCount = optimized?.second ?: 0L
                )
            }
        }
    }

    fun pause(type: TaskType) {
        viewModelScope.launch { c.taskCenter.pause(type) }
    }

    fun cancel(type: TaskType) {
        viewModelScope.launch {
            c.taskCenter.cancel(type)
            refresh()
        }
    }

    fun retry(type: TaskType) {
        viewModelScope.launch {
            c.taskCenter.retry(type)
            refresh()
        }
    }

    fun deleteBatch(batchId: Long) {
        viewModelScope.launch {
            extra.update { it.copy(busy = true) }
            val result = c.dataMaintenance.deleteBatch(batchId)
            extra.update {
                it.copy(
                    busy = false,
                    message = result.fold(
                        onSuccess = { "已删除导入批次及关联数据" },
                        onFailure = { error -> error.message ?: "删除批次失败" }
                    )
                )
            }
            refresh()
        }
    }

    fun repairIndexes() {
        viewModelScope.launch {
            extra.update { it.copy(busy = true) }
            val result = c.dataMaintenance.repairIndexes()
            extra.update {
                it.copy(
                    busy = false,
                    message = result.fold(
                        onSuccess = { counts ->
                            "索引重建完成：基站 ${counts.first}，优化点 ${counts.second}"
                        },
                        onFailure = { error -> error.message ?: "索引重建失败" }
                    )
                )
            }
            refresh()
        }
    }

    fun runOptimization() {
        if (!state.value.readiness.labelReady) {
            extra.update { it.copy(message = "请先完成分类标注，再运行压缩优化") }
            return
        }
        c.scheduler.enqueueOptimize()
        extra.update { it.copy(message = "已提交压缩优化任务") }
    }

    fun rollbackOptimization() {
        viewModelScope.launch {
            extra.update { it.copy(busy = true) }
            val ok = runCatching { c.optimizationRepository.rollback() }.getOrDefault(false)
            extra.update {
                it.copy(
                    busy = false,
                    message = if (ok) "已回退优化数据集，地图将读取原始标注数据" else "没有可回退的优化结果"
                )
            }
            refresh()
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            runCatching { c.database.logDao().clear() }
            extra.update { it.copy(message = "运行日志已清空") }
        }
    }

    fun consumeMessage() {
        extra.update { it.copy(message = null) }
    }

    companion object {
        fun factory(app: CellMapApp) = viewModelFactory {
            initializer { ManageViewModel(app) }
        }
    }
}
