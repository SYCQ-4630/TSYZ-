package com.tsyz.autoledger.capture.sms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import com.tsyz.autoledger.AutoLedgerApp
import com.tsyz.autoledger.core.model.RawCapture
import com.tsyz.autoledger.core.model.SourceType
import com.tsyz.autoledger.core.util.PaymentTextParser
import com.tsyz.autoledger.health.HealthChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 短信通道：绑卡支付保底。纯文本正则，不遍历界面。 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) return

        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull().orEmpty()
        if (messages.isEmpty()) return
        val sender = messages.first().originatingAddress ?: "unknown"
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (body.isBlank() || !PaymentTextParser.looksLikePayment(body)) return

        val timestamp = messages.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val app = context.applicationContext as? AutoLedgerApp ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.health.beatNow(HealthChannel.SMS, "sms received")
                app.container.pipeline.ingest(
                    RawCapture(
                        packageName = "sms",
                        rawText = "发件人: $sender\n$body",
                        timestamp = timestamp,
                        sourceType = SourceType.SMS
                    )
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
