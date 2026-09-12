package com.niutrip.app.ui.detail.map

import com.niutrip.app.core.PointLite
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleThumbnailRequestsTest {
    private val viewport = MapViewport(south = 22.0, west = 112.0, north = 24.0, east = 114.0)

    @Test fun `only visible checkin images are requested`() {
        val requests = visibleThumbnailRequests(
            points = listOf(
                point("visible", lat = 23.0, lon = 113.0, checkin = true, images = listOf("/visible.jpg")),
                point("outside", lat = 30.0, lon = 120.0, checkin = true, images = listOf("/outside.jpg")),
                point("route", lat = 23.0, lon = 113.0, checkin = false, images = listOf("/route.jpg")),
                point("empty", lat = 23.0, lon = 113.0, checkin = true),
            ),
            viewport = viewport,
        )

        assertEquals(listOf(ThumbnailRequest("visible", "/visible.jpg")), requests)
    }

    @Test fun `nothing is requested before map viewport is ready`() {
        val requests = visibleThumbnailRequests(
            listOf(point("visible", lat = 23.0, lon = 113.0, checkin = true, images = listOf("/visible.jpg"))),
            viewport = null,
        )

        assertEquals(emptyList<ThumbnailRequest>(), requests)
    }

    @Test fun `viewport supports crossing the date line`() {
        val dateLineViewport = MapViewport(south = -10.0, west = 170.0, north = 10.0, east = -170.0)
        val requests = visibleThumbnailRequests(
            listOf(
                point("east", lat = 0.0, lon = 175.0, checkin = true, images = listOf("/east.jpg")),
                point("west", lat = 0.0, lon = -175.0, checkin = true, images = listOf("/west.jpg")),
                point("middle", lat = 0.0, lon = 0.0, checkin = true, images = listOf("/middle.jpg")),
            ),
            viewport = dateLineViewport,
        )

        assertEquals(listOf("east", "west"), requests.map(ThumbnailRequest::pointId))
    }

    private fun point(
        id: String,
        lat: Double,
        lon: Double,
        checkin: Boolean,
        images: List<String> = emptyList(),
    ) = PointLite(
        id = id,
        time = LocalDateTime.of(2026, 9, 12, 12, 0),
        lon = lon,
        lat = lat,
        isCheckin = checkin,
        images = images,
    )
}
