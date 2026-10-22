package com.tsyz.autoledger.ui.screens

import android.Manifest
import android.os.Build
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsyz.autoledger.permissions.OemGuides
import com.tsyz.autoledger.permissions.PermissionHelper
import com.tsyz.autoledger.permissions.SystemPermissionState
import com.tsyz.autoledger.ui.MainViewModel
import com.tsyz.autoledger.ui.components.KeyValueRow
import com.tsyz.autoledger.ui.components.PermissionRow
import com.tsyz.autoledger.ui.components.RiskNote
import com.tsyz.autoledger.ui.components.SectionCard
import com.tsyz.autoledger.ui.components.SubDivider
import com.tsyz.autoledger.ui.components.ToggleRow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ManageScreen(
    vm: MainViewModel,
    onOpenLogs: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenPending: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val health by vm.healthStatuses.collectAsStateWithLifecycle()
    val ruleInfo by vm.ruleInfo.collectAsStateWithLifecycle()
    val scroll by vm.scrollPositions.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = scroll["manage"] ?: 0)

    var permissions by remember { mutableStateOf(PermissionHelper.snapshot(context)) }
    var threshold by remember(settings.autoPostThreshold) { mutableStateOf(settings.autoPostThreshold) }
    var retention by remember(settings.logRetentionDays) { mutableStateOf(settings.logRetentionDays.toFloat()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = PermissionHelper.snapshot(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissions = PermissionHelper.snapshot(context) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissions = PermissionHelper.snapshot(context) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { vm.onScroll("manage", it) }
    }

    val oemGuide = remember { OemGuides.current(context) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(title = "运行状态", subtitle = "所有数据保存在本机加密数据库") {
                KeyValueRow("待核对记录", "$pending 笔")
                KeyValueRow("规则版本", "v${ruleInfo.version}（${ruleInfo.count} 条）")
                KeyValueRow("短信通道", if (permissions.sms) "已授权" else "未授权")
                KeyValueRow("无障碍通道", if (permissions.accessibility) "已开启" else "未开启")
                SubDivider()
                health.forEach { status ->
                    KeyValueRow(
                        status.label,
                        if (status.healthy) "正常" else "心跳超期",
                        valueColor = if (status.healthy) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        }
        item {
            SectionCard(
                title = "权限与系统设置",
                subtitle = "无障碍为主动力通道；短信为保底；通知监听可选"
            ) {
                PermissionRow(
                    title = "无障碍服务",
                    granted = permissions.accessibility,
                    detail = "读取微信、支付宝、云闪付与银行 App 的支付结果文本",
                    actionLabel = if (permissions.accessibility) "查看" else "去开启",
                    onAction = { PermissionHelper.openAccessibilitySettings(context) }
                )
                PermissionRow(
                    title = "短信权限（保底通道）",
                    granted = permissions.sms,
                    detail = "仅解析银行扣款/入账短信，不读取验证码以外的正文用途",
                    actionLabel = if (permissions.sms) "查看" else "授权",
                    onAction = {
                        if (permissions.sms) PermissionHelper.openAppDetails(context)
                        else smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
                    }
                )
                PermissionRow(
                    title = "通知使用权（可选补充）",
                    granted = permissions.notificationListener,
                    detail = "作为无障碍漏抓时的补充通道，默认关闭",
                    actionLabel = if (permissions.notificationListener) "查看" else "去开启",
                    onAction = { PermissionHelper.openNotificationListenerSettings(context) }
                )
                PermissionRow(
                    title = "通知权限",
                    granted = permissions.notifications,
                    detail = "月报与后台守护通知需要通知权限",
                    actionLabel = if (permissions.notifications) "查看" else "授权",
                    onAction = {
                        if (permissions.notifications) {
                            PermissionHelper.openAppNotificationSettings(context)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            PermissionHelper.openAppNotificationSettings(context)
                        }
                    }
                )
                PermissionRow(
                    title = "精确闹钟",
                    granted = permissions.exactAlarm,
                    detail = "每月 1 日 9:00 准点生成月报；未授权则空闲窗口兜底",
                    actionLabel = if (permissions.exactAlarm) "查看" else "去开启",
                    onAction = { PermissionHelper.openExactAlarmSettings(context) }
                )
                PermissionRow(
                    title = "电池优化白名单",
                    granted = permissions.batteryWhitelisted,
                    detail = "降低国产 ROM 后台清理导致的漏抓",
                    actionLabel = if (permissions.batteryWhitelisted) "查看" else "去设置",
                    onAction = { PermissionHelper.requestIgnoreBatteryOptimizations(context) }
                )
                PermissionRow(
                    title = "自启动 / 后台运行",
                    granted = false,
                    detail = "厂商限制差异较大，建议按下方 ${oemGuide.brand} 指引逐项确认",
                    actionLabel = "去设置",
                    onAction = { PermissionHelper.openAutostartSettings(context) }
                )
            }
        }
        item {
            SectionCard(title = "抓取通道", subtitle = "任一通道命中即入账，跨通道自动去重") {
                ToggleRow(
                    title = "截图 OCR 兜底",
                    description = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        "需要 Android 11 及以上，当前系统不可用"
                    } else {
                        "无障碍节点为空时截图识别，约 3 FPS 本地处理"
                    },
                    checked = settings.ocrEnabled,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                    onCheckedChange = vm::setOcrEnabled
                )
                ToggleRow(
                    title = "通知监听补充",
                    description = "可选通道，需要通知使用权；关闭后不影响无障碍与短信",
                    checked = settings.notificationCaptureEnabled,
                    onCheckedChange = vm::setNotificationCapture
                )
                ToggleRow(
                    title = "前台守护通知",
                    description = "常驻低优先级通知，提高后台存活率；可随时关闭",
                    checked = settings.foregroundServiceEnabled,
                    onCheckedChange = vm::setForegroundEnabled
                )
                SubDivider()
                RiskNote("截屏与通知内容仅在本机内存中解析，解析后不保留原文，日志只记录脱敏后的字段摘要。")
            }
        }
        item {
            SectionCard(title = "报表与入账阈值") {
                ToggleRow(
                    title = "每月自动生成月报",
                    description = "每月 1 日 9:00，WorkManager 10:00 兜底，开机后自动重建",
                    checked = settings.monthlyReportEnabled,
                    onCheckedChange = vm::setMonthlyReportEnabled
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "自动入账阈值 ${"%.0f".format(threshold * 100)}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "高置信度静默入账；低于阈值进入待核对，月报前自动交叉验证修复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    onValueChangeFinished = { vm.setAutoPostThreshold(threshold) },
                    valueRange = 0.50f..0.98f,
                    steps = 47
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "日志保留 ${retention.toInt()} 天",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = retention,
                    onValueChange = { retention = it },
                    onValueChangeFinished = { vm.setLogRetention(retention.toInt()) },
                    valueRange = 7f..14f,
                    steps = 6
                )
                OutlinedButton(onClick = vm::generateReportNow, modifier = Modifier.fillMaxWidth()) {
                    Text("立即生成上月月报并推送通知")
                }
            }
        }
        item {
            SectionCard(title = "数据管理") {
                OutlinedButton(onClick = onOpenPending, modifier = Modifier.fillMaxWidth()) {
                    Text("待核对记录（$pending）")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenImport, modifier = Modifier.fillMaxWidth()) {
                    Text("导入账单 CSV")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenBackup, modifier = Modifier.fillMaxWidth()) {
                    Text("加密快照与备份说明")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenRules, modifier = Modifier.fillMaxWidth()) {
                    Text("解析规则管理")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                    Text("脱敏日志与导出")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = vm::reparseNow, modifier = Modifier.fillMaxWidth()) {
                    Text("重新解析近期失败的捕获")
                }
            }
        }
        item {
            SectionCard(
                title = "${oemGuide.brand} 后台保活指引",
                subtitle = "系统升级或清理数据后需要重新确认"
            ) {
                oemGuide.steps.forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. $step",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                }
                SubDivider()
                Text(
                    "可选自检命令：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    oemGuide.adbHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = vm::openOnboarding) { Text("重新打开首次引导") }
            }
        }
        item {
            SectionCard(title = "能力边界") {
                RiskNote("不申请网络权限，所有解析与存储均在本机完成。")
                Spacer(Modifier.height(4.dp))
                RiskNote("无法保证 100% 捕获：支付页面结构变化、厂商后台清理、权限被系统回收都会造成漏抓。")
                Spacer(Modifier.height(4.dp))
                RiskNote("无障碍与短信读取属于敏感权限，请确认符合当地法律与个人使用场景。")
            }
        }
    }
}

private val SystemPermissionState.summary: String
    get() = "无障碍=$accessibility 短信=$sms 通知=$notifications"
