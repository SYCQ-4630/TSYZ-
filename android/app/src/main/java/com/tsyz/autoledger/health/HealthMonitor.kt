package com.tsyz.autoledger.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.tsyz.autoledger.core.log.SafeLogger
import com.tsyz.autoledger.core.model.ChannelState
import com.tsyz.autoledger.core.model.ChannelStatus
import com.tsyz.autoledger.data.db.HealthDao
import com.tsyz.autoledger.data.db.HealthHeartbeatEntity
import com.tsyz.autoledger.data.prefs.AppSettings
import com.tsyz.autoledger.data.prefs.SettingsStore
import com.tsyz.autoledger.permissions.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HealthChannel(val label: String, val staleAfterMillis: Long, val eventDriven: Boolean) {
    ACCESSIBILITY("无障碍抓取", 48L * 60 * 60 * 1000, false),
    SMS("短信通道", 48L * 60 * 60 * 1000, true),
    NOTIFICATION("通知补充", 72L * 60 * 60 * 1000, true),
    OCR("截图 OCR", 72L * 60 * 60 * 1000, true),
    FOREGROUND("前台守护", 24L * 60 * 60 * 1000, false),
    WORKER("月报任务", 35L * 24 * 60 * 60 * 1000, false),
    APP("应用进程", 24L * 60 * 60 * 1000, false)
}

/**
 * 通道健康监测。期望状态来自权限与开关，心跳来自真实事件或服务保活；
 * 未启用、尚未触发与真正超期分别呈现，避免用户刚授权就看到整屏“心跳超期”。
 */
class HealthMonitor(
    private val context: Context,
    private val settings: SettingsStore,
    private val dao: HealthDao,
    private val logger: SafeLogger,
    private val scope: CoroutineScope
) {
    fun beat(channel: HealthChannel, detail: String = "") {
        scope.launch(Dispatchers.IO) { beatNow(channel, detail) }
    }

    suspend fun beatNow(channel: HealthChannel, detail: String = "") = withContext(Dispatchers.IO) {
        dao.upsert(
            HealthHeartbeatEntity(
                channel = channel.name,
                lastBeatAt = System.currentTimeMillis(),
                detail = detail.take(120)
            )
        )
    }

    fun observe(): Flow<List<ChannelStatus>> = combine(
        dao.observeAll(),
        settings.settings,
        ticker()
    ) { rows, current, _ ->
        buildStatuses(rows, current, System.currentTimeMillis())
    }

    suspend fun statuses(): List<ChannelStatus> = withContext(Dispatchers.IO) {
        buildStatuses(dao.all(), settings.current(), System.currentTimeMillis())
    }

    /** 只把真正超期的通道写日志；空闲、未启用都不再误报。 */
    suspend fun checkStaleChannels(): List<ChannelStatus> {
        val stale = statuses().filter { it.state == ChannelState.STALE }
        stale.forEach { logger.warn("HEALTH", "通道心跳超期: ${it.label}") }
        return stale
    }

    private fun buildStatuses(
        rows: List<HealthHeartbeatEntity>,
        current: AppSettings,
        now: Long
    ): List<ChannelStatus> = HealthChannel.entries.map { channel ->
        val row = rows.firstOrNull { it.channel == channel.name }
        val expected = isExpected(channel, current)
        val state = HealthEvaluator.evaluate(
            expected = expected,
            lastBeatAt = row?.lastBeatAt,
            now = now,
            staleAfterMillis = channel.staleAfterMillis,
            eventDriven = channel.eventDriven
        )
        ChannelStatus(
            channel = channel.name,
            label = channel.label,
            lastBeatAt = row?.lastBeatAt,
            healthy = state != ChannelState.STALE,
            detail = detailFor(channel, state, row, current, now),
            state = state
        )
    }

    private fun isExpected(channel: HealthChannel, current: AppSettings): Boolean = when (channel) {
        HealthChannel.ACCESSIBILITY -> PermissionHelper.isAccessibilityEnabled(context)
        HealthChannel.SMS -> smsGranted()
        HealthChannel.NOTIFICATION ->
            current.notificationCaptureEnabled && PermissionHelper.isNotificationListenerEnabled(context)
        HealthChannel.OCR ->
            current.ocrEnabled &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                PermissionHelper.isAccessibilityEnabled(context)
        HealthChannel.FOREGROUND -> current.foregroundServiceEnabled
        HealthChannel.WORKER -> current.monthlyReportEnabled
        HealthChannel.APP -> true
    }

    private fun smsGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun detailFor(
        channel: HealthChannel,
        state: ChannelState,
        row: HealthHeartbeatEntity?,
        current: AppSettings,
        now: Long
    ): String = when (state) {
        ChannelState.DISABLED -> disabledReason(channel, current)
        ChannelState.IDLE -> idleReason(channel, row, now)
        ChannelState.STALE -> row?.detail?.takeIf { it.isNotBlank() }
            ?.let { "$it，已超过预期心跳周期" }
            ?: "已超过预期心跳周期"
        ChannelState.OK -> row?.detail.orEmpty()
    }

    private fun disabledReason(channel: HealthChannel, current: AppSettings): String = when (channel) {
        HealthChannel.ACCESSIBILITY -> "无障碍服务未开启"
        HealthChannel.SMS -> "未授予短信权限"
        HealthChannel.NOTIFICATION -> if (!current.notificationCaptureEnabled) {
            "通知补充已关闭"
        } else {
            "未授予通知使用权"
        }
        HealthChannel.OCR -> when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> "系统版本低于 Android 11"
            !current.ocrEnabled -> "截图 OCR 已关闭"
            else -> "无障碍服务未开启"
        }
        HealthChannel.FOREGROUND -> "前台守护已关闭"
        HealthChannel.WORKER -> "月报任务已关闭"
        HealthChannel.APP -> "应用未运行"
    }

    private fun idleReason(channel: HealthChannel, row: HealthHeartbeatEntity?, now: Long): String {
        if (row != null) {
            val days = ((now - row.lastBeatAt).coerceAtLeast(0L) / DAY_MILLIS).coerceAtLeast(1L)
            return "已启用，最近捕获在 $days 天前"
        }
        return when (channel) {
            HealthChannel.ACCESSIBILITY -> "已开启，等待服务连接"
            HealthChannel.SMS -> "已授权，等待首条支付短信"
            HealthChannel.NOTIFICATION -> "已开启，等待首条支付通知"
            HealthChannel.OCR -> "已启用，等待首次截图 OCR"
            HealthChannel.FOREGROUND -> "已启用，等待服务启动"
            HealthChannel.WORKER -> "已排程，等待首次执行"
            HealthChannel.APP -> "等待应用启动"
        }
    }

    /** 定期重算，权限变更或心跳老化后无需等新事件也能刷新界面。 */
    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(REFRESH_INTERVAL_MILLIS)
        }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val REFRESH_INTERVAL_MILLIS = 15_000L
    }
}
