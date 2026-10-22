package com.tsyz.autoledger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsyz.autoledger.ui.MainViewModel
import com.tsyz.autoledger.ui.components.KeyValueRow
import com.tsyz.autoledger.ui.components.RiskNote
import com.tsyz.autoledger.ui.components.SectionCard

@Composable
fun ImportScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val importing by vm.importing.collectAsStateWithLifecycle()
    val result by vm.importResult.collectAsStateWithLifecycle()

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importBill(uri)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(
                title = "导入账单 CSV",
                subtitle = "支持 UTF-8 / GBK，逗号、分号或制表符分隔"
            ) {
                Text(
                    "表头可选，兼容列名：时间/日期、金额、商户/描述、类型、分类、备注。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "示例：\n时间,金额,商户,类型,分类,备注\n" +
                        "2026-05-01 12:30,32.50,美团,支出,餐饮,午饭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { openLauncher.launch(arrayOf("text/*", "text/csv", "application/csv")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !importing
                ) {
                    Text("选择 CSV 文件")
                }
                if (importing) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        result?.let { summary ->
            item {
                SectionCard(title = "本次导入结果", subtitle = "单次最多处理 20000 行") {
                    KeyValueRow("读取行数", "${summary.total}")
                    KeyValueRow("直接入账", "${summary.posted}")
                    KeyValueRow("进入待核对", "${summary.pending}")
                    KeyValueRow("自动去重", "${summary.duplicate}")
                    KeyValueRow("解析失败", "${summary.failed}")
                }
            }
        }
        item {
            SectionCard(title = "幂等与去重说明") {
                RiskNote("导入与实时捕获共用同一去重键：金额 + 类型 + 商户 + 1 分钟时间桶，并在 ±120 秒内做相似查询。")
                Spacer(Modifier.height(4.dp))
                RiskNote("重复导入同一份账单不会产生重复流水；金额相同但商户或时间不同的记录仍会保留。")
                Spacer(Modifier.height(4.dp))
                RiskNote("导入的原始行会先写入加密的 raw_captures，解析失败可在规则页回溯重试。")
            }
        }
    }
}
