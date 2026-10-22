package com.tsyz.autoledger.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SQLCipher 口令由运行时随机生成（32 字节），
 * 再用 Android Keystore 中的 AES-256-GCM 密钥包裹后存入私有 SharedPreferences。
 * 口令明文与密钥均不硬编码、不进入备份、不写日志。
 */
object DatabaseKeyManager {
    private const val PREFS = "auto_ledger_secure"
    private const val WRAPPED_KEY = "wrapped_db_passphrase"
    private const val KEY_ALIAS = "auto_ledger_db_wrap_key"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun passphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(WRAPPED_KEY, null)
        if (stored != null) {
            return runCatching { unwrap(stored) }
                .getOrElse {
                    throw IllegalStateException("数据库密钥不可用，可能是 Keystore 被清除或系统还原导致。", it)
                }
        }
        // 口令使用 Base64 文本的 UTF-8 字节，保证 SupportFactory(byte[]) 与
        // SQLCipher openDatabase(String) 两条路径推导出的密钥完全一致。
        val generated = Base64.encodeToString(
            ByteArray(32).also { SecureRandom().nextBytes(it) },
            Base64.NO_WRAP
        ).toByteArray(Charsets.UTF_8)
        prefs.edit().putString(WRAPPED_KEY, wrap(generated)).commit()
        return generated
    }

    private fun wrap(passphrase: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(passphrase)
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): ByteArray {
        val combined = Base64.decode(stored, Base64.NO_WRAP)
        require(combined.size > IV_BYTES) { "invalid wrapped key" }
        val iv = combined.copyOfRange(0, IV_BYTES)
        val encrypted = combined.copyOfRange(IV_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
