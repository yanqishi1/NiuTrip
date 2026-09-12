package com.niutrip.app.ui.checkin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

data class PreparedImage(val file: File, val mediaType: String, val compressed: Boolean)

fun interface ImagePreparer {
    fun prepareForUpload(uri: Uri): PreparedImage
}

class ImageCompressor(private val context: Context) : ImagePreparer {
    override fun prepareForUpload(uri: Uri): PreparedImage {
        val mediaType = context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mediaType)?.let { ".$it" } ?: ".jpg"
        val source = File.createTempFile("upload_source_", extension, context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取图片" }
                source.outputStream().use(input::copyTo)
            }
            return processCopiedFile(source, mediaType)
        } catch (error: Throwable) {
            source.delete()
            throw error
        }
    }

    internal fun processCopiedFile(source: File, mediaType: String): PreparedImage {
        if (source.length() <= MAX_BYTES) return PreparedImage(source, mediaType, false)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DIMENSION * 2) sampleSize *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?: error("无法读取图片")
        var bitmap = applyExifOrientation(source, decoded)
        val initialScale = minOf(1f, MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height))
        if (initialScale < 1f) {
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * initialScale).roundToInt(),
                (bitmap.height * initialScale).roundToInt(), true)
            bitmap.recycle()
            bitmap = scaled
        }

        val output = File.createTempFile("upload_compressed_", ".jpg", context.cacheDir)
        try {
            var quality = 88
            while (true) {
                FileOutputStream(output, false).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                if (output.length() <= MAX_BYTES) break
                if (quality > MIN_QUALITY) quality -= 8
                else {
                    require(bitmap.width > MIN_DIMENSION && bitmap.height > MIN_DIMENSION) { "图片压缩后仍超过 1MB" }
                    val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 0.8f).roundToInt(),
                        (bitmap.height * 0.8f).roundToInt(), true)
                    bitmap.recycle()
                    bitmap = resized
                    quality = 80
                }
            }
            source.delete()
            return PreparedImage(output, "image/jpeg", true)
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    private fun applyExifOrientation(source: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    companion object {
        const val MAX_BYTES = 1024 * 1024L
        private const val MAX_DIMENSION = 2048
        private const val MIN_DIMENSION = 480
        private const val MIN_QUALITY = 40
    }
}
