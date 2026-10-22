package com.tsyz.autoledger.snapshot

import com.tsyz.autoledger.core.log.SafeLogger
import com.tsyz.autoledger.data.prefs.SettingsStore
import com.tsyz.autoledger.data.prefs.StoredSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI 状态快照：仅保存界面状态与筛选条件，不保存已入账流水。
 * 写入经过 400ms debounce，序列化在 IO 线程执行。
 */
class SnapshotManager(
    private val settings: SettingsStore,
    private val logger: SafeLogger,
    private val scope: CoroutineScope
) {
    private val requests = MutableSharedFlow<UiSnapshot>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Volatile
    private var lastSnapshot: UiSnapshot? = null

    @OptIn(FlowPreview::class)
    private val saver = scope.launch {
        requests.debounce(400).collect { snapshot ->
            writeSnapshot(snapshot)
        }
    }

    fun requestSave(snapshot: UiSnapshot) {
        lastSnapshot = snapshot
        requests.tryEmit(snapshot)
    }

    /** 退后台或进程即将被回收时立即落盘，跳过 debounce。 */
    suspend fun flush() {
        val snapshot = lastSnapshot ?: return
        writeSnapshot(snapshot)
    }

    private suspend fun writeSnapshot(snapshot: UiSnapshot) {
        val stamped = snapshot.copy(savedAt = System.currentTimeMillis())
        runCatching {
            withContext(Dispatchers.IO) {
                val json = SnapshotCodec.encode(stamped)
                settings.saveSnapshot(json, SnapshotCodec.SCHEMA_VERSION, stamped.savedAt)
            }
        }.onFailure { logger.warn("SNAPSHOT", "保存界面快照失败: ${it.javaClass.simpleName}") }
    }

    /**
     * 启动恢复入口。超过 24 小时、版本不匹配、反序列化失败都会丢弃并返回 null。
     */
    suspend fun restore(stored: StoredSnapshot?): UiSnapshot? {
        if (stored == null) return null
        val age = System.currentTimeMillis() - stored.savedAt
        if (stored.savedAt <= 0 || age > SnapshotCodec.MAX_AGE_MILLIS) {
            logger.info("SNAPSHOT", "快照超过 24 小时，已丢弃")
            settings.clearSnapshot()
            return null
        }
        if (stored.schemaVersion != SnapshotCodec.SCHEMA_VERSION) {
            logger.warn("SNAPSHOT", "快照 schema 版本不匹配，已丢弃")
            settings.clearSnapshot()
            return null
        }
        val decoded = SnapshotCodec.decode(stored.json, stored.schemaVersion)
        if (decoded == null) {
            logger.warn("SNAPSHOT", "快照反序列化失败，回默认首页")
            settings.clearSnapshot()
            return null
        }
        return decoded
    }

    fun stop() {
        saver.cancel()
    }
}
