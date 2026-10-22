package com.tsyz.autoledger.capture.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tsyz.autoledger.core.util.Hashing
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * 第五层兜底：系统截图 API + ML Kit 中文离线 OCR。
 * 线程节流约 3 FPS；如果连续两次 OCR 文本一致（设备静止/页面不变），降频到 5 秒一次。
 */
class OcrFallback {

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val mutex = Mutex()

    private var nextAllowedAt = 0L
    private var lastSignature = ""

    suspend fun recognize(service: AccessibilityService, landscape: Boolean): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val now = SystemClock.elapsedRealtime()
        if (now < nextAllowedAt) return null
        if (!mutex.tryLock()) return null
        try {
            val interval = if (landscape) LANDSCAPE_INTERVAL_MS else NORMAL_INTERVAL_MS
            nextAllowedAt = SystemClock.elapsedRealtime() + interval
            val bitmap = captureScreenshot(service) ?: return null
            val scaled = downscale(bitmap)
            if (scaled !== bitmap) bitmap.recycle()
            val text = runCatching { recognizeBitmap(scaled) }.getOrNull()
            scaled.recycle()
            if (text.isNullOrBlank()) return null
            val signature = Hashing.short(text)
            if (signature == lastSignature) {
                nextAllowedAt = SystemClock.elapsedRealtime() + IDLE_INTERVAL_MS
            }
            lastSignature = signature
            return text
        } finally {
            mutex.unlock()
        }
    }

    fun close() {
        runCatching { recognizer.close() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenshot(service: AccessibilityService): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(service),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val hardware = runCatching {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        }.getOrNull()
                        val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        runCatching { buffer.close() }
                        if (continuation.isActive) {
                            continuation.resume(software)
                        } else {
                            software?.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            )
        }

    private suspend fun recognizeBitmap(bitmap: Bitmap): String? = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result.text)
            }
            .addOnFailureListener {
                if (continuation.isActive) continuation.resume(null)
            }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_WIDTH) return bitmap
        val scale = MAX_WIDTH.toFloat() / bitmap.width
        return Bitmap.createScaledBitmap(
            bitmap,
            MAX_WIDTH,
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    companion object {
        private const val MAX_WIDTH = 1080
        private const val NORMAL_INTERVAL_MS = 340L
        private const val LANDSCAPE_INTERVAL_MS = 420L
        private const val IDLE_INTERVAL_MS = 5_000L
    }
}
