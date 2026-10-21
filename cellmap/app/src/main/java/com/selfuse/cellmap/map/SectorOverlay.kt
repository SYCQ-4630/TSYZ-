package com.selfuse.cellmap.map

import androidx.compose.runtime.Immutable
import com.selfuse.cellmap.core.model.SectorCell
import com.selfuse.cellmap.core.model.SectorGeometry
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor

@Immutable
data class SectorLabelIcon(val id: String, val text: String)

data class SectorRender(
    val labelJson: String,
    val leaderJson: String,
    val icons: List<SectorLabelIcon>
)

object SectorOverlay {

    private const val PROP_STYLE = "style"
    private const val PROP_NAME = "name"
    private const val PROP_ID = "sid"
    private const val PROP_ICON = "icon"

    /** 标签位于扇区外侧，引导线从扇区内部连到标签中心。 */
    private const val LABEL_BASE_RATIO = 1.22
    private const val LABEL_TIER_STEP = 0.42
    private const val LABEL_MAX_TIER = 6
    private const val LEADER_START_RATIO = 0.9
    private const val MIN_ANGLE_GAP = 20.0
    private const val TIER_ANGLE_STEP = 6.0
    private const val GROUP_GRID = 100_000.0

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

    /** 生成悬浮名称标签、引导线以及需要预渲染的位图标签清单。 */
    fun render(cells: List<SectorCell>): SectorRender {
        val limited = cells.take(SectorGeometry.MAX_CELLS)
        val placements = placements(limited)
        val labels = JSONArray()
        val leaders = JSONArray()
        val icons = LinkedHashMap<String, SectorLabelIcon>()
        placements.forEach { placement ->
            val icon = SectorLabelIcons.iconName(placement.text)
            icons[icon] = SectorLabelIcon(icon, placement.text)
            labels.put(
                JSONObject()
                    .put("type", "Feature")
                    .put(
                        "properties",
                        JSONObject()
                            .put(PROP_NAME, placement.text)
                            .put(PROP_ID, placement.cell.id.toString())
                            .put(PROP_ICON, icon)
                    )
                    .put(
                        "geometry",
                        JSONObject()
                            .put("type", "Point")
                            .put(
                                "coordinates",
                                JSONArray().put(placement.labelLon).put(placement.labelLat)
                            )
                    )
            )
            leaders.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("properties", JSONObject())
                    .put(
                        "geometry",
                        JSONObject()
                            .put("type", "LineString")
                            .put(
                                "coordinates",
                                JSONArray()
                                    .put(
                                        JSONArray()
                                            .put(placement.fromLon)
                                            .put(placement.fromLat)
                                    )
                                    .put(
                                        JSONArray()
                                            .put(placement.labelLon)
                                            .put(placement.labelLat)
                                    )
                            )
                    )
            )
        }
        return SectorRender(
            labelJson = JSONObject()
                .put("type", "FeatureCollection")
                .put("features", labels)
                .toString(),
            leaderJson = JSONObject()
                .put("type", "FeatureCollection")
                .put("features", leaders)
                .toString(),
            icons = icons.values.toList()
        )
    }

    private fun placements(cells: List<SectorCell>): List<Placement> {
        val groups = LinkedHashMap<String, MutableList<SectorCell>>()
        cells.forEach { cell ->
            val key = "${floor(cell.lat * GROUP_GRID).toLong()}:${floor(cell.lon * GROUP_GRID).toLong()}"
            groups.getOrPut(key) { mutableListOf() }.add(cell)
        }
        val result = ArrayList<Placement>(cells.size)
        groups.values.forEach { group ->
            group.sortWith(compareBy({ it.azimuth }, { it.id }))
            var previousAzimuth = Double.NaN
            var tier = 0
            group.forEach { cell ->
                tier = if (
                    !previousAzimuth.isNaN() &&
                    angularGap(previousAzimuth, cell.azimuth) < MIN_ANGLE_GAP
                ) {
                    tier + 1
                } else {
                    0
                }
                previousAzimuth = cell.azimuth
                val distanceTier = tier.coerceAtMost(LABEL_MAX_TIER)
                val swing = if (tier == 0) {
                    0.0
                } else {
                    ((tier + 1) / 2) * TIER_ANGLE_STEP * if (tier % 2 == 1) 1.0 else -1.0
                }
                val (labelLat, labelLon) = SectorGeometry.destination(
                    cell.lat,
                    cell.lon,
                    cell.azimuth + swing,
                    cell.radiusMeters * (LABEL_BASE_RATIO + LABEL_TIER_STEP * distanceTier)
                )
                val (fromLat, fromLon) = SectorGeometry.destination(
                    cell.lat,
                    cell.lon,
                    cell.azimuth,
                    cell.radiusMeters * LEADER_START_RATIO
                )
                val text = cell.cellName.ifBlank { "未命名小区" }
                result.add(
                    Placement(
                        cell = cell,
                        text = text,
                        labelLat = labelLat,
                        labelLon = labelLon,
                        fromLat = fromLat,
                        fromLon = fromLon
                    )
                )
            }
        }
        return result
    }

    private fun angularGap(first: Double, second: Double): Double {
        val diff = abs(first - second) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    private data class Placement(
        val cell: SectorCell,
        val text: String,
        val labelLat: Double,
        val labelLon: Double,
        val fromLat: Double,
        val fromLon: Double
    )
}
