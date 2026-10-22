package com.tsyz.autoledger.data.db

import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 迁移前使用 VACUUM INTO 生成加密快照（SQLCipher 连接产出的文件仍是加密的），
 * 快照保存在应用私有目录 files/encrypted_backups，不进入系统备份。
 */
class BackupManager(private val context: Context) {

    private val backupDir: File
        get() = File(context.filesDir, "encrypted_backups").apply { if (!exists()) mkdirs() }

    /**
     * 打开数据库前检查 user_version。若结构版本低于当前版本，先生成快照再让 Room 执行迁移。
     */
    fun preMigrationBackup(passphrase: ByteArray) {
        val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        if (!dbFile.exists()) return
        val currentVersion = runCatching { readUserVersion(dbFile, passphrase) }.getOrElse {
            Timber.e(it, "读取数据库版本失败，跳过迁移前备份")
            return
        }
        if (currentVersion >= AppDatabase.VERSION) return
        val target = File(backupDir, "pre_migration_v${currentVersion}_${timestamp()}.db")
        runCatching { vacuumInto(dbFile, passphrase, target) }
            .onSuccess { Timber.i("迁移前加密快照已生成: ${target.name}") }
            .onFailure { Timber.e(it, "迁移前加密快照失败") }
    }

    /** 管理界面手动创建加密快照。 */
    fun createSnapshot(passphrase: ByteArray): File? {
        val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        if (!dbFile.exists()) return null
        val target = File(backupDir, "manual_v${AppDatabase.VERSION}_${timestamp()}.db")
        return runCatching { vacuumInto(dbFile, passphrase, target) }
            .onFailure { Timber.e(it, "手动快照失败") }
            .getOrNull()
    }

    fun listSnapshots(): List<File> =
        backupDir.listFiles()?.filter { it.isFile && it.extension == "db" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 迁移成功后只保留最近两个迁移前快照，清理更旧的快照。 */
    fun cleanupOldMigrationSnapshots() {
        listSnapshots()
            .filter { it.name.startsWith("pre_migration_") }
            .drop(2)
            .forEach { stale ->
                if (stale.delete()) Timber.i("已清理旧迁移快照: ${stale.name}")
            }
    }

    private fun readUserVersion(dbFile: File, passphrase: ByteArray): Int {
        SQLiteDatabase.loadLibs(context)
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            passphrase.toString(Charsets.UTF_8),
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        db.use { database ->
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                return if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }
    }

    private fun vacuumInto(dbFile: File, passphrase: ByteArray, target: File): File {
        if (target.exists()) target.delete()
        SQLiteDatabase.loadLibs(context)
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            passphrase.toString(Charsets.UTF_8),
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        db.use { database ->
            val escaped = target.absolutePath.replace("'", "''")
            database.execSQL("VACUUM INTO '$escaped'")
        }
        check(target.exists() && target.length() > 0) { "snapshot not created" }
        return target
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
