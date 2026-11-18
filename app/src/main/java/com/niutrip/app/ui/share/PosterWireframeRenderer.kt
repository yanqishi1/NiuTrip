package com.niutrip.app.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import com.niutrip.app.core.formatDistance

/** One fixed logical canvas gives preview and PNG identical typography and marker selection. */
object PosterWireframeRenderer {
    data class AnnotationReport(val selected: Int = 0, val named: Int = 0)
    const val PAPER = 0xFFF8FBFB.toInt()
    const val CORAL = 0xFFF45140.toInt()
    const val RED = 0xFFF22538.toInt()
    private const val INK = 0xFF202C30.toInt()
    private const val MUTED = 0xFF78898E.toInt()
    @Volatile private var cachedCheckinIcon: Bitmap? = null

    fun loadCheckinIcon(context: Context): Bitmap = cachedCheckinIcon ?: synchronized(this) {
        cachedCheckinIcon ?: context.assets.open("poster_checkin_pin.png").use {
            requireNotNull(BitmapFactory.decodeStream(it)) { "无法读取分享打卡图标" }
        }.also { cachedCheckinIcon = it }
    }

    fun mapRect(height: Float) = RectF(80f, height * .169f, 1720f, height * .735f)

    fun draw(canvas: Canvas, width: Float, height: Float, document: PosterDocument,
             snapshot: PosterTrackSnapshot, model: PosterRouteModel,
             names: PosterEndpointNames, outline: PosterMapOutline, checkinIcon: Bitmap): AnnotationReport {
        if (width <= 0 || height <= 0) return AnnotationReport()
        val saved = canvas.save()
        canvas.scale(width / 1800f, width / 1800f)
        val h = height * 1800f / width
        val light = document.mapInfoState?.textTheme == PosterInfoTextTheme.LIGHT
        val paper = if (light) 0xFF202B2E.toInt() else PAPER
        val ink = if (light) Color.WHITE else INK
        val muted = if (light) 0xFFB0C0C4.toInt() else MUTED
        canvas.drawColor(paper)
        fun text(value: String, x: Float, y: Float, size: Float, bold: Boolean = false,
                 color: Int = ink, maxWidth: Float = 1640f) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = size; isFakeBoldText = bold
            }
            canvas.drawText(TextUtils.ellipsize(value, paint, maxWidth, TextUtils.TruncateAt.END).toString(), x, y, paint)
        }
        text("旅行牛牛 / 旅行记录", 100f, h * .046f, 27f, color = muted)
        if (document.mapInfoState?.showName != false) text(snapshot.stats.name, 100f, h * .098f, 62f, true)
        if (document.mapInfoState?.showDate != false) snapshot.stats.dateText?.let {
            text(it, 100f, h * .127f, 28f, color = muted)
        }
        val rect = mapRect(h)
        val mapSave = canvas.save()
        canvas.translate(rect.left, rect.top)
        canvas.clipRect(0f, 0f, rect.width(), rect.height())
        if (model.bounds.spanX >= .5 || model.bounds.spanY >= .5) {
            canvas.drawColor(if (light) 0xFF263539.toInt() else 0xFFF0F6F7.toInt())
        }
        val viewport = document.mapState
        val transform = model.fit(rect.width().toDouble(), rect.height().toDouble(),
            minOf(rect.width(), rect.height()) * .11).viewport(
            viewport.outlineZoom, viewport.outlinePanX * rect.width(), viewport.outlinePanY * rect.height(),
            rect.width(), rect.height())
        outline.draw(canvas, model, transform, 0f, 0f, rect.width(), rect.height(), light,
            provinceNames = true, attribution = false)
        val report = PosterRouteLayerRenderer.draw(canvas, model, transform, rect.width(), rect.height(),
            document.mapRouteStyle, names, checkinIcon, ink, paper)
        canvas.restoreToCount(mapSave)
        val footerTop = h * .775f
        canvas.drawLine(100f, footerTop, 1700f, footerTop, Paint().apply { color = 0xFFD5E0E2.toInt(); strokeWidth = 2f })
        text("行程总里程", 100f, footerTop + h * .034f, 28f, color = muted)
        val distance = formatDistance(snapshot.stats.distanceMeters)
        val split = distance.lastIndexOf(' ')
        val value = if (split < 0) distance else distance.substring(0, split)
        val unit = if (split < 0) "" else distance.substring(split + 1)
        val size = minOf(142f, 1350f / TextPaint().apply { textSize = 1f }.measureText(value))
        text(value, 100f, footerTop + h * .092f, size)
        val numberWidth = TextPaint().apply { textSize = size }.measureText(value)
        text(unit, 120f + numberWidth, footerTop + h * .092f, 37f, maxWidth = 180f)
        if (document.mapInfoState?.showAux != false) {
            listOf((snapshot.stats.durationText ?: "--") to "旅行历时", "${snapshot.stats.recordDays}" to "记录天数",
                "${snapshot.stats.checkinCount}" to "打卡次数").forEachIndexed { index, (v, label) ->
                val x = 100f + index * 590f
                text(v, x, h * .93f, 48f, maxWidth = 520f)
                text(label, x, h * .956f, 28f, color = muted, maxWidth = 520f)
            }
        }
        text("地理轮廓：阿里云 DataV", 100f, h * .984f, 18f, color = muted)
        canvas.restoreToCount(saved)
        return report
    }

}
