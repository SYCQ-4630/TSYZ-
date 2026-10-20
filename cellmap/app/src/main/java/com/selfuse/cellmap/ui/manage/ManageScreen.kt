package com.selfuse.cellmap.ui.manage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.RiskAction
import com.selfuse.cellmap.core.model.RiskItem
import com.selfuse.cellmap.core.model.TaskStatus
import com.selfuse.cellmap.core.util.Formats
import com.selfuse.cellmap.data.db.AppLogEntity
import com.selfuse.cellmap.data.db.BatchStatus
import com.selfuse.cellmap.data.db.ImportBatchEntity
import com.selfuse.cellmap.data.db.LogLevel
import com.selfuse.cellmap.data.db.OptimizationRunEntity
import com.selfuse.cellmap.data.db.OptimizationStatus
import com.selfuse.cellmap.ui.components.AppTopBar
import com.selfuse.cellmap.ui.components.ConfirmDialog
import com.selfuse.cellmap.ui.components.DividerSpacer
import com.selfuse.cellmap.ui.components.EmptyHint
import com.selfuse.cellmap.ui.components.KeyValueRow
import com.selfuse.cellmap.ui.components.RiskRow
import com.selfuse.cellmap.ui.components.SectionHeader
import com.selfuse.cellmap.ui.components.StatusPill
import com.selfuse.cellmap.ui.components.TaskRow
import com.selfuse.cellmap.ui.theme.RiskHigh
import com.selfuse.cellmap.ui.theme.RiskLow
import com.selfuse.cellmap.ui.theme.RiskMedium

private val TABS = listOf("总览", "任务", "数据", "风险", "日志")

