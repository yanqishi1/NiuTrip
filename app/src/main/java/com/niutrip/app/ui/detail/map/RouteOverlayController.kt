package com.niutrip.app.ui.detail.map

import com.amap.api.maps.AMap
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.niutrip.app.core.dayColor
import com.niutrip.app.core.route.RouteGeometryIndex
import com.niutrip.app.core.route.RouteRenderKey
import com.niutrip.app.core.route.RouteRenderModel

/** 控制器与高德之间的顶点（纯数据，测试可脱离 SDK 构造）。 */
data class RouteVertex(val latitude: Double, val longitude: Double)

/** 一条待添加的轨迹线：日颜色索引 + 有序顶点。 */
data class RouteOverlaySpec(val dayIndex: Int, val points: List<RouteVertex>)

/** Overlay 宿主抽象：JVM 测试注入录制 fake 验证 add/remove 顺序（方案 §6.8）。 */
interface RouteOverlayHost {
    fun addRoutePolyline(spec: RouteOverlaySpec): Int
    fun removeRoutePolyline(handle: Int)
}

/**
 * 轨迹线控制器（方案 §6.7）：active/pending 原子切换——新层全部创建完成后才移除旧层，
 * 不出现空白帧；同 key 不重建。Overlay 句柄由宿主持有，控制器只管顺序与生命周期。
 */
class RouteOverlayController(private val host: RouteOverlayHost) {
    private var active: List<Int> = emptyList()

    var appliedKey: RouteRenderKey? = null
        private set

    fun replace(model: RouteRenderModel, index: RouteGeometryIndex) {
        if (model.key == appliedKey) return
        val pending = model.polylines.map { polyline ->
            host.addRoutePolyline(RouteOverlaySpec(
                dayIndex = polyline.dayIndex,
                points = polyline.pointIndices.map { RouteVertex(index.latAt(it), index.lonAt(it)) },
            ))
        }
        active.forEach(host::removeRoutePolyline)
        active = pending
        appliedKey = model.key
    }

    fun clear() {
        active.forEach(host::removeRoutePolyline)
        active = emptyList()
        appliedKey = null
    }
}

/** 高德实现：按日颜色画线（宽度与旧路径一致 10px），持真实 Polyline 引用负责删除。 */
class MapPolylineRegistry(private val map: AMap) : RouteOverlayHost {
    private val polylines = LinkedHashMap<Int, Polyline>()
    private var nextHandle = 1

    override fun addRoutePolyline(spec: RouteOverlaySpec): Int {
        val polyline = map.addPolyline(PolylineOptions()
            .addAll(spec.points.map { LatLng(it.latitude, it.longitude) })
            .width(10f)
            .color(dayColor(spec.dayIndex).toInt()))
        val handle = nextHandle++
        polylines[handle] = polyline
        return handle
    }

    override fun removeRoutePolyline(handle: Int) {
        polylines.remove(handle)?.remove()
    }

    fun clearAll() {
        polylines.values.toList().forEach(Polyline::remove)
        polylines.clear()
    }

    fun size(): Int = polylines.size
}
