package com.selfuse.cellmap.data.key

import com.selfuse.cellmap.core.model.KeyHealth
import com.selfuse.cellmap.core.model.KeyUi
import com.selfuse.cellmap.core.util.Formats
import com.selfuse.cellmap.data.db.LogLevel
import com.selfuse.cellmap.data.db.LogModule
import com.selfuse.cellmap.data.prefs.AppPreferences
import com.selfuse.cellmap.data.prefs.StoredKeyRecord
import com.selfuse.cellmap.data.repo.AppLogger
import com.selfuse.cellmap.data.security.KeystoreCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class SessionKey(val id: String, val label: String, val plain: String, val usedToday: Int)

sealed interface KeyAddResult {
    data object Success : KeyAddResult
    data class Error(val message: String) : KeyAddResult
}

sealed interface KeyVerifyResult {
    data class Success(val message: String) : KeyVerifyResult
    data class Invalid(val message: String) : KeyVerifyResult
    data class NetworkError(val message: String) : KeyVerifyResult
}

class KeyRepository(
    private val prefs: AppPreferences,
    private val cipher: KeystoreCipher,
    private val tiles: TiandituTiles,
    private val logger: AppLogger
) {
    private val mutex = Mutex()

    val keys: Flow<List<KeyUi>> = prefs.keysFlow
        .map { records -> records.map { it.toUi() } }
        .flowOn(Dispatchers.IO)

    val keyHealth: Flow<KeyHealth> = prefs.keysFlow
        .map { records ->
            val usage = prefs.usageToday(records.map { it.id })
            KeyHealth(
                total = records.size,
                verified = records.count { it.verified && decryptOrNull(it) != null },
                maxDailyUsage = usage.values.maxOrNull() ?: 0
            )
        }
        .flowOn(Dispatchers.IO)

    suspend fun addKey(label: String, rawKey: String): KeyAddResult = mutex.withLock {
        val value = rawKey.trim()
        if (value.isBlank()) return@withLock KeyAddResult.Error("Key 不能为空")
        val records = prefs.keys()
        if (records.size >= MAX_KEYS) {
            return@withLock KeyAddResult.Error("最多保存 $MAX_KEYS 个 Key，请先删除一个")
        }
        val duplicate = records.any { decryptOrNull(it) == value }
        if (duplicate) return@withLock KeyAddResult.Error("该 Key 已存在")

        val payload = runCatching { cipher.encrypt(value) }.getOrElse {
            logger.log(LogModule.KEY, LogLevel.ERROR, "Key 加密失败", it.message)
            return@withLock KeyAddResult.Error("Keystore 加密失败，请重试")
        }
        val record = StoredKeyRecord(
            id = UUID.randomUUID().toString(),
            label = label.trim().ifBlank { "天地图 Key ${records.size + 1}" },
            cipherText = payload.cipherText,
            iv = payload.iv,
            verified = false,
            createdAt = System.currentTimeMillis()
        )
        prefs.writeKeys(records + record)
        logger.log(LogModule.KEY, LogLevel.INFO, "已保存 Key：${record.label}（未验证）")
        syncKeyReady()
        KeyAddResult.Success
    }

    suspend fun removeKey(id: String): Boolean = mutex.withLock {
        val records = prefs.keys()
        val target = records.firstOrNull { it.id == id } ?: return@withLock false
        prefs.writeKeys(records.filterNot { it.id == id })
        logger.log(LogModule.KEY, LogLevel.INFO, "已删除 Key：${target.label}")
        syncKeyReady()
        true
    }

    suspend fun verify(id: String): KeyVerifyResult = mutex.withLock {
        val records = prefs.keys()
        val record = records.firstOrNull { it.id == id }
            ?: return@withLock KeyVerifyResult.Invalid("Key 不存在")
        val plain = decryptOrNull(record)
            ?: return@withLock KeyVerifyResult.Invalid("无法解密，请删除后重新添加")
        logger.log(LogModule.KEY, LogLevel.INFO, "开始验证 Key：${record.label}")
        when (val result = tiles.probe(plain)) {
            is ProbeResult.Ok -> {
                updateRecord(record.copy(verified = true, lastVerifiedAt = System.currentTimeMillis()))
                logger.log(LogModule.KEY, LogLevel.INFO, "Key 验证通过：${record.label}")
                syncKeyReady()
                KeyVerifyResult.Success("验证通过，后续启动不再联网验证")
            }
            is ProbeResult.AuthFailed -> {
                updateRecord(record.copy(verified = false))
                logger.log(
                    LogModule.KEY,
                    LogLevel.WARN,
                    "Key 验证失败：${record.label}",
                    "HTTP ${result.code}，检查 Key 是否过期、服务是否开启、配额是否耗尽"
                )
                syncKeyReady()
                KeyVerifyResult.Invalid("HTTP ${result.code}：Key 无效、已过期或配额耗尽")
            }
            is ProbeResult.NetworkError -> {
                logger.log(LogModule.KEY, LogLevel.WARN, "Key 验证网络失败：${record.label}", result.message)
                KeyVerifyResult.NetworkError("网络错误：${result.message}。已保留现有验证状态")
            }
        }
    }

    suspend fun markInvalid(id: String, reason: String) {
        mutex.withLock {
            val records = prefs.keys()
            val record = records.firstOrNull { it.id == id } ?: return@withLock
            if (!record.verified) return@withLock
            updateRecord(record.copy(verified = false))
            logger.log(LogModule.KEY, LogLevel.ERROR, "运行时 Key 失效：${record.label}", reason)
            syncKeyReady()
        }
    }

    suspend fun sessionKey(): SessionKey? = mutex.withLock {
        val records = prefs.keys().filter { it.verified }
        if (records.isEmpty()) return@withLock null
        val decrypted = records.mapNotNull { record ->
            decryptOrNull(record)?.let { record to it }
        }
        if (decrypted.isEmpty()) return@withLock null
        val usage = prefs.usageToday(decrypted.map { it.first.id })
        val (record, plain) = decrypted.minByOrNull { usage[it.first.id] ?: 0 } ?: return@withLock null
        SessionKey(record.id, record.label, plain, usage[record.id] ?: 0)
    }

    suspend fun recordTileUsage(keyId: String, count: Int) {
        if (count <= 0) return
        prefs.recordTileUsage(keyId, count, Formats.todayKey())
    }

    suspend fun reconcile() = mutex.withLock {
        val records = prefs.keys()
        var changed = false
        val reconciled = records.map { record ->
            if (record.verified && decryptOrNull(record) == null) {
                changed = true
                logger.log(
                    LogModule.KEY,
                    LogLevel.ERROR,
                    "Key 无法解密：${record.label}",
                    "Keystore 密钥可能已被系统清除，需要重新添加"
                )
                record.copy(verified = false)
            } else {
                record
            }
        }
        if (changed) prefs.writeKeys(reconciled)
        syncKeyReady()
    }

    suspend fun verifiedCount(): Int {
        val records = prefs.keys().filter { it.verified }
        return records.count { decryptOrNull(it) != null }
    }

    private suspend fun syncKeyReady() {
        val ready = prefs.keys().any { it.verified && decryptOrNull(it) != null }
        prefs.setKeyReady(ready)
    }

    private suspend fun updateRecord(updated: StoredKeyRecord) {
        val records = prefs.keys().map { if (it.id == updated.id) updated else it }
        prefs.writeKeys(records)
    }

    private fun decryptOrNull(record: StoredKeyRecord): String? =
        runCatching { cipher.decrypt(record.cipherText, record.iv) }.getOrNull()

    private fun StoredKeyRecord.toUi(): KeyUi {
        val plain = decryptOrNull(this)
        return KeyUi(
            id = id,
            label = label,
            masked = plain?.mask() ?: "无法解密",
            verified = verified && plain != null,
            createdAt = createdAt
        )
    }

    private fun String.mask(): String =
        if (length <= 8) "****" else take(4) + "****" + takeLast(4)

    private companion object {
        const val MAX_KEYS = 5
    }
}
