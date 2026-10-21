package com.selfuse.cellmap.ui.map

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.MapCluster
import com.selfuse.cellmap.core.model.MapMarker
import com.selfuse.cellmap.core.model.MarkerFilters
import com.selfuse.cellmap.core.model.MarkerStyle
import com.selfuse.cellmap.core.model.NetworkSystem
import com.selfuse.cellmap.core.model.ReadinessState
import com.selfuse.cellmap.core.model.SearchHit
import com.selfuse.cellmap.core.util.GeoBounds
import com.selfuse.cellmap.data.db.StationEntity
import com.selfuse.cellmap.data.key.ProbeResult
import com.selfuse.cellmap.data.key.SessionKey
import com.selfuse.cellmap.map.GeoJsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.pow

@Immutable
data class FocusRequest(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val zoom: Double
)

@Immutable
data class MapUiState(
    val styleJson: String = "",
    val keyReady: Boolean = false,
    val attribution: String = "",
    val markers: List<MapMarker> = emptyList(),
    val clusters: List<MapCluster> = emptyList(),
    val markersJson: String = "",
    val clustersJson: String = "",
    val usingOptimized: Boolean = false,
    val truncated: Boolean = false,
    val loading: Boolean = false,
    val system: NetworkSystem = NetworkSystem.FOUR_G,
    val filters: MarkerFilters = MarkerFilters(),
    val districts: List<String> = emptyList(),
    val bands: List<String> = emptyList(),
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<SearchHit> = emptyList(),
    val selected: StationEntity? = null,
    val readiness: ReadinessState = ReadinessState(),
    val centerLat: Double = 34.5,
    val centerLon: Double = 108.9,
    val zoom: Double = 4.0,
    val focus: FocusRequest? = null,
    val message: String? = null,
    val annotationsVisible: Boolean = true,
    val textureMode: Boolean = false
) {
    val canRender: Boolean get() = readiness.dataReady && readiness.labelReady
}

class MapViewModel(app: CellMapApp) : ViewModel() {
    private val c = app.container

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var currentBounds: GeoBounds? = null
    private var viewportJob: Job? = null
    private var searchJob: Job? = null
    private var focusCounter = 0L
    private var lastKeyReady: Boolean? = null
    private var basemapSession: SessionKey? = null
    private var lastUsageAt = 0L
    private var cameraZoom = _state.value.zoom

    init {
        viewModelScope.launch {
            c.readiness.state.collect { readiness ->
                _state.update { it.copy(readiness = readiness) }
                val previous = lastKeyReady
                lastKeyReady = readiness.keyReady
                if (previous != null && previous != readiness.keyReady) {
                    refreshBasemap()
                }
                reloadViewport()
            }
        }
        viewModelScope.launch {
            c.prefs.mapTextureModeFlow.collect { enabled ->
                _state.update { it.copy(textureMode = enabled) }
            }
        }
        refreshBasemap()
        loadFilterOptions()
    }

