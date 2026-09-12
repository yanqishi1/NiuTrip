package com.niutrip.app.ui.detail.map

import android.graphics.Color
import android.graphics.Bitmap
import android.os.Bundle
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.*
import com.niutrip.app.R
import com.niutrip.app.core.DayGroup
import com.niutrip.app.core.PointLite
import com.niutrip.app.core.RouteGeometry
import com.niutrip.app.core.dayColor
import com.niutrip.app.ui.common.absoluteMediaUrl
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult

@Composable fun AMapView(
    days: List<DayGroup>,
    selectedDayIdx: Int,
    latest: PointLite?,
    modifier: Modifier = Modifier,
    onPointClick: (PointLite) -> Unit = {},
    current: PointLite? = null,  // 地图上的当前位置图标
    currentAvatarUrl: String? = null,
    focusCurrentRequest: Int = 0,
    showEndpoints: Boolean = true,
    showRouteEnd: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { TextureMapView(context).apply { onCreate(Bundle()) } }
    val cameraMemory = remember { MapCameraMemory() }
    var visibleViewport by remember { mutableStateOf<MapViewport?>(null) }
    var markerPresentation by remember { mutableStateOf(markerPresentationForZoom(15f)) }
    val visibleDays = if (selectedDayIdx in days.indices) listOf(days[selectedDayIdx]) else days
    val thumbnailRequests = if (markerPresentation.checkinStyle == CheckinMarkerStyle.CARD) {
        visibleThumbnailRequests(
            points = visibleDays.flatMap(DayGroup::points),
            viewport = visibleViewport,
        )
    } else emptyList()
    var thumbnails by remember { mutableStateOf<Map<ThumbnailRequest, Bitmap>>(emptyMap()) }
    val autoDotCache = remember { mutableMapOf<Int, Bitmap>() }   // 自动点圆圈按当日色缓存
    LaunchedEffect(thumbnailRequests) {
        val requested = thumbnailRequests.toSet()
        var loaded = thumbnails.filterKeys(requested::contains)
        thumbnails = loaded
        thumbnailRequests.filterNot(loaded::containsKey).forEach { request ->
            runCatching {
                val imageRequest = ImageRequest.Builder(context).data(absoluteMediaUrl(request.path))
                    .allowHardware(false).size(192).build()
                val result = context.imageLoader.execute(imageRequest) as? SuccessResult
                result?.drawable?.toBitmap()
            }.getOrNull()?.let { bitmap ->
                loaded = loaded + (request to bitmap)
                thumbnails = loaded
            }
        }
    }
    val avatarRequest = currentAvatarUrl?.takeIf { current != null && it.isNotBlank() }
    // 未设置头像（或网络加载失败）时回退默认头像资源
    val avatarData: Any = avatarRequest?.let { absoluteMediaUrl(it) } ?: R.drawable.default_head
    val currentAvatar by produceState<Bitmap?>(null, avatarData) {
        value = runCatching {
            val request = ImageRequest.Builder(context).data(avatarData)
                .allowHardware(false).size(144).build()
            val result = context.imageLoader.execute(request) as? SuccessResult
            result?.drawable?.toBitmap()
        }.getOrNull() ?: runCatching {
            val request = ImageRequest.Builder(context).data(R.drawable.default_head)
                .allowHardware(false).size(144).build()
            val result = context.imageLoader.execute(request) as? SuccessResult
            result?.drawable?.toBitmap()
        }.getOrNull()
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_RESUME -> mapView.onResume()
            Lifecycle.Event.ON_PAUSE -> mapView.onPause()
            Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); mapView.onDestroy() }
    }
    AndroidView(factory = { mapView }, modifier = modifier) { view ->
        val map = view.map
        map.clear()
        map.uiSettings.isZoomControlsEnabled = false
        val bounds = LatLngBounds.builder()
        val pointLookup = mutableMapOf<String, PointLite>()
        val endpointSpecs = if (showEndpoints) {
            RouteGeometry.endpoints(visibleDays)?.let { endpoints ->
                routeEndpointTypes(
                    showRouteEnd = showRouteEnd,
                    samePoint = endpoints.start.id == endpoints.end.id,
                ).map { type ->
                    when (type) {
                        EndpointMarkerType.START -> EndpointSpec(endpoints.start, "起点", type)
                        EndpointMarkerType.END -> EndpointSpec(endpoints.end, "终点", type)
                        EndpointMarkerType.ROUND_TRIP -> EndpointSpec(endpoints.start, "起点 / 终点", type)
                    }
                }
            }.orEmpty()
        } else emptyList()
        val representedEndpointSpecs = current?.let { location ->
            endpointSpecs.filter { spec ->
                RouteGeometry.distanceMeters(location.lat, location.lon, spec.point.lat, spec.point.lon) <= 50.0
            }
        }.orEmpty()
        val representedTypes = representedEndpointSpecs.map(EndpointSpec::type).toSet()
        val currentEndpointType = combinedEndpointType(representedTypes)
        val endpointPointIds = endpointSpecs.mapTo(mutableSetOf()) { it.point.id }
        val reservedPositions = endpointSpecs.asSequence()
            .filterNot { it.type in representedTypes }
            .map { map.projection.toScreenLocation(LatLng(it.point.lat, it.point.lon)).toMarkerScreenPoint() }
            .toMutableList()
            .apply {
                current?.let {
                    add(map.projection.toScreenLocation(LatLng(it.lat, it.lon)).toMarkerScreenPoint())
                }
            }
        val checkinCandidates = if (markerPresentation.checkinStyle == CheckinMarkerStyle.HIDDEN) {
            emptyList()
        } else {
            visibleDays.flatMap(DayGroup::points)
                .filter { it.isCheckin && it.id !in endpointPointIds }
                .filter { visibleViewport?.contains(it) != false }
        }
        val visibleCheckinIds = nonOverlappingMarkerIndices(
            points = checkinCandidates.map {
                map.projection.toScreenLocation(LatLng(it.lat, it.lon)).toMarkerScreenPoint()
            },
            reserved = reservedPositions,
            minHorizontalPx = markerPresentation.minHorizontalSpacingDp * context.resources.displayMetrics.density,
            minVerticalPx = markerPresentation.minVerticalSpacingDp * context.resources.displayMetrics.density,
        ).mapTo(mutableSetOf()) { checkinCandidates[it].id }
        visibleDays.forEach { day ->
            val index = days.indexOf(day)
            // The visible point markers and the polyline must use the same point set.
            val points = RouteGeometry.orderedPath(day.points).map { LatLng(it.lat, it.lon) }
            if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points).width(10f).color(dayColor(index).toInt()).geodesic(true))
            day.points.forEach pointLoop@ { point ->
                val position = LatLng(point.lat, point.lon); bounds.include(position)
                if (point.isCheckin && point.id in visibleCheckinIds) {
                    val thumbnail = point.images.firstOrNull()?.let { path ->
                        thumbnails[ThumbnailRequest(point.id, path)]
                    }
                    val icon = when (markerPresentation.checkinStyle) {
                        CheckinMarkerStyle.HIDDEN -> null
                        CheckinMarkerStyle.COMPACT -> createCompactCheckinMarkerBitmap(context)
                        CheckinMarkerStyle.CARD -> createCheckinMarkerBitmap(
                            context, point.name, thumbnail, markerPresentation.checkinScale)
                    }
                    if (icon == null) return@pointLoop
                    val marker = map.addMarker(MarkerOptions().position(position).title(point.name ?: "旅途打卡")
                        .icon(BitmapDescriptorFactory.fromBitmap(icon))
                        .anchor(.5f, 1f))
                    pointLookup[marker.id] = point
                } else if (!point.isCheckin) {
                    // 自动轨迹点：白底彩边圆圈（与 Web 分享页 CircleMarker 一致）
                    val dayColorInt = dayColor(index).toInt()
                    map.addMarker(MarkerOptions().position(position)
                        .icon(BitmapDescriptorFactory.fromBitmap(
                            autoDotCache.getOrPut(dayColorInt) {
                                createAutoPointDotBitmap(context, dayColorInt)
                            }))
                        .anchor(.5f, .5f)
                        .zIndex(1f))
                }
            }
        }
        endpointSpecs.filterNot { it.type in representedTypes }.forEach { spec ->
            val artwork = when (markerPresentation.endpointStyle) {
                EndpointMarkerStyle.COMPACT -> createCompactEndpointMarkerArtwork(context, spec.type)
                EndpointMarkerStyle.SIGN -> createEndpointMarkerArtwork(
                    context, spec.type, markerPresentation.endpointScale)
            }
            val marker = map.addMarker(
                MarkerOptions().position(LatLng(spec.point.lat, spec.point.lon)).title(spec.title)
                    .icon(BitmapDescriptorFactory.fromBitmap(artwork.bitmap))
                    .anchor(artwork.anchorU, artwork.anchorV)
                    .zIndex(6f),
            )
            if (spec.point.isCheckin) {
                pointLookup[marker.id] = spec.point
            }
        }
        latest?.takeIf { visibleDays.any { day -> day.points.any { point -> point.id == it.id } } }?.let {
            val position = LatLng(it.lat, it.lon)
            map.addCircle(CircleOptions().center(position).radius(35.0).fillColor(0x3300B96B).strokeColor(0xFF00B96B.toInt()).strokeWidth(3f))
        }
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition) = Unit

            override fun onCameraChangeFinish(position: CameraPosition) {
                visibleViewport = map.projection.visibleRegion.latLngBounds.toMapViewport()
                markerPresentation = markerPresentationForZoom(position.zoom)
            }
        })
        val all = visibleDays.flatMap { it.points }
        val viewportKey = "$selectedDayIdx:${all.joinToString { it.id }}"
        if (cameraMemory.viewportKey != viewportKey) {
            cameraMemory.viewportKey = viewportKey
            if (all.size == 1) map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(all[0].lat, all[0].lon), 15f))
            else if (all.size > 1) runCatching { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 90)) }
        } else if (visibleViewport == null) {
            visibleViewport = map.projection.visibleRegion.latLngBounds.toMapViewport()
        }
        if (current != null) {
            val position = LatLng(current.lat, current.lon)
            val visibleCurrentEndpointType = currentEndpointType.takeIf {
                markerPresentation.endpointStyle == EndpointMarkerStyle.SIGN
            }
            val artwork = createCurrentLocationMarkerArtwork(context, currentAvatar, visibleCurrentEndpointType)
            map.addCircle(CircleOptions().center(position).radius(35.0).fillColor(0x2600B96B).strokeColor(0x9900B96B.toInt()).strokeWidth(2f))
            val marker = map.addMarker(MarkerOptions().position(position)
                .title(currentEndpointType?.let { "当前位置 · ${it.label}" } ?: "当前位置")
                .icon(BitmapDescriptorFactory.fromBitmap(artwork.bitmap))
                .anchor(artwork.anchorU, artwork.anchorV)
                .zIndex(10f))
            representedEndpointSpecs.firstOrNull { it.point.isCheckin }?.let {
                pointLookup[marker.id] = it.point
            }
            if (all.isEmpty() || focusCurrentRequest > cameraMemory.focusRequest) {
                cameraMemory.focusRequest = focusCurrentRequest
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 16f))
            }
        }
        map.setOnMarkerClickListener { marker ->
            pointLookup[marker.id]?.let(onPointClick)
            true
        }
    }
}

