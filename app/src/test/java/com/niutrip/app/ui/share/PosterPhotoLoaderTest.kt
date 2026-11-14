package com.niutrip.app.ui.share

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterPhotoLoaderTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun writeJpeg(width: Int, height: Int, orientation: Int = ExifInterface.ORIENTATION_NORMAL): File {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xFF3366AA.toInt())
        val file = File.createTempFile("pick_", ".jpg", context.cacheDir)
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        source.recycle()
        if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            val exif = ExifInterface(file.path)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
        }
        return file
    }

    @Test fun `import normalizes exif orientation into upright dimensions`() {
        val picked = writeJpeg(80, 40, ExifInterface.ORIENTATION_ROTATE_90)
        val resource = PosterPhotoLoader.import(context, Uri.fromFile(picked), "s1")
        assertEquals(40, resource.width)
        assertEquals(80, resource.height)
        val decoded = resource.decode(maxDimension = 512)
        assertEquals(40, decoded!!.width)
        assertEquals(80, decoded.height)
        decoded.recycle()
        PosterPhotoLoader.cleanup(context, "s1")
    }

    @Test fun `import copies into session dir and cleanup removes it`() {
        val picked = writeJpeg(60, 30)
        val resource = PosterPhotoLoader.import(context, Uri.fromFile(picked), "s2")
        val sessionDir = File(context.cacheDir, "poster_sessions/s2")
        assertTrue(resource.file.parentFile == sessionDir)
        assertTrue(resource.file.exists())
        assertTrue(resource.file.length() > 0)
        PosterPhotoLoader.cleanup(context, "s2")
        assertTrue(!sessionDir.exists())
        // 源文件不受会话清理影响。
        assertTrue(picked.exists())
        picked.delete()
    }

    @Test fun `undecodable content is rejected without creating a resource`() {
        val broken = File.createTempFile("broken_", ".jpg", context.cacheDir)
        broken.writeBytes(byteArrayOf(1, 2, 3))
        try {
            PosterPhotoLoader.import(context, Uri.fromFile(broken), "s3")
            fail("应当拒绝无法解码的图片")
        } catch (expected: IllegalArgumentException) {
        }
        val sessionDir = File(context.cacheDir, "poster_sessions/s3")
        assertTrue(sessionDir.list().isNullOrEmpty())
        broken.delete()
    }

    @Test fun `decode samples down to the requested bound`() {
        val picked = writeJpeg(1200, 900)
        val resource = PosterPhotoLoader.import(context, Uri.fromFile(picked), "s4")
        val decoded = resource.decode(maxDimension = 300)
        assertTrue(decoded!!.width <= 400) // 1200→~300，允许少量上取整
        assertTrue(decoded.height <= 300)
        decoded.recycle()
        PosterPhotoLoader.cleanup(context, "s4")
        picked.delete()
    }
}
