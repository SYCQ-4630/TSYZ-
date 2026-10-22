package com.tsyz.autoledger.core.log

import com.tsyz.autoledger.core.util.Sanitizer
import com.tsyz.autoledger.data.db.LogDao
import com.tsyz.autoledger.data.db.LogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自建结构化日志：写入前脱敏，按大小滚动，保留期由维护任务控制。
 * 仅记录模块、时间、规则命中与字段摘要。
 */
class SafeLogger(
    private val dao: LogDao,
    private val scope: CoroutineScope,
    private val sessionId: String
) {
    private val maxEntries = 2_500
    private val keepAfterTrim = 2_000

    fun info(module: String, message: String) = write("INFO", module, message)
    fun warn(module: String, message: String) = write("WARN", module, message)
    fun error(module: String, message: String) = write("ERROR", module, message)

    private fun write(level: String, module: String, message: String) {
        val safeMessage = Sanitizer.sanitize(message)
        val safeModule = module.take(32)
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.insert(
                    LogEntity(
                        ts = System.currentTimeMillis(),
                        level = level,
                        module = safeModule,
                        message = safeMessage,
                        sessionId = sessionId
                    )
                )
                if (dao.count() > maxEntries) dao.trimTo(keepAfterTrim)
            }
        }
    }

    fun observeRecent(limit: Int = 500): Flow<List<LogEntity>> = dao.observeRecent(limit)

    fun observeByModule(module: String, limit: Int = 500): Flow<List<LogEntity>> =
        dao.observeByModule(module, limit)

    suspend fun queryForExport(from: Long, to: Long, module: String?, limit: Int = 5_000): List<LogEntity> =
        withContext(Dispatchers.IO) { dao.queryForExport(from, to, module, limit) }

    suspend fun cleanup(retentionDays: Int) = withContext(Dispatchers.IO) {
        dao.deleteOlderThan(System.currentTimeMillis() - retentionDays.coerceIn(7, 14) * 24L * 60L * 60L * 1000L)
        dao.trimTo(keepAfterTrim)
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }
}
