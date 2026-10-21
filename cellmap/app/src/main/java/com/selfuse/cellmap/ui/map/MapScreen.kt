package com.selfuse.cellmap.ui.map

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.DenseGroup
import com.selfuse.cellmap.core.model.MarkerStyle
import com.selfuse.cellmap.core.model.MapMarker
import com.selfuse.cellmap.core.model.NetworkSystem
import com.selfuse.cellmap.core.util.Formats
import com.selfuse.cellmap.data.db.StationEntity
import com.selfuse.cellmap.diagnostics.CrashDiagnostics
import com.selfuse.cellmap.map.BaseMapProvider
import com.selfuse.cellmap.map.MapLibreRuntime
import com.selfuse.cellmap.map.MarkerIconCache
import com.selfuse.cellmap.map.MarkerRings
import com.selfuse.cellmap.map.MarkerScaling
import com.selfuse.cellmap.map.SectorLabelIcons
import com.selfuse.cellmap.map.TempMarkerIcons
import com.selfuse.cellmap.map.TempMarkerOverlay
import com.selfuse.cellmap.ui.components.AppTopBar
import com.selfuse.cellmap.ui.components.DividerSpacer
import com.selfuse.cellmap.ui.components.KeyValueRow
import com.selfuse.cellmap.ui.components.SectionHeader
import com.selfuse.cellmap.ui.components.StatusPill
import com.selfuse.cellmap.ui.theme.RiskHigh
import com.selfuse.cellmap.ui.theme.RiskLow
import com.selfuse.cellmap.ui.theme.RiskMedium
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.GeoJsonSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.hypot

private const val SOURCE_MARKERS = "cellmap-markers"
private const val SOURCE_SECTORS = "cellmap-sectors"
private const val SOURCE_SECTOR_LABELS = "cellmap-sector-labels"
private const val SOURCE_SECTOR_LEADERS = "cellmap-sector-leaders"
private const val SOURCE_TEMP = "cellmap-temp"
private const val SOURCE_CLUSTERS = "cellmap-clusters"
private const val SOURCE_DENSE = "cellmap-dense"
private const val LAYER_DENSE = "cellmap-dense-layer"
private const val LAYER_DENSE_TEXT = "cellmap-dense-text"
private const val LAYER_MARKERS_PREFIX = "cellmap-marker-layer"
private const val LAYER_SECTOR_LINE = "cellmap-sector-line"
private const val LAYER_SECTOR_LABEL = "cellmap-sector-label"
private const val LAYER_SECTOR_LEADER = "cellmap-sector-leader"
private const val LAYER_TEMP = "cellmap-temp-layer"

private const val LONG_PRESS_MILLIS = 1_500L

private fun markerLayerId(ring: Int) = "$LAYER_MARKERS_PREFIX-$ring"
private val markerLayerIds = (0 until MarkerRings.COUNT).map(::markerLayerId)

