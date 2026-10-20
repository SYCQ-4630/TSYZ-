package com.selfuse.cellmap.map

import com.selfuse.cellmap.core.model.MapCluster
import com.selfuse.cellmap.core.model.MapMarker
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object GeoJsonBuilder {

    fun markers(markers: List<MapMarker>): String {
        val features = JSONArray()
        markers.forEach { marker ->
            val properties = JSONObject()
                .put("sid", marker.id.toString())
                .put("style", marker.style.key)
                .put("icon", marker.style.iconName)
                .put("system", marker.system)
                .put("name", marker.title)
                .put("stationId", marker.stationId)
                .put("cellId", marker.cellId)
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

    private fun abbreviate(count: Int): String = when {
        count < 1000 -> count.toString()
        count < 10_000 -> String.format(Locale.US, "%.1fk", count / 1000.0)
        else -> "${count / 1000}k"
    }
}
