package com.tsyz.autoledger.core.util

import java.security.MessageDigest

object Hashing {
    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun short(value: String, length: Int = 8): String = sha256(value).take(length)
}
