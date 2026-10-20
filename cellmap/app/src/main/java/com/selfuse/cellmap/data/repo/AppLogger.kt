package com.selfuse.cellmap.data.repo

import android.util.Log
import com.selfuse.cellmap.data.db.AppDatabase
import com.selfuse.cellmap.data.db.AppLogEntity
import com.selfuse.cellmap.data.db.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppLogger(
    private val db: AppDatabase,
    private val scope: CoroutineScope
) {
    suspend fun log(module: String, level: String, message: String, detail: String? = null) {
        withContext(Dispatchers.IO) {
            runCatching {
                db.logDao().insert(
                    AppLogEntity(
                        ts = System.currentTimeMillis(),
                        module = module,
                        level = level,
                        message = message.take(300),
                        detail = detail?.take(2000)
                    )
                )
            }.onFailure { Log.e("CellMap", "write log failed", it) }
        }
        if (level == LogLevel.ERROR) Log.e("CellMap[$module]", message)
    }

    fun logAsync(module: String, level: String, message: String, detail: String? = null) {
        scope.launch { log(module, level, message, detail) }
    }

    suspend fun prune(keep: Int = 5000) {
        withContext(Dispatchers.IO) {
            runCatching { db.logDao().prune(keep) }
        }
    }
}
