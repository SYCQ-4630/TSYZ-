package com.tsyz.autoledger.report

import com.tsyz.autoledger.core.model.ChannelStatus
import com.tsyz.autoledger.core.model.TxType
import com.tsyz.autoledger.core.util.TimeUtils
import com.tsyz.autoledger.data.db.TransactionEntity
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max

/**
 * 纯函数月报计算，便于单元测试。金额单位统一为分。
 */
object ReportMath {

    fun build(
        month: YearMonth,
        allTransactions: List<TransactionEntity>,
        pendingCount: Int,
        captureCount: Int,
        health: List<ChannelStatus> = emptyList()
    ): ReportSummary {
        val monthStart = TimeUtils.monthStart(month)
        val monthEnd = TimeUtils.monthEndExclusive(month)
        val current = allTransactions.filter { it.occurredAt in monthStart until monthEnd }
        val expenses = current.filter { it.type == TxType.EXPENSE.name }
        val incomes = current.filter { it.type == TxType.INCOME.name }
        val incomeCents = incomes.sumOf { it.amountCents }
        val expenseCents = expenses.sumOf { it.amountCents }

        val categoryTotals = expenses.groupBy { it.category }
            .map { (category, rows) ->
                val total = rows.sumOf { it.amountCents }
                CategoryTotal(
                    category = category,
                    amountCents = total,
                    ratio = if (expenseCents > 0) total.toDouble() / expenseCents else 0.0
                )
            }
            .sortedByDescending { it.amountCents }

        val daily = (1..month.lengthOfMonth()).map { day ->
            val start = month.atDay(day).atStartOfDay(TimeUtils.zone()).toInstant().toEpochMilli()
            val end = month.atDay(day).plusDays(1).atStartOfDay(TimeUtils.zone()).toInstant().toEpochMilli()
            val rows = current.filter { it.occurredAt in start until end }
            DailyPoint(
                day = "%02d".format(day),
                expenseCents = rows.filter { it.type == TxType.EXPENSE.name }.sumOf { it.amountCents },
                incomeCents = rows.filter { it.type == TxType.INCOME.name }.sumOf { it.amountCents }
            )
        }

        val topMerchants = expenses.groupBy { it.merchant }
            .map { (merchant, rows) ->
                MerchantTotal(merchant, rows.sumOf { it.amountCents }, rows.size)
            }
            .sortedByDescending { it.amountCents }
            .take(5)

        val trendMonths = (5 downTo 0).map { month.minusMonths(it.toLong()) }
        val monthlyTrend = trendMonths.map { trendMonth ->
            val start = TimeUtils.monthStart(trendMonth)
            val end = TimeUtils.monthEndExclusive(trendMonth)
            val rows = allTransactions.filter { it.occurredAt in start until end }
            val income = rows.filter { it.type == TxType.INCOME.name }.sumOf { it.amountCents }
            val expense = rows.filter { it.type == TxType.EXPENSE.name }.sumOf { it.amountCents }
            MonthlyTrendPoint(trendMonth.toString(), income, expense, income - expense)
        }

        val previousMonth = month.minusMonths(1)
        val previousExpense = sumExpense(allTransactions, previousMonth)
        val lastYearExpense = sumExpense(allTransactions, month.minusYears(1))

        val anomalies = detectAnomalies(month, allTransactions, expenses, expenseCents)

        return ReportSummary(
            month = month.toString(),
            incomeCents = incomeCents,
            expenseCents = expenseCents,
            balanceCents = incomeCents - expenseCents,
            categoryTotals = categoryTotals,
            daily = daily,
            topMerchants = topMerchants,
            monthlyTrend = monthlyTrend,
            momExpenseRatio = ratio(expenseCents, previousExpense),
            yoyExpenseRatio = ratio(expenseCents, lastYearExpense),
            anomalies = anomalies,
            pendingCount = pendingCount,
            captureCount = captureCount,
            autoPostedCount = current.count { it.sourceType != "MANUAL" },
            health = health.map { HealthLine(it.label, it.healthy, it.lastBeatAt, it.state, it.detail) }
        )
    }

    private fun detectAnomalies(
        month: YearMonth,
        all: List<TransactionEntity>,
        currentExpenses: List<TransactionEntity>,
        totalExpense: Long
    ): List<Anomaly> {
        if (totalExpense <= 0L) return emptyList()
        val currentByCategory = currentExpenses.groupBy { it.category }.mapValues { entry ->
            entry.value.sumOf { it.amountCents }
        }
        val baselines = currentByCategory.keys.map { category ->
            val amounts = (1..3).map { offset ->
                val m = month.minusMonths(offset.toLong())
                all.filter {
                    it.occurredAt in TimeUtils.monthStart(m) until TimeUtils.monthEndExclusive(m) &&
                        it.type == TxType.EXPENSE.name && it.category == category
                }.sumOf { it.amountCents }
            }
            category to (amounts.sum() / 3)
        }.toMap()

        return currentByCategory.mapNotNull { (category, current) ->
            val baseline = baselines[category] ?: 0L
            val share = current.toDouble() / totalExpense
            val threshold = max(MIN_BASELINE_CENTS, (baseline * ANOMALY_MULTIPLIER).toLong())
            if (current > threshold && share >= MIN_SHARE) {
                val increase = if (baseline > 0) (current - baseline).toDouble() / baseline else 0.0
                Anomaly(category, current, baseline, increase)
            } else {
                null
            }
        }.sortedByDescending { it.increaseRatio }
    }

    private fun sumExpense(all: List<TransactionEntity>, month: YearMonth): Long =
        all.filter {
            it.occurredAt in TimeUtils.monthStart(month) until TimeUtils.monthEndExclusive(month) &&
                it.type == TxType.EXPENSE.name
        }.sumOf { it.amountCents }

    private fun ratio(current: Long, baseline: Long): Double? =
        if (baseline <= 0L) null else (current - baseline).toDouble() / baseline

    fun percent(ratio: Double?): String {
        if (ratio == null) return "—"
        val value = ratio * 100
        val sign = if (value >= 0) "+" else ""
        return "$sign${"%.1f".format(value)}%"
    }

    fun absPercent(ratio: Double?): String = if (ratio == null) "—" else "${"%.1f".format(abs(ratio) * 100)}%"

    fun topCategoryRatio(expense: Long, category: Long): Double =
        if (expense <= 0) 0.0 else category.toDouble() / expense

    private const val MIN_BASELINE_CENTS = 50_000L
    private const val ANOMALY_MULTIPLIER = 2.0
    private const val MIN_SHARE = 0.15
}
