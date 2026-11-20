package com.niutrip.app.ui.detail

import com.niutrip.app.core.PointLite
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelinePhotoGroupsTest {
    private fun entry(id: String, images: List<String>, name: String? = id) = CheckinTimelineEntry(
        PointLite(id = id, time = LocalDateTime.of(2026, 11, 20, 10, 0), lon = 114.0, lat = 30.0,
            isCheckin = true, name = name, images = images), 0)

    @Test fun skipsConsecutiveEmptyCheckinsAndKeepsTimelineAndPhotoOrder() {
        val groups = timelinePhotoGroups(listOf(
            entry("first", listOf("1a", "1b")), entry("empty1", emptyList()),
            entry("empty2", emptyList()), entry("last", listOf("3a")),
        ))
        assertEquals(listOf("first", "last"), groups.map { it.id })
        assertEquals(listOf("1a", "1b"), groups.first().images)
        assertEquals(listOf("3a"), groups.last().images)
    }

    @Test fun unnamedPhotoCheckinRemainsBrowsable() {
        val groups = timelinePhotoGroups(listOf(entry("a", listOf("photo"), "  ")))
        assertEquals("旅途打卡", groups.single().title)
        assertEquals("a", groups.single().id)
    }

    @Test fun emptyTimelineOrAllEmptyCheckinsHaveNoPhotoPages() {
        assertTrue(timelinePhotoGroups(emptyList()).isEmpty())
        assertTrue(timelinePhotoGroups(listOf(entry("a", emptyList()))).isEmpty())
    }
}
