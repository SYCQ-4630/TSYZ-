package com.tsyz.autoledger.core.util

import com.tsyz.autoledger.core.model.SourceType
import com.tsyz.autoledger.core.model.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FingerprintTest {

    @Test
    fun `dedupe key is stable within the same minute`() {
        val base = 1_700_000_000_000L
        val first = Fingerprint.dedupeKey(1250L, TxType.EXPENSE, "星巴克", base)
        val second = Fingerprint.dedupeKey(1250L, TxType.EXPENSE, "星巴克", base + 20_000L)
        assertEquals(first, second)
    }

    @Test
    fun `dedupe key changes across minutes and amounts`() {
        val base = 1_700_000_000_000L
        val first = Fingerprint.dedupeKey(1250L, TxType.EXPENSE, "星巴克", base)
        val nextMinute = Fingerprint.dedupeKey(1250L, TxType.EXPENSE, "星巴克", base + 61_000L)
        val otherAmount = Fingerprint.dedupeKey(1251L, TxType.EXPENSE, "星巴克", base)
        assertNotEquals(first, nextMinute)
        assertNotEquals(first, otherAmount)
    }

    @Test
    fun `merchant normalization strips spaces punctuation and case`() {
        assertEquals("starbucks咖啡", Fingerprint.normalizeMerchant(" StarBucks， 咖啡 "))
    }

    @Test
    fun `raw fingerprint groups same text in same five second bucket`() {
        val base = 1_700_000_000_000L
        val first = Fingerprint.ofRaw(SourceType.ACCESSIBILITY, "com.tencent.mm", "支付 成功 ¥1.00", base)
        val sameBucket = Fingerprint.ofRaw(SourceType.ACCESSIBILITY, "com.tencent.mm", "支付成功 ¥1.00", base + 1_000L)
        val otherSource = Fingerprint.ofRaw(SourceType.SMS, "com.tencent.mm", "支付 成功 ¥1.00", base)
        assertEquals(first, sameBucket)
        assertNotEquals(first, otherSource)
    }
}
