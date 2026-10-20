package com.selfuse.cellmap.ui.label

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.MarkerStyle
import com.selfuse.cellmap.core.model.TaskStatus
import com.selfuse.cellmap.core.util.Formats
import com.selfuse.cellmap.ui.components.AppTopBar
import com.selfuse.cellmap.ui.components.DividerSpacer
import com.selfuse.cellmap.ui.components.KeyValueRow
import com.selfuse.cellmap.ui.components.MarkerLegend
import com.selfuse.cellmap.ui.components.SectionHeader
import com.selfuse.cellmap.ui.components.StatusPill
import com.selfuse.cellmap.ui.components.TaskRow
import com.selfuse.cellmap.ui.theme.RiskHigh
import com.selfuse.cellmap.ui.theme.RiskLow
import com.selfuse.cellmap.ui.theme.RiskMedium

@Composable
fun LabelScreen(
    onBack: () -> Unit,
    onOpenMap: () -> Unit
) {
    val app = LocalContext.current.applicationContext as CellMapApp
    val vm: LabelViewModel = viewModel(factory = LabelViewModel.factory(app))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.consumeMessage()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "分类标注",
                onBack = onBack,
                actions = {
                    OutlinedButton(onClick = onOpenMap, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("地图")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("四色分类规则", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    MarkerLegend()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "承建方为联通/电信，且共享状态明确时才能归类。承建方为移动或其他、或共享状态未知的记录不会渲染，需要回到源表修正。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("标注状态", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusPill(
                            when {
                                state.readiness.labelReady -> "已启用"
                                state.stats.completedAt > 0L -> "待确认"
                                else -> "未完成"
                            },
                            when {
                                state.readiness.labelReady -> RiskLow
                                state.stats.completedAt > 0L -> RiskMedium
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("总记录", Formats.number(state.stats.total))
                    KeyValueRow("可渲染", Formats.number(state.stats.classified))
                    KeyValueRow(
                        "未归类",
                        Formats.number(state.stats.unclassified),
                        if (state.stats.unclassified > 0) RiskHigh else MaterialTheme.colorScheme.onSurface
                    )
                    KeyValueRow("完成时间", Formats.dateTime(state.stats.completedAt))
                    DividerSpacer()
                    KeyValueRow("联通 · 已共享", Formats.number(state.stats.unicomShared))
                    KeyValueRow("电信 · 已共享", Formats.number(state.stats.telecomShared))
                    KeyValueRow("联通 · 未共享", Formats.number(state.stats.unicomPrivate))
                    KeyValueRow("电信 · 未共享", Formats.number(state.stats.telecomPrivate))
                }
            }

            if (state.canConfirm) {
                Card(colors = CardDefaults.cardColors(containerColor = RiskMedium.copy(alpha = 0.10f))) {
                    Column(Modifier.padding(16.dp)) {
                        Text("存在未归类记录", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "有 ${Formats.number(state.stats.unclassified)} 条记录无法匹配四色规则，这些点位不会显示在地图上。确认后地图标注才会启用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = vm::confirm) { Text("确认并启用地图标注") }
                    }
                }
            }

            val task = state.task
            if (task != null) {
                SectionHeader("标注任务")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        TaskRow(
                            task = task,
                            onPause = vm::cancel,
                            onCancel = vm::cancel,
                            onRetry = vm::retry
                        )
                        if (task.status == TaskStatus.SUCCEEDED) {
                            Text(
                                "标注完成后会自动触发压缩优化，可在统一管理中查看进度。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Button(
                onClick = vm::run,
                enabled = state.readiness.dataReady && !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    when {
                        !state.readiness.dataReady -> "请先导入基站数据"
                        state.stats.completedAt > 0L -> "重新分类标注"
                        else -> "开始分类标注"
                    }
                )
            }

            if (!state.readiness.dataReady) {
                Text(
                    "导入完成后才能标注。返回首页进入“XLSX 数据导入”。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!state.readiness.optimizeReady && state.stats.classified > 0) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("压缩优化", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "优化数据集尚未启用，地图当前读取原始标注数据。标注完成后会自动排队优化，也可以稍后在统一管理中手动运行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
