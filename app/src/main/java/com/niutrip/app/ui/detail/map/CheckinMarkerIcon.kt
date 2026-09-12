package com.niutrip.app.ui.detail.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.graphics.Typeface
import android.util.TypedValue
import kotlin.math.ceil

internal fun createCheckinMarkerBitmap(context: Context, name: String?, thumbnail: Bitmap?): Bitmap {
    val density = context.resources.displayMetrics.density
    val shadow = 3 * density
    val padding = 6 * density
    val previewSize = 56 * density
    val border = 2 * density
    val imageTextGap = 5 * density
    val maxTextWidth = 104 * density
    val tailHeight = 8 * density
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 66, 49)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val label = TextUtils.ellipsize(
        name?.trim().takeUnless { it.isNullOrEmpty() } ?: "旅途打卡",
        textPaint,
        maxTextWidth,
        TextUtils.TruncateAt.END,
    ).toString()
    val titleHeight = maxOf(
        (21 * density).toInt(),
        ceil(textPaint.descent() - textPaint.ascent() + 5 * density).toInt(),
    )
    val contentWidth = maxOf(previewSize, textPaint.measureText(label) + 14 * density)
    val bubbleWidth = ceil(padding * 2 + contentWidth).toInt()
    val bubbleHeight = ceil(padding + previewSize + imageTextGap + titleHeight + 4 * density).toInt()
    val width = ceil(bubbleWidth + shadow * 2).toInt()
    val height = ceil(shadow + bubbleHeight + tailHeight + shadow).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val body = RectF(shadow, shadow, shadow + bubbleWidth, shadow + bubbleHeight)
    val radius = 10 * density
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 252, 242)
        setShadowLayer(shadow, 0f, density, 0x33000000)
    }
    canvas.drawRoundRect(body, radius, radius, fill)
    fill.clearShadowLayer()
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(116, 150, 93)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    canvas.drawRoundRect(body, radius, radius, outline)

    val squareRect = RectF(
        body.centerX() - previewSize / 2,
        body.top + padding,
        body.centerX() + previewSize / 2,
        body.top + padding + previewSize,
    )
    val previewRadius = 8 * density
    val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(93, 137, 74) }
    canvas.drawRoundRect(squareRect, previewRadius, previewRadius, green)

    val inner = RectF(
        squareRect.left + border,
        squareRect.top + border,
        squareRect.right - border,
        squareRect.bottom - border,
    )
    if (thumbnail != null) {
        val cropSize = minOf(thumbnail.width, thumbnail.height)
        val source = Rect(
            (thumbnail.width - cropSize) / 2,
            (thumbnail.height - cropSize) / 2,
            (thumbnail.width + cropSize) / 2,
            (thumbnail.height + cropSize) / 2,
        )
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(inner, previewRadius - border, previewRadius - border, Path.Direction.CW) })
        canvas.drawBitmap(thumbnail, source, inner, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    } else {
        val softFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(235, 243, 220) }
        canvas.drawRoundRect(inner, previewRadius - border, previewRadius - border, softFill)
        val centerX = inner.centerX()
        val centerY = inner.centerY() - 2 * density
        canvas.drawCircle(centerX, centerY, 10 * density, green)
        canvas.drawCircle(centerX, centerY, 4 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawPath(Path().apply {
            moveTo(centerX - 5 * density, centerY + 7 * density)
            lineTo(centerX, centerY + 15 * density)
            lineTo(centerX + 5 * density, centerY + 7 * density)
            close()
        }, green)
    }
    drawClover(canvas, body.right - 8 * density, body.top + 8 * density, density, green.color)
    val titleTop = squareRect.bottom + imageTextGap
    val titleBaseline = titleTop + (titleHeight - textPaint.descent() - textPaint.ascent()) / 2f
    canvas.drawText(label, body.centerX(), titleBaseline, textPaint)

    val tailCenter = body.centerX()
    canvas.drawPath(Path().apply {
        moveTo(tailCenter - 7 * density, body.bottom - density)
        lineTo(tailCenter, body.bottom + tailHeight)
        lineTo(tailCenter + 7 * density, body.bottom - density)
        close()
    }, green)
    return bitmap
}

