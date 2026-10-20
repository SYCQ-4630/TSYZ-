package com.selfuse.cellmap.data.importer

import com.selfuse.cellmap.core.model.NetworkSystem
import com.selfuse.cellmap.core.model.StationField
import com.selfuse.cellmap.core.util.Normalizer
import com.selfuse.cellmap.core.util.SearchText
import com.selfuse.cellmap.data.db.StationDraft
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

data class SheetPreview(
    val sheetName: String,
    val rows: List<List<String>>,
    val rowNumbers: List<Int>,
    val dimensionLastRow: Int?
)

data class XlsxParseOutcome(
    val processed: Int,
    val failed: Int,
    val totalRows: Int?
)

class XlsxFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ImportCancelledException : Exception("导入已取消")

/**
 * 两遍读取：
 * 1. scanHeader() 只读前若干行 + sheet dimension，供用户确认表头与字段映射；
 * 2. parse() 从文件开头重新流式读取数据，按批回调，堆内存只保留一个批次。
 *
 * 直接读取 OOXML 的 zip 包，用平台自带的 SAX 解析 sheet 与 sharedStrings，
 * 不依赖 POI/XMLBeans（二者引用 Android 不提供的 javax.xml.stream 与 java.awt）。
 * headerRow 与 rowNumbers 均为 Excel 从 1 开始的行号。
 */
class XlsxParser {

