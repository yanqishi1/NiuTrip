package com.niutrip.app.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import com.niutrip.app.core.RouteGeometry
import com.niutrip.app.core.dayColor
import kotlin.math.hypot

/** Shared map/photo route layer. Coordinates use the map's fixed logical scale. */
internal object PosterRouteLayerRenderer {
    const val REFERENCE_WIDTH = 1640f
    const val INK = 0xFF202C30.toInt()

    fun draw(canvas: Canvas, model: PosterRouteModel, transform: PosterRouteTransform,
             width: Float, height: Float, style: PosterRouteStyleState, names: PosterEndpointNames,
             checkinIcon: Bitmap, ink: Int, haloColor: Int,
             routeUnderlayColor: Int = haloColor): PosterWireframeRenderer.AnnotationReport {
        val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val underlay = Paint(routePaint).apply { color = routeUnderlayColor; strokeWidth = 9f }
        val route = model.paths.flatten().map { transform.map(it.x, it.y) }.map { it.x.toFloat() to it.y.toFloat() }
        model.paths.forEachIndexed { index, points ->
            val path = Path()
            points.forEachIndexed { i, point ->
                val xy = transform.map(point.x, point.y)
                if (i == 0) path.moveTo(xy.x.toFloat(), xy.y.toFloat()) else path.lineTo(xy.x.toFloat(), xy.y.toFloat())
            }
            routePaint.color = if (style.theme == PosterRouteTheme.SINGLE) PosterWireframeRenderer.CORAL
                else dayColor(model.pathDayIndices.getOrElse(index) { 0 }).toInt()
            canvas.drawPath(path, underlay); canvas.drawPath(path, routePaint)
        }
        val all = model.paths.flatten().distinctBy { it.point.id }
        val reserved = mutableListOf<PosterMarkerBox>()
        val endpoints = setOf(all.firstOrNull()?.point?.id, all.lastOrNull()?.point?.id)
        val start = all.firstOrNull(); val end = all.lastOrNull()
        fun endpoint(point: PosterRoutePoint, title: String?, subtitle: String) {
            val xy = transform.map(point.x, point.y)
            val x = xy.x.toFloat(); val y = xy.y.toFloat()
            if (x !in 0f..width || y !in 0f..height) return
            drawEndpointPin(canvas, x, y)
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = PosterWireframeRenderer.RED; textSize = 24f; isFakeBoldText = true }
            val label = title?.takeIf { style.showEndpointNames }?.let {
                TextUtils.ellipsize(it, p, width * .34f, TextUtils.TruncateAt.END).toString()
            }
            val small = TextPaint(p).apply { textSize = 19f; isFakeBoldText = false }
            val w = maxOf(label?.let(p::measureText) ?: 0f, small.measureText(subtitle))
            val labelHeight = if (label == null) 28f else 57f
            val safe = PosterMarkerBox(8f, 8f, width - 8, height - 8)
            val box = PosterMapMarkers.endpointLabelBox(x to y - 32, x to y - 32, w, labelHeight, safe, route, reserved)
            val halo = TextPaint(p).apply { color = haloColor; this.style = Paint.Style.STROKE; strokeWidth = 5f }
            if (label != null) {
                canvas.drawText(label, box.left, box.top + 24, halo)
                canvas.drawText(label, box.left, box.top + 24, p)
            }
            halo.textSize = small.textSize; halo.isFakeBoldText = false
            canvas.drawText(subtitle, box.left, box.bottom - 5, halo)
            canvas.drawText(subtitle, box.left, box.bottom - 5, small)
            reserved.add(box.expanded(8f)); reserved.add(PosterCompactLabels.pinBox(x, y).expanded(8f))
        }
        if (start != null && end != null) {
            val sx = transform.map(start.x, start.y); val ex = transform.map(end.x, end.y)
            val meters = RouteGeometry.distanceMeters(start.point.lat, start.point.lon, end.point.lat, end.point.lon)
            if (hypot(sx.x - ex.x, sx.y - ex.y) < 48 && meters <= 1000) {
                val a = PosterMapMarkers.endpointName(start.point, names.start)
                val b = PosterMapMarkers.endpointName(end.point, names.end)
                endpoint(start, listOfNotNull(a, b).distinct().joinToString(" / ").ifBlank { null }, "起点 / 终点")
            } else {
                endpoint(start, PosterMapMarkers.endpointName(start.point, names.start), "起点")
                endpoint(end, PosterMapMarkers.endpointName(end.point, names.end), "终点")
            }
        }
        if (!style.showCheckinMarkers) return PosterWireframeRenderer.AnnotationReport()
        val candidates = all.filter { it.point.isCheckin && it.point.id !in endpoints &&
            (style.selectedCheckinIds == null || it.point.id in style.selectedCheckinIds) }.mapIndexed { index, point ->
            val xy = transform.map(point.x, point.y)
            PosterMarkerCandidate(point.point, xy.x.toFloat(), xy.y.toFloat(), index)
        }
        val labels = PosterCompactLabels.layout(candidates, reserved, route, width, height,
            style.preferredCheckins) { name, size -> TextPaint().apply { textSize = size }.measureText(name) }
        val placed = labels.map { it.candidate.point.id }.toSet()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PosterWireframeRenderer.CORAL }
        candidates.filter { it.point.id !in placed }.forEach { canvas.drawCircle(it.x, it.y, 4f, dot) }
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val iconHeight = 36f * checkinIcon.height / checkinIcon.width
        labels.forEach {
            val x = it.candidate.x; val y = it.candidate.y
            canvas.drawBitmap(checkinIcon, null, RectF(x - 18f, y - iconHeight, x + 18f, y), iconPaint)
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = it.textSize }
            val halo = TextPaint(p).apply { color = haloColor; this.style = Paint.Style.STROKE; strokeWidth = 5f }
            canvas.drawText(it.name, it.box.left, it.box.bottom - it.textSize * .22f, halo)
            canvas.drawText(it.name, it.box.left, it.box.bottom - it.textSize * .22f, p)
        }
        return PosterWireframeRenderer.AnnotationReport(candidates.size, labels.size)
    }

    private fun drawEndpointPin(canvas: Canvas, x: Float, y: Float) {
        val saved = canvas.save()
        canvas.translate(x, y)
        canvas.scale(1.28f, 1.28f)
        val path = Path().apply {
            moveTo(0f, 0f); cubicTo(-7f, -11f, -18f, -23f, -18f, -32f)
            cubicTo(-18f, -56f, 18f, -56f, 18f, -32f)
            cubicTo(18f, -23f, 7f, -11f, 0f, 0f); close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PosterWireframeRenderer.RED }
        canvas.drawPath(path, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(0f, -32f, 7f, paint)
        canvas.restoreToCount(saved)
    }
}
