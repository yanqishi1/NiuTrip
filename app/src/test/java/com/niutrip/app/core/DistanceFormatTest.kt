package com.niutrip.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceFormatTest {
    @Test fun `formats meters and kilometers for track summaries`() {
        assertEquals("0 米", formatDistance(0.0))
        assertEquals("421 米", formatDistance(420.6))
        assertEquals("12.35 公里", formatDistance(12_345.0))
    }
}
