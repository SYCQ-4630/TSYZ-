package com.selfuse.cellmap.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.util.Formats
import com.selfuse.cellmap.data.db.LogLevel
import com.selfuse.cellmap.data.db.LogModule
import com.selfuse.cellmap.data.prefs.LabelRunState
import com.selfuse.cellmap.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 每天首次启动后的轻量维护任务：
 * 校验就绪状态与 Key、修正空间索引、同步标注统计，并预热区县/频段与底图样式。
 * 只读取本地数据，不访问网络，失败不影响应用使用。
 */
class DailyRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as CellMapApp).container
        val today = Formats.todayKey()
        if (!c.prefs.claimDailyRefresh(today)) return Result.success()

        return try {
            c.readiness.reconcile()
            c.keyRepository.reconcile()

            val (stationCount, stationIndexCount) = c.stationRepository.spatialCounts()
            val (optimizedCount, optimizedIndexCount) = c.stationRepository.optimizeCounts()
            if (stationCount != stationIndexCount || optimizedCount.toLong() != optimizedIndexCount) {
                c.dataMaintenance.repairIndexes()
            }

            syncLabelStats(c)
            // 预热首次进入地图会用到的基础查询与样式构建。
            c.stationRepository.distinctDistricts()
            c.stationRepository.distinctBands()
            c.baseMapProvider.prepare()

            c.logger.log(
                LogModule.DATA,
                LogLevel.INFO,
                "每日数据刷新完成",
                "stations=$stationCount optimized=$optimizedCount"
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            c.logger.log(
                LogModule.DATA,
                LogLevel.WARN,
                "每日数据刷新未完成",
                e.message ?: e.javaClass.simpleName
            )
            Result.success()
        }
    }

    /**
     * 以数据库实际统计为准修正标注计数，保留用户确认时间。
     */
    private suspend fun syncLabelStats(c: AppContainer) {
        val readiness = c.prefs.readiness()
        if (!readiness.labelReady) return
        val stored = c.prefs.labelStatsFlow.first()
        val db = c.database.stationDao().stats()
        if (stored.total == db.total &&
            stored.classified == db.labeled &&
            stored.unclassified == db.unclassified &&
            stored.unicomShared == db.unicomShared &&
            stored.telecomShared == db.telecomShared &&
            stored.unicomPrivate == db.unicomPrivate &&
            stored.telecomPrivate == db.telecomPrivate
        ) {
            return
        }
        c.prefs.setLabelStats(
            LabelRunState(
                total = db.total,
                classified = db.labeled,
                unclassified = db.unclassified,
                unicomShared = db.unicomShared,
                telecomShared = db.telecomShared,
                unicomPrivate = db.unicomPrivate,
                telecomPrivate = db.telecomPrivate,
                completedAt = stored.completedAt
            )
        )
        c.logger.log(
            LogModule.LABEL,
            LogLevel.INFO,
            "标注统计已同步",
            "total=${db.total} labeled=${db.labeled}"
        )
    }
}
