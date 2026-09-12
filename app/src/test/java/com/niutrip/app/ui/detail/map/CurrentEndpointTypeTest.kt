package com.niutrip.app.ui.detail.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentEndpointTypeTest {
    @Test fun `unfinished route only exposes its start`() {
        assertEquals(
            listOf(EndpointMarkerType.START),
            routeEndpointTypes(showRouteEnd = false, samePoint = true),
        )
        assertEquals(
            listOf(EndpointMarkerType.START),
            routeEndpointTypes(showRouteEnd = false, samePoint = false),
        )
    }

    @Test fun `finished route exposes its real endpoint shape`() {
        assertEquals(
            listOf(EndpointMarkerType.ROUND_TRIP),
            routeEndpointTypes(showRouteEnd = true, samePoint = true),
        )
        assertEquals(
            listOf(EndpointMarkerType.START, EndpointMarkerType.END),
            routeEndpointTypes(showRouteEnd = true, samePoint = false),
        )
    }

    @Test fun `single endpoint keeps its own label`() {
        assertEquals(
            EndpointMarkerType.START,
            combinedEndpointType(setOf(EndpointMarkerType.START)),
        )
        assertEquals(
            EndpointMarkerType.END,
            combinedEndpointType(setOf(EndpointMarkerType.END)),
        )
    }

    @Test fun `both endpoints collapse into one round trip label`() {
        assertEquals(
            EndpointMarkerType.ROUND_TRIP,
            combinedEndpointType(setOf(EndpointMarkerType.START, EndpointMarkerType.END)),
        )
        assertEquals(
            EndpointMarkerType.ROUND_TRIP,
            combinedEndpointType(setOf(EndpointMarkerType.ROUND_TRIP)),
        )
    }

    @Test fun `no nearby endpoint has no label`() {
        assertNull(combinedEndpointType(emptySet()))
    }
}
