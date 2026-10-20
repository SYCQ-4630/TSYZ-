package com.selfuse.cellmap.data.repo

import com.selfuse.cellmap.core.model.MarkerStyle
import com.selfuse.cellmap.core.model.OptimizationSummary
import com.selfuse.cellmap.core.util.WebMercator
import com.selfuse.cellmap.core.util.coord6
import com.selfuse.cellmap.data.db.AppDatabase
import com.selfuse.cellmap.data.db.LogLevel
import com.selfuse.cellmap.data.db.LogModule
import com.selfuse.cellmap.data.db.OptimizationRunEntity
import com.selfuse.cellmap.data.db.OptimizationStatus
import com.selfuse.cellmap.data.db.OptimizedChunkEntity
import com.selfuse.cellmap.data.db.OptimizedClusterEntity
import com.selfuse.cellmap.data.db.OptimizedMarkerEntity
import com.selfuse.cellmap.data.db.StationEntity
import com.selfuse.cellmap.data.db.StationWriter
import com.selfuse.cellmap.data.prefs.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

data class OptimizationProgress(
    val percent: Int,
    val processed: Int,
    val total: Int,
    val phase: String
)

class OptimizationRepository(
    private val context: android.content.Context,
    private val db: AppDatabase,
    private val writer: StationWriter,
    private val readiness: ReadinessRepository,
    private val prefs: AppPreferences,
    private val logger: AppLogger
) {
    private val dao = db.optimizationDao()

    suspend fun run(
        dataVersion: Int,
        isCancelled: () -> Boolean,
        onProgress: (OptimizationProgress) -> Unit
    ): OptimizationSummary = withContext(Dispatchers.IO) {
        val total = db.stationDao().countLabeled()
        if (total <= 0) throw IllegalStateException("没有可优化的标注数据，请先完成分类标注")

        readiness.setOptimizeReady(false)
        prefs.markOptimizationVersion(-1)
        val startedAt = System.currentTimeMillis()
        val runId = dao.insertRun(
            OptimizationRunEntity(
                status = OptimizationStatus.RUNNING,
                dataVersion = dataVersion,
                startedAt = startedAt
            )
        )
        val runDir = File(File(context.filesDir, "optimization"), "run_$runId").apply { mkdirs() }
        val chunks = ChunkWriterCache(runDir)
        val clusters = HashMap<Long, ClusterAccumulator>(64_000)
        var processed = 0
        var rawBytes = 0L
        var clusterLimitLogged = false

        try {
            writer.clearOptimized()
            var afterId = 0L
            while (true) {
                if (isCancelled()) throw CancellationException("优化已暂停")
                val page = db.stationDao().pageClassified(afterId, PAGE_SIZE)
                if (page.isEmpty()) break
                val markers = page.map { station ->
                    rawBytes += estimateRawBytes(station)
                    val marker = station.toOptimized()
                    addClusters(clusters, marker, onLimit = { clusterLimitLogged = true })
                    chunks.write(marker)
                    marker
                }
                writer.insertOptimizedMarkers(markers)
                afterId = page.last().id
                processed += page.size
                val percent = (processed * 100 / total).coerceIn(0, 99)
                onProgress(
                    OptimizationProgress(
                        percent = percent,
                        processed = processed,
                        total = total,
                        phase = "裁剪属性与坐标 · $processed / $total"
                    )
                )
            }
            chunks.close()
            if (clusterLimitLogged) {
                logger.log(
                    LogModule.OPTIMIZE,
                    LogLevel.WARN,
                    "聚类格网数量超过上限，低缩放层将只保留已计算的聚合点"
                )
            }

            val clusterEntities = clusters.map { (_, acc) -> acc.toEntity() }
            clusterEntities.chunked(2_000).forEach { batch ->
                if (isCancelled()) throw CancellationException("优化已暂停")
                writer.insertOptimizedClusters(batch)
            }

            val chunkEntities = chunks.toEntities()
            writer.insertOptimizedChunks(chunkEntities)
            val optimizedBytes = chunkEntities.sumOf { it.byteSize }
            val note = if (processed >= MVT_EVALUATION_THRESHOLD) {
                "点位超过 ${MVT_EVALUATION_THRESHOLD / 10_000} 万，建议后续评估 MVT 瓦片方案"
            } else {
                null
            }
            val finished = dao.byId(runId)?.copy(
                status = OptimizationStatus.SUCCESS,
                finishedAt = System.currentTimeMillis(),
                markerCount = processed,
                clusterCount = clusterEntities.size,
                chunkCount = chunkEntities.size,
                rawBytes = rawBytes,
                optimizedBytes = optimizedBytes,
                note = note
            ) ?: throw IllegalStateException("优化任务记录丢失")
            dao.updateRun(finished)
            readiness.markOptimizationVersion(dataVersion)
            readiness.setOptimizeReady(true)
            onProgress(OptimizationProgress(100, total, total, "优化完成"))
            logger.log(
                LogModule.OPTIMIZE,
                LogLevel.INFO,
                "压缩优化完成",
                "markers=$processed clusters=${clusterEntities.size} chunks=${chunkEntities.size} " +
                    "raw=$rawBytes optimized=$optimizedBytes"
            )
            cleanupOldRuns(runDir)
            finished.toSummary()
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                runCatching { chunks.close() }
                runCatching { writer.clearOptimized() }
                runCatching { prefs.markOptimizationVersion(-1) }
                runCatching { readiness.setOptimizeReady(false) }
                runCatching {
                    dao.byId(runId)?.let { run ->
                        dao.updateRun(
                            run.copy(
                                status = OptimizationStatus.FAILED,
                                finishedAt = System.currentTimeMillis(),
                                markerCount = processed,
                                rawBytes = rawBytes,
                                note = e.message?.take(500)
                            )
                        )
                    }
                }
                logger.log(LogModule.OPTIMIZE, LogLevel.ERROR, "压缩优化失败，已回退原始数据", e.message)
            }
            throw e
        }
    }

    suspend fun rollback(): Boolean = withContext(Dispatchers.IO) {
        val latest = dao.latestSuccess()
        writer.clearOptimized()
        prefs.markOptimizationVersion(-1)
        readiness.setOptimizeReady(false)
        if (latest != null) {
            dao.updateRun(
                latest.copy(
                    status = OptimizationStatus.ROLLED_BACK,
                    note = "用户回退，地图改为读取原始标注数据"
                )
            )
        }
        logger.log(LogModule.OPTIMIZE, LogLevel.WARN, "已回退到原始标注数据")
        true
    }

    suspend fun loadChunk(chunkId: String): String? = withContext(Dispatchers.IO) {
        val chunk = dao.chunks().firstOrNull { it.chunkId == chunkId } ?: return@withContext null
        val file = File(chunk.filePath)
        if (!file.exists()) null else runCatching { file.readText() }.getOrNull()
    }

    suspend fun chunkCount(): Int = dao.chunkCount()

    val runs = dao.observeRuns()

    private fun addClusters(
        clusters: HashMap<Long, ClusterAccumulator>,
        marker: OptimizedMarkerEntity,
        onLimit: () -> Unit
    ) {
        for (level in CLUSTER_LEVELS) {
            val tx = WebMercator.tileX(marker.lon, level)
            val ty = WebMercator.tileY(marker.lat, level)
            val key = (level.toLong() shl 40) or (tx.toLong() shl 20) or ty.toLong()
            val acc = clusters[key] ?: run {
                if (clusters.size >= MAX_CLUSTERS) {
                    onLimit()
                    return@run null
                }
                ClusterAccumulator(level, tx, ty).also { clusters[key] = it }
            } ?: continue
            acc.add(marker)
        }
    }

    private fun StationEntity.toOptimized(): OptimizedMarkerEntity = OptimizedMarkerEntity(
        id = id,
        lat = coord6(lat),
        lon = coord6(lon),
        markerStyle = MarkerStyle.fromKey(markerStyle)?.key ?: throw IllegalStateException("缺少 markerStyle"),
        system = system,
        operatorNorm = operatorNorm,
        builderNorm = builderNorm,
        sharedNorm = sharedNorm,
        district = district,
        band = band,
        category = category,
        cellName = cellName,
        stationId = stationId,
        cellId = cellId,
        siteName = siteName
    )

    private fun estimateRawBytes(station: StationEntity): Long {
        var size = 120L
        size += station.builder?.length ?: 0
        size += station.operator?.length ?: 0
        size += station.district?.length ?: 0
        size += station.owner?.length ?: 0
        size += station.stationId.length
        size += station.cellName?.length ?: 0
        size += station.cellId.length
        size += station.category?.length ?: 0
        size += station.band?.length ?: 0
        size += station.siteName?.length ?: 0
        size += station.shared?.length ?: 0
        size += station.antennaHeight?.length ?: 0
        size += station.coDevice45G?.length ?: 0
        return size
    }

    private fun cleanupOldRuns(current: File) {
        runCatching {
            val parent = current.parentFile ?: return@runCatching
            parent.listFiles()?.filter { it.isDirectory }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(2)
                ?.forEach { it.deleteRecursively() }
        }
    }

    private inner class ChunkWriterCache(private val dir: File) {
        private val metas = LinkedHashMap<String, ChunkMeta>()
        private val writers = object : LinkedHashMap<String, BufferedWriter>(24, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedWriter>): Boolean {
                if (size > MAX_OPEN_CHUNK_FILES) {
                    runCatching { eldest.value.close() }
                    return true
                }
                return false
            }
        }

        fun write(marker: OptimizedMarkerEntity) {
            val latCell = floor(marker.lat * 2.0).toInt()
            val lonCell = floor(marker.lon * 2.0).toInt()
            val chunkId = "${latCell}_${lonCell}"
            val meta = metas.getOrPut(chunkId) {
                ChunkMeta(
                    id = chunkId,
                    file = File(dir, "chunk_$chunkId.geojsonl"),
                    minLat = marker.lat,
                    maxLat = marker.lat,
                    minLon = marker.lon,
                    maxLon = marker.lon
                )
            }
            meta.count++
            meta.minLat = minOf(meta.minLat, marker.lat)
            meta.maxLat = maxOf(meta.maxLat, marker.lat)
            meta.minLon = minOf(meta.minLon, marker.lon)
            meta.maxLon = maxOf(meta.maxLon, marker.lon)
            val line = featureLine(marker) + "\n"
            meta.bytes += line.toByteArray(Charsets.UTF_8).size.toLong()
            writerFor(chunkId, meta.file).write(line)
        }

        fun close() {
            writers.values.forEach { runCatching { it.close() } }
            writers.clear()
        }

        fun toEntities(): List<OptimizedChunkEntity> = metas.values.map { meta ->
            OptimizedChunkEntity(
                chunkId = meta.id,
                minLat = meta.minLat,
                maxLat = meta.maxLat,
                minLon = meta.minLon,
                maxLon = meta.maxLon,
                markerCount = meta.count,
                byteSize = meta.bytes,
                filePath = meta.file.absolutePath
            )
        }

        private fun writerFor(chunkId: String, file: File): BufferedWriter =
            writers.getOrPut(chunkId) {
                FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8)
            }
    }

    private data class ChunkMeta(
        val id: String,
        val file: File,
        var minLat: Double,
        var maxLat: Double,
        var minLon: Double,
        var maxLon: Double,
        var count: Int = 0,
        var bytes: Long = 0L
    )

    private class ClusterAccumulator(val level: Int, val tileX: Int, val tileY: Int) {
        var count = 0
        var unicomShared = 0
        var telecomShared = 0
        var unicomPrivate = 0
        var telecomPrivate = 0
        var sumLat = 0.0
        var sumLon = 0.0

        fun add(marker: OptimizedMarkerEntity) {
            count++
            sumLat += marker.lat
            sumLon += marker.lon
            when (MarkerStyle.fromKey(marker.markerStyle)) {
                MarkerStyle.UNICOM_SHARED -> unicomShared++
                MarkerStyle.TELECOM_SHARED -> telecomShared++
                MarkerStyle.UNICOM_PRIVATE -> unicomPrivate++
                MarkerStyle.TELECOM_PRIVATE -> telecomPrivate++
                null -> Unit
            }
        }

        fun toEntity(): OptimizedClusterEntity {
            val safeCount = count.coerceAtLeast(1)
            return OptimizedClusterEntity(
                zoomLevel = level,
                tileX = tileX,
                tileY = tileY,
                count = count,
                unicomShared = unicomShared,
                telecomShared = telecomShared,
                unicomPrivate = unicomPrivate,
                telecomPrivate = telecomPrivate,
                lat = coord6(sumLat / safeCount),
                lon = coord6(sumLon / safeCount)
            )
        }
    }

    private fun featureLine(marker: OptimizedMarkerEntity): String {
        val properties = JSONObject()
            .put("id", marker.id)
            .put("style", marker.markerStyle)
            .put("system", marker.system)
            .put("name", marker.cellName ?: marker.siteName ?: marker.cellId)
            .put("stationId", marker.stationId)
            .put("cellId", marker.cellId)
        val geometry = JSONObject()
            .put("type", "Point")
            .put("coordinates", org.json.JSONArray().put(marker.lon).put(marker.lat))
        return JSONObject()
            .put("type", "Feature")
            .put("geometry", geometry)
            .put("properties", properties)
            .toString()
    }

    private fun OptimizationRunEntity.toSummary() = OptimizationSummary(
        markerCount = markerCount,
        clusterCount = clusterCount,
        chunkCount = chunkCount,
        rawBytes = rawBytes,
        optimizedBytes = optimizedBytes,
        finishedAt = finishedAt
    )

    private companion object {
        const val PAGE_SIZE = 1_000
        val CLUSTER_LEVELS = intArrayOf(6, 7, 8, 9, 10)
        const val MAX_CLUSTERS = 150_000
        const val MAX_OPEN_CHUNK_FILES = 24
        const val MVT_EVALUATION_THRESHOLD = 100_000
    }
}
