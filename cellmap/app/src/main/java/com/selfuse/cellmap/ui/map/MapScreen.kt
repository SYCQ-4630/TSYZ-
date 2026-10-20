package com.selfuse.cellmap.ui.map

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.MarkerStyle
import com.selfuse.cellmap.core.model.NetworkSystem
import com.selfuse.cellmap.core.util.Formats
import com.selfuse.cellmap.data.db.StationEntity
import com.selfuse.cellmap.map.GeoJsonBuilder
import com.selfuse.cellmap.map.MarkerIconCache
import com.selfuse.cellmap.ui.components.AppTopBar
import com.selfuse.cellmap.ui.components.DividerSpacer
import com.selfuse.cellmap.ui.components.KeyValueRow
import com.selfuse.cellmap.ui.components.SectionHeader
import com.selfuse.cellmap.ui.components.StatusPill
import com.selfuse.cellmap.ui.theme.RiskHigh
import com.selfuse.cellmap.ui.theme.RiskLow
import com.selfuse.cellmap.ui.theme.RiskMedium
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale

private const val SOURCE_MARKERS = "cellmap-markers"
private const val SOURCE_CLUSTERS = "cellmap-clusters"
private const val LAYER_MARKERS = "cellmap-marker-layer"
private const val LAYER_CLUSTERS = "cellmap-cluster-layer"
private const val LAYER_CLUSTER_TEXT = "cellmap-cluster-text"
private const val EMPTY_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as CellMapApp
    val vm: MapViewModel = viewModel(factory = MapViewModel.factory(app))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var showFilters by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.consumeMessage()
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val current = lifecycleOwner.lifecycle.currentState
        if (current.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onStart()
            mapView.onResume()
        } else if (current.isAtLeast(Lifecycle.State.STARTED)) {
            mapView.onStart()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(state.centerLat, state.centerLon))
                .zoom(state.zoom)
                .build()
            map.addOnCameraIdleListener {
                val camera = map.cameraPosition
                val bounds = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull()
                    ?: return@addOnCameraIdleListener
                val target = camera.target ?: LatLng(state.centerLat, state.centerLon)
                vm.onCameraIdle(
                    minLat = bounds.latitudeSouth,
                    minLon = bounds.longitudeWest,
                    maxLat = bounds.latitudeNorth,
                    maxLon = bounds.longitudeEast,
                    zoom = camera.zoom,
                    centerLat = target.latitude,
                    centerLon = target.longitude
                )
            }
            map.addOnMapClickListener { latLng ->
                val point = map.projection.toScreenLocation(latLng)
                val clusters = runCatching {
                    map.queryRenderedFeatures(point, LAYER_CLUSTERS, LAYER_CLUSTER_TEXT)
                }.getOrDefault(emptyList())
                val cluster = clusters.firstOrNull()
                if (cluster != null) {
                    val count = cluster.getNumberProperty("point_count")?.toInt() ?: 0
                    val targetZoom = (map.cameraPosition.zoom + if (count > 200) 2.2 else 1.4)
                        .coerceAtMost(16.0)
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(latLng, targetZoom),
                        400
                    )
                    return@addOnMapClickListener true
                }
                val marker = runCatching {
                    map.queryRenderedFeatures(point, LAYER_MARKERS)
                }.getOrDefault(emptyList()).firstOrNull()
                val id = marker?.getStringProperty("sid")?.toLongOrNull()
                if (id != null) {
                    vm.selectStation(id)
                    true
                } else {
                    false
                }
            }
        }
    }

    LaunchedEffect(state.styleJson, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val json = state.styleJson
        if (json.isBlank()) return@LaunchedEffect
        val icons = MarkerIconCache.icons(context)
        map.setStyle(Style.Builder().fromJson(json)) { style ->
            icons.forEach { (name, bitmap) -> style.addImage(name, bitmap) }
            style.addSource(GeoJsonSource(SOURCE_CLUSTERS, EMPTY_COLLECTION))
            style.addSource(GeoJsonSource(SOURCE_MARKERS, EMPTY_COLLECTION))
            style.addLayer(
                CircleLayer(LAYER_CLUSTERS, SOURCE_CLUSTERS).withProperties(
                    circleColor("#0D47A1"),
                    circleOpacity(0.85f),
                    circleRadius(16f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(2.5f)
                )
            )
            style.addLayer(
                SymbolLayer(LAYER_CLUSTER_TEXT, SOURCE_CLUSTERS).withProperties(
                    textField(get("point_count_abbreviated")),
                    textSize(12f),
                    textColor("#FFFFFF"),
                    textAllowOverlap(true),
                    textIgnorePlacement(true),
                    textFont(arrayOf("Noto Sans Regular", "Open Sans Regular"))
                )
            )
            style.addLayer(
                SymbolLayer(LAYER_MARKERS, SOURCE_MARKERS).withProperties(
                    iconImage(get("icon")),
                    iconSize(0.62f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true)
                )
            )
            loadedStyle = style
        }
    }

    LaunchedEffect(state.markers, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        runCatching {
            style.getSourceAs<GeoJsonSource>(SOURCE_MARKERS)
                ?.setGeoJson(GeoJsonBuilder.markers(state.markers))
        }
    }

    LaunchedEffect(state.clusters, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        runCatching {
            style.getSourceAs<GeoJsonSource>(SOURCE_CLUSTERS)
                ?.setGeoJson(GeoJsonBuilder.clusters(state.clusters))
        }
    }

    LaunchedEffect(state.focus?.id, mapLibreMap) {
        val focus = state.focus ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(focus.lat, focus.lon), focus.zoom),
            600
        )
        vm.consumeFocus()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "地图查询",
                subtitle = if (state.keyReady) state.attribution else "底图 Key 未就绪",
                onBack = onBack,
                actions = {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchBar(
                    query = state.searchQuery,
                    searching = state.searching,
                    onChange = vm::onQueryChange,
                    onClear = vm::clearQuery
                )
                if (state.searchQuery.isNotBlank() && state.searchResults.isNotEmpty()) {
                    SearchResults(state, onSelect = vm::selectSearch)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = state.system == NetworkSystem.FOUR_G,
                        onClick = { vm.setSystem(NetworkSystem.FOUR_G) },
                        label = { Text("4G") }
                    )
                    FilterChip(
                        selected = state.system == NetworkSystem.FIVE_G,
                        onClick = { vm.setSystem(NetworkSystem.FIVE_G) },
                        label = { Text("5G") }
                    )
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { showFilters = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.filters.activeCount > 0) "筛选 ${state.filters.activeCount}" else "筛选"
                        )
                    }
                }
                if (!state.keyReady) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = RiskMedium.copy(alpha = 0.92f),
                        contentColor = Color.White
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "天地图 Key 未就绪，底图降级为纯色，标注仍可查询",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            MapStatusCard(
                state = state,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }
    }

    if (showFilters) {
        FilterSheet(
            state = state,
            onToggleStyle = vm::toggleStyle,
            onDistrict = vm::setDistrict,
            onBand = vm::setBand,
            onClear = vm::clearFilters,
            onDismiss = { showFilters = false }
        )
    }

    val selected = state.selected
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = vm::clearSelection) {
            StationDetail(
                station = selected,
                onFocus = { vm.focusOn(selected.lat, selected.lon) },
                onDismiss = vm::clearSelection
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    searching: Boolean,
    onChange: (String) -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("搜索小区名称 / 基站 ID / 小区 ID") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (searching) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (query.isNotBlank()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = "清除")
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SearchResults(state: MapUiState, onSelect: (com.selfuse.cellmap.core.model.SearchHit) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Column {
            state.searchResults.take(6).forEachIndexed { index, hit ->
                if (index > 0) DividerSpacer()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(hit) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        hit.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        hit.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MapStatusCard(state: MapUiState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.clusters.isNotEmpty() -> "${Formats.number(state.clusters.size)} 个聚合"
                        state.markers.isNotEmpty() -> "${Formats.number(state.markers.size)} 个点位"
                        state.canRender -> "当前范围无点位"
                        else -> "数据或标注未就绪"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(
                    if (state.usingOptimized) "优化数据集" else "原始数据",
                    if (state.usingOptimized) RiskLow else RiskMedium
                )
            }
            if (state.truncated) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "点位过多已截断，请放大地图后查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = RiskHigh
                )
            }
            Spacer(Modifier.height(6.dp))
            MarkerStyle.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { style ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(if (style.isSquare) RoundedCornerShape(2.dp) else CircleShape)
                                    .background(Color(style.colorHex))
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                style.label,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    state: MapUiState,
    onToggleStyle: (MarkerStyle) -> Unit,
    onDistrict: (String) -> Unit,
    onBand: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionHeader("颜色与形状")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MarkerStyle.entries.forEach { style ->
                        FilterChip(
                            selected = style.key in state.filters.styles,
                            onClick = { onToggleStyle(style) },
                            label = { Text(style.label) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(if (style.isSquare) RoundedCornerShape(2.dp) else CircleShape)
                                        .background(Color(style.colorHex))
                                )
                            }
                        )
                    }
                }
            }
            SectionHeader("管理区县")
            SelectionDropdown(
                label = "区县",
                selected = state.filters.district,
                options = state.districts,
                onSelect = onDistrict
            )
            SectionHeader("频段")
            SelectionDropdown(
                label = "频段",
                selected = state.filters.band,
                options = state.bands,
                onSelect = onBand
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                    Text("清除区县/频段")
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("完成")
                }
            }
        }
    }
}

