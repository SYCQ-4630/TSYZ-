package com.tsyz.autoledger.capture.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.tsyz.autoledger.AutoLedgerApp
import com.tsyz.autoledger.core.model.IngestStatus
import com.tsyz.autoledger.core.model.RawCapture
import com.tsyz.autoledger.core.model.SourceType
import com.tsyz.autoledger.health.HealthChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 主力通道：无障碍服务。
 *
 * 策略：
 * 1. 按包名过滤，非目标应用直接丢弃；
 * 2. 0/800/2000/3500ms 错峰执行 DFS、关键词搜索、事件源回溯、多窗口遍历；
 * 3. 其他通道未命中且疑似支付结果时，才调用截图 OCR；
 * 4. 同包名 5 秒冷却，横竖屏切换会调整策略顺序与 OCR 节流参数。
 */
class PaymentAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private val processing = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var targetPackages: Set<String> = DEFAULT_TARGETS

    @Volatile
    private var landscape = false

    @Volatile
    private var serviceContainer: AutoLedgerApp? = null

    private var ocr: OcrFallback? = null
    private var refreshJob: Job? = null
    private var heartbeatJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = application as? AutoLedgerApp
        serviceContainer = app
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        ocr = OcrFallback()

        app?.container?.health?.beat(HealthChannel.ACCESSIBILITY, "service connected")
        scope.launch { refreshTargets() }
        refreshJob = scope.launch {
            while (isActive) {
                delay(10L * 60L * 1000L)
                refreshTargets()
            }
        }
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(6L * 60L * 60L * 1000L)
                serviceContainer?.container?.health?.beatNow(HealthChannel.ACCESSIBILITY, "idle heartbeat")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val app = serviceContainer ?: return
        val packageName = event?.packageName?.toString() ?: return
        if (packageName !in targetPackages) return
        if (!isInterestingEvent(event)) return

        val now = System.currentTimeMillis()
        val last = cooldowns[packageName] ?: 0L
        if (now - last < COOLDOWN_MS) return
        cooldowns[packageName] = now

        val eventText = ScreenTextExtractor.fromEvent(event)
        val backtrackText = ScreenTextExtractor.fromEventSource(event)
        if (eventText.isBlank() && backtrackText.isBlank()) return

        if (processing.putIfAbsent(packageName, true) != null) return
        scope.launch {
            try {
                captureWithStrategy(app, packageName, eventText, backtrackText)
            } finally {
                processing.remove(packageName)
            }
        }
    }

    private suspend fun captureWithStrategy(
        app: AutoLedgerApp,
        packageName: String,
        eventText: String,
        backtrackText: String
    ) {
        val root = currentRoot()
        val first = if (landscape) {
            ScreenTextExtractor.keywordText(root).ifBlank { ScreenTextExtractor.dfsText(root) }
        } else {
            ScreenTextExtractor.dfsText(root).ifBlank { ScreenTextExtractor.keywordText(root) }
        }
        var lastSuspiciousText = first
        if (tryIngest(app, packageName, first, SourceType.ACCESSIBILITY)) return

        delay(STEP_DELAY_MS)
        val second = if (landscape) {
            ScreenTextExtractor.dfsText(currentRoot())
        } else {
            ScreenTextExtractor.keywordText(currentRoot())
        }
        if (second.isNotBlank()) lastSuspiciousText = second
        if (tryIngest(app, packageName, second, SourceType.ACCESSIBILITY)) return

        delay(LONG_STEP_DELAY_MS)
        val third = backtrackText.ifBlank { ScreenTextExtractor.keywordText(currentRoot()) }
        if (third.isNotBlank()) lastSuspiciousText = third
        if (tryIngest(app, packageName, third, SourceType.ACCESSIBILITY)) return

        delay(LAST_STEP_DELAY_MS)
        val windows = runCatching { windows }.getOrNull().orEmpty()
        val fourth = ScreenTextExtractor.windowsText(windows)
        if (fourth.isNotBlank()) lastSuspiciousText = fourth
        if (tryIngest(app, packageName, fourth, SourceType.ACCESSIBILITY)) return

        // OCR 仅在疑似支付结果页、其他层未命中、用户开启 OCR 时调用。
        val ocrEnabled = app.container.settings.current().ocrEnabled
        if (!ocrEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val suspiciousText = listOf(eventText, backtrackText, lastSuspiciousText)
            .firstOrNull { ScreenTextExtractor.looksLikePayment(it) }
            ?: return
        if (!ScreenTextExtractor.looksLikePayment(suspiciousText)) return
        val ocrText = ocr?.recognize(this, landscape) ?: return
        if (!ScreenTextExtractor.looksLikePayment(ocrText)) return
        tryIngest(app, packageName, ocrText, SourceType.OCR)
    }

    private suspend fun tryIngest(
        app: AutoLedgerApp,
        packageName: String,
        text: String,
        source: SourceType
    ): Boolean {
        if (text.isBlank() || !ScreenTextExtractor.looksLikePayment(text)) return false
        val result = app.container.pipeline.ingest(
            RawCapture(
                packageName = packageName,
                rawText = text.take(16_000),
                timestamp = System.currentTimeMillis(),
                sourceType = source
            )
        )
        return result.status == IngestStatus.POSTED ||
            result.status == IngestStatus.PENDING ||
            result.status == IngestStatus.DUPLICATE
    }

    private fun currentRoot() = runCatching { rootInActiveWindow }.getOrNull()

    private fun isInterestingEvent(event: AccessibilityEvent): Boolean = when (event.eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_CLICKED -> true
        else -> false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private suspend fun refreshTargets() {
        val app = serviceContainer ?: return
        runCatching {
            val packages = app.container.rules.accessibilityPackages()
            if (packages.isNotEmpty()) targetPackages = packages + DEFAULT_TARGETS
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        refreshJob?.cancel()
        heartbeatJob?.cancel()
        ocr?.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val COOLDOWN_MS = 5_000L
        private const val STEP_DELAY_MS = 800L
        private const val LONG_STEP_DELAY_MS = 1_200L
        private const val LAST_STEP_DELAY_MS = 1_500L
        private val DEFAULT_TARGETS = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.unionpay",
            "com.icbc",
            "com.chinamworld.main",
            "com.android.bankabc",
            "cmb.pb",
            "com.bankcomm.Bankcomm",
            "com.spdbccc.app",
            "com.ecitic.bank.mobile",
            "com.cmbchina.ccd.pluto.cmbActivity"
        )
    }
}
