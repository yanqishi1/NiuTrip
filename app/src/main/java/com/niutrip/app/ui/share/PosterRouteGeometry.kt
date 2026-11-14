package com.niutrip.app.ui.share

import com.niutrip.app.core.DayGroup
import com.niutrip.app.core.PointLite
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * 照片模式透明轨迹的几何管线（plan §8，纯逻辑）：
 * 投影（Web Mercator 局部相对坐标）→ 包围盒（最小跨度保护）→ 等比适配模块 → 简化（锚点保留）。
 * 里程与统计永远使用完整有效点，不从简化点回算。
 */
data class PosterRoutePoint(val point: PointLite, val x: Double, val y: Double)

data class PosterProjectedBounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double) {
    val spanX: Double get() = maxX - minX
    val spanY: Double get() = maxY - minY
}

data class PosterPointF(val x: Double, val y: Double)

class PosterRouteTransform internal constructor(
    private val scaleX: Double,
    private val scaleY: Double,
    private val offsetX: Double,
    private val offsetY: Double,
) {
    fun map(x: Double, y: Double): PosterPointF = PosterPointF(offsetX + x * scaleX, offsetY + y * scaleY)
}

class PosterRouteModel internal constructor(
    val paths: List<List<PosterRoutePoint>>,
    /** 与 paths 一一对应的绘制日序号（衔接点归入后一天），用于按天分色。 */
    val pathDayIndices: List<Int>,
    val bounds: PosterProjectedBounds,
) {
    /** 投影包围盒的宽高比（w/h），带最小跨度保护；路线模块高度由宽度除以该值推导。 */
    val aspect: Double get() = protectedSpan(bounds.spanX) / protectedSpan(bounds.spanY)

    /**
     * 按最大投影偏差简化（Ramer-Douglas-Peucker）。起终点、按天边界衔接点和打卡点为保留锚点，
     * 简化不得删除它们；包围盒保持完整数据的包围盒，布局不随简化抖动。
     */
    fun simplified(maxDeviation: Double): PosterRouteModel {
        if (maxDeviation <= 0.0) return this
        val simplifiedPaths = paths.map { path ->
            if (path.size <= 2) path else simplifyPath(path, maxDeviation)
        }
        return PosterRouteModel(simplifiedPaths, pathDayIndices, bounds)
    }

    /**
     * 以同一个缩放系数等比适配模块内部区域（扣除 padding 的 innerW/innerH），居中放置；
     * 纵轴方向：投影 y 向上，屏幕 y 向下（北在上）。
     */
    fun fit(moduleWidth: Double, moduleHeight: Double, padding: Double): PosterRouteTransform {
        val innerW = (moduleWidth - padding * 2).coerceAtLeast(0.0)
        val innerH = (moduleHeight - padding * 2).coerceAtLeast(0.0)
        val spanX = protectedSpan(bounds.spanX)
        val spanY = protectedSpan(bounds.spanY)
        val scale = minOf(innerW / spanX, innerH / spanY)
        val centerX = (bounds.minX + bounds.maxX) / 2
        val centerY = (bounds.minY + bounds.maxY) / 2
        return PosterRouteTransform(
            scaleX = scale,
            scaleY = -scale,
            offsetX = moduleWidth / 2 - centerX * scale,
            offsetY = moduleHeight / 2 + centerY * scale,
        )
    }

    companion object {
        /** Mercator 纬度可用范围；超出部分按边界处理，避免 tan(π/2) 发散。 */
        private const val MERCATOR_MAX_LAT = 85.0511287798066
        private const val MIN_SPAN = 1e-6

        /** 按天构建路线，沿用跨日衔接语义：前一天的终点接入下一天起点（与现有地图一致）。 */
        fun build(days: List<DayGroup>): PosterRouteModel {
            var previousEnd: PointLite? = null
            var lastUnwrappedLon: Double? = null
            val paths = mutableListOf<List<PosterRoutePoint>>()
            val dayIndices = mutableListOf<Int>()
            days.forEachIndexed { dayIndex, day ->
                val ordered = day.points.sortedBy(PointLite::time)
                if (ordered.isEmpty()) return@forEachIndexed
                val connected = previousEnd?.let { listOf(it) + ordered } ?: ordered
                previousEnd = ordered.last()
                val projected = mutableListOf<PosterRoutePoint>()
                connected.forEach { point ->
                    val lon = unwrapLongitude(point.lon, lastUnwrappedLon)
                    lastUnwrappedLon = lon
                    projected.add(PosterRoutePoint(point, lon, mercatorY(point.lat)))
                }
                paths.add(projected)
                dayIndices.add(dayIndex)
            }
            return PosterRouteModel(paths, dayIndices, boundsOf(paths))
        }

        /** 跨日期变更线：相对上一个已展开经度 ±360 展开，避免横穿整张图的连线。 */
        private fun unwrapLongitude(rawLon: Double, previousUnwrapped: Double?): Double {
            if (previousUnwrapped == null) return rawLon
            val shifts = ((previousUnwrapped - rawLon) / 360.0).roundToInt()
            return rawLon + 360.0 * shifts
        }

        private fun mercatorY(lat: Double): Double {
            val clamped = lat.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)
            // 与 x 轴（经度·度）统一量级：否则 RDP 阈值在纬度方向等效放大约 57 倍，
            // 真实轨迹的弯道会被整体简化成直线（真机反馈回归）。
            return ln(tan(PI / 4 + Math.toRadians(clamped) / 2)) * 180.0 / PI
        }

        private fun protectedSpan(span: Double): Double = if (span < MIN_SPAN) MIN_SPAN else span

        private fun boundsOf(paths: List<List<PosterRoutePoint>>): PosterProjectedBounds {
            val all = paths.flatten()
            if (all.isEmpty()) return PosterProjectedBounds(0.0, 0.0, 0.0, 0.0)
            return PosterProjectedBounds(
                minX = all.minOf { it.x }, maxX = all.maxOf { it.x },
                minY = all.minOf { it.y }, maxY = all.maxOf { it.y },
            )
        }

        /** 锚点（起终点与打卡点）之间的窗口内做标准 RDP，锚点本身永不删除。 */
        private fun simplifyPath(path: List<PosterRoutePoint>, epsilon: Double): List<PosterRoutePoint> {
            val keep = BooleanArray(path.size)
            keep[0] = true
            keep[path.lastIndex] = true
            path.forEachIndexed { index, routePoint ->
                if (routePoint.point.isCheckin) keep[index] = true
            }
            val anchorIndices = (0..path.lastIndex).filter { keep[it] }
            val result = mutableListOf<PosterRoutePoint>()
            for (window in anchorIndices.zipWithNext()) {
                result += rdpKeep(path, window.first, window.second, epsilon).dropLast(1)
            }
            result += path.last()
            return result
        }

        /** 返回 [start, end] 闭区间内按 RDP 保留的点（含两端）。 */
        private fun rdpKeep(
            path: List<PosterRoutePoint>,
            start: Int,
            end: Int,
            epsilon: Double,
        ): List<PosterRoutePoint> {
            if (end <= start + 1) return listOf(path[start], path[end])
            val ax = path[start].x; val ay = path[start].y
            val bx = path[end].x; val by = path[end].y
            val dx = bx - ax; val dy = by - ay
            val lengthSq = dx * dx + dy * dy
            var farthest = -1
            var farthestDistance = 0.0
            for (index in start + 1 until end) {
                val px = path[index].x; val py = path[index].y
                val distance = if (lengthSq == 0.0) hypot(px - ax, py - ay)
                else {
                    val t = ((px - ax) * dx + (py - ay) * dy) / lengthSq
                    hypot(px - (ax + t * dx), py - (ay + t * dy))
                }
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthest = index
                }
            }
            return if (farthestDistance > epsilon) {
                rdpKeep(path, start, farthest, epsilon) + rdpKeep(path, farthest, end, epsilon).drop(1)
            } else {
                listOf(path[start], path[end])
            }
        }
    }
}
