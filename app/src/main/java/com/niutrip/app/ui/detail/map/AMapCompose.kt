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
    current: PointLite? = null,  // 无轨迹点时聚焦的当前位置
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
    val visibleDays = if (selectedDayIdx in days.indices) listOf(days[selectedDayIdx]) else days
    val thumbnailRequests = visibleThumbnailRequests(
        points = visibleDays.flatMap(DayGroup::points),
        viewport = visibleViewport,
    )
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
        visibleDays.forEach { day ->
            val index = days.indexOf(day)
            // The visible point markers and the polyline must use the same point set.
            val points = RouteGeometry.orderedPath(day.points).map { LatLng(it.lat, it.lon) }
            if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points).width(10f).color(dayColor(index).toInt()).geodesic(true))
            day.points.forEach { point ->
                val position = LatLng(point.lat, point.lon); bounds.include(position)
                if (point.isCheckin) {
                    val thumbnail = point.images.firstOrNull()?.let { path ->
                        thumbnails[ThumbnailRequest(point.id, path)]
                    }
                    val marker = map.addMarker(MarkerOptions().position(position).title(point.name ?: "旅途打卡")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                            createCheckinMarkerBitmap(context, point.name, thumbnail)))
                        .anchor(.5f, 1f))
                    pointLookup[marker.id] = point
                } else {
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
        var currentEndpointType: EndpointMarkerType? = null
        if (showEndpoints) RouteGeometry.endpoints(visibleDays)?.let { endpoints ->
            val endpointSpecs = routeEndpointTypes(
                showRouteEnd = showRouteEnd,
                samePoint = endpoints.start.id == endpoints.end.id,
            ).map { type ->
                when (type) {
                    EndpointMarkerType.START -> Triple(endpoints.start, "起点", type)
                    EndpointMarkerType.END -> Triple(endpoints.end, "终点", type)
                    EndpointMarkerType.ROUND_TRIP -> Triple(endpoints.start, "起点 / 终点", type)
                }
            }
            val representedTypes = current?.let { location ->
                endpointSpecs.asSequence()
                    .filter { (point) ->
                        RouteGeometry.distanceMeters(location.lat, location.lon, point.lat, point.lon) <= 50.0
                    }
                    .map { it.third }
                    .toSet()
            }.orEmpty()
            currentEndpointType = combinedEndpointType(representedTypes)
            endpointSpecs.filterNot { it.third in representedTypes }.forEach { (point, title, type) ->
                val artwork = createEndpointMarkerArtwork(context, type)
                val marker = map.addMarker(
                    MarkerOptions().position(LatLng(point.lat, point.lon)).title(title)
                        .icon(BitmapDescriptorFactory.fromBitmap(artwork.bitmap))
                        .anchor(artwork.anchorU, artwork.anchorV)
                        .zIndex(6f),
                )
                if (point.isCheckin) {
                    pointLookup[marker.id] = point
                }
            }
        }
        latest?.takeIf { visibleDays.any { day -> day.points.any { point -> point.id == it.id } } }?.let {
            val position = LatLng(it.lat, it.lon)
            map.addCircle(CircleOptions().center(position).radius(35.0).fillColor(0x3300B96B).strokeColor(0xFF00B96B.toInt()).strokeWidth(3f))
        }
        map.setOnMarkerClickListener { marker ->
            pointLookup[marker.id]?.let(onPointClick)
            true
        }
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition) = Unit

            override fun onCameraChangeFinish(position: CameraPosition) {
                visibleViewport = map.projection.visibleRegion.latLngBounds.toMapViewport()
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
            val artwork = createCurrentLocationMarkerArtwork(context, currentAvatar, currentEndpointType)
            map.addCircle(CircleOptions().center(position).radius(35.0).fillColor(0x2600B96B).strokeColor(0x9900B96B.toInt()).strokeWidth(2f))
            map.addMarker(MarkerOptions().position(position)
                .title(currentEndpointType?.let { "当前位置 · ${it.label}" } ?: "当前位置")
                .icon(BitmapDescriptorFactory.fromBitmap(artwork.bitmap))
                .anchor(artwork.anchorU, artwork.anchorV)
                .zIndex(10f))
            if (all.isEmpty() || focusCurrentRequest > cameraMemory.focusRequest) {
                cameraMemory.focusRequest = focusCurrentRequest
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 16f))
            }
        }
    }
}

private class MapCameraMemory(var viewportKey: String? = null, var focusRequest: Int = -1)

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
