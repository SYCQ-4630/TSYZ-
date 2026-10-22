package com.tsyz.autoledger.decision

import com.tsyz.autoledger.core.log.SafeLogger
import com.tsyz.autoledger.core.model.IngestResult
import com.tsyz.autoledger.core.model.IngestStatus
import com.tsyz.autoledger.core.model.ParsedRecord
import com.tsyz.autoledger.core.model.RawCapture
import com.tsyz.autoledger.core.model.SourceType
import com.tsyz.autoledger.core.util.Fingerprint
import com.tsyz.autoledger.data.db.AppDatabase
import com.tsyz.autoledger.data.db.PendingReconciliationEntity
import com.tsyz.autoledger.data.db.RawCaptureEntity
import com.tsyz.autoledger.data.db.TransactionEntity
import com.tsyz.autoledger.data.prefs.SettingsStore
import com.tsyz.autoledger.health.HealthChannel
import com.tsyz.autoledger.health.HealthMonitor
import com.tsyz.autoledger.rules.RuleRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RawStatus {
    const val RECEIVED = "RECEIVED"
    const val PARSED = "PARSED"
    const val FAILED = "FAILED"
    const val DUPLICATE = "DUPLICATE"
}

/**
 * 入账决策层：原始捕获 -> 规则解析 -> 跨通道去重 -> 置信度分流 -> 幂等入库。
 * 所有入口共用同一把互斥锁与同一去重键，重复事件不会生成重复流水。
 */
