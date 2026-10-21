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
        private val fiveGPatterns = listOf(
            Regex("""5G|NR|NR5G|5GNR"""),
            Regex("""(?<![A-Z0-9])N(?:1|3|5|8|28|41|78|79)(?![0-9])""")
        )
        private val fourGPatterns = listOf(
            Regex("""4G|LTE|FDD|TDD"""),
            Regex("""(?<![A-Z0-9])B(?:1|3|5|8|34|39|40|41)(?![0-9])""")
        )
        private val lteBand = Regex("""L(?:TE)?[\s_-]?(\d{3,4})""")
        private val nrBand = Regex("""NR[\s_-]?(\d{3,4})""")
        private val numericBand = Regex("""(\d+(?:\.\d+)?)\s*(GHZ|G|MHZ|M)""")

        /**
         * 兼容「4G/5G」写法，也兼容只有频率的台账（1.8G、2.1G、900M、3.5G、800M 等）。
         * 同一频率可能同时用于 4G/5G，优先使用显式制式；无法区分时按主流代际归类，
         * 调用方可用小区名称中的 4G/5G 字样覆盖该结果。
         */
        fun fromRaw(raw: String?): NetworkSystem? = explicit(raw) ?: fromBand(raw)

        fun explicit(raw: String?): NetworkSystem? {
            val value = normalize(raw) ?: return null
            return when {
                fiveGPatterns.any { it.containsMatchIn(value) } -> FIVE_G
                fourGPatterns.any { it.containsMatchIn(value) } -> FOUR_G
                else -> null
            }
        }

        fun isAmbiguousBand(raw: String?): Boolean {
            val value = normalize(raw) ?: return false
            val mhz = bandMhz(value) ?: return false
            return mhz in 790..960 || mhz in 1900..2200 || mhz in 2300..2400
        }

        fun fromBand(raw: String?): NetworkSystem? {
            val value = normalize(raw) ?: return null
            lteBand.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return FOUR_G }
            nrBand.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return FIVE_G }
            val mhz = bandMhz(value) ?: return null
            return when (mhz) {
                in 3300..3800 -> FIVE_G
                in 4800..5000 -> FIVE_G
                in 2500..2690 -> FIVE_G
                in 700..790 -> FIVE_G
                in 790..960 -> FOUR_G
                in 1700..1900 -> FOUR_G
                in 1900..2200 -> FOUR_G
                in 2300..2400 -> FOUR_G
                else -> null
            }
        }

        private fun bandMhz(value: String): Int? {
            numericBand.find(value)?.let { match ->
                val number = match.groupValues[1].toDoubleOrNull() ?: return null
                val unit = match.groupValues[2]
                return when (unit) {
                    "GHZ", "G" -> (number * 1000).toInt()
                    else -> number.toInt()
                }
            }
            val bare = value.toDoubleOrNull()
            if (bare != null) {
                return when {
                    bare in 0.6..6.0 -> (bare * 1000).toInt()
                    bare in 600.0..6000.0 -> bare.toInt()
                    else -> null
                }
            }
            return null
        }

        private fun normalize(raw: String?): String? =
            raw?.trim()?.uppercase()?.replace(" ", "")?.replace("　", "")
                ?.replace("／", "/")?.takeIf { it.isNotBlank() }
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

enum class FieldGroup(val label: String) {
    IDENTITY("标识与名称"),
    LOCATION("位置"),
    COVERAGE("覆盖与天线"),
    FREQUENCY("频率与带宽"),
    RADIO("小区参数与设备"),
    OPERATION("运营与共享")
}

