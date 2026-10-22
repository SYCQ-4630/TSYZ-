package com.tsyz.autoledger.report

import org.json.JSONArray
import org.json.JSONObject

object ReportCodec {
    fun encode(report: ReportSummary): String {
        val root = JSONObject()
        root.put("month", report.month)
        root.put("incomeCents", report.incomeCents)
        root.put("expenseCents", report.expenseCents)
        root.put("balanceCents", report.balanceCents)
        root.put("pendingCount", report.pendingCount)
        root.put("captureCount", report.captureCount)
        root.put("autoPostedCount", report.autoPostedCount)
        root.put("momExpenseRatio", report.momExpenseRatio ?: JSONObject.NULL)
        root.put("yoyExpenseRatio", report.yoyExpenseRatio ?: JSONObject.NULL)
        root.put("categories", JSONArray(report.categoryTotals.map {
            JSONObject().put("category", it.category).put("amountCents", it.amountCents).put("ratio", it.ratio)
        }))
        root.put("daily", JSONArray(report.daily.map {
            JSONObject().put("day", it.day).put("expenseCents", it.expenseCents).put("incomeCents", it.incomeCents)
        }))
        root.put("merchants", JSONArray(report.topMerchants.map {
            JSONObject().put("merchant", it.merchant).put("amountCents", it.amountCents).put("count", it.count)
        }))
        root.put("trend", JSONArray(report.monthlyTrend.map {
            JSONObject().put("month", it.month).put("incomeCents", it.incomeCents)
                .put("expenseCents", it.expenseCents).put("balanceCents", it.balanceCents)
        }))
        root.put("anomalies", JSONArray(report.anomalies.map {
            JSONObject().put("category", it.category).put("currentCents", it.currentCents)
                .put("baselineCents", it.baselineCents).put("increaseRatio", it.increaseRatio)
        }))
        root.put("health", JSONArray(report.health.map {
            JSONObject().put("label", it.label).put("healthy", it.healthy)
                .put("lastBeatAt", it.lastBeatAt ?: JSONObject.NULL)
        }))
        return root.toString()
    }
}
