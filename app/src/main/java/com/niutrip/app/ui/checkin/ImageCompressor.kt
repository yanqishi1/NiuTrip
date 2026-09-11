package com.niutrip.app.ui.checkin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

class ImageCompressor(private val context: Context) {
    fun compressToUnder1Mb(uri: Uri): File {
        val source = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: error("无法读取图片")
        val scale = minOf(1f, 2048f / maxOf(source.width, source.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true) else source
        val file = File.createTempFile("checkin_", ".jpg", context.cacheDir)
        var quality = 90
        do {
            FileOutputStream(file, false).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            quality -= 8
        } while (file.length() > MAX_BYTES && quality >= 34)
        if (bitmap !== source) source.recycle()
        bitmap.recycle()
        require(file.length() <= MAX_BYTES) { "图片压缩后仍超过 1MB" }
        return file
    }

    companion object { const val MAX_BYTES = 1024 * 1024L }
}
