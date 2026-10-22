package com.tsyz.autoledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsyz.autoledger.core.model.SourceType
import com.tsyz.autoledger.core.model.TxType
import com.tsyz.autoledger.core.util.CategoryClassifier
import com.tsyz.autoledger.core.util.Money
import com.tsyz.autoledger.core.util.TimeUtils
import com.tsyz.autoledger.data.db.TransactionEntity
import com.tsyz.autoledger.ui.MainViewModel
import com.tsyz.autoledger.ui.ReportRange
import com.tsyz.autoledger.ui.TypeFilter
import com.tsyz.autoledger.ui.components.AmountText
import com.tsyz.autoledger.ui.components.ChipRow
import com.tsyz.autoledger.ui.components.EmptyState
import com.tsyz.autoledger.ui.components.FilterChipGroup
import com.tsyz.autoledger.ui.components.RiskNote
import com.tsyz.autoledger.ui.components.SectionCard
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun TransactionsScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val filters by vm.filters.collectAsStateWithLifecycle()
    val transactions by vm.filteredTransactions.collectAsStateWithLifecycle()
    val bulkMode by vm.bulkMode.collectAsStateWithLifecycle()
    val bulkSelection by vm.bulkSelection.collectAsStateWithLifecycle()
    val scroll by vm.scrollPositions.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = scroll["transactions"] ?: 0)
    var bulkCategory by remember { mutableStateOf("其他") }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { vm.onScroll("transactions", it) }
    }

    val expense = transactions.filter { it.type == TxType.EXPENSE.name }.sumOf { it.amountCents }
    val income = transactions.filter { it.type == TxType.INCOME.name }.sumOf { it.amountCents }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeFilter.entries.forEach { type ->
                    FilterChip(
                        selected = filters.type == type,
                        onClick = { vm.setTypeFilter(type) },
                        label = { Text(type.label, style = MaterialTheme.typography.bodySmall) }
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.setBulkMode(!bulkMode) }) {
                    Text(if (bulkMode) "退出批量" else "批量整理")
                }
            }
        }
        item {
            ChipRow(
                options = CategoryClassifier.categories,
                selected = filters.category,
                onSelect = vm::setCategory
            )
        }
        item {
            SectionCard(
                title = "共 ${transactions.size} 笔",
                subtitle = "收入 ${Money.formatWithSymbol(income)} · 支出 ${Money.formatWithSymbol(expense)}"
            ) {
                RiskNote("只统计本地已入账流水。待核对记录请到管理页的“待核对”中处理。")
            }
        }
        if (bulkMode) {
            item {
                SectionCard(title = "批量修改分类", subtitle = "已选 ${bulkSelection.size} 笔") {
                    FilterChipGroup(
                        options = CategoryClassifier.categories,
                        selected = bulkCategory,
                        onSelect = { bulkCategory = it }
                    )
                    TextButton(
                        onClick = { vm.applyBulkCategory(bulkCategory) },
                        enabled = bulkSelection.isNotEmpty()
                    ) {
                        Text("应用分类到 ${bulkSelection.size} 笔")
                    }
                }
            }
        }
        if (transactions.isEmpty()) {
            item { EmptyState("当前筛选条件下没有流水") }
        } else {
            val grouped = transactions.groupBy { TimeUtils.dayKey(it.occurredAt) }
            grouped.forEach { (day, rows) ->
                item(key = "day-$day") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            day,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "支出 ${Money.formatWithSymbol(rows.filter { it.type == TxType.EXPENSE.name }.sumOf { it.amountCents })}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(rows, key = { it.id }) { tx ->
                    TransactionRow(
                        tx = tx,
                        selected = tx.id in bulkSelection,
                        bulkMode = bulkMode,
                        onClick = {
                            if (bulkMode) vm.toggleSelection(tx.id) else vm.openNote(tx.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(
    tx: TransactionEntity,
    selected: Boolean,
    bulkMode: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (bulkMode) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
                Spacer(Modifier.width(4.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tx.merchant,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    SourceType.fromName(tx.sourceType).label.let { label ->
                        AssistChip(
                            onClick = {},
                            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                            enabled = false
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(TimeUtils.formatTime(tx.occurredAt))
                        append(" · ")
                        append(tx.category)
                        if (tx.confidence < 0.80) append(" · 置信度 ${"%.0f".format(tx.confidence * 100)}%")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                tx.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            AmountText(tx.amountCents, tx.type)
        }
    }
}
