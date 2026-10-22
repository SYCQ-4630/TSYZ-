package com.tsyz.autoledger.core.log

import android.util.Log
import timber.log.Timber

/** Timber 封装：所有日志进入脱敏日志表，不打印原文到 logcat。 */
class DbLogTree(private val logger: SafeLogger) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val module = tag?.take(32) ?: "timber"
        val payload = buildString {
            append(message)
            if (t != null) append(" | ").append(t.javaClass.simpleName)
        }
        when (priority) {
            Log.WARN -> logger.warn(module, payload)
            Log.ERROR, Log.ASSERT -> logger.error(module, payload)
            else -> logger.info(module, payload)
        }
    }
}
