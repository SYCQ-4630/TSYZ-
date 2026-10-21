package com.selfuse.cellmap.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class SectorCell(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val azimuth: Double,
    val styleKey: String,
    val cellName: String,
    val system: String,
    val radiusMeters: Double
)

/** 同位置小区方位角扇区几何。仅在用户手动开启时生成，避免默认渲染开销。 */
object SectorGeometry {

    const val BEAM_WIDTH = 64.0
    const val MAX_CELLS = 24
    private const val EARTH_METERS_PER_DEGREE = 111_320.0
    const val ARC_SEGMENTS = 12
    private val NUMBER = Regex("[-+]?\\d+(?:\\.\\d+)?")

    /** 经纬度完全相同的标识点归为同一位置，用于同点扇区合并。 */
    fun positionKey(lat: Double, lon: Double): String = "${lat.toBits()}|${lon.toBits()}"

    /** 从「30」「30°」「30.5 度」等文本中提取方位角，超出 0..360 时归一化。 */
    fun parseAzimuth(text: String?): Double? {
        val matched = NUMBER.find(text?.trim().orEmpty())?.value?.toDoubleOrNull() ?: return null
        if (!matched.isFinite()) return null
        return ((matched % 360.0) + 360.0) % 360.0
    }

    fun destination(lat: Double, lon: Double, bearing: Double, distanceMeters: Double): Pair<Double, Double> {
        val rad = Math.toRadians(bearing)
        val latDelta = distanceMeters * Math.cos(rad) / EARTH_METERS_PER_DEGREE
        val lonScale = Math.cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
        val lonDelta = distanceMeters * Math.sin(rad) / (EARTH_METERS_PER_DEGREE * lonScale)
        return (lat + latDelta) to (lon + lonDelta)
    }

    /** 返回闭合的扇区轮廓（含圆心），可直接作为 Polygon 环。 */
    fun wedge(
        lat: Double,
        lon: Double,
        azimuth: Double,
        radiusMeters: Double,
        beamWidth: Double = BEAM_WIDTH
    ): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>(ARC_SEGMENTS + 2)
        points.add(lat to lon)
        val start = azimuth - beamWidth / 2.0
        for (step in 0..ARC_SEGMENTS) {
            val bearing = start + beamWidth * step / ARC_SEGMENTS
            points.add(destination(lat, lon, bearing, radiusMeters))
        }
        points.add(lat to lon)
        return points
    }
}
