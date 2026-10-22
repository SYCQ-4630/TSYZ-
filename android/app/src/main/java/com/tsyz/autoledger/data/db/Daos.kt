package com.tsyz.autoledger.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RawCaptureDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: RawCaptureEntity): Long

    @Query(
        "UPDATE raw_captures SET status = :status, ruleId = :ruleId, ruleVersion = :version, " +
            "amountCents = :amount WHERE fingerprint = :fingerprint"
    )
    suspend fun markParsed(fingerprint: String, status: String, ruleId: String, version: Int, amount: Long)

    @Query("UPDATE raw_captures SET status = :status WHERE fingerprint = :fingerprint")
    suspend fun markStatus(fingerprint: String, status: String)

    @Query("SELECT * FROM raw_captures WHERE status = 'FAILED' ORDER BY capturedAt DESC LIMIT :limit")
    suspend fun recentFailed(limit: Int): List<RawCaptureEntity>

    @Query("SELECT * FROM raw_captures WHERE capturedAt BETWEEN :from AND :to ORDER BY capturedAt ASC")
    suspend fun around(from: Long, to: Long): List<RawCaptureEntity>

    @Query("SELECT COUNT(*) FROM raw_captures WHERE capturedAt BETWEEN :from AND :to")
    suspend fun countInRange(from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM raw_captures")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM raw_captures ORDER BY capturedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RawCaptureEntity>

    @Query("DELETE FROM raw_captures WHERE capturedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TransactionEntity): Long

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC")
    suspend fun getInRange(from: Long, to: Long): List<TransactionEntity>

    @Query(
        "SELECT COUNT(*) FROM transactions WHERE amountCents = :amountCents AND type = :type " +
            "AND occurredAt BETWEEN :from AND :to"
    )
    suspend fun countSimilar(amountCents: Long, type: String, from: Long, to: Long): Int

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("UPDATE transactions SET note = :note, updatedAt = :now WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?, now: Long)

    @Query("UPDATE transactions SET category = :category, updatedAt = :now WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String, now: Long)

    @Query("UPDATE transactions SET category = :category, updatedAt = :now WHERE id IN (:ids)")
    suspend fun updateCategories(ids: List<Long>, category: String, now: Long)

    @Query("SELECT COUNT(*) FROM transactions WHERE occurredAt BETWEEN :from AND :to")
    suspend fun countInRange(from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE occurredAt BETWEEN :from AND :to AND sourceType != 'MANUAL'")
    suspend fun countAutoInRange(from: Long, to: Long): Int

    @Query("DELETE FROM transactions WHERE occurredAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface PendingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PendingReconciliationEntity): Long

    @Query("SELECT * FROM pending_reconciliation WHERE resolved = 0 ORDER BY occurredAt DESC")
    fun observeUnresolved(): Flow<List<PendingReconciliationEntity>>

    @Query("SELECT COUNT(*) FROM pending_reconciliation WHERE resolved = 0")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_reconciliation WHERE resolved = 0 ORDER BY occurredAt ASC")
    suspend fun getUnresolved(): List<PendingReconciliationEntity>

    @Query("SELECT * FROM pending_reconciliation WHERE dedupeKey = :key LIMIT 1")
    suspend fun byDedupeKey(key: String): PendingReconciliationEntity?

    @Query("SELECT * FROM pending_reconciliation WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): PendingReconciliationEntity?

    @Query(
        "SELECT COUNT(*) FROM pending_reconciliation WHERE amountCents = :amountCents " +
            "AND type = :type AND occurredAt BETWEEN :from AND :to"
    )
    suspend fun countSimilar(amountCents: Long, type: String, from: Long, to: Long): Int

    @Query(
        "UPDATE pending_reconciliation SET resolved = 1, resolvedTransactionId = :transactionId, " +
            "updatedAt = :now WHERE id = :id"
    )
    suspend fun resolve(id: Long, transactionId: Long?, now: Long)

    @Query("DELETE FROM pending_reconciliation WHERE resolved = 1 AND updatedAt < :cutoff")
    suspend fun deleteResolvedOlderThan(cutoff: Long)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules WHERE active = 1")
    suspend fun activeRules(): List<RuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<RuleEntity>)

    @Query("SELECT COUNT(*) FROM rules")
    suspend fun count(): Int

    @Query("SELECT MAX(version) FROM rules")
    suspend fun maxVersion(): Int?

    @Query("DELETE FROM rules")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(rules: List<RuleEntity>) {
        clear()
        upsertAll(rules)
    }
}

@Dao
interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: UiDraftEntity)

    @Query("SELECT * FROM ui_drafts WHERE consumed = 0 ORDER BY updatedAt DESC")
    suspend fun unconsumed(): List<UiDraftEntity>

    @Query("SELECT * FROM ui_drafts WHERE scene = :scene AND consumed = 0 LIMIT 1")
    suspend fun byScene(scene: String): UiDraftEntity?

    @Query("UPDATE ui_drafts SET consumed = 1, updatedAt = :now WHERE scene = :scene")
    suspend fun consume(scene: String, now: Long)

    @Query("DELETE FROM ui_drafts WHERE consumed = 1")
    suspend fun deleteConsumed()

    @Query("DELETE FROM ui_drafts WHERE updatedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface HealthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HealthHeartbeatEntity)

    @Query("SELECT * FROM health_heartbeat")
    fun observeAll(): Flow<List<HealthHeartbeatEntity>>

    @Query("SELECT * FROM health_heartbeat")
    suspend fun all(): List<HealthHeartbeatEntity>
}

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entity: LogEntity): Long

    @Query("SELECT * FROM logs ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LogEntity>>

    @Query("SELECT * FROM logs WHERE module = :module ORDER BY ts DESC LIMIT :limit")
    fun observeByModule(module: String, limit: Int): Flow<List<LogEntity>>

    @Query(
        "SELECT * FROM logs WHERE ts BETWEEN :from AND :to " +
            "AND (:module IS NULL OR module = :module) ORDER BY ts DESC LIMIT :limit"
    )
    suspend fun queryForExport(from: Long, to: Long, module: String?, limit: Int): List<LogEntity>

    @Query("DELETE FROM logs WHERE ts < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY ts DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("SELECT COUNT(*) FROM logs")
    suspend fun count(): Int
}
