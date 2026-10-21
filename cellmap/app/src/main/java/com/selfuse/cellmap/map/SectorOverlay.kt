package com.selfuse.cellmap.map

import com.selfuse.cellmap.core.model.SectorCell
import com.selfuse.cellmap.core.model.SectorGeometry
import org.json.JSONArray
import org.json.JSONObject

object SectorOverlay {

    private const val PROP_STYLE = "style"
    private const val PROP_NAME = "name"
    private const val PROP_KIND = "kind"
    private const val PROP_ID = "sid"
    private const val KIND_POLYGON = "polygon"
    private const val KIND_LABEL = "label"

    /** 名称标签放在扇区中轴线上，位于半径的 58% 处，兼顾可读性与点击命中。 */
    private const val LABEL_RADIUS_RATIO = 0.58

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
                    .put("properties", properties.put(PROP_KIND, KIND_POLYGON))
                    .put(
                        "geometry",
                        JSONObject()
                            .put("type", "Polygon")
                            .put("coordinates", JSONArray().put(ring))
                    )
            )
            val (labelLat, labelLon) = SectorGeometry.destination(
                cell.lat,
                cell.lon,
                cell.azimuth,
                cell.radiusMeters * LABEL_RADIUS_RATIO
            )
            val labelProperties = JSONObject()
                .put(PROP_STYLE, cell.styleKey)
                .put(PROP_NAME, cell.cellName)
                .put(PROP_ID, cell.id.toString())
                .put(PROP_KIND, KIND_LABEL)
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("properties", labelProperties)
                    .put(
                        "geometry",
                        JSONObject()
                            .put("type", "Point")
                            .put("coordinates", JSONArray().put(labelLon).put(labelLat))
                    )
            )
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()
    }
}
