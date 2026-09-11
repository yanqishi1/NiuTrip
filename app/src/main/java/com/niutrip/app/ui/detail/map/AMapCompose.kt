package com.niutrip.app.ui.detail.map

import android.graphics.Color
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.*
import com.niutrip.app.core.DayGroup
import com.niutrip.app.core.PointLite
import com.niutrip.app.core.dayColor

@Composable fun AMapView(
    days: List<DayGroup>,
    selectedDayIdx: Int,
    latest: PointLite?,
    modifier: Modifier = Modifier,
    onPointClick: (PointLite) -> Unit = {},
    current: PointLite? = null,  // 无轨迹点时聚焦的当前位置
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { TextureMapView(context).apply { onCreate(Bundle()) } }
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
        val visible = if (selectedDayIdx in days.indices) listOf(days[selectedDayIdx]) else days
        val bounds = LatLngBounds.builder()
        val pointLookup = mutableMapOf<String, PointLite>()
        visible.forEach { day ->
            val index = days.indexOf(day)
            val points = day.points.map { LatLng(it.lat, it.lon) }
            if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points).width(10f).color(dayColor(index).toInt()).geodesic(true))
            day.points.forEach { point ->
                val position = LatLng(point.lat, point.lon); bounds.include(position)
                if (point.isCheckin) {
                    val marker = map.addMarker(MarkerOptions().position(position).title(point.name ?: "旅途打卡")
                        .snippet(point.desc.orEmpty()).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)))
                    pointLookup[marker.id] = point
                }
            }
        }
        latest?.takeIf { visible.any { day -> day.points.any { point -> point.id == it.id } } }?.let {
            val position = LatLng(it.lat, it.lon)
            map.addCircle(CircleOptions().center(position).radius(35.0).fillColor(0x3300B96B).strokeColor(0xFF00B96B.toInt()).strokeWidth(3f))
            map.addMarker(MarkerOptions().position(position).title("最新位置").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
        }
        map.setOnMarkerClickListener { marker -> pointLookup[marker.id]?.let(onPointClick); marker.showInfoWindow(); true }
        val all = visible.flatMap { it.points }
        if (all.size == 1) map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(all[0].lat, all[0].lon), 15f))
        else if (all.size > 1) runCatching { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 90)) }
        else if (current != null) {
            // 空轨迹：相机移到当前位置并标记
            val position = LatLng(current.lat, current.lon)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 15f))
            map.addCircle(CircleOptions().center(position).radius(35.0).fillColor(0x3300B96B).strokeColor(0xFF00B96B.toInt()).strokeWidth(3f))
            map.addMarker(MarkerOptions().position(position).title("当前位置").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
        }
    }
}
