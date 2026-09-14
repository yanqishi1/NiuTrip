package com.niutrip.app.core

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteGeometryTest {
    private fun point(id: String, minute: Int, lon: Double) = PointLite(
        id = id,
        time = LocalDateTime.of(2026, 9, 14, 10, minute),
        lon = lon,
        lat = 0.0,
        isCheckin = false,
    )

    @Test fun `total distance follows chronological point order`() {
        val points = listOf(
            point("last", 2, .002),
            point("first", 0, 0.0),
            point("middle", 1, .001),
        )

        assertEquals(222.4, RouteGeometry.totalDistanceMeters(points), 1.0)
    }

    @Test fun `fewer than two points has no distance`() {
        assertEquals(0.0, RouteGeometry.totalDistanceMeters(emptyList()), 0.0)
        assertEquals(0.0, RouteGeometry.totalDistanceMeters(listOf(point("only", 0, 0.0))), 0.0)
    }
}
