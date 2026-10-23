package com.tsyz.autoledger

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.tsyz.autoledger.capture.ForegroundGuardService
import com.tsyz.autoledger.capture.importer.BillImporter
import com.tsyz.autoledger.core.log.SafeLogger
import com.tsyz.autoledger.data.db.DatabaseProvider
import com.tsyz.autoledger.data.prefs.SettingsStore
import com.tsyz.autoledger.data.prefs.StoredSnapshot
import com.tsyz.autoledger.data.repo.LedgerRepository
import com.tsyz.autoledger.decision.IngestionPipeline
import com.tsyz.autoledger.decision.ReconciliationManager
import com.tsyz.autoledger.decision.ReparseManager
import com.tsyz.autoledger.health.HealthChannel
import com.tsyz.autoledger.health.HealthMonitor
import com.tsyz.autoledger.notification.Notifications
import com.tsyz.autoledger.report.MonthlyReportGenerator
import com.tsyz.autoledger.rules.RuleRepository
import com.tsyz.autoledger.schedule.MaintenanceScheduler
import com.tsyz.autoledger.schedule.MonthlyReportScheduler
import com.tsyz.autoledger.snapshot.DraftManager
import com.tsyz.autoledger.snapshot.SnapshotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.UUID

/**
 * 手工依赖容器：按输入通道层、解析层、入账决策层、持久层、呈现层组装。
 * 所有跨层依赖均通过构造参数注入，便于替换实现。
 */
class AppContainer(val app: Application) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app)
    val scheduler = MonthlyReportScheduler(app)
    val maintenance = MaintenanceScheduler(app)

    private val sessionId = UUID.randomUUID().toString().take(8)

    val db by lazy { DatabaseProvider.get(app) }

    val logger by lazy { SafeLogger(db.logDao(), appScope, sessionId) }
    val health by lazy { HealthMonitor(app, settings, db.healthDao(), logger, appScope) }
    val rules by lazy { RuleRepository(app, db.ruleDao(), logger) }
    val pipeline by lazy {
        IngestionPipeline(db, rules, settings, logger, health)
    }
    val reconciliation by lazy {
        ReconciliationManager(db, rules, pipeline, logger)
    }
    val reparse by lazy {
        ReparseManager(db.rawCaptureDao(), rules, pipeline, logger)
    }
    val reportGenerator by lazy {
        MonthlyReportGenerator(app, db, settings, health, reconciliation, logger)
    }
    val ledger by lazy { LedgerRepository(db, logger) }
    val importer by lazy { BillImporter(app, pipeline, logger) }
    val drafts by lazy { DraftManager(db.draftDao(), appScope) }
    val snapshots by lazy { SnapshotManager(settings, logger, appScope) }

    @Volatile
    var sessionStartedAt: Long = 0L
        private set

    private var restoreCandidate: StoredSnapshot? = null
    private var restoreConsumed = false

    fun start() {
        val decision = runBlocking(Dispatchers.IO) { settings.beginSession() }
        restoreCandidate = decision.restoreCandidate
        sessionStartedAt = decision.newSessionStartedAt

        Timber.plant(com.tsyz.autoledger.core.log.DbLogTree(logger))
        Notifications.ensureChannels(app)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                appScope.launch(Dispatchers.IO) {
                    runCatching { snapshots.flush() }
                    settings.markCleanExit()
                }
            }
        })

        appScope.launch(Dispatchers.IO) {
            runCatching {
                health.beatNow(HealthChannel.APP, "process start")
                rules.ensureSeeded()
                val current = settings.current()
                scheduler.apply(current)
                if (current.monthlyReportEnabled) {
                    health.beatNow(HealthChannel.WORKER, "scheduled")
                }
                maintenance.schedule()
                if (current.foregroundServiceEnabled) {
                    ForegroundGuardService.start(app)
                }
                health.checkStaleChannels()
                logger.info("APP", "进程启动 session=$sessionId restore=${decision.restoreCandidate != null}")
            }.onFailure {
                Timber.e(it, "startup failed")
            }
        }
    }

    /** 只在本次进程首次启动时返回一次恢复候选，避免 Activity 重建重复恢复。 */
    fun consumeRestoreCandidate(): StoredSnapshot? = synchronized(this) {
        if (restoreConsumed) return null
        restoreConsumed = true
        restoreCandidate
    }

    fun currentSessionId(): String = sessionId
}
