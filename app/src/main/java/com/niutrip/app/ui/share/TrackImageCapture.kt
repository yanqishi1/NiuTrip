package com.niutrip.app.ui.share

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.*
import com.niutrip.app.core.DayGroup
import com.niutrip.app.core.RouteGeometry
import com.niutrip.app.core.dayColor
import com.niutrip.app.ui.detail.map.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** 独立地图保证截图始终是全程，不改变详情页的相机、日选择或当前位置。 */
@Composable
fun TrackImageCapture(
    data: TrackImageData,
    modifier: Modifier,
    onReady: (File) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val loaded = remember { CompletableDeferred<Unit>() }
    val laidOut = remember { CompletableDeferred<Unit>() }
    val mapView = remember {
        TextureMapView(context).apply {
            onCreate(Bundle())
            map.setOnMapLoadedListener { loaded.complete(Unit) }
            doOnLayout { laidOut.complete(Unit) }
        }
    }
    val readyCallback by rememberUpdatedState(onReady)
    val errorCallback by rememberUpdatedState(onError)
    DisposableEffect(mapView, lifecycle) {
        mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
    LaunchedEffect(mapView, data) {
        try {
            val screenshot = withTimeout(30_000) {
                loaded.await()
                laidOut.await()
                val map = mapView.map
                map.uiSettings.apply {
                    isZoomControlsEnabled = false
                    isScaleControlsEnabled = true
                    setAllGesturesEnabled(false)
                }
                val points = data.days.flatMap { it.points }
                val viewportSize = minOf(mapView.width, mapView.height)
                val density = context.resources.displayMetrics.density
                val markerScale = (viewportSize / (320f * density)).coerceIn(.5f, 1f)
                val padding = maxOf(viewportSize * .14f, 72f * density * markerScale)
                    .toInt().coerceAtMost(viewportSize / 3)
                visibleRoutePaths(data.days, -1).forEach { path ->
                    if (path.points.size >= 2) map.addPolyline(PolylineOptions()
                        .addAll(path.points.map { LatLng(it.lat, it.lon) })
                        .color(dayColor(path.dayIndex).toInt()).width(8f).geodesic(true))
                }
                val endpoints = requireNotNull(RouteGeometry.endpoints(data.days))
                val positions = points.map { LatLng(it.lat, it.lon) }.distinct()
                val samePosition = LatLng(endpoints.start.lat, endpoints.start.lon) == LatLng(endpoints.end.lat, endpoints.end.lon)
                val specs = if (samePosition) listOf(endpoints.start to EndpointMarkerType.ROUND_TRIP)
                    else listOf(endpoints.start to EndpointMarkerType.START, endpoints.end to EndpointMarkerType.END)
                specs.forEach { (point, type) ->
                    val artwork = createEndpointMarkerArtwork(context, type, markerScale)
                    map.addMarker(MarkerOptions().position(LatLng(point.lat, point.lon))
                        .icon(BitmapDescriptorFactory.fromBitmap(artwork.bitmap))
                        .anchor(artwork.anchorU, artwork.anchorV).zIndex(6f))
                }
                val cameraFinished = CompletableDeferred<Unit>()
                map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                    override fun onCameraChange(position: CameraPosition) = Unit
                    override fun onCameraChangeFinish(position: CameraPosition) { cameraFinished.complete(Unit) }
                })
                val camera = if (positions.size == 1) CameraUpdateFactory.newLatLngZoom(positions.first(), 15f)
                    else CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds.builder().apply { positions.forEach { include(it) } }.build(),
                        mapView.width, mapView.height, padding)
                map.moveCamera(camera)
                cameraFinished.await()
                // 全程总览只保留不重叠的紧凑打卡标记，不请求照片或当前位置。
                val reserved = specs.map { (point, _) -> map.projection.toScreenLocation(LatLng(point.lat, point.lon)) }
                    .toMutableList()
                points.filter { it.isCheckin && it.id != endpoints.start.id && it.id != endpoints.end.id }
                    .forEach { point ->
                        val screen = map.projection.toScreenLocation(LatLng(point.lat, point.lon))
                        if (reserved.none { kotlin.math.hypot((it.x - screen.x).toDouble(), (it.y - screen.y).toDouble()) < mapView.width * .06 }) {
                            reserved.add(screen)
                            map.addMarker(MarkerOptions().position(LatLng(point.lat, point.lon))
                                .icon(BitmapDescriptorFactory.fromBitmap(createCompactCheckinMarkerBitmap(context)))
                                .anchor(.5f, 1f))
                        }
                    }
                mapView.awaitPosterFrame()
                val result = CompletableDeferred<Bitmap>()
                map.getMapScreenShot(object : AMap.OnMapScreenShotListener {
                    override fun onMapScreenShot(bitmap: Bitmap?) = Unit
                    override fun onMapScreenShot(bitmap: Bitmap?, status: Int) {
                        if (bitmap != null && status == 1) result.complete(bitmap)
                        else result.completeExceptionally(IllegalStateException("地图尚未完整加载，请重试"))
                    }
                })
                result.await()
            }
            val file = withContext(Dispatchers.IO) { TrackImageFiles.create(context, screenshot, data) }
            readyCallback(file)
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            errorCallback("地图加载超时，请检查网络后重试")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            errorCallback(error.message ?: "轨迹图片生成失败")
        }
    }
}

