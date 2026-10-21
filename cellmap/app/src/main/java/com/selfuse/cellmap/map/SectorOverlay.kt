package com.selfuse.cellmap.map

import com.selfuse.cellmap.core.model.SectorCell
import com.selfuse.cellmap.core.model.SectorGeometry
import org.json.JSONArray
import org.json.JSONObject

object SectorOverlay {

    private const val PROP_STYLE = "style"
    private const val PROP_NAME = "name"
    private const val PROP_ID = "sid"

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

    /** 小区名称标签使用独立点数据源，避免与面数据混合导致部分渲染器不显示扇区。 */
    fun labelGeoJson(cells: List<SectorCell>): String {
        val features = JSONArray()
        cells.take(SectorGeometry.MAX_CELLS).forEach { cell ->
            val (lat, lon) = SectorGeometry.destination(
                cell.lat,
                cell.lon,
                cell.azimuth,
                cell.radiusMeters * LABEL_RADIUS_RATIO
            )
            val properties = JSONObject()
                .put(PROP_NAME, cell.cellName)
                .put(PROP_ID, cell.id.toString())
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("properties", properties)
                    .put(
                        "geometry",
                        JSONObject()
                            .put("type", "Point")
                            .put("coordinates", JSONArray().put(lon).put(lat))
                    )
            )
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()
    }
}
