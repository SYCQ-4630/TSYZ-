package com.tsyz.autoledger.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        RawCaptureEntity::class,
        TransactionEntity::class,
        PendingReconciliationEntity::class,
        RuleEntity::class,
        UiDraftEntity::class,
        HealthHeartbeatEntity::class,
        LogEntity::class
    ],
    version = AppDatabase.VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawCaptureDao(): RawCaptureDao
    abstract fun transactionDao(): TransactionDao
    abstract fun pendingDao(): PendingDao
    abstract fun ruleDao(): RuleDao
    abstract fun draftDao(): DraftDao
    abstract fun healthDao(): HealthDao
    abstract fun logDao(): LogDao

    companion object {
        const val DB_NAME = "auto_ledger.db"

        /**
         * 版本升级前会由 BackupManager 使用 VACUUM INTO 生成加密快照。
         * 当前为首版；未来新增 Migration 时只需追加到这里，备份钩子自动生效。
         */
        const val VERSION = 1

        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
