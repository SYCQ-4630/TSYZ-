package com.tsyz.autoledger.snapshot

import com.tsyz.autoledger.data.db.DraftDao
import com.tsyz.autoledger.data.db.UiDraftEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DraftWrite(val scene: String, val payloadJson: String, val schemaVersion: Int = 1)

/**
 * 未提交草稿保存在 Room ui_drafts。高频表单输入使用 450ms debounce 合并写入。
 */
class DraftManager(
    private val dao: DraftDao,
    private val scope: CoroutineScope
) {
    private val writes = MutableSharedFlow<DraftWrite>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @OptIn(FlowPreview::class)
    private val writer = scope.launch {
        writes.debounce(450).collect { write ->
            withContext(Dispatchers.IO) {
                dao.upsert(
                    UiDraftEntity(
                        scene = write.scene,
                        payloadJson = write.payloadJson,
                        updatedAt = System.currentTimeMillis(),
                        schemaVersion = write.schemaVersion,
                        consumed = false
                    )
                )
            }
        }
    }

    fun requestSave(scene: String, payloadJson: String) {
        writes.tryEmit(DraftWrite(scene, payloadJson))
    }

    suspend fun loadUnconsumed(): List<UiDraftEntity> = withContext(Dispatchers.IO) { dao.unconsumed() }

    suspend fun load(scene: String): UiDraftEntity? = withContext(Dispatchers.IO) { dao.byScene(scene) }

    suspend fun consume(scene: String) = withContext(Dispatchers.IO) {
        dao.consume(scene, System.currentTimeMillis())
        dao.deleteConsumed()
    }

    suspend fun cleanupExpired(olderThanMillis: Long = 7L * 24 * 60 * 60 * 1000) = withContext(Dispatchers.IO) {
        dao.deleteOlderThan(System.currentTimeMillis() - olderThanMillis)
        dao.deleteConsumed()
    }

    fun stop() {
        writer.cancel()
    }
}
