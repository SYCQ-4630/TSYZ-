package com.tsyz.autoledger.core.util

import com.tsyz.autoledger.core.model.ParsedRecord

/**
 * 日志脱敏。只允许记录包名、时间、规则命中与字段摘要，
 * 不记录通知正文、屏幕全文、完整金额与完整商户。
 */
object Sanitizer {
    private val currencyRegex = Regex("[¥￥]\\s?\\d[\\d,.]*")
    private val yuanRegex = Regex("\\d[\\d,]*\\.\\d{1,2}\\s*元")
    private val longDigitRegex = Regex("\\d{4,}")
    private val phoneRegex = Regex("1[3-9]\\d{9}")
    private val cardRegex = Regex("(尾号|卡号)\\s*\\d{3,}")

    fun sanitize(text: String, maxLength: Int = 500): String {
        var value = text
        value = currencyRegex.replace(value, "¥***")
        value = yuanRegex.replace(value, "***元")
        value = phoneRegex.replace(value, "1**********")
        value = cardRegex.replace(value) { "${it.groupValues[1]}****" }
        value = longDigitRegex.replace(value, "****")
        return value.take(maxLength)
    }

    fun merchantMask(merchant: String): String {
        if (merchant.isBlank()) return "*"
        val head = merchant.first()
        return "$head**(${Hashing.short(merchant, 6)})"
    }

    fun fieldSummary(record: ParsedRecord): String =
        "amount=¥*** merchant=${merchantMask(record.merchant)} type=${record.type.name} " +
            "category=${record.category} rule=${record.ruleId} confidence=${"%.2f".format(record.confidence)}"
}
