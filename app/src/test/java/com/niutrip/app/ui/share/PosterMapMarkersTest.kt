package com.niutrip.app.ui.share

import com.niutrip.app.core.PointLite
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class PosterMapMarkersTest {
    private fun candidate(id: String, x: Float, y: Float, images: Boolean = false, name: String? = null) =
        PosterMarkerCandidate(PointLite(id, LocalDateTime.of(2026, 11, 14, 9, 0), 114.0, 30.0, true,
            name = name, images = if (images) listOf("photo.jpg") else emptyList()), x, y, id.toInt())

    @Test fun `edited endpoint names win over district and blanks fall back`() {
        val point = candidate("1", 100f, 100f, name = "  我家的起点  ").point
        assertEquals("我家的起点", PosterMapMarkers.endpointName(point, "武昌区"))
        assertEquals("武昌区", PosterMapMarkers.endpointName(point.copy(name = " "), "武昌区"))
        assertNull(PosterMapMarkers.endpointName(point.copy(name = null), null))
    }

    @Test fun `dense cluster keeps authored photo point and leaves remote stop visible`() {
        val points = listOf(candidate("1", 100f, 150f), candidate("2", 102f, 151f, images = true),
            candidate("3", 260f, 330f, name = "山顶"))
        val chosen = PosterMapMarkers.select(points, emptyList(), emptyList(), 360f, 500f)
        assertEquals(listOf("2", "3"), chosen.map { it.point.id })
    }

    @Test fun `endpoints offscreen markers and crossing route remain unobscured`() {
        val points = listOf(candidate("1", 80f, 120f), candidate("2", 200f, 250f),
            candidate("3", 3f, 90f), candidate("4", 280f, 380f))
        val chosen = PosterMapMarkers.select(points,
            listOf(PosterMarkerBox(50f, 60f, 120f, 140f)),
            listOf(200f to 216f), 360f, 500f)
        assertEquals(listOf("4"), chosen.map { it.point.id })
    }

    @Test fun `selection adapts to viewport and never dumps all dense checkins`() {
        val points = (1..100).map { candidate(it.toString(), 40f + it % 5 * 63, 80f + it / 5 * 50) }
        val chosen = PosterMapMarkers.select(points, emptyList(), emptyList(), 360f, 500f)
        assertTrue(chosen.size in 2..10)
        for (a in chosen.indices) for (b in 0 until a)
            assertTrue(kotlin.math.hypot(chosen[a].x - chosen[b].x, chosen[a].y - chosen[b].y) >= 58f)
    }

    @Test fun `sparse crossing segment is protected even when its endpoints miss marker box`() {
        val chosen = PosterMapMarkers.select(listOf(candidate("1", 200f, 250f)), emptyList(),
            listOf(160f to 216f, 240f to 216f), 360f, 500f)
        assertTrue(chosen.isEmpty())
    }

    @Test fun `names prefer authored name then description without inventing an index`() {
        val point = candidate("1", 100f, 100f, name = "  峨眉山  ").point
        assertEquals("峨眉山", PosterMapMarkers.checkinName(point))
        assertEquals("江边日落", PosterMapMarkers.checkinName(point.copy(name = " ", desc = "\n 江边日落\n第二行")))
        assertEquals("未命名打卡点", PosterMapMarkers.checkinName(point.copy(name = null, desc = null)))
    }

    @Test fun `photo count and description length change cluster priority`() {
        val low = candidate("1", 150f, 200f, images = true, name = "一张图")
        val high = candidate("2", 152f, 201f, name = "旅行记录").let {
            it.copy(point = it.point.copy(images = (1..6).map { index -> "$index.jpg" }, desc = "沿途景色".repeat(30)))
        }
        assertTrue(PosterMapMarkers.contentWeight(high.point) > PosterMapMarkers.contentWeight(low.point))
        val named = PosterMapMarkers.layoutNames(listOf(low, high), emptyList(), emptyList(), 360f, 500f) { 72f }
        assertEquals(listOf("2"), named.map { it.candidate.point.id })
    }

    @Test fun `label changes direction to avoid line and uses actual measured bounds`() {
        val point = candidate("1", 180f, 250f, name = "很长的自定义打卡点名称")
        val line = listOf(80f to 225f, 280f to 225f)
        val chosen = PosterMapMarkers.layoutNames(listOf(point), emptyList(), line, 360f, 500f) { 132f }.single()
        assertEquals(132f, chosen.box.width)
        assertTrue(line.zipWithNext().none { chosen.box.expanded(3f).intersectsSegment(it.first, it.second) })
        assertFalse(chosen.box.contains(point.x, point.y))
    }

    @Test fun `sparse viewport can show more named stops without overlaps`() {
        val points = (1..20).map { candidate(it.toString(), 120f + it % 5 * 210, 120f + it / 5 * 160, name = "景点$it") }
        val chosen = PosterMapMarkers.layoutNames(points, emptyList(), emptyList(), 1200f, 1000f) { 72f }
        assertTrue(chosen.size > 12)
        for (a in chosen.indices) for (b in 0 until a) assertFalse(chosen[a].box.overlaps(chosen[b].box))
    }

    @Test fun `named labels avoid endpoint region and skip blocked stops`() {
        val points = listOf(candidate("1", 100f, 100f), candidate("2", 300f, 300f))
        val reserved = listOf(PosterMarkerBox(20f, 20f, 200f, 200f))
        val chosen = PosterMapMarkers.layoutNames(points, reserved, emptyList(), 400f, 500f) { 72f }
        assertEquals(listOf("2"), chosen.map { it.candidate.point.id })
        assertTrue(chosen.none { label -> reserved.any { it.overlaps(label.box) } })
    }

    @Test fun `combined endpoint label avoids outgoing line without moving anchors`() {
        val start = 180f to 200f
        val route = listOf(start, 180f to 80f)
        val box = PosterMapMarkers.endpointLabelBox(start, start, 100f, 54f,
            PosterMarkerBox(8f, 8f, 352f, 470f), route)
        assertTrue(box.top > start.second)
        assertFalse(box.expanded(3f).intersectsSegment(route[0], route[1]))
    }

    @Test fun `explicit highlight outranks nearby rich content and uses share alias`() {
        val wanted = candidate("1", 180f, 240f, name = "景区内具体观景点")
        val rich = candidate("2", 182f, 241f, images = true, name = "附近餐馆")
        val chosen = PosterMapMarkers.layoutNames(listOf(rich, wanted), emptyList(), emptyList(), 360f, 500f,
            preferredNames = mapOf("1" to "希望展示的景区")) { 100f }
        assertEquals(listOf("1"), chosen.map { it.candidate.point.id })
        assertEquals("希望展示的景区", chosen.single().name)
        assertEquals("景区内具体观景点", wanted.point.name)
    }

    @Test fun `dense route cluster can use a longer leader while protecting line`() {
        val point = candidate("1", 180f, 250f, name = "重点景点")
        val route = (0..10).flatMap { row ->
            val y = 210f + row * 8
            if (row % 2 == 0) listOf(110f to y, 250f to y) else listOf(250f to y, 110f to y)
        }
        val marker = PosterMapMarkers.layoutNames(listOf(point), emptyList(), route, 360f, 500f,
            preferredNames = mapOf("1" to "重点景点")) { 100f }.single()
        assertTrue(route.zipWithNext().none { marker.box.expanded(3f).intersectsSegment(it.first, it.second) })
        assertTrue(marker.box.bottom < 210 || marker.box.top > 290 || marker.box.right < 110 || marker.box.left > 250)
    }
}
