package com.tsyz.autoledger.health

import com.tsyz.autoledger.core.model.ChannelState
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthEvaluatorTest {

    private val now = 1_700_000_000_000L
    private val staleAfter = 48L * 60 * 60 * 1000

    @Test
    fun `disabled channel is not treated as stale`() {
        assertEquals(
            ChannelState.DISABLED,
            HealthEvaluator.evaluate(false, null, now, staleAfter, eventDriven = false)
        )
    }

    @Test
    fun `enabled channel without heartbeat waits for first trigger`() {
        assertEquals(
            ChannelState.IDLE,
            HealthEvaluator.evaluate(true, null, now, staleAfter, eventDriven = false)
        )
    }

    @Test
    fun `live channel becomes stale after threshold`() {
        assertEquals(
            ChannelState.STALE,
            HealthEvaluator.evaluate(true, now - staleAfter - 1, now, staleAfter, eventDriven = false)
        )
    }

    @Test
    fun `event driven channel stays idle when no event arrives`() {
        assertEquals(
            ChannelState.IDLE,
            HealthEvaluator.evaluate(true, now - staleAfter * 10, now, staleAfter, eventDriven = true)
        )
    }

    @Test
    fun `recent heartbeat is healthy`() {
        assertEquals(
            ChannelState.OK,
            HealthEvaluator.evaluate(true, now - 60_000L, now, staleAfter, eventDriven = false)
        )
    }
}
