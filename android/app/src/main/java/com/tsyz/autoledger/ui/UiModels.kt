package com.tsyz.autoledger.ui

import com.tsyz.autoledger.core.model.TxType
import com.tsyz.autoledger.core.util.TimeUtils
import com.tsyz.autoledger.report.Anomaly
import com.tsyz.autoledger.report.CategoryTotal
import com.tsyz.autoledger.report.DailyPoint
import com.tsyz.autoledger.report.HealthLine
import com.tsyz.autoledger.report.MerchantTotal
import com.tsyz.autoledger.report.MonthlyTrendPoint

enum class ReportRange(val label: String) {
    THIS_MONTH("本月"),
    LAST_MONTH("上月"),
    LAST_3_MONTHS("近3月"),
    THIS_YEAR("今年"),
    ALL("全部");

    companion object {
        fun from(value: String?): ReportRange = entries.firstOrNull { it.name == value } ?: THIS_MONTH
    }
}

enum class TypeFilter(val label: String) {
    ALL("全部"),
    INCOME("收入"),
    EXPENSE("支出");

    companion object {
        fun from(value: String?): TypeFilter = entries.firstOrNull { it.name == value } ?: ALL
    }
}

enum class ChartMode(val label: String) {
    BAR("柱状"),
    PIE("占比"),
    LINE("趋势");

    companion object {
        fun from(value: String?): ChartMode = entries.firstOrNull { it.name == value } ?: BAR
    }
}

data class FiltersState(
    val range: ReportRange = ReportRange.THIS_MONTH,
    val category: String? = null,
    val type: TypeFilter = TypeFilter.ALL
)

data class NoteDraftState(
    val txId: Long,
    val note: String = "",
    val category: String = "其他"
)

data class ManualFormState(
    val amount: String = "",
    val merchant: String = "",
    val category: String = "其他",
    val type: TxType = TxType.EXPENSE,
    val date: String = TimeUtils.formatDate(System.currentTimeMillis()),
    val note: String = ""
)

data class ReportUiState(
    val rangeLabel: String,
    val monthLabel: String,
    val incomeCents: Long,
    val expenseCents: Long,
    val balanceCents: Long,
    val categories: List<CategoryTotal>,
    val daily: List<DailyPoint>,
    val topMerchants: List<MerchantTotal>,
    val trend: List<MonthlyTrendPoint>,
    val momRatio: Double?,
    val yoyRatio: Double?,
    val anomalies: List<Anomaly>,
    val pendingCount: Int,
    val captureCount: Int,
    val autoPostedCount: Int,
    val health: List<HealthLine>
)

data class BackupInfo(
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long
)

data class RuleInfo(
    val version: Int,
    val count: Int
)
