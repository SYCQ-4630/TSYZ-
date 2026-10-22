package com.tsyz.autoledger.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotCodecTest {

    @Test
    fun `round trips ui state`() {
        val snapshot = UiSnapshot(
            tabIndex = 2,
            screenStack = listOf("manual"),
            range = "LAST_MONTH",
            category = "餐饮",
            typeFilter = "EXPENSE",
            chartType = "PIE",
            transactionScroll = 12,
            reportScroll = 3,
            trendScroll = 1,
            bulkMode = true,
            bulkSelection = listOf(4L, 9L),
            noteEditingTxId = 7L,
            savedAt = 1_700_000_000_000L
        )

        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))

        assertEquals(snapshot.copy(savedAt = 0L), decoded?.copy(savedAt = 0L))
    }

    @Test
    fun `rejects mismatched schema version`() {
        val json = SnapshotCodec.encode(UiSnapshot())
        assertNull(SnapshotCodec.decode(json, storedVersion = 99))
    }

    @Test
    fun `rejects corrupted json`() {
        assertNull(SnapshotCodec.decode("{not-json"))
    }
}