@Composable
private fun SelectionDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (selected.isBlank()) "全部$label" else selected,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("全部") },
                onClick = {
                    onSelect("")
                    expanded = false
                }
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun StationDetail(
    station: StationEntity,
    onFocus: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                station.cellName ?: station.siteName ?: station.cellId,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            val style = MarkerStyle.fromKey(station.markerStyle)
            if (style != null) {
                StatusPill(style.label, Color(style.colorHex))
            }
        }
        Spacer(Modifier.height(6.dp))
        KeyValueRow("制式", station.system)
        KeyValueRow("基站 ID", station.stationId)
        KeyValueRow("小区 ID", station.cellId)
        KeyValueRow("承建方", station.builder.orEmpty().ifBlank { "—" })
        KeyValueRow("运营商", station.operator.orEmpty().ifBlank { "—" })
        KeyValueRow("是否共享", station.shared.orEmpty().ifBlank { "未知" })
        KeyValueRow("网元归属", station.owner.orEmpty().ifBlank { "—" })
        KeyValueRow("管理区县", station.district.orEmpty().ifBlank { "—" })
        KeyValueRow("覆盖类别", station.category.orEmpty().ifBlank { "—" })
        KeyValueRow("频段", station.band.orEmpty().ifBlank { "—" })
        KeyValueRow("站址名称", station.siteName.orEmpty().ifBlank { "—" })
        KeyValueRow("天线挂高", station.antennaHeight.orEmpty().ifBlank { "—" })
        KeyValueRow("45G 共设备", station.coDevice45G.orEmpty().ifBlank { "—" })
        KeyValueRow(
            "坐标",
            String.format(Locale.US, "%.6f, %.6f", station.lat, station.lon)
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("关闭")
            }
            Button(onClick = onFocus, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("居中到该点")
            }
        }
    }
}
