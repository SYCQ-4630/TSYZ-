package com.selfuse.cellmap.map

import com.selfuse.cellmap.core.model.DenseGroup
import com.selfuse.cellmap.core.model.MapMarker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.tan
import kotlin.math.hypot

/**
 * 最大缩放层级附近，把屏幕上距离很近的标注合并为一个大半透明点，
 * 点击后由列表选择具体标注，避免密集小图标难以命中。
 * 低于该层级保持原始坐标显示，缩放过程中位置不漂移。
 */
object DenseGroups {

    const val MIN_ZOOM = 19.0

    private const val GROUP_RADIUS_PX = 22.0
    private const val TILE_SIZE = 256.0

    fun build(markers: List<MapMarker>, zoom: Double): List<DenseGroup> {
        if (zoom < MIN_ZOOM || markers.size < 2) return emptyList()
        val scale = TILE_SIZE * 2.0.pow(zoom.coerceAtLeast(0.0))
        val cell = GROUP_RADIUS_PX
        val groups = ArrayList<MutableList<MapMarker>>(8)
        val anchorsX = ArrayList<Double>(8)
        val anchorsY = ArrayList<Double>(8)
        val buckets = HashMap<Long, MutableList<Int>>(32)

        markers.sortedBy { it.id }.forEach { marker ->
            val x = worldX(marker.lon, scale)
            val y = worldY(marker.lat, scale)
            val cellX = floor(x / cell).toInt()
            val cellY = floor(y / cell).toInt()
            var target = -1
            search@ for (dx in -1..1) {
                for (dy in -1..1) {
                    val key = bucketKey(cellX + dx, cellY + dy)
                    val bucket = buckets[key] ?: continue
                    for (index in bucket) {
                        if (hypot(anchorsX[index] - x, anchorsY[index] - y) <= cell) {
                            target = index
                            break@search
                        }
                    }
                }
            }
            if (target >= 0) {
                groups[target].add(marker)
            } else {
                val index = groups.size
                groups.add(arrayListOf(marker))
                anchorsX.add(x)
                anchorsY.add(y)
                buckets.getOrPut(bucketKey(cellX, cellY)) { ArrayList(2) }.add(index)
            }
        }

        val result = ArrayList<DenseGroup>(groups.size)
        groups.forEachIndexed { index, bucket ->
            if (bucket.size < 2) return@forEachIndexed
            val anchor = bucket.first()
            val sorted = bucket.sortedWith(
                compareBy<MapMarker>({ it.title }, { it.cellId }, { it.id })
            )
            result.add(
                DenseGroup(
                    id = "d_${index}_${(anchor.lat * 1_000_000).roundToLong()}_" +
                        "${(anchor.lon * 1_000_000).roundToLong()}",
                    lat = anchor.lat,
                    lon = anchor.lon,
                    markers = sorted
                )
            )
        }
        return result
    }

    private fun bucketKey(x: Int, y: Int): Long =
        (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)

    private fun worldX(lon: Double, scale: Double): Double =
        (lon + 180.0) / 360.0 * scale

    private fun worldY(lat: Double, scale: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(clamped)
        val y = ln(tan(rad) + 1.0 / cos(rad))
        return (1.0 - y / PI) / 2.0 * scale
    }
}
