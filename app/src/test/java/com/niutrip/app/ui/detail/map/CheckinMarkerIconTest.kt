package com.niutrip.app.ui.detail.map

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CheckinMarkerIconTest {
    @Test fun `checkin marker renders thumbnail above its title`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val thumbnail = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40, 100, 220))
        }
        val marker = createCheckinMarkerBitmap(context, "广州塔", thumbnail)
        val density = context.resources.displayMetrics.density
        val previewCenterX = marker.width / 2
        val previewCenterY = ((3 + 6 + 28) * density).toInt()

        assertTrue(marker.height > marker.width)
        assertEquals(Color.rgb(40, 100, 220), marker.getPixel(previewCenterX, previewCenterY))
        val borderPixel = marker.getPixel(
            (marker.width / 2f - 28 * density + density).toInt(),
            previewCenterY,
        )
        assertTrue(Color.green(borderPixel) > Color.red(borderPixel))
    }

    @Test fun `route endpoints use distinct travel sign colors`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val start = createEndpointMarkerArtwork(context, EndpointMarkerType.START).bitmap
        val end = createEndpointMarkerArtwork(context, EndpointMarkerType.END).bitmap
        val density = context.resources.displayMetrics.density
        val borderX = (3 * density).toInt()
        val borderY = (18 * density).toInt()

        assertTrue(start.width > 60 * density)
        assertTrue(start.height > 55 * density)
        assertTrue(Color.green(start.getPixel(borderX, borderY)) > Color.red(start.getPixel(borderX, borderY)))
        assertTrue(Color.red(end.getPixel(borderX, borderY)) > Color.green(end.getPixel(borderX, borderY)))
    }

    @Test fun `card markers shrink with the map presentation scale`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fullCheckin = createCheckinMarkerBitmap(context, "武汉", null)
        val reducedCheckin = createCheckinMarkerBitmap(context, "武汉", null, .7f)
        val fullEndpoint = createEndpointMarkerArtwork(context, EndpointMarkerType.START).bitmap
        val reducedEndpoint = createEndpointMarkerArtwork(context, EndpointMarkerType.START, .7f).bitmap

        assertTrue(reducedCheckin.width < fullCheckin.width)
        assertTrue(reducedCheckin.height < fullCheckin.height)
        assertTrue(reducedEndpoint.width < fullEndpoint.width)
        assertTrue(reducedEndpoint.height < fullEndpoint.height)
    }

    @Test fun `compact markers occupy less space than full cards and signs`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val compactCheckin = createCompactCheckinMarkerBitmap(context)
        val fullCheckin = createCheckinMarkerBitmap(context, "武汉", null)
        val compactEndpoint = createCompactEndpointMarkerArtwork(context, EndpointMarkerType.START).bitmap
        val fullEndpoint = createEndpointMarkerArtwork(context, EndpointMarkerType.START).bitmap

        assertTrue(compactCheckin.width < fullCheckin.width)
        assertTrue(compactCheckin.height < fullCheckin.height)
        assertTrue(compactEndpoint.width < fullEndpoint.width)
        assertTrue(compactEndpoint.height < fullEndpoint.height)
    }

    @Test fun `current location endpoint label is rendered below the map anchor`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val plain = createCurrentLocationMarkerArtwork(context, avatar = null)
        val withStart = createCurrentLocationMarkerArtwork(
            context,
            avatar = null,
            endpointType = EndpointMarkerType.START,
        )
        val anchorY = (withStart.anchorV * withStart.bitmap.height).toInt()

        assertTrue(withStart.bitmap.height > plain.bitmap.height)
        assertTrue(withStart.anchorV < plain.anchorV)
        assertTrue(Color.alpha(withStart.bitmap.getPixel(withStart.bitmap.width / 2, anchorY + 10)) > 0)
    }

    @Test fun `current location marker center crops avatar into a circle`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val avatar = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40, 100, 220))
        }
        val marker = createCurrentLocationMarkerArtwork(context, avatar).bitmap

        assertEquals(Color.rgb(40, 100, 220), marker.getPixel(marker.width / 2, marker.width / 2))
        assertEquals(Color.TRANSPARENT, marker.getPixel(0, 0))
        val ringPixel = marker.getPixel(marker.width / 2, (4 * context.resources.displayMetrics.density).toInt())
        assertTrue(Color.green(ringPixel) > Color.red(ringPixel))
    }

    @Test fun `auto point dot is white filled with day color stroke`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dot = createAutoPointDotBitmap(context, Color.rgb(220, 40, 40))
        val c = dot.width / 2

        assertTrue(dot.width == dot.height)
        assertEquals(Color.WHITE, dot.getPixel(c, c))
        assertTrue(Color.red(dot.getPixel(c, 1)) > Color.green(dot.getPixel(c, 1)))
        assertEquals(Color.TRANSPARENT, dot.getPixel(0, 0))
    }
}
