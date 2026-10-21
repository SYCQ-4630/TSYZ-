package com.selfuse.cellmap.map

import com.selfuse.cellmap.core.model.DenseGroup
import com.selfuse.cellmap.core.model.MapMarker
import kotlin.math.roundToLong

/**
 * 高倍率下的同坐标小区合并：只保留一个半透明大点，点击后由列表选择具体小区，
 * 避免十多个小图标挤在一起难以命中。
 */
object DenseGroups {

    const val MIN_ZOOM = 15.0

    fun build(markers: List<MapMarker>): List<DenseGroup> {
        if (markers.size < 2) return emptyList()
        val buckets = HashMap<Pair<Long, Long>, MutableList<MapMarker>>(markers.size)
        markers.forEach { marker ->
            val key = (marker.lat * 1_000_000).roundToLong() to
                (marker.lon * 1_000_000).roundToLong()
            buckets.getOrPut(key) { ArrayList(4) }.add(marker)
        }
        val groups = ArrayList<DenseGroup>(8)
        buckets.forEach { (key, bucket) ->
            if (bucket.size < 2) return@forEach
            val sorted = bucket.sortedWith(
                compareBy<MapMarker>({ it.title }, { it.cellId }, { it.id })
            )
            groups.add(
                DenseGroup(
                    id = "d_${key.first}_${key.second}",
                    lat = sorted.first().lat,
                    lon = sorted.first().lon,
                    markers = sorted
                )
            )
        }
        return groups
    }
}
