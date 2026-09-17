package com.niutrip.app.ui.share

import android.graphics.Bitmap
import android.os.Bundle
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
import com.niutrip.app.core.RouteGeometry
import com.niutrip.app.core.dayColor
import com.niutrip.app.ui.detail.map.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
                withFrameNanos { }
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
