package com.tsyz.autoledger.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tsyz.autoledger.MainActivity
import com.tsyz.autoledger.R
import com.tsyz.autoledger.core.model.ChannelState
import com.tsyz.autoledger.core.util.Money
import com.tsyz.autoledger.report.ReportSummary

object Notifications {
    const val SERVICE_CHANNEL = "auto_ledger_service"
    const val MONTHLY_CHANNEL = "auto_ledger_monthly"
    const val SERVICE_NOTIFICATION_ID = 1001
    const val MONTHLY_NOTIFICATION_ID = 2001

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val service = NotificationChannel(
            SERVICE_CHANNEL,
            "后台守护",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持支付结果监听，仅本地处理"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        val monthly = NotificationChannel(
            MONTHLY_CHANNEL,
            "月度报表",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "每月 1 日月报与待核对数量提醒"
        }
        manager.createNotificationChannels(listOf(service, monthly))
    }

    fun serviceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ledger)
            .setContentTitle("自动记账正在后台运行")
            .setContentText("本地监听支付结果，不申请网络权限")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent(context))
            .build()

    fun postMonthlyReport(context: Context, report: ReportSummary) {
        ensureChannels(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val monthLabel = formatMonth(report.month)
        val staleChannels = report.health.filter { it.state == ChannelState.STALE }
        val healthText = if (staleChannels.isEmpty()) {
            ""
        } else {
            staleChannels.joinToString(" · ") { "${it.label}心跳超期" }
        }
        val body = buildString {
            append("收入 ${Money.formatWithSymbol(report.incomeCents)}  ")
            append("支出 ${Money.formatWithSymbol(report.expenseCents)}  ")
            append("结余 ${Money.formatWithSymbol(report.balanceCents)}")
            append("\n本月 ${report.pendingCount} 笔待核对")
            if (healthText.isNotBlank()) append("\n通道提醒：$healthText")
        }
        val notification = NotificationCompat.Builder(context, MONTHLY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ledger)
            .setContentTitle("$monthLabel 月报")
            .setContentText("收入 ${Money.formatWithSymbol(report.incomeCents)} · 支出 ${Money.formatWithSymbol(report.expenseCents)} · ${report.pendingCount} 笔待核对")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "重新生成", refreshReportIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(MONTHLY_NOTIFICATION_ID, notification)
        }
    }

    fun formatMonth(monthKey: String): String {
        if (monthKey.length < 7) return monthKey
        return "${monthKey.substring(0, 4)}年${monthKey.substring(5, 7)}月"
    }

    private fun refreshReportIntent(context: Context): PendingIntent {
        val intent = Intent(context, com.tsyz.autoledger.schedule.ReportActionReceiver::class.java)
            .setAction(com.tsyz.autoledger.schedule.ReportActionReceiver.ACTION_REFRESH_REPORT)
        return PendingIntent.getBroadcast(
            context,
            2002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("screen", "report")
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
