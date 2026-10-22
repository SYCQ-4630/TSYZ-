package com.tsyz.autoledger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsyz.autoledger.ui.MainViewModel
import com.tsyz.autoledger.ui.components.KeyValueRow
import com.tsyz.autoledger.ui.components.RiskNote
import com.tsyz.autoledger.ui.components.SectionCard
import com.tsyz.autoledger.ui.components.SubDivider

@Composable
fun RulesScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val ruleInfo by vm.ruleInfo.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importRules(uri)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(title = "当前规则", subtitle = "规则存于加密数据库，可热更新") {
                KeyValueRow("规则版本", "v${ruleInfo.version}")
                KeyValueRow("启用条数", "${ruleInfo.count}")
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "text/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("导入规则 JSON")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = vm::reparseNow, modifier = Modifier.fillMaxWidth()) {
                    Text("回溯重解析近期失败捕获")
                }
            }
        }
        item {
            SectionCard(title = "规则文件格式") {
                Text(
                    """
                    {
                      "version": 4,
                      "rules": [
                        {
                          "id": "wechat_pay",
                          "packages": ["com.tencent.mm"],
                          "titleKeywords": ["支付成功", "转账"],
                          "amountRegex": "(¥|￥)\s?([0-9,]+(?:\.[0-9]{1,2})?)",
                          "merchantRegex": "收款方[：:]\s*(.+)",
                          "type": "EXPENSE",
                          "category": "转账",
                          "confidence": 0.9,
                          "priority": 10
                        }
                      ]
                    }
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SubDivider()
                RiskNote("导入会整表替换现有规则；版本号提升后，近期解析失败的原始捕获可回溯重解析。")
                Spacer(Modifier.height(4.dp))
                RiskNote("规则越具体越稳定：优先使用固定标题关键词与明确的商户正则，避免过宽匹配造成误入账。")
            }
        }
        item {
            SectionCard(title = "规则与隐私") {
                RiskNote("规则只匹配本机屏幕文本与短信，不包含任何联网下载或远程配置能力。")
                Spacer(Modifier.height(4.dp))
                RiskNote("修改规则后建议先观察一段时间待核对队列，再提高自动入账阈值。")
            }
        }
    }
}
