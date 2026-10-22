package com.tsyz.autoledger.snapshot

import org.json.JSONArray
import org.json.JSONObject

object SnapshotCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L

    fun encode(snapshot: UiSnapshot): String {
        val root = JSONObject()
        root.put("version", SCHEMA_VERSION)
        root.put("tabIndex", snapshot.tabIndex)
        root.put("range", snapshot.range)
        root.put("category", snapshot.category)
        root.put("typeFilter", snapshot.typeFilter)
        root.put("chartType", snapshot.chartType)
        root.put("transactionScroll", snapshot.transactionScroll)
        root.put("reportScroll", snapshot.reportScroll)
        root.put("trendScroll", snapshot.trendScroll)
        root.put("bulkMode", snapshot.bulkMode)
        root.put("noteEditingTxId", snapshot.noteEditingTxId)
        root.put("savedAt", snapshot.savedAt)
        root.put("screenStack", JSONArray(snapshot.screenStack))
        root.put("bulkSelection", JSONArray(snapshot.bulkSelection))
        return root.toString()
    }

    /** 反序列化失败、版本不匹配时返回 null，由调用方回默认首页。 */
    fun decode(json: String, storedVersion: Int = SCHEMA_VERSION): UiSnapshot? {
        return try {
            val root = JSONObject(json)
            val version = root.optInt("version", 0)
            if (version != SCHEMA_VERSION || storedVersion != SCHEMA_VERSION) return null
            val stackArray = root.optJSONArray("screenStack") ?: JSONArray()
            val selectionArray = root.optJSONArray("bulkSelection") ?: JSONArray()
            val stack = (0 until stackArray.length()).mapNotNull { stackArray.optString(it).takeIf(String::isNotBlank) }
            val selection = (0 until selectionArray.length()).mapNotNull {
                val value = selectionArray.optLong(it, -1L)
                value.takeIf { it > 0 }
            }
            UiSnapshot(
                tabIndex = root.optInt("tabIndex", 0).coerceIn(0, 3),
                screenStack = stack,
                range = root.optString("range", "THIS_MONTH"),
                category = root.optString("category").takeIf { it.isNotBlank() && it != "null" },
                typeFilter = root.optString("typeFilter", "ALL"),
                chartType = root.optString("chartType", "BAR"),
                transactionScroll = root.optInt("transactionScroll", 0).coerceAtLeast(0),
                reportScroll = root.optInt("reportScroll", 0).coerceAtLeast(0),
                trendScroll = root.optInt("trendScroll", 0).coerceAtLeast(0),
                bulkMode = root.optBoolean("bulkMode", false),
                bulkSelection = selection,
                noteEditingTxId = root.optLong("noteEditingTxId", -1L).takeIf { it > 0 },
                savedAt = root.optLong("savedAt", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }
}
