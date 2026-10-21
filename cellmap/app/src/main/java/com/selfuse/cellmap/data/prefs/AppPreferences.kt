package com.selfuse.cellmap.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.selfuse.cellmap.core.model.LabelStats
import com.selfuse.cellmap.core.model.ReadinessState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.cellMapDataStore by preferencesDataStore(name = "cellmap_prefs")

@Serializable
data class StoredKeyRecord(
    val id: String,
    val label: String,
    val cipherText: String,
    val iv: String,
    val verified: Boolean,
    val createdAt: Long,
    val lastVerifiedAt: Long = 0L
)

@Serializable
data class UsageEntry(val date: String = "", val count: Int = 0)

@Serializable
data class ImportSpec(
    val filePath: String,
    val fileName: String,
    val sheetName: String,
    val headerRow: Int,
    val totalRows: Int,
    val batchId: Long = 0L,
    val mapping: Map<String, Int> = emptyMap()
)

@Serializable
data class LabelRunState(
    val total: Int = 0,
    val classified: Int = 0,
    val unclassified: Int = 0,
    val unicomShared: Int = 0,
    val telecomShared: Int = 0,
    val unicomPrivate: Int = 0,
    val telecomPrivate: Int = 0,
    val completedAt: Long = 0L
) {
    fun toStats() = LabelStats(
        total = total,
        classified = classified,
        unclassified = unclassified,
        unicomShared = unicomShared,
        telecomShared = telecomShared,
        unicomPrivate = unicomPrivate,
        telecomPrivate = telecomPrivate,
        completedAt = completedAt
    )
}

class AppPreferences(context: Context) {

    private val store = context.applicationContext.cellMapDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val readinessFlow: Flow<ReadinessState> = store.data.map { data ->
        ReadinessState(
            keyReady = data[KEY_READY] ?: false,
            dataReady = data[DATA_READY] ?: false,
            labelReady = (data[LABEL_RUN_COMPLETED] ?: false) && (data[LABEL_CONFIRMED] ?: false),
            optimizeReady = data[OPTIMIZE_READY] ?: false
        )
    }.distinctUntilChanged()

    val labelStatsFlow: Flow<LabelStats> = store.data
        .map { it[LABEL_STATS] }
        .map { raw -> raw?.let { runCatching { json.decodeFromString<LabelRunState>(it).toStats() }.getOrNull() } ?: LabelStats() }
        .distinctUntilChanged()

    val keysFlow: Flow<List<StoredKeyRecord>> = store.data
        .map { it[KEYS_JSON] }
        .map { raw -> raw?.let { runCatching { json.decodeFromString<List<StoredKeyRecord>>(it) }.getOrNull() } ?: emptyList() }
        .distinctUntilChanged()

    val usageFlow: Flow<Map<String, UsageEntry>> = store.data
        .map { it[USAGE_JSON] }
        .map { raw -> raw?.let { runCatching { json.decodeFromString<Map<String, UsageEntry>>(it) }.getOrNull() } ?: emptyMap() }
        .distinctUntilChanged()

    val importSpecFlow: Flow<ImportSpec?> = store.data
        .map { it[IMPORT_SPEC_JSON] }
        .map { raw -> raw?.let { runCatching { json.decodeFromString<ImportSpec>(it) }.getOrNull() } }
        .distinctUntilChanged()

    val dataVersionFlow: Flow<Int> = store.data.map { it[DATA_VERSION] ?: 0 }.distinctUntilChanged()

    /**
     * 兼容渲染开关：容器或远程渲染环境里 SurfaceView 可能黑屏，开启后改用纹理模式。
     */
    val mapTextureModeFlow: Flow<Boolean> = store.data
        .map { it[MAP_TEXTURE_MODE] ?: false }
        .distinctUntilChanged()

    suspend fun readiness(): ReadinessState = readinessFlow.first()
    suspend fun keys(): List<StoredKeyRecord> = keysFlow.first()
    suspend fun importSpec(): ImportSpec? = importSpecFlow.first()
    suspend fun dataVersion(): Int = dataVersionFlow.first()

    suspend fun setMapTextureMode(enabled: Boolean) {
        store.edit { it[MAP_TEXTURE_MODE] = enabled }
    }

    suspend fun setKeyReady(value: Boolean) {
        store.edit { it[KEY_READY] = value }
    }

    suspend fun setDataReady(value: Boolean) {
        store.edit { it[DATA_READY] = value }
    }

    suspend fun setOptimizeReady(value: Boolean) {
        store.edit { it[OPTIMIZE_READY] = value }
    }

