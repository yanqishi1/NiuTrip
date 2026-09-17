package com.niutrip.app.ui.share

import android.app.Application
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.niutrip.app.core.DayGrouper
import com.niutrip.app.core.PointLite
import com.niutrip.app.data.remote.TrackDto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackImageFilesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun data() = TrackImageData(
        TrackDto(track_id = "t1", track_name = "A long journey across the mountains and coast with a very long title that must wrap without covering the dates",
            track_record_mode = "AUTO", track_status = "FINISHED"),
        DayGrouper.group(listOf(
            PointLite("p1", LocalDateTime.parse("2026-09-11T10:00:00"), 100.0, 30.0, true),
            PointLite("p2", LocalDateTime.parse("2026-09-12T10:00:00"), 101.0, 31.0, false),
        )),
    )

    @Test fun `image preserves complete map rectangle and shares only png with readable uri`() {
        val map = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        map.eraseColor(Color.rgb(235, 242, 246))
        val canvas = Canvas(map)
        val paint = Paint().apply { color = Color.rgb(18, 150, 84); strokeWidth = 4f }
        canvas.drawLine(50f, 340f, 300f, 80f, paint)
        paint.color = Color.BLACK
        canvas.drawRect(0f, 395f, 400f, 400f, paint)
        val file = TrackImageFiles.create(context, map, data())
        val image = BitmapFactory.decodeFile(file.path)
        assertEquals(1080, image.width)
        assertEquals(1540, image.height)
        assertEquals(map.getPixel(0, 0), image.getPixel(0, 240))
        assertEquals(Color.BLACK, image.getPixel(0, 1319))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        TrackImageFiles.share(activity, file)
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra("android.intent.extra.INTENT", Intent::class.java)!!
        assertEquals("image/png", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(send.clipData)
        assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
        val preview = File("build/outputs/share-image-preview.png")
        preview.parentFile!!.mkdirs()
        file.copyTo(preview, overwrite = true)
        image.recycle()
        map.recycle()
    }

    @Test fun `rectangular maps keep their aspect ratio and logos are not cropped`() {
        val map = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        map.eraseColor(Color.BLUE)
        val file = TrackImageFiles.create(context, map, data())
        val image = BitmapFactory.decodeFile(file.path)
        assertEquals(1000, image.height)
        assertEquals(Color.BLUE, image.getPixel(1079, 779))
        image.recycle()
        map.recycle()
    }
}
