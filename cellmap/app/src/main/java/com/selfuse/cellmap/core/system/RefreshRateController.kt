package com.selfuse.cellmap.core.system

import android.app.Activity
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * 请求当前屏幕在原生分辨率下可用的最高刷新率。
 *
 * Android 应用不能直接指定 30/60/90/120/144/165/185Hz，只能向系统声明偏好；
 * 最终由系统根据面板能力、省电策略和温度决定。这里选择与当前分辨率相同的
 * 最高刷新率模式，低刷新率设备会自然停留在其支持的档位。
 */
object RefreshRateController {
    private const val TAG = "RefreshRate"

    fun apply(activity: Activity) {
        runCatching {
            val power = activity.getSystemService(PowerManager::class.java)
            if (power?.isPowerSaveMode == true) return

            @Suppress("DEPRECATION")
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                activity.windowManager.defaultDisplay
            } ?: return
            val modes = display.supportedModes
            if (modes.isEmpty()) return

            val current = display.mode
            val sameResolution = modes.filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            val best = (sameResolution.ifEmpty { modes.toList() })
                .maxByOrNull { it.refreshRate } ?: return

            val attrs = activity.window.attributes
            if (attrs.preferredDisplayModeId != best.modeId) {
                attrs.preferredDisplayModeId = best.modeId
                attrs.preferredRefreshRate = best.refreshRate
                activity.window.attributes = attrs
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                runCatching {
                    activity.window.decorView.setRequestedFrameRate(best.refreshRate)
                }
            }
        }.onFailure { Log.w(TAG, "Unable to request high refresh rate", it) }
    }
}
