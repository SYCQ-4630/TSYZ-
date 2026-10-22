package com.tsyz.autoledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsyz.autoledger.core.util.TimeUtils
import com.tsyz.autoledger.ui.MainViewModel
import com.tsyz.autoledger.ui.components.EmptyState
import com.tsyz.autoledger.ui.components.KeyValueRow
import com.tsyz.autoledger.ui.components.RiskNote
import com.tsyz.autoledger.ui.components.SectionCard

@Composable
fun BackupScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val backups by vm.backups.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(
                title = "加密数据库快照",
                subtitle = "VACUUM INTO 生成全库加密副本，密钥由 Android Keystore 保护"
            ) {
                OutlinedButton(onClick = vm::createBackupNow, modifier = Modifier.fillMaxWidth()) {
                    Text("立即生成加密快照")
                }
                Spacer(Modifier.height(8.dp))
                RiskNote("快照保存在应用私有目录，不写入外部存储；卸载应用会一并删除。")
                Spacer(Modifier.height(4.dp))
                RiskNote("应用会在数据库版本升级前自动生成一份迁移前快照，并只保留最近若干份。")
            }
        }
        item {
            SectionCard(title = "已有快照（${backups.size}）") {
                if (backups.isEmpty()) {
                    EmptyState("还没有快照")
                } else {
                    backups.forEach { backup ->
                        Column(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            KeyValueRow(backup.name, formatSize(backup.sizeBytes))
                            Text(
                                TimeUtils.formatDateTime(backup.modifiedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = "恢复方式") {
                Text(
                    "本项目不提供一键恢复入口，避免误覆盖正在使用的加密库。如需恢复：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "1. 在系统设置中停止应用；\n" +
                        "2. 通过 adb 备份当前数据库与快照；\n" +
                        "3. 由开发者在调试版本中用快照替换数据库并校验完整性。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                RiskNote("恢复操作有数据覆盖风险，请在明确知道后果时再执行。")
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
