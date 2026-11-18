package com.niutrip.app.ui.share

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/** Cached offline geographic contours; the same Mercator transform binds them to the route. */
class PosterMapOutline private constructor(private val path: Path, private val labels: List<ProvinceLabel>,
                                         internal val areas: List<Area>,
                                         private val focusBounds: PosterProjectedBounds? = null) {
    private data class ProvinceLabel(val name: String, val x: Double, val y: Double)
    internal data class Area(val code: Int, val level: String, val rings: List<List<PosterPointF>>) {
        val bounds = PosterProjectedBounds(rings.flatten().minOf { it.x }, rings.flatten().maxOf { it.x },
            rings.flatten().minOf { it.y }, rings.flatten().maxOf { it.y })

        fun contains(point: PosterPointF): Boolean {
            if (point.x !in bounds.minX..bounds.maxX || point.y !in bounds.minY..bounds.maxY) return false
            var inside = false
            for (ring in rings) {
                var previous = ring.last()
                for (current in ring) {
                    if ((current.y > point.y) != (previous.y > point.y) &&
                        point.x < (previous.x - current.x) * (point.y - current.y) /
                        (previous.y - current.y) + current.x) inside = !inside
                    previous = current
                }
            }
            return inside
        }
    }

    internal fun containing(points: List<PosterPointF>): Area? {
        if (points.isEmpty()) return null
        val bounds = PosterProjectedBounds(points.minOf { it.x }, points.maxOf { it.x }, points.minOf { it.y }, points.maxOf { it.y })
        return areas.firstOrNull { area -> area.code > 0 &&
            bounds.minX >= area.bounds.minX && bounds.maxX <= area.bounds.maxX &&
            bounds.minY >= area.bounds.minY && bounds.maxY <= area.bounds.maxY && points.all(area::contains) }
    }

    internal fun focusedOn(area: Area) = PosterMapOutline(path, labels, areas, area.bounds)

    fun photoTransform(model: PosterRouteModel, width: Float, height: Float, padding: Float): PosterRouteTransform {
        val bounds = focusBounds?.let { PosterProjectedBounds(minOf(it.minX, model.bounds.minX), maxOf(it.maxX, model.bounds.maxX),
            minOf(it.minY, model.bounds.minY), maxOf(it.maxY, model.bounds.maxY)) } ?: model.bounds
        return model.fit(width.toDouble(), height.toDouble(), padding.toDouble(), bounds)
    }
    fun draw(canvas: Canvas, model: PosterRouteModel, transform: PosterRouteTransform,
             left: Float, top: Float, width: Float, height: Float, light: Boolean,
             provinceNames: Boolean = false, attribution: Boolean = true) {
        val origin = transform.map(0.0, 0.0)
        val unit = transform.map(1.0, 1.0)
        val scale = (unit.x - origin.x).toFloat()
        if (scale <= 0f) return
        val checkpoint = canvas.save()
        canvas.translate(left, top)
        canvas.clipRect(0f, 0f, width, height)
        canvas.concat(Matrix().apply { setValues(floatArrayOf(scale, 0f, origin.x.toFloat(),
            0f, (unit.y - origin.y).toFloat(), origin.y.toFloat(), 0f, 0f, 1f)) })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = if (provinceNames) {
            if (light) 0xFF202B2E.toInt() else PosterWireframeRenderer.PAPER
        } else Color.TRANSPARENT
        if (provinceNames) canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        val strokeWidth = if (provinceNames) maxOf(1f, width * .002f) else maxOf(1.6f, width * .004f)
        if (!provinceNames) {
            paint.strokeWidth = (strokeWidth + width * .002f) / scale
            paint.color = if (light) Color.argb(85, 20, 28, 32) else Color.argb(120, 255, 255, 255)
            canvas.drawPath(path, paint)
        }
        paint.strokeWidth = strokeWidth / scale
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = if (light) Color.argb(if (provinceNames) 175 else 210, 245, 250, 250)
            else if (provinceNames) Color.rgb(202, 217, 221) else Color.argb(205, 36, 65, 69)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(checkpoint)
        if (provinceNames) {
            val saved = canvas.save()
            canvas.clipRect(left, top, left + width, top + height)
            paint.style = Paint.Style.FILL
            paint.textSize = width * .014f
            paint.textAlign = Paint.Align.CENTER
            paint.color = if (light) Color.argb(120, 240, 250, 250) else Color.rgb(159, 177, 181)
            labels.forEach {
                val xy = transform.map(it.x, it.y)
                val half = paint.measureText(it.name) / 2
                if (xy.x >= half + 4 && xy.x <= width - half - 4 && xy.y >= paint.textSize && xy.y <= height - 4) {
                    canvas.drawText(it.name, left + xy.x.toFloat(), top + xy.y.toFloat(), paint)
                }
            }
            canvas.restoreToCount(saved)
        }
        if (!attribution) return
        paint.textAlign = Paint.Align.LEFT
        paint.style = Paint.Style.FILL
        paint.textSize = width * .018f
        paint.color = if (light) Color.argb(160, 255, 255, 255) else Color.argb(160, 36, 65, 69)
        canvas.drawText("地理轮廓：DataV", left + width * .025f, top + height - width * .02f, paint)
    }

    companion object {
        @Volatile private var cached: PosterMapOutline? = null

        fun load(context: Context): PosterMapOutline = cached ?: synchronized(this) {
            cached ?: context.assets.open("poster_china_provinces.geojson").bufferedReader().use {
                fromJson(it.readText())
            }.also { cached = it }
        }

        internal fun fromJson(json: String): PosterMapOutline {
            val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
            val labels = mutableListOf<ProvinceLabel>()
            val areas = mutableListOf<Area>()
            var areaRings = mutableListOf<List<PosterPointF>>()
            fun polygon(rings: JSONArray) {
                for (r in 0 until rings.length()) {
                    val ring = rings.getJSONArray(r)
                    val points = mutableListOf<PosterPointF>()
                    for (i in 0 until ring.length()) {
                        val coordinate = ring.getJSONArray(i)
                        val x = coordinate.getDouble(0).toFloat()
                        val lat = coordinate.getDouble(1).coerceIn(-85.05112878, 85.05112878)
                        val y = (ln(tan(PI / 4 + lat * PI / 360)) * 180 / PI).toFloat()
                        points.add(PosterPointF(coordinate.getDouble(0), y.toDouble()))
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    if (points.size >= 3) areaRings.add(points)
                }
            }
            val features = JSONObject(json).getJSONArray("features")
            for (i in 0 until features.length()) {
                areaRings = mutableListOf()
                val feature = features.getJSONObject(i)
                val properties = feature.optJSONObject("properties")
                val center = properties?.optJSONArray("centroid") ?: properties?.optJSONArray("center")
                if (center != null && center.length() >= 2) {
                    val lat = center.getDouble(1).coerceIn(-85.05112878, 85.05112878)
                    labels.add(ProvinceLabel(properties?.optString("name").orEmpty(), center.getDouble(0),
                        ln(tan(PI / 4 + lat * PI / 360)) * 180 / PI))
                }
                val geometry = feature.getJSONObject("geometry")
                val coordinates = geometry.getJSONArray("coordinates")
                when (geometry.getString("type")) {
                    "Polygon" -> polygon(coordinates)
                    "MultiPolygon" -> for (p in 0 until coordinates.length()) polygon(coordinates.getJSONArray(p))
                }
                if (areaRings.isNotEmpty()) areas.add(Area(properties?.optInt("adcode") ?: 0,
                    properties?.optString("level").orEmpty(), areaRings))
            }
            require(areas.isNotEmpty()) { "地图边界数据为空" }
            return PosterMapOutline(path, labels, areas)
        }
    }
}
