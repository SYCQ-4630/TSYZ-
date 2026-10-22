package com.tsyz.autoledger.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tsyz.autoledger.snapshot.SnapshotCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "auto_ledger_settings")

data class AppSettings(
    val ocrEnabled: Boolean = true,
    val notificationCaptureEnabled: Boolean = false,
    val monthlyReportEnabled: Boolean = true,
    val foregroundServiceEnabled: Boolean = true,
    val autoPostThreshold: Float = 0.80f,
    val logRetentionDays: Int = 14,
    val onboardingDone: Boolean = false,
    val cleanExit: Boolean = true,
    val sessionStartedAt: Long = 0L,
    val snapshotJson: String? = null,
    val snapshotSavedAt: Long = 0L,
    val snapshotSchemaVersion: Int = 0,
    val lastReportJson: String? = null,
    val lastReportMonth: String? = null,
    val lastReportGeneratedAt: Long = 0L
)

data class StoredSnapshot(
    val json: String,
    val savedAt: Long,
    val schemaVersion: Int
)

data class SessionDecision(
    val restoreCandidate: StoredSnapshot?,
    val newSessionStartedAt: Long
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val OCR = booleanPreferencesKey("ocr_enabled")
        val NOTIFICATION = booleanPreferencesKey("notification_capture_enabled")
        val MONTHLY_REPORT = booleanPreferencesKey("monthly_report_enabled")
        val FOREGROUND = booleanPreferencesKey("foreground_service_enabled")
        val THRESHOLD = floatPreferencesKey("auto_post_threshold")
        val RETENTION = intPreferencesKey("log_retention_days")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val CLEAN_EXIT = booleanPreferencesKey("clean_exit")
        val SESSION_STARTED = longPreferencesKey("session_started_at")
        val SNAPSHOT_JSON = stringPreferencesKey("snapshot_json")
        val SNAPSHOT_SAVED = longPreferencesKey("snapshot_saved_at")
        val SNAPSHOT_VERSION = intPreferencesKey("snapshot_schema_version")
        val REPORT_JSON = stringPreferencesKey("last_report_json")
        val REPORT_MONTH = stringPreferencesKey("last_report_month")
        val REPORT_AT = longPreferencesKey("last_report_generated_at")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            AppSettings(
                ocrEnabled = prefs[Keys.OCR] ?: true,
                notificationCaptureEnabled = prefs[Keys.NOTIFICATION] ?: false,
                monthlyReportEnabled = prefs[Keys.MONTHLY_REPORT] ?: true,
                foregroundServiceEnabled = prefs[Keys.FOREGROUND] ?: true,
                autoPostThreshold = prefs[Keys.THRESHOLD] ?: 0.80f,
                logRetentionDays = prefs[Keys.RETENTION] ?: 14,
                onboardingDone = prefs[Keys.ONBOARDING] ?: false,
                cleanExit = prefs[Keys.CLEAN_EXIT] ?: true,
                sessionStartedAt = prefs[Keys.SESSION_STARTED] ?: 0L,
                snapshotJson = prefs[Keys.SNAPSHOT_JSON],
                snapshotSavedAt = prefs[Keys.SNAPSHOT_SAVED] ?: 0L,
                snapshotSchemaVersion = prefs[Keys.SNAPSHOT_VERSION] ?: 0,
                lastReportJson = prefs[Keys.REPORT_JSON],
                lastReportMonth = prefs[Keys.REPORT_MONTH],
                lastReportGeneratedAt = prefs[Keys.REPORT_AT] ?: 0L
            )
        }

    suspend fun current(): AppSettings = settings.first()

    suspend fun beginSession(now: Long = System.currentTimeMillis()): SessionDecision {
        var candidate: StoredSnapshot? = null
        var newStartedAt = now
        context.settingsDataStore.edit { prefs ->
            val clean = prefs[Keys.CLEAN_EXIT] ?: true
            val startedAt = prefs[Keys.SESSION_STARTED] ?: 0L
            val json = prefs[Keys.SNAPSHOT_JSON]
            val savedAt = prefs[Keys.SNAPSHOT_SAVED] ?: 0L
            val version = prefs[Keys.SNAPSHOT_VERSION] ?: 0
            val withinWindow = startedAt > 0 && now > startedAt && now - startedAt <= SnapshotCodec.MAX_AGE_MILLIS
            if (!clean && withinWindow && !json.isNullOrBlank() && version == SnapshotCodec.SCHEMA_VERSION) {
                candidate = StoredSnapshot(json, savedAt, version)
            }
            prefs[Keys.CLEAN_EXIT] = false
            prefs[Keys.SESSION_STARTED] = now
            newStartedAt = now
        }
        return SessionDecision(candidate, newStartedAt)
    }

    suspend fun markCleanExit() {
        context.settingsDataStore.edit { it[Keys.CLEAN_EXIT] = true }
    }

    suspend fun setOcrEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.OCR] = enabled }

    suspend fun setNotificationCaptureEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.NOTIFICATION] = enabled }

    suspend fun setMonthlyReportEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.MONTHLY_REPORT] = enabled }

    suspend fun setForegroundServiceEnabled(enabled: Boolean) =
        context.settingsDataStore.edit { it[Keys.FOREGROUND] = enabled }

    suspend fun setAutoPostThreshold(value: Float) =
        context.settingsDataStore.edit { it[Keys.THRESHOLD] = value.coerceIn(0.5f, 0.98f) }

    suspend fun setLogRetentionDays(days: Int) =
        context.settingsDataStore.edit { it[Keys.RETENTION] = days.coerceIn(7, 14) }

    suspend fun setOnboardingDone(done: Boolean) =
        context.settingsDataStore.edit { it[Keys.ONBOARDING] = done }

    suspend fun saveSnapshot(json: String, version: Int, savedAt: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SNAPSHOT_JSON] = json
            prefs[Keys.SNAPSHOT_VERSION] = version
            prefs[Keys.SNAPSHOT_SAVED] = savedAt
        }
    }

    suspend fun clearSnapshot() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.SNAPSHOT_JSON)
            prefs.remove(Keys.SNAPSHOT_SAVED)
            prefs.remove(Keys.SNAPSHOT_VERSION)
        }
    }

    suspend fun saveReport(json: String, month: String, generatedAt: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.REPORT_JSON] = json
            prefs[Keys.REPORT_MONTH] = month
            prefs[Keys.REPORT_AT] = generatedAt
        }
    }
}
