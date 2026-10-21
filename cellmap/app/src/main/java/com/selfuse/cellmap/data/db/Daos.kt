package com.selfuse.cellmap.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.SkipQueryVerification
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(stations: List<StationEntity>): List<Long>

    @Query("SELECT * FROM stations WHERE id = :id")
    suspend fun byId(id: Long): StationEntity?

    @Query("SELECT COUNT(*) FROM stations")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM stations")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM stations WHERE markerStyle IS NOT NULL")
    suspend fun countLabeled(): Int

    @Query("SELECT COUNT(*) FROM stations WHERE markerStyle IS NULL")
    suspend fun countUnclassified(): Int

    @Query(
        """
        SELECT COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN markerStyle IS NOT NULL THEN 1 ELSE 0 END), 0) AS labeled,
               COALESCE(SUM(CASE WHEN markerStyle IS NULL THEN 1 ELSE 0 END), 0) AS unclassified,
               COALESCE(SUM(CASE WHEN markerStyle = 'unicom_shared' THEN 1 ELSE 0 END), 0) AS unicomShared,
               COALESCE(SUM(CASE WHEN markerStyle = 'telecom_shared' THEN 1 ELSE 0 END), 0) AS telecomShared,
               COALESCE(SUM(CASE WHEN markerStyle = 'unicom_private' THEN 1 ELSE 0 END), 0) AS unicomPrivate,
               COALESCE(SUM(CASE WHEN markerStyle = 'telecom_private' THEN 1 ELSE 0 END), 0) AS telecomPrivate
        FROM stations
        """
    )
    suspend fun stats(): StationStats

    @Query("SELECT * FROM stations WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun pageForLabel(afterId: Long, limit: Int): List<StationEntity>

    @Query("SELECT * FROM stations WHERE markerStyle IS NOT NULL AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun pageClassified(afterId: Long, limit: Int): List<StationEntity>

    @Query("UPDATE stations SET markerStyle = :style, updatedAt = :now WHERE id IN (:ids)")
    suspend fun updateMarkerStyle(ids: List<Long>, style: String, now: Long): Int

    @Query("UPDATE stations SET markerStyle = NULL, updatedAt = :now WHERE id IN (:ids)")
    suspend fun clearMarkerStyle(ids: List<Long>, now: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM stations
        WHERE stationId = :stationId AND system = :system
          AND lat BETWEEN :lat - 0.0002 AND :lat + 0.0002
          AND lon BETWEEN :lon - 0.0002 AND :lon + 0.0002
          AND azimuth IS NOT NULL AND TRIM(azimuth) != ''
        """
    )
    suspend fun sectorCellCount(stationId: String, system: String, lat: Double, lon: Double): Int

    @Query(
        """
        SELECT * FROM stations
        WHERE stationId = :stationId AND system = :system
          AND lat BETWEEN :lat - 0.0002 AND :lat + 0.0002
          AND lon BETWEEN :lon - 0.0002 AND :lon + 0.0002
          AND azimuth IS NOT NULL AND TRIM(azimuth) != ''
        ORDER BY id LIMIT 24
        """
    )
    suspend fun sectorCells(stationId: String, system: String, lat: Double, lon: Double): List<StationEntity>

    @Query("SELECT DISTINCT district FROM stations WHERE district IS NOT NULL AND district != '' ORDER BY district LIMIT 500")
    suspend fun distinctDistricts(): List<String>

    @Query("SELECT DISTINCT band FROM stations WHERE band IS NOT NULL AND band != '' ORDER BY band LIMIT 200")
    suspend fun distinctBands(): List<String>

    @Query("DELETE FROM stations WHERE batchId = :batchId")
    suspend fun deleteBatch(batchId: Long): Int

    @RawQuery
    suspend fun rawQuery(query: SupportSQLiteQuery): List<StationEntity>

    @RawQuery
    suspend fun rawMarkers(query: SupportSQLiteQuery): List<StationMarkerRow>
}

@Dao
interface ImportBatchDao {

    @Insert
    suspend fun insert(batch: ImportBatchEntity): Long

    @Update
    suspend fun update(batch: ImportBatchEntity)

    @Query("SELECT * FROM import_batches WHERE id = :id")
    suspend fun byId(id: Long): ImportBatchEntity?

    @Query("SELECT * FROM import_batches ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<ImportBatchEntity>>

    @Query("SELECT * FROM import_batches ORDER BY startedAt DESC LIMIT 1")
    fun observeLatest(): Flow<ImportBatchEntity?>

    @Query("SELECT * FROM import_batches WHERE status = 'RUNNING' ORDER BY startedAt DESC LIMIT 1")
    suspend fun activeBatch(): ImportBatchEntity?

    @Query("DELETE FROM import_batches WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE import_batches SET status = :status, note = :note, importedAt = :finishedAt WHERE id = :id")
    suspend fun finish(id: Long, status: String, note: String?, finishedAt: Long)
}

@SkipQueryVerification
@Dao
interface SpatialIndexDao {

    @Query("INSERT OR REPLACE INTO station_rtree(id, minLat, maxLat, minLon, maxLon) VALUES(:id, :lat, :lat, :lon, :lon)")
    suspend fun insertStationPoint(id: Long, lat: Double, lon: Double)

    @Query("INSERT OR REPLACE INTO optimized_rtree(id, minLat, maxLat, minLon, maxLon) VALUES(:id, :lat, :lat, :lon, :lon)")
    suspend fun insertOptimizedPoint(id: Long, lat: Double, lon: Double)

    @Query("SELECT COUNT(*) FROM station_rtree")
    suspend fun stationRTreeCount(): Long

    @Query("SELECT COUNT(*) FROM optimized_rtree")
    suspend fun optimizedRTreeCount(): Long

    @Query("DELETE FROM station_rtree WHERE id IN (SELECT id FROM stations WHERE batchId = :batchId)")
    suspend fun deleteStationForBatch(batchId: Long)

    @Query("DELETE FROM station_rtree")
    suspend fun clearStationRTree()

    @Query("INSERT OR REPLACE INTO station_rtree(id, minLat, maxLat, minLon, maxLon) SELECT id, lat, lat, lon, lon FROM stations")
    suspend fun rebuildStationRTree()

    @Query("DELETE FROM optimized_rtree")
    suspend fun clearOptimizedRTree()

    @Query("INSERT OR REPLACE INTO optimized_rtree(id, minLat, maxLat, minLon, maxLon) SELECT id, lat, lat, lon, lon FROM optimized_markers")
    suspend fun rebuildOptimizedRTree()
}

@Dao
interface LogDao {

    @Insert
    suspend fun insert(log: AppLogEntity): Long

    @Query("SELECT * FROM app_logs WHERE (:module IS NULL OR module = :module) ORDER BY ts DESC")
    fun pagingSource(module: String?): PagingSource<Int, AppLogEntity>

    @Query("SELECT * FROM app_logs WHERE (:module IS NULL OR module = :module) ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(module: String?, limit: Int): Flow<List<AppLogEntity>>

    @Query(
        """
        DELETE FROM app_logs WHERE id NOT IN (
            SELECT id FROM app_logs ORDER BY ts DESC LIMIT :keep
        )
        """
    )
    suspend fun prune(keep: Int): Int

    @Query("DELETE FROM app_logs")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM app_logs")
    suspend fun count(): Int
}

@Dao
interface OptimizationDao {

    @Insert
    suspend fun insertMarkers(markers: List<OptimizedMarkerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClusters(clusters: List<OptimizedClusterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<OptimizedChunkEntity>)

    @Query("DELETE FROM optimized_markers")
    suspend fun clearMarkers()

    @Query("DELETE FROM optimized_clusters")
    suspend fun clearClusters()

    @Query("DELETE FROM optimized_chunks")
    suspend fun clearChunks()

    @Query("SELECT COUNT(*) FROM optimized_markers")
    suspend fun markerCount(): Int

    @Query("SELECT COUNT(*) FROM optimized_clusters")
    suspend fun clusterCount(): Int

    @Query("SELECT COUNT(*) FROM optimized_chunks")
    suspend fun chunkCount(): Int

    @Query("SELECT * FROM optimized_chunks")
    suspend fun chunks(): List<OptimizedChunkEntity>

    @Query("SELECT * FROM optimized_chunks WHERE maxLat >= :minLat AND minLat <= :maxLat AND maxLon >= :minLon AND minLon <= :maxLon")
    suspend fun chunksIntersecting(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<OptimizedChunkEntity>

    @RawQuery
    suspend fun rawMarkers(query: SupportSQLiteQuery): List<OptimizedMarkerEntity>

    @RawQuery
    suspend fun rawClusters(query: SupportSQLiteQuery): List<OptimizedClusterEntity>

    @Insert
    suspend fun insertRun(run: OptimizationRunEntity): Long

    @Update
    suspend fun updateRun(run: OptimizationRunEntity)

    @Query("SELECT * FROM optimization_runs ORDER BY startedAt DESC")
    fun observeRuns(): Flow<List<OptimizationRunEntity>>

    @Query("SELECT * FROM optimization_runs WHERE id = :id")
    suspend fun byId(id: Long): OptimizationRunEntity?

    @Query("SELECT * FROM optimization_runs WHERE status = 'SUCCESS' ORDER BY finishedAt DESC LIMIT 1")
    suspend fun latestSuccess(): OptimizationRunEntity?

    @Query("SELECT * FROM optimization_runs WHERE status = 'SUCCESS' AND dataVersion = :version ORDER BY finishedAt DESC LIMIT 1")
    suspend fun latestSuccessForVersion(version: Int): OptimizationRunEntity?
}
