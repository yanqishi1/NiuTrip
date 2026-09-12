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

    @Test fun `route endpoints follow chronological order`() {
        val days = DayGrouper.group(listOf(point("end", "2026-09-06T12:00:00"), point("start", "2026-09-05T08:00:00")))
        val endpoints = RouteGeometry.endpoints(days)
        assertEquals("start", endpoints?.start?.id)
        assertEquals("end", endpoints?.end?.id)
    }

    @Test fun `route path keeps every visible point in chronological order`() {
        val base = LocalDateTime.parse("2026-09-05T08:00:00")
        val points = listOf(
            PointLite("end", base.plusMinutes(30), 116.00105, 39.00105, false),
            PointLite("a", base, 116.0, 39.0, false),
            PointLite("moved", base.plusMinutes(20), 116.001, 39.001, false),
            PointLite("nearby", base.plusMinutes(10), 116.00005, 39.00005, false),
        )
        assertEquals(listOf("a", "nearby", "moved", "end"), RouteGeometry.orderedPath(points).map { it.id })
    }
}
