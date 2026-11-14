package com.niutrip.app.ui.share

import com.niutrip.app.core.DayGrouper
import com.niutrip.app.core.PointLite
import com.niutrip.app.data.remote.TrackDto
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosterStatsTest {
    private fun point(id: String, time: String, checkin: Boolean = false) =
        PointLite(id, LocalDateTime.parse(time), 100.0, 30.0, checkin)

    private fun track(start: String? = null, end: String? = null) = TrackDto(
        track_id = "t1", track_name = "川西环线", track_record_mode = "AUTO",
        track_status = "FINISHED", track_start_time = start, track_end_time = end)

    @Test fun `same day shows single date and clock duration`() {
        val stats = PosterStats.from(
            track("2026-09-11T08:00:00", "2026-09-11T18:30:00"),
            DayGrouper.group(listOf(point("p1", "2026-09-11T08:00:00"), point("p2", "2026-09-11T18:30:00"))))
        assertEquals("2026-09-11", stats.dateText)
        assertEquals("10:30:00", stats.durationText)
    }

    @Test fun `cross day duration keeps days instead of wrapping at 24 hours`() {
        val stats = PosterStats.from(
            track("2026-09-10T09:00:00", "2026-09-12T12:20:00"),
            DayGrouper.group(listOf(point("p1", "2026-09-10T09:00:00"), point("p2", "2026-09-12T12:20:00"))))
        assertEquals("2026-09-10 ~ 2026-09-12", stats.dateText)
        assertEquals("2天 03:20", stats.durationText)
    }

    @Test fun `exactly 24 hours formats as one day`() {
        val stats = PosterStats.from(
            track("2026-09-10T09:00:00", "2026-09-11T09:00:00"),
            DayGrouper.group(listOf(point("p1", "2026-09-10T09:00:00"), point("p2", "2026-09-11T09:00:00"))))
        assertEquals("1天 00:00", stats.durationText)
    }

    @Test fun `end before start hides duration instead of guessing`() {
        val stats = PosterStats.from(
            track("2026-09-11T18:00:00", "2026-09-11T08:00:00"),
            DayGrouper.group(listOf(point("p1", "2026-09-11T18:00:00"), point("p2", "2026-09-11T08:00:00"))))
        assertNull(stats.durationText)
    }

    @Test fun `missing track times fall back to first and last valid point times`() {
        val stats = PosterStats.from(
            track(),
            DayGrouper.group(listOf(point("p1", "2026-09-11T09:30:00"), point("p2", "2026-09-11T11:00:00"))))
        assertEquals("2026-09-11", stats.dateText)
        assertEquals("01:30:00", stats.durationText)
    }

    @Test fun `stats count merged checkins and distinct record days`() {
        val stats = PosterStats.from(
            track("2026-09-11T08:00:00", "2026-09-13T18:00:00"),
            DayGrouper.group(listOf(
                point("p1", "2026-09-11T08:00:00", checkin = true),
                point("p2", "2026-09-12T10:00:00"),
                point("p3", "2026-09-12T12:00:00", checkin = true),
                point("p4", "2026-09-13T18:00:00"))))
        assertEquals("川西环线", stats.name)
        assertEquals(3, stats.recordDays)
        assertEquals(2, stats.checkinCount)
    }
}