// ---------- 海报编辑器的独立地图宿主（plan §10） ----------

/**
 * 编辑器自己的地图实例：不复用详情页地图、不改变其相机；
 * 支持用户构图（平移/缩放，北向上，关闭旋转与倾斜）、相机状态恢复和目标视口截图。
 * autoFit 与 capture 分离，避免用户拖好地图后导出跳回全程（plan §10.1）。
 */
@Composable
fun PosterMapHost(
    days: List<DayGroup>,
    mapState: PosterMapState,
    canvasAspect: Float,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    routeStyle: PosterRouteStyleState = PosterRouteStyleState(),
    endpointNames: PosterEndpointNames = PosterEndpointNames(),
    onCameraPosition: (Double, Double, Double) -> Unit = { _, _, _ -> },
    onReservedBottomMeasured: (Float) -> Unit = {},
    registerCapture: (((suspend (PosterCanvasSpec) -> Bitmap?)?) -> Unit) = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val loaded = remember { CompletableDeferred<Unit>() }
    val cameraIdle = remember { MutableStateFlow(false) }
    var mapLoaded by remember { mutableStateOf(false) }
    var cameraRevision by remember { mutableStateOf(0) }
    var laidOutPx by remember { mutableStateOf(0 to 0) }
    val cameraCallback by rememberUpdatedState(onCameraPosition)
    val currentStyle by rememberUpdatedState(routeStyle)
    val currentNames by rememberUpdatedState(endpointNames)
    val currentDays by rememberUpdatedState(days)
    val markers = remember { mutableListOf<com.amap.api.maps.model.Marker>() }
    val mapView = remember {
        TextureMapView(context).apply {
            onCreate(Bundle())
            map.setOnMapLoadedListener { loaded.complete(Unit); mapLoaded = true }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (width > 0 && height > 0) laidOutPx = width to height
            }
            map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                override fun onCameraChange(position: CameraPosition) {
                    cameraIdle.value = false
                }
                override fun onCameraChangeFinish(position: CameraPosition) {
                    cameraIdle.value = true
                    cameraRevision++
                    cameraCallback(position.target.latitude, position.target.longitude, position.zoom.toDouble())
                }
            })
        }
    }
    DisposableEffect(mapView, lifecycle) {
        mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDestroy()
        }
    }
    AndroidView(
        factory = { mapView },
        modifier = modifier.aspectRatio(canvasAspect.coerceIn(0.4f, 2.8f)),
        update = { view ->
            view.map.uiSettings.apply {
                isZoomControlsEnabled = false
                isScaleControlsEnabled = true
                setAllGesturesEnabled(interactive)
                // 首版保持北向上（plan §3.4）。
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
            }
        },
    )
    // 路线、端点与打卡标记按样式装载；切换纯色/按天或标记开关时重建（相机不受影响）。
    LaunchedEffect(mapLoaded, laidOutPx, routeStyle.theme, days) {
        if (mapLoaded && laidOutPx.first > 0) {
            mapView.map.clear()
            markers.clear()
            applyPosterRoute(mapView.map, days, context, routeStyle)
        }
    }
    LaunchedEffect(mapLoaded, laidOutPx, cameraRevision, routeStyle, endpointNames, days) {
        if (mapLoaded && laidOutPx.first > 0 && cameraIdle.value) {
            markers.forEach { it.remove() }; markers.clear()
            markers.addAll(applyPosterMarkers(mapView, days, context, routeStyle, endpointNames))
        }
    }
    // Initial fit uses the map region; the summary lives outside the SDK viewport.
    LaunchedEffect(mapLoaded, laidOutPx, mapState, canvasAspect) {
        if (!mapLoaded || laidOutPx.first <= 0) return@LaunchedEffect
        val (width, height) = laidOutPx
        if (mapState.isAutoFit) {
            val density = context.resources.displayMetrics.density
            val positions = days.flatMap(DayGroup::points).map { LatLng(it.lat, it.lon) }.distinct()
            val update = if (positions.size <= 1) {
                val center = positions.firstOrNull() ?: return@LaunchedEffect
                CameraUpdateFactory.newLatLngZoom(center, 15f)
            } else {
                CameraUpdateFactory.newLatLngBoundsRect(
                    LatLngBounds.builder().apply { positions.forEach(::include) }.build(),
                    (width * .12f).toInt().coerceAtLeast((36 * density).toInt()),
                    (width * .12f).toInt().coerceAtLeast((36 * density).toInt()),
                    (height * .09f).toInt().coerceAtLeast((52 * density).toInt()),
                    (height * .10f).toInt().coerceAtLeast((40 * density).toInt()),
                )
            }
            mapView.map.moveCamera(update)
        } else {
            val lat = mapState.centerLat ?: return@LaunchedEffect
            val lon = mapState.centerLon ?: return@LaunchedEffect
            val zoom = mapState.zoom ?: return@LaunchedEffect
            mapView.map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom.toFloat()))
        }
    }
    // SDK 标识/版权保留区：以实际布局测量 logo 底边，映射为画布高度比例（plan §10.3）。
    LaunchedEffect(laidOutPx, mapLoaded) {
        if (mapLoaded && laidOutPx.second > 0) {
            val logo = (0 until mapView.childCount)
                .map(mapView::getChildAt)
                .filterNotNull()
                .firstOrNull { it.javaClass.name.contains("Logo", ignoreCase = true) }
            val fraction = if (logo != null && logo.bottom > 0) {
                ((logo.bottom + logo.paddingBottom + 8f * context.resources.displayMetrics.density) / mapView.height.toFloat())
                    .coerceIn(0.05f, 0.25f)
            } else 0.08f
            onReservedBottomMeasured(fraction)
        }
    }
    // 导出截图入口：等待加载与相机静止后截图；与预览同一视口，缩放到目标尺寸由渲染器完成。
    DisposableEffect(mapView) {
        suspend fun capture(@Suppress("UNUSED_PARAMETER") spec: PosterCanvasSpec): Bitmap? {
            return withTimeout(30_000) {
                loaded.await()
                if (!cameraIdle.value) withTimeout(10_000) { cameraIdle.first { it } }
                markers.forEach { it.remove() }; markers.clear()
                markers.addAll(applyPosterMarkers(mapView, currentDays, context, currentStyle, currentNames))
                mapView.awaitPosterFrame()
            val result = CompletableDeferred<Bitmap>()
            mapView.map.getMapScreenShot(object : AMap.OnMapScreenShotListener {
                override fun onMapScreenShot(bitmap: Bitmap?) = Unit
                override fun onMapScreenShot(bitmap: Bitmap?, status: Int) {
                    if (bitmap != null && status == 1) result.complete(bitmap)
                    else result.completeExceptionally(IllegalStateException("地图尚未完整加载，请重试"))
                }
            })
                result.await()
            }
        }
        registerCapture(::capture)
        onDispose { registerCapture(null) }
    }
}

