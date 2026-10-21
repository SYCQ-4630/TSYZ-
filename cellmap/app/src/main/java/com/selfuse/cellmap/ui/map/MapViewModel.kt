package com.selfuse.cellmap.ui.map

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.MapCluster
import com.selfuse.cellmap.core.model.DenseGroup
import com.selfuse.cellmap.core.model.MapMarker
import com.selfuse.cellmap.core.model.MarkerFilters
import com.selfuse.cellmap.core.model.MarkerStyle
import com.selfuse.cellmap.core.model.NetworkSystem
import com.selfuse.cellmap.core.model.ReadinessState
import com.selfuse.cellmap.core.model.SearchHit
import com.selfuse.cellmap.core.model.SectorCell
import com.selfuse.cellmap.core.model.SectorGeometry
import com.selfuse.cellmap.core.model.TempMarker
import com.selfuse.cellmap.core.util.GeoBounds
import com.selfuse.cellmap.data.db.ImportBatchEntity
import com.selfuse.cellmap.data.db.StationEntity
import com.selfuse.cellmap.data.key.ProbeResult
import com.selfuse.cellmap.data.key.SessionKey
import com.selfuse.cellmap.map.DenseGroups
import com.selfuse.cellmap.map.GeoJsonBuilder
import com.selfuse.cellmap.map.SectorOverlay
import com.selfuse.cellmap.map.TempMarkerOverlay
import com.selfuse.cellmap.data.repo.TemporaryMarkerStore
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
import kotlin.math.cos
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
    val denseGroups: List<DenseGroup> = emptyList(),
    val denseJson: String = "",
    val usingOptimized: Boolean = false,
    val truncated: Boolean = false,
    val loading: Boolean = false,
    val system: NetworkSystem = NetworkSystem.FOUR_G,
    val filters: MarkerFilters = MarkerFilters(),
    val districts: List<String> = emptyList(),
    val bands: List<String> = emptyList(),
    val batches: List<ImportBatchEntity> = emptyList(),
    val selectedBatchId: Long? = null,
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
    val textureMode: Boolean = false,
    val expandedSearchId: Long? = null,
    val expandedStation: StationEntity? = null,
    val sectorVisible: Boolean = false,
    val sectorBusy: Boolean = false,
    val sectorCount: Int = 0,
    val sectorTotal: Int = 0,
    val selectedSectorActive: Boolean = false,
    val sectorJson: String = "",
    val sectorLabelJson: String = "",
    val tempMarkers: List<TempMarker> = emptyList(),
    val tempJson: String = ""
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
    private var selectionSeq = 0L
    private val sectorGroups = LinkedHashMap<String, SectorGroup>()
    private var sectorGeneration = 0L

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
        viewModelScope.launch {
            c.importRepository.batches.collect { all ->
                val batches = all.filter { it.successRows > 0 }
                val current = _state.value.selectedBatchId
                val valid = current?.takeIf { id -> batches.any { it.id == id } }
                _state.update { it.copy(batches = batches, selectedBatchId = valid) }
                if (valid != current) {
                    reloadViewport()
                    runSearch()
                }
            }
        }
        viewModelScope.launch {
            c.temporaryMarkers.markers.collect { markers ->
                val json = withContext(Dispatchers.Default) {
                    TempMarkerOverlay.geoJson(markers)
                }
                _state.update { it.copy(tempMarkers = markers, tempJson = json) }
            }
        }
        refreshBasemap()
        loadFilterOptions()
    }

    fun setBatch(batchId: Long?) {
        if (_state.value.selectedBatchId == batchId) return
        selectionSeq += 1
        _state.update {
            it.copy(
                selectedBatchId = batchId,
                selected = null,
                searchResults = emptyList(),
                expandedSearchId = null,
                expandedStation = null,
                sectorVisible = false,
                sectorBusy = false,
                sectorCount = 0,
                sectorTotal = 0,
                selectedSectorActive = false,
                sectorJson = "",
                sectorLabelJson = "",
                denseGroups = emptyList(),
                denseJson = ""
            )
        }
        sectorGroups.clear()
        sectorGeneration += 1
        reloadViewport()
        runSearch()
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
                    optimizeReady = c.readiness.state.value.optimizeReady &&
                        _state.value.selectedBatchId == null,
                    zoom = cameraZoom,
                    batchId = _state.value.selectedBatchId
                )
            }.getOrElse { error ->
                _state.update { it.copy(loading = false, message = error.message ?: "加载视口失败") }
                return@launch
            }
            // 分组与 GeoJSON 序列化都放到后台线程，避免大结果集在主线程卡住地图手势。
            val (denseGroups, json) = withContext(Dispatchers.Default) {
                val groups = if (cameraZoom >= DenseGroups.MIN_ZOOM) {
                    DenseGroups.build(snapshot.markers, cameraZoom)
                } else {
                    emptyList()
                }
                val groupedIds = if (groups.isEmpty()) {
                    emptySet()
                } else {
                    groups.flatMapTo(HashSet()) { group -> group.markers.map { it.id } }
                }
                val visibleMarkers = if (groupedIds.isEmpty()) {
                    snapshot.markers
                } else {
                    snapshot.markers.filterNot { it.id in groupedIds }
                }
                groups to Triple(
                    GeoJsonBuilder.markers(visibleMarkers),
                    GeoJsonBuilder.clusters(snapshot.clusters),
                    GeoJsonBuilder.denseGroups(groups)
                )
            }
            val (markersJson, clustersJson, denseJson) = json
            _state.update { current ->
                if (current.markersJson == markersJson && current.clustersJson == clustersJson &&
                    current.denseJson == denseJson && current.denseGroups == denseGroups &&
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
                        denseGroups = denseGroups,
                        denseJson = denseJson,
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
        selectionSeq += 1
        _state.update {
            it.copy(
                system = system,
                searchResults = emptyList(),
                expandedSearchId = null,
                expandedStation = null,
                sectorVisible = false,
                sectorBusy = false,
                sectorCount = 0,
                sectorTotal = 0,
                selectedSectorActive = false,
                sectorJson = "",
                sectorLabelJson = "",
                denseGroups = emptyList(),
                denseJson = ""
            )
        }
        sectorGroups.clear()
        sectorGeneration += 1
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
        _state.update {
            it.copy(
                searchQuery = query,
                expandedSearchId = null,
                expandedStation = null
            )
        }
        runSearch()
    }

    fun clearQuery() {
        _state.update {
            it.copy(
                searchQuery = "",
                searchResults = emptyList(),
                searching = false,
                expandedSearchId = null,
                expandedStation = null
            )
        }
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
                c.stationRepository.search(
                    query = query,
                    system = _state.value.system,
                    filters = _state.value.filters,
                    batchId = _state.value.selectedBatchId
                )
            }.getOrDefault(emptyList())
            if (_state.value.searchQuery == query) {
                _state.update { it.copy(searching = false, searchResults = hits) }
            }
        }
    }

    fun selectSearch(hit: SearchHit) {
        focus(hit.lat, hit.lon, 15.5)
        selectStation(hit.id)
        _state.update {
            it.copy(
                searchResults = emptyList(),
                expandedSearchId = null,
                expandedStation = null
            )
        }
    }

    fun toggleSearchDetail(hit: SearchHit) {
        if (_state.value.expandedSearchId == hit.id) {
            _state.update { it.copy(expandedSearchId = null, expandedStation = null) }
            return
        }
        _state.update { it.copy(expandedSearchId = hit.id, expandedStation = null) }
        viewModelScope.launch {
            val station = runCatching { c.stationRepository.byId(hit.id) }.getOrNull()
            if (_state.value.expandedSearchId != hit.id) return@launch
            if (station == null) {
                _state.update {
                    it.copy(expandedSearchId = null, message = "未能读取基站详情")
                }
            } else {
                _state.update { it.copy(expandedStation = station) }
            }
        }
    }

    fun selectStation(id: Long) {
        val seq = ++selectionSeq
        viewModelScope.launch {
            val station = runCatching { c.stationRepository.byId(id) }.getOrNull()
            if (seq != selectionSeq) return@launch
            val sectors = station?.let {
                runCatching { c.stationRepository.sectorCellCountAt(it.lat, it.lon) }.getOrDefault(0)
            } ?: 0
            if (seq != selectionSeq) return@launch
            _state.update {
                it.copy(
                    selected = station,
                    sectorBusy = false,
                    sectorCount = sectors,
                    selectedSectorActive = station != null &&
                        sectorGroups.containsKey(sectorKey(station.lat, station.lon))
                )
            }
        }
    }

    /**
     * 扇区覆盖默认关闭，仅在用户点击后查询同坐标的小区并生成几何。
     * 同一坐标上的多个标识点会一起展开，不同坐标互不影响；
     * 数量受 [SectorGeometry.MAX_CELLS] 限制，避免大量绘制拖慢地图。
     */
    fun toggleSectors() {
        val station = _state.value.selected ?: return
        if (_state.value.sectorBusy) return
        val key = sectorKey(station.lat, station.lon)
        if (sectorGroups.containsKey(key)) {
            sectorGroups.remove(key)
            viewModelScope.launch {
                rebuildSectors()
                _state.update { it.copy(selectedSectorActive = false, sectorBusy = false) }
            }
            return
        }
        if (sectorGroups.size >= MAX_SECTOR_GROUPS) {
            _state.update { it.copy(message = "最多同时展开 $MAX_SECTOR_GROUPS 个位置的扇区") }
            return
        }
        val seq = selectionSeq
        viewModelScope.launch {
            _state.update { it.copy(sectorBusy = true) }
            val entities = runCatching { c.stationRepository.sectorCellsAt(station.lat, station.lon) }
                .getOrDefault(emptyList())
            if (seq != selectionSeq) return@launch
            val radius = sectorRadiusMeters(station.lat)
            val sectors = entities.mapNotNull { entity ->
                val azimuth = SectorGeometry.parseAzimuth(entity.azimuth) ?: return@mapNotNull null
                val style = MarkerStyle.fromKey(entity.markerStyle) ?: return@mapNotNull null
                SectorCell(
                    id = entity.id,
                    lat = entity.lat,
                    lon = entity.lon,
                    azimuth = azimuth,
                    styleKey = style.key,
                    cellName = entity.cellName ?: entity.cellId,
                    system = entity.system,
                    radiusMeters = radius
                )
            }.take(SectorGeometry.MAX_CELLS)
            if (sectors.isEmpty()) {
                _state.update {
                    it.copy(sectorBusy = false, message = "该位置没有可用的方位角数据")
                }
                return@launch
            }
            sectorGroups[key] = SectorGroup(sectors)
            val ok = rebuildSectors()
            val stillActive = ok && sectorGroups.containsKey(key)
            _state.update {
                it.copy(
                    sectorBusy = false,
                    sectorCount = if (stillActive) sectors.size else 0,
                    selectedSectorActive = stillActive
                )
            }
        }
    }

    fun clearSectors() {
        if (sectorGroups.isEmpty() &&
            _state.value.sectorJson.isBlank() &&
            _state.value.sectorLabelJson.isBlank()
        ) return
        selectionSeq += 1
        sectorGroups.clear()
        sectorGeneration += 1
        _state.update {
            it.copy(
                sectorVisible = false,
                sectorBusy = false,
                sectorCount = 0,
                sectorTotal = 0,
                selectedSectorActive = false,
                sectorJson = "",
                sectorLabelJson = ""
            )
        }
    }

    /** 重建全部扇区，返回是否仍有可见扇区。 */
    private suspend fun rebuildSectors(): Boolean {
        val generation = ++sectorGeneration
        val cells = sectorGroups.values.flatMap { it.cells }
        val (json, labelJson) = if (cells.isEmpty()) {
            "" to ""
        } else withContext(Dispatchers.Default) {
            val shapes = runCatching { SectorOverlay.geoJson(cells) }.getOrDefault("")
            val labels = runCatching { SectorOverlay.labelGeoJson(cells) }.getOrDefault("")
            shapes to labels
        }
        if (generation != sectorGeneration) return _state.value.sectorVisible
        val visible = json.isNotBlank()
        _state.update {
            it.copy(
                sectorJson = json,
                sectorLabelJson = labelJson,
                sectorVisible = visible,
                sectorTotal = if (visible) cells.size else 0
            )
        }
        return visible
    }

    private fun sectorKey(lat: Double, lon: Double): String =
        SectorGeometry.positionKey(lat, lon)

    private data class SectorGroup(val cells: List<SectorCell>)

    private fun sectorRadiusMeters(lat: Double): Double {
        val zoom = cameraZoom.coerceIn(3.0, 20.0)
        val metersPerPixel = 156_543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)
        return (metersPerPixel * 80).coerceIn(50.0, 600.0)
    }

    fun focusOn(lat: Double, lon: Double, zoom: Double = 16.0) {
        focus(lat, lon, zoom)
        clearSelection()
    }

    fun clearSelection() {
        selectionSeq += 1
        _state.update {
            it.copy(
                selected = null,
                sectorBusy = false,
                sectorCount = 0,
                selectedSectorActive = false
            )
        }
    }

    fun addTempMarker(lat: Double, lon: Double, content: String) {
        if (!c.temporaryMarkers.add(lat, lon, content)) {
            _state.update {
                it.copy(message = "最多添加 ${TemporaryMarkerStore.MAX_MARKERS} 个临时标识")
            }
        }
    }

    fun updateTempMarker(id: Long, content: String) {
        c.temporaryMarkers.update(id, content)
    }

    fun removeTempMarker(id: Long) {
        c.temporaryMarkers.remove(id)
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
        const val MAX_SECTOR_GROUPS = 20

        fun factory(app: CellMapApp) = viewModelFactory {
            initializer { MapViewModel(app) }
        }
    }
}
