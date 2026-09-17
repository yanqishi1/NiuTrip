package com.niutrip.app.ui.share

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import com.niutrip.app.core.RouteGeometry
import com.niutrip.app.core.formatDistance
import java.io.File

object TrackImageFiles {
    fun create(context: Context, map: Bitmap, data: TrackImageData): File {
        val width = 1080
        val mapHeight = (width.toFloat() * map.height / map.width).toInt()
        val image = Bitmap.createBitmap(width, 240 + mapHeight + 220, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            canvas.drawColor(Color.WHITE)
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 44, 32); textSize = 48f; isFakeBoldText = true }
            val title = StaticLayout.Builder.obtain(data.track.track_name, 0, data.track.track_name.length, titlePaint, 968)
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
            canvas.save()
            canvas.translate(56f, 32f)
            title.draw(canvas)
            canvas.restore()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = Color.rgb(95, 106, 100) }
            val start = data.track.track_start_time?.take(10) ?: data.days.first().date.toString()
            val end = data.track.track_end_time?.take(10) ?: data.days.last().date.toString()
            canvas.drawText("$start ~ $end · 已结束", 56f, 208f, paint)
            // 不裁切地图截图，保留 SDK 的地图标识与比例尺。
            canvas.drawBitmap(map, null, Rect(0, 240, width, 240 + mapHeight), Paint(Paint.FILTER_BITMAP_FLAG))
            val points = data.days.flatMap { it.points }
            paint.textSize = 36f
            paint.color = Color.rgb(20, 110, 69)
            val stats = "${formatDistance(RouteGeometry.totalDistanceMeters(points))}   ·   ${data.days.size} 天   ·   ${points.count { it.isCheckin }} 次打卡"
            val measuredWidth = paint.measureText(stats)
            if (measuredWidth > 968f) paint.textSize *= 968f / measuredWidth
            canvas.drawText(stats, 56f, (240 + mapHeight + 80).toFloat(), paint)
            paint.textSize = 30f
            paint.color = Color.rgb(95, 106, 100)
            canvas.drawText("旅行牛牛", 56f, (image.height - 54).toFloat(), paint)
            val directory = File(context.cacheDir, "track_shares").apply { check(isDirectory || mkdirs()) }
            // 已发出的文件保留一天，避免接收应用延迟读取时 URI 失效。
            directory.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 86_400_000 }
                ?.forEach { it.delete() }
            val file = File.createTempFile("track_", ".png", directory)
            try {
                file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
            return file
        } finally { image.recycle() }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "轨迹图片", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享轨迹图片"))
    }

    fun saveToGallery(context: Context, file: File) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "NiuTrip_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NiuTrip")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "无法创建相册图片" }
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
            if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
