package com.niutrip.app.ui.share

import com.niutrip.app.core.DayGrouper
import com.niutrip.app.core.PointLite
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterRouteGeometryTest {
    private fun p(id: String, time: String, lon: Double, lat: Double, checkin: Boolean = false) =
        PointLite(id, LocalDateTime.parse(time), lon, lat, checkin)

    private fun days(vararg points: PointLite) = DayGrouper.group(points.toList())

    @Test fun `single point route has protected span and centered marker`() {
        val model = PosterRouteModel.build(days(p("only", "2026-09-11T10:00:00", 100.0, 30.0)))
        assertTrue(model.aspect.isFinite())
        assertTrue(model.aspect > 0)
        val transform = model.fit(1000.0, 1000.0, 40.0)
        val mapped = transform.map(model.paths.single().single().x, model.paths.single().single().y)
        assertEquals(500.0, mapped.x, 0.5)
        assertEquals(500.0, mapped.y, 0.5)
    }

    @Test fun `duplicate points do not create degenerate geometry`() {
        val model = PosterRouteModel.build(days(
            p("a", "2026-09-11T10:00:00", 100.0, 30.0),
            p("b", "2026-09-11T10:01:00", 100.0, 30.0)))
        assertTrue(model.bounds.spanX.isFinite() && model.bounds.spanY.isFinite())
        assertTrue(model.aspect.isFinite() && model.aspect > 0)
    }

    @Test fun `antimeridian crossing unwraps longitudes instead of spanning the globe`() {
        val model = PosterRouteModel.build(days(
            p("a", "2026-09-11T10:00:00", 179.0, 30.0),
            p("b", "2026-09-11T11:00:00", 179.5, 30.0),
            p("c", "2026-09-11T12:00:00", -179.5, 30.0),
            p("d", "2026-09-11T13:00:00", -179.0, 30.0)))
        assertEquals(2.0, model.bounds.spanX, 1e-6)
        assertEquals(4, model.paths.single().size)
    }

    @Test fun `extreme latitude clamps into mercator range without NaN`() {
        val model = PosterRouteModel.build(days(
            p("a", "2026-09-11T10:00:00", 100.0, 89.9),
            p("b", "2026-09-11T11:00:00", 101.0, -89.9)))
        model.paths.single().forEach {
            assertTrue(it.x.isFinite()); assertTrue(it.y.isFinite())
        }
        assertTrue(model.aspect.isFinite())
    }

    @Test fun `north maps above south on the module`() {
        val model = PosterRouteModel.build(days(
            p("south", "2026-09-11T10:00:00", 100.0, 30.0),
            p("north", "2026-09-11T11:00:00", 100.0, 31.0)))
        val transform = model.fit(1000.0, 1000.0, 20.0)
        val south = model.paths.single().first(); val north = model.paths.single().last()
        assertTrue(transform.map(north.x, north.y).y < transform.map(south.x, south.y).y)
    }

    @Test fun `fit preserves route aspect inside the module`() {
        val model = PosterRouteModel.build(days(
            p("a", "2026-09-11T10:00:00", 100.0, 29.0),
            p("b", "2026-09-11T11:00:00", 104.0, 31.0)))
        val transform = model.fit(1000.0, 1000.0, 0.0)
        val mapped = model.paths.single().map { transform.map(it.x, it.y) }
        val xs = mapped.map { it.x }; val ys = mapped.map { it.y }
        val drawnAspect = (xs.max() - xs.min()) / (ys.max() - ys.min())
        assertEquals(model.aspect, drawnAspect, 1e-6)
        // 居中放置：映射后的包围盒中心即模块中心。
        assertEquals(500.0, (xs.min() + xs.max()) / 2, 0.5)
        assertEquals(500.0, (ys.min() + ys.max()) / 2, 0.5)
    }

    @Test fun `simplification drops collinear points but keeps anchors`() {
        val model = PosterRouteModel.build(DayGrouper.group(listOf(
            p("start", "2026-09-11T10:00:00", 0.0, 30.0),
            p("mid1", "2026-09-11T10:01:00", 1.0, 30.0),
            p("mark", "2026-09-11T10:02:00", 5.0, 30.0, checkin = true),
            p("mid2", "2026-09-11T10:03:00", 9.0, 30.0),
            p("end", "2026-09-11T10:04:00", 10.0, 30.0))))
        val simplified = model.simplified(0.4)
        assertEquals(listOf("start", "mark", "end"), simplified.paths.single().map { it.point.id })
        // 布局包围盒保持完整数据，不随简化抖动。
        assertEquals(model.bounds, simplified.bounds)
    }

    @Test fun `deviating point survives when threshold is small`() {
        val model = PosterRouteModel.build(days(
            p("a", "2026-09-11T10:00:00", 0.0, 30.0),
            p("off", "2026-09-11T10:01:00", 5.0, 30.1),
            p("b", "2026-09-11T10:02:00", 10.0, 30.0)))
        val simplified = model.simplified(0.001)
        assertEquals(3, simplified.paths.single().size)
    }

    @Test fun `day paths connect previous end into next day start`() {
        val model = PosterRouteModel.build(days(
            p("d1a", "2026-09-11T10:00:00", 0.0, 30.0),
            p("d1b", "2026-09-11T11:00:00", 5.0, 30.0),
            p("d2a", "2026-09-12T10:00:00", 6.0, 30.0),
            p("d2b", "2026-09-12T11:00:00", 10.0, 30.0)))
        assertEquals(2, model.paths.size)
        assertEquals("d1b", model.paths[1].first().point.id)
        assertEquals(3, model.paths[1].size)
    }

    /**
     * 回归：真实轨迹弯道被简化成直线（真机截图反馈）。
     * 根因：投影 x 轴为经度“度”、y 轴为 Mercator“弧度”，单位差约 57 倍，
     * RDP 阈值在 y 方向等效放大，弯道的纬度波动全部被删除。
     */
    @Test fun `real-scale wiggle survives vm-style simplification`() {
        val base = LocalDateTime.parse("2026-09-11T10:00:00")
        // 18km 量级的东西向轨迹（约 0.18°），带真实 GPS 量级的纬度摆动（±0.004°）。
        val points = (0..180).map { index ->
            val lon = index.toDouble() / 1000 // 0..0.18
            val lat = 30.0 + (if (index % 20 < 10) 0.004 else -0.004)
            PointLite("p$index", base.plusSeconds(index.toLong()), lon, lat, false)
        }
        val model = PosterRouteModel.build(days(*points.toTypedArray()))
        // 与 PosterEditorViewModel 相同的阈值公式（1.5px @1080px 模块宽度）。
        val epsilon = 1.5 * model.bounds.spanX / 1080.0
        val simplified = model.simplified(epsilon)
        val kept = simplified.paths.flatten().size
        assertTrue("弯道应保留足够节点，实际只剩 $kept 个", kept >= 8)
    }

    @Test fun `large point count simplifies without losing anchors`() {
        val base = LocalDateTime.parse("2026-09-11T10:00:00")
        val stamped = (0..2000).map { index ->
            PointLite("p$index", base.plusMinutes(index.toLong()), index.toDouble() / 100, 30.0, index == 1500) }
        val model = PosterRouteModel.build(days(*stamped.toTypedArray()))
        val simplified = model.simplified(0.01)
        val ids = simplified.paths.flatten().map { it.point.id }
        assertTrue(ids.size < stamped.size)
        assertEquals("p0", ids.first())
        assertEquals("p2000", ids.last())
        assertTrue("p1500" in ids)
        assertFalse("p700" in ids)
    }
}
