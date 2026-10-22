package com.tsyz.autoledger.rules

import com.tsyz.autoledger.core.model.TxType
import org.json.JSONArray
import org.json.JSONObject

data class PaymentRule(
    val id: String,
    val packages: List<String>,
    val titleKeywords: List<String>,
    val bodyRegex: String?,
    val amountRegex: String?,
    val merchantRegex: String?,
    val incomeKeywords: List<String>,
    val expenseKeywords: List<String>,
    val category: String,
    val confidence: Double,
    val type: TxType,
    val priority: Int
) {
    companion object {
        fun fromJson(json: JSONObject): PaymentRule = PaymentRule(
            id = json.optString("id"),
            packages = json.optJSONArray("packages").toStringList(),
            titleKeywords = json.optJSONArray("titleKeywords").toStringList(),
            bodyRegex = json.optString("bodyRegex").takeIf { it.isNotBlank() },
            amountRegex = json.optString("amountRegex").takeIf { it.isNotBlank() },
            merchantRegex = json.optString("merchantRegex").takeIf { it.isNotBlank() },
            incomeKeywords = json.optJSONArray("incomeKeywords").toStringList(),
            expenseKeywords = json.optJSONArray("expenseKeywords").toStringList(),
            category = json.optString("category", "其他"),
            confidence = json.optDouble("confidence", 0.7).coerceIn(0.0, 1.0),
            type = TxType.fromName(json.optString("type")),
            priority = json.optInt("priority", 0)
        )

        fun fromJsonText(text: String): PaymentRule = fromJson(JSONObject(text))

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { index ->
                optString(index).takeIf { it.isNotBlank() }
            }
        }
    }
}

data class RuleSet(
    val version: Int,
    val rules: List<PaymentRule>
) {
    companion object {
        fun fromJson(text: String): RuleSet {
            val root = JSONObject(text)
            val array = root.optJSONArray("rules") ?: JSONArray()
            val rules = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { PaymentRule.fromJson(it) }
            }
            return RuleSet(root.optInt("version", 1), rules)
        }

        fun fromStoredRules(rows: List<Pair<Int, String>>): RuleSet {
            val parsed = rows.mapNotNull { (version, json) ->
                runCatching { version to PaymentRule.fromJsonText(json) }.getOrNull()
            }
            return RuleSet(
                version = parsed.maxOfOrNull { it.first } ?: 0,
                rules = parsed.map { it.second }
            )
        }
    }
}
