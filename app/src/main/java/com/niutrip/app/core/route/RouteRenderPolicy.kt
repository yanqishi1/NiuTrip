package com.niutrip.app.core.route

import com.niutrip.app.core.GeoBbox
import kotlin.math.cos

/** 单条渲染 Polyline：所属日颜色索引 + 索引数组中的点下标（有序、去重）。 */
data class RoutePolyline(val dayIndex: Int, val pointIndices: List<Int>)

/** 相机静止后的渲染请求。viewport 为相机可视 bbox（策略内部负责外扩）。 */
data class RouteRenderQuery(
    val generation: Long,
    val zoom: Float,
    val viewport: GeoBbox?,
    val selectedDayIndex: Int?,
)

/** 渲染重建的去重 key（方案 §6.7）：不变则不重建 Polyline。 */
data class RouteRenderKey(
    val generation: Long,
    val lodBand: Int,
    val viewportCellX: Int? = null,
    val viewportCellY: Int? = null,
    val viewportScaleX: Int? = null,
    val viewportScaleY: Int? = null,
    val selectedDay: Int,
)

/** 一次渲染的完整模型：主线程按序 add 全部 Polyline 后再移除旧层。 */
data class RouteRenderModel(
    val key: RouteRenderKey,
    val band: Int,
    val polylines: List<RoutePolyline>,
    /** 去重后的渲染顶点总数。 */
    val pointCount: Int,
)

/**
 * 渲染策略（方案 §4.3/§6.7）：zoom 档位、容差、渲染 key 与模型生成。
 */
object RouteRenderPolicy {
    /** 本地嵌套 LOD 功能开关（方案 §12）：关闭时退回完整点直接绘制。 */
    const val LOCAL_ROUTE_LOD_ENABLED = true

    /** zoom 14 起才按视口裁剪；低缩放绘制全程 LOD，避免总览轨迹随平移被截断。 */
    private const val VIEWPORT_QUERY_MIN_BAND = 14

    /** 取不高于 zoom 的最近档位；低于最低档时取最低档（方案 §4.3）。 */
    fun bandFor(zoom: Float): Int {
        var result = RouteLodParams.LOD_ZOOM_BANDS.first()
        for (band in RouteLodParams.LOD_ZOOM_BANDS) {
            if (band.toFloat() <= zoom) result = band
        }
        return result
    }

    /** zoom 档位 → 容差（米）：metersPerPixel(band, latitude) * TOLERANCE_PIXELS（方案 §4.3）。 */
    fun toleranceMeters(band: Int, latitude: Double): Double =
        RouteLodParams.EQUATOR_METERS_PER_PIXEL_AT_ZOOM_0 *
            cos(Math.toRadians(latitude)) / (1L shl band) *
            RouteLodParams.TOLERANCE_PIXELS

    /** 视口中心量化网格（避免每次微小移动都触发重建 key 变化）。 */
    fun viewportCell(bbox: GeoBbox): Pair<Int, Int> {
        val width = (bbox.maxLng - bbox.minLng).coerceAtLeast(1e-7)
        val height = (bbox.maxLat - bbox.minLat).coerceAtLeast(1e-7)
        return Pair(
            Math.floor((bbox.minLng + bbox.maxLng) / width).toInt(),
            Math.floor((bbox.minLat + bbox.maxLat) / height).toInt(),
        )
    }

    /** 把视口跨度按 1/8 个 octave 量化，保证同一 LOD 档内大幅缩放也会刷新裁剪结果。 */
    fun viewportScale(bbox: GeoBbox): Pair<Int, Int> = Pair(
        Math.floor(kotlin.math.log2((bbox.maxLng - bbox.minLng).coerceAtLeast(1e-7)) * 8).toInt(),
        Math.floor(kotlin.math.log2((bbox.maxLat - bbox.minLat).coerceAtLeast(1e-7)) * 8).toInt(),
    )

