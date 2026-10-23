package com.tsyz.autoledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsyz.autoledger.core.model.ChannelState
import com.tsyz.autoledger.core.util.Money
import com.tsyz.autoledger.core.util.TimeUtils
import com.tsyz.autoledger.ui.ChartMode
import com.tsyz.autoledger.ui.MainViewModel
import com.tsyz.autoledger.ui.ReportRange
import com.tsyz.autoledger.ui.components.CategoryBarChart
import com.tsyz.autoledger.ui.components.CategoryPieChart
import com.tsyz.autoledger.ui.components.DailyExpenseChart
import com.tsyz.autoledger.ui.components.EmptyState
import com.tsyz.autoledger.ui.components.FilterChipGroup
import com.tsyz.autoledger.ui.components.KeyValueRow
import com.tsyz.autoledger.ui.components.RiskNote
import com.tsyz.autoledger.ui.components.SectionCard
import com.tsyz.autoledger.ui.components.StatTile
import com.tsyz.autoledger.ui.components.SubDivider
import com.tsyz.autoledger.ui.theme.ExpenseRed
import com.tsyz.autoledger.ui.theme.IncomeGreen
import com.tsyz.autoledger.ui.theme.WarningAmber
import com.tsyz.autoledger.ui.theme.LedgerSecondary
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ReportScreen(
    vm: MainViewModel,
    onOpenPending: () -> Unit,
    modifier: Modifier = Modifier
) {
    val report by vm.report.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val chartMode by vm.chartMode.collectAsStateWithLifecycle()
    val scroll by vm.scrollPositions.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = scroll["report"] ?: 0)

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { vm.onScroll("report", it) }
    }

    val current = report
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FilterChipGroup(
                options = ReportRange.entries.map { it.label },
                selected = filters.range.label,
                onSelect = { label ->
                    ReportRange.entries.firstOrNull { it.label == label }?.let(vm::setRange)
                }
            )
        }
        if (current == null) {
            item { EmptyState("正在汇总本地流水…") }
            return@LazyColumn
        }
        item {
            SectionCard(title = "${current.monthLabel} 概览", subtitle = current.rangeLabel) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatTile("收入", Money.formatWithSymbol(current.incomeCents), IncomeGreen, Modifier.weight(1f))
                    StatTile("支出", Money.formatWithSymbol(current.expenseCents), ExpenseRed, Modifier.weight(1f))
                    StatTile("结余", Money.formatWithSymbol(current.balanceCents), LedgerSecondary, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                KeyValueRow("自动捕获", "${current.captureCount} 次，自动入账 ${current.autoPostedCount} 笔")
                KeyValueRow(
                    "待核对",
                    "${current.pendingCount} 笔",
                    valueColor = if (current.pendingCount > 0) WarningAmber else MaterialTheme.colorScheme.onSurface
                )
                if (current.pendingCount > 0) {
                    TextButton(onClick = onOpenPending) { Text("去核对") }
                }
            }
        }
        item {
            SectionCard(title = "支出结构") {
                FilterChipGroup(
                    options = ChartMode.entries.map { it.label },
                    selected = chartMode.label,
                    onSelect = { label ->
                        ChartMode.entries.firstOrNull { it.label == label }?.let(vm::setChartMode)
                    }
                )
                Spacer(Modifier.height(8.dp))
                when (chartMode) {
                    ChartMode.BAR -> CategoryBarChart(current.categories)
                    ChartMode.PIE -> CategoryPieChart(current.categories)
                    ChartMode.LINE -> DailyExpenseChart(current.daily)
                }
                if (current.categories.isEmpty()) {
                    EmptyState("当前时间范围还没有支出记录")
                }
            }
        }
        if (current.categories.isNotEmpty()) {
            item {
                SectionCard(title = "分类排行") {
                    current.categories.take(8).forEach { category ->
                        Column(modifier = Modifier.padding(vertical = 5.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(category.category, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    Money.formatWithSymbol(category.amountCents),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { category.ratio.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(5.dp)
                            )
                        }
                    }
                }
            }
        }
        if (current.topMerchants.isNotEmpty()) {
            item {
                SectionCard(title = "高频商户 Top 5") {
                    current.topMerchants.forEachIndexed { index, merchant ->
                        KeyValueRow(
                            "${index + 1}. ${merchant.merchant}（${merchant.count} 笔）",
                            Money.formatWithSymbol(merchant.amountCents)
                        )
                    }
                }
            }
        }
        if (current.anomalies.isNotEmpty()) {
            item {
                SectionCard(title = "异常提醒", subtitle = "与前三个月均值比较") {
                    current.anomalies.forEach { anomaly ->
                        RiskNote(
                            "${anomaly.category} 本月 ${Money.formatWithSymbol(anomaly.currentCents)}，" +
                                "为近三月均值的 ${"%.1f".format(anomaly.increaseRatio * 100)}%"
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
        item {
            SectionCard(title = "环比与同比") {
                KeyValueRow("支出环比", ratioText(current.momRatio))
                KeyValueRow("支出同比", ratioText(current.yoyRatio))
                SubDivider()
                Text(
                    "月报固定在每月 1 日 9:00 生成，未授予精确闹钟权限时以系统空闲窗口兜底。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SectionCard(title = "通道健康", subtitle = "区分正常、待触发、未启用与心跳超期；只有超期会在月报中提醒") {
                if (current.health.isEmpty()) {
                    EmptyState("暂无通道心跳")
                } else {
                    current.health.forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val state = line.state
                            Icon(
                                imageVector = when (state) {
                                    ChannelState.OK -> Icons.Filled.CheckCircle
                                    ChannelState.IDLE -> Icons.Filled.Schedule
                                    ChannelState.STALE -> Icons.Filled.Warning
                                    ChannelState.DISABLED -> Icons.Filled.Block
                                },
                                contentDescription = null,
                                tint = when (state) {
                                    ChannelState.OK -> MaterialTheme.colorScheme.primary
                                    ChannelState.IDLE -> MaterialTheme.colorScheme.tertiary
                                    ChannelState.STALE -> WarningAmber
                                    ChannelState.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    line.label,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (line.detail.isNotBlank()) {
                                    Text(
                                        line.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                "${state.label} · " +
                                    (line.lastBeatAt?.let { TimeUtils.formatDateTime(it) } ?: "从未上报"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ratioText(ratio: Double?): String {
    if (ratio == null) return "缺少对比数据"
    val percent = "%.1f".format(ratio * 100)
    return if (ratio >= 0) "增加 $percent%" else "减少 ${"%.1f".format(-ratio * 100)}%"
}
