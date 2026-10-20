package com.selfuse.cellmap.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.TaskStatus
import com.selfuse.cellmap.ui.components.AppTopBar
import com.selfuse.cellmap.ui.components.DividerSpacer
import com.selfuse.cellmap.ui.components.EmptyHint
import com.selfuse.cellmap.ui.components.ModuleCard
import com.selfuse.cellmap.ui.components.SectionHeader
import com.selfuse.cellmap.ui.components.StatusPill
import com.selfuse.cellmap.ui.components.TaskRow
import com.selfuse.cellmap.ui.theme.RiskHigh
import com.selfuse.cellmap.ui.theme.RiskLow
import com.selfuse.cellmap.ui.theme.RiskMedium

@Composable
fun HomeScreen(
    onOpenKeys: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenLabels: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenManage: () -> Unit
) {
    val app = LocalContext.current.applicationContext as CellMapApp
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(app))
    val state by vm.state.collectAsStateWithLifecycle()

    var tasksExpanded by rememberSaveable { mutableStateOf(false) }
    var showMissingDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "基站地图",
                subtitle = "自用基站数据查询工具",
                actions = {
                    IconButton(onClick = onOpenManage) {
                        Icon(Icons.Filled.Settings, contentDescription = "统一管理")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = {
                            if (state.readiness.mapQueryReady) onOpenMap() else showMissingDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(Icons.Filled.Map, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.readiness.mapQueryReady) "进入地图查询" else "进入地图查询（前置条件未齐）"
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReadinessCard(
                keyReady = state.readiness.keyReady,
                dataReady = state.readiness.dataReady,
                labelReady = state.readiness.labelReady,
                finishedSteps = state.readiness.finishedSteps
            )

            ModuleCard(
                title = "天地图 Key",
                summary = state.keySummary,
                statusText = when {
                    state.verifiedKeys > 0 -> "可用"
                    state.keys.isNotEmpty() -> "待验证"
                    else -> "未配置"
                },
                statusColor = when {
                    state.verifiedKeys > 0 -> RiskLow
                    state.keys.isNotEmpty() -> RiskMedium
                    else -> RiskHigh
                },
                icon = Icons.Filled.Key,
                onClick = onOpenKeys
            )

            ModuleCard(
                title = "XLSX 数据导入",
                summary = state.dataSummary,
                statusText = when {
                    state.importTask?.status == TaskStatus.RUNNING -> "导入中"
                    state.latestBatch?.status == "FAILED" -> "上次失败"
                    state.readiness.dataReady -> "已就绪"
                    else -> "未导入"
                },
                statusColor = when {
                    state.importTask?.status == TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
                    state.latestBatch?.status == "FAILED" -> RiskHigh
                    state.readiness.dataReady -> RiskLow
                    else -> RiskMedium
                },
                icon = Icons.Filled.UploadFile,
                onClick = onOpenImport,
                footnote = state.latestBatch?.let {
                    "${it.fileName} · 成功 ${it.successRows} / 总 ${it.totalRows} · 失败 ${it.failedRows}"
                }
            )

            ModuleCard(
                title = "分类标注",
                summary = state.labelSummary,
                statusText = when {
                    state.readiness.labelReady -> "已启用"
                    state.labelStats.unclassified > 0 -> "待确认"
                    else -> "未完成"
                },
                statusColor = when {
                    state.readiness.labelReady -> RiskLow
                    state.labelStats.unclassified > 0 -> RiskMedium
                    else -> RiskMedium
                },
                icon = Icons.Filled.Palette,
                onClick = onOpenLabels,
                footnote = if (state.labelStats.total > 0) {
                    "联通共享 ${state.labelStats.unicomShared} · 电信共享 ${state.labelStats.telecomShared} · " +
                        "联通未共享 ${state.labelStats.unicomPrivate} · 电信未共享 ${state.labelStats.telecomPrivate}"
                } else {
                    null
                }
            )

            ModuleCard(
                title = "压缩优化与数据维护",
                summary = state.optimizeSummary,
                statusText = if (state.readiness.optimizeReady) "已启用" else "原始数据",
                statusColor = if (state.readiness.optimizeReady) RiskLow else RiskMedium,
                icon = Icons.Filled.Tune,
                onClick = onOpenManage,
                footnote = state.optimization?.let {
                    "点位 ${it.markerCount} · 聚类 ${it.clusterCount} · 分块 ${it.chunkCount}"
                }
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { tasksExpanded = !tasksExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "任务中心",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        val active = state.tasks.count { it.active }
                        if (active > 0) {
                            StatusPill("$active 个进行中", MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                        }
                        if (state.failedTasks > 0) {
                            StatusPill("${state.failedTasks} 个失败", RiskHigh)
                            Spacer(Modifier.width(6.dp))
                        }
                        Icon(
                            if (tasksExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (tasksExpanded) "收起" else "展开"
                        )
                    }
                    DividerSpacer()
                    val visible = if (tasksExpanded) {
                        state.tasks
                    } else {
                        state.tasks.filter { it.active || it.status == TaskStatus.FAILED }
                    }
                    if (visible.isEmpty()) {
                        EmptyHint("暂无后台任务")
                    } else {
                        visible.forEachIndexed { index, task ->
                            if (index > 0) DividerSpacer()
                            TaskRow(
                                task = task,
                                onPause = { vm.pause(task.type) },
                                onCancel = { vm.cancel(task.type) },
                                onRetry = { vm.retry(task.type) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            SectionHeader("使用顺序")
            Text(
                "Key 与数据相互独立；标注依赖已导入数据；地图查询需要 Key、数据与标注确认三项同时就绪。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showMissingDialog) {
        val missing = buildList {
            if (!state.readiness.keyReady) add("未配置可用的天地图 Key")
            if (!state.readiness.dataReady) add("尚未导入基站数据")
            if (!state.readiness.labelReady) add("分类标注未完成或未确认")
        }
        val goFix: () -> Unit = when {
            !state.readiness.keyReady -> onOpenKeys
            !state.readiness.dataReady -> onOpenImport
            else -> onOpenLabels
        }
        AlertDialog(
            onDismissRequest = { showMissingDialog = false },
            title = { Text("地图前置条件未齐") },
            text = {
                Column {
                    Text("地图仍可打开，但底图或点位可能缺失：")
                    Spacer(Modifier.height(8.dp))
                    missing.forEach { item -> Text("· $item") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMissingDialog = false; goFix() }) { Text("去补齐") }
            },
            dismissButton = {
                TextButton(onClick = { showMissingDialog = false; onOpenMap() }) { Text("仍然进入") }
            }
        )
    }
}

@Composable
private fun ReadinessCard(
    keyReady: Boolean,
    dataReady: Boolean,
    labelReady: Boolean,
    finishedSteps: Int
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("准备度", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusPill("$finishedSteps / 3", MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { finishedSteps / 3f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            StepRow("天地图 Key", keyReady)
            StepRow("基站数据", dataReady)
            StepRow("分类标注", labelReady)
        }
    }
}

@Composable
private fun StepRow(label: String, ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            imageVector = if (ready) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (ready) RiskLow else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            if (ready) "已就绪" else "未完成",
            style = MaterialTheme.typography.bodySmall,
            color = if (ready) RiskLow else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
