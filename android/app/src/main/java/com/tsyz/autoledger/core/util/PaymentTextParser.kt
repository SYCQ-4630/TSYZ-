package com.tsyz.autoledger.core.util

/**
 * 纯文本语义解析：不依赖节点层级、控件 id 或固定坐标，
 * 因而横竖屏切换、ROM 改版都不会直接失效。
 */
object PaymentTextParser {
    private const val MAX_AMOUNT_CENTS = 100_000_000_00L

    private val successKeywords = listOf(
        "支付成功", "付款成功", "交易成功", "已支付", "收款到账",
        "收款成功", "转账成功", "到账", "消费", "扣款", "转出", "取现"
    )
    private val failureKeywords = listOf("支付失败", "付款失败", "交易失败", "已取消", "未成功", "余额不足")
    private val balanceKeywords = listOf("余额", "可用", "额度", "剩余", "积分", "尾号", "卡号", "手续费", "时间", "日期")
    private val amountContextKeywords = listOf("￥", "¥", "元", "支付", "付款", "转账", "消费", "金额", "收入", "支出", "扣款", "到账")
    private val merchantLabelRegex = Regex(
        "(?:收款方|付款给|转账给|商户|商家|收款人|对方户名|付款方|户名|摘要|用途)[:：\\s]*([^\\n，,。;；]{1,32})"
    )
    private val defaultAmountRegex = Regex(
        "(?:￥|¥|人民币|金额|收入|支出|支付|付款|转账|消费|扣款|到账)?\\s*" +
            "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:元|￥|¥|人民币)?"
    )

    fun extractAmountCents(text: String, amountRegex: String? = null): Long? {
        val regex = amountRegex?.let {
            runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
        } ?: defaultAmountRegex
        for (match in regex.findAll(text)) {
            val line = lineAt(text, match.range.first)
            if (balanceKeywords.any { line.contains(it) }) continue
            if (amountContextKeywords.none { line.contains(it) }) continue
            val group = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
            val cents = Money.parseToCents(group) ?: continue
            if (cents in 1..MAX_AMOUNT_CENTS) return cents
        }
        return null
    }

    fun extractMerchant(text: String, merchantRegex: String? = null): String? {
        val regex = merchantRegex?.let {
            runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
        } ?: merchantLabelRegex
        val match = regex.find(text) ?: return null
        val candidate = cleanupMerchant(match.groupValues.getOrNull(1).orEmpty())
        return candidate
    }

    /** 比 isSuspectedPaymentResult 更宽松，用于决定是否需要继续走后续抓取层。 */
    fun looksLikePayment(text: String): Boolean {
        if (text.isBlank()) return false
        val hasKeyword = listOf(
            "支付成功", "付款成功", "交易成功", "已支付", "转账", "收款", "到账",
            "消费", "扣款", "订单金额", "付款金额", "¥", "￥"
        ).any { text.contains(it) }
        val hasAmount = Regex("[¥￥]\\s?\\d|\\d[\\d,]*\\.\\d{1,2}\\s*元|金额").containsMatchIn(text)
        return hasKeyword && hasAmount
    }

    fun isSuspectedPaymentResult(text: String): Boolean {
        if (text.isBlank()) return false
        val hasSuccess = successKeywords.any { text.contains(it) }
        if (!hasSuccess) return false
        val hasFailure = failureKeywords.any { text.contains(it) }
        if (hasFailure && successKeywords.none { text.contains(it) && it != "消费" }) return false
        val hasAmount = extractAmountCents(text) != null ||
            Regex("[¥￥]\\s?\\d[\\d,.]*|\\d[\\d,]*\\.\\d{1,2}\\s*元").containsMatchIn(text)
        return hasAmount
    }

    fun titleOf(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(48).orEmpty()

    private fun cleanupMerchant(raw: String): String? {
        var value = raw.trim()
            .trim(':', '：', '-', '—', ' ', '\t')
            .removeSuffix("的")
            .trim()
        value = value.substringBefore("（").substringBefore("(").trim()
        if (value.length < 1 || value.length > 32) return null
        if (value.all { it.isDigit() || it == '*' || it == '.' }) return null
        return value
    }

    private fun lineAt(text: String, index: Int): String {
        val start = text.lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }
}
