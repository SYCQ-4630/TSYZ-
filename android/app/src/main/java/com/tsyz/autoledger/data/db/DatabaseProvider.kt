package com.tsyz.autoledger.data.db

import android.content.Context
import androidx.room.Room
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

object DatabaseProvider {
    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    fun passphrase(context: Context): ByteArray =
        DatabaseKeyManager.passphrase(context.applicationContext)

    private fun create(context: Context): AppDatabase {
        val passphrase = DatabaseKeyManager.passphrase(context)
        SQLiteDatabase.loadLibs(context)
        val backups = BackupManager(context)
        backups.preMigrationBackup(passphrase)

        val factory = SupportFactory(passphrase)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DB_NAME)
            .openHelperFactory(factory)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        // 打开后触发一次读写，确保迁移已成功，再清理旧快照。
        database.openHelper.writableDatabase
        backups.cleanupOldMigrationSnapshots()
        return database
    }
}
