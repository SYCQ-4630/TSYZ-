package com.tsyz.autoledger.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentTextParserTest {

    @Test
    fun `extracts yuan amount from payment result`() {
        val text = "支付成功\n付款金额\n¥123.45"
        assertEquals(12345L, PaymentTextParser.extractAmountCents(text))
    }

    @Test
    fun `ignores balance lines and picks the transaction amount`() {
        val text = "余额：¥2,000.00\n支付金额 ¥88.60"
        assertEquals(8860L, PaymentTextParser.extractAmountCents(text))
    }

    @Test
    fun `extracts merchant after label`() {
        assertEquals("美团外卖", PaymentTextParser.extractMerchant("支付成功\n收款方：美团外卖"))
    }

    @Test
    fun `rejects blank or unrelated text`() {
        assertFalse(PaymentTextParser.looksLikePayment("系统设置"))
        assertFalse(PaymentTextParser.looksLikePayment(""))
        assertNull(PaymentTextParser.extractAmountCents("没有金额"))
    }

    @Test
    fun `detects successful and failed results`() {
        assertTrue(PaymentTextParser.isSuspectedPaymentResult("支付成功 ¥12.00"))
        assertFalse(PaymentTextParser.isSuspectedPaymentResult("支付失败 余额不足 ¥12.00"))
        assertFalse(PaymentTextParser.isSuspectedPaymentResult("支付成功"))
    }

    @Test
    fun `title is first non blank line`() {
        assertEquals("支付成功", PaymentTextParser.titleOf("\n\n支付成功\n¥1.00"))
    }
}
