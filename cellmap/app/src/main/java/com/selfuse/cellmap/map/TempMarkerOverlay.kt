package com.selfuse.cellmap.map

import com.selfuse.cellmap.core.model.TempMarker
import org.json.JSONArray
import org.json.JSONObject

object TempMarkerOverlay {

    fun iconName(id: Long) = "temp_$id"

    fun geoJson(markers: List<TempMarker>): String {
        val features = JSONArray()
        markers.forEach { marker ->
            val properties = JSONObject()
                .put("id", marker.id)
                .put("content", marker.content)
                .put("icon", iconName(marker.id))
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("properties", properties)
                    .put(
                        "geometry",
                        JSONObject()
                            .put("type", "Point")
                            .put("coordinates", JSONArray().put(marker.lon).put(marker.lat))
                    )
            )
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()
    }
}
