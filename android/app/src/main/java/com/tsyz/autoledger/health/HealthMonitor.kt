package com.tsyz.autoledger.health

import com.tsyz.autoledger.core.log.SafeLogger
import com.tsyz.autoledger.core.model.ChannelStatus
import com.tsyz.autoledger.data.db.HealthDao
import com.tsyz.autoledger.data.db.HealthHeartbeatEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HealthChannel(val label: String, val staleAfterMillis: Long) {
    ACCESSIBILITY("无障碍抓取", 48L * 60 * 60 * 1000),
    SMS("短信通道", 48L * 60 * 60 * 1000),
    NOTIFICATION("通知补充", 72L * 60 * 60 * 1000),
    OCR("截图 OCR", 72L * 60 * 60 * 1000),
    FOREGROUND("前台守护", 24L * 60 * 60 * 1000),
    WORKER("月报任务", 35L * 24 * 60 * 60 * 1000),
    APP("应用进程", 24L * 60 * 60 * 1000)
}

class HealthMonitor(
    private val dao: HealthDao,
    private val logger: SafeLogger,
    private val scope: CoroutineScope
) {
    fun beat(channel: HealthChannel, detail: String = "") {
        scope.launch(Dispatchers.IO) { beatNow(channel, detail) }
    }

    suspend fun beatNow(channel: HealthChannel, detail: String = "") = withContext(Dispatchers.IO) {
        dao.upsert(
            HealthHeartbeatEntity(
                channel = channel.name,
                lastBeatAt = System.currentTimeMillis(),
                detail = detail.take(120)
            )
        )
    }

    fun observe(): Flow<List<ChannelStatus>> = dao.observeAll().map { rows ->
        val now = System.currentTimeMillis()
        HealthChannel.entries.map { channel ->
            val row = rows.firstOrNull { it.channel == channel.name }
            ChannelStatus(
                channel = channel.name,
                label = channel.label,
                lastBeatAt = row?.lastBeatAt,
                healthy = row != null && now - row.lastBeatAt <= channel.staleAfterMillis,
                detail = row?.detail.orEmpty()
            )
        }
    }

    suspend fun statuses(): List<ChannelStatus> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val rows = dao.all()
        HealthChannel.entries.map { channel ->
            val row = rows.firstOrNull { it.channel == channel.name }
            ChannelStatus(
                channel = channel.name,
                label = channel.label,
                lastBeatAt = row?.lastBeatAt,
                healthy = row != null && now - row.lastBeatAt <= channel.staleAfterMillis,
                detail = row?.detail.orEmpty()
            )
        }
    }

    /** 心跳超期写日志，月报摘要中同样会体现。 */
    suspend fun checkStaleChannels(): List<ChannelStatus> {
        val stale = statuses().filter { !it.healthy }
        stale.forEach { logger.warn("HEALTH", "通道心跳超期: ${it.label}") }
        return stale
    }
}
