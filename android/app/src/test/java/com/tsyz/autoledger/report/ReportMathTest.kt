package com.tsyz.autoledger.report

import com.tsyz.autoledger.core.model.TxType
import com.tsyz.autoledger.core.util.TimeUtils
import com.tsyz.autoledger.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class ReportMathTest {

    private val month = YearMonth.of(2026, 5)

    private fun tx(
        amountCents: Long,
        type: TxType,
        merchant: String,
        category: String,
        occurredAt: Long,
        source: String = "ACCESSIBILITY"
    ) = TransactionEntity(
        amountCents = amountCents,
        type = type.name,
        merchant = merchant,
        category = category,
        occurredAt = occurredAt,
        sourceType = source,
        confidence = 0.95,
        dedupeKey = "$amountCents-$type-$occurredAt",
        fingerprint = "fp-$amountCents-$occurredAt",
        ruleId = "test"
    )

    @Test
    fun `aggregates income expense categories and merchants`() {
        val start = TimeUtils.monthStart(month)
        val rows = listOf(
            tx(100_000L, TxType.INCOME, "工资", "工资", start + 1_000L, "MANUAL"),
            tx(20_000L, TxType.EXPENSE, "星巴克", "餐饮", start + 2_000L),
            tx(10_000L, TxType.EXPENSE, "地铁", "交通", start + 3_000L),
            tx(5_000L, TxType.EXPENSE, "星巴克", "餐饮", start + 4_000L)
        )

        val summary = ReportMath.build(month, rows, pendingCount = 2, captureCount = 9)

        assertEquals(100_000L, summary.incomeCents)
        assertEquals(35_000L, summary.expenseCents)
        assertEquals(65_000L, summary.balanceCents)
        assertEquals("餐饮", summary.categoryTotals.first().category)
        assertEquals(25_000L, summary.categoryTotals.first().amountCents)
        assertEquals("星巴克", summary.topMerchants.first().merchant)
        assertEquals(2, summary.topMerchants.first().count)
        assertEquals(3, summary.autoPostedCount)
        assertEquals(2, summary.pendingCount)
        assertEquals(9, summary.captureCount)
        assertEquals(6, summary.monthlyTrend.size)
        assertEquals(31, summary.daily.size)
        assertTrue(summary.anomalies.isEmpty())
        assertNull(summary.momExpenseRatio)
    }

    @Test
    fun `computes month over month ratio from previous month`() {
        val start = TimeUtils.monthStart(month)
        val previous = TimeUtils.monthStart(month.minusMonths(1))
        val rows = listOf(
            tx(30_000L, TxType.EXPENSE, "餐厅", "餐饮", start + 1_000L),
            tx(15_000L, TxType.EXPENSE, "餐厅", "餐饮", previous + 1_000L)
        )

        val summary = ReportMath.build(month, rows, pendingCount = 0, captureCount = 0)

        assertEquals(1.0, summary.momExpenseRatio!!, 0.0001)
        assertEquals("+100.0%", ReportMath.percent(summary.momExpenseRatio))
        assertEquals("100.0%", ReportMath.absPercent(summary.momExpenseRatio))
    }

    @Test
    fun `detects category anomaly against three month baseline`() {
        val start = TimeUtils.monthStart(month)
        val rows = mutableListOf<TransactionEntity>()
        for (offset in 1..3) {
            val baselineMonth = TimeUtils.monthStart(month.minusMonths(offset.toLong()))
            rows += tx(60_000L, TxType.EXPENSE, "医院", "医疗", baselineMonth + 1_000L)
        }
        rows += tx(300_000L, TxType.EXPENSE, "医院", "医疗", start + 1_000L)
        rows += tx(10_000L, TxType.EXPENSE, "地铁", "交通", start + 2_000L)

        val summary = ReportMath.build(month, rows, pendingCount = 0, captureCount = 0)

        val anomaly = summary.anomalies.firstOrNull { it.category == "医疗" }
        assertTrue(anomaly != null)
        assertEquals(300_000L, anomaly!!.currentCents)
        assertEquals(60_000L, anomaly.baselineCents)
        assertEquals(4.0, anomaly.increaseRatio, 0.0001)
    }

    @Test
    fun `top category ratio handles empty expense`() {
        assertEquals(0.0, ReportMath.topCategoryRatio(0L, 100L), 0.0001)
    }
}
