package com.tsyz.autoledger.capture.importer

import android.content.Context
import android.net.Uri
import com.tsyz.autoledger.core.log.SafeLogger
import com.tsyz.autoledger.core.model.IngestStatus
import com.tsyz.autoledger.core.model.ParsedRecord
import com.tsyz.autoledger.core.model.RawCapture
import com.tsyz.autoledger.core.model.SourceType
import com.tsyz.autoledger.core.model.TxType
import com.tsyz.autoledger.core.util.CategoryClassifier
import com.tsyz.autoledger.core.util.Money
import com.tsyz.autoledger.core.util.Sanitizer
import com.tsyz.autoledger.core.util.TimeUtils
import com.tsyz.autoledger.decision.IngestionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

data class ImportResult(
    val total: Int,
    val posted: Int,
    val pending: Int,
    val duplicate: Int,
    val failed: Int
)

/**
 * 账单文件导入：支持 UTF-8/GBK 的 CSV，表头可选。
 * 列名兼容 时间/日期、金额、商户/描述、类型、分类、备注。
 */
class BillImporter(
    private val context: Context,
    private val pipeline: IngestionPipeline,
    private val logger: SafeLogger
) {
    suspend fun importCsv(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取文件")
        val content = decode(bytes)
        val rawLines = content.lineSequence().filter { it.isNotBlank() }.toList()
        if (rawLines.isEmpty()) return@withContext ImportResult(0, 0, 0, 0, 0)

        val firstLine = rawLines.first()
        val delimiter = when {
            firstLine.contains('\t') -> '\t'
            firstLine.contains(';') -> ';'
            else -> ','
        }
        val header = split(firstLine, delimiter)
        val hasHeader = header.any {
            it.contains("金额") || it.equals("amount", true) || it.contains("商户") || it.contains("日期")
        }
        val indices = columnIndices(header, hasHeader)
        val dataLines = if (hasHeader) rawLines.drop(1) else rawLines

        var posted = 0
        var pending = 0
        var duplicate = 0
        var failed = 0
        val limited = dataLines.take(20_000)
        for (line in limited) {
            val row = split(line, delimiter)
            val amountRaw = row.getOrNull(indices.amount) ?: continue
            val amount = Money.parseToCents(amountRaw)
            if (amount == null) {
                failed++
                continue
            }
            val timeRaw = row.getOrNull(indices.time).orEmpty()
            val merchant = row.getOrNull(indices.merchant)?.trim()
                ?.takeIf { it.isNotBlank() } ?: "账单导入"
            val typeRaw = row.getOrNull(indices.type).orEmpty()
            val type = resolveType(typeRaw, amountRaw)
            val occurredAt = TimeUtils.parseDateOrNow(timeRaw)
            val category = row.getOrNull(indices.category)?.trim()
                ?.takeIf { it in CategoryClassifier.categories }
                ?: CategoryClassifier.classify(merchant, line)
            val note = row.getOrNull(indices.note)?.trim()?.takeIf { it.isNotBlank() }?.take(500)

            val base = ParsedRecord(
                amountCents = amount,
                type = type,
                merchant = merchant.take(40),
                category = category,
                occurredAt = occurredAt,
                confidence = 0.86,
                ruleId = "bill_import",
                ruleVersion = 1,
                title = "账单导入",
                note = note,
                fieldSummary = ""
            )
            val parsed = base.copy(fieldSummary = Sanitizer.fieldSummary(base))
            val capture = RawCapture(
                packageName = "import",
                rawText = line,
                timestamp = occurredAt,
                sourceType = SourceType.BILL_IMPORT
            )
            when (pipeline.ingestPreParsed(capture, parsed).status) {
                IngestStatus.POSTED -> posted++
                IngestStatus.PENDING -> pending++
                IngestStatus.DUPLICATE -> duplicate++
                IngestStatus.FAILED -> failed++
            }
        }
        logger.info("IMPORT", "账单导入完成 total=${limited.size} posted=$posted pending=$pending duplicate=$duplicate failed=$failed")
        ImportResult(limited.size, posted, pending, duplicate, failed)
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) bytes.toString(Charset.forName("GBK")) else utf8
    }

    private fun columnIndices(header: List<String>, hasHeader: Boolean): Indices {
        if (!hasHeader) return Indices(time = 0, amount = 1, merchant = 2, type = 3, category = 4, note = 5)
        fun find(vararg names: String): Int =
            header.indexOfFirst { cell -> names.any { cell.contains(it, ignoreCase = true) } }
        return Indices(
            time = find("时间", "日期", "date", "time").takeIf { it >= 0 } ?: 0,
            amount = find("金额", "amount", "交易额").takeIf { it >= 0 } ?: 1,
            merchant = find("商户", "商家", "描述", "对方", "merchant", "description").takeIf { it >= 0 } ?: 2,
            type = find("类型", "收支", "type", "方向").takeIf { it >= 0 } ?: 3,
            category = find("分类", "category").takeIf { it >= 0 } ?: 4,
            note = find("备注", "note", "memo").takeIf { it >= 0 } ?: 5
        )
    }

    private fun resolveType(typeRaw: String, amountRaw: String): TxType {
        val text = "$typeRaw $amountRaw".lowercase()
        return when {
            text.contains("收入") || text.contains("income") || text.contains("credit") ||
                text.contains("退款") || text.contains("入账") -> TxType.INCOME
            text.contains("支出") || text.contains("expense") || text.contains("debit") ||
                amountRaw.trim().startsWith("-") -> TxType.EXPENSE
            else -> TxType.EXPENSE
        }
    }

    private fun split(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val builder = StringBuilder()
        var quoted = false
        for (char in line) {
            when {
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    result += builder.toString()
                    builder.clear()
                }
                else -> builder.append(char)
            }
        }
        result += builder.toString()
        return result.map { it.trim().trim('"') }
    }

    private data class Indices(
        val time: Int,
        val amount: Int,
        val merchant: Int,
        val type: Int,
        val category: Int,
        val note: Int
    )
}
