package com.selfuse.cellmap.ui.map

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
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
import com.selfuse.cellmap.data.prefs.StoredMapState
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
    val annotationsVisible: Boolean = true
) {
    val canRender: Boolean get() = readiness.dataReady && readiness.labelReady
}

class MapViewModel(
    app: CellMapApp,
    private val handle: SavedStateHandle
) : ViewModel() {
    private val c = app.container

    private val initialSystem = NetworkSystem.entries
        .firstOrNull { it.name == handle.get<String>(KEY_SYSTEM) }
        ?: NetworkSystem.FOUR_G

    private val initialFilters = MarkerFilters(
        styles = handle.get<String>(KEY_STYLES)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty(),
        district = handle.get<String>(KEY_DISTRICT).orEmpty(),
        band = handle.get<String>(KEY_BAND).orEmpty(),
        category = handle.get<String>(KEY_CATEGORY).orEmpty()
    )

    private val _state = MutableStateFlow(
        MapUiState(
            system = initialSystem,
            filters = initialFilters,
            searchQuery = handle.get<String>(KEY_QUERY).orEmpty(),
            centerLat = handle.get<Double>(KEY_LAT) ?: 34.5,
            centerLon = handle.get<Double>(KEY_LON) ?: 108.9,
            zoom = handle.get<Double>(KEY_ZOOM) ?: 4.0,
            annotationsVisible = handle.get<Boolean>(KEY_ANNOTATIONS) ?: true
        )
    )
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var currentBounds: GeoBounds? = null
    private var viewportJob: Job? = null
    private var searchJob: Job? = null
    private var focusCounter = 0L
    private var lastKeyReady: Boolean? = null
    private var basemapSession: SessionKey? = null
    private var lastUsageAt = 0L
    private var persistJob: Job? = null
    private var restoreCompleted = false
    private var cameraLat = _state.value.centerLat
    private var cameraLon = _state.value.centerLon
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
        refreshBasemap()
        loadFilterOptions()
        val restoredQuery = _state.value.searchQuery
        if (restoredQuery.isNotBlank()) runSearch()
        val restoredSelected = handle.get<Long>(KEY_SELECTED) ?: 0L
        if (restoredSelected > 0L) selectStation(restoredSelected)
        restoreLastSession()
    }

    /**
     * 恢复上次退出时的地图视野、筛选与查询。SavedStateHandle 中的值来自当前导航栈，
     * 优先级更高；只有缺失的字段才用磁盘快照补齐。读取失败时保持默认状态。
     */
    private fun restoreLastSession() {
        viewModelScope.launch {
            try {
                val saved = runCatching { c.prefs.mapState() }.getOrNull()
                if (saved != null && saved.savedAt > 0L) {
                    val hasCamera = handle.contains(KEY_LAT)
                    val hasSystem = handle.contains(KEY_SYSTEM)
                    val hasFilters = handle.contains(KEY_STYLES) || handle.contains(KEY_DISTRICT) ||
                        handle.contains(KEY_BAND) || handle.contains(KEY_CATEGORY)
                    val hasQuery = handle.contains(KEY_QUERY)
                    val hasSelected = handle.contains(KEY_SELECTED)
                    val hasAnnotations = handle.contains(KEY_ANNOTATIONS)
                    val lat = saved.centerLat.coerceIn(-85.0, 85.0)
                    val lon = saved.centerLon.coerceIn(-180.0, 180.0)
                    val zoom = saved.zoom.coerceIn(3.0, 19.0)
                    val savedSystem = NetworkSystem.entries.firstOrNull { it.name == saved.system }
                    _state.update { current ->
                        current.copy(
                            centerLat = if (hasCamera) current.centerLat else lat,
                            centerLon = if (hasCamera) current.centerLon else lon,
                            zoom = if (hasCamera) current.zoom else zoom,
                            system = if (hasSystem) current.system else savedSystem ?: current.system,
                            filters = if (hasFilters) {
                                current.filters
                            } else {
                                MarkerFilters(
                                    styles = saved.styles.toSet(),
                                    district = saved.district,
                                    band = saved.band,
                                    category = saved.category
                                )
                            },
                            searchQuery = if (hasQuery) current.searchQuery else saved.query,
                            annotationsVisible = if (hasAnnotations) {
                                current.annotationsVisible
                            } else {
                                saved.annotationsVisible
                            }
                        )
                    }
                    if (!hasCamera) focus(lat, lon, zoom)
                    if (!hasQuery && saved.query.isNotBlank()) runSearch()
                    if (!hasSelected && saved.selectedId > 0L) selectStation(saved.selectedId)
                    reloadViewport()
                }
            } finally {
                restoreCompleted = true
            }
        }
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
        // 相机位置不写入 Compose 状态，避免每次平移结束都触发整屏重组；
        // 仅保留在普通字段中供查询与持久化使用。
        cameraLat = centerLat
        cameraLon = centerLon
        cameraZoom = zoom
        handle[KEY_ZOOM] = zoom
        handle[KEY_LAT] = centerLat
        handle[KEY_LON] = centerLon
        reloadViewport(debounceMs = 220)
        recordTileUsage()
        schedulePersist()
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
        handle[KEY_SYSTEM] = system.name
        reloadViewport()
        runSearch()
        schedulePersist()
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
        handle[KEY_STYLES] = filters.styles.joinToString(",")
        handle[KEY_DISTRICT] = filters.district
        handle[KEY_BAND] = filters.band
        handle[KEY_CATEGORY] = filters.category
        reloadViewport()
        runSearch()
        schedulePersist()
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
        handle[KEY_QUERY] = query
        runSearch()
        schedulePersist()
    }

    fun clearQuery() {
        _state.update { it.copy(searchQuery = "", searchResults = emptyList(), searching = false) }
        handle[KEY_QUERY] = ""
        searchJob?.cancel()
        schedulePersist()
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
        handle[KEY_SELECTED] = id
        schedulePersist()
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
        handle[KEY_SELECTED] = 0L
        _state.update { it.copy(selected = null) }
        schedulePersist()
    }

    fun setAnnotationsVisible(visible: Boolean) {
        if (_state.value.annotationsVisible == visible) return
        _state.update { it.copy(annotationsVisible = visible) }
        handle[KEY_ANNOTATIONS] = visible
        schedulePersist()
    }

    private fun schedulePersist() {
        if (!restoreCompleted) return
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(700)
            persistNow()
        }
    }

    private fun persistNow() {
        if (!restoreCompleted) return
        val snapshot = snapshot()
        c.applicationScope.launch {
            runCatching { c.prefs.saveMapState(snapshot) }
        }
    }

    private fun snapshot(): StoredMapState {
        val current = _state.value
        return StoredMapState(
            centerLat = cameraLat,
            centerLon = cameraLon,
            zoom = cameraZoom,
            system = current.system.name,
            styles = current.filters.styles.toList(),
            district = current.filters.district,
            band = current.filters.band,
            category = current.filters.category,
            query = current.searchQuery,
            selectedId = current.selected?.id ?: 0L,
            annotationsVisible = current.annotationsVisible,
            savedAt = System.currentTimeMillis()
        )
    }

    override fun onCleared() {
        persistJob?.cancel()
        if (restoreCompleted) {
            val snapshot = snapshot()
            c.applicationScope.launch {
                runCatching { c.prefs.saveMapState(snapshot) }
            }
        }
        super.onCleared()
    }

    private fun focus(lat: Double, lon: Double, zoom: Double) {
        cameraLat = lat
        cameraLon = lon
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
        private const val KEY_SYSTEM = "system"
        private const val KEY_STYLES = "styles"
        private const val KEY_DISTRICT = "district"
        private const val KEY_BAND = "band"
        private const val KEY_CATEGORY = "category"
        private const val KEY_QUERY = "query"
        private const val KEY_SELECTED = "selected"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_ANNOTATIONS = "annotations"

        fun factory(app: CellMapApp) = viewModelFactory {
            initializer { MapViewModel(app, createSavedStateHandle()) }
        }
    }
}
