package com.niutrip.app.ui.detail.map

import com.niutrip.app.core.DayGroup
import com.niutrip.app.core.PointLite
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class MarkerPresentationTest {
    private fun point(id: String, dateTime: String) = PointLite(
        id, LocalDateTime.parse(dateTime), 116.0, 39.0, false)

    @Test fun `zoom levels progressively reveal and enlarge markers`() {
        val far = markerPresentationForZoom(9.9f)
        val overview = markerPresentationForZoom(10f)
        val medium = markerPresentationForZoom(12f)
        val near = markerPresentationForZoom(14f)
        val street = markerPresentationForZoom(16f)

        assertEquals(CheckinMarkerStyle.COMPACT, far.checkinStyle)
        assertEquals(EndpointMarkerStyle.COMPACT, far.endpointStyle)
        assertEquals(false, far.showAutoPoints)
        assertEquals(CheckinMarkerStyle.COMPACT, overview.checkinStyle)
        assertEquals(EndpointMarkerStyle.COMPACT, overview.endpointStyle)
        assertEquals(false, medium.showAutoPoints)
        assertEquals(CheckinMarkerStyle.CARD, medium.checkinStyle)
        assertEquals(.7f, medium.checkinScale)
        assertEquals(true, near.showAutoPoints)
        assertEquals(.85f, near.checkinScale)
        assertEquals(1f, street.checkinScale)
    }

    @Test fun `decluttering respects existing endpoints and accepted checkins`() {
        val visible = nonOverlappingMarkerIndices(
            points = listOf(
                MarkerScreenPoint(0, 0),
                MarkerScreenPoint(20, 20),
                MarkerScreenPoint(200, 200),
                MarkerScreenPoint(20, 100),
            ),
            reserved = listOf(MarkerScreenPoint(190, 190)),
            minHorizontalPx = 50f,
            minVerticalPx = 50f,
        )

        assertEquals(listOf(0, 3), visible)
    }

    @Test fun `full route connects the end of one day to the start of the next`() {
        val days = listOf(
            DayGroup(LocalDate.parse("2026-09-14"), listOf(
                point("d1-start", "2026-09-14T22:00:00"),
                point("d1-end", "2026-09-14T23:50:00"),
            )),
            DayGroup(LocalDate.parse("2026-09-15"), listOf(
                point("d2-start", "2026-09-15T08:00:00"),
                point("d2-end", "2026-09-15T09:00:00"),
            )),
        )

        val paths = visibleRoutePaths(days, -1)

        assertEquals(listOf("d1-start", "d1-end"), paths[0].points.map { it.id })
        assertEquals(listOf("d1-end", "d2-start", "d2-end"), paths[1].points.map { it.id })
        assertEquals(1, paths[1].dayIndex)
    }

    @Test fun `single day route does not include the previous day bridge`() {
        val days = listOf(
            DayGroup(LocalDate.parse("2026-09-14"), listOf(point("d1", "2026-09-14T23:50:00"))),
            DayGroup(LocalDate.parse("2026-09-15"), listOf(point("d2", "2026-09-15T08:00:00"))),
        )

        assertEquals(listOf("d2"), visibleRoutePaths(days, 1).single().points.map { it.id })
    }

    @Test fun `route sampling retains endpoints and respects limit`() {
        val points = (0..999).map { point("p$it", "2026-09-14T10:00:00") }
        val sampled = sampledRoutePoints(points, 80)
        assertEquals(80, sampled.size)
        assertEquals("p0", sampled.first().id)
        assertEquals("p999", sampled.last().id)
    }

    @Test fun `recent selection keeps only latest fifty points across day boundaries`() {
        val firstDay = (0 until 30).map { index ->
            point("old-$index", "2026-09-14T10:${index.toString().padStart(2, '0')}:00")
        }
        val secondDay = (0 until 30).map { index ->
            point("new-$index", "2026-09-15T10:${index.toString().padStart(2, '0')}:00")
        }

        val visible = visibleDaysForSelection(
            listOf(
                DayGroup(LocalDate.parse("2026-09-14"), firstDay),
                DayGroup(LocalDate.parse("2026-09-15"), secondDay),
            ),
            RECENT_SELECTION,
        )

        assertEquals(50, visible.sumOf { it.points.size })
        assertEquals(listOf(20, 30), visible.map { it.points.size })
        assertEquals("old-10", visible.first().points.first().id)
        assertEquals("new-29", visible.last().points.last().id)
    }

    @Test fun `recent camera includes current location but full route does not`() {
        val route = listOf(point("route", "2026-09-14T10:00:00"))
        val current = point("current", "2026-09-15T10:00:00")

        assertEquals(listOf("route", "current"),
            cameraPointsForSelection(route, RECENT_SELECTION, current).map { it.id })
        assertEquals(listOf("route"),
            cameraPointsForSelection(route, ALL_DAYS_SELECTION, current).map { it.id })
    }

    @Test fun `route point budget decreases as geographic span grows and stays finite`() {
        fun routeWithLongitudeSpan(span: Double) = listOf(
            point("start", "2026-09-14T10:00:00").copy(lon = 0.0, lat = 0.0),
            point("middle", "2026-09-14T10:01:00").copy(lon = span / 2, lat = 0.0),
            point("end", "2026-09-14T10:02:00").copy(lon = span, lat = 0.0),
        )

        val budgets = listOf(0.1, 0.5, 2.0, 5.0, 15.0)
            .map { routePointBudget(routeWithLongitudeSpan(it)) }

        assertEquals(listOf(2_000, 1_400, 1_000, 700, 500), budgets)
        assertEquals(true, budgets.all { it in 1..2_000 })
        assertEquals(true, budgets.zipWithNext().all { (near, far) -> far <= near })
    }

    @Test fun `route sampling keeps a major turn`() {
        val points = (0 until 101).map { index ->
            point("p$index", "2026-09-14T10:00:00").copy(
                lon = index.toDouble(),
                lat = if (index == 50) 20.0 else 0.0,
            )
        }

        val sampled = sampledRoutePoints(points, 10)

        assertEquals(true, sampled.any { it.id == "p50" })
    }

    @Test fun `auto markers are spaced and capped`() {
        val points = (0..1000).map { MarkerScreenPoint(it * 2, 100) }
        val selected = nonOverlappingAutoMarkerIndices(points, 16f, maxMarkers = 40)
        assertEquals(40, selected.size)
        assertEquals(0, selected.first())
        assertEquals(true, selected.zipWithNext().all { (a, b) -> points[b].x - points[a].x >= 16 })
    }
}
