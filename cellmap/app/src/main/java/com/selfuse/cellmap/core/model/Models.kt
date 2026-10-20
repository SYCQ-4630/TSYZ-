package com.selfuse.cellmap.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
data class ReadinessState(
    val keyReady: Boolean = false,
    val dataReady: Boolean = false,
    val labelReady: Boolean = false,
    val optimizeReady: Boolean = false
) {
    val mapQueryReady: Boolean get() = keyReady && dataReady && labelReady
    val finishedSteps: Int get() = listOf(keyReady, dataReady, labelReady).count { it }
}

enum class NetworkSystem(val label: String) {
    FOUR_G("4G"),
    FIVE_G("5G");

    companion object {
        fun fromRaw(raw: String?): NetworkSystem? {
            val value = raw?.trim()?.uppercase() ?: return null
            return when {
                value.contains("5G") || value.contains("NR") -> FIVE_G
                value.contains("4G") || value.contains("LTE") -> FOUR_G
                else -> null
            }
        }
    }
}

enum class OperatorType(val label: String) {
    UNICOM("联通"),
    TELECOM("电信"),
    MOBILE("移动"),
    OTHER("其他")
}

enum class BuilderType(val label: String) {
    UNICOM("联通"),
    TELECOM("电信"),
    OTHER("其他")
}

enum class SharedState(val label: String) {
    SHARED("已共享"),
    PRIVATE("未共享"),
    UNKNOWN("未知")
}

enum class MarkerStyle(
    val key: String,
    val label: String,
    val iconName: String,
    val isSquare: Boolean,
    val colorHex: Long
) {
    UNICOM_SHARED("unicom_shared", "联通自建 · 已共享", "marker_unicom_shared", false, 0xFF1565C0),
    TELECOM_SHARED("telecom_shared", "电信自建 · 已共享", "marker_telecom_shared", false, 0xFFC62828),
    UNICOM_PRIVATE("unicom_private", "联通自建 · 未共享", "marker_unicom_private", true, 0xFFF9A825),
    TELECOM_PRIVATE("telecom_private", "电信自建 · 未共享", "marker_telecom_private", true, 0xFF2E7D32);

    companion object {
        fun fromKey(key: String?): MarkerStyle? = entries.firstOrNull { it.key == key }
        val renderableKeys: List<String> = entries.map { it.key }
    }
}

@Serializable
@Immutable
data class MarkerFilters(
    val operators: Set<String> = emptySet(),
    val sharedStates: Set<String> = emptySet(),
    val styles: Set<String> = emptySet(),
    val district: String = "",
    val band: String = "",
    val category: String = ""
) {
    val isActive: Boolean
        get() = operators.isNotEmpty() || sharedStates.isNotEmpty() || styles.isNotEmpty() ||
            district.isNotBlank() || band.isNotBlank() || category.isNotBlank()

    val activeCount: Int
        get() = operators.size + sharedStates.size + styles.size +
            (if (district.isNotBlank()) 1 else 0) +
            (if (band.isNotBlank()) 1 else 0) +
            (if (category.isNotBlank()) 1 else 0)
}

enum class StationField(val displayName: String, val required: Boolean, val aliases: List<String>) {
    LATITUDE("纬度", true, listOf("纬度", "lat", "latitude")),
    LONGITUDE("经度", true, listOf("经度", "lon", "lng", "longitude")),
    BUILDER("承建方", true, listOf("承建方", "承建单位", "建设单位", "builder")),
    OPERATOR("运营商", false, listOf("运营商", "运营方", "operator")),
    DISTRICT("管理区县", false, listOf("管理区县", "区县", "行政区县", "district")),
    OWNER("网元归属方", false, listOf("网元归属方", "归属方", "网元归属", "owner")),
    STATION_ID("基站 ID", true, listOf("基站id", "基站编号", "基站标识", "stationid", "enodebid", "gnbid")),
    CELL_NAME("小区名称", false, listOf("小区名称", "小区名", "cellname")),
    CELL_ID("小区 ID", true, listOf("小区id", "小区编号", "小区标识", "cellid", "localcellid")),
    CATEGORY("覆盖类别", false, listOf("覆盖类别", "覆盖类型", "category")),
    BAND("频段", false, listOf("频段", "band", "frequency")),
    SITE_NAME("站址名称", false, listOf("站址名称", "站址", "站点名称", "sitename")),
    SHARED("是否共享", true, listOf("是否共享", "共享", "共享状态", "isshared", "shared")),
    ANTENNA_HEIGHT("天线总挂高", false, listOf("天线总挂高", "挂高", "天线高度", "antennaheight")),
    CO_DEVICE_45G("45G 共设备", false, listOf("45g共设备", "共设备", "45g", "codevice")),
    SYSTEM("制式", true, listOf("制式", "网络制式", "系统", "system", "networktype"));

