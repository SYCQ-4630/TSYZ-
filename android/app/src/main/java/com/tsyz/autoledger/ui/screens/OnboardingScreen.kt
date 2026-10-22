package com.tsyz.autoledger.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tsyz.autoledger.permissions.OemGuides
import com.tsyz.autoledger.permissions.PermissionHelper
import com.tsyz.autoledger.ui.MainViewModel
import com.tsyz.autoledger.ui.components.PermissionRow
import com.tsyz.autoledger.ui.components.RiskNote
import com.tsyz.autoledger.ui.components.SectionCard
import com.tsyz.autoledger.ui.components.SubDivider

@Composable
fun OnboardingScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissions by remember { mutableStateOf(PermissionHelper.snapshot(context)) }

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

    val guide = remember { OemGuides.current(context) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(title = "欢迎使用本机自动记账") {
                Text(
                    "所有流水、规则、日志都保存在本机加密数据库；应用不申请网络权限，数据不会离开设备。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "抓取策略",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "1. 无障碍识别支付结果页面文本（主力）；\n" +
                        "2. 系统截图 + 离线中文 OCR 兜底；\n" +
                        "3. 银行短信作为保底通道；\n" +
                        "4. 通知监听为可选补充；\n" +
                        "5. 账单 CSV 导入用于补齐历史数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SectionCard(title = "需要你开启的权限", subtitle = "设置后回到本页会自动刷新状态") {
                PermissionRow(
                    title = "无障碍服务（必需）",
                    granted = permissions.accessibility,
                    detail = "读取支付与银行 App 的结果页文本",
                    actionLabel = if (permissions.accessibility) "查看" else "去开启",
                    onAction = { PermissionHelper.openAccessibilitySettings(context) }
                )
                PermissionRow(
                    title = "短信权限（保底）",
                    granted = permissions.sms,
                    detail = "解析银行扣款/入账短信，作为漏抓兜底",
                    actionLabel = if (permissions.sms) "查看" else "授权",
                    onAction = {
                        if (permissions.sms) PermissionHelper.openAppDetails(context)
                        else smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
                    }
                )
                PermissionRow(
                    title = "通知权限",
                    granted = permissions.notifications,
                    detail = "月报与后台守护通知",
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
                    detail = "每月 1 日准点生成月报",
                    actionLabel = if (permissions.exactAlarm) "查看" else "去开启",
                    onAction = { PermissionHelper.openExactAlarmSettings(context) }
                )
                PermissionRow(
                    title = "电池优化白名单",
                    granted = permissions.batteryWhitelisted,
                    detail = "降低后台被系统清理的概率",
                    actionLabel = if (permissions.batteryWhitelisted) "查看" else "去设置",
                    onAction = { PermissionHelper.requestIgnoreBatteryOptimizations(context) }
                )
                PermissionRow(
                    title = "通知使用权（可选）",
                    granted = permissions.notificationListener,
                    detail = "开启后自动启用通知补充通道",
                    actionLabel = if (permissions.notificationListener) "查看" else "去开启",
                    onAction = { PermissionHelper.openNotificationListenerSettings(context) }
                )
            }
        }
        item {
            SectionCard(
                title = "${guide.brand} 额外设置",
                subtitle = "国产 ROM 需要单独允许后台运行"
            ) {
                guide.steps.forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SubDivider()
                Text(
                    "自检命令：${guide.adbHint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            RiskNote(
                "无障碍服务与短信权限可随时在系统设置中关闭。关闭后对应通道停止工作，" +
                    "已入账数据不会丢失。"
            )
            Spacer(Modifier.height(8.dp))
            RiskNote(
                "自动化无法保证 100% 捕获，请定期查看待核对队列与通道健康状态。"
            )
        }
        item {
            Button(onClick = vm::completeOnboarding, modifier = Modifier.fillMaxWidth()) {
                Text("进入应用")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "权限未完全开启也可以先进入，之后在“管理”页随时补齐。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