    fun scanHeader(file: File, maxRows: Int = 25): SheetPreview {
        val workbook = openWorkbook(file)
        try {
            val strings = workbook.readSharedStrings()
            val collector = HeaderCollector(maxRows.coerceAtLeast(1))
            val dimension = DimensionRef()
            try {
                workbook.parseSheet(SheetSaxHandler(strings, collector, dimension))
            } catch (e: SAXException) {
                // 已采样到足够行数，提前结束；部分 SAX 实现会包装异常。
                if (e.message?.contains("header-scan-complete") != true) throw e
            }
            return SheetPreview(
                sheetName = workbook.sheetName,
                rows = collector.rows,
                rowNumbers = collector.rowNumbers,
                dimensionLastRow = dimension.endRow
            )
        } catch (e: XlsxFormatException) {
            throw e
        } catch (e: Exception) {
            throw XlsxFormatException("无法读取 XLSX：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            workbook.close()
        }
    }

    /**
     * @param headerRow 表头所在 Excel 行号（从 1 开始）。
     * @param mapping StationField -> 0-based 列号。
     * @param skipRows 已处理的数据行数，用于暂停后续传（重复行会被唯一索引忽略）。
     * @param onBatch 每批成功的 draft 与失败原因；调用方在回调内同步落库。
     */
    fun parse(
        file: File,
        headerRow: Int,
        mapping: Map<StationField, Int>,
        skipRows: Int,
        batchSize: Int,
        isCancelled: () -> Boolean,
        onBatch: (drafts: List<StationDraft>, failures: List<String>, processed: Int) -> Unit
    ): XlsxParseOutcome {
        val workbook = openWorkbook(file)
        try {
            val strings = workbook.readSharedStrings()
            val collector = DataCollector(
                headerRow = headerRow,
                mapping = mapping,
                skipRows = skipRows,
                batchSize = batchSize.coerceIn(100, 1000),
                isCancelled = isCancelled,
                onBatch = onBatch
            )
            val dimension = DimensionRef()
            try {
                workbook.parseSheet(SheetSaxHandler(strings, collector, dimension))
            } catch (e: XlsxCancelled) {
                collector.flush()
                throw ImportCancelledException()
            } catch (e: SAXException) {
                if (e.message?.contains("cancelled") == true) {
                    collector.flush()
                    throw ImportCancelledException()
                }
                throw e
            }
            collector.flush()
            val total = dimension.endRow?.let { end -> (end - headerRow).coerceAtLeast(0) }
            return XlsxParseOutcome(
                processed = collector.seenDataRows,
                failed = collector.failedCount,
                totalRows = total
            )
        } catch (e: ImportCancelledException) {
            throw e
        } catch (e: XlsxFormatException) {
            throw e
        } catch (e: Exception) {
            throw XlsxFormatException("无法读取 XLSX：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            workbook.close()
        }
    }

    private fun openWorkbook(file: File): Workbook {
        if (!file.exists() || !file.canRead()) throw XlsxFormatException("文件不可读")
        if (!file.name.lowercase().endsWith(".xlsx")) {
            throw XlsxFormatException("仅支持 .xlsx（不支持旧版 .xls）")
        }
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            throw XlsxFormatException("无法读取 XLSX：${e.message ?: e.javaClass.simpleName}", e)
        }
        try {
            val workbookEntry = zip.getEntry("xl/workbook.xml")
                ?: throw XlsxFormatException("缺少 workbook.xml")
            val sheets = readSheets(zip, workbookEntry)
            if (sheets.isEmpty()) throw XlsxFormatException("工作簿没有工作表")
            val rels = readRelationships(zip)
            val sheet = sheets.firstOrNull { rels[it.relId]?.type?.endsWith("/worksheet") == true }
                ?: sheets.first()
            val target = rels[sheet.relId]?.target ?: "worksheets/sheet1.xml"
            val sheetEntry = zip.getEntry(resolveTarget("xl", target))
                ?: throw XlsxFormatException("工作表文件缺失")
            val stringsTarget = rels.values.firstOrNull { it.type.endsWith("/sharedStrings") }?.target
            val stringsEntry = stringsTarget?.let { zip.getEntry(resolveTarget("xl", it)) }
                ?: zip.getEntry("xl/sharedStrings.xml")
            return Workbook(zip, sheet.name, sheetEntry, stringsEntry)
        } catch (e: Throwable) {
            zip.close()
            throw e
        }
    }

    private fun readSheets(zip: ZipFile, entry: ZipEntry): List<SheetRef> {
        val result = mutableListOf<SheetRef>()
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                if (nameOf(localName, qName) != "sheet") return
                val name = attributes.local("name") ?: return
                val relId = attributes.local("id") ?: return
                result.add(SheetRef(name, relId))
            }
        }
        parse(zip, entry, handler)
        return result
    }

    private fun readRelationships(zip: ZipFile): Map<String, Relationship> {
        val entry = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return emptyMap()
        val result = mutableMapOf<String, Relationship>()
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                if (nameOf(localName, qName) != "Relationship") return
                val id = attributes.local("Id") ?: return
                val target = attributes.local("Target") ?: return
                val type = attributes.local("Type").orEmpty()
                result[id] = Relationship(target, type)
            }
        }
        parse(zip, entry, handler)
        return result
    }

    private fun parse(zip: ZipFile, entry: ZipEntry, handler: DefaultHandler) {
        zip.getInputStream(entry).use { stream -> parseXml(stream, handler) }
    }

    private class Workbook(
        private val zip: ZipFile,
        val sheetName: String,
        private val sheetEntry: ZipEntry,
        private val stringsEntry: ZipEntry?
    ) : Closeable {

        override fun close() {
            zip.close()
        }

        fun parseSheet(handler: DefaultHandler) {
            zip.getInputStream(sheetEntry).use { stream -> parseXml(stream, handler) }
        }

        fun readSharedStrings(): List<String> {
            val entry = stringsEntry ?: return emptyList()
            val result = mutableListOf<String>()
            val handler = SharedStringsHandler(result)
            zip.getInputStream(entry).use { stream -> parseXml(stream, handler) }
            return result
        }
    }

    private class SharedStringsHandler(private val target: MutableList<String>) : DefaultHandler() {
        private val builder = StringBuilder()
        private var inItem = false
        private var inText = false
        private var phoneticDepth = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (nameOf(localName, qName)) {
                "si" -> {
                    inItem = true
                    inText = false
                    phoneticDepth = 0
                    builder.setLength(0)
                }
                "rPh" -> phoneticDepth++
                "t" -> if (inItem && phoneticDepth == 0) inText = true
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (nameOf(localName, qName)) {
                "t" -> inText = false
                "rPh" -> if (phoneticDepth > 0) phoneticDepth--
                "si" -> if (inItem) {
                    target.add(builder.toString())
                    builder.setLength(0)
                    inItem = false
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (inText && phoneticDepth == 0 && ch != null) {
                builder.append(ch, start, length)
            }
        }
    }

    private class SheetSaxHandler(
        private val strings: List<String>,
        private val sink: SheetSink,
        private val dimension: DimensionRef
    ) : DefaultHandler() {

        private var rowNumber = 0
        private var column = -1
        private var lastColumn = -1
        private var inCell = false
        private var inValue = false
        private var inInline = false
        private var inText = false
        private var inFormula = false
        private var phoneticDepth = 0
        private var cellType = ""
        private val value = StringBuilder()
        private val inline = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (nameOf(localName, qName)) {
                "dimension" -> dimension.ref = attributes.local("ref")
                "row" -> {
                    rowNumber = attributes.local("r")?.toIntOrNull() ?: (rowNumber + 1)
                    sink.startRow(rowNumber)
                }
                "c" -> {
                    inCell = true
                    column = columnIndex(attributes.local("r")).takeIf { it >= 0 } ?: (lastColumn + 1)
                    lastColumn = column
                    cellType = attributes.local("t").orEmpty()
                    value.setLength(0)
                    inline.setLength(0)
                    inValue = false
                    inInline = false
                    inText = false
                    inFormula = false
                    phoneticDepth = 0
                }
                "f" -> if (inCell) inFormula = true
                "v" -> if (inCell) inValue = true
                "is" -> if (inCell) inInline = true
                "rPh" -> if (inCell) phoneticDepth++
                "t" -> if (inCell && inInline && phoneticDepth == 0) inText = true
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (nameOf(localName, qName)) {
                "v" -> inValue = false
                "f" -> inFormula = false
                "t" -> inText = false
                "rPh" -> if (phoneticDepth > 0) phoneticDepth--
                "is" -> inInline = false
                "row" -> sink.endRow(rowNumber)
                "c" -> {
                    if (inCell) {
                        emitCell()
                        inCell = false
                    }
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (ch == null || inFormula) return
            if (inValue) {
                value.append(ch, start, length)
            } else if (inText && phoneticDepth == 0) {
                inline.append(ch, start, length)
            }
        }

        private fun emitCell() {
            val text: String? = when (cellType) {
                "s" -> {
                    val index = value.toString().trim().toIntOrNull() ?: -1
                    strings.getOrNull(index)
                }
                "inlineStr" -> inline.toString()
                "str" -> value.toString()
                "b" -> if (value.toString().trim() == "1") "TRUE" else "FALSE"
                "e" -> null
                "d" -> value.toString()
                else -> numberText(value.toString())
            }
            if (!text.isNullOrBlank()) sink.cell(column, text)
        }
    }

    private class HeaderCollector(private val maxRows: Int) : SheetSink {
        val rows = mutableListOf<List<String>>()
        val rowNumbers = mutableListOf<Int>()
        private var current = linkedMapOf<Int, String>()
        private var maxColumn = -1

        override fun startRow(rowNum: Int) {
            current = linkedMapOf()
        }

        override fun cell(column: Int, value: String) {
            current[column] = value
            if (column > maxColumn) maxColumn = column
        }

        override fun endRow(rowNum: Int) {
            if (current.isEmpty()) return
            val size = maxOf(maxColumn + 1, current.keys.maxOrNull()?.plus(1) ?: 0)
            rows.add(List(size) { current[it].orEmpty() })
            rowNumbers.add(rowNum)
            if (rows.size >= maxRows) throw StopHeaderScan()
        }
    }

    private class DataCollector(
        private val headerRow: Int,
        private val mapping: Map<StationField, Int>,
        private val skipRows: Int,
        private val batchSize: Int,
        private val isCancelled: () -> Boolean,
        private val onBatch: (List<StationDraft>, List<String>, Int) -> Unit
    ) : SheetSink {

        private var current = linkedMapOf<Int, String>()
        private val pending = mutableListOf<StationDraft>()
        private val failures = mutableListOf<String>()
        var seenDataRows = 0
            private set
        var failedCount = 0
            private set

        override fun startRow(rowNum: Int) {
            current = linkedMapOf()
        }

        override fun cell(column: Int, value: String) {
            current[column] = value
        }

        override fun endRow(rowNum: Int) {
            if (isCancelled()) throw XlsxCancelled()
            if (rowNum <= headerRow || current.isEmpty()) return
            val index = seenDataRows
            seenDataRows++
            if (index < skipRows) return

            val values = current
            val draft = runCatching { buildDraft(values) }
                .getOrElse { error ->
                    failedCount++
                    failures.add(error.message ?: "行解析失败")
                    null
                }
            if (draft != null) pending.add(draft)
            if (pending.size >= batchSize) flush()
        }

        fun flush() {
            if (pending.isEmpty() && failures.isEmpty()) return
            onBatch(pending.toList(), failures.toList(), seenDataRows)
            pending.clear()
            failures.clear()
        }

        private fun resolveSystem(
            systemRaw: String?,
            bandRaw: String?,
            cellName: String?,
            siteName: String?,
            category: String?
        ): NetworkSystem? {
            NetworkSystem.explicit(systemRaw)?.let { return it }
            NetworkSystem.explicit(bandRaw)?.let { return it }
            val inferred = NetworkSystem.fromBand(systemRaw) ?: NetworkSystem.fromBand(bandRaw)
            val context = listOfNotNull(cellName, siteName, category).joinToString(" ")
            val hinted = NetworkSystem.explicit(context)
            return when {
                inferred == null -> hinted
                hinted != null && NetworkSystem.isAmbiguousBand(systemRaw ?: bandRaw) -> hinted
                else -> inferred
            }
        }

        private fun buildDraft(values: Map<Int, String>): StationDraft {
            fun raw(field: StationField): String? {
                val column = mapping[field] ?: return null
                return clean(values[column])
            }

            val lat = raw(StationField.LATITUDE)?.toDoubleOrNull()
                ?: throw RowFailure("纬度缺失或无效")
            val lon = raw(StationField.LONGITUDE)?.toDoubleOrNull()
                ?: throw RowFailure("经度缺失或无效")
            if (lat !in -90.0..90.0) throw RowFailure("纬度超出范围")
            if (lon !in -180.0..180.0) throw RowFailure("经度超出范围")

            val stationId = raw(StationField.STATION_ID)
                ?: throw RowFailure("缺少基站 ID")
            val cellId = raw(StationField.CELL_ID)
                ?: throw RowFailure("缺少小区 ID")

            val builder = raw(StationField.BUILDER)
            val operator = raw(StationField.OPERATOR)
            val shared = raw(StationField.SHARED)
            val district = raw(StationField.DISTRICT)
            val cellName = raw(StationField.CELL_NAME)
            val siteName = raw(StationField.SITE_NAME)
            val category = raw(StationField.CATEGORY)
            val systemRaw = raw(StationField.SYSTEM)
            val bandRaw = raw(StationField.BAND)
            val system = resolveSystem(systemRaw, bandRaw, cellName, siteName, category)
                ?: throw RowFailure("频段/制式无法识别为 4G/5G")

            return StationDraft(
                lat = lat,
                lon = lon,
                builder = builder,
                operator = operator,
                district = district,
                owner = raw(StationField.OWNER),
                stationId = stationId,
                cellName = cellName,
                cellId = cellId,
                category = category,
                band = bandRaw ?: systemRaw?.takeIf { NetworkSystem.fromBand(it) != null },
                siteName = siteName,
                shared = shared,
                antennaHeight = raw(StationField.ANTENNA_HEIGHT),
                coDevice45G = raw(StationField.CO_DEVICE_45G),
                system = system.label,
                operatorNorm = Normalizer.operator(operator).label,
                builderNorm = Normalizer.builder(builder).label,
                sharedNorm = Normalizer.shared(shared).label,
                searchBlob = SearchText.searchBlob(
                    stationId,
                    cellId,
                    cellName,
                    siteName,
                    district
                )
            )
        }

        private fun clean(value: String?): String? {
            if (value == null) return null
            val cleaned = value
                .replace('\u0000', ' ')
                .trim()
                .take(512)
            return cleaned.ifBlank { null }
        }
    }

    private interface SheetSink {
        fun startRow(rowNum: Int)
        fun cell(column: Int, value: String)
        fun endRow(rowNum: Int)
    }

    private data class SheetRef(val name: String, val relId: String)

    private data class Relationship(val target: String, val type: String)

    private class DimensionRef {
        var ref: String? = null
        val endRow: Int?
            get() {
                val match = Regex("([A-Za-z]+)(\\d+)").findAll(ref.orEmpty()).lastOrNull() ?: return null
                return match.groupValues[2].toIntOrNull()
            }
    }

    private class RowFailure(message: String) : Exception(message)
    private class StopHeaderScan : SAXException("header-scan-complete")
    private class XlsxCancelled : SAXException("cancelled")

}



private fun nameOf(localName: String?, qName: String?): String =
    localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')

private fun Attributes?.local(name: String): String? {
    if (this == null) return null
    for (index in 0 until length) {
        val local = getLocalName(index)
        val qualified = getQName(index)
        if (local == name || qualified == name || qualified.endsWith(":$name")) {
            return getValue(index)
        }
    }
    return null
}

private fun numberText(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    return runCatching { BigDecimal(text).stripTrailingZeros().toPlainString() }
        .getOrElse { text }
}

private fun parseXml(stream: InputStream, handler: DefaultHandler) {
    val factory = SAXParserFactory.newInstance()
    factory.isNamespaceAware = true
    runCatching {
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    runCatching {
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    }
    runCatching {
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val reader = factory.newSAXParser().xmlReader
    reader.contentHandler = handler
    reader.parse(InputSource(stream))
}

private fun resolveTarget(base: String, rawTarget: String): String {
    val target = rawTarget.trim()
    val combined = if (target.startsWith("/")) target.removePrefix("/") else "$base/$target"
    val parts = ArrayDeque<String>()
    combined.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeLast()
            else -> parts.addLast(segment)
        }
    }
    return parts.joinToString("/")
}

fun columnLetter(index: Int): String {
    var value = index
    val builder = StringBuilder()
    while (value >= 0) {
        builder.append(('A' + (value % 26)))
        value = value / 26 - 1
    }
    return builder.reverse().toString()
}

fun columnIndex(reference: String?): Int {
    if (reference.isNullOrBlank()) return -1
    var value = 0
    var any = false
    for (ch in reference) {
        when (ch) {
            in 'A'..'Z' -> {
                value = value * 26 + (ch - 'A' + 1)
                any = true
            }
            in 'a'..'z' -> {
                value = value * 26 + (ch - 'a' + 1)
                any = true
            }
            else -> if (any) break
        }
    }
    return if (any) value - 1 else -1
}

object ColumnMapping {
    private val systemAliases = StationField.SYSTEM.aliases.map { Normalizer.normalizeHeader(it) }
    private val bandAliases = StationField.BAND.aliases.map { Normalizer.normalizeHeader(it) }

    /**
     * 先映射其他字段，再单独处理「制式/频段」：优先「制式」列；没有时把「频段」列同时
     * 用作制式识别来源。这样表头只写「频段」的台账也能自动对齐，且频段原始值仍会保存。
     */
    fun autoDetect(header: List<String>): Map<StationField, Int> {
        val normalized = header.map { Normalizer.normalizeHeader(it) }
        val used = mutableSetOf<Int>()
        val result = linkedMapOf<StationField, Int>()

        StationField.entries
            .filter { it != StationField.SYSTEM && it != StationField.BAND }
            .forEach { field ->
                findColumn(normalized, field.aliases, used)?.let { index ->
                    result[field] = index
                    used.add(index)
                }
            }

        val systemIndex = findExact(normalized, systemAliases, used)
            ?: findFuzzy(normalized, systemAliases, used)
        if (systemIndex != null) {
            result[StationField.SYSTEM] = systemIndex
            used.add(systemIndex)
        }

        val bandIndex = findExact(normalized, bandAliases, used)
            ?: findFuzzy(normalized, bandAliases, used)
            ?: systemIndex?.takeIf { isFrequencyHeader(normalized.getOrNull(it)) }
        if (bandIndex != null) {
            result[StationField.BAND] = bandIndex
        }

        return result
    }

    fun missingRequired(mapping: Map<StationField, Int>): List<StationField> =
        StationField.entries.filter { it.required && !mapping.containsKey(it) }

    private fun findColumn(
        normalized: List<String>,
        aliases: List<String>,
        used: Set<Int>
    ): Int? {
        val normalizedAliases = aliases.map { Normalizer.normalizeHeader(it) }
        return findExact(normalized, normalizedAliases, used)
            ?: findFuzzy(normalized, normalizedAliases, used)
    }

    private fun findExact(normalized: List<String>, aliases: List<String>, used: Set<Int>): Int? {
        aliases.forEach { alias ->
            val index = normalized.indices.firstOrNull { idx ->
                idx !in used && normalized[idx].isNotBlank() && normalized[idx] == alias
            }
            if (index != null) return index
        }
        return null
    }

    private fun findFuzzy(normalized: List<String>, aliases: List<String>, used: Set<Int>): Int? {
        aliases.forEach { alias ->
            val index = normalized.indices.firstOrNull { idx ->
                val value = normalized[idx]
                !used.contains(idx) && value.isNotBlank() && alias.isNotBlank() &&
                    (value.contains(alias) || alias.contains(value))
            }
            if (index != null) return index
        }
        return null
    }

    private fun isFrequencyHeader(header: String?): Boolean {
        val value = header ?: return false
        return bandAliases.any { alias ->
            alias.isNotBlank() && (value == alias || value.contains(alias))
        }
    }
}