private class MapCameraMemory(var viewportKey: String? = null, var focusRequest: Int = -1)

private data class EndpointSpec(
    val point: PointLite,
    val title: String,
    val type: EndpointMarkerType,
)

internal enum class CheckinMarkerStyle { HIDDEN, COMPACT, CARD }
internal enum class EndpointMarkerStyle { COMPACT, SIGN }

internal data class MarkerPresentation(
    val checkinStyle: CheckinMarkerStyle,
    val endpointStyle: EndpointMarkerStyle,
    val checkinScale: Float,
    val endpointScale: Float,
    val minHorizontalSpacingDp: Float,
    val minVerticalSpacingDp: Float,
)

internal fun markerPresentationForZoom(zoom: Float): MarkerPresentation = when {
    zoom < 10f -> MarkerPresentation(
        CheckinMarkerStyle.HIDDEN, EndpointMarkerStyle.COMPACT, .5f, .5f, 0f, 0f)
    zoom < 12f -> MarkerPresentation(
        CheckinMarkerStyle.COMPACT, EndpointMarkerStyle.COMPACT, .5f, .5f, 30f, 30f)
    zoom < 14f -> MarkerPresentation(
        CheckinMarkerStyle.CARD, EndpointMarkerStyle.SIGN, .7f, .7f, 82f, 72f)
    zoom < 16f -> MarkerPresentation(
        CheckinMarkerStyle.CARD, EndpointMarkerStyle.SIGN, .85f, .85f, 100f, 86f)
    else -> MarkerPresentation(
        CheckinMarkerStyle.CARD, EndpointMarkerStyle.SIGN, 1f, 1f, 116f, 100f)
}

