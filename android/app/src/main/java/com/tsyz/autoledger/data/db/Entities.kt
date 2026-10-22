package com.tsyz.autoledger.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "raw_captures",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["capturedAt"]),
        Index(value = ["status"])
    ]
)
data class RawCaptureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val rawText: String,
    val capturedAt: Long,
    val sourceType: String,
    val fingerprint: String,
    val status: String,
    val ruleId: String? = null,
    val ruleVersion: Int? = null,
    val amountCents: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["occurredAt"]),
        Index(value = ["type"]),
        Index(value = ["category"]),
        Index(value = ["merchant"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountCents: Long,
    val type: String,
    val merchant: String,
    val category: String,
    val note: String? = null,
    val occurredAt: Long,
    val sourceType: String,
    val confidence: Double,
    val dedupeKey: String,
    val fingerprint: String,
    val ruleId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "pending_reconciliation",
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["createdAt"]),
        Index(value = ["resolved"])
    ]
)
data class PendingReconciliationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountCents: Long,
    val type: String,
    val merchant: String,
    val category: String,
    val occurredAt: Long,
    val sourceType: String,
    val confidence: Double,
    val rawText: String,
    val dedupeKey: String,
    val reason: String,
    val resolved: Boolean = false,
    val resolvedTransactionId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val version: Int,
    val json: String,
    val active: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ui_drafts",
    indices = [Index(value = ["scene"], unique = true)]
)
data class UiDraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scene: String,
    val payloadJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val schemaVersion: Int = 1,
    val consumed: Boolean = false
)

@Entity(tableName = "health_heartbeat")
data class HealthHeartbeatEntity(
    @PrimaryKey val channel: String,
    val lastBeatAt: Long,
    val detail: String = ""
)

@Entity(
    tableName = "logs",
    indices = [Index(value = ["ts"]), Index(value = ["module"])]
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val level: String,
    val module: String,
    val message: String,
    val sessionId: String
)
