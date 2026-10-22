package com.tsyz.autoledger.core.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat

object Money {
    private val formatter = DecimalFormat("#,##0.00")

    fun format(cents: Long): String = formatter.format(cents / 100.0)

    fun formatWithSymbol(cents: Long): String = "¥${format(cents)}"

    /** 解析 "1,234.56" / "¥12.3" / "12.3元" 为分。非法值返回 null。 */
    fun parseToCents(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw
            .replace(",", "")
            .replace("，", "")
            .replace("¥", "")
            .replace("￥", "")
            .replace("元", "")
            .replace("人民币", "")
            .trim()
        val match = Regex("-?\\d+(?:\\.\\d{1,2})?").find(cleaned) ?: return null
        return try {
            val value = BigDecimal(match.value).setScale(2, RoundingMode.HALF_UP)
            if (value <= BigDecimal.ZERO) null else value.movePointRight(2).longValueExact()
        } catch (_: Exception) {
            null
        }
    }
}
