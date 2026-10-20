package com.selfuse.cellmap.data.repo

import androidx.sqlite.db.SimpleSQLiteQuery
import com.selfuse.cellmap.core.model.MapCluster
import com.selfuse.cellmap.core.model.MapMarker
import com.selfuse.cellmap.core.model.MarkerFilters
import com.selfuse.cellmap.core.model.MarkerStyle
import com.selfuse.cellmap.core.model.NetworkSystem
import com.selfuse.cellmap.core.model.SearchHit
import com.selfuse.cellmap.core.model.ViewportSnapshot
import com.selfuse.cellmap.core.util.GeoBounds
import com.selfuse.cellmap.core.util.SearchText
import com.selfuse.cellmap.data.db.AppDatabase
import com.selfuse.cellmap.data.db.OptimizedMarkerEntity
import com.selfuse.cellmap.data.db.StationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class StationRepository(private val db: AppDatabase) {

    suspend fun viewport(
        bounds: GeoBounds,
        system: NetworkSystem,
        filters: MarkerFilters,
        optimizeReady: Boolean,
        zoom: Double,
        limit: Int = 7_000
    ): ViewportSnapshot = withContext(Dispatchers.IO) {
        val optimizedMarkers = if (optimizeReady) db.optimizationDao().markerCount() else 0
        val useOptimized = optimizeReady && optimizedMarkers > 0
        if (useOptimized && zoom <= PRE_CLUSTER_MAX_ZOOM) {
            val clusters = optimizedClusters(bounds, zoom)
            return@withContext ViewportSnapshot(
                clusters = clusters,
                usingOptimized = true
            )
        }
        return@withContext if (useOptimized) {
            val entities = queryOptimized(bounds, system, filters, limit)
            ViewportSnapshot(
                markers = entities.mapNotNull { it.toMapMarker() },
                usingOptimized = true,
                truncated = entities.size >= limit
            )
        } else {
            val entities = queryRaw(bounds, system, filters, limit)
            ViewportSnapshot(
                markers = entities.mapNotNull { it.toMapMarker() },
                usingOptimized = false,
                truncated = entities.size >= limit
            )
        }
    }

    suspend fun search(
        query: String,
        system: NetworkSystem,
        filters: MarkerFilters,
        limit: Int = 30
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val ftsQuery = SearchText.ftsQuery(query) ?: return@withContext emptyList()
        val (where, args) = buildFilters(filters, "s", system, includeSystem = true)
        val ftsSql = buildString {
            append("SELECT s.* FROM stations s JOIN station_fts ON station_fts.rowid = s.id ")
            append("WHERE station_fts MATCH ? ")
            if (where.isNotBlank()) append("AND ").append(where).append(' ')
            append("ORDER BY s.id LIMIT ?")
        }
        val ftsArgs = (listOf<Any?>(ftsQuery) + args + limit).toTypedArray()
        val rows = runCatching {
            db.stationDao().rawQuery(SimpleSQLiteQuery(ftsSql, ftsArgs))
        }.getOrElse {
            likeSearch(query, system, filters, limit)
        }
        rows.mapNotNull { station ->
            val style = MarkerStyle.fromKey(station.markerStyle)
            SearchHit(
                id = station.id,
                title = station.cellName ?: station.siteName ?: station.cellId,
                subtitle = listOfNotNull(
                    station.stationId,
                    station.cellId,
                    station.district,
                    station.system
                ).joinToString(" · "),
                lat = station.lat,
                lon = station.lon,
                style = style,
                system = station.system
            )
        }
    }

    suspend fun byId(id: Long): StationEntity? = withContext(Dispatchers.IO) {
        db.stationDao().byId(id)
    }

    suspend fun distinctDistricts(): List<String> = withContext(Dispatchers.IO) {
        db.stationDao().distinctDistricts()
    }

    suspend fun distinctBands(): List<String> = withContext(Dispatchers.IO) {
        db.stationDao().distinctBands()
    }

    suspend fun spatialCounts(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        db.spatialIndexDao().stationRTreeCount() to db.stationDao().count().toLong()
    }

    suspend fun optimizeCounts(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        db.optimizationDao().markerCount() to db.spatialIndexDao().optimizedRTreeCount()
    }

    private suspend fun queryRaw(
        bounds: GeoBounds,
        system: NetworkSystem,
        filters: MarkerFilters,
        limit: Int
    ): List<StationEntity> {
        val (where, args) = buildFilters(filters, "s", system, includeSystem = true)
        val sql = buildString {
            append("SELECT s.* FROM stations s JOIN station_rtree r ON s.id = r.id ")
            append("WHERE r.maxLat >= ? AND r.minLat <= ? AND r.maxLon >= ? AND r.minLon <= ? ")
            if (where.isNotBlank()) append("AND ").append(where).append(' ')
            append("LIMIT ?")
        }
        val sqlArgs = (
            listOf<Any?>(bounds.minLat, bounds.maxLat, bounds.minLon, bounds.maxLon) +
                args + limit
            ).toTypedArray()
        return db.stationDao().rawQuery(SimpleSQLiteQuery(sql, sqlArgs))
    }

    private suspend fun queryOptimized(
        bounds: GeoBounds,
        system: NetworkSystem,
        filters: MarkerFilters,
        limit: Int
    ): List<OptimizedMarkerEntity> {
        val (where, args) = buildFilters(filters, "o", system, includeSystem = true)
        val sql = buildString {
            append("SELECT o.* FROM optimized_markers o JOIN optimized_rtree r ON o.id = r.id ")
            append("WHERE r.maxLat >= ? AND r.minLat <= ? AND r.maxLon >= ? AND r.minLon <= ? ")
            if (where.isNotBlank()) append("AND ").append(where).append(' ')
            append("LIMIT ?")
        }
        val sqlArgs = (
            listOf<Any?>(bounds.minLat, bounds.maxLat, bounds.minLon, bounds.maxLon) +
                args + limit
            ).toTypedArray()
        return db.optimizationDao().rawMarkers(SimpleSQLiteQuery(sql, sqlArgs))
    }

    private suspend fun optimizedClusters(bounds: GeoBounds, zoom: Double): List<MapCluster> {
        val level = zoom.roundToInt().coerceIn(6, PRE_CLUSTER_MAX_ZOOM)
        val sql = """
            SELECT * FROM optimized_clusters
            WHERE zoomLevel = ? AND lat >= ? AND lat <= ? AND lon >= ? AND lon <= ?
            LIMIT 4000
        """.trimIndent()
        val args = arrayOf<Any?>(level, bounds.minLat, bounds.maxLat, bounds.minLon, bounds.maxLon)
        return db.optimizationDao().rawClusters(SimpleSQLiteQuery(sql, args)).map {
            MapCluster(level = it.zoomLevel, lat = it.lat, lon = it.lon, count = it.count)
        }
    }

    private suspend fun likeSearch(
        query: String,
        system: NetworkSystem,
        filters: MarkerFilters,
        limit: Int
    ): List<StationEntity> {
        val (where, args) = buildFilters(filters, "s", system, includeSystem = true)
        val like = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
        val sql = buildString {
            append("SELECT s.* FROM stations s WHERE (")
            append("s.cellName LIKE ? ESCAPE '\\' OR s.siteName LIKE ? ESCAPE '\\' ")
            append("OR s.stationId LIKE ? ESCAPE '\\' OR s.cellId LIKE ? ESCAPE '\\') ")
            if (where.isNotBlank()) append("AND ").append(where).append(' ')
            append("LIMIT ?")
        }
        val sqlArgs = (listOf<Any?>(like, like, like, like) + args + limit).toTypedArray()
        return db.stationDao().rawQuery(SimpleSQLiteQuery(sql, sqlArgs))
    }

    private fun buildFilters(
        filters: MarkerFilters,
        alias: String,
        system: NetworkSystem,
        includeSystem: Boolean
    ): Pair<String, List<Any?>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        if (includeSystem) {
            clauses.add("$alias.system = ?")
            args.add(system.label)
        }
        if (filters.operators.isNotEmpty()) {
            clauses.add("$alias.operatorNorm IN (${filters.operators.joinToString(",") { "?" }})")
            args.addAll(filters.operators)
        }
        if (filters.sharedStates.isNotEmpty()) {
            clauses.add("$alias.sharedNorm IN (${filters.sharedStates.joinToString(",") { "?" }})")
            args.addAll(filters.sharedStates)
        }
        if (filters.styles.isNotEmpty()) {
            clauses.add("$alias.markerStyle IN (${filters.styles.joinToString(",") { "?" }})")
            args.addAll(filters.styles)
        }
        if (filters.district.isNotBlank()) {
            clauses.add("$alias.district = ?")
            args.add(filters.district.trim())
        }
        if (filters.band.isNotBlank()) {
            clauses.add("$alias.band = ?")
            args.add(filters.band.trim())
        }
        if (filters.category.isNotBlank()) {
            clauses.add("$alias.category = ?")
            args.add(filters.category.trim())
        }
        return clauses.joinToString(" AND ") to args
    }

    private fun StationEntity.toMapMarker(): MapMarker? {
        val style = MarkerStyle.fromKey(markerStyle) ?: return null
        return MapMarker(
            id = id,
            lat = lat,
            lon = lon,
            style = style,
            system = system,
            title = cellName ?: siteName ?: cellId,
            stationId = stationId,
            cellId = cellId
        )
    }

    private fun OptimizedMarkerEntity.toMapMarker(): MapMarker? {
        val style = MarkerStyle.fromKey(markerStyle) ?: return null
        return MapMarker(
            id = id,
            lat = lat,
            lon = lon,
            style = style,
            system = system,
            title = cellName ?: siteName ?: cellId,
            stationId = stationId,
            cellId = cellId
        )
    }

    companion object {
        const val PRE_CLUSTER_MAX_ZOOM = 10
    }
}
