package com.tsyz.autoledger.core.model

import com.tsyz.autoledger.core.util.Fingerprint

/** 输入通道类型。channel 用于跨通道去重时的来源分组与健康统计。 */
enum class SourceType(val label: String, val channel: String) {
    ACCESSIBILITY("无障碍", "payment"),
    SMS("短信", "sms"),
    NOTIFICATION("通知", "payment"),
    OCR("截图OCR", "payment"),
    BILL_IMPORT("账单导入", "import"),
    MANUAL("手动", "manual");

    companion object {
        fun fromName(value: String?): SourceType =
            entries.firstOrNull { it.name == value } ?: ACCESSIBILITY
    }
}

enum class TxType(val label: String) {
    INCOME("收入"),
    EXPENSE("支出");

    companion object {
        fun fromName(value: String?): TxType = if (value == INCOME.name) INCOME else EXPENSE
    }
}

enum class ConfidenceBand { HIGH, MEDIUM, LOW }

/** 所有输入通道统一输出的标准捕获对象。 */
data class RawCapture(
    val packageName: String,
    val rawText: String,
    val timestamp: Long,
    val sourceType: SourceType,
    val fingerprint: String = Fingerprint.ofRaw(sourceType, packageName, rawText, timestamp),
    val extra: Map<String, String> = emptyMap()
)

/**
 * 输入通道统一接口。新增支付 App 时实现该接口并补充规则 JSON 即可，
 * 解析、去重、入账流程无需修改。
 */
interface CaptureSource {
    val id: String
    val displayName: String
    val sourceType: SourceType
    fun isConfigured(): Boolean
}

data class ParsedRecord(
    val amountCents: Long,
    val type: TxType,
    val merchant: String,
    val category: String,
    val occurredAt: Long,
    val confidence: Double,
    val ruleId: String,
    val ruleVersion: Int,
    val title: String,
    val note: String? = null,
    val fieldSummary: String
) {
    val band: ConfidenceBand
        get() = when {
            confidence >= 0.80 -> ConfidenceBand.HIGH
            confidence >= 0.55 -> ConfidenceBand.MEDIUM
            else -> ConfidenceBand.LOW
        }
}

enum class IngestStatus { POSTED, PENDING, DUPLICATE, FAILED }

data class IngestResult(
    val status: IngestStatus,
    val transactionId: Long? = null,
    val pendingId: Long? = null,
    val message: String = ""
)

data class ChannelStatus(
    val channel: String,
    val label: String,
    val lastBeatAt: Long?,
    val healthy: Boolean,
    val detail: String = ""
)
