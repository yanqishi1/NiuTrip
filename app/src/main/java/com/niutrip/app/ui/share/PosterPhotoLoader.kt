package com.niutrip.app.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** 会话内的照片资源：已复制到应用私有目录，尺寸为方向归一化后的值（plan §7.1）。 */
data class PosterPhotoResource(
    val id: String,
    val file: File,
    val width: Int,
    val height: Int,
) {
    /** 按用途采样解码：预览用小图，导出用目标尺寸；动图只取静态首帧。 */
    fun decode(maxDimension: Int): Bitmap? = PosterPhotoLoader.decodeOriented(file.path, maxDimension)
}

/**
 * 系统图库单选导入（plan §7.1）：
 * 复制到会话目录避免 URI 过期；读取元信息验证可解码；EXIF 旋转/镜像统一到明确方向；
 * 预览与导出按需采样解码，不同时持有原始全尺寸图与多份位图。不沿用上传的 1MB 压缩策略。
 */
object PosterPhotoLoader {
    private const val SESSION_ROOT = "poster_sessions"

    fun sessionDir(context: Context, sessionId: String): File =
        File(File(context.cacheDir, SESSION_ROOT), sessionId)

    fun import(context: Context, uri: Uri, sessionId: String): PosterPhotoResource {
        val dir = sessionDir(context, sessionId).apply { check(isDirectory || mkdirs()) }
        val target = File(dir, "photo_${System.currentTimeMillis()}")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取图片")
            input.use { source -> target.outputStream().use(source::copyTo) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
            val orientation = readOrientation(target)
            val (width, height) = normalizedSize(bounds.outWidth, bounds.outHeight, orientation)
            return PosterPhotoResource(id = sessionId, file = target, width = width, height = height)
        } catch (error: Throwable) {
            target.delete()
            dir.takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }?.delete()
            throw error
        }
    }

    /** 只清理本会话目录；被历史引用的旧照片在首版不提前删除（会话结束统一清理）。 */
    fun cleanup(context: Context, sessionId: String) {
        sessionDir(context, sessionId).deleteRecursively()
    }

    internal fun readOrientation(file: File): Int = runCatching {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    internal fun normalizedSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE,
            -> height to width
            else -> width to height
        }

    internal fun decodeOriented(path: String, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > maxDimension * 2) sampleSize *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?: return null
        val oriented = applyOrientation(decoded, readOrientation(File(path)))
        val longSide = max(oriented.width, oriented.height)
        if (longSide <= maxDimension) return oriented
        val scale = maxDimension.toFloat() / longSide
        val scaled = Bitmap.createScaledBitmap(
            oriented, (oriented.width * scale).roundToInt(), (oriented.height * scale).roundToInt(), true)
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
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
}