@Composable
fun ManageScreen(
    onBack: () -> Unit,
    onOpenKeys: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenLabels: () -> Unit,
    onOpenMap: () -> Unit
) {
    val app = LocalContext.current.applicationContext as CellMapApp
    val context = LocalContext.current
    val vm: ManageViewModel = viewModel(factory = ManageViewModel.factory(app))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<ImportBatchEntity?>(null) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.consumeMessage()
    }

    val handleRisk: (RiskItem) -> Unit = { risk ->
        when (risk.action) {
            RiskAction.OPEN_KEY -> onOpenKeys()
            RiskAction.OPEN_IMPORT -> onOpenImport()
            RiskAction.OPEN_LABELS -> onOpenLabels()
            RiskAction.OPEN_TASKS -> tabIndex = 1
            RiskAction.OPEN_DATA, RiskAction.OPEN_MANAGE -> tabIndex = 2
            RiskAction.REQUEST_BATTERY -> requestBatteryWhitelist(context)
            RiskAction.REPAIR_INDEX -> vm.repairIndexes()
            null -> Unit
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = "统一管理", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = tabIndex) {
                TABS.forEachIndexed { index, label ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = {
                            Text(
                                if (index == 3 && state.risks.isNotEmpty()) {
                                    "$label ${state.risks.size}"
                                } else {
                                    label
                                }
                            )
                        }
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when (tabIndex) {
                    0 -> OverviewTab(
                        state = state,
                        onOpenKeys = onOpenKeys,
                        onOpenImport = onOpenImport,
                        onOpenLabels = onOpenLabels,
                        onOpenMap = onOpenMap
                    )
                    1 -> TasksTab(
                        state = state,
                        onPause = vm::pause,
                        onCancel = vm::cancel,
                        onRetry = vm::retry
                    )
                    2 -> DataTab(
                        state = state,
                        onRepair = vm::repairIndexes,
                        onOptimize = vm::runOptimization,
                        onRollback = vm::rollbackOptimization,
                        onDeleteBatch = { pendingDelete = it }
                    )
                    3 -> RisksTab(state = state, onAction = handleRisk)
                    else -> LogsTab(state = state, onClear = vm::clearLogs)
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        ConfirmDialog(
            title = "删除导入批次",
            text = "将删除「${target.fileName}」导入的全部基站记录，并清空标注与优化结果。此操作不可撤销。",
            confirmText = "删除",
            destructive = true,
            onConfirm = { vm.deleteBatch(target.id) },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun OverviewTab(
    state: ManageUiState,
    onOpenKeys: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenLabels: () -> Unit,
    onOpenMap: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("就绪度", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    ReadinessLine("天地图 Key", state.readiness.keyReady)
                    ReadinessLine("基站数据", state.readiness.dataReady)
                    ReadinessLine("分类标注", state.readiness.labelReady)
                    ReadinessLine("压缩优化", state.readiness.optimizeReady)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenKeys) { Text("Key") }
                        OutlinedButton(onClick = onOpenImport) { Text("导入") }
                        OutlinedButton(onClick = onOpenLabels) { Text("标注") }
                        OutlinedButton(onClick = onOpenMap) { Text("地图") }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("数据总览", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("基站记录", Formats.number(state.stationCount))
                    KeyValueRow("数据版本", "v${state.dataVersion}")
                    KeyValueRow("导入批次", Formats.number(state.batches.size))
                    KeyValueRow("标注总数", Formats.number(state.labelStats.total))
                    KeyValueRow("可渲染", Formats.number(state.labelStats.classified))
                    KeyValueRow("未归类", Formats.number(state.labelStats.unclassified))
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Key 与配额", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("已保存", "${state.keyHealth.total} 个")
                    KeyValueRow("已验证", "${state.keyHealth.verified} 个")
                    KeyValueRow("今日最高用量", Formats.number(state.keyHealth.maxDailyUsage))
                    KeyValueRow("单 Key 日配额参考", Formats.number(10_000))
                }
            }
        }
        item {
            val optimization = state.optimization
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("优化数据集", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (optimization == null) {
                        Text(
                            "尚未生成优化数据集",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        KeyValueRow("点位", Formats.number(optimization.markerCount))
                        KeyValueRow("格网聚合", Formats.number(optimization.clusterCount))
                        KeyValueRow("分块文件", Formats.number(optimization.chunkCount))
                        KeyValueRow("原始估算", Formats.bytes(optimization.rawBytes))
                        KeyValueRow("优化后", Formats.bytes(optimization.optimizedBytes))
                        KeyValueRow("完成时间", Formats.dateTime(optimization.finishedAt))
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("设备与索引", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("剩余存储", Formats.bytes(state.system.freeStorageBytes))
                    KeyValueRow(
                        "电池白名单",
                        if (state.system.batteryWhitelisted) "已加入" else "未加入"
                    )
                    KeyValueRow("R-Tree 基站", Formats.number(state.system.rtreeCount))
                    KeyValueRow("业务表基站", Formats.number(state.system.stationCountForRtree))
                    KeyValueRow("R-Tree 优化点", Formats.number(state.system.optimizedRtreeCount))
                    KeyValueRow("优化点表", Formats.number(state.system.optimizedMarkerCount.toLong()))
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ReadinessLine(label: String, ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        StatusPill(if (ready) "就绪" else "未完成", if (ready) RiskLow else RiskMedium)
    }
}

@Composable
private fun TasksTab(
    state: ManageUiState,
    onPause: (com.selfuse.cellmap.core.model.TaskType) -> Unit,
    onCancel: (com.selfuse.cellmap.core.model.TaskType) -> Unit,
    onRetry: (com.selfuse.cellmap.core.model.TaskType) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.tasks.isEmpty()) {
            item { EmptyHint("暂无任务记录") }
        }
        items(state.tasks, key = { it.workId }) { task ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp)) {
                    TaskRow(
                        task = task,
                        onPause = { onPause(task.type) },
                        onCancel = { onCancel(task.type) },
                        onRetry = { onRetry(task.type) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DataTab(
    state: ManageUiState,
    onRepair: () -> Unit,
    onOptimize: () -> Unit,
    onRollback: () -> Unit,
    onDeleteBatch: (ImportBatchEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("维护操作", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "修复索引会重建 station_rtree 与 optimized_rtree；压缩优化不会覆盖原始标注数据，可随时回退。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onRepair,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("修复 R-Tree 索引") }
                        OutlinedButton(
                            onClick = onOptimize,
                            enabled = !state.busy && state.readiness.labelReady,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (state.readiness.labelReady) "运行压缩优化" else "需先完成分类标注") }
                        OutlinedButton(
                            onClick = onRollback,
                            enabled = !state.busy && state.optimization != null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("回退优化数据集") }
                    }
                }
            }
        }
        item { SectionHeader("导入批次") }
        if (state.batches.isEmpty()) {
            item { EmptyHint("还没有导入记录") }
        }
        items(state.batches, key = { it.id }) { batch ->
            BatchRow(batch = batch, onDelete = { onDeleteBatch(batch) })
        }
        item { SectionHeader("优化记录") }
        if (state.optimizationRuns.isEmpty()) {
            item { EmptyHint("还没有优化记录") }
        }
        items(state.optimizationRuns, key = { it.id }) { run ->
            OptimizationRow(run)
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun BatchRow(batch: ImportBatchEntity, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    batch.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusPill(batchLabel(batch.status), batchColor(batch.status))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "成功 ${Formats.number(batch.successRows)} · 重复 ${Formats.number(batch.duplicateRows)} · " +
                    "失败 ${Formats.number(batch.failedRows)} · 总行 ${Formats.number(batch.totalRows)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "工作表 ${batch.sheetName} · 开始 ${Formats.dateTime(batch.startedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!batch.note.isNullOrBlank()) {
                Text(
                    batch.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = RiskMedium
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onDelete,
                enabled = batch.status != BatchStatus.RUNNING
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (batch.status == BatchStatus.RUNNING) "导入中不可删除" else "删除批次")
            }
        }
    }
}

@Composable
private fun OptimizationRow(run: OptimizationRunEntity) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "优化 #${run.id} · v${run.dataVersion}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(optimizationLabel(run.status), optimizationColor(run.status))
            }
            Text(
                "点位 ${Formats.number(run.markerCount)} · 聚合 ${Formats.number(run.clusterCount)} · " +
                    "分块 ${Formats.number(run.chunkCount)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${Formats.dateTime(run.startedAt)} → ${Formats.dateTime(run.finishedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!run.note.isNullOrBlank()) {
                Text(run.note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RisksTab(state: ManageUiState, onAction: (RiskItem) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.risks.isEmpty()) {
            item { EmptyHint("当前没有检测到风险项") }
        }
        items(state.risks, key = { it.id }) { risk ->
            RiskRow(risk = risk, onAction = onAction)
        }
    }
}

@Composable
private fun LogsTab(state: ManageUiState, onClear: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "最近 ${state.logs.size} 条运行日志",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onClear, enabled = state.logs.isNotEmpty()) {
                    Text("清空日志")
                }
            }
        }
        if (state.logs.isEmpty()) {
            item { EmptyHint("暂无日志") }
        }
        items(state.logs, key = { it.id }) { log ->
            LogRow(log)
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun LogRow(log: AppLogEntity) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(log.module, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                StatusPill(log.level, logColor(log.level))
                Spacer(Modifier.weight(1f))
                Text(
                    Formats.dateTime(log.ts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(log.message, style = MaterialTheme.typography.bodyMedium)
            if (!log.detail.isNullOrBlank()) {
                Text(
                    log.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun requestBatteryWhitelist(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
    runCatching { context.startActivity(request) }
        .onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
}

@Composable
private fun logColor(level: String): Color = when (level) {
    LogLevel.ERROR -> RiskHigh
    LogLevel.WARN -> RiskMedium
    LogLevel.INFO -> RiskLow
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun batchLabel(status: String): String = when (status) {
    BatchStatus.RUNNING -> "进行中"
    BatchStatus.COMPLETED -> "已完成"
    BatchStatus.PARTIAL -> "部分成功"
    BatchStatus.FAILED -> "失败"
    BatchStatus.CANCELLED -> "已取消"
    BatchStatus.ABANDONED -> "已放弃"
    else -> status
}

@Composable
private fun batchColor(status: String): Color = when (status) {
    BatchStatus.COMPLETED -> RiskLow
    BatchStatus.PARTIAL -> RiskMedium
    BatchStatus.RUNNING -> MaterialTheme.colorScheme.primary
    BatchStatus.FAILED -> RiskHigh
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun optimizationLabel(status: String): String = when (status) {
    OptimizationStatus.RUNNING -> "进行中"
    OptimizationStatus.SUCCESS -> "成功"
    OptimizationStatus.FAILED -> "失败"
    OptimizationStatus.ROLLED_BACK -> "已回退"
    else -> status
}

@Composable
private fun optimizationColor(status: String): Color = when (status) {
    OptimizationStatus.SUCCESS -> RiskLow
    OptimizationStatus.RUNNING -> MaterialTheme.colorScheme.primary
    OptimizationStatus.FAILED -> RiskHigh
    else -> MaterialTheme.colorScheme.outline
}
