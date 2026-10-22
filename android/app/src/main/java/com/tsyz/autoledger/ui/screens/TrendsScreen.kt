package com.tsyz.autoledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsyz.autoledger.core.util.Money
import com.tsyz.autoledger.ui.MainViewModel
import com.tsyz.autoledger.ui.components.CategoryPieChart
import com.tsyz.autoledger.ui.components.DailyExpenseChart
import com.tsyz.autoledger.ui.components.EmptyState
import com.tsyz.autoledger.ui.components.KeyValueRow
import com.tsyz.autoledger.ui.components.MonthlyTrendChart
import com.tsyz.autoledger.ui.components.RiskNote
import com.tsyz.autoledger.ui.components.SectionCard
import com.tsyz.autoledger.ui.components.SubDivider
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun TrendsScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val report by vm.report.collectAsStateWithLifecycle()
    val scroll by vm.scrollPositions.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = scroll["trend"] ?: 0)

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { vm.onScroll("trend", it) }
    }

    val current = report
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (current == null) {
            item { EmptyState("正在计算趋势…") }
            return@LazyColumn
        }
        item {
            SectionCard(
                title = "近六个月收支趋势",
                subtitle = "横轴为月份，单位元；趋势基于全部本地流水"
            ) {
                MonthlyTrendChart(current.trend)
            }
        }
        item {
            SectionCard(title = "同比与环比") {
                KeyValueRow("本月支出", Money.formatWithSymbol(current.expenseCents))
                KeyValueRow("支出环比", ratioText(current.momRatio))
                KeyValueRow("支出同比", ratioText(current.yoyRatio))
                SubDivider()
                RiskNote("同比需要至少一年的本地数据；缺失历史时显示“缺少对比数据”。")
            }
        }
        item {
            SectionCard(title = "${current.rangeLabel}分类占比") {
                if (current.categories.isEmpty()) {
                    EmptyState("当前范围没有支出")
                } else {
                    CategoryPieChart(current.categories)
                    val total = current.categories.sumOf { it.amountCents }
                    current.categories.take(6).forEach { category ->
                        KeyValueRow(
                            category.category,
                            "${Money.formatWithSymbol(category.amountCents)}  " +
                                "(${"%.1f".format(if (total > 0) category.amountCents * 100.0 / total else 0.0)}%)"
                        )
                    }
                }
            }
        }
        item {
            SectionCard(title = "每日支出曲线", subtitle = current.monthLabel) {
                DailyExpenseChart(current.daily)
            }
        }
        item {
            SectionCard(title = "月度小结") {
                Column {
                    Row {
                        Text(
                            "本月共记录 ${current.captureCount} 次支付相关屏幕捕获，" +
                                "其中自动入账 ${current.autoPostedCount} 笔。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "高频商户：" +
                            current.topMerchants.take(3).joinToString("、") { it.merchant }
                                .ifBlank { "暂无" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun ratioText(ratio: Double?): String {
    if (ratio == null) return "缺少对比数据"
    return if (ratio >= 0) "增加 ${"%.1f".format(ratio * 100)}%"
    else "减少 ${"%.1f".format(-ratio * 100)}%"
}
