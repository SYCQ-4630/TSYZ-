package com.tsyz.autoledger.core.util

import com.tsyz.autoledger.core.model.SourceType
import com.tsyz.autoledger.core.model.TxType
import java.util.Locale

/**
 * 指纹与去重键。
 *
 * - ofRaw 用于 raw_captures 的原始捕获去重，避免同一屏幕重复写入。
 * - dedupeKey 用于入账幂等：金额 + 收支类型 + 商户 + 1 分钟时间桶。
 *   跨通道时比较金额与时间窗口，能合并微信无障碍与银行短信的同一笔支付。
 */
object Fingerprint {
    fun ofRaw(sourceType: SourceType, packageName: String, text: String, timestamp: Long): String {
        val normalized = text.replace(Regex("\\s+"), "").take(4000)
        return Hashing.sha256("raw|${sourceType.name}|$packageName|${timestamp / 5_000}|$normalized")
    }

    fun dedupeKey(amountCents: Long, type: TxType, merchant: String, occurredAt: Long): String {
        val normalizedMerchant = normalizeMerchant(merchant)
        return Hashing.sha256("tx|$amountCents|${type.name}|$normalizedMerchant|${occurredAt / 60_000}")
    }

    fun normalizeMerchant(merchant: String): String {
        return merchant.lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Punct}，。、「」【】（）]+"), "")
            .take(32)
    }
}
