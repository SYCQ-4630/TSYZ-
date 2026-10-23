package com.tsyz.autoledger.report

import com.tsyz.autoledger.core.model.ChannelState

data class CategoryTotal(val category: String, val amountCents: Long, val ratio: Double)

data class MerchantTotal(val merchant: String, val amountCents: Long, val count: Int)

data class MonthlyTrendPoint(
    val month: String,
    val incomeCents: Long,
    val expenseCents: Long,
    val balanceCents: Long
)

data class Anomaly(
    val category: String,
    val currentCents: Long,
    val baselineCents: Long,
    val increaseRatio: Double
)

data class DailyPoint(val day: String, val expenseCents: Long, val incomeCents: Long)

data class HealthLine(
    val label: String,
    val healthy: Boolean,
    val lastBeatAt: Long?,
    val state: ChannelState = ChannelState.OK,
    val detail: String = ""
)

data class ReportSummary(
    val month: String,
    val incomeCents: Long,
    val expenseCents: Long,
    val balanceCents: Long,
    val categoryTotals: List<CategoryTotal>,
    val daily: List<DailyPoint>,
    val topMerchants: List<MerchantTotal>,
    val monthlyTrend: List<MonthlyTrendPoint>,
    val momExpenseRatio: Double?,
    val yoyExpenseRatio: Double?,
    val anomalies: List<Anomaly>,
    val pendingCount: Int,
    val captureCount: Int,
    val autoPostedCount: Int,
    val health: List<HealthLine>
)
