package com.selfuse.cellmap.core.util

import com.selfuse.cellmap.core.model.BuilderType
import com.selfuse.cellmap.core.model.MarkerStyle
import com.selfuse.cellmap.core.model.NetworkSystem
import com.selfuse.cellmap.core.model.OperatorType
import com.selfuse.cellmap.core.model.SharedState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Normalizer {

    fun builder(raw: String?): BuilderType {
        val value = normalizeToken(raw)
        return when {
            value.contains("联通") || value.contains("unicom") || value.contains("cucc") -> BuilderType.UNICOM
            value.contains("电信") || value.contains("telecom") || value.contains("ctcc") -> BuilderType.TELECOM
            else -> BuilderType.OTHER
        }
    }

    fun operator(raw: String?): OperatorType {
        val value = normalizeToken(raw)
        return when {
            value.contains("联通") || value.contains("unicom") || value.contains("cucc") -> OperatorType.UNICOM
            value.contains("电信") || value.contains("telecom") || value.contains("ctcc") -> OperatorType.TELECOM
            value.contains("移动") || value.contains("mobile") || value.contains("cmcc") -> OperatorType.MOBILE
            else -> OperatorType.OTHER
        }
    }

    fun shared(raw: String?): SharedState {
        val value = normalizeToken(raw)
        if (value.isBlank()) return SharedState.UNKNOWN
        val positive = listOf("已共享", "共享", "是", "yes", "true", "1", "y", "shared")
        val negative = listOf("未共享", "不共享", "否", "no", "false", "0", "n", "private", "独享")
        return when {
            negative.any { value == it || value.contains(it) } -> SharedState.PRIVATE
            positive.any { value == it || value.contains(it) } -> SharedState.SHARED
            else -> SharedState.UNKNOWN
        }
    }

    fun system(raw: String?): NetworkSystem? = NetworkSystem.fromRaw(raw)

    fun markerStyle(builderRaw: String?, sharedRaw: String?): MarkerStyle? =
        markerStyle(builder(builderRaw), shared(sharedRaw))

    fun markerStyle(builder: BuilderType, shared: SharedState): MarkerStyle? = when {
        builder == BuilderType.UNICOM && shared == SharedState.SHARED -> MarkerStyle.UNICOM_SHARED
        builder == BuilderType.TELECOM && shared == SharedState.SHARED -> MarkerStyle.TELECOM_SHARED
        builder == BuilderType.UNICOM && shared == SharedState.PRIVATE -> MarkerStyle.UNICOM_PRIVATE
        builder == BuilderType.TELECOM && shared == SharedState.PRIVATE -> MarkerStyle.TELECOM_PRIVATE
        else -> null
    }

    fun normalizeToken(raw: String?): String =
        raw.orEmpty().trim().lowercase(Locale.ROOT).replace(" ", "").replace("　", "")

    fun normalizeHeader(raw: String?): String = normalizeToken(raw)
        .replace("(", "").replace(")", "")
        .replace("（", "").replace("）", "")
        .replace("-", "").replace("_", "")
        .replace("/", "")
}

/**
 * XLSX 中的中文没有天然分词。为 FTS4(unicode61) 生成汉字 bigram 索引串，
 * 查询时同样切分，可支持“小区名称”中间片段的模糊匹配，而无需引入分词库。
 */
object SearchText {

    fun shingles(raw: String?): List<String> {
        val text = raw.orEmpty().trim().lowercase(Locale.ROOT)
        if (text.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()
        val cjkRun = StringBuilder()
        val latinRun = StringBuilder()

        fun flushCjk() {
            if (cjkRun.isEmpty()) return
            if (cjkRun.length == 1) {
                result.add(cjkRun.toString())
            } else {
                for (i in 0 until cjkRun.length - 1) {
                    result.add(cjkRun.substring(i, i + 2))
                }
            }
            cjkRun.clear()
        }

        fun flushLatin() {
            if (latinRun.isNotEmpty()) {
                result.add(latinRun.toString())
                latinRun.clear()
            }
        }

        for (ch in text) {
            when {
                ch.isCjk() -> {
                    flushLatin()
                    cjkRun.append(ch)
                }
                ch.isLetterOrDigit() -> {
                    flushCjk()
                    latinRun.append(ch)
                }
                else -> {
                    flushCjk()
                    flushLatin()
                }
            }
        }
        flushCjk()
        flushLatin()
        return result.toList()
    }

    fun searchBlob(vararg values: String?): String =
        values.flatMap { shingles(it) }.distinct().joinToString(" ")

    fun ftsQuery(raw: String?): String? {
        val tokens = shingles(raw)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" OR ") { token ->
            "\"" + token.replace("\"", "") + "\""
        }
    }

    private fun Char.isCjk(): Boolean {
        val code = code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF
    }
}

object Formats {
    fun dateTime(timestamp: Long): String {
        if (timestamp <= 0L) return "—"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    fun date(timestamp: Long): String {
        if (timestamp <= 0L) return "—"
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
    }

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun number(value: Long): String = String.format(Locale.US, "%,d", value)
    fun number(value: Int): String = number(value.toLong())
    fun percent(value: Int?): String = if (value == null) "进行中" else "$value%"
    fun todayKey(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(System.currentTimeMillis()))
}
