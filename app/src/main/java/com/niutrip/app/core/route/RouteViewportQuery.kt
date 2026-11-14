package com.niutrip.app.core.route

import com.niutrip.app.core.GeoBbox

/** 视口内的连续可见区间（原始点序下标，含端点）。 */
data class RouteInterval(val startIndex: Int, val endIndexInclusive: Int)

/** 视口查询结果：LOD 保留点 + 可见区间；只访问内存索引，不触碰网络/数据库。 */
class RouteViewportResult(
    /** LOD 容差筛后的保留点绝对下标（升序）。 */
    val keptIndices: IntArray,
    /** 每个连续可见区间的原始下标范围；渲染时按区间独立构造 Polyline，禁止跨缺口连接。 */
    val intervals: List<RouteInterval>,
)

/**
 * 本地 chunk 视口查询（方案 §4.5）：bbox 与 chunk 相交 → 命中区间内做
 * “线段与 bbox 相交”判断（不只看端点）→ 每个区间由渲染层补前驱/后继原始点。
 */
object RouteViewportQuery {
    /** LOD 容差筛选：mandatory || importance >= toleranceMeters（方案 §4.4）。 */
    fun lodIndices(index: RouteGeometryIndex, toleranceMeters: Double): IntArray {
        val result = IntArray(index.pointCount)
        var size = 0
        for (i in 0 until index.pointCount) {
            if (index.mandatory[i] || index.importance[i].toDouble() >= toleranceMeters) {
                result[size++] = i
            }
        }
        return result.copyOf(size)
    }

    /**
     * 查询可见区间。
     * @param viewport 已外扩后的可视 bbox；null 表示全程模式（低缩放整段显示）。
     * @param selectedDayIndex 选中日；null 表示全程。
     */
    fun query(
        index: RouteGeometryIndex,
        toleranceMeters: Double,
        viewport: GeoBbox?,
        selectedDayIndex: Int? = null,
    ): RouteViewportResult {
        val kept = lodIndices(index, toleranceMeters)
        val parts = index.routeParts.filter { selectedDayIndex == null || it.dayIndex == selectedDayIndex }
        if (kept.isEmpty() || parts.isEmpty()) return RouteViewportResult(kept, emptyList())
        if (viewport == null) {
            return RouteViewportResult(
                kept,
                parts.map { RouteInterval(it.startIndex, it.endIndexInclusive) },
            )
        }

        val chunksByPart = index.chunks.groupBy(RouteChunk::partId)
        val intervals = mutableListOf<RouteInterval>()
        for ((partPosition, part) in parts.withIndex()) {
            if (selectedDayIndex == null && partPosition > 0) {
                val previousEnd = parts[partPosition - 1].endIndexInclusive
                if (segmentIntersects(index, viewport, previousEnd, part.startIndex)) {
                    intervals += RouteInterval(previousEnd, part.startIndex)
                }
            }
            val partChunks = chunksByPart[part.id].orEmpty()
            // 相交 chunk 只负责缩小扫描范围。不能把所有命中 chunk 合成一个大区间，
            // 否则回环轨迹的两次穿越会被错误连接。
            val candidateRanges = mutableListOf<RouteInterval>()
            for (chunk in partChunks) {
                if (!intersects(chunk.bbox, viewport)) continue
                val last = candidateRanges.lastOrNull()
                if (last != null && chunk.startIndex <= last.endIndexInclusive) {
                    candidateRanges[candidateRanges.lastIndex] = RouteInterval(
                        last.startIndex, maxOf(last.endIndexInclusive, chunk.endIndexInclusive))
                } else {
                    candidateRanges += RouteInterval(chunk.startIndex, chunk.endIndexInclusive)
                }
            }
            // 必须扫描原始连续线段，而不是 LOD 点之间的长弦。否则一个被抽稀掉的
            // 发卡弯进入视口时，粗弦不相交会让整段轨迹消失。
            for (range in candidateRanges) {
                var segment = maxOf(range.startIndex, part.startIndex)
                val lastSegment = minOf(range.endIndexInclusive, part.endIndexInclusive) - 1
                while (segment <= lastSegment) {
                    if (!segmentIntersects(index, viewport, segment, segment + 1)) {
                        segment++
                        continue
                    }
                    val firstHit = segment
                    while (segment <= lastSegment &&
                        segmentIntersects(index, viewport, segment, segment + 1)
                    ) {
                        segment++
                    }
                    val interval = RouteInterval(firstHit, segment)
                    val previous = intervals.lastOrNull()
                    if (previous != null && interval.startIndex <= previous.endIndexInclusive) {
                        intervals[intervals.lastIndex] = RouteInterval(
                            previous.startIndex, maxOf(previous.endIndexInclusive, interval.endIndexInclusive))
                    } else {
                        intervals += interval
                    }
                }
            }
        }
        return RouteViewportResult(kept, intervals)
    }

    private fun intersects(a: GeoBbox, b: GeoBbox): Boolean =
        a.minLng <= b.maxLng && a.maxLng >= b.minLng && a.minLat <= b.maxLat && a.maxLat >= b.minLat

    /** Liang-Barsky 线段与矩形相交测试：端点在框内、穿越框体都算相交。 */
    private fun segmentIntersects(index: RouteGeometryIndex, box: GeoBbox, i: Int, j: Int): Boolean {
        val ax = index.lonAt(i); val ay = index.latAt(i)
        val bx = index.lonAt(j); val by = index.latAt(j)
        var t0 = 0.0
        var t1 = 1.0
        val dx = bx - ax
        val dy = by - ay
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(ax - box.minLng, box.maxLng - ax, ay - box.minLat, box.maxLat - ay)
        for (edge in 0..3) {
            if (p[edge] == 0.0) {
                if (q[edge] < 0) return false
            } else {
                val r = q[edge] / p[edge]
                if (p[edge] < 0) {
                    if (r > t1) return false
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return false
                    if (r < t1) t1 = r
                }
            }
        }
        return true
    }
}
