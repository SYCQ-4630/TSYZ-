package com.selfuse.cellmap.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.selfuse.cellmap.core.model.ImportSummary
import com.selfuse.cellmap.core.model.OptimizationSummary
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "stations",
    indices = [
        Index(value = ["stationId"]),
        Index(value = ["cellId"]),
        Index(value = ["stationId", "cellId"], unique = true),
        Index(value = ["system"]),
        Index(value = ["operatorNorm"]),
        Index(value = ["sharedNorm"]),
        Index(value = ["district"]),
        Index(value = ["band"]),
        Index(value = ["markerStyle"]),
        Index(value = ["batchId"])
    ]
)
data class StationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
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
    val bandwidth: String? = null,
    val uplinkFreq: String? = null,
    val downlinkFreq: String? = null,
    val deployment: String? = null,
    val azimuth: String? = null,
    val pci: String? = null,
    val multiCarrier: String? = null,
    val antennaGain: String? = null,
    val aauCount: String? = null,
    val rruCount: String? = null,
    val system: String,
    val markerStyle: String? = null,
    val searchBlob: String = "",
    @ColumnInfo(name = "operatorNorm") val operatorNorm: String = "",
    @ColumnInfo(name = "builderNorm") val builderNorm: String = "",
    @ColumnInfo(name = "sharedNorm") val sharedNorm: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

data class StationMarkerRow(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val markerStyle: String?,
    val system: String,
    val cellName: String?,
    val siteName: String?,
    val stationId: String,
    val cellId: String
)

@Entity(
    tableName = "import_batches",
    indices = [Index(value = ["importedAt"]), Index(value = ["status"])]
)
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val sheetName: String,
    val headerRow: Int,
    val dataVersion: Int,
    val totalRows: Int,
    val processedRows: Int = 0,
    val successRows: Int = 0,
    val duplicateRows: Int = 0,
    val failedRows: Int = 0,
    val failedReasonsJson: String = "{}",
    val status: String = BatchStatus.RUNNING,
    val startedAt: Long,
    val importedAt: Long = 0L,
    val note: String? = null
)

object BatchStatus {
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val PARTIAL = "PARTIAL"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val ABANDONED = "ABANDONED"
}

@Entity(
    tableName = "app_logs",
    indices = [Index(value = ["ts"]), Index(value = ["module"]), Index(value = ["level"])]
)
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val module: String,
    val level: String,
    val message: String,
    val detail: String? = null
)

object LogLevel {
    const val DEBUG = "DEBUG"
    const val INFO = "INFO"
    const val WARN = "WARN"
    const val ERROR = "ERROR"
}

object LogModule {
    const val KEY = "Key"
    const val IMPORT = "导入"
    const val LABEL = "标注"
    const val OPTIMIZE = "优化"
    const val MAP = "地图"
    const val DATA = "数据"
    const val TASK = "任务"
    const val SYSTEM = "系统"
}

@Entity(
    tableName = "optimization_runs",
    indices = [Index(value = ["status"]), Index(value = ["dataVersion"]), Index(value = ["startedAt"])]
)
data class OptimizationRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val status: String,
    val dataVersion: Int,
    val startedAt: Long,
    val finishedAt: Long = 0L,
    val markerCount: Int = 0,
    val clusterCount: Int = 0,
    val chunkCount: Int = 0,
    val rawBytes: Long = 0L,
    val optimizedBytes: Long = 0L,
    val note: String? = null
) {
    fun toSummary() = OptimizationSummary(
        markerCount = markerCount,
        clusterCount = clusterCount,
        chunkCount = chunkCount,
        rawBytes = rawBytes,
        optimizedBytes = optimizedBytes,
        finishedAt = finishedAt
    )
}

object OptimizationStatus {
    const val RUNNING = "RUNNING"
    const val SUCCESS = "SUCCESS"
    const val FAILED = "FAILED"
    const val ROLLED_BACK = "ROLLED_BACK"
}

@Entity(
    tableName = "optimized_markers",
    indices = [
        Index(value = ["markerStyle"]),
        Index(value = ["system"]),
        Index(value = ["district"]),
        Index(value = ["band"])
    ]
)
data class OptimizedMarkerEntity(
    @PrimaryKey val id: Long,
    val lat: Double,
    val lon: Double,
    val markerStyle: String,
    val system: String,
    val operatorNorm: String,
    val builderNorm: String,
    val sharedNorm: String,
    val district: String?,
    val band: String?,
    val category: String?,
    val cellName: String?,
    val stationId: String,
    val cellId: String,
    val siteName: String?
)

@Entity(
    tableName = "optimized_clusters",
    primaryKeys = ["zoomLevel", "tileX", "tileY"],
    indices = [Index(value = ["zoomLevel", "lat", "lon"]), Index(value = ["zoomLevel", "tileX"])]
)
data class OptimizedClusterEntity(
    val zoomLevel: Int,
    val tileX: Int,
    val tileY: Int,
    val count: Int,
    val unicomShared: Int,
    val telecomShared: Int,
    val unicomPrivate: Int,
    val telecomPrivate: Int,
    val lat: Double,
    val lon: Double
)

@Entity(
    tableName = "optimized_chunks",
    indices = [Index(value = ["minLat", "maxLat"]), Index(value = ["minLon", "maxLon"])]
)
data class OptimizedChunkEntity(
    @PrimaryKey val chunkId: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val markerCount: Int,
    val byteSize: Long,
    val filePath: String
)

data class StationStats(
    val total: Int = 0,
    val labeled: Int = 0,
    val unclassified: Int = 0,
    val unicomShared: Int = 0,
    val telecomShared: Int = 0,
    val unicomPrivate: Int = 0,
    val telecomPrivate: Int = 0
) {
    val renderable: Int get() = labeled
}

fun ImportBatchEntity.toSummary(): ImportSummary = ImportSummary(
    batchId = id,
    fileName = fileName,
    sheetName = sheetName,
    totalRows = totalRows,
    successRows = successRows,
    duplicateRows = duplicateRows,
    failedRows = failedRows,
    failedReasons = runCatching {
        kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>(failedReasonsJson)
    }.getOrDefault(emptyMap()),
    importedAt = importedAt,
    status = status
)
