package com.niutrip.app.core.route

import com.niutrip.app.core.GeoBbox
import com.niutrip.app.core.toEpochMillis
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRenderPolicyTest {
    private val baseTime = LocalDateTime.of(2026, 1, 1, 8, 0).toEpochMillis()
    private val day = LocalDate.of(2026, 1, 1)

    private fun p(id: String, minute: Long, lon: Double, lat: Double) =
        CanonicalRoutePoint(id, baseTime + minute * 60_000, lon, lat, false, day)

    @Test
    fun `band is the largest band not exceeding zoom`() {
        assertEquals(8, RouteRenderPolicy.bandFor(7f))
        assertEquals(8, RouteRenderPolicy.bandFor(8f))
        assertEquals(8, RouteRenderPolicy.bandFor(8.9f))
        assertEquals(8, RouteRenderPolicy.bandFor(9.9f))
        assertEquals(10, RouteRenderPolicy.bandFor(10f))
        assertEquals(10, RouteRenderPolicy.bandFor(11f))
        assertEquals(12, RouteRenderPolicy.bandFor(13.9f))
        assertEquals(14, RouteRenderPolicy.bandFor(14f))
        assertEquals(18, RouteRenderPolicy.bandFor(19.9f))
        assertEquals(20, RouteRenderPolicy.bandFor(20f))
        assertEquals(20, RouteRenderPolicy.bandFor(21f))
    }

    @Test
    fun `tolerance follows web mercator meters per pixel formula`() {
        // metersPerPixel = 156543.03392 * cos(lat) / 2^band; tolerance = mpp * 0.75
        assertEquals(156_543.03392 / 256 * 0.75, RouteRenderPolicy.toleranceMeters(8, 0.0), 1e-6)
        assertEquals(156_543.03392 / 256 * 0.75 * 0.5, RouteRenderPolicy.toleranceMeters(8, 60.0), 1e-6)
        assertTrue(RouteRenderPolicy.toleranceMeters(10, 39.0) < RouteRenderPolicy.toleranceMeters(8, 39.0))
        assertTrue(RouteRenderPolicy.toleranceMeters(8, 39.0) > 300 && RouteRenderPolicy.toleranceMeters(8, 39.0) < 400)
    }

    @Test
    fun `viewport cell quantizes tiny moves and separates big moves`() {
        val base = GeoBbox(116.000, 39.000, 116.020, 39.010)
        val tinyMove = GeoBbox(116.001, 39.000, 116.021, 39.010)
        val bigMove = GeoBbox(116.100, 39.000, 116.120, 39.010)
        assertEquals(RouteRenderPolicy.viewportCell(base), RouteRenderPolicy.viewportCell(tinyMove))
        assertNotEquals(RouteRenderPolicy.viewportCell(base), RouteRenderPolicy.viewportCell(bigMove))
    }

    @Test
    fun `render key carries generation band day and viewport cell`() {
        val full = RouteRenderPolicy.renderKey(RouteRenderQuery(7, 13.7f, null, null))
        assertEquals(RouteRenderKey(generation = 7, lodBand = 12, selectedDay = -1), full)

        val boxed = RouteRenderPolicy.renderKey(
            RouteRenderQuery(7, 15f, GeoBbox(116.0, 39.0, 116.02, 39.01), 0))
        assertEquals(14, boxed.lodBand)
        assertEquals(0, boxed.selectedDay)
        assertEquals(RouteRenderPolicy.viewportCell(GeoBbox(116.0, 39.0, 116.02, 39.01)).first, boxed.viewportCellX)
        assertEquals(RouteRenderPolicy.viewportCell(GeoBbox(116.0, 39.0, 116.02, 39.01)).second, boxed.viewportCellY)
        assertNull(full.viewportCellX)
    }

    @Test
    fun `low zoom ignores viewport and keeps full route`() {
        val index = RouteLodBuilder.build("t1", listOf(
            p("a", 0, 116.0, 39.0),
            p("b", 1, 116.1, 39.1),
            p("c", 2, 116.2, 39.0),
        ))
        val away = GeoBbox(120.0, 42.0, 120.1, 42.1)

        val model = RouteRenderPolicy.buildRenderModel(
            index, RouteRenderQuery(1, 12f, away, null), useLod = false)

        assertEquals(listOf("a", "b", "c"), model.polylines.single().pointIndices.map(index::idAt))
        assertNull(model.key.viewportCellX)
    }

    @Test
    fun `full route model splits long polylines with one point overlap`() {
        // 600 点锯齿（偏移 2m > band20 容差 → 全保留），单日 → 单区间 → 512 + 89 两段
        val points = (0 until 600).map { i ->
            p("w%03d".format(i), i.toLong(), 116.0 + i * 0.00002, 39.0 + if (i % 2 == 0) 0.00002 else -0.00002)
        }
        val index = RouteLodBuilder.build("t1", points)
        val model = RouteRenderPolicy.buildRenderModel(
            index, RouteRenderQuery(generation = 1, zoom = 20f, viewport = null, selectedDayIndex = null))

        assertEquals(listOf(512, 89), model.polylines.map { it.pointIndices.size })
        // 相邻段重叠一个端点
        assertEquals(model.polylines[0].pointIndices.last(), model.polylines[1].pointIndices.first())
        assertEquals(600, model.pointCount)
    }

    @Test
    fun `full route model connects days with previous day endpoint`() {
        val day2 = LocalDate.of(2026, 1, 2)
        val points = listOf(
            CanonicalRoutePoint("a1", baseTime, 116.000, 39.000, false, day),
            CanonicalRoutePoint("a2", baseTime + 60_000, 116.002, 39.000, false, day),
            CanonicalRoutePoint("b1", baseTime + 3_600_000, 116.004, 39.001, false, day2),
            CanonicalRoutePoint("b2", baseTime + 3_660_000, 116.006, 39.001, false, day2),
        )
        val index = RouteLodBuilder.build("t1", points)
        val model = RouteRenderPolicy.buildRenderModel(
            index, RouteRenderQuery(generation = 1, zoom = 12f, viewport = null, selectedDayIndex = null))

        assertEquals(2, model.polylines.size)
        assertEquals(listOf("a1", "a2"), model.polylines[0].pointIndices.map(index::idAt))
        // 第二日首段显式补上一日末点（方案 §3.2）
        assertEquals(listOf("a2", "b1", "b2"), model.polylines[1].pointIndices.map(index::idAt))
        assertEquals(0, model.polylines[0].dayIndex)
        assertEquals(1, model.polylines[1].dayIndex)
    }

    @Test
    fun `lod disabled falls back to full point drawing`() {
        val points = (0 until 20).map { i -> p("f%02d".format(i), i.toLong(), 116.0 + i * 0.0001, 39.0) }
        val index = RouteLodBuilder.build("t1", points)
        val model = RouteRenderPolicy.buildRenderModel(
            index, RouteRenderQuery(1, 8f, null, null), useLod = false)
        // 共线点在 LOD 下会全丢；关闭开关后完整点直接绘制
        assertEquals(20, model.pointCount)
        assertEquals(20, model.polylines.single().pointIndices.size)
    }
}
