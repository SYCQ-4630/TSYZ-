package com.tsyz.autoledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsyz.autoledger.core.model.SourceType
import com.tsyz.autoledger.core.util.Money
import com.tsyz.autoledger.core.util.TimeUtils
import com.tsyz.autoledger.ui.MainViewModel
import com.tsyz.autoledger.ui.components.AmountText
import com.tsyz.autoledger.ui.components.EmptyState
import com.tsyz.autoledger.ui.components.RiskNote

@Composable
fun PendingScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val pending by vm.pendingList.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            RiskNote(
                "中低置信度记录先进入待核对。月报生成前会做交叉验证并自动修复可确认的记录，" +
                    "其余需要你人工确认。"
            )
        }
        if (pending.isEmpty()) {
            item { EmptyState("没有待核对记录") }
        } else {
            items(pending, key = { it.id }) { record ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                record.merchant,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "${TimeUtils.formatDateTime(record.occurredAt)} · ${record.category} · " +
                                    SourceType.fromName(record.sourceType).label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "置信度 ${"%.0f".format(record.confidence * 100)}% · " +
                                    when (record.reason) {
                                        "medium_confidence" -> "中等置信度"
                                        "low_confidence" -> "低置信度"
                                        else -> record.reason
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        AmountText(record.amountCents, record.type)
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { vm.confirmPending(record.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("确认入账（将按幂等规则去重）")
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "原始捕获文本只保存在加密数据库中，不在此处展示，避免敏感信息暴露。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