    fun renderKey(query: RouteRenderQuery): RouteRenderKey {
        val useViewport = bandFor(query.zoom) >= VIEWPORT_QUERY_MIN_BAND
        val cell = query.viewport?.takeIf { useViewport }?.let(::viewportCell)
        val scale = query.viewport?.takeIf { useViewport }?.let(::viewportScale)
        return RouteRenderKey(
            generation = query.generation,
            lodBand = bandFor(query.zoom),
            viewportCellX = cell?.first,
            viewportCellY = cell?.second,
            viewportScaleX = scale?.first,
            viewportScaleY = scale?.second,
            selectedDay = query.selectedDayIndex ?: -1,
        )
    }

    /**
     * 由索引 + 查询生成渲染模型：LOD 筛选 → 视口外扩 → 区间查询 → 每区间补
     * 前驱/后继原始点 → ≤[RouteLodParams.MAX_POLYLINE_POINTS] 分段（相邻段重叠
     * 一个端点）→ 全程模式跨日首段补上一日末点（方案 §3.2/§4.5/§6.7）。
     */
    fun buildRenderModel(
        index: RouteGeometryIndex,
        query: RouteRenderQuery,
        useLod: Boolean = LOCAL_ROUTE_LOD_ENABLED,
    ): RouteRenderModel {
        val band = bandFor(query.zoom)
        val tolerance = if (useLod) toleranceMeters(band, index.referenceLatitude) else 0.0
        val padded = query.viewport
            ?.takeIf { band >= VIEWPORT_QUERY_MIN_BAND }
            ?.padded(RouteLodParams.VIEWPORT_PADDING_RATIO)
        val result = RouteViewportQuery.query(index, tolerance, padded, query.selectedDayIndex)
        val parts = index.routeParts
            .filter { query.selectedDayIndex == null || it.dayIndex == query.selectedDayIndex }
        val rendered = BooleanArray(index.pointCount)
        val polylines = mutableListOf<RoutePolyline>()
        for (interval in result.intervals) {
            val part = parts.firstOrNull {
                interval.endIndexInclusive in it.startIndex..it.endIndexInclusive
            } ?: continue
            val indices = mutableListOf<Int>()
            for (kept in result.keptIndices) {
                if (kept in interval.startIndex..interval.endIndexInclusive) indices += kept
            }
            // 可见性来自原始连续线段；即使该发卡弯的内部点在当前 LOD 被抽稀，
            // 命中线段的两端仍必须进入本次局部 Polyline。
            if (interval.startIndex !in indices) indices += interval.startIndex
            if (interval.endIndexInclusive !in indices) indices += interval.endIndexInclusive
            // 前驱/后继原始点：保证线段跨出视口边缘而不是停在框上（方案 §4.5.4）
            if (interval.startIndex > part.startIndex && interval.startIndex - 1 !in indices) {
                indices.add(0, interval.startIndex - 1)
            }
            if (interval.endIndexInclusive < part.endIndexInclusive &&
                interval.endIndexInclusive + 1 !in indices
            ) {
                indices += interval.endIndexInclusive + 1
            }
            // 全程模式跨日连接：首段显式补上一日末点（方案 §3.2）
            if (query.viewport == null && query.selectedDayIndex == null) {
                val partPosition = parts.indexOf(part)
                if (partPosition > 0) {
                    val previousEnd = parts[partPosition - 1].endIndexInclusive
                    if (previousEnd !in indices) indices.add(0, previousEnd)
                }
            }
            indices.sort()
            // 分段：每条 ≤ MAX_POLYLINE_POINTS，相邻段重叠一个端点（方案 §6.7）
            var start = 0
            while (true) {
                val end = minOf(start + RouteLodParams.MAX_POLYLINE_POINTS, indices.size)
                val slice = indices.subList(start, end).toList()
                polylines += RoutePolyline(part.dayIndex, slice)
                slice.forEach { rendered[it] = true }
                if (end == indices.size) break
                start = end - 1
            }
        }
        var uniqueCount = 0
        for (flag in rendered) if (flag) uniqueCount++
        return RouteRenderModel(renderKey(query), band, polylines, uniqueCount)
    }
}
