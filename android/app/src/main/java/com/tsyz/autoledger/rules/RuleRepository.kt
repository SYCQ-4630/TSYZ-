package com.tsyz.autoledger.rules

import android.content.Context
import com.tsyz.autoledger.core.log.SafeLogger
import com.tsyz.autoledger.data.db.RuleDao
import com.tsyz.autoledger.data.db.RuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 规则带版本号，存于加密数据库。资产中的 rules.json 作为初始版本；
 * 导入新规则后清空规则缓存，并可对近期解析失败的 raw_captures 回溯重解析。
 */
class RuleRepository(
    private val context: Context,
    private val dao: RuleDao,
    private val logger: SafeLogger
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: RuleEngine? = null

    suspend fun engine(): RuleEngine {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: loadEngine().also { cached = it }
        }
    }

    suspend fun ensureSeeded() {
        mutex.withLock {
            if (dao.count() == 0) seedFromAssets()
            cached = null
        }
    }

    suspend fun currentVersion(): Int = withContext(Dispatchers.IO) {
        dao.maxVersion() ?: 0
    }

    suspend fun accessibilityPackages(): Set<String> = engine().accessibilityPackages()

    suspend fun ruleCount(): Int = withContext(Dispatchers.IO) { dao.count() }

    suspend fun importJson(json: String): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(json)
        val version = root.optInt("version", 1)
        val array = root.optJSONArray("rules") ?: throw IllegalArgumentException("rules 数组缺失")
        val entities = (0 until array.length()).mapNotNull { index ->
            val ruleJson = array.optJSONObject(index) ?: return@mapNotNull null
            val id = ruleJson.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RuleEntity(
                id = id,
                version = version,
                json = ruleJson.toString(),
                active = true,
                updatedAt = System.currentTimeMillis()
            )
        }
        require(entities.isNotEmpty()) { "没有可用规则" }
        dao.replaceAll(entities)
        cached = null
        logger.info("RULES", "规则导入成功，version=$version count=${entities.size}")
        entities.size
    }

    suspend fun reload() {
        mutex.withLock { cached = null }
    }

    private suspend fun seedFromAssets() {
        withContext(Dispatchers.IO) {
            val json = context.assets.open("rules.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val version = root.optInt("version", 1)
            val array = root.optJSONArray("rules") ?: return@withContext
            val entities = (0 until array.length()).mapNotNull { index ->
                val ruleJson = array.optJSONObject(index) ?: return@mapNotNull null
                val id = ruleJson.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RuleEntity(
                    id = id,
                    version = version,
                    json = ruleJson.toString(),
                    active = true,
                    updatedAt = System.currentTimeMillis()
                )
            }
            dao.replaceAll(entities)
            logger.info("RULES", "已从资产载入规则 version=$version count=${entities.size}")
        }
    }

    private suspend fun loadEngine(): RuleEngine = withContext(Dispatchers.IO) {
        val rows = dao.activeRules()
        if (rows.isEmpty()) {
            seedFromAssets()
            RuleEngine(RuleSet.fromStoredRules(dao.activeRules().map { it.version to it.json }))
        } else {
            RuleEngine(RuleSet.fromStoredRules(rows.map { it.version to it.json }))
        }
    }
}
