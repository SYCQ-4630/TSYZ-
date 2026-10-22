package com.tsyz.autoledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsyz.autoledger.core.model.TxType
import com.tsyz.autoledger.core.util.CategoryClassifier
import com.tsyz.autoledger.ui.MainViewModel
import com.tsyz.autoledger.ui.components.FilterChipGroup
import com.tsyz.autoledger.ui.components.FormField
import com.tsyz.autoledger.ui.components.RiskNote
import com.tsyz.autoledger.ui.components.SectionCard

@Composable
fun ManualEntryScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val form by vm.manualForm.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(
                title = "手动补录",
                subtitle = "用于漏抓或线下现金支出，提交后仍走同一套幂等规则"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TxType.entries.forEach { type ->
                        FilterChip(
                            selected = form.type == type,
                            onClick = { vm.updateManualForm(form.copy(type = type)) },
                            label = { Text(type.label) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                FormField(
                    label = "金额（元）",
                    value = form.amount,
                    onValueChange = { vm.updateManualForm(form.copy(amount = it)) },
                    keyboardType = KeyboardType.Decimal,
                    placeholder = "例如 32.50"
                )
                Spacer(Modifier.height(10.dp))
                FormField(
                    label = "商户 / 对方",
                    value = form.merchant,
                    onValueChange = { vm.updateManualForm(form.copy(merchant = it)) },
                    placeholder = "例如 便利店"
                )
                Spacer(Modifier.height(10.dp))
                FormField(
                    label = "日期",
                    value = form.date,
                    onValueChange = { vm.updateManualForm(form.copy(date = it)) },
                    placeholder = "yyyy-MM-dd"
                )
                Spacer(Modifier.height(10.dp))
                FormField(
                    label = "备注（可选）",
                    value = form.note,
                    onValueChange = { vm.updateManualForm(form.copy(note = it)) },
                    singleLine = false,
                    placeholder = "最多 500 字"
                )
            }
        }
        item {
            SectionCard(title = "分类") {
                FilterChipGroup(
                    options = CategoryClassifier.categories,
                    selected = form.category,
                    onSelect = { vm.updateManualForm(form.copy(category = it)) }
                )
            }
        }
        item {
            Button(onClick = vm::saveManual, modifier = Modifier.fillMaxWidth()) {
                Text("保存补录")
            }
            Spacer(Modifier.height(8.dp))
            RiskNote("两分钟内相同金额与类型视为重复，不会再次入账。")
            Spacer(Modifier.height(4.dp))
            RiskNote("表单内容会自动保存草稿，进程被回收后可恢复。")
        }
    }
}
