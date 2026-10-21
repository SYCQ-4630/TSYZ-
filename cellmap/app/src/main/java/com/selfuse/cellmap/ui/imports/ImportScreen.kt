package com.selfuse.cellmap.ui.imports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.FieldGroup
import com.selfuse.cellmap.core.model.ImportSummary
import com.selfuse.cellmap.core.model.StationField
import com.selfuse.cellmap.core.util.Formats
import com.selfuse.cellmap.data.importer.FieldSuggestion
import com.selfuse.cellmap.ui.components.AppTopBar
import com.selfuse.cellmap.ui.components.DividerSpacer
import com.selfuse.cellmap.ui.components.EmptyHint
import com.selfuse.cellmap.ui.components.KeyValueRow
import com.selfuse.cellmap.ui.components.SectionHeader
import com.selfuse.cellmap.ui.components.StatusPill
import com.selfuse.cellmap.ui.components.TaskRow
import com.selfuse.cellmap.ui.theme.RiskHigh
import com.selfuse.cellmap.ui.theme.RiskLow
import com.selfuse.cellmap.ui.theme.RiskMedium

@Composable
fun ImportScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as CellMapApp
    val vm: ImportViewModel = viewModel(factory = ImportViewModel.factory(app))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.select(uri)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.consumeMessage()
    }
    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        snackbar.showSnackbar(error)
        vm.consumeError()
    }

    Scaffold(
        topBar = { AppTopBar(title = "XLSX 数据导入", onBack = onBack) },
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
                    Text("选择源表", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "仅支持 .xlsx，单文件不超过 200MB。文件先复制到应用私有目录，再进行两遍流式读取；不会修改原文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            enabled = !state.busy
                        ) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.fileName == null) "选择 .xlsx 文件" else "重新选择")
                        }
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(24.dp)
                                    .align(Alignment.CenterVertically),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    val fileName = state.fileName
                    if (fileName != null) {
                        Spacer(Modifier.height(10.dp))
                        KeyValueRow("文件", fileName)
                        KeyValueRow("大小", Formats.bytes(state.fileBytes))
                        KeyValueRow("工作表", state.sheetName.ifBlank { "—" })
                        KeyValueRow(
                            "数据行",
                            if (state.totalRows > 0) Formats.number(state.totalRows) else "未知"
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { vm.clearFile() }) { Text("清除当前文件") }
                    }
                }
            }

            val task = state.task
            if (task != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("导入任务", style = MaterialTheme.typography.titleMedium)
                        TaskRow(
                            task = task,
                            onPause = vm::cancelTask,
                            onCancel = vm::cancelTask,
                            onRetry = vm::retryTask
                        )
                        if (task.status == com.selfuse.cellmap.core.model.TaskStatus.RUNNING && task.percent != null) {
                            LinearProgressIndicator(
                                progress = { (task.percent ?: 0) / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (state.fileName != null && state.previewRows.isNotEmpty()) {
                HeaderSelectionCard(state = state, onChoose = vm::chooseHeader)
                PreviewCard(state = state)
                SuggestionCard(
                    suggestions = state.suggestions,
                    onApply = vm::applySuggestions,
                    onIgnore = vm::ignoreSuggestions
                )
                MappingCard(state = state, onSelect = vm::setColumn)

                if (state.missingRequired.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = RiskHigh.copy(alpha = 0.08f))) {
                        Text(
                            "缺少必填字段：" + state.missingRequired.joinToString { it.displayName } +
                                "。请调整表头行或手动选择列。",
                            style = MaterialTheme.typography.bodySmall,
                            color = RiskHigh,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Button(
                    onClick = vm::start,
                    enabled = !state.busy && !state.queued && state.missingRequired.isEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(if (state.queued) "已提交，等待执行" else "开始导入")
                }
            }

            val latest = state.latest
            if (latest != null) {
                SectionHeader("最近一次导入")
                BatchCard(latest)
            }

            if (state.batches.size > 1) {
                SectionHeader("导入历史")
                state.batches.drop(1).take(5).forEach { batch ->
                    BatchCard(batch)
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeaderSelectionCard(state: ImportUiState, onChoose: (Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text("确认表头行", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "选择包含字段名称的行，当前自动识别为第 ${state.headerRow} 行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            state.previewRows.take(8).forEachIndexed { index, row ->
                val rowNumber = state.rowNumbers.getOrElse(index) { index }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = rowNumber == state.headerRow,
                        onClick = { onChoose(index) }
                    )
                    Text(
                        "第 $rowNumber 行 · " + row.take(6).joinToString(" | "),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(state: ImportUiState) {
    val headerIndex = state.rowNumbers.indexOf(state.headerRow).coerceAtLeast(0)
    val dataRows = state.previewRows.drop(headerIndex + 1).take(4)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text("数据预览", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (dataRows.isEmpty()) {
                EmptyHint("表头行之后没有可预览的数据行")
            } else {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    dataRows.forEach { row ->
                        Text(
                            row.joinToString("  |  "),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestions: List<FieldSuggestion>,
    onApply: (Set<StationField>) -> Unit,
    onIgnore: (Set<StationField>) -> Unit
) {
    if (suggestions.isEmpty()) return
    val grouped = suggestions.groupBy { it.field.group }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("相近字段待确认", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "以下表头与字段名相近但不是完全一致，按类别确认后才会写入。",
                style = MaterialTheme.typography.bodySmall
            )
            grouped.forEach { (group, items) ->
                Spacer(Modifier.height(10.dp))
                Text(group.label, style = MaterialTheme.typography.labelLarge)
                items.forEach { suggestion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(suggestion.field.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "第 ${suggestion.column + 1} 列 · ${suggestion.header.ifBlank { "（空表头）" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onApply(items.map { it.field }.toSet()) }) {
                        Text("采用本类（${items.size}）")
                    }
                    TextButton(onClick = { onIgnore(items.map { it.field }.toSet()) }) {
                        Text("忽略本类")
                    }
                }
            }
        }
    }
}

@Composable
private fun MappingCard(
    state: ImportUiState,
    onSelect: (StationField, Int?) -> Unit
) {
    val headerIndex = state.rowNumbers.indexOf(state.headerRow).coerceAtLeast(0)
    val columns = state.previewRows.getOrElse(headerIndex) { emptyList() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text("字段映射", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "自动按表头名称匹配，可手动调整。标 * 的为必填字段。未映射的列会按原表头收录到基站详情，并参与地图搜索。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            val required = StationField.entries.filter { it.required }
            val optional = StationField.entries.filterNot { it.required }
            Text("必填", style = MaterialTheme.typography.labelLarge)
            required.forEach { field ->
                MappingRow(field, columns, state.mapping[field], onSelect)
            }
            DividerSpacer()
            Text("选填", style = MaterialTheme.typography.labelLarge)
            FieldGroup.entries.forEach { group ->
                val fields = optional.filter { it.group == group }
                if (fields.isEmpty()) return@forEach
                Text(
                    group.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
                fields.forEach { field ->
                    MappingRow(field, columns, state.mapping[field], onSelect)
                }
            }
        }
    }
}

@Composable
private fun MappingRow(
    field: StationField,
    columns: List<String>,
    selected: Int?,
    onSelect: (StationField, Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            (if (field.required) "* " else "") + field.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(
                    selected?.let { index ->
                        columns.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "第 ${index + 1} 列"
                    } ?: "不映射",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("不映射") },
                    onClick = {
                        onSelect(field, null)
                        expanded = false
                    }
                )
                columns.forEachIndexed { index, header ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "第 ${index + 1} 列 · ${header.ifBlank { "（空表头）" }}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {
                            onSelect(field, index)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchCard(summary: ImportSummary) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusPill(
                    batchStatusLabel(summary.status),
                    batchStatusColor(summary.status)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "成功 ${Formats.number(summary.successRows)} · 重复 ${Formats.number(summary.duplicateRows)} · " +
                    "失败 ${Formats.number(summary.failedRows)} · 总行 ${Formats.number(summary.totalRows)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "工作表 ${summary.sheetName} · " + Formats.dateTime(summary.importedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (summary.failedReasons.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                summary.failedReasons.entries.take(3).forEach { (reason, count) ->
                    Text(
                        "· $reason ×$count",
                        style = MaterialTheme.typography.bodySmall,
                        color = RiskHigh
                    )
                }
            }
        }
    }
}

@Composable
private fun batchStatusLabel(status: String): String = when (status) {
    "RUNNING" -> "进行中"
    "COMPLETED" -> "已完成"
    "PARTIAL" -> "部分成功"
    "FAILED" -> "失败"
    "CANCELLED" -> "已取消"
    else -> status
}

@Composable
private fun batchStatusColor(status: String) = when (status) {
    "COMPLETED" -> RiskLow
    "PARTIAL" -> RiskMedium
    "RUNNING" -> MaterialTheme.colorScheme.primary
    "FAILED" -> RiskHigh
    else -> MaterialTheme.colorScheme.outline
}