    companion object {
        fun fromName(name: String?): StationField? = entries.firstOrNull { it.name == name }
    }
}

@Serializable
@Immutable
data class LabelStats(
    val total: Int = 0,
    val classified: Int = 0,
    val unclassified: Int = 0,
    val unicomShared: Int = 0,
    val telecomShared: Int = 0,
    val unicomPrivate: Int = 0,
    val telecomPrivate: Int = 0,
    val completedAt: Long = 0
)

enum class TaskType(val workName: String, val title: String) {
    IMPORT("cellmap_import", "XLSX 导入"),
    LABEL("cellmap_label", "分类标注"),
    OPTIMIZE("cellmap_optimize", "压缩优化");

    companion object {
        fun fromWorkName(name: String?): TaskType? = entries.firstOrNull { it.workName == name }
        fun fromTag(tags: Set<String>): TaskType? =
            entries.firstOrNull { tags.contains("type:${it.name.lowercase()}") }
    }
}

enum class TaskStatus(val label: String) {
    ENQUEUED("排队中"),
    RUNNING("运行中"),
    SUCCEEDED("已完成"),
    FAILED("失败"),
    CANCELLED("已取消"),
    BLOCKED("阻塞")
}

@Immutable
data class TaskItem(
    val workId: String,
    val type: TaskType,
    val status: TaskStatus,
    val percent: Int?,
    val message: String,
    val attempt: Int,
    val batchId: Long? = null,
    val failureReason: String? = null,
    val runnable: Boolean = false,
    val pausable: Boolean = false
) {
    val active: Boolean get() = status == TaskStatus.RUNNING || status == TaskStatus.ENQUEUED
}

enum class RiskLevel(val label: String) {
    HIGH("高"),
    MEDIUM("中"),
    LOW("低")
}

enum class RiskAction(val label: String) {
    OPEN_KEY("去配置 Key"),
    OPEN_IMPORT("去导入数据"),
    OPEN_LABELS("去完成标注"),
    OPEN_TASKS("查看任务"),
    OPEN_DATA("查看数据"),
    OPEN_MANAGE("打开管理"),
    REQUEST_BATTERY("申请白名单"),
    REPAIR_INDEX("修复索引")
}

@Immutable
data class RiskItem(
    val id: String,
    val level: RiskLevel,
    val title: String,
    val description: String,
    val impact: String,
    val suggestion: String,
    val action: RiskAction? = null
)

@Immutable
data class RiskInput(
    val readiness: ReadinessState,
    val totalKeys: Int,
    val verifiedKeys: Int,
    val maxDailyUsage: Int,
    val quotaPerKey: Int = 10_000,
    val totalStations: Int,
    val unclassifiedCount: Int,
    val labeledCount: Int,
    val optimizeReady: Boolean,
    val optimizedMarkerCount: Int,
    val optimizationAvailable: Boolean,
    val failedTasks: Int,
    val rtreeCount: Long,
    val stationCountForRtree: Long,
    val batteryWhitelisted: Boolean,
    val freeStorageBytes: Long
)

@Immutable
data class KeyUi(
    val id: String,
    val label: String,
    val masked: String,
    val verified: Boolean,
    val createdAt: Long
)

@Immutable
data class KeyHealth(
    val total: Int,
    val verified: Int,
    val maxDailyUsage: Int
)

@Immutable
data class ImportSummary(
    val batchId: Long,
    val fileName: String,
    val sheetName: String,
    val totalRows: Int,
    val successRows: Int,
    val duplicateRows: Int,
    val failedRows: Int,
    val failedReasons: Map<String, Int>,
    val importedAt: Long,
    val status: String
)

@Immutable
data class OptimizationSummary(
    val markerCount: Int,
    val clusterCount: Int,
    val chunkCount: Int,
    val rawBytes: Long,
    val optimizedBytes: Long,
    val finishedAt: Long
)
