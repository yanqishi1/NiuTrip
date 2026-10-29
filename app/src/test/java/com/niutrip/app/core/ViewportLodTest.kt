package com.niutrip.app.core

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportLodTest {
    private fun point(id: String, day: Int, minute: Int, lon: Double = 0.0) = PointLite(
        id = id,
        time = LocalDateTime.of(2026, 9, day, 10, minute),
        lon = lon,
        lat = 0.0,
        isCheckin = false,
    )

    private val box = GeoBbox(minLng = 100.0, minLat = 30.0, maxLng = 101.0, maxLat = 31.0)

    @Test fun `padded expands every side by ratio of the viewport extent`() {
        val padded = box.padded(0.5)
        assertEquals(99.5, padded.minLng, 1e-9)
        assertEquals(29.5, padded.minLat, 1e-9)
        assertEquals(101.5, padded.maxLng, 1e-9)
        assertEquals(31.5, padded.maxLat, 1e-9)
    }

    @Test fun `covers requires containment on all four edges`() {
        assertTrue(box.covers(box))
        assertTrue(box.covers(GeoBbox(100.2, 30.2, 100.8, 30.8)))
        assertFalse(box.covers(GeoBbox(99.9, 30.0, 100.8, 30.8)))
        assertFalse(box.covers(GeoBbox(100.2, 30.0, 101.1, 30.8)))
        assertFalse(box.covers(GeoBbox(100.2, 29.9, 100.8, 30.8)))
        assertFalse(box.covers(GeoBbox(100.2, 30.2, 100.8, 31.1)))
    }

    @Test fun `segments merge by day dedupe by id and sort by time`() {
        val cache = DetailSegmentCache()
        cache.add(box, listOf(point("b", 1, 1), point("a", 1, 0), point("d", 2, 0, lon = 5.0)))
        cache.add(GeoBbox(101.0, 30.0, 102.0, 31.0),
                  listOf(point("d", 2, 0, lon = 9.0), point("c", 1, 2)))

        val days = cache.mergedDays()

        assertEquals(listOf(1, 2), days.keys.map { it.dayOfMonth })
        assertEquals(
            listOf("a", "b", "c"),
            days[LocalDateTime.of(2026, 9, 1, 10, 0).toLocalDate()]!!.map(PointLite::id))
        // 同 id 出现在相邻两个片段（边界重叠）：后到片段的版本胜出
        val dayTwo = days[LocalDateTime.of(2026, 9, 2, 10, 0).toLocalDate()]!!.single()
        assertEquals("d", dayTwo.id)
        assertEquals(9.0, dayTwo.lon, 1e-9)
    }

    @Test fun `equal timestamps break ties by id`() {
        val cache = DetailSegmentCache()
        cache.add(box, listOf(point("zz", 1, 5), point("aa", 1, 5), point("mm", 1, 5)))
        val day = cache.mergedDays().values.single()
        assertEquals(listOf("aa", "mm", "zz"), day.map(PointLite::id))
    }

    @Test fun `cover check hits when any segment contains the wanted box`() {
        val cache = DetailSegmentCache()
        assertFalse(cache.covers(box))
        cache.add(box, listOf(point("a", 1, 0)))
        assertTrue(cache.covers(GeoBbox(100.1, 30.1, 100.9, 30.9)))
        assertFalse(cache.covers(GeoBbox(0.0, 0.0, 0.1, 0.1)))
    }

    @Test fun `overflow evicts the oldest segment first`() {
        val cache = DetailSegmentCache(limit = 2)
        cache.add(GeoBbox(0.0, 0.0, 1.0, 1.0), listOf(point("old", 1, 0)))
        cache.add(GeoBbox(1.0, 1.0, 2.0, 2.0), listOf(point("mid", 1, 1)))
        cache.add(GeoBbox(2.0, 2.0, 3.0, 3.0), listOf(point("new", 1, 2)))

        assertFalse(cache.covers(GeoBbox(0.1, 0.1, 0.9, 0.9)))   // 最旧片段已丢
        assertEquals(listOf("mid", "new"),
            cache.mergedDays().values.single().map(PointLite::id))
    }
}