internal data class MarkerScreenPoint(val x: Int, val y: Int)

internal fun nonOverlappingMarkerIndices(
    points: List<MarkerScreenPoint>,
    reserved: List<MarkerScreenPoint>,
    minHorizontalPx: Float,
    minVerticalPx: Float,
): List<Int> {
    val accepted = mutableListOf<MarkerScreenPoint>()
    return points.indices.filter { index ->
        val candidate = points[index]
        val overlaps = (reserved.asSequence() + accepted.asSequence()).any { occupied ->
            kotlin.math.abs(candidate.x - occupied.x) < minHorizontalPx &&
                kotlin.math.abs(candidate.y - occupied.y) < minVerticalPx
        }
        if (!overlaps) accepted += candidate
        !overlaps
    }
}

private fun android.graphics.Point.toMarkerScreenPoint() = MarkerScreenPoint(x, y)

internal data class ThumbnailRequest(val pointId: String, val path: String)

internal data class MapViewport(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    fun contains(point: PointLite): Boolean {
        if (point.lat !in south..north) return false
        return if (west <= east) point.lon in west..east else point.lon >= west || point.lon <= east
    }
}

internal fun visibleThumbnailRequests(
    points: List<PointLite>,
    viewport: MapViewport?,
): List<ThumbnailRequest> {
    if (viewport == null) return emptyList()
    return points.asSequence()
        .filter(PointLite::isCheckin)
        .filter(viewport::contains)
        .mapNotNull { point ->
            point.images.firstOrNull()?.let { ThumbnailRequest(point.id, it) }
        }
        .distinct()
        .toList()
}

private fun LatLngBounds.toMapViewport() = MapViewport(
    south = southwest.latitude,
    west = southwest.longitude,
    north = northeast.latitude,
    east = northeast.longitude,
)

internal fun combinedEndpointType(types: Set<EndpointMarkerType>): EndpointMarkerType? = when {
    EndpointMarkerType.ROUND_TRIP in types -> EndpointMarkerType.ROUND_TRIP
    EndpointMarkerType.START in types && EndpointMarkerType.END in types -> EndpointMarkerType.ROUND_TRIP
    EndpointMarkerType.START in types -> EndpointMarkerType.START
    EndpointMarkerType.END in types -> EndpointMarkerType.END
    else -> null
}

internal fun routeEndpointTypes(
    showRouteEnd: Boolean,
    samePoint: Boolean,
): List<EndpointMarkerType> = when {
    !showRouteEnd -> listOf(EndpointMarkerType.START)
    samePoint -> listOf(EndpointMarkerType.ROUND_TRIP)
    else -> listOf(EndpointMarkerType.START, EndpointMarkerType.END)
}
