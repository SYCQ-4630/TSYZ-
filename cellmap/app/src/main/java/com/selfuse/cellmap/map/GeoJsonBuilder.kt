package com.selfuse.cellmap.map

import com.selfuse.cellmap.core.model.MapCluster
import com.selfuse.cellmap.core.model.MapMarker
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

object GeoJsonBuilder {

    fun markers(markers: List<MapMarker>): String {
        val features = JSONArray()
        val rings = markerRings(markers)
        markers.forEachIndexed { index, marker ->
            val ring = rings[index] ?: 0
            val properties = JSONObject()
                .put("sid", marker.id.toString())
                .put("style", marker.style.key)
                .put("icon", marker.style.iconName)
                .put("system", marker.system)
                .put("name", marker.title)
                .put("stationId", marker.stationId)
                .put("cellId", marker.cellId)
                .put("ring", ring)
            val geometry = JSONObject()
                .put("type", "Point")
                .put("coordinates", JSONArray().put(marker.lon).put(marker.lat))
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", geometry)
                    .put("properties", properties)
            )
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()
    }

    fun clusters(clusters: List<MapCluster>): String {
        val features = JSONArray()
        clusters.forEach { cluster ->
            val properties = JSONObject()
                .put("point_count", cluster.count)
                .put("point_count_abbreviated", abbreviate(cluster.count))
                .put("level", cluster.level)
            val geometry = JSONObject()
                .put("type", "Point")
                .put("coordinates", JSONArray().put(cluster.lon).put(cluster.lat))
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", geometry)
                    .put("properties", properties)
            )
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()
    }

    /**
     * 同一经纬度的多个小区会完全重叠，点击时只能命中最后一个。这里给重叠点分配
     * 渲染环编号，地图按环使用静态像素偏移，既保留真实坐标，又能分别点击。
     */
    internal fun markerRings(markers: List<MapMarker>): Map<Int, Int> {
        if (markers.size < 2) return emptyMap()
        val groups = HashMap<Pair<Long, Long>, MutableList<Int>>()
        markers.forEachIndexed { index, marker ->
            val key = (marker.lat * 1_000_000).roundToLong() to
                (marker.lon * 1_000_000).roundToLong()
            groups.getOrPut(key) { mutableListOf() }.add(index)
        }
        val result = HashMap<Int, Int>()
        groups.values.forEach { indices ->
            if (indices.size < 2) return@forEach
            indices.forEachIndexed { order, index ->
                result[index] = order.coerceAtMost(MarkerRings.COUNT - 1)
            }
        }
        return result
    }

    private fun abbreviate(count: Int): String = when {
        count < 1000 -> count.toString()
        count < 10_000 -> String.format(Locale.US, "%.1fk", count / 1000.0)
        else -> "${count / 1000}k"
    }
}