    suspend fun setLabelFlags(runCompleted: Boolean, confirmed: Boolean) {
        store.edit {
            it[LABEL_RUN_COMPLETED] = runCompleted
            it[LABEL_CONFIRMED] = confirmed
        }
    }

    suspend fun setLabelStats(state: LabelRunState) {
        store.edit { it[LABEL_STATS] = json.encodeToString(state) }
    }

    suspend fun confirmLabels() {
        store.edit { it[LABEL_CONFIRMED] = true }
    }

    suspend fun resetLabels() {
        store.edit {
            it[LABEL_RUN_COMPLETED] = false
            it[LABEL_CONFIRMED] = false
            it.remove(LABEL_STATS)
        }
    }

    suspend fun writeKeys(keys: List<StoredKeyRecord>) {
        store.edit { it[KEYS_JSON] = json.encodeToString(keys) }
    }

    suspend fun writeImportSpec(spec: ImportSpec?) {
        store.edit {
            if (spec == null) it.remove(IMPORT_SPEC_JSON) else it[IMPORT_SPEC_JSON] = json.encodeToString(spec)
        }
    }

    suspend fun updateImportBatchId(batchId: Long) {
        val spec = importSpec() ?: return
        writeImportSpec(spec.copy(batchId = batchId))
    }

    suspend fun recordTileUsage(keyId: String, count: Int, date: String) {
        store.edit { data ->
            val current = data[USAGE_JSON]?.let {
                runCatching { json.decodeFromString<Map<String, UsageEntry>>(it) }.getOrNull()
            } ?: emptyMap()
            val previous = current[keyId]
            val next = if (previous == null || previous.date != date) {
                UsageEntry(date = date, count = count.coerceAtLeast(0))
            } else {
                previous.copy(count = previous.count + count.coerceAtLeast(0))
            }
            data[USAGE_JSON] = json.encodeToString(current + (keyId to next))
        }
    }

    suspend fun usageToday(ids: Collection<String>): Map<String, Int> {
        val today = com.selfuse.cellmap.core.util.Formats.todayKey()
        val all = usageFlow.first()
        return ids.associateWith { id -> all[id]?.takeIf { it.date == today }?.count ?: 0 }
    }

    suspend fun bumpDataVersion(): Int {
        var version = 0
        store.edit {
            version = (it[DATA_VERSION] ?: 0) + 1
            it[DATA_VERSION] = version
        }
        return version
    }

    suspend fun lastOptimizationVersion(): Int = store.data.first()[OPTIMIZATION_VERSION] ?: -1

    suspend fun markOptimizationVersion(version: Int) {
        store.edit { it[OPTIMIZATION_VERSION] = version }
    }

    suspend fun setImportedAt(timestamp: Long) {
        store.edit { it[LAST_IMPORTED_AT] = timestamp }
    }

    suspend fun lastImportedAt(): Long = store.data.first()[LAST_IMPORTED_AT] ?: 0L

    /**
     * 原子领取当天首次刷新：同一天只允许一个调用方拿到 true。
     */
    suspend fun claimDailyRefresh(date: String): Boolean {
        var claimed = false
        store.edit { data ->
            if (data[DAILY_REFRESH_DATE] != date) {
                data[DAILY_REFRESH_DATE] = date
                claimed = true
            }
        }
        return claimed
    }

    private companion object {
        val KEY_READY = booleanPreferencesKey("key_ready")
        val DATA_READY = booleanPreferencesKey("data_ready")
        val LABEL_RUN_COMPLETED = booleanPreferencesKey("label_run_completed")
        val LABEL_CONFIRMED = booleanPreferencesKey("label_confirmed")
        val OPTIMIZE_READY = booleanPreferencesKey("optimize_ready")
        val LABEL_STATS = stringPreferencesKey("label_stats")
        val KEYS_JSON = stringPreferencesKey("keys_json")
        val USAGE_JSON = stringPreferencesKey("usage_json")
        val IMPORT_SPEC_JSON = stringPreferencesKey("import_spec_json")
        val DATA_VERSION = intPreferencesKey("data_version")
        val OPTIMIZATION_VERSION = intPreferencesKey("optimization_version")
        val LAST_IMPORTED_AT = longPreferencesKey("last_imported_at")
        val DAILY_REFRESH_DATE = stringPreferencesKey("daily_refresh_date")
        val MAP_TEXTURE_MODE = booleanPreferencesKey("map_texture_mode")
    }
}
