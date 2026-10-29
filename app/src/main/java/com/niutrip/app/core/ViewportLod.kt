package com.niutrip.app.core

import java.time.LocalDate

/** 视口 LOD 生效缩放级别：与 Web 端、自动点圆点显示（zoom ≥ 14）一致。 */
const val DETAIL_ZOOM = 14f

/** 拉取前视口每边外扩倍率（0.5 = 半屏，平移约半屏才需下一次拉取）。 */
const val DETAIL_BBOX_PADDING = 0.5

/** bbox 查询参数：minLng,minLat,maxLng,maxLat，保留 5 位小数（同 Web 端）。 */
fun GeoBbox.toQueryParam(): String =
    listOf(minLng, minLat, maxLng, maxLat).joinToString(",") { "%.5f".format(it) }

/**
 * 视口 LOD（与 Web 分享页 routeDetail.ts 同思路）：
 * 高缩放级别按可视区域拉取局部细点，缩放/平移只拉未覆盖的区域。
 */
data class GeoBbox(
    val minLng: Double,
    val minLat: Double,
    val maxLng: Double,
    val maxLat: Double,
) {
    /** 每边外扩 ratio 倍视口宽高（0.5 = 每边外扩半个视口，平移约半屏才需下一次拉取）。 */
    fun padded(ratio: Double): GeoBbox = GeoBbox(
        minLng = minLng - (maxLng - minLng) * ratio,
        minLat = minLat - (maxLat - minLat) * ratio,
        maxLng = maxLng + (maxLng - minLng) * ratio,
        maxLat = maxLat + (maxLat - minLat) * ratio,
    )

    /** inner 是否完全落在本盒内（含边界）；任一缓存片段覆盖目标即免拉取。 */
    fun covers(inner: GeoBbox): Boolean = minLng <= inner.minLng && maxLng >= inner.maxLng &&
        minLat <= inner.minLat && maxLat >= inner.maxLat
}

/**
 * bbox 片段缓存：片段到达顺序任意，按 point_id 去重（相邻片段边界重叠）、
 * 按日分桶、组内 (time, id) 升序——渲染在高缩放级别按日期整组替换抽稀线。
 */
class DetailSegmentCache(private val limit: Int = 8) {
    private val segments = ArrayDeque<Pair<GeoBbox, List<PointLite>>>()

    fun covers(want: GeoBbox): Boolean = segments.any { it.first.covers(want) }

    fun add(bbox: GeoBbox, points: List<PointLite>) {
        segments.addLast(bbox to points)
        while (segments.size > limit) segments.removeFirst()
    }

    fun mergedDays(): Map<LocalDate, List<PointLite>> {
        val byId = LinkedHashMap<String, PointLite>()
        segments.forEach { (_, points) -> points.forEach { byId[it.id] = it } }
        return byId.values
            .groupBy { it.time.toLocalDate() }
            .mapValues { (_, dayPoints) ->
                dayPoints.sortedWith(compareBy(PointLite::time, PointLite::id))
            }
            .toSortedMap()
    }
}