internal enum class EndpointMarkerType { START, END, ROUND_TRIP }

internal val EndpointMarkerType.label: String get() = when (this) {
    EndpointMarkerType.START -> "起点"
    EndpointMarkerType.END -> "终点"
    EndpointMarkerType.ROUND_TRIP -> "起点 · 终点"
}

internal val EndpointMarkerType.color: Int get() = when (this) {
    EndpointMarkerType.START -> Color.rgb(91, 132, 72)
    EndpointMarkerType.END -> Color.rgb(190, 104, 65)
    EndpointMarkerType.ROUND_TRIP -> Color.rgb(129, 108, 70)
}

internal data class EndpointMarkerArtwork(
    val bitmap: Bitmap,
    val anchorU: Float,
    val anchorV: Float,
)

internal fun createEndpointMarkerArtwork(
    context: Context,
    type: EndpointMarkerType,
): EndpointMarkerArtwork {
    val density = context.resources.displayMetrics.density
    val label = type.label
    val accent = type.color
    val shadow = 3 * density
    val signHeight = 36 * density
    val signWidth = if (type == EndpointMarkerType.ROUND_TRIP) 88 * density else 62 * density
    val postHeight = 17 * density
    val tipHeight = 6 * density
    val width = ceil(signWidth + shadow * 2).toInt()
    val pointX = width / 2f
    val signLeft = shadow
    val tipY = shadow + signHeight + postHeight + tipHeight
    val height = ceil(tipY + shadow).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val sign = RectF(signLeft, shadow, signLeft + signWidth, shadow + signHeight)
    val post = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    canvas.drawLine(sign.centerX(), sign.bottom - density, pointX, tipY - tipHeight, post.apply {
        strokeWidth = 5 * density
        strokeCap = Paint.Cap.ROUND
    })
    canvas.drawPath(Path().apply {
        moveTo(pointX - 5 * density, tipY - tipHeight)
        lineTo(pointX, tipY)
        lineTo(pointX + 5 * density, tipY - tipHeight)
        close()
    }, post)

    val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 249, 232)
        setShadowLayer(shadow, 0f, density, 0x33000000)
    }
    canvas.drawRoundRect(sign, 9 * density, 9 * density, paper)
    paper.clearShadowLayer()
    canvas.drawRoundRect(sign, 9 * density, 9 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    })
    val nail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 100, 85, 61) }
    canvas.drawCircle(sign.left + 8 * density, sign.centerY(), 1.5f * density, nail)
    canvas.drawCircle(sign.right - 8 * density, sign.centerY(), 1.5f * density, nail)
    val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 13f, context.resources.displayMetrics)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val baseline = sign.centerY() - (text.descent() + text.ascent()) / 2f
    canvas.drawText(label, sign.centerX(), baseline, text)
    drawClover(canvas, sign.right - 3 * density, sign.top + 3 * density, density * .8f, accent)
    return EndpointMarkerArtwork(
        bitmap = bitmap,
        anchorU = pointX / width,
        anchorV = tipY / height,
    )
}

private fun drawClover(
    canvas: Canvas,
    centerX: Float,
    centerY: Float,
    density: Float,
    color: Int,
) {
    val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val radius = 2.8f * density
    canvas.drawCircle(centerX - radius, centerY, radius, leaf)
    canvas.drawCircle(centerX + radius, centerY, radius, leaf)
    canvas.drawCircle(centerX, centerY - radius, radius, leaf)
    canvas.drawLine(centerX, centerY + radius, centerX + 3 * density, centerY + 7 * density, leaf.apply {
        strokeWidth = 1.4f * density
        strokeCap = Paint.Cap.ROUND
    })
}

internal data class CurrentLocationMarkerArtwork(
    val bitmap: Bitmap,
    val anchorU: Float,
    val anchorV: Float,
)

