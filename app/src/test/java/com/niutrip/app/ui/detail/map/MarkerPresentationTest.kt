package com.niutrip.app.ui.detail.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkerPresentationTest {
    @Test fun `zoom levels progressively reveal and enlarge markers`() {
        val far = markerPresentationForZoom(9.9f)
        val overview = markerPresentationForZoom(10f)
        val medium = markerPresentationForZoom(12f)
        val near = markerPresentationForZoom(14f)
        val street = markerPresentationForZoom(16f)

        assertEquals(CheckinMarkerStyle.HIDDEN, far.checkinStyle)
        assertEquals(EndpointMarkerStyle.COMPACT, far.endpointStyle)
        assertEquals(CheckinMarkerStyle.COMPACT, overview.checkinStyle)
        assertEquals(EndpointMarkerStyle.COMPACT, overview.endpointStyle)
        assertEquals(CheckinMarkerStyle.CARD, medium.checkinStyle)
        assertEquals(.7f, medium.checkinScale)
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
}