/** 全程路线 + 起终点 + 不重叠的紧凑打卡标记；不显示当前位置或照片气泡（plan §3.4）。 */
private fun applyPosterRoute(
    map: AMap,
    days: List<DayGroup>,
    context: android.content.Context,
    style: PosterRouteStyleState,
) {
    val paths = visibleRoutePaths(days, -1)
    paths.forEach { path ->
        if (path.points.size >= 2) map.addPolyline(PolylineOptions()
            .addAll(path.points.map { LatLng(it.lat, it.lon) })
            .color(if (style.theme == PosterRouteTheme.SINGLE) 0xFFE04435.toInt()
            else dayColor(path.dayIndex).toInt())
            .width(3.5f * context.resources.displayMetrics.density).geodesic(true))
    }
}

internal suspend fun android.view.View.awaitPosterFrame() {
    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
        val frame = Runnable { if (continuation.isActive) continuation.resumeWith(Result.success(Unit)) }
        postOnAnimation(frame)
        continuation.invokeOnCancellation { removeCallbacks(frame) }
    }
}

private fun applyPosterMarkers(
    view: TextureMapView, days: List<DayGroup>, context: android.content.Context,
    style: PosterRouteStyleState, names: PosterEndpointNames,
): List<com.amap.api.maps.model.Marker> {
    val endpoints = RouteGeometry.endpoints(days) ?: return emptyList()
    val map = view.map
    val density = context.resources.displayMetrics.density
    val points = days.flatMap(DayGroup::points)
    val route = points.filterIndexed { index, _ -> index % maxOf(1, points.size / 2000) == 0 }.map {
        val xy = map.projection.toScreenLocation(LatLng(it.lat, it.lon)); xy.x / density to xy.y / density
    }
    val output = mutableListOf<com.amap.api.maps.model.Marker>()
    val reserved = mutableListOf<PosterMarkerBox>()
    val startScreen = map.projection.toScreenLocation(LatLng(endpoints.start.lat, endpoints.start.lon))
    val endScreen = map.projection.toScreenLocation(LatLng(endpoints.end.lat, endpoints.end.lon))
    val same = kotlin.math.hypot((startScreen.x - endScreen.x).toDouble(),
        (startScreen.y - endScreen.y).toDouble()) < 40 * density
    fun endpoint(position: LatLng, art: PosterMarkerArtwork) {
        val screen = map.projection.toScreenLocation(position)
        output.add(map.addMarker(MarkerOptions().position(position)
            .icon(BitmapDescriptorFactory.fromBitmap(art.bitmap)).anchor(art.anchorU, art.anchorV).zIndex(10f)))
        if (art.collisionBoxes != null) reserved.addAll(art.collisionBoxes.map {
            PosterMarkerBox(it.left + screen.x / density, it.top + screen.y / density,
                it.right + screen.x / density, it.bottom + screen.y / density).expanded(8f)
        }) else reserved.add(PosterMarkerBox(screen.x / density - art.anchorU * art.bitmap.width / density - 8,
            screen.y / density - art.anchorV * art.bitmap.height / density - 8,
            screen.x / density + (1 - art.anchorU) * art.bitmap.width / density + 8,
            screen.y / density + (1 - art.anchorV) * art.bitmap.height / density + 8))
    }
    val startName = if (style.showEndpointNames) PosterMapMarkers.endpointName(endpoints.start, names.start) else null
    val endName = if (style.showEndpointNames) PosterMapMarkers.endpointName(endpoints.end, names.end) else null
    if (same) {
        val position = LatLng(endpoints.start.lat, endpoints.start.lon)
        val center = map.projection.toScreenLocation(position)
        endpoint(position, posterEndpointPairArtwork(startName, endName,
            (startScreen.x - center.x) / density, (startScreen.y - center.y) / density,
            (endScreen.x - center.x) / density, (endScreen.y - center.y) / density, density,
            PosterMarkerBox(8 - center.x / density, 8 - center.y / density,
                (view.width - center.x) / density - 8, (view.height - center.y) / density - 30),
            route.map { it.first - center.x / density to it.second - center.y / density }))
    } else {
        endpoint(LatLng(endpoints.start.lat, endpoints.start.lon), posterMarkerArtwork("起", startName,
            0xFF239DAB.toInt(), density, startScreen.x > view.width / 2))
        endpoint(LatLng(endpoints.end.lat, endpoints.end.lon), posterMarkerArtwork("终", endName,
            0xFFE04435.toInt(), density, endScreen.x > view.width / 2))
    }
    if (!style.showCheckinMarkers) return output
    val candidates = points.filter { it.isCheckin }.mapIndexedNotNull { index, point ->
        if (point.id == endpoints.start.id || point.id == endpoints.end.id) null else {
            val xy = map.projection.toScreenLocation(LatLng(point.lat, point.lon))
            PosterMarkerCandidate(point, xy.x / density, xy.y / density, index + 1)
        }
    }
    PosterMapMarkers.layoutNames(candidates, reserved, route, view.width / density, view.height / density,
        style.preferredCheckins, ::posterNameWidth).forEach { selected ->
        val art = posterNamedMarkerArtwork(selected, density)
        val point = selected.candidate.point
        output.add(map.addMarker(MarkerOptions().position(LatLng(point.lat, point.lon))
            .icon(BitmapDescriptorFactory.fromBitmap(art.bitmap)).anchor(art.anchorU, art.anchorV).zIndex(5f)))
    }
    return output
}