internal fun createCurrentLocationMarkerArtwork(
    context: Context,
    avatar: Bitmap?,
    endpointType: EndpointMarkerType? = null,
): CurrentLocationMarkerArtwork {
    val density = context.resources.displayMetrics.density
    val shadow = 4 * density
    val outerSize = 52 * density
    val greenRing = 3 * density
    val whiteRing = 3 * density
    val tailHeight = 9 * density
    val labelGap = 3 * density
    val labelHeight = 21 * density
    val labelPaint = endpointType?.let {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = it.color
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 11f, context.resources.displayMetrics)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }
    val labelWidth = endpointType?.let {
        maxOf(42 * density, labelPaint!!.measureText(it.label) + 16 * density)
    } ?: 0f
    val width = ceil(maxOf(outerSize + shadow * 2, labelWidth + shadow * 2)).toInt()
    val centerX = width / 2f
    val centerY = shadow + outerSize / 2f
    val tipY = centerY + outerSize / 2f + tailHeight
    val height = ceil(
        if (endpointType == null) tipY + shadow
        else tipY + labelGap + labelHeight + shadow
    ).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val outerRadius = outerSize / 2f
    val green = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 158, 91)
        setShadowLayer(shadow, 0f, density, 0x40000000)
    }
    canvas.drawCircle(centerX, centerY, outerRadius, green)
    green.clearShadowLayer()
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    canvas.drawCircle(centerX, centerY, outerRadius - greenRing, white)

    val avatarRadius = outerRadius - greenRing - whiteRing
    if (avatar != null) {
        val cropSize = minOf(avatar.width, avatar.height)
        val source = Rect(
            (avatar.width - cropSize) / 2,
            (avatar.height - cropSize) / 2,
            (avatar.width + cropSize) / 2,
            (avatar.height + cropSize) / 2,
        )
        val destination = RectF(
            centerX - avatarRadius,
            centerY - avatarRadius,
            centerX + avatarRadius,
            centerY + avatarRadius,
        )
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(centerX, centerY, avatarRadius, Path.Direction.CW) })
        canvas.drawBitmap(avatar, source, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    } else {
        val avatarFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 247, 239) }
        canvas.drawCircle(centerX, centerY, avatarRadius, avatarFill)
        val person = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 158, 91) }
        canvas.drawCircle(centerX, centerY - 6 * density, 7 * density, person)
        canvas.drawOval(
            centerX - 13 * density,
            centerY + 3 * density,
            centerX + 13 * density,
            centerY + 19 * density,
            person,
        )
    }
    canvas.drawPath(Path().apply {
        moveTo(centerX - 7 * density, centerY + outerRadius - 2 * density)
        lineTo(centerX, tipY)
        lineTo(centerX + 7 * density, centerY + outerRadius - 2 * density)
        close()
    }, green)
    if (endpointType != null && labelPaint != null) {
        val labelTop = tipY + labelGap
        val labelRect = RectF(
            centerX - labelWidth / 2,
            labelTop,
            centerX + labelWidth / 2,
            labelTop + labelHeight,
        )
        val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 249, 232) }
        canvas.drawRoundRect(labelRect, 8 * density, 8 * density, paper)
        canvas.drawRoundRect(labelRect, 8 * density, 8 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = endpointType.color
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        })
        val baseline = labelRect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(endpointType.label, centerX, baseline, labelPaint)
    }
    return CurrentLocationMarkerArtwork(
        bitmap = bitmap,
        anchorU = centerX / width,
        anchorV = tipY / height,
    )
}

/** 自动轨迹点圆圈标记（对齐 Web 分享页 CircleMarker：白底 + 当日色描边）。 */
internal fun createAutoPointDotBitmap(context: Context, color: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val stroke = 2f * density          // 描边宽
    val radius = 3f * density          // 白底半径（描边中心线在 radius + stroke/2）
    val size = ceil((radius + stroke) * 2f).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    canvas.drawCircle(center, center, radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
    canvas.drawCircle(center, center, radius + stroke / 2f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = stroke
        })
    return bitmap
}
