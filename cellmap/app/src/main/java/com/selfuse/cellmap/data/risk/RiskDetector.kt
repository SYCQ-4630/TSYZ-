package com.selfuse.cellmap.data.risk

import com.selfuse.cellmap.core.model.RiskAction
import com.selfuse.cellmap.core.model.RiskInput
import com.selfuse.cellmap.core.model.RiskItem
import com.selfuse.cellmap.core.model.RiskLevel

/**
 * 风险只由数据与任务状态计算，UI 不硬编码任何风险判断。
 */
object RiskDetector {

    fun detect(input: RiskInput): List<RiskItem> {
        val risks = mutableListOf<RiskItem>()

        if (input.verifiedKeys == 0) {
            risks += RiskItem(
                id = "key_unverified",
                level = RiskLevel.HIGH,
                title = if (input.totalKeys == 0) "尚未配置天地图 Key" else "没有可用的天地图 Key",
                description = if (input.totalKeys == 0) {
                    "Key 列表为空，卫星底图无法加载。"
                } else {
                    "已保存 ${input.totalKeys} 个 Key，但均未验证通过或已失效。"
                },
                impact = "地图查询不可用，底图会降级为纯色。",
                suggestion = "在 Key 模块添加并验证 Key，确认天地图控制台已开启影像 WMTS 服务。",
                action = RiskAction.OPEN_KEY
            )
        }

        if (input.maxDailyUsage >= input.quotaPerKey * 0.8) {
            val percent = (input.maxDailyUsage * 100 / input.quotaPerKey).coerceAtMost(100)
            risks += RiskItem(
                id = "key_quota",
                level = RiskLevel.MEDIUM,
                title = "Key 日配额接近上限",
                description = "单 Key 今日约 ${input.maxDailyUsage} 次瓦片请求，已达到 $percent%。",
                impact = "继续浏览可能触发 403/418，底图自动降级。",
                suggestion = "添加第 2 个 Key 轮询，或减少长时间大范围浏览。",
                action = RiskAction.OPEN_KEY
            )
        }

        if (input.unclassifiedCount >= UNCLASSIFIED_WARN) {
            risks += RiskItem(
                id = "label_unclassified",
                level = RiskLevel.MEDIUM,
                title = "存在大量未归类记录",
                description = "${input.unclassifiedCount} 条记录无法匹配四色规则（承建方非联通/电信，或共享状态未知）。",
                impact = "这些点位不会在地图上渲染，可能造成覆盖误判。",
                suggestion = "检查源表的承建方/是否共享列，修正后重新标注。",
                action = RiskAction.OPEN_LABELS
            )
        }

        if (input.labeledCount > HEAVY_LABEL_THRESHOLD && !input.optimizeReady) {
            risks += RiskItem(
                id = "heavy_unoptimized",
                level = RiskLevel.MEDIUM,
                title = "标注量较大且未完成压缩优化",
                description = "已标注 ${input.labeledCount} 条，超过 ${HEAVY_LABEL_THRESHOLD / 10_000} 万阈值，优化数据集未启用。",
                impact = "大范围浏览时渲染压力高，可能出现卡顿。",
                suggestion = "在数据管理中重新运行压缩优化，必要时评估 MVT 方案。",
                action = RiskAction.OPEN_DATA
            )
        } else if (input.labeledCount in 1..HEAVY_LABEL_THRESHOLD && !input.optimizeReady) {
            risks += RiskItem(
                id = "optimize_missing",
                level = RiskLevel.LOW,
                title = "标注数据尚未压缩优化",
                description = "地图当前读取原始标注数据。",
                impact = "数据量大时首次加载与平移可能变慢。",
                suggestion = "在数据管理中运行优化，或等待标注后的自动优化完成。",
                action = RiskAction.OPEN_DATA
            )
        }

        if (input.stationCountForRtree != input.rtreeCount) {
            risks += RiskItem(
                id = "rtree_mismatch",
                level = RiskLevel.HIGH,
                title = "R-Tree 索引与业务表不同步",
                description = "stations=${input.stationCountForRtree}，station_rtree=${input.rtreeCount}。",
                impact = "视口查询可能漏点或返回已删除点位。",
                suggestion = "在管理页执行“修复索引”，重建 R-Tree。",
                action = RiskAction.REPAIR_INDEX
            )
        }

        if (input.failedTasks > 0) {
            risks += RiskItem(
                id = "task_failed",
                level = RiskLevel.HIGH,
                title = "后台任务失败且重试未成功",
                description = "有 ${input.failedTasks} 个任务处于失败状态。",
                impact = "导入/标注/优化可能未按预期完成，就绪状态不可靠。",
                suggestion = "打开任务中心查看错误并重试，必要时清理失败批次。",
                action = RiskAction.OPEN_TASKS
            )
        }

        if (!input.batteryWhitelisted) {
            risks += RiskItem(
                id = "battery_optimization",
                level = RiskLevel.MEDIUM,
                title = "未加入电池优化白名单",
                description = "系统可能在导入或优化过程中限制后台长任务。",
                impact = "锁屏后任务可能被暂停或延迟。",
                suggestion = "按 ROM 提示将本应用设为不受限制（自用工具，无商店合规限制）。",
                action = RiskAction.REQUEST_BATTERY
            )
        }

        if (input.freeStorageBytes < LOW_STORAGE_BYTES) {
            risks += RiskItem(
                id = "low_storage",
                level = RiskLevel.MEDIUM,
                title = "存储空间不足",
                description = "剩余空间约 ${input.freeStorageBytes / 1024 / 1024} MB。",
                impact = "导入、分块文件与数据库写入可能失败。",
                suggestion = "清理旧导入暂存文件或其他应用数据后再执行大任务。",
                action = RiskAction.OPEN_DATA
            )
        }

        return risks.sortedBy { it.level.ordinal }
    }

    private const val UNCLASSIFIED_WARN = 500
    private const val HEAVY_LABEL_THRESHOLD = 50_000
    private const val LOW_STORAGE_BYTES = 300L * 1024 * 1024
}
