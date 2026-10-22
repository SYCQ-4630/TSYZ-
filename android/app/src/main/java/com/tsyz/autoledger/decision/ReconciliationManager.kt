package com.tsyz.autoledger.decision

import com.tsyz.autoledger.core.log.SafeLogger
import com.tsyz.autoledger.core.model.IngestStatus
import com.tsyz.autoledger.core.model.RawCapture
import com.tsyz.autoledger.core.model.SourceType
import com.tsyz.autoledger.core.model.TxType
import com.tsyz.autoledger.data.db.AppDatabase
import com.tsyz.autoledger.rules.RuleRepository
import kotlin.math.abs

data class ReconcileResult(val checked: Int, val repaired: Int)

/**
 * 月报生成前复核待核对队列：用短信金额与 OCR 金额交叉验证，
 * 能自动修复的直接入账，修复不了的保留在队列中由月报提示数量。
 */
class ReconciliationManager(
    private val db: AppDatabase,
    private val rules: RuleRepository,
    private val pipeline: IngestionPipeline,
    private val logger: SafeLogger
) {
    suspend fun reconcilePending(): ReconcileResult {
        val pendingList = db.pendingDao().getUnresolved()
        var repaired = 0
        val engine = rules.engine()
        for (pending in pendingList) {
            val rawCaptures = db.rawCaptureDao().around(
                pending.occurredAt - WINDOW_MS,
                pending.occurredAt + WINDOW_MS
            )
            val corroborated = rawCaptures.any { raw ->
                val source = SourceType.fromName(raw.sourceType)
                if (source != SourceType.SMS && source != SourceType.OCR) return@any false
                if (raw.fingerprint == pending.dedupeKey) return@any false
                val parsed = engine.parse(
                    RawCapture(
                        packageName = raw.packageName,
                        rawText = raw.rawText,
                        timestamp = raw.capturedAt,
                        sourceType = source,
                        fingerprint = raw.fingerprint
                    )
                ) ?: return@any false
                parsed.type == TxType.fromName(pending.type) && abs(parsed.amountCents - pending.amountCents) <= TOLERANCE_CENTS
            }
            if (corroborated) {
                val result = pipeline.promotePending(pending, maxOf(pending.confidence, 0.9))
                if (result.status == IngestStatus.POSTED) repaired++
            }
        }
        logger.info("RECONCILE", "月报前核对完成 checked=${pendingList.size} repaired=$repaired")
        return ReconcileResult(pendingList.size, repaired)
    }

    companion object {
        private const val WINDOW_MS = 10L * 60L * 1000L
        private const val TOLERANCE_CENTS = 100L
    }
}
