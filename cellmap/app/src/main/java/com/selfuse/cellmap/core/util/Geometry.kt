package com.selfuse.cellmap.core.util

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

@Immutable
data class GeoBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
) {
    val centerLat: Double get() = (minLat + maxLat) / 2.0
    val centerLon: Double get() = (minLon + maxLon) / 2.0

    fun contains(lat: Double, lon: Double): Boolean =
        lat in minLat..maxLat && lon in minLon..maxLon

    companion object {
        fun world(): GeoBounds = GeoBounds(-85.0, -180.0, 85.0, 180.0)

        fun fromCenterZoom(lat: Double, lon: Double, zoom: Double): GeoBounds {
            val safeLat = lat.coerceIn(-80.0, 80.0)
            val z = zoom.coerceIn(3.0, 18.0)
            val metersPerPixel = 156543.03392 * cos(safeLat * PI / 180.0) / Math.pow(2.0, z)
            // 屏幕像素先按常见竖屏估计，随后由地图相机回调校正。
            val widthMeters = metersPerPixel * 1080.0 * 0.65
            val heightMeters = metersPerPixel * 2200.0 * 0.65
            val latDelta = (heightMeters / 111_320.0).coerceIn(0.002, 40.0)
            val lonDelta = (widthMeters / (111_320.0 * cos(safeLat * PI / 180.0).coerceAtLeast(0.2)))
                .coerceIn(0.002, 60.0)
            return GeoBounds(
                minLat = (safeLat - latDelta).coerceAtLeast(-85.0),
                minLon = (lon - lonDelta).coerceAtLeast(-180.0),
                maxLat = (safeLat + latDelta).coerceAtMost(85.0),
                maxLon = (lon + lonDelta).coerceAtMost(180.0)
            )
        }
    }
}

object WebMercator {
    fun tileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun tileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val rad = lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
        return floor(y * n).toInt().coerceIn(0, n - 1)
    }

    fun centerOfTile(tileX: Int, tileY: Int, zoom: Int): Pair<Double, Double> {
        val n = 1 shl zoom
        val lon = (tileX + 0.5) / n * 360.0 - 180.0
        val latRad = atan(sinh(PI * (1.0 - 2.0 * (tileY + 0.5) / n)))
        return latRad * 180.0 / PI to lon
    }

    private fun sinh(x: Double): Double = (Math.exp(x) - Math.exp(-x)) / 2.0
}

fun coord6(value: Double): Double = String.format(java.util.Locale.US, "%.6f", value).toDouble()
