package com.selfuse.cellmap.data.db

import androidx.room.withTransaction
import com.selfuse.cellmap.core.model.LabelStats
import com.selfuse.cellmap.core.model.MarkerStyle

data class StationDraft(
    val lat: Double,
    val lon: Double,
    val builder: String?,
    val operator: String?,
    val district: String?,
    val owner: String?,
    val stationId: String,
    val cellName: String?,
    val cellId: String,
    val category: String?,
    val band: String?,
    val siteName: String?,
    val shared: String?,
    val antennaHeight: String?,
    val coDevice45G: String?,
    val system: String,
    val operatorNorm: String,
    val builderNorm: String,
    val sharedNorm: String,
    val searchBlob: String
)

data class BatchWriteResult(val inserted: Int, val duplicates: Int)

/**
 * 业务表、R-Tree、FTS 永远在同一个 Room 事务中写入或删除。
 * 单条异常由调用方吞掉并计入失败行，不会中断整批。
 */
class StationWriter(private val db: AppDatabase) {

    private val stationDao = db.stationDao()
    private val spatial = db.spatialIndexDao()
    private val fts = db.stationFtsDao()

    suspend fun insertBatch(batchId: Long, drafts: List<StationDraft>, now: Long): BatchWriteResult =
        db.withTransaction {
            if (drafts.isEmpty()) return@withTransaction BatchWriteResult(0, 0)
            val entities = drafts.map { draft ->
                StationEntity(
                    batchId = batchId,
                    lat = draft.lat,
                    lon = draft.lon,
                    builder = draft.builder,
                    operator = draft.operator,
                    district = draft.district,
                    owner = draft.owner,
                    stationId = draft.stationId,
                    cellName = draft.cellName,
                    cellId = draft.cellId,
                    category = draft.category,
                    band = draft.band,
                    siteName = draft.siteName,
                    shared = draft.shared,
                    antennaHeight = draft.antennaHeight,
                    coDevice45G = draft.coDevice45G,
                    system = draft.system,
                    searchBlob = draft.searchBlob,
                    operatorNorm = draft.operatorNorm,
                    builderNorm = draft.builderNorm,
                    sharedNorm = draft.sharedNorm,
                    createdAt = now,
                    updatedAt = now
                )
            }
            val ids = stationDao.insertIgnore(entities)
            var inserted = 0
            var duplicates = 0
            ids.forEachIndexed { index, id ->
                if (id > 0) {
                    inserted++
                    val entity = entities[index]
                    spatial.insertStationPoint(id, entity.lat, entity.lon)
                    if (entity.searchBlob.isNotBlank()) {
                        fts.insert(id, entity.searchBlob)
                    }
                } else {
                    duplicates++
                }
            }
            BatchWriteResult(inserted, duplicates)
        }

    suspend fun applyLabels(updates: Map<Long, MarkerStyle?>, now: Long) {
        if (updates.isEmpty()) return
        db.withTransaction {
            val grouped = updates.entries.groupBy { it.value }
            grouped.forEach { (style, entries) ->
                entries.map { it.key }.chunked(400).forEach { ids ->
                    if (style == null) {
                        stationDao.clearMarkerStyle(ids, now)
                    } else {
                        stationDao.updateMarkerStyle(ids, style.key, now)
                    }
                }
            }
        }
    }

    suspend fun deleteBatch(batchId: Long) {
        db.withTransaction {
            fts.deleteForBatch(batchId)
            spatial.deleteStationForBatch(batchId)
            stationDao.deleteBatch(batchId)
        }
    }

    suspend fun clearAllData() {
        db.withTransaction {
            fts.clear()
            spatial.clearStationRTree()
            db.clearAllTables()
        }
    }

    suspend fun clearOptimized() {
        db.withTransaction {
            db.optimizationDao().clearChunks()
            db.optimizationDao().clearClusters()
            db.optimizationDao().clearMarkers()
            spatial.clearOptimizedRTree()
        }
    }

    suspend fun insertOptimizedMarkers(markers: List<OptimizedMarkerEntity>) {
        db.withTransaction {
            db.optimizationDao().insertMarkers(markers)
            markers.forEach { spatial.insertOptimizedPoint(it.id, it.lat, it.lon) }
        }
    }

    suspend fun insertOptimizedClusters(clusters: List<OptimizedClusterEntity>) {
        db.withTransaction { db.optimizationDao().insertClusters(clusters) }
    }

    suspend fun insertOptimizedChunks(chunks: List<OptimizedChunkEntity>) {
        db.withTransaction { db.optimizationDao().insertChunks(chunks) }
    }

    suspend fun repairSpatialIndexes(): Pair<Long, Long> = db.withTransaction {
        spatial.clearStationRTree()
        spatial.rebuildStationRTree()
        spatial.clearOptimizedRTree()
        spatial.rebuildOptimizedRTree()
        spatial.stationRTreeCount() to spatial.optimizedRTreeCount()
    }

    suspend fun stats(): StationStats = stationDao.stats()
    suspend fun stationCount(): Int = stationDao.count()
    suspend fun stationRTreeCount(): Long = spatial.stationRTreeCount()
    suspend fun optimizedMarkerCount(): Int = db.optimizationDao().markerCount()
    suspend fun optimizedRTreeCount(): Long = spatial.optimizedRTreeCount()
}
