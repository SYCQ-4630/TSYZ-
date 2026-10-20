package com.selfuse.cellmap.data.repo

import com.selfuse.cellmap.core.model.ReadinessState
import com.selfuse.cellmap.data.db.AppDatabase
import com.selfuse.cellmap.data.db.LogLevel
import com.selfuse.cellmap.data.db.LogModule
import com.selfuse.cellmap.data.prefs.AppPreferences
import com.selfuse.cellmap.data.prefs.LabelRunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ReadinessRepository(
    private val prefs: AppPreferences,
    private val db: AppDatabase,
    private val logger: AppLogger,
    scope: CoroutineScope
) {
    val state: StateFlow<ReadinessState> = prefs.readinessFlow.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ReadinessState()
    )

    suspend fun dataVersion(): Int = prefs.dataVersion()

    /**
     * 导入新增数据、删除表、清空数据后调用：dataVersion 自增，旧标注与优化结果全部失效。
     */
    suspend fun onDataMutated(hasData: Boolean) {
        val version = prefs.bumpDataVersion()
        prefs.setDataReady(hasData)
        prefs.setOptimizeReady(false)
        prefs.resetLabels()
        prefs.markOptimizationVersion(-1)
        logger.log(
            LogModule.DATA,
            LogLevel.INFO,
            "数据版本更新为 v$version",
            if (hasData) "标注与优化状态已重置" else "当前没有基站数据"
        )
    }

    suspend fun setDataReadyWithoutInvalidating(hasData: Boolean) {
        prefs.setDataReady(hasData)
    }

    suspend fun beginLabelRun() {
        prefs.setLabelFlags(runCompleted = false, confirmed = false)
        prefs.setOptimizeReady(false)
    }

    suspend fun markLabelRunCompleted(run: LabelRunState, unclassifiedBlocks: Boolean = true) {
        prefs.setLabelStats(run)
        prefs.setLabelFlags(
            runCompleted = true,
            confirmed = if (unclassifiedBlocks) run.unclassified == 0 else true
        )
        logger.log(
            LogModule.LABEL,
            if (run.unclassified > 0) LogLevel.WARN else LogLevel.INFO,
            "标注完成：可渲染 ${run.classified}，未归类 ${run.unclassified}"
        )
    }

    suspend fun confirmLabels() {
        prefs.confirmLabels()
        logger.log(LogModule.LABEL, LogLevel.INFO, "用户已确认未归类警告，地图标注启用")
    }

    suspend fun setOptimizeReady(ready: Boolean) {
        prefs.setOptimizeReady(ready)
    }

    suspend fun markOptimizationVersion(version: Int) {
        prefs.markOptimizationVersion(version)
    }

    suspend fun setImportedAt(timestamp: Long) {
        prefs.setImportedAt(timestamp)
    }

    suspend fun reconcile() {
        val stationCount = db.stationDao().count()
        val readiness = prefs.readiness()
        val version = prefs.dataVersion()
        if (stationCount == 0) {
            prefs.setDataReady(false)
            prefs.setOptimizeReady(false)
            prefs.resetLabels()
        } else {
            val labeled = db.stationDao().countLabeled()
            if (readiness.labelReady && labeled == 0) {
                prefs.resetLabels()
                logger.log(LogModule.LABEL, LogLevel.WARN, "启动校验：标注数据为空，标注状态已重置")
            }
            val success = db.optimizationDao().latestSuccessForVersion(version)
            val optimizedCount = db.optimizationDao().markerCount()
            if (success == null || optimizedCount == 0) {
                prefs.setOptimizeReady(false)
            }
        }
        logger.log(
            LogModule.SYSTEM,
            LogLevel.INFO,
            "启动就绪度校验完成",
            "stations=$stationCount version=$version keyReady=${readiness.keyReady} " +
                "dataReady=${readiness.dataReady} labelReady=${readiness.labelReady}"
        )
    }
}
