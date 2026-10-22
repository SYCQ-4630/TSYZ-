package com.tsyz.autoledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tsyz.autoledger.core.util.CategoryClassifier
import com.tsyz.autoledger.ui.MainViewModel
import com.tsyz.autoledger.ui.NoteDraftState
import com.tsyz.autoledger.ui.components.FilterChipGroup
import com.tsyz.autoledger.ui.components.FormField
import com.tsyz.autoledger.ui.screens.BackupScreen
import com.tsyz.autoledger.ui.screens.ImportScreen
import com.tsyz.autoledger.ui.screens.LogsScreen
import com.tsyz.autoledger.ui.screens.ManageScreen
import com.tsyz.autoledger.ui.screens.ManualEntryScreen
import com.tsyz.autoledger.ui.screens.OnboardingScreen
import com.tsyz.autoledger.ui.screens.PendingScreen
import com.tsyz.autoledger.ui.screens.ReportScreen
import com.tsyz.autoledger.ui.screens.RulesScreen
import com.tsyz.autoledger.ui.screens.TransactionsScreen
import com.tsyz.autoledger.ui.screens.TrendsScreen
import com.tsyz.autoledger.ui.theme.AutoLedgerTheme
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AutoLedgerApp).container
        val initialScreen = intent?.getStringExtra("screen")

        setContent {
            AutoLedgerTheme {
                val vm: MainViewModel = viewModel(factory = MainViewModel.factory(container))
                val ready by vm.ready.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(Unit) {
                    vm.events.collect { message -> snackbarHostState.showSnackbar(message) }
                }
                LaunchedEffect(ready) {
                    if (ready && initialScreen == "report") vm.selectTab(0)
                }

                if (!ready) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    MainScaffold(vm = vm, snackbarHostState = snackbarHostState)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    vm: MainViewModel,
    snackbarHostState: SnackbarHostState
) {
    val tabIndex by vm.tabIndex.collectAsStateWithLifecycle()
    val stack by vm.screenStack.collectAsStateWithLifecycle()
    val noteDraft by vm.noteDraft.collectAsStateWithLifecycle()
    val top = stack.lastOrNull()

    BackHandler(enabled = top != null) { vm.popScreen() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (top != null) {
                TopAppBar(
                    title = { Text(titleOf(top)) },
                    navigationIcon = {
                        androidx.compose.material3.IconButton(onClick = vm::popScreen) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (top == null) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tabIndex == 0,
                        onClick = { vm.selectTab(0) },
                        icon = { Icon(Icons.Filled.PieChart, contentDescription = null) },
                        label = { Text("报表") }
                    )
                    NavigationBarItem(
                        selected = tabIndex == 1,
                        onClick = { vm.selectTab(1) },
                        icon = { Icon(Icons.Filled.List, contentDescription = null) },
                        label = { Text("明细") }
                    )
                    NavigationBarItem(
                        selected = tabIndex == 2,
                        onClick = { vm.selectTab(2) },
                        icon = { Icon(Icons.Filled.ShowChart, contentDescription = null) },
                        label = { Text("趋势") }
                    )
                    NavigationBarItem(
                        selected = tabIndex == 3,
                        onClick = { vm.selectTab(3) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("管理") }
                    )
                }
            }
        },
        floatingActionButton = {
            if (top == null && tabIndex == 1) {
                FloatingActionButton(onClick = vm::openManual) {
                    Icon(Icons.Filled.Add, contentDescription = "手动补录")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (top) {
                MainViewModel.ONBOARDING -> OnboardingScreen(vm)
                MainViewModel.ROUTE_MANUAL -> ManualEntryScreen(vm)
                MainViewModel.ROUTE_BACKUP -> BackupScreen(vm)
                MainViewModel.ROUTE_RULES -> RulesScreen(vm)
                MainViewModel.ROUTE_LOGS -> LogsScreen(vm)
                MainViewModel.ROUTE_IMPORT -> ImportScreen(vm)
                MainViewModel.ROUTE_PENDING -> PendingScreen(vm)
                null -> when (tabIndex) {
                    0 -> ReportScreen(vm, onOpenPending = { vm.pushScreen(MainViewModel.ROUTE_PENDING) })
                    1 -> TransactionsScreen(vm)
                    2 -> TrendsScreen(vm)
                    else -> ManageScreen(
                        vm = vm,
                        onOpenLogs = { vm.pushScreen(MainViewModel.ROUTE_LOGS) },
                        onOpenImport = { vm.pushScreen(MainViewModel.ROUTE_IMPORT) },
                        onOpenBackup = { vm.pushScreen(MainViewModel.ROUTE_BACKUP) },
                        onOpenRules = { vm.pushScreen(MainViewModel.ROUTE_RULES) },
                        onOpenPending = { vm.pushScreen(MainViewModel.ROUTE_PENDING) }
                    )
                }
            }
        }
    }

    noteDraft?.let { draft ->
        NoteEditorDialog(
            draft = draft,
            onNoteChange = vm::updateNoteDraft,
            onCategoryChange = vm::updateNoteCategory,
            onSave = vm::saveNote,
            onDismiss = vm::cancelNote
        )
    }
}

@Composable
private fun NoteEditorDialog(
    draft: NoteDraftState,
    onNoteChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("备注与分类") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FormField(
                    label = "备注",
                    value = draft.note,
                    onValueChange = onNoteChange,
                    singleLine = false,
                    keyboardType = KeyboardType.Text,
                    placeholder = "最多 500 字"
                )
                FilterChipGroup(
                    options = CategoryClassifier.categories,
                    selected = draft.category,
                    onSelect = onCategoryChange
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun titleOf(route: String): String = when (route) {
    MainViewModel.ONBOARDING -> "首次使用引导"
    MainViewModel.ROUTE_MANUAL -> "手动补录"
    MainViewModel.ROUTE_BACKUP -> "备份与快照"
    MainViewModel.ROUTE_RULES -> "解析规则"
    MainViewModel.ROUTE_LOGS -> "脱敏日志"
    MainViewModel.ROUTE_IMPORT -> "账单导入"
    MainViewModel.ROUTE_PENDING -> "待核对"
    else -> "自动记账"
}
