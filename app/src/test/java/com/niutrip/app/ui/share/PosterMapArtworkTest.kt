package com.niutrip.app.ui.share

import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterMapArtworkTest {
    @Test fun `hiding place names retains two distinct endpoint icons`() {
        val start = posterMarkerArtwork("起", null, 0xFF239DAB.toInt(), 2f)
        val end = posterMarkerArtwork("终", null, 0xFFE04435.toInt(), 2f)
        val named = posterMarkerArtwork("起", "用户自定义起点", 0xFF239DAB.toInt(), 2f)
        assertTrue(named.bitmap.width > start.bitmap.width)
        assertTrue(start.bitmap.getPixel(18, 40) != end.bitmap.getPixel(18, 40))
        assertEquals(start.anchorV, end.anchorV)
    }

    @Test fun `offset endpoints retain their true geographic anchor`() {
        val start = posterMarkerArtwork("起", "武昌区", 0xFF239DAB.toInt(), 2f, true, -18f)
        val end = posterMarkerArtwork("终", "武昌区", 0xFFE04435.toInt(), 2f, false, 18f)
        for (art in listOf(start, end)) {
            val x = (art.anchorU * art.bitmap.width).toInt()
            val y = (art.anchorV * art.bitmap.height).toInt()
            assertTrue(android.graphics.Color.alpha(art.bitmap.getPixel(x, y)) > 0)
        }
    }

    @Test fun `combined endpoints keep a single shared point without displaced pins`() {
        val art = posterEndpointPairArtwork("洪山区", "大环线终点", 0f, 0f, 0f, 0f, 2f)
        val x = (art.anchorU * art.bitmap.width).toInt()
        val y = (art.anchorV * art.bitmap.height).toInt()
        assertEquals(0xFF26312C.toInt(), art.bitmap.getPixel(x, y))
        // Both color badges remain visible, even though there is only one map anchor.
        val colors = IntArray(art.bitmap.width * art.bitmap.height)
        art.bitmap.getPixels(colors, 0, art.bitmap.width, 0, 0, art.bitmap.width, art.bitmap.height)
        assertTrue(colors.contains(0xFF239DAB.toInt()))
        assertTrue(colors.contains(0xFFE04435.toInt()))
        val hidden = posterEndpointPairArtwork(null, null, -8f, 0f, 8f, 0f, 2f)
        val centerX = hidden.anchorU * hidden.bitmap.width
        val centerY = hidden.anchorV * hidden.bitmap.height
        assertEquals(0xFF239DAB.toInt(), hidden.bitmap.getPixel((centerX - 16).toInt(), centerY.toInt()))
        assertEquals(0xFFE04435.toInt(), hidden.bitmap.getPixel((centerX + 16).toInt(), centerY.toInt()))
    }

    @Test fun `frame wait works from coroutine without Compose frame clock`() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val view = View(activity); activity.setContentView(view)
        var finished = false
        val job = CoroutineScope(Dispatchers.Unconfined).launch { view.awaitPosterFrame(); finished = true }
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertTrue(finished)
        assertTrue(job.isCompleted)
        activity.finish()
    }

    @Test fun `transparent endpoint artwork space does not suppress nearby preferred checkin`() {
        val art = posterEndpointPairArtwork("洪山区", "大环线终点", 0f, 0f, 0f, 0f, 1f)
        val reserved = art.collisionBoxes!!.map {
            PosterMarkerBox(it.left + 180, it.top + 250, it.right + 180, it.bottom + 250).expanded(8f)
        }
        val point = com.niutrip.app.core.PointLite("scenic", java.time.LocalDateTime.of(2026, 9, 11, 12, 0),
            110.0, 29.0, true, "重点景点")
        val marker = PosterMarkerCandidate(point, 230f, 252f, 1)
        val result = PosterMapMarkers.layoutNames(listOf(marker), reserved, emptyList(), 400f, 500f,
            preferredNames = mapOf(point.id to "重点景点")) { 80f }
        assertEquals(listOf(point.id), result.map { it.candidate.point.id })
        art.bitmap.recycle()
    }
}