    fun refreshBasemap() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { c.baseMapProvider.prepare() } }
                .onSuccess { config ->
                    basemapSession = config.sessionKey
                    config.sessionKey?.let { checkKeyHealth(it) }
                    _state.update {
                        it.copy(
                            styleJson = config.styleJson,
                            keyReady = config.keyReady,
                            attribution = config.attribution
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(message = error.message ?: "底图初始化失败") }
                }
        }
    }

    private fun loadFilterOptions() {
        viewModelScope.launch {
            val districts = runCatching { c.stationRepository.distinctDistricts() }
                .getOrDefault(emptyList())
            val bands = runCatching { c.stationRepository.distinctBands() }
                .getOrDefault(emptyList())
            _state.update { it.copy(districts = districts, bands = bands) }
        }
    }

    fun onCameraIdle(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        zoom: Double,
        centerLat: Double,
        centerLon: Double
    ) {
        currentBounds = GeoBounds(
            minLat = minLat.coerceAtLeast(-85.0),
            minLon = minLon.coerceAtLeast(-180.0),
            maxLat = maxLat.coerceAtMost(85.0),
            maxLon = maxLon.coerceAtMost(180.0)
        )
        // 相机位置不写入 Compose 状态，避免每次平移结束都触发整屏重组。
        cameraZoom = zoom
        reloadViewport(debounceMs = 220)
        recordTileUsage()
    }

    private fun checkKeyHealth(session: SessionKey) {
        viewModelScope.launch {
            when (val result = runCatching { c.baseMapProvider.probe(session) }.getOrNull()) {
                is ProbeResult.AuthFailed -> {
                    c.baseMapProvider.markKeyInvalid(session, "底图探测返回 ${result.code}")
                    refreshBasemap()
                }
                is ProbeResult.Ok -> c.baseMapProvider.recordTileUsage(session, 1)
                else -> Unit
            }
        }
    }

    private fun recordTileUsage() {
        val session = basemapSession ?: return
        val bounds = currentBounds ?: return
        val now = System.currentTimeMillis()
        if (now - lastUsageAt < 15_000L) return
        lastUsageAt = now
        val zoom = cameraZoom
        val tiles = 2.0.pow(zoom.coerceIn(1.0, 18.0))
        val across = ((bounds.maxLon - bounds.minLon) / 360.0 * tiles).coerceAtLeast(1.0)
        val down = ((bounds.maxLat - bounds.minLat) / 180.0 * tiles).coerceAtLeast(1.0)
        val estimated = ceil(across * down).toInt()
        viewModelScope.launch {
            runCatching { c.baseMapProvider.recordTileUsage(session, estimated) }
        }
    }

    private fun reloadViewport(debounceMs: Long = 0) {
        val bounds = currentBounds ?: return
        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            _state.update { it.copy(loading = true) }
            val snapshot = runCatching {
                c.stationRepository.viewport(
                    bounds = bounds,
                    system = _state.value.system,
                    filters = _state.value.filters,
                    optimizeReady = c.readiness.state.value.optimizeReady,
                    zoom = cameraZoom
                )
            }.getOrElse { error ->
                _state.update { it.copy(loading = false, message = error.message ?: "加载视口失败") }
                return@launch
            }
            // GeoJSON 序列化放到后台线程，避免大结果集在主线程卡住地图手势。
            val (markersJson, clustersJson) = withContext(Dispatchers.Default) {
                GeoJsonBuilder.markers(snapshot.markers) to GeoJsonBuilder.clusters(snapshot.clusters)
            }
            _state.update { current ->
                if (current.markersJson == markersJson && current.clustersJson == clustersJson &&
                    current.usingOptimized == snapshot.usingOptimized &&
                    current.truncated == snapshot.truncated
                ) {
                    current.copy(loading = false)
                } else {
                    current.copy(
                        markers = snapshot.markers,
                        clusters = snapshot.clusters,
                        markersJson = markersJson,
                        clustersJson = clustersJson,
                        usingOptimized = snapshot.usingOptimized,
                        truncated = snapshot.truncated,
                        loading = false
                    )
                }
            }
        }
    }

    fun setSystem(system: NetworkSystem) {
        if (_state.value.system == system) return
        _state.update { it.copy(system = system, searchResults = emptyList()) }
        reloadViewport()
        runSearch()
    }

    fun toggleStyle(style: MarkerStyle) {
        val current = _state.value.filters.styles
        val next = if (current.contains(style.key)) current - style.key else current + style.key
        updateFilters(_state.value.filters.copy(styles = next))
    }

    fun setDistrict(value: String) {
        updateFilters(_state.value.filters.copy(district = value))
    }

    fun setBand(value: String) {
        updateFilters(_state.value.filters.copy(band = value))
    }

    fun clearFilters() {
        updateFilters(MarkerFilters(styles = _state.value.filters.styles))
    }

    private fun updateFilters(filters: MarkerFilters) {
        _state.update { it.copy(filters = filters) }
        reloadViewport()
        runSearch()
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
        runSearch()
    }

    fun clearQuery() {
        _state.update { it.copy(searchQuery = "", searchResults = emptyList(), searching = false) }
        searchJob?.cancel()
    }

    private fun runSearch() {
        val query = _state.value.searchQuery
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(240)
            _state.update { it.copy(searching = true) }
            val hits = runCatching {
                c.stationRepository.search(query, _state.value.system, _state.value.filters)
            }.getOrDefault(emptyList())
            if (_state.value.searchQuery == query) {
                _state.update { it.copy(searching = false, searchResults = hits) }
            }
        }
    }

    fun selectSearch(hit: SearchHit) {
        focus(hit.lat, hit.lon, 15.5)
        selectStation(hit.id)
        _state.update { it.copy(searchResults = emptyList()) }
    }

    fun selectStation(id: Long) {
        viewModelScope.launch {
            val station = runCatching { c.stationRepository.byId(id) }.getOrNull()
            _state.update { it.copy(selected = station) }
        }
    }

    fun focusOn(lat: Double, lon: Double, zoom: Double = 16.0) {
        focus(lat, lon, zoom)
        clearSelection()
    }

    fun clearSelection() {
        _state.update { it.copy(selected = null) }
    }

    fun setAnnotationsVisible(visible: Boolean) {
        if (_state.value.annotationsVisible == visible) return
        _state.update { it.copy(annotationsVisible = visible) }
    }

    fun setTextureMode(enabled: Boolean) {
        viewModelScope.launch {
            c.prefs.setMapTextureMode(enabled)
            _state.update { it.copy(message = "渲染模式已切换，重新进入地图生效") }
        }
    }

    private fun focus(lat: Double, lon: Double, zoom: Double) {
        cameraZoom = zoom
        focusCounter += 1
        _state.update { it.copy(focus = FocusRequest(focusCounter, lat, lon, zoom)) }
    }

    fun consumeFocus() {
        _state.update { it.copy(focus = null) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        fun factory(app: CellMapApp) = viewModelFactory {
            initializer { MapViewModel(app) }
        }
    }
}
