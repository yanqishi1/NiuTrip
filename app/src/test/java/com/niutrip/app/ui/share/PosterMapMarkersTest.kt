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
}