class IngestionPipeline(
    private val db: AppDatabase,
    private val rules: RuleRepository,
    private val settings: SettingsStore,
    private val logger: SafeLogger,
    private val health: HealthMonitor
) {
    private val mutex = Mutex()

    suspend fun ingest(capture: RawCapture): IngestResult = handle(capture, null)

    /** 账单文件等结构化输入可直接提供已解析结果，跳过规则引擎。 */
    suspend fun ingestPreParsed(capture: RawCapture, parsed: ParsedRecord): IngestResult =
        handle(capture, parsed)

    private suspend fun handle(capture: RawCapture, preParsed: ParsedRecord?): IngestResult = mutex.withLock {
        if (capture.rawText.isBlank()) return@withLock IngestResult(IngestStatus.FAILED, message = "empty")
        beatFor(capture.sourceType)

        db.rawCaptureDao().insert(
            RawCaptureEntity(
                packageName = capture.packageName,
                rawText = capture.rawText,
                capturedAt = capture.timestamp,
                sourceType = capture.sourceType.name,
                fingerprint = capture.fingerprint,
                status = RawStatus.RECEIVED
            )
        )

        val parsed = preParsed ?: runCatching { rules.engine().parse(capture) }.getOrElse { error ->
            logger.error("PARSE", "解析异常 ${error.javaClass.simpleName} source=${capture.sourceType.name}")
            null
        }

        if (parsed == null) {
            db.rawCaptureDao().markStatus(capture.fingerprint, RawStatus.FAILED)
            logger.warn(
                "PARSE",
                "解析失败 source=${capture.sourceType.name} package=${capture.packageName} " +
                    "textLength=${capture.rawText.length}"
            )
            return@withLock IngestResult(IngestStatus.FAILED, message = "unparsed")
        }

        db.rawCaptureDao().markParsed(
            fingerprint = capture.fingerprint,
            status = RawStatus.PARSED,
            ruleId = parsed.ruleId,
            version = parsed.ruleVersion,
            amount = parsed.amountCents
        )

        val dedupeKey = Fingerprint.dedupeKey(
            parsed.amountCents,
            parsed.type,
            parsed.merchant,
            parsed.occurredAt
        )
        val from = parsed.occurredAt - DEDUPE_WINDOW_MS
        val to = parsed.occurredAt + DEDUPE_WINDOW_MS
        val duplicateTransaction = db.transactionDao().countSimilar(
            parsed.amountCents, parsed.type.name, from, to
        ) > 0
        val duplicatePending = db.pendingDao().countSimilar(
            parsed.amountCents, parsed.type.name, from, to
        ) > 0

        if (duplicateTransaction || duplicatePending) {
            db.rawCaptureDao().markStatus(capture.fingerprint, RawStatus.DUPLICATE)
            logger.info("DEDUPE", "跨通道重复捕获已忽略 source=${capture.sourceType.name} package=${capture.packageName}")
            return@withLock IngestResult(IngestStatus.DUPLICATE, message = "duplicate")
        }

        val threshold = settings.current().autoPostThreshold.toDouble()
        if (parsed.confidence >= threshold) {
            val transactionId = db.transactionDao().insert(
                parsed.toTransaction(capture.sourceType, dedupeKey, capture.fingerprint)
            )
            if (transactionId == -1L) {
                db.rawCaptureDao().markStatus(capture.fingerprint, RawStatus.DUPLICATE)
                return@withLock IngestResult(IngestStatus.DUPLICATE, message = "unique_key_conflict")
            }
            logger.info(
                "INGEST",
                "自动入账 source=${capture.sourceType.name} package=${capture.packageName} ${parsed.fieldSummary}"
            )
            return@withLock IngestResult(IngestStatus.POSTED, transactionId = transactionId)
        }

        val pendingId = db.pendingDao().insert(parsed.toPending(capture, dedupeKey))
        if (pendingId == -1L) {
            db.rawCaptureDao().markStatus(capture.fingerprint, RawStatus.DUPLICATE)
            return@withLock IngestResult(IngestStatus.DUPLICATE, message = "pending_conflict")
        }
        logger.info(
            "INGEST",
            "进入待核对 source=${capture.sourceType.name} package=${capture.packageName} ${parsed.fieldSummary}"
        )
        return@withLock IngestResult(IngestStatus.PENDING, pendingId = pendingId)
    }

    /** 月报前交叉验证通过后，用同一去重键提升为正式流水。 */
    suspend fun promotePending(pending: PendingReconciliationEntity, confidence: Double): IngestResult =
        mutex.withLock {
            val now = System.currentTimeMillis()
            val from = pending.occurredAt - DEDUPE_WINDOW_MS
            val to = pending.occurredAt + DEDUPE_WINDOW_MS
            val existing = db.transactionDao().countSimilar(
                pending.amountCents, pending.type, from, to
            )
            if (existing > 0) {
                db.pendingDao().resolve(pending.id, null, now)
                return@withLock IngestResult(IngestStatus.DUPLICATE, message = "already_posted")
            }
            val transactionId = db.transactionDao().insert(
                TransactionEntity(
                    amountCents = pending.amountCents,
                    type = pending.type,
                    merchant = pending.merchant,
                    category = pending.category,
                    note = "月报前交叉核对自动修复",
                    occurredAt = pending.occurredAt,
                    sourceType = pending.sourceType,
                    confidence = confidence,
                    dedupeKey = pending.dedupeKey,
                    fingerprint = pending.dedupeKey,
                    ruleId = "reconciliation"
                )
            )
            if (transactionId == -1L) {
                db.pendingDao().resolve(pending.id, null, now)
                return@withLock IngestResult(IngestStatus.DUPLICATE, message = "conflict")
            }
            db.pendingDao().resolve(pending.id, transactionId, now)
            logger.info("RECONCILE", "待核对自动修复：merchant=${com.tsyz.autoledger.core.util.Sanitizer.merchantMask(pending.merchant)}")
            IngestResult(IngestStatus.POSTED, transactionId = transactionId)
        }

    private fun ParsedRecord.toTransaction(
        sourceType: SourceType,
        dedupeKey: String,
        fingerprint: String
    ) = TransactionEntity(
        amountCents = amountCents,
        type = type.name,
        merchant = merchant,
        category = category,
        note = note,
        occurredAt = occurredAt,
        sourceType = sourceType.name,
        confidence = confidence,
        dedupeKey = dedupeKey,
        fingerprint = fingerprint,
        ruleId = ruleId
    )

    private fun ParsedRecord.toPending(capture: RawCapture, dedupeKey: String) =
        PendingReconciliationEntity(
            amountCents = amountCents,
            type = type.name,
            merchant = merchant,
            category = category,
            occurredAt = occurredAt,
            sourceType = capture.sourceType.name,
            confidence = confidence,
            rawText = capture.rawText,
            dedupeKey = dedupeKey,
            reason = if (confidence >= 0.55) "medium_confidence" else "low_confidence"
        )

    private fun beatFor(sourceType: SourceType) {
        when (sourceType) {
            SourceType.ACCESSIBILITY -> health.beat(HealthChannel.ACCESSIBILITY, "capture")
            SourceType.SMS -> health.beat(HealthChannel.SMS, "sms")
            SourceType.NOTIFICATION -> health.beat(HealthChannel.NOTIFICATION, "notification")
            SourceType.OCR -> health.beat(HealthChannel.OCR, "ocr")
            else -> Unit
        }
    }

    companion object {
        const val DEDUPE_WINDOW_MS = 120_000L
    }
}
