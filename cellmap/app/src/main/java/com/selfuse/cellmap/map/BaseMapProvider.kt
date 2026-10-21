package com.selfuse.cellmap.map

import com.selfuse.cellmap.data.key.KeyRepository
import com.selfuse.cellmap.data.key.ProbeResult
import com.selfuse.cellmap.data.key.SessionKey
import com.selfuse.cellmap.data.key.TiandituTiles
import com.selfuse.cellmap.data.repo.AppLogger
import org.json.JSONArray
import org.json.JSONObject

data class BasemapConfig(
    val styleJson: String,
    val sessionKey: SessionKey?,
    val attribution: String,
    val keyReady: Boolean
)

/**
 * 构造 MapLibre 样式。没有 Key 时仍生成包含标注层的纯色样式，避免白屏；
 * 运行时连续鉴权失败时只隐藏 raster 图层，标注与交互保持可用。
 */
class BaseMapProvider(
    private val keyRepository: KeyRepository,
    private val tiles: TiandituTiles,
    private val logger: AppLogger
) {
    suspend fun prepare(): BasemapConfig {
        val session = keyRepository.sessionKey()
        if (session == null) {
            return BasemapConfig(
                styleJson = buildStyle(null, null, null),
                sessionKey = null,
                attribution = "天地图 Key 未就绪",
                keyReady = false
            )
        }
        val urls = tiles.tileUrls(session.plain)
        val annotations = runCatching { tiles.annotationUrls(session.plain) }.getOrNull()
        return BasemapConfig(
            styleJson = buildStyle(urls.urls, annotations, urls.attribution),
            sessionKey = session,
            attribution = if (annotations != null) "${urls.attribution} · 含注记" else urls.attribution,
            keyReady = true
        )
    }

    suspend fun probe(key: SessionKey): ProbeResult = tiles.probe(key.plain)

    suspend fun recordTileUsage(key: SessionKey, estimatedTiles: Int) {
        keyRepository.recordTileUsage(key.id, estimatedTiles.coerceIn(1, 200))
    }

    suspend fun markKeyInvalid(key: SessionKey, reason: String) {
        keyRepository.markInvalid(key.id, reason)
        logger.log(com.selfuse.cellmap.data.db.LogModule.KEY, com.selfuse.cellmap.data.db.LogLevel.ERROR, "底图鉴权失败", reason)
    }

    private fun buildStyle(
        tileUrls: List<String>?,
        annotationUrls: List<String>?,
        attribution: String?
    ): String {
        val root = JSONObject()
            .put("version", 8)
            .put("name", "CellMap Tianditu")
            .put("glyphs", "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf")

        val sources = JSONObject()
        if (tileUrls != null) {
            sources.put(
                "tianditu",
                JSONObject()
                    .put("type", "raster")
                    .put("tiles", JSONArray(tileUrls))
                    .put("tileSize", 256)
                    .put("minzoom", 1)
                    .put("maxzoom", 18)
                    .put("attribution", attribution ?: "")
            )
        }
        if (annotationUrls != null) {
            sources.put(
                SOURCE_ANNOTATION,
                JSONObject()
                    .put("type", "raster")
                    .put("tiles", JSONArray(annotationUrls))
                    .put("tileSize", 256)
                    .put("minzoom", ANNOTATION_MIN_ZOOM)
                    .put("maxzoom", 18)
                    .put("attribution", attribution ?: "")
            )
        }
        root.put("sources", sources)

        val layers = JSONArray()
        layers.put(
            JSONObject()
                .put("id", "background")
                .put("type", "background")
                .put("paint", JSONObject().put("background-color", "#0F1B24"))
        )
        if (tileUrls != null) {
            layers.put(
                JSONObject()
                    .put("id", "tianditu-raster")
                    .put("type", "raster")
                    .put("source", "tianditu")
                    .put("minzoom", 1)
                    .put("maxzoom", 18)
                    .put(
                        "paint",
                        JSONObject()
                            .put("raster-opacity", 1.0)
                            .put("raster-fade-duration", 150)
                    )
            )
        }
        if (annotationUrls != null) {
            layers.put(
                JSONObject()
                    .put("id", LAYER_ANNOTATION)
                    .put("type", "raster")
                    .put("source", SOURCE_ANNOTATION)
                    .put("minzoom", ANNOTATION_MIN_ZOOM)
                    .put("maxzoom", 18)
                    .put(
                        "paint",
                        JSONObject()
                            .put("raster-opacity", 0.92)
                            .put("raster-fade-duration", 120)
                    )
            )
        }

        root.put("layers", layers)
        return root.toString()
    }

    companion object {
        const val SOURCE_ANNOTATION = "tianditu-annotation"
        const val LAYER_ANNOTATION = "tianditu-annotation"
        const val ANNOTATION_MIN_ZOOM = 10
    }
}