private fun sectorFillLayerId(styleKey: String) = "cellmap-sector-fill-$styleKey"
private const val LAYER_CLUSTERS = "cellmap-cluster-layer"
private const val LAYER_CLUSTER_TEXT = "cellmap-cluster-text"
private const val EMPTY_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"
private const val HIDE_MARKERS_THRESHOLD = 600

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as CellMapApp
    val vm: MapViewModel = viewModel(factory = MapViewModel.factory(app))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapViewError by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(context) {
        val init = runCatching {
            CrashDiagnostics.log(context, "MapScreen initializing MapLibre")
            MapLibreRuntime.ensureInitialized(context)
        }
        val initFailure = init.exceptionOrNull()
        if (initFailure != null) {
            mapViewError = initFailure
            return@LaunchedEffect
        }
        runCatching {
            CrashDiagnostics.log(context, "MapScreen creating MapView")
            val textureMode = runCatching {
                app.container.prefs.mapTextureModeFlow.first()
            }.getOrDefault(false)
            val options = MapLibreMapOptions().textureMode(textureMode)
            MapView(context, options).apply { onCreate(null) }
        }.onSuccess { mapView = it }
            .onFailure {
                CrashDiagnostics.log(context, "MapView creation failed: $it")
                mapViewError = it
            }
    }
    if (mapViewError != null) {
        MapUnavailableScreen(mapViewError, onBack)
        return
    }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var appliedStyleJson by remember { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var showBatches by remember { mutableStateOf(false) }
    var tempDraft by remember { mutableStateOf<TempDraft?>(null) }
    var denseGroup by remember { mutableStateOf<DenseGroup?>(null) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.consumeMessage()
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val view = mapView
        if (view == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> view.onStart()
                    Lifecycle.Event.ON_RESUME -> view.onResume()
                    Lifecycle.Event.ON_PAUSE -> view.onPause()
                    Lifecycle.Event.ON_STOP -> view.onStop()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            val current = lifecycleOwner.lifecycle.currentState
            if (current.isAtLeast(Lifecycle.State.RESUMED)) {
                view.onStart()
                view.onResume()
            } else if (current.isAtLeast(Lifecycle.State.STARTED)) {
                view.onStart()
            }
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                runCatching {
                    view.onPause()
                    view.onStop()
                    view.onDestroy()
                }
            }
        }
    }

    LaunchedEffect(mapView) {
        val view = mapView ?: return@LaunchedEffect
        view.getMapAsync { map ->
            mapLibreMap = map
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.setMaxZoomPreference(20.0)
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(state.centerLat, state.centerLon))
                .zoom(state.zoom)
                .build()
            map.addOnCameraMoveStartedListener {
                if (state.markers.size >= HIDE_MARKERS_THRESHOLD) {
                    loadedStyle?.setCellMapLayersVisible(false)
                }
            }
            map.addOnCameraIdleListener {
                loadedStyle?.setCellMapLayersVisible(true)
                val camera = map.cameraPosition
                loadedStyle?.let { applyMarkerRingSpacing(it, camera.zoom) }
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
                val temp = runCatching {
                    map.queryRenderedFeatures(point, LAYER_TEMP)
                }.getOrDefault(emptyList()).firstOrNull()
                val tempId = temp?.getNumberProperty("id")?.toLong()
                if (tempId != null) {
                    val marker = vm.state.value.tempMarkers.firstOrNull { it.id == tempId }
                    if (marker != null) {
                        tempDraft = TempDraft(
                            id = marker.id,
                            lat = marker.lat,
                            lon = marker.lon,
                            content = marker.content
                        )
                        return@addOnMapClickListener true
                    }
                }
                val dense = runCatching {
                    map.queryRenderedFeatures(point, LAYER_DENSE, LAYER_DENSE_TEXT)
                }.getOrDefault(emptyList()).firstOrNull()
                val denseId = dense?.getStringProperty("gid")
                if (denseId != null) {
                    val group = vm.state.value.denseGroups.firstOrNull { it.id == denseId }
                    if (group != null) {
                        denseGroup = group
                        return@addOnMapClickListener true
                    }
                }
                val sectorLabel = runCatching {
                    map.queryRenderedFeatures(point, LAYER_SECTOR_LABEL)
                }.getOrDefault(emptyList()).firstOrNull()
                val sectorStationId = sectorLabel?.getStringProperty("sid")?.toLongOrNull()
                if (sectorStationId != null) {
                    vm.selectStation(sectorStationId)
                    return@addOnMapClickListener true
                }
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
                    map.queryRenderedFeatures(point, *markerLayerIds.toTypedArray())
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

    // 只观察触摸事件并返回 false，让地图自身的平移/缩放手势照常工作。
    DisposableEffect(mapView) {
        val view = mapView
        if (view == null) {
            onDispose { }
        } else {
            val handler = Handler(Looper.getMainLooper())
            val slop = ViewConfiguration.get(view.context).scaledTouchSlop
            var token = 0
            var downX = 0f
            var downY = 0f
            view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        token += 1
                        val current = token
                        downX = event.x
                        downY = event.y
                        handler.postDelayed({
                            if (current != token) return@postDelayed
                            val map = mapLibreMap ?: return@postDelayed
                            val latLng = runCatching {
                                map.projection.fromScreenLocation(PointF(downX, downY))
                            }.getOrNull() ?: return@postDelayed
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            tempDraft = TempDraft(
                                id = null,
                                lat = latLng.latitude,
                                lon = latLng.longitude,
                                content = ""
                            )
                        }, LONG_PRESS_MILLIS)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (hypot(event.x - downX, event.y - downY) > slop) {
                            token += 1
                            handler.removeCallbacksAndMessages(null)
                        }
                    }
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        token += 1
                        handler.removeCallbacksAndMessages(null)
                    }
                }
                false
            }
            onDispose {
                handler.removeCallbacksAndMessages(null)
                view.setOnTouchListener(null)
            }
        }
    }

    LaunchedEffect(state.styleJson, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val json = state.styleJson
        if (json.isBlank()) return@LaunchedEffect
        if (json == appliedStyleJson) return@LaunchedEffect
        appliedStyleJson = json
        val icons = withContext(Dispatchers.Default) { MarkerIconCache.icons(context) }
        map.setStyle(Style.Builder().fromJson(json)) { style ->
            icons.forEach { (name, bitmap) -> style.addImage(name, bitmap) }
            style.addSource(GeoJsonSource(SOURCE_CLUSTERS, EMPTY_COLLECTION))
            style.addSource(GeoJsonSource(SOURCE_DENSE, EMPTY_COLLECTION))
            style.addSource(GeoJsonSource(SOURCE_MARKERS, EMPTY_COLLECTION))
            style.addSource(GeoJsonSource(SOURCE_SECTORS, EMPTY_COLLECTION))
            style.addSource(GeoJsonSource(SOURCE_SECTOR_LABELS, EMPTY_COLLECTION))
            style.addSource(GeoJsonSource(SOURCE_SECTOR_LEADERS, EMPTY_COLLECTION))
            style.addSource(GeoJsonSource(SOURCE_TEMP, EMPTY_COLLECTION))
            style.addLayer(
                CircleLayer(LAYER_CLUSTERS, SOURCE_CLUSTERS).withProperties(
                    circleColor("#0D47A1"),
                    circleOpacity(0.85f),
                    circleRadius(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(6, 10f),
                            Expression.stop(10, 26f)
                        )
                    ),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(2.5f)
                )
            )
            style.addLayer(
                SymbolLayer(LAYER_CLUSTER_TEXT, SOURCE_CLUSTERS).withProperties(
                    textField(get("point_count_abbreviated")),
                    textSize(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(6, 9f),
                            Expression.stop(10, 15f)
                        )
                    ),
                    textColor("#FFFFFF"),
                    textAllowOverlap(true),
                    textIgnorePlacement(true),
                    textFont(arrayOf("Noto Sans Regular", "Open Sans Regular"))
                )
            )
            MarkerStyle.entries.forEach { markerStyle ->
                style.addLayer(
                    FillLayer(sectorFillLayerId(markerStyle.key), SOURCE_SECTORS)
                        .withFilter(Expression.eq(Expression.get("style"), markerStyle.key))
                        .withProperties(
                            fillColor(markerStyle.colorHex.toInt()),
                            fillOpacity(0.28f),
                            visibility("none")
                        )
                )
            }
            style.addLayer(
                LineLayer(LAYER_SECTOR_LINE, SOURCE_SECTORS).withProperties(
                    lineColor("#FFFFFF"),
                    lineWidth(1.2f),
                    lineOpacity(0.85f),
                    visibility("none")
                )
            )
            style.addLayer(
                LineLayer(LAYER_SECTOR_LEADER, SOURCE_SECTOR_LEADERS).withProperties(
                    lineColor("#546E7A"),
                    lineOpacity(0.7f),
                    lineWidth(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(10, 1f),
                            Expression.stop(18, 1.6f)
                        )
                    ),
                    visibility("none")
                )
            )
            style.addLayer(
                SymbolLayer(LAYER_SECTOR_LABEL, SOURCE_SECTOR_LABELS).withProperties(
                    iconImage(get("icon")),
                    iconSize(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(10, 0.9f),
                            Expression.stop(16, 1f),
                            Expression.stop(20, 1.08f)
                        )
                    ),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    visibility("none")
                )
            )
            style.addLayer(
                CircleLayer(LAYER_DENSE, SOURCE_DENSE).withProperties(
                    circleColor("#0D47A1"),
                    circleOpacity(0.38f),
                    circleRadius(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(19, 34f),
                            Expression.stop(20, 42f)
                        )
                    ),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(2.5f)
                )
            )
            style.addLayer(
                SymbolLayer(LAYER_DENSE_TEXT, SOURCE_DENSE).withProperties(
                    textField(get("count_abbreviated")),
                    textSize(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(19, 15f),
                            Expression.stop(20, 18f)
                        )
                    ),
                    textColor("#FFFFFF"),
                    textHaloColor("#0D47A1"),
                    textHaloWidth(1.5f),
                    textAllowOverlap(true),
                    textIgnorePlacement(true),
                    textFont(arrayOf("Noto Sans Regular", "Open Sans Regular"))
                )
            )
            for (ring in 0 until MarkerRings.COUNT) {
                style.addLayer(
                    SymbolLayer(markerLayerId(ring), SOURCE_MARKERS)
                        .withFilter(Expression.eq(Expression.get("ring"), ring))
                        .withProperties(
                            iconImage(get("icon")),
                            iconSize(
                                Expression.interpolate(
                                    Expression.linear(),
                                    Expression.zoom(),
                                    Expression.stop(10, MarkerScaling.iconScale(10.0)),
                                    Expression.stop(20, MarkerScaling.iconScale(20.0))
                                )
                            ),
                            iconOffset(MarkerRings.offset(ring)),
                            iconAllowOverlap(true),
                            iconIgnorePlacement(true)
                        )
                )
            }
            style.addLayer(
                SymbolLayer(LAYER_TEMP, SOURCE_TEMP).withProperties(
                    iconImage(get("icon")),
                    iconSize(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(10, MarkerScaling.iconScale(10.0)),
                            Expression.stop(20, MarkerScaling.iconScale(20.0))
                        )
                    ),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true)
                )
            )
            applyMarkerRingSpacing(style, map.cameraPosition.zoom)
            loadedStyle = style
        }
    }

    LaunchedEffect(state.markersJson, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        val json = state.markersJson
        if (json.isBlank()) return@LaunchedEffect
        runCatching {
            style.getSourceAs<GeoJsonSource>(SOURCE_MARKERS)?.setGeoJson(json)
        }
    }

    LaunchedEffect(state.sectorJson, state.sectorLabelJson, state.sectorLeaderJson, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        val shapes = state.sectorJson.ifBlank { EMPTY_COLLECTION }
        val labels = state.sectorLabelJson.ifBlank { EMPTY_COLLECTION }
        val leaders = state.sectorLeaderJson.ifBlank { EMPTY_COLLECTION }
        runCatching {
            style.getSourceAs<GeoJsonSource>(SOURCE_SECTORS)?.setGeoJson(shapes)
            style.getSourceAs<GeoJsonSource>(SOURCE_SECTOR_LABELS)?.setGeoJson(labels)
            style.getSourceAs<GeoJsonSource>(SOURCE_SECTOR_LEADERS)?.setGeoJson(leaders)
        }
    }

    val sectorIconIds = remember { mutableSetOf<String>() }
    DisposableEffect(loadedStyle) {
        onDispose { sectorIconIds.clear() }
    }
    LaunchedEffect(state.sectorIcons, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        val desired = state.sectorIcons
        val missing = desired.filter { it.id !in sectorIconIds }
        if (missing.isNotEmpty()) {
            val bitmaps = withContext(Dispatchers.Default) {
                missing.map { it to SectorLabelIcons.bitmap(context, it.text) }
            }
            bitmaps.forEach { (icon, bitmap) ->
                runCatching { style.addImage(icon.id, bitmap) }
                    .onSuccess { sectorIconIds.add(icon.id) }
            }
            runCatching {
                style.getSourceAs<GeoJsonSource>(SOURCE_SECTOR_LABELS)
                    ?.setGeoJson(state.sectorLabelJson.ifBlank { EMPTY_COLLECTION })
            }
        }
        val desiredIds = desired.mapTo(HashSet()) { it.id }
        (sectorIconIds - desiredIds).toList().forEach { id ->
            runCatching { style.removeImage(id) }
            sectorIconIds.remove(id)
        }
    }

    LaunchedEffect(state.sectorVisible, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        val value = if (state.sectorVisible) "visible" else "none"
        runCatching {
            MarkerStyle.entries.forEach { markerStyle ->
                style.getLayer(sectorFillLayerId(markerStyle.key))?.setProperties(visibility(value))
            }
            style.getLayer(LAYER_SECTOR_LINE)?.setProperties(visibility(value))
            style.getLayer(LAYER_SECTOR_LEADER)?.setProperties(visibility(value))
            style.getLayer(LAYER_SECTOR_LABEL)?.setProperties(visibility(value))
        }
    }

    LaunchedEffect(state.tempJson, state.tempMarkers, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        runCatching {
            state.tempMarkers.forEach { marker ->
                val name = TempMarkerOverlay.iconName(marker.id)
                if (style.getImage(name) == null) {
                    style.addImage(name, TempMarkerIcons.bitmap(marker.color))
                }
            }
            style.getSourceAs<GeoJsonSource>(SOURCE_TEMP)
                ?.setGeoJson(state.tempJson.ifBlank { EMPTY_COLLECTION })
        }
    }

    LaunchedEffect(state.clustersJson, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        val json = state.clustersJson
        if (json.isBlank()) return@LaunchedEffect
        runCatching {
            style.getSourceAs<GeoJsonSource>(SOURCE_CLUSTERS)?.setGeoJson(json)
        }
    }

    LaunchedEffect(state.denseJson, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        val json = state.denseJson
        if (json.isBlank()) return@LaunchedEffect
        runCatching {
            style.getSourceAs<GeoJsonSource>(SOURCE_DENSE)?.setGeoJson(json)
        }
    }

    LaunchedEffect(state.annotationsVisible, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        runCatching {
            style.getLayer(BaseMapProvider.LAYER_ANNOTATION)?.setProperties(
                visibility(if (state.annotationsVisible) "visible" else "none")
            )
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
            val currentMapView = mapView
            if (currentMapView != null) {
                AndroidView(factory = { currentMapView }, modifier = Modifier.fillMaxSize())
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("地图加载中", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

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
                    SearchResults(
                        state = state,
                        onSelect = vm::selectSearch,
                        onToggleDetail = vm::toggleSearchDetail
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val activeBatch = state.batches.firstOrNull { it.id == state.selectedBatchId }
                    OutlinedButton(
                        onClick = { showBatches = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Icon(
                            Icons.Filled.TableChart,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            activeBatch?.fileName ?: "全部表格",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp)
                        )
                    }
                    if (activeBatch != null) {
                        OutlinedButton(
                            onClick = { vm.setBatch(null) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Icon(
                                Icons.Filled.ClearAll,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("显示全部")
                        }
                    }
                    if (state.sectorVisible) {
                        OutlinedButton(
                            onClick = vm::clearSectors,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Icon(
                                Icons.Filled.LayersClear,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("清除扇区 ${state.sectorTotal}")
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val chipColors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                    val chipElevation = FilterChipDefaults.filterChipElevation(3.dp)
                    FilterChip(
                        selected = state.system == NetworkSystem.FOUR_G,
                        onClick = { vm.setSystem(NetworkSystem.FOUR_G) },
                        label = { Text("4G") },
                        shape = FilterChipDefaults.shape,
                        colors = chipColors,
                        elevation = chipElevation,
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = state.system == NetworkSystem.FOUR_G,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                            borderWidth = 1.dp,
                            selectedBorderWidth = 1.dp
                        )
                    )
                    FilterChip(
                        selected = state.system == NetworkSystem.FIVE_G,
                        onClick = { vm.setSystem(NetworkSystem.FIVE_G) },
                        label = { Text("5G") },
                        shape = FilterChipDefaults.shape,
                        colors = chipColors,
                        elevation = chipElevation,
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = state.system == NetworkSystem.FIVE_G,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                            borderWidth = 1.dp,
                            selectedBorderWidth = 1.dp
                        )
                    )
                    FilterChip(
                        selected = state.system == NetworkSystem.BOTH,
                        onClick = { vm.setSystem(NetworkSystem.BOTH) },
                        label = { Text("45") },
                        shape = FilterChipDefaults.shape,
                        colors = chipColors,
                        elevation = chipElevation,
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = state.system == NetworkSystem.BOTH,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                            borderWidth = 1.dp,
                            selectedBorderWidth = 1.dp
                        )
                    )
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { showFilters = true }) {
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
            onToggleAnnotations = vm::setAnnotationsVisible,
            onToggleTexture = vm::setTextureMode,
            onDismiss = { showFilters = false }
        )
    }

    if (showBatches) {
        BatchSheet(
            state = state,
            onSelect = {
                vm.setBatch(it)
                showBatches = false
            },
            onDismiss = { showBatches = false }
        )
    }

    val selected = state.selected
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = vm::clearSelection) {
            StationDetail(
                station = selected,
                sectorActive = state.selectedSectorActive,
                sectorBusy = state.sectorBusy,
                sectorCount = state.sectorCount,
                onToggleSectors = vm::toggleSectors,
                onFocus = { vm.focusOn(selected.lat, selected.lon) },
                onDismiss = vm::clearSelection
            )
        }
    }

    val group = denseGroup
    if (group != null) {
        DenseGroupDialog(
            group = group,
            onDismiss = { denseGroup = null },
            onPick = { marker ->
                denseGroup = null
                vm.selectStation(marker.id)
            }
        )
    }

    val draft = tempDraft
    if (draft != null) {
        TempMarkerDialog(
            draft = draft,
            onDismiss = { tempDraft = null },
            onSave = { content ->
                if (draft.id == null) {
                    vm.addTempMarker(draft.lat, draft.lon, content)
                } else {
                    vm.updateTempMarker(draft.id, content)
                }
                tempDraft = null
            },
            onDelete = draft.id?.let { id ->
                { vm.removeTempMarker(id); tempDraft = null }
            }
        )
    }
}

private fun Style.setCellMapLayersVisible(visible: Boolean) {
    val value = if (visible) "visible" else "none"
    runCatching {
        getLayer(LAYER_CLUSTERS)?.setProperties(visibility(value))
        getLayer(LAYER_CLUSTER_TEXT)?.setProperties(visibility(value))
        getLayer(LAYER_DENSE)?.setProperties(visibility(value))
        getLayer(LAYER_DENSE_TEXT)?.setProperties(visibility(value))
        markerLayerIds.forEach { id -> getLayer(id)?.setProperties(visibility(value)) }
    }
}

private fun applyMarkerRingSpacing(style: Style, zoom: Double) {
    val spacing = MarkerScaling.ringSpacing(zoom)
    runCatching {
        for (ring in 0 until MarkerRings.COUNT) {
            style.getLayer(markerLayerId(ring))?.setProperties(
                iconOffset(MarkerRings.offset(ring, spacing))
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
    val shape = RoundedCornerShape(14.dp)
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("搜索小区 / 基站 ID / 小区 ID") },
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
        shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            cursorColor = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, shape, clip = false)
    )
}

@Composable
private fun SearchResults(
    state: MapUiState,
    onSelect: (com.selfuse.cellmap.core.model.SearchHit) -> Unit,
    onToggleDetail: (com.selfuse.cellmap.core.model.SearchHit) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Column {
            state.searchResults.take(6).forEachIndexed { index, hit ->
                if (index > 0) DividerSpacer()
                val expanded = state.expandedSearchId == hit.id
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(hit) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp)
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
                        IconButton(onClick = { onToggleDetail(hit) }) {
                            Icon(
                                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (expanded) "收起基站详情" else "展开基站详情"
                            )
                        }
                    }
                    if (expanded) {
                        DividerSpacer()
                        val station = state.expandedStation
                        if (station == null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "正在读取基站详情",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    "长按文字可选择复制",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                SelectionContainer {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        StationInfoRows(station)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationExtraRows(extraJson: String) {
    val rows = remember(extraJson) {
        runCatching {
            Json.parseToJsonElement(extraJson).jsonObject.entries.mapNotNull { (key, value) ->
                value.jsonPrimitive.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { key to it }
            }
        }.getOrDefault(emptyList())
    }
    rows.forEach { (label, value) -> KeyValueRow(label, value) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchSheet(
    state: MapUiState,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("选择数据表", style = MaterialTheme.typography.titleMedium)
            Text(
                "仅查询所选 XLSX 生成的标注；每张表的表头会独立保存并显示在基站详情中。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                BatchOptionRow(
                    title = "全部表格",
                    subtitle = "共 ${state.batches.size} 张已导入表格",
                    selected = state.selectedBatchId == null,
                    onClick = { onSelect(null) }
                )
                state.batches.forEach { batch ->
                    DividerSpacer()
                    BatchOptionRow(
                        title = batch.fileName,
                        subtitle = "成功 ${Formats.number(batch.successRows)} · " +
                            "失败 ${Formats.number(batch.failedRows)} · ${batch.sheetName}",
                        selected = state.selectedBatchId == batch.id,
                        onClick = { onSelect(batch.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MapStatusCard(state: MapUiState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
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
    onToggleAnnotations: (Boolean) -> Unit,
    onToggleTexture: (Boolean) -> Unit,
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
            SectionHeader("底图注记")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("路网与 POI 注记", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "显示道路、商铺与楼栋名称，关闭可省流量与电量",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.annotationsVisible,
                    onCheckedChange = onToggleAnnotations
                )
            }
            SectionHeader("兼容渲染")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("纹理模式", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "地图黑屏或闪烁时开启，重新进入地图生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.textureMode,
                    onCheckedChange = onToggleTexture
                )
            }
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
private fun StationInfoRows(station: StationEntity) {
    KeyValueRow("制式", station.system)
    KeyValueRow("基站 ID", station.stationId)
    KeyValueRow("小区 ID", station.cellId)
    KeyValueRow("小区名称", station.cellName.orEmpty().ifBlank { "—" })
    KeyValueRow("基站名称", station.siteName.orEmpty().ifBlank { "—" })
    KeyValueRow("承建方", station.builder.orEmpty().ifBlank { "—" })
    KeyValueRow("运营商", station.operator.orEmpty().ifBlank { "—" })
    KeyValueRow("是否共享", station.shared.orEmpty().ifBlank { "未知" })
    KeyValueRow("网元归属", station.owner.orEmpty().ifBlank { "—" })
    KeyValueRow("管理区县", station.district.orEmpty().ifBlank { "—" })
    KeyValueRow("小区覆盖类", station.category.orEmpty().ifBlank { "—" })
    KeyValueRow("频段", station.band.orEmpty().ifBlank { "—" })
    KeyValueRow("带宽", station.bandwidth.orEmpty().ifBlank { "—" })
    KeyValueRow("上行中心频点", station.uplinkFreq.orEmpty().ifBlank { "—" })
    KeyValueRow("下行中心频点", station.downlinkFreq.orEmpty().ifBlank { "—" })
    KeyValueRow("多载波标识", station.multiCarrier.orEmpty().ifBlank { "—" })
    KeyValueRow("基站部署方式", station.deployment.orEmpty().ifBlank { "—" })
    KeyValueRow("天线挂高", station.antennaHeight.orEmpty().ifBlank { "—" })
    KeyValueRow("小区方位角", station.azimuth.orEmpty().ifBlank { "—" })
    KeyValueRow("天线增益", station.antennaGain.orEmpty().ifBlank { "—" })
    KeyValueRow("PCI", station.pci.orEmpty().ifBlank { "—" })
    KeyValueRow("AAU 数量", station.aauCount.orEmpty().ifBlank { "—" })
    KeyValueRow("RRU 数量", station.rruCount.orEmpty().ifBlank { "—" })
    KeyValueRow("45G 共设备", station.coDevice45G.orEmpty().ifBlank { "—" })
    StationExtraRows(station.extraJson)
    KeyValueRow(
        "坐标",
        String.format(Locale.US, "%.6f, %.6f", station.lat, station.lon)
    )
}

@Composable
private fun StationDetail(
    station: StationEntity,
    sectorActive: Boolean,
    sectorBusy: Boolean,
    sectorCount: Int,
    onToggleSectors: () -> Unit,
    onFocus: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f)
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
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
        ScrollableStationInfo(
            station = station,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
        if (sectorCount > 0) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onToggleSectors,
                enabled = !sectorBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (sectorBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (sectorActive) "隐藏方位角扇区" else "显示方位角扇区（$sectorCount）"
                )
            }
        }
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

@Composable
private fun ScrollableStationInfo(station: StationEntity, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val thumbMinHeight = with(LocalDensity.current) { 32.dp.toPx() }
    BoxWithConstraints(modifier) {
        val viewportHeight = constraints.maxHeight.toFloat()
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                StationInfoRows(station)
            }
        }
        if (scrollState.maxValue > 0 && viewportHeight > 0f) {
            val contentHeight = viewportHeight + scrollState.maxValue
            Canvas(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(4.dp)
                    .fillMaxHeight()
            ) {
                val thumbHeight = (size.height * size.height / contentHeight)
                    .coerceAtLeast(thumbMinHeight.coerceAtMost(size.height))
                val offset = (size.height - thumbHeight) *
                    (scrollState.value.toFloat() / scrollState.maxValue)
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(0f, offset),
                    size = Size(size.width, thumbHeight),
                    cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
                )
            }
        }
    }
}


@Composable
private fun DenseGroupDialog(
    group: DenseGroup,
    onDismiss: () -> Unit,
    onPick: (MapMarker) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "此处 ${group.count} 个标注点",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "位置相近，点选一项查看详情",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            val listState = rememberLazyListState()
            val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            val thumbMinHeight = with(LocalDensity.current) { 32.dp.toPx() }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .drawWithContent {
                        drawContent()
                        val layout = listState.layoutInfo
                        val total = layout.totalItemsCount
                        val visible = layout.visibleItemsInfo.size
                        if (total > visible && visible > 0) {
                            val thumbWidth = 4.dp.toPx()
                            val thumbHeight = (size.height * visible / total)
                                .coerceAtLeast(thumbMinHeight.coerceAtMost(size.height))
                            val maxScroll = (total - visible).coerceAtLeast(1)
                            val progress = (listState.firstVisibleItemIndex.toFloat() / maxScroll)
                                .coerceIn(0f, 1f)
                            val top = (size.height - thumbHeight) * progress
                            drawRoundRect(
                                color = thumbColor,
                                topLeft = Offset(size.width - thumbWidth, top),
                                size = Size(thumbWidth, thumbHeight),
                                cornerRadius = CornerRadius(thumbWidth / 2f, thumbWidth / 2f)
                            )
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(group.markers, key = { it.id }) { marker ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(marker) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .background(Color(marker.style.colorHex.toInt()), CircleShape)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                marker.title.ifBlank {
                                    marker.cellId.ifBlank { "未命名小区" }
                                },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                listOf(marker.cellId, marker.system, marker.style.label)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private data class TempDraft(
    val id: Long?,
    val lat: Double,
    val lon: Double,
    val content: String
)

@Composable
private fun TempMarkerDialog(
    draft: TempDraft,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var text by remember(draft) { mutableStateOf(draft.content) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "添加临时标识" else "编辑临时标识") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    String.format(Locale.US, "%.6f, %.6f", draft.lat, draft.lon),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        if (it.length <= 500) text = it
                    },
                    label = { Text("内容") },
                    placeholder = { Text("输入这个位置的备注") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${text.length}/500",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
private fun MapUnavailableScreen(error: Throwable?, onBack: () -> Unit) {
    val context = LocalContext.current
    val detail = error?.stackTraceToString().orEmpty().ifBlank { "未知错误" }
    Scaffold(
        topBar = { AppTopBar(title = "地图不可用", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("MapLibre 初始化失败", style = MaterialTheme.typography.titleMedium)
            Text(
                "应用其他功能仍可使用。诊断日志已写入 Download/cellmap-diagnostics.txt。",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 20,
                overflow = TextOverflow.Ellipsis
            )
            Button(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("MapLibre error", detail))
            }) {
                Text("复制错误信息")
            }
        }
    }
}
