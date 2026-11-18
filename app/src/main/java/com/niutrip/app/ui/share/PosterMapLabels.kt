package com.niutrip.app.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val START_INK = 0xFF239DAB.toInt()
private const val END_INK = 0xFFE04435.toInt()
private const val LABEL_INK = 0xFF26312C.toInt()

private fun namePaint() = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; isFakeBoldText = true; color = LABEL_INK }

internal fun posterNameWidth(name: String): Float = (namePaint().measureText(name) + 16f).coerceIn(48f, 132f)

/** Artwork includes the anchor and leader; the SDK marker stays at the original coordinate. */
internal fun posterNamedMarkerArtwork(marker: PosterNamedMarker, density: Float): PosterMarkerArtwork {
    val x = marker.candidate.x; val y = marker.candidate.y
    val box = marker.box
    val left = min(box.left - 2, x - 5); val top = min(box.top - 2, y - 5)
    val right = max(box.right + 2, x + 5); val bottom = max(box.bottom + 2, y + 5)
    val bitmap = Bitmap.createBitmap(ceil((right - left) * density).toInt(), ceil((bottom - top) * density).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); canvas.scale(density, density); canvas.translate(-left, -top)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val tipX = x.coerceIn(box.left + 4, box.right - 4)
    val tipY = y.coerceIn(box.top + 4, box.bottom - 4)
    paint.color = Color.WHITE; paint.strokeWidth = 3f; canvas.drawLine(x, y, tipX, tipY, paint)
    paint.color = LABEL_INK; paint.strokeWidth = 1f; canvas.drawLine(x, y, tipX, tipY, paint)
    paint.color = Color.WHITE; canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, 4f, 4f, paint)
    paint.color = 0xFFD6DFD9.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = .7f
    canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, 4f, 4f, paint)
    val text = namePaint()
    val label = TextUtils.ellipsize(marker.name, text, box.width - 16, TextUtils.TruncateAt.END).toString()
    text.textAlign = Paint.Align.CENTER
    canvas.drawText(label, (box.left + box.right) / 2, (box.top + box.bottom - text.ascent() - text.descent()) / 2, text)
    paint.style = Paint.Style.FILL; paint.color = Color.WHITE; canvas.drawCircle(x, y, 4f, paint)
    paint.color = LABEL_INK; canvas.drawCircle(x, y, 2.5f, paint)
    return PosterMarkerArtwork(bitmap, (x - left) * density / bitmap.width, (y - top) * density / bitmap.height)
}

/** One compact label, with leaders ending at the actual start/end screen positions. */
internal fun posterEndpointPairArtwork(
    startName: String?, endName: String?, startX: Float, startY: Float, endX: Float, endY: Float,
    density: Float, safe: PosterMarkerBox? = null, route: List<Pair<Float, Float>> = emptyList(),
): PosterMarkerArtwork {
    val paint = namePaint()
    val separateNames = startName != endName && (startName != null || endName != null)
    val rows = if (separateNames) listOf(startName, endName) else listOf(startName ?: endName)
    val nameWidth = rows.filterNotNull().maxOfOrNull { min(paint.measureText(it), 114f) } ?: 0f
    val width = if (separateNames) max(72f, nameWidth + 42) else max(60f, nameWidth + if (nameWidth > 0) 64 else 0)
    val height = if (separateNames) 54f else 30f
    val centerX = (startX + endX) / 2
    val body = if (safe != null) PosterMapMarkers.endpointLabelBox(startX to startY, endX to endY, width, height, safe, route)
        else PosterMarkerBox(centerX - width / 2, min(startY, endY) - height - 12, centerX + width / 2, min(startY, endY) - 12)
    val left = min(body.left - 2, min(startX, endX) - 5)
    val top = min(body.top - 2, min(startY, endY) - 5)
    val right = max(body.right + 2, max(startX, endX) + 5)
    val bottom = max(body.bottom + 2, max(startY, endY) + 5)
    val bitmap = Bitmap.createBitmap(ceil((right - left) * density).toInt(), ceil((bottom - top) * density).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); canvas.scale(density, density); canvas.translate(-left, -top)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val coincident = hypot(startX - endX, startY - endY) < 2f
    fun leader(x: Float, y: Float, color: Int) {
        val tipX = x.coerceIn(body.left + 4, body.right - 4)
        val tipY = y.coerceIn(body.top + 4, body.bottom - 4)
        fill.color = Color.WHITE; fill.strokeWidth = 3f; canvas.drawLine(x, y, tipX, tipY, fill)
        fill.color = color; fill.strokeWidth = 1.2f; canvas.drawLine(x, y, tipX, tipY, fill)
    }
    if (coincident) leader(centerX, (startY + endY) / 2, LABEL_INK)
    else {
        leader(startX, startY, START_INK)
        leader(endX, endY, END_INK)
    }
    fill.color = Color.WHITE; canvas.drawRoundRect(body.left, body.top, body.right, body.bottom, 5f, 5f, fill)
    fill.style = Paint.Style.STROKE; fill.strokeWidth = .7f; fill.color = 0xFFD6DFD9.toInt()
    canvas.drawRoundRect(body.left, body.top, body.right, body.bottom, 5f, 5f, fill); fill.style = Paint.Style.FILL
    fun badge(text: String, x: Float, cy: Float, color: Int) {
        fill.color = color; canvas.drawRoundRect(x, cy - 9, x + 18, cy + 9, 3f, 3f, fill)
        paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, x + 9, cy - (paint.ascent() + paint.descent()) / 2, paint)
    }
    fun name(text: String?, x: Float, cy: Float) {
        if (text == null) return
        paint.color = LABEL_INK; paint.textAlign = Paint.Align.LEFT
        val label = TextUtils.ellipsize(text, paint, nameWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(label, x, cy - (paint.ascent() + paint.descent()) / 2, paint)
    }
    if (separateNames) {
        badge("起", body.left + 8, body.top + 14, START_INK); name(startName, body.left + 34, body.top + 14)
        badge("终", body.left + 8, body.top + 40, END_INK); name(endName, body.left + 34, body.top + 40)
    } else {
        badge("起", body.left + 8, body.top + 15, START_INK)
        badge("终", body.left + 30, body.top + 15, END_INK)
        name(startName ?: endName, body.left + 56, body.top + 15)
    }
    fun dot(x: Float, y: Float, color: Int) {
        fill.color = Color.WHITE; canvas.drawCircle(x, y, 4f, fill)
        fill.color = color; canvas.drawCircle(x, y, 2.5f, fill)
    }
    if (coincident) dot((startX + endX) / 2, (startY + endY) / 2, LABEL_INK)
    else { dot(startX, startY, START_INK); dot(endX, endY, END_INK) }
    // Transparent space between the label and its anchors must remain available to nearby scenic stops.
    val collisionBoxes = listOf(body,
        PosterMarkerBox(startX - 4, startY - 4, startX + 4, startY + 4),
        PosterMarkerBox(endX - 4, endY - 4, endX + 4, endY + 4))
    return PosterMarkerArtwork(bitmap, -left * density / bitmap.width, -top * density / bitmap.height, collisionBoxes)
}
