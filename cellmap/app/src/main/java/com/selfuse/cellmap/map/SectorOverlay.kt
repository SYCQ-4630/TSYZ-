package com.selfuse.cellmap.map

import com.selfuse.cellmap.core.model.SectorCell
import com.selfuse.cellmap.core.model.SectorGeometry
import org.json.JSONArray
import org.json.JSONObject

object SectorOverlay {

    private const val PROP_STYLE = "style"
    private const val PROP_NAME = "name"

    fun geoJson(cells: List<SectorCell>): String {
        val features = JSONArray()
        cells.take(SectorGeometry.MAX_CELLS).forEach { cell ->
            val ring = JSONArray()
            SectorGeometry.wedge(cell.lat, cell.lon, cell.azimuth, cell.radiusMeters).forEach { (lat, lon) ->
                ring.put(JSONArray().put(lon).put(lat))
            }
            val properties = JSONObject()
                .put(PROP_STYLE, cell.styleKey)
                .put(PROP_NAME, cell.cellName)
                .put("azimuth", cell.azimuth)
                .put("system", cell.system)
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("properties", properties)
                    .put(
                        "geometry",
                        JSONObject()
                            .put("type", "Polygon")
                            .put("coordinates", JSONArray().put(ring))
                    )
            )
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()
    }
}
