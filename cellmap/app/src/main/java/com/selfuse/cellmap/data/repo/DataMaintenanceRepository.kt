package com.selfuse.cellmap.data.repo

import com.selfuse.cellmap.data.db.AppDatabase
import com.selfuse.cellmap.data.db.BatchStatus
import com.selfuse.cellmap.data.db.LogLevel
import com.selfuse.cellmap.data.db.LogModule
import com.selfuse.cellmap.data.db.StationWriter

class DataMaintenanceRepository(
    private val db: AppDatabase,
    private val writer: StationWriter,
    private val readiness: ReadinessRepository,
    private val logger: AppLogger
) {
    suspend fun deleteBatch(batchId: Long): Result<Unit> {
        val batch = db.importBatchDao().byId(batchId)
            ?: return Result.failure(IllegalStateException("批次不存在"))
        if (batch.status == BatchStatus.RUNNING) {
            return Result.failure(IllegalStateException("该批次仍在导入，请先在任务中心取消"))
        }
        return runCatching {
            writer.deleteBatch(batchId)
            writer.clearOptimized()
            val remaining = writer.stationCount()
            readiness.onDataMutated(remaining > 0)
            db.importBatchDao().delete(batchId)
            logger.log(
                LogModule.DATA,
                LogLevel.INFO,
                "已删除表格：${batch.fileName}",
                "batch=$batchId remaining=$remaining"
            )
        }
    }

    suspend fun repairIndexes(): Result<Pair<Long, Long>> = runCatching {
        val counts = writer.repairSpatialIndexes()
        logger.log(
            LogModule.DATA,
            LogLevel.INFO,
            "空间索引已重建",
            "station=${counts.first} optimized=${counts.second}"
        )
        counts
    }.onFailure {
        logger.log(LogModule.DATA, LogLevel.ERROR, "重建空间索引失败", it.message)
    }
}
