package com.niutrip.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CoreLogicTest {
    private fun point(id: String, time: String) = PointLite(id, LocalDateTime.parse(time), 100.0, 30.0, false)

    @Test fun `groups points by Beijing calendar day in order`() {
        val result = DayGrouper.group(listOf(point("b", "2026-09-06T00:10:00"), point("a2", "2026-09-05T23:50:00"), point("a1", "2026-09-05T08:00:00")))
        assertEquals(2, result.size)
        assertEquals(listOf("a1", "a2"), result.first().points.map { it.id })
        assertEquals(LocalDate.of(2026, 9, 6), result.last().date)
    }

    @Test fun `finished track cannot restart or check in`() {
        assertFalse(TrackStateMachine.canStart(TrackStatus.FINISHED))
        assertFalse(TrackStateMachine.canFinish(TrackStatus.FINISHED))
        assertFalse(TrackStateMachine.canCheckin(TrackStatus.FINISHED))
    }

    @Test fun `day colors cycle`() {
        assertEquals(DAY_COLORS[0], dayColor(DAY_COLORS.size))
        assertEquals(DAY_COLORS.last(), dayColor(-1))
    }
}
