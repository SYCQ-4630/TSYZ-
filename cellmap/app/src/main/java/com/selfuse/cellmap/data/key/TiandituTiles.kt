package com.selfuse.cellmap.data.key

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed interface ProbeResult {
    data class Ok(val bytes: Long) : ProbeResult
    data class AuthFailed(val code: Int) : ProbeResult
    data class NetworkError(val message: String) : ProbeResult
}

data class TiandituTileUrls(val urls: List<String>, val attribution: String)

/**
 * 天地图 WMTS 影像底图（_w 矩阵集）。CGCS2000 与 WGS84 在本工具所需精度内按同一坐标处理，
 * 不做 GCJ-02 偏移。
 */
class TiandituTiles(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {

    fun tileUrls(key: String): TiandituTileUrls {
        val encoded = URLEncoder.encode(key, "UTF-8")
        val urls = (0..7).map { sub ->
            "https://t$sub.tianditu.gov.cn/img_w/wmts" +
                "?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
                "&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles" +
                "&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=$encoded"
        }
        return TiandituTileUrls(
            urls = urls,
            attribution = "天地图 · 影像底图 · 审图号以天地图官网为准"
        )
    }

    fun probeUrl(key: String): String {
        val encoded = URLEncoder.encode(key, "UTF-8")
        return "https://t0.tianditu.gov.cn/img_w/wmts" +
            "?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
            "&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles" +
            "&TILEMATRIX=1&TILEROW=1&TILECOL=1&tk=$encoded"
    }

    suspend fun probe(key: String): ProbeResult {
        val request = Request.Builder()
            .url(probeUrl(key))
            .header("User-Agent", "CellMap/1.0 (self-use)")
            .get()
            .build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 403 || response.code == 418 -> ProbeResult.AuthFailed(response.code)
                        !response.isSuccessful -> ProbeResult.NetworkError("HTTP ${response.code}")
                        else -> {
                            val body = response.body
                            val contentType = body?.contentType()?.toString().orEmpty()
                            if (contentType.contains("image", ignoreCase = true) ||
                                contentType.contains("octet-stream", ignoreCase = true)
                            ) {
                                ProbeResult.Ok(body?.contentLength() ?: 0L)
                            } else {
                                ProbeResult.AuthFailed(response.code)
                            }
                        }
                    }
                }
            }.getOrElse { error ->
                ProbeResult.NetworkError(error.message ?: error.javaClass.simpleName)
            }
        }
    }
}
