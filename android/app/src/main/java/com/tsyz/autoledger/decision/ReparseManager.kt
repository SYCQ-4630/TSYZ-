package com.tsyz.autoledger.decision

import com.tsyz.autoledger.core.log.SafeLogger
import com.tsyz.autoledger.core.model.IngestStatus
import com.tsyz.autoledger.core.model.RawCapture
import com.tsyz.autoledger.core.model.SourceType
import com.tsyz.autoledger.data.db.RawCaptureDao
import com.tsyz.autoledger.rules.RuleRepository

data class ReparseResult(val attempted: Int, val posted: Int, val pending: Int, val failed: Int)

/** 规则升级后回溯重解析近期解析失败的原始捕获。 */
class ReparseManager(
    private val rawDao: RawCaptureDao,
    private val rules: RuleRepository,
    private val pipeline: IngestionPipeline,
    private val logger: SafeLogger
) {
    suspend fun reparseRecentFailures(limit: Int = 300): ReparseResult {
        rules.engine()
        val failures = rawDao.recentFailed(limit)
        var posted = 0
        var pending = 0
        var failed = 0
        for (entity in failures) {
            val capture = RawCapture(
                packageName = entity.packageName,
                rawText = entity.rawText,
                timestamp = entity.capturedAt,
                sourceType = SourceType.fromName(entity.sourceType),
                fingerprint = entity.fingerprint
            )
            when (pipeline.ingest(capture).status) {
                IngestStatus.POSTED -> posted++
                IngestStatus.PENDING -> pending++
                IngestStatus.FAILED -> failed++
                IngestStatus.DUPLICATE -> Unit
            }
        }
        val result = ReparseResult(failures.size, posted, pending, failed)
        logger.info(
            "RULES",
            "规则回溯完成 attempted=${result.attempted} posted=$posted pending=$pending failed=$failed"
        )
        return result
    }
}
