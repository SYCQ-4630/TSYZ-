package com.tsyz.autoledger.data.repo

import com.tsyz.autoledger.core.log.SafeLogger
import com.tsyz.autoledger.core.model.TxType
import com.tsyz.autoledger.core.util.Fingerprint
import com.tsyz.autoledger.core.util.Sanitizer
import com.tsyz.autoledger.data.db.AppDatabase
import com.tsyz.autoledger.data.db.PendingReconciliationEntity
import com.tsyz.autoledger.data.db.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class ManualEntry(
    val amountCents: Long,
    val merchant: String,
    val category: String,
    val type: TxType,
    val occurredAt: Long,
    val note: String? = null
)

class LedgerRepository(
    private val db: AppDatabase,
    private val logger: SafeLogger
) {
    val transactions: Flow<List<TransactionEntity>> = db.transactionDao().observeAll()
    val pending: Flow<List<PendingReconciliationEntity>> = db.pendingDao().observeUnresolved()
    val pendingCount: Flow<Int> = db.pendingDao().observeCount()
    val rawCount: Flow<Int> = db.rawCaptureDao().observeCount()

    suspend fun addManual(entry: ManualEntry): Result<Long> = withContext(Dispatchers.IO) {
        val merchant = entry.merchant.trim().ifBlank { "手动记录" }.take(40)
        val category = entry.category.ifBlank { "其他" }
        val note = entry.note?.trim()?.takeIf { it.isNotBlank() }?.take(500)
        val key = Fingerprint.dedupeKey(entry.amountCents, entry.type, merchant, entry.occurredAt)
        val from = entry.occurredAt - 120_000L
        val to = entry.occurredAt + 120_000L
        val exists = db.transactionDao().countSimilar(entry.amountCents, entry.type.name, from, to) > 0
        if (exists) return@withContext Result.failure(IllegalStateException("两分钟内存在相同金额与类型，已按幂等规则忽略"))
        val id = db.transactionDao().insert(
            TransactionEntity(
                amountCents = entry.amountCents,
                type = entry.type.name,
                merchant = merchant,
                category = category,
                note = note,
                occurredAt = entry.occurredAt,
                sourceType = "MANUAL",
                confidence = 1.0,
                dedupeKey = key,
                fingerprint = key,
                ruleId = "manual"
            )
        )
        if (id == -1L) {
            Result.failure(IllegalStateException("重复记录，已忽略"))
        } else {
            logger.info("MANUAL", "手动补录成功 merchant=${Sanitizer.merchantMask(merchant)} category=$category")
            Result.success(id)
        }
    }

    /** 备注修改只更新原流水，不插入新记录，天然幂等。 */
    suspend fun updateNote(id: Long, note: String?) = withContext(Dispatchers.IO) {
        db.transactionDao().updateNote(id, note?.trim()?.take(500), System.currentTimeMillis())
    }

    suspend fun updateCategory(id: Long, category: String) = withContext(Dispatchers.IO) {
        db.transactionDao().updateCategory(id, category, System.currentTimeMillis())
    }

    suspend fun updateCategories(ids: List<Long>, category: String) = withContext(Dispatchers.IO) {
        if (ids.isNotEmpty()) {
            db.transactionDao().updateCategories(ids, category, System.currentTimeMillis())
        }
    }

    suspend fun allTransactions(): List<TransactionEntity> = withContext(Dispatchers.IO) {
        db.transactionDao().getAll()
    }

    suspend fun transaction(id: Long): TransactionEntity? = withContext(Dispatchers.IO) {
        db.transactionDao().getById(id)
    }
}
