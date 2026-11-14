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
}
