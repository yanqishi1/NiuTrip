package com.niutrip.app.core.route

import com.niutrip.app.core.GeoBbox
import com.niutrip.app.core.toEpochMillis
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteViewportQueryTest {
    private val baseTime = LocalDateTime.of(2026, 1, 1, 8, 0).toEpochMillis()
    private val day = LocalDate.of(2026, 1, 1)

    private fun p(id: String, minute: Long, lon: Double, lat: Double, checkin: Boolean = false) =
        CanonicalRoutePoint(id, baseTime + minute * 60_000, lon, lat, checkin, day)

    /** LOD 全保留容差：隔离视口裁剪逻辑与 LOD 筛选。 */
    private val keepAll = 0.0

    @Test
    fun `segment crossing viewport with both endpoints outside is returned`() {
        val points = listOf(
            p("x0", 0, 116.000, 39.000),
            p("x1", 1, 116.006, 39.000),
            p("x2", 2, 116.012, 39.000),
            p("x3", 3, 116.018, 39.000),
        )
        val index = RouteLodBuilder.build("t1", points)
        val tolerance = RouteRenderPolicy.toleranceMeters(8, index.referenceLatitude)
        val box = GeoBbox(116.007, 38.995, 116.011, 39.005)  // 只覆盖线段中段，不含任何端点

        val result = RouteViewportQuery.query(index, tolerance, box)

        assertEquals(1, result.intervals.size)
        assertEquals(1, result.intervals.single().startIndex)
        assertEquals(2, result.intervals.single().endIndexInclusive)
        // 共线中点被 LOD 丢弃，但穿越线段两端的保留点都在
        assertEquals(setOf("x0", "x3"), result.keptIndices.map(index::idAt).toSet())
    }

    /** U 形轨迹穿越视口两次：两次穿越之间必须断开，且每段补前驱/后继原始点。 */
    private fun uShapeIndex(): RouteGeometryIndex {
        val points = listOf(
            p("q0", 0, 116.000, 39.0000),
            p("q1", 1, 116.020, 39.0000),
            p("q2", 2, 116.020, 39.0060),   // 北上离开视口
            p("q3", 3, 115.996, 39.0060),
            p("q4", 4, 115.996, 39.0000),   // 从视口西侧回到纬线
            p("q5", 5, 116.032, 39.0000),
        )
        return RouteLodBuilder.build("t1", points)
    }

    @Test
    fun `two traversals produce disconnected intervals without bridging`() {
        val index = uShapeIndex()
        val tolerance = RouteRenderPolicy.toleranceMeters(8, index.referenceLatitude)
        val box = GeoBbox(116.004, 38.9999, 116.010, 39.0001)

        val result = RouteViewportQuery.query(index, tolerance, box)

        assertEquals(2, result.intervals.size)
        assertEquals(0, result.intervals[0].startIndex)
        assertEquals(1, result.intervals[0].endIndexInclusive)
        assertEquals(4, result.intervals[1].startIndex)
        assertEquals(5, result.intervals[1].endIndexInclusive)
        // 缺口（q2、q3 未命中）不得被并成一个区间
        assertTrue(result.intervals[1].startIndex > result.intervals[0].endIndexInclusive)
    }

    @Test
    fun `disconnected polylines keep predecessor and successor raw points`() {
        val index = uShapeIndex()
        val box = GeoBbox(116.004, 38.9999, 116.010, 39.0001)

        val model = RouteRenderPolicy.buildRenderModel(
            index, RouteRenderQuery(generation = 1, zoom = 14f, viewport = box, selectedDayIndex = null))

        assertEquals(2, model.polylines.size)
        assertEquals(listOf("q0", "q1", "q2"), model.polylines[0].pointIndices.map(index::idAt))
        assertEquals(listOf("q3", "q4", "q5"), model.polylines[1].pointIndices.map(index::idAt))
    }

    @Test
    fun `adjacent chunks join seamlessly across the boundary`() {
        // 600 点正弦轨迹：chunk 边界在 511（[0..511] 与 [511..599] 共享边界点）
        val points = (0 until 600).map { i ->
            p("w%03d".format(i), i.toLong(), 116.0 + i * 0.00002, 39.0 + if (i % 2 == 0) 0.0001 else -0.0001)
        }
        val index = RouteLodBuilder.build("t1", points)
        assertEquals(2, index.chunks.size)
        assertEquals(511, index.chunks[0].endIndexInclusive)
        assertEquals(511, index.chunks[1].startIndex)

        val box = GeoBbox(116.0100, 38.9999, 116.0104, 39.0001)  // 覆盖下标 ~500..520 区域
        val result = RouteViewportQuery.query(index, keepAll, box)

        assertEquals(1, result.intervals.size)
        val interval = result.intervals.single()
        assertTrue("interval must span the chunk seam (511)", interval.startIndex < 511 && interval.endIndexInclusive > 511)
    }

    @Test
    fun `full route mode returns whole parts per day`() {
        val day2 = LocalDate.of(2026, 1, 2)
        val points = listOf(
            CanonicalRoutePoint("a1", baseTime, 116.000, 39.000, false, day),
            CanonicalRoutePoint("a2", baseTime + 60_000, 116.002, 39.000, false, day),
            CanonicalRoutePoint("b1", baseTime + 3_600_000, 116.004, 39.000, false, day2),
            CanonicalRoutePoint("b2", baseTime + 3_660_000, 116.006, 39.000, false, day2),
        )
        val index = RouteLodBuilder.build("t1", points)

        val result = RouteViewportQuery.query(index, keepAll, viewport = null)

        assertEquals(2, result.intervals.size)
        assertEquals(0, result.intervals[0].startIndex)
        assertEquals(1, result.intervals[0].endIndexInclusive)
        assertEquals(2, result.intervals[1].startIndex)
        assertEquals(3, result.intervals[1].endIndexInclusive)
    }

    @Test
    fun `selected day restricts query to that part`() {
        val day2 = LocalDate.of(2026, 1, 2)
        val points = listOf(
            CanonicalRoutePoint("a1", baseTime, 116.000, 39.000, false, day),
            CanonicalRoutePoint("a2", baseTime + 60_000, 116.002, 39.000, false, day),
            CanonicalRoutePoint("b1", baseTime + 3_600_000, 116.004, 39.000, false, day2),
            CanonicalRoutePoint("b2", baseTime + 3_660_000, 116.006, 39.000, false, day2),
        )
        val index = RouteLodBuilder.build("t1", points)

        val result = RouteViewportQuery.query(index, keepAll, viewport = null, selectedDayIndex = 1)

        assertEquals(1, result.intervals.size)
        assertEquals(listOf(2, 3), result.intervals.single().let { (it.startIndex..it.endIndexInclusive).toList() })
    }

    @Test
    fun `viewport away from track returns empty`() {
        val index = uShapeIndex()
        val result = RouteViewportQuery.query(
            index, keepAll, GeoBbox(118.0, 41.0, 118.1, 41.1))
        assertTrue(result.intervals.isEmpty())
    }
}
