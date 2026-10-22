package com.tsyz.autoledger.rules

import com.tsyz.autoledger.core.model.RawCapture
import com.tsyz.autoledger.core.model.SourceType
import com.tsyz.autoledger.core.model.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private val rulesJson = """
        {
          "version": 2,
          "rules": [
            {
              "id": "wechat_pay",
              "packages": ["com.tencent.mm"],
              "titleKeywords": ["支付成功"],
              "amountRegex": "(?:¥|￥)\\s?([0-9,]+(?:\\.[0-9]{1,2})?)",
              "merchantRegex": "收款方[:：]\\s*([^\\n]+)",
              "type": "EXPENSE",
              "category": "其他",
              "confidence": 0.9,
              "priority": 10
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses matching payment screen`() {
        val engine = RuleEngine(RuleSet.fromJson(rulesJson))
        val capture = RawCapture(
            packageName = "com.tencent.mm",
            rawText = "支付成功\n收款方：星巴克咖啡\n¥32.50",
            timestamp = 1_700_000_000_000L,
            sourceType = SourceType.ACCESSIBILITY
        )

        val record = engine.parse(capture)

        assertNotNull(record)
        assertEquals(3250L, record!!.amountCents)
        assertEquals(TxType.EXPENSE, record.type)
        assertEquals("星巴克咖啡", record.merchant)
        assertEquals("餐饮", record.category)
        assertEquals(0.9, record.confidence, 0.0001)
        assertEquals("wechat_pay", record.ruleId)
        assertEquals(2, record.ruleVersion)
    }

    @Test
    fun `ignores non target package`() {
        val engine = RuleEngine(RuleSet.fromJson(rulesJson))
        val capture = RawCapture(
            packageName = "com.example.other",
            rawText = "支付成功 ¥32.50",
            timestamp = 1_700_000_000_000L,
            sourceType = SourceType.ACCESSIBILITY
        )
        assertNull(engine.parse(capture))
    }

    @Test
    fun `reports accessibility packages without wildcard and special sources`() {
        val engine = RuleEngine(RuleSet.fromJson(rulesJson))
        assertEquals(setOf("com.tencent.mm"), engine.accessibilityPackages())
        assertTrue(engine.supportsPackage("com.tencent.mm"))
    }
}
