package com.tsyz.autoledger.rules

import com.tsyz.autoledger.core.model.ParsedRecord
import com.tsyz.autoledger.core.model.RawCapture
import com.tsyz.autoledger.core.model.TxType
import com.tsyz.autoledger.core.util.CategoryClassifier
import com.tsyz.autoledger.core.util.PaymentTextParser
import com.tsyz.autoledger.core.util.Sanitizer

class RuleEngine(private val ruleSet: RuleSet) {

    val version: Int get() = ruleSet.version

    fun parse(capture: RawCapture): ParsedRecord? {
        val text = capture.rawText.take(20_000)
        if (text.isBlank()) return null

        val candidates = ruleSet.rules
            .filter { packageMatches(it.packages, capture.packageName) }
            .sortedByDescending { it.priority }

        for (rule in candidates) {
            val keywordHit = rule.titleKeywords.any { text.contains(it, ignoreCase = true) }
            val bodyHit = rule.bodyRegex?.let { regexHit(it, text) } ?: false
            if (!keywordHit && !bodyHit) continue

            val amountCents = PaymentTextParser.extractAmountCents(text, rule.amountRegex) ?: continue
            val merchantLabel = PaymentTextParser.extractMerchant(text, rule.merchantRegex)
            val merchant = merchantLabel ?: defaultMerchant(capture.packageName)
            val type = resolveType(rule, text)
            val category = CategoryClassifier.classify(merchant, text, rule.category)

            var confidence = rule.confidence
            if (merchantLabel == null) confidence -= 0.06
            if (!keywordHit) confidence -= 0.04
            confidence = confidence.coerceIn(0.0, 1.0)

            val record = ParsedRecord(
                amountCents = amountCents,
                type = type,
                merchant = merchant,
                category = category,
                occurredAt = capture.timestamp,
                confidence = confidence,
                ruleId = rule.id,
                ruleVersion = ruleSet.version,
                title = PaymentTextParser.titleOf(text),
                fieldSummary = ""
            )
            return record.copy(fieldSummary = Sanitizer.fieldSummary(record))
        }
        return null
    }

    private fun resolveType(rule: PaymentRule, text: String): TxType {
        val income = rule.incomeKeywords.any { text.contains(it, ignoreCase = true) }
        val expense = rule.expenseKeywords.any { text.contains(it, ignoreCase = true) }
        return when {
            income && !expense -> TxType.INCOME
            expense && !income -> TxType.EXPENSE
            else -> rule.type
        }
    }

    /** 供无障碍服务做包名过滤：规则中出现的目标包名，排除 sms/import/manual。 */
    fun accessibilityPackages(): Set<String> =
        ruleSet.rules.flatMap { it.packages }
            .filter { it.isNotBlank() && it != "*" && it != "sms" && it != "import" && it != "manual" }
            .toSet()

    fun supportsPackage(packageName: String): Boolean =
        ruleSet.rules.any { packageMatches(it.packages, packageName) }

    private fun packageMatches(patterns: List<String>, packageName: String): Boolean {
        if (patterns.isEmpty()) return false
        return patterns.any { pattern ->
            pattern == "*" || pattern == packageName ||
                (pattern.endsWith("*") && packageName.startsWith(pattern.dropLast(1)))
        }
    }

    private fun regexHit(pattern: String, text: String): Boolean =
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text) }.getOrDefault(false)

    private fun defaultMerchant(packageName: String): String = when {
        packageName == "sms" -> "银行短信"
        packageName == "import" -> "账单导入"
        packageName == "manual" -> "手动记录"
        packageName == "com.tencent.mm" -> "微信支付"
        packageName == "com.eg.android.AlipayGphone" -> "支付宝"
        packageName == "com.unionpay" -> "云闪付"
        packageName.contains("icbc") -> "工商银行"
        packageName.contains("chinamworld") -> "建设银行"
        packageName.contains("bankabc") -> "农业银行"
        packageName.contains("cmb") -> "招商银行"
        packageName.contains("bankcomm") -> "交通银行"
        packageName.contains("spdb") -> "浦发银行"
        packageName.contains("citic") -> "中信银行"
        else -> "未知商户"
    }
}
