package com.tsyz.autoledger.health

import com.tsyz.autoledger.core.model.ChannelState

/**
 * 纯函数健康判定：把“是否启用”“是否从未触发”“是否超期”分开，
 * 事件驱动通道长时间没有新事件只算空闲，不算故障。
 */
object HealthEvaluator {
    fun evaluate(
        expected: Boolean,
        lastBeatAt: Long?,
        now: Long,
        staleAfterMillis: Long,
        eventDriven: Boolean
    ): ChannelState {
        if (!expected) return ChannelState.DISABLED
        if (lastBeatAt == null) return ChannelState.IDLE
        val expired = now - lastBeatAt > staleAfterMillis
        return when {
            !expired -> ChannelState.OK
            eventDriven -> ChannelState.IDLE
            else -> ChannelState.STALE
        }
    }
}
