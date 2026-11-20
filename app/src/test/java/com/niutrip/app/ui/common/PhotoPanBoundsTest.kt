package com.niutrip.app.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoPanBoundsTest {
    @Test fun fitPhotoStaysCentered() {
        assertOffset(Offset.Zero, constrainPhotoPan(Offset(300f, -200f), IntSize(400, 800), 2f, 1f))
    }

    @Test fun landscapePhotoCannotBeDraggedIntoVerticalLetterbox() {
        assertOffset(Offset(200f, 0f), constrainPhotoPan(Offset(999f, -999f), IntSize(400, 800), 2f, 2f))
    }

    @Test fun portraitPhotoCanPanOnlyAlongEnlargedDimension() {
        assertOffset(Offset(0f, -200f), constrainPhotoPan(Offset(999f, -999f), IntSize(800, 400), .5f, 2f))
    }

    @Test fun largerZoomAllowsBothAxesWithoutMovingImageOffscreen() {
        assertOffset(Offset(-1000f, 200f), constrainPhotoPan(Offset(-9999f, 9999f), IntSize(400, 800), 2f, 6f))
    }

    @Test fun zeroViewportIsSafeBeforeLayout() {
        assertOffset(Offset.Zero, constrainPhotoPan(Offset(30f, 40f), IntSize.Zero, 1f, 3f))
    }

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, .001f)
        assertEquals(expected.y, actual.y, .001f)
    }
}
