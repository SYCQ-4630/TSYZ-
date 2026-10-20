package com.selfuse.cellmap.ui.key

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.KeyUi
import com.selfuse.cellmap.core.util.Formats
import com.selfuse.cellmap.ui.components.AppTopBar
import com.selfuse.cellmap.ui.components.ConfirmDialog
import com.selfuse.cellmap.ui.components.DividerSpacer
import com.selfuse.cellmap.ui.components.EmptyHint
import com.selfuse.cellmap.ui.components.KeyValueRow
import com.selfuse.cellmap.ui.components.SectionHeader
import com.selfuse.cellmap.ui.components.StatusPill
import com.selfuse.cellmap.ui.theme.RiskHigh
import com.selfuse.cellmap.ui.theme.RiskLow
import com.selfuse.cellmap.ui.theme.RiskMedium

@Composable
fun KeysScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as CellMapApp
    val vm: KeysViewModel = viewModel(factory = KeysViewModel.factory(app))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var label by rememberSaveable { mutableStateOf("") }
    var rawKey by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<KeyUi?>(null) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.consumeMessage()
    }

    Scaffold(
        topBar = { AppTopBar(title = "天地图 Key", onBack = onBack) },
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
                    Text("配置说明", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "1. 在天地图开放平台申请浏览器端 Key，并在控制台开启影像 WMTS 服务。\n" +
                            "2. 最多保存 5 个 Key，地图按当日用量轮询选择，单 Key 建议不超过 10000 次/日。\n" +
                            "3. Key 使用 Android Keystore 加密后存入本地，不会打包进 APK，也不会上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已保存 Key", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusPill("${state.health.verified} / ${state.health.total} 已验证", RiskLow)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (state.keys.isEmpty()) {
                        EmptyHint("还没有保存任何 Key")
                    } else {
                        state.keys.forEachIndexed { index, key ->
                            if (index > 0) DividerSpacer()
                            KeyRow(
                                key = key,
                                verifying = state.verifyingId == key.id,
                                onVerify = { vm.verify(key.id) },
                                onDelete = { pendingDelete = key }
                            )
                        }
                    }
                    if (state.health.maxDailyUsage > 0) {
                        DividerSpacer()
                        val quota = 10_000
                        Text(
                            "今日最高用量：${state.health.maxDailyUsage} / $quota 次",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (state.health.maxDailyUsage.toFloat() / quota).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            SectionHeader("添加 Key")
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("备注名称（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rawKey,
                onValueChange = { rawKey = it },
                label = { Text("天地图 Key") },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (keyVisible) "隐藏" else "显示"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    vm.add(label, rawKey)
                    label = ""
                    rawKey = ""
                },
                enabled = rawKey.isNotBlank() && !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("保存 Key")
                }
            }

            Text(
                "提示：验证会请求一次天地图瓦片。网络错误不会清除原有验证状态；403/418 表示 Key 无效或配额耗尽。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    val target = pendingDelete
    if (target != null) {
        ConfirmDialog(
            title = "删除 Key",
            text = "确定删除「${target.label}」？底图将不再使用该 Key。",
            confirmText = "删除",
            destructive = true,
            onConfirm = { vm.remove(target.id) },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun KeyRow(
    key: KeyUi,
    verifying: Boolean,
    onVerify: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(key.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusPill(
                if (key.verified) "已验证" else "未验证",
                if (key.verified) RiskLow else RiskMedium
            )
        }
        Spacer(Modifier.height(4.dp))
        KeyValueRow("Key", key.masked)
        KeyValueRow("添加时间", Formats.dateTime(key.createdAt))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onVerify, enabled = !verifying) {
                if (verifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("验证中")
                } else {
                    Text("验证")
                }
            }
            OutlinedButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("删除")
            }
        }
    }
}
