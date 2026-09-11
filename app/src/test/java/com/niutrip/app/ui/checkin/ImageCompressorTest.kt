package com.niutrip.app.ui.checkin

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class ImageCompressorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val compressor = ImageCompressor(context)

    @Test fun `image at or below one megabyte is not re-encoded`() {
        val bytes = ByteArray(ImageCompressor.MAX_BYTES.toInt()) { (it % 251).toByte() }
        val source = File.createTempFile("small_", ".png", context.cacheDir).apply { writeBytes(bytes) }

        val result = compressor.processCopiedFile(source, "image/png")

        assertFalse(result.compressed)
        assertTrue(result.file.length() <= ImageCompressor.MAX_BYTES)
        assertArrayEquals(bytes, result.file.readBytes())
        result.file.delete()
    }

    @Test fun `image over one megabyte is compressed below the limit`() {
        val random = Random(42)
        val pixels = IntArray(1200 * 1200) { random.nextInt() or (0xFF shl 24) }
        val bitmap = Bitmap.createBitmap(pixels, 1200, 1200, Bitmap.Config.ARGB_8888)
        val source = File.createTempFile("large_", ".png", context.cacheDir)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        assertTrue(source.length() > ImageCompressor.MAX_BYTES)

        val result = compressor.processCopiedFile(source, "image/png")

        assertTrue(result.compressed)
        assertTrue(result.file.length() in 1..ImageCompressor.MAX_BYTES)
        assertTrue(result.mediaType == "image/jpeg")
        result.file.delete()
    }
}
