package com.tsyz.autoledger.permissions

import android.content.Context
import android.os.Build

data class OemGuide(
    val brand: String,
    val steps: List<String>,
    val adbHint: String
)

object OemGuides {
    val all: List<OemGuide> = listOf(
        OemGuide(
            brand = "小米 / Redmi",
            steps = listOf(
                "设置 → 应用设置 → 授权管理 → 自启动，开启“自动记账”",
                "设置 → 应用设置 → 应用管理 → 自动记账 → 省电策略，选择“无限制”",
                "最近任务卡片下拉，给自动记账加锁，避免一键清理",
                "设置 → 应用设置 → 特殊权限 → 电池优化，选择“不优化”"
            ),
            adbHint = "adb shell dumpsys deviceidle whitelist | grep com.tsyz.autoledger"
        ),
        OemGuide(
            brand = "华为 / 荣耀",
            steps = listOf(
                "手机管家 → 应用启动管理 → 自动记账，关闭自动管理并允许自启动、关联启动、后台活动",
                "设置 → 电池 → 更多电池设置 → 休眠时始终保持网络连接（可选）",
                "设置 → 应用 → 自动记账 → 耗电详情，允许后台活动",
                "最近任务下拉加锁"
            ),
            adbHint = "adb shell dumpsys deviceidle whitelist | grep com.tsyz.autoledger"
        ),
        OemGuide(
            brand = "OPPO / 一加 / realme",
            steps = listOf(
                "设置 → 电池 → 应用耗电管理 → 自动记账，允许后台运行、允许自启动",
                "设置 → 应用 → 自启动管理，开启自动记账",
                "设置 → 应用 → 自动记账 → 耗电管理，关闭“智能省电”限制",
                "最近任务加锁"
            ),
            adbHint = "adb shell dumpsys deviceidle whitelist | grep com.tsyz.autoledger"
        ),
        OemGuide(
            brand = "vivo / iQOO",
            steps = listOf(
                "i 管家 → 应用管理 → 权限管理 → 自启动，开启自动记账",
                "设置 → 电池 → 后台高耗电，允许自动记账",
                "设置 → 应用与权限 → 应用管理 → 自动记账 → 电池，允许后台运行",
                "最近任务加锁"
            ),
            adbHint = "adb shell dumpsys deviceidle whitelist | grep com.tsyz.autoledger"
        ),
        OemGuide(
            brand = "其他 Android",
            steps = listOf(
                "在系统设置中允许应用自启动与后台活动",
                "将应用加入电池优化白名单",
                "在最近任务中锁定应用",
                "部分 ROM 还需要允许“悬浮窗”或“后台弹出界面”，本项目不依赖这些权限"
            ),
            adbHint = "adb shell dumpsys deviceidle whitelist | grep com.tsyz.autoledger"
        )
    )

    fun current(context: Context): OemGuide {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        return all.firstOrNull { guide ->
            val key = guide.brand.substringBefore(" ")
            manufacturer.contains(key, ignoreCase = true) ||
                (key == "小米" && manufacturer.contains("xiaomi", true)) ||
                (key == "华为" && manufacturer.contains("huawei", true)) ||
                (key == "OPPO" && (manufacturer.contains("oppo", true) || manufacturer.contains("realme", true))) ||
                (key == "vivo" && manufacturer.contains("vivo", true))
        } ?: all.last()
    }
}
