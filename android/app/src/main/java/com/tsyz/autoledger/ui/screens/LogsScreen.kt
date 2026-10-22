package com.tsyz.autoledger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsyz.autoledger.core.util.Money
import com.tsyz.autoledger.core.util.TimeUtils
import com.tsyz.autoledger.data.db.LogEntity
import com.tsyz.autoledger.ui.MainViewModel
import com.tsyz.autoledger.ui.components.EmptyState
import com.tsyz.autoledger.ui.components.RiskNote
import com.tsyz.autoledger.ui.components.SectionCard
import com.tsyz.autoledger.ui.theme.DangerRed
import com.tsyz.autoledger.ui.theme.WarningAmber

private val modules = listOf(
    null to "全部",
    "APP" to "进程",
    "INGEST" to "入账",
    "PARSE" to "解析",
    "DEDUPE" to "去重",
    "RULES" to "规则",
    "REPORT" to "月报",
    "HEALTH" to "健康",
    "SMS" to "短信",
    "OCR" to "OCR",
    "MANUAL" to "手动",
    "SNAPSHOT" to "快照",
    "MAINT" to "维护"
)

@Composable
fun LogsScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val module by vm.logModule.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) vm.exportLogs(uri, module)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard(
                title = "脱敏日志",
                subtitle = "金额与商户已掩码，不记录支付正文；保留 ${14} 天以内"
            ) {
                Text(
                    "日志仅用于定位漏抓与解析失败，最多保留 2500 条。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { exportLauncher.launch("auto_ledger_logs_${System.currentTimeMillis()}.csv") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("导出当前筛选日志（CSV）")
                }
                Spacer(Modifier.height(8.dp))
                RiskNote("导出文件包含模块、时间与脱敏后的摘要，请自行妥善保管。")
            }
        }
        item {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(modules.size) { index ->
                    val (value, label) = modules[index]
                    FilterChip(
                        selected = module == value,
                        onClick = { vm.setLogModule(value) },
                        label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }
        }
        if (logs.isEmpty()) {
            item { EmptyState("暂无日志") }
        } else {
            items(logs, key = { it.id }) { log -> LogRow(log) }
        }
    }
}

@Composable
private fun LogRow(log: LogEntity) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                log.level,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = when (log.level) {
                    "ERROR" -> DangerRed
                    "WARN" -> WarningAmber
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${TimeUtils.formatDateTime(log.ts)} · ${log.module}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            log.message,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