enum class StationField(
    val displayName: String,
    val required: Boolean,
    val aliases: List<String>,
    val group: FieldGroup = FieldGroup.OPERATION
) {
    LATITUDE("纬度", true, listOf("纬度", "lat", "latitude"), FieldGroup.LOCATION),
    LONGITUDE("经度", true, listOf("经度", "lon", "lng", "longitude"), FieldGroup.LOCATION),
    BUILDER("承建方", true, listOf("承建方", "承建单位", "建设单位", "builder")),
    OPERATOR("运营商", false, listOf("运营商", "运营方", "operator")),
    DISTRICT("管理区县", false, listOf("管理区县", "区县", "行政区县", "district")),
    OWNER("网元归属方", false, listOf("网元归属方", "归属方", "网元归属", "owner")),
    STATION_ID("基站 ID", true, listOf("基站id", "基站编号", "基站标识", "stationid", "enodebid", "gnbid"), FieldGroup.IDENTITY),
    CELL_NAME("小区名称", false, listOf("小区名称", "小区名", "cellname"), FieldGroup.IDENTITY),
    CELL_ID("小区 ID", true, listOf("小区id", "小区编号", "小区标识", "cellid", "localcellid"), FieldGroup.IDENTITY),
    CATEGORY("小区覆盖类", false, listOf("小区覆盖类", "小区覆盖类别", "小区覆盖类型", "覆盖类别", "覆盖类型", "覆盖类", "category"), FieldGroup.COVERAGE),
    BAND("频段明细", false, listOf("频段", "频率", "频段号", "band", "frequency"), FieldGroup.FREQUENCY),
    SITE_NAME("基站名称", false, listOf("基站名称", "基站名", "站址名称", "站址", "站点名称", "sitename"), FieldGroup.IDENTITY),
    SHARED("是否共享", true, listOf("是否共享", "共享", "共享状态", "isshared", "shared")),
    ANTENNA_HEIGHT("天线挂高", false, listOf("天线挂高", "天线总挂高", "小区总挂高", "小区高度", "小区挂高", "挂高", "天线高度", "antennaheight"), FieldGroup.COVERAGE),
    CO_DEVICE_45G("45G 共设备", false, listOf("45g共设备", "共设备", "45g", "codevice"), FieldGroup.RADIO),
    BANDWIDTH("带宽", false, listOf("带宽", "小区带宽", "载波带宽", "bandwidth"), FieldGroup.FREQUENCY),
    UPLINK_FREQ("上行中心频点", false, listOf("上行中心频点", "上行频点", "上行中心频率", "上行频率", "uplinkfreq", "ulfreq", "upfreq"), FieldGroup.FREQUENCY),
    DOWNLINK_FREQ("下行中心频点", false, listOf("下行中心频点", "下行频点", "下行中心频率", "下行频率", "downlinkfreq", "dlfreq", "downfreq"), FieldGroup.FREQUENCY),
    DEPLOYMENT("基站部署方式", false, listOf("基站部署方式", "部署方式", "基站类型", "站点类型", "deployment", "deploymenttype"), FieldGroup.RADIO),
    AZIMUTH("小区方位角", false, listOf("小区方位角", "方位角", "天线方位角", "azimuth"), FieldGroup.COVERAGE),
    PCI("PCI", false, listOf("pci", "小区pci", "物理小区标识", "物理小区id"), FieldGroup.RADIO),
    MULTI_CARRIER("多载波标识", false, listOf("多载波标识", "多载波标志", "多载波", "载波标识", "multicarrier"), FieldGroup.FREQUENCY),
    ANTENNA_GAIN("天线增益", false, listOf("天线增益", "天线增益db", "antennagain"), FieldGroup.COVERAGE),
    AAU_COUNT("AAU 数量", false, listOf("aau数量", "aau数", "aau个数", "aaucount", "aauqty"), FieldGroup.RADIO),
    RRU_COUNT("RRU 数量", false, listOf("rru数量", "rru数", "rru个数", "rrucount", "rruqty"), FieldGroup.RADIO),
    SYSTEM("频段/制式", true, listOf("制式", "网络制式", "系统", "网络类型", "频段", "频率", "system", "networktype", "band", "frequency"), FieldGroup.FREQUENCY);

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