internal data class PosterMarkerArtwork(val bitmap: Bitmap, val anchorU: Float, val anchorV: Float,
    val collisionBoxes: List<PosterMarkerBox>? = null)

internal fun posterMarkerArtwork(
    text: String, name: String?, color: Int, density: Float, labelLeft: Boolean = false, offset: Float = 0f,
): PosterMarkerArtwork {
    val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; isFakeBoldText = true }
    val label = name?.let { android.text.TextUtils.ellipsize(it, paint, 105f, android.text.TextUtils.TruncateAt.END).toString() }
    val labelWidth = label?.let { paint.measureText(it) + 16f } ?: 0f
    val totalWidth = 38f + labelWidth + kotlin.math.abs(offset) * 2
    val pinX = if (labelLeft) totalWidth - 19f - kotlin.math.abs(offset) else 19f + kotlin.math.abs(offset)
    val bitmap = Bitmap.createBitmap(kotlin.math.ceil(totalWidth * density).toInt(), (48 * density).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap); canvas.scale(density, density)
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    if (label != null) {
        val left = if (labelLeft) pinX - 15 - labelWidth else pinX + 15
        fill.color = android.graphics.Color.WHITE
        canvas.drawRoundRect(left, 7f, left + labelWidth, 33f, 4f, 4f, fill)
        paint.color = 0xFF26312C.toInt(); paint.textAlign = android.graphics.Paint.Align.LEFT
        canvas.drawText(label, left + 8, 24f, paint)
    }
    val path = android.graphics.Path().apply {
        moveTo(pinX - 9, 29f); lineTo(pinX, 43f); lineTo(pinX + 9, 29f); close()
    }
    fill.color = android.graphics.Color.WHITE
    canvas.drawCircle(pinX, 20f, 16f, fill)
    fill.color = color
    if (offset != 0f) {
        fill.strokeWidth = 2f
        canvas.drawLine(pinX - offset, 43f, pinX, 43f, fill)
        canvas.drawCircle(pinX - offset, 43f, 2.5f, fill)
    }
    canvas.drawPath(path, fill); canvas.drawCircle(pinX, 20f, 14f, fill)
    paint.color = android.graphics.Color.WHITE; paint.textAlign = android.graphics.Paint.Align.CENTER; paint.textSize = 14f
    canvas.drawText(text, pinX, 25f, paint)
    return PosterMarkerArtwork(bitmap, (pinX - offset) / totalWidth, 43f / 48f)
}
