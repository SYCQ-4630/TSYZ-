package com.selfuse.cellmap.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class MapMarker(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val style: MarkerStyle,
    val system: String,
    val title: String,
    val stationId: String,
    val cellId: String
)

@Immutable
data class MapCluster(
    val level: Int,
    val lat: Double,
    val lon: Double,
    val count: Int
)

@Immutable
data class DenseGroup(
    val id: String,
    val lat: Double,
    val lon: Double,
    val markers: List<MapMarker>
) {
    val count: Int get() = markers.size
}

@Immutable
data class ViewportSnapshot(
    val markers: List<MapMarker> = emptyList(),
    val clusters: List<MapCluster> = emptyList(),
    val usingOptimized: Boolean = false,
    val truncated: Boolean = false
)

@Immutable
data class SearchHit(
    val id: Long,
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double,
    val style: MarkerStyle?,
    val system: String
)
