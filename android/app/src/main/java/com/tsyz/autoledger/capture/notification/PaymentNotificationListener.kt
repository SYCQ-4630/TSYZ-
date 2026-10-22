package com.tsyz.autoledger.capture.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.tsyz.autoledger.AutoLedgerApp
import com.tsyz.autoledger.core.model.RawCapture
import com.tsyz.autoledger.core.model.SourceType
import com.tsyz.autoledger.core.util.PaymentTextParser
import com.tsyz.autoledger.health.HealthChannel
import kotlinx.coroutines.launch

/**
 * 可选补充通道。微信/支付宝可能不发支付通知，所以默认关闭，
 * 仅当用户明确开启并授予通知使用权时参与解析。
 */
class PaymentNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        val app = application as? AutoLedgerApp ?: return
        app.container.health.beat(HealthChannel.NOTIFICATION, "listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val app = application as? AutoLedgerApp ?: return
        if (notification.packageName == packageName) return

        val container = app.container
        container.appScope.launch {
            if (!container.settings.current().notificationCaptureEnabled) return@launch
            val engine = runCatching { container.rules.engine() }.getOrNull() ?: return@launch
            if (!engine.supportsPackage(notification.packageName)) return@launch

            val extras = notification.notification?.extras ?: return@launch
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            val combined = listOf(title, text, bigText)
                .filter { it.isNotBlank() }
                .joinToString("\n")
            if (!PaymentTextParser.looksLikePayment(combined)) return@launch

            container.health.beatNow(HealthChannel.NOTIFICATION, "notification captured")
            container.pipeline.ingest(
                RawCapture(
                    packageName = notification.packageName,
                    rawText = combined.take(8_000),
                    timestamp = notification.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    sourceType = SourceType.NOTIFICATION
                )
            )
        }
    }
}
