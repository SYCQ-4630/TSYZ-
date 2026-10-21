package com.selfuse.cellmap.data.repo

import com.selfuse.cellmap.core.model.TempMarker
import com.selfuse.cellmap.core.model.TempMarkerPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 长按生成的临时标识只保存在内存中，退出应用后清空，不写入台账数据库。 */
class TemporaryMarkerStore {

    private val _markers = MutableStateFlow<List<TempMarker>>(emptyList())
    val markers: StateFlow<List<TempMarker>> = _markers.asStateFlow()
    private var nextId = 1L

    fun add(lat: Double, lon: Double, content: String): Boolean {
        val current = _markers.value
        if (current.size >= MAX_MARKERS) return false
        val id = nextId++
        val normalized = normalize(content)
        _markers.value = current + TempMarker(
            id = id,
            lat = lat,
            lon = lon,
            content = normalized,
            color = TempMarkerPalette.colorFor(id),
            createdAt = System.currentTimeMillis()
        )
        return true
    }

    fun update(id: Long, content: String) {
        val normalized = normalize(content)
        _markers.update { list ->
            list.map { if (it.id == id) it.copy(content = normalized) else it }
        }
    }

    fun remove(id: Long) {
        _markers.update { list -> list.filterNot { it.id == id } }
    }

    private fun normalize(content: String): String =
        content.trim().ifBlank { "临时标识" }.take(MAX_CONTENT)

    companion object {
        const val MAX_MARKERS = 50
        const val MAX_CONTENT = 500
    }
}
