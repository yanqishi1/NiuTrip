package com.niutrip.app.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import com.niutrip.app.core.dayColor
import com.niutrip.app.core.formatDistance
import java.io.File
import kotlin.math.hypot
import kotlin.math.max

/**
 * 海报位图合成（plan §11）：预览与导出复用同一布局解析与绘制规则，
 * 只在资源分辨率上区分；导出坐标使用目标位图真实像素。
 */
object PosterRenderer {

    /** 信息模块样式与显示开关（plan §9.1）：主里程保留，名称/日期/辅助指标可隐藏。 */
    data class InfoStyle(
        val textTheme: PosterInfoTextTheme,
        val outline: Boolean = true,
        val showName: Boolean = true,
        val showDate: Boolean = true,
        val showAux: Boolean = true,
    )
    data class RouteStyle(val theme: PosterRouteTheme, val showCheckins: Boolean,
                          val showEndpointNames: Boolean = true,
                          val endpointNames: PosterEndpointNames = PosterEndpointNames())

    private val START_COLOR = 0xFF00B96B.toInt()
    private val END_COLOR = 0xFFFF5A5F.toInt()
    private val CHECKIN_COLOR = 0xFFF59E0B.toInt()
    private const val BRAND = "旅行牛牛"

    /**
     * 信息模块（plan §9.1，参考图排版节奏）：
     * 名称（最大、单行居中）→ 日期（弱化）→ 英雄里程数字 → 辅助指标（数值上/标签下分列）。
     * 名称不走 StaticLayout：单行省略由 measureText + TextUtils.ellipsize 处理后居中 drawText，
     * 确定性居中且描边/填充不可能分叉（真机曾出现一白一黑双标题，StaticLayout 双画笔排版分叉所致）。
     */
    class InfoLayout private constructor(
        private val stats: PosterStats,
        private val style: InfoStyle,
        private val width: Float,
        internal val heightPx: Float,
        private val nameText: String,
        private val distanceValue: String,
        private val distanceUnit: String,
        internal val auxColumns: List<Pair<String, String>>,
    ) {
        val aspect: Float get() = if (width > 0f) heightPx / width else 0f

        fun draw(canvas: Canvas, left: Float, top: Float) {
            var y = top + gap(0.022f)
            // 名称：模块内水平居中。
            if (style.showName) {
                val paint = fillPaint(fillColor(style), bold = true).apply { textSize = nameSize(width) }
                drawText(canvas, nameText, left + width / 2f, y + paint.textSize, paint)
                y += paint.textSize * 1.25f + gap(0.010f)
            }
            // 日期：弱化为次级信息；可能是跨日区间（最长 23 字符），过宽时压缩字号。
            if (style.showDate) {
                stats.dateText?.let { date ->
                    val paint = fillPaint(secondaryColor(style)).apply { textSize = dateSize(width) }
                    val available = width - pad() * 2
                    val measured = paint.measureText(date)
                    if (measured > available) {
                        paint.textSize = paint.textSize * available / measured
                    }
                    drawText(canvas, date, left + width / 2f, y + paint.textSize, paint)
                    y += paint.textSize * 1.35f + gap(0.034f)
                }
            }
            // 英雄里程：极大加粗数字 + 基线小单位，成对居中（plan §9.2）。
            val mainPaint = fillPaint(mainColor(style), bold = true, align = Paint.Align.LEFT)
                .apply { textSize = mainSize(width) }
            val unitPaint = fillPaint(mainColor(style), bold = true, align = Paint.Align.LEFT)
                .apply { textSize = unitSize(width) }
            val unitGap = width * 0.016f
            val mainWidth = mainPaint.measureText(distanceValue)
            val unitWidth = if (distanceUnit.isEmpty()) 0f else unitPaint.measureText(distanceUnit)
            val startX = left + (width - mainWidth - unitWidth - unitGap).coerceAtLeast(0f) / 2f
            val baseline = y + mainPaint.textSize
            drawText(canvas, distanceValue, startX, baseline, mainPaint)
            if (distanceUnit.isNotEmpty()) {
                drawText(canvas, distanceUnit, startX + mainWidth + unitGap, baseline, unitPaint)
            }
            y += mainPaint.textSize * 1.05f + gap(0.030f)
            // 辅助指标：数值上、标签下的分列数据组，缺失项自动收列（参考图网格节奏）。
            if (auxColumns.isNotEmpty()) {
                val columnWidth = width / auxColumns.size
                auxColumns.forEachIndexed { index, (value, label) ->
                    val centerX = left + columnWidth * (index + 0.5f)
                    val valuePaint = fillPaint(fillColor(style), bold = true).apply { textSize = auxValueSize(width) }
                    val available = columnWidth * 0.92f
                    val measured = valuePaint.measureText(value)
                    if (measured > available) {
                        valuePaint.textSize = valuePaint.textSize * available / measured
                    }
                    drawText(canvas, value, centerX, y + valuePaint.textSize, valuePaint)
                    val labelPaint = fillPaint(secondaryColor(style)).apply { textSize = auxLabelSize(width) }
                    drawText(canvas, label, centerX, y + valuePaint.textSize + gap(0.008f) + labelPaint.textSize, labelPaint)
                }
                y += auxValueSize(width) * 1.2f + gap(0.008f) + auxLabelSize(width) * 1.3f + gap(0.030f)
            }
        }

        private fun pad(): Float = width * 0.03f
        private fun gap(ratio: Float): Float = width * ratio

        /** 双 pass 文本绘制：需要时先画描边再画填充。 */
        private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, paint: TextPaint) {
            if (style.outline) {
                canvas.drawText(text, x, y, strokePaint(style, paint.textSize).apply {
                    textAlign = paint.textAlign
                    isFakeBoldText = paint.isFakeBoldText
                })
            }
            canvas.drawText(text, x, y, paint)
        }

        companion object {
            fun measure(stats: PosterStats, style: InfoStyle, moduleWidthPx: Float): InfoLayout {
                val width = moduleWidthPx.coerceAtLeast(1f)
                val namePaint = fillPaint(fillColor(style), bold = true).apply { textSize = nameSize(width) }
                val name = stats.name.ifBlank { "未命名轨迹" }
                val available = width * 0.94f
                val nameText = TextUtils.ellipsize(
                    name, namePaint, available, TextUtils.TruncateAt.END).toString()
                val (value, unit) = splitDistance(stats.distanceMeters)
                val auxColumns = if (style.showAux) buildList {
                    stats.durationText?.let { add(it to "历时") }
                    if (stats.recordDays > 0) add("${stats.recordDays}" to "天数")
                    if (stats.checkinCount > 0) add("${stats.checkinCount}" to "打卡")
                } else emptyList()
                var height = width * 0.022f
                if (style.showName) height += nameSize(width) * 1.25f + width * 0.010f
                if (style.showDate && stats.dateText != null) height += dateSize(width) * 1.35f + width * 0.034f
                height += mainSize(width) * 1.05f + width * 0.030f
                if (auxColumns.isNotEmpty()) {
                    height += auxValueSize(width) * 1.2f + width * 0.008f + auxLabelSize(width) * 1.3f + width * 0.030f
                }
                height += width * 0.024f
                return InfoLayout(stats, style, width, height, nameText, value, unit, auxColumns)
            }

            private fun splitDistance(meters: Double): Pair<String, String> {
                val formatted = formatDistance(meters)
                val index = formatted.indexOfLast { it == ' ' }
                return if (index <= 0) formatted to ""
                else formatted.substring(0, index) to formatted.substring(index + 1)
            }

            // 用户要求：轨迹名称为最大文字；品牌水印已移至画布左上角，不占模块行高。
            private fun nameSize(width: Float) = width * 0.085f
            private fun dateSize(width: Float) = width * 0.028f
            private fun mainSize(width: Float) = width * 0.075f
            private fun unitSize(width: Float) = width * 0.034f
            private fun auxValueSize(width: Float) = width * 0.034f
            private fun auxLabelSize(width: Float) = width * 0.022f

            /** 字号比例（相对模块宽度），供测试锁定层级关系。 */
            internal fun textRatios(): Map<String, Float> = mapOf(
                "name" to 0.085f,
                "main" to 0.075f,
                "unit" to 0.034f,
                "auxValue" to 0.034f,
                "date" to 0.028f,
                "auxLabel" to 0.022f,
            )

            private fun fillColor(style: InfoStyle) =
                if (style.textTheme == PosterInfoTextTheme.LIGHT) Color.WHITE else Color.rgb(24, 44, 32)

            private fun mainColor(style: InfoStyle) =
                if (style.textTheme == PosterInfoTextTheme.LIGHT) Color.WHITE else Color.rgb(20, 110, 69)

            private fun secondaryColor(style: InfoStyle) =
                if (style.textTheme == PosterInfoTextTheme.LIGHT) Color.argb(235, 255, 255, 255)
                else Color.rgb(95, 106, 100)

            internal fun fillPaint(
                color: Int,
                bold: Boolean = false,
                align: Paint.Align = Paint.Align.CENTER,
            ) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textAlign = align; isFakeBoldText = bold
            }

            internal fun strokePaint(style: InfoStyle, textSize: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                // 轻描边：与文字反色的半透明描边，保证复杂照片上的可读性（plan §9.2）。
                color = if (style.textTheme == PosterInfoTextTheme.LIGHT) Color.argb(170, 20, 32, 24)
                else Color.argb(150, 255, 255, 255)
                this.textSize = textSize
                isFakeBoldText = true // 与填充字重一致，保证描边与填充布局完全重合
                this.style = Paint.Style.STROKE
                strokeWidth = max(2f, textSize * 0.055f)
                strokeJoin = Paint.Join.ROUND
            }
        }
    }

    /** 路线模块盒子的 h/w：内容比例叠加标记留白，极扁/极高路线保留最小可读高度。 */
    fun routeModuleAspect(model: PosterRouteModel): Float {
        val contentAspect = model.bounds.spanY.toDouble().coerceAtLeast(1e-6) /
            model.bounds.spanX.toDouble().coerceAtLeast(1e-6)
        return contentAspect.toFloat().coerceIn(0.24f, 2.2f)
    }

    /** 照片模式透明路线（plan §8）：投影→等比适配→按天分色→端点与打卡标记。 */
    fun drawRouteModule(
        canvas: Canvas,
        model: PosterRouteModel,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        style: RouteStyle,
    ) {
        if (width <= 0f || height <= 0f || model.paths.isEmpty()) return
        val lineWidth = max(4f, width * 0.022f)
        // 标记与描边留白不能吃掉整个盒子：极扁/极窄路线也要保留可用内容区。
        val padding = (width * .14f).coerceAtMost(minOf(width, height) * 0.40f)
        val transform = model.fit(width.toDouble(), height.toDouble(), padding.toDouble())
        val underlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            strokeWidth = lineWidth * 2.1f
            this.style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = lineWidth
            this.style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        model.paths.forEachIndexed { index, path ->
            if (path.size < 2) return@forEachIndexed
            val color = if (style.theme == PosterRouteTheme.SINGLE) 0xFF00B96B.toInt()
            else dayColor(model.pathDayIndices.getOrElse(index) { 0 }).toInt()
            val mapped = path.map { transform.map(it.x, it.y) }
            // 单条连续 Path：圆头端点 + 圆角转折，弯道处平滑无缺口（plan §8.3）。
            val routePath = android.graphics.Path()
            mapped.forEachIndexed { pointIndex, point ->
                val x = left + point.x.toFloat()
                val y = top + point.y.toFloat()
                if (pointIndex == 0) routePath.moveTo(x, y) else routePath.lineTo(x, y)
            }
            linePaint.color = color
            canvas.drawPath(routePath, underlay)
            canvas.drawPath(routePath, linePaint)
        }
        drawMarkers(canvas, model, transform, left, top, width, height, style)
    }

    private fun drawMarkers(
        canvas: Canvas,
        model: PosterRouteModel,
        transform: PosterRouteTransform,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        style: RouteStyle,
    ) {
        val allPoints = model.paths.flatten()
        if (allPoints.isEmpty()) return
        val startPoint = allPoints.first()
        val endPoint = allPoints.last()
        val startXY = transform.map(startPoint.x, startPoint.y)
        val endXY = transform.map(endPoint.x, endPoint.y)
        val scale = minOf(width / 320f, height / 90f).coerceAtLeast(.1f)
        val reserved = mutableListOf<PosterMarkerBox>()
        val roundTrip = hypot(startXY.x - endXY.x, startXY.y - endXY.y) < scale * 10
        listOf(Triple(startPoint, startXY, true), Triple(endPoint, endXY, false)).forEach { (point, xy, start) ->
            val name = if (style.showEndpointNames) PosterMapMarkers.endpointName(point.point,
                if (start) style.endpointNames.start else style.endpointNames.end) else null
            val art = posterMarkerArtwork(if (start) "起" else "终", name,
                if (start) START_COLOR else END_COLOR, scale, if (roundTrip) start else xy.x > width / 2,
                if (roundTrip) if (start) -18f else 18f else 0f)
            val x = xy.x.toFloat() - art.anchorU * art.bitmap.width
            val y = xy.y.toFloat() - art.anchorV * art.bitmap.height
            canvas.drawBitmap(art.bitmap, left + x, top + y, null)
            reserved.add(PosterMarkerBox(x / scale - 8, y / scale - 8,
                (x + art.bitmap.width) / scale + 8, (y + art.bitmap.height) / scale + 8))
            art.bitmap.recycle()
        }
        if (!style.showCheckins) return
        val mapped = allPoints.distinctBy { it.point.id }.map { it to transform.map(it.x, it.y) }
        val candidates = mapped.filter { it.first.point.isCheckin }.mapIndexedNotNull { index, (point, xy) ->
            if (point.point.id == startPoint.point.id || point.point.id == endPoint.point.id) null
            else PosterMarkerCandidate(point.point, xy.x.toFloat() / scale, xy.y.toFloat() / scale, index + 1)
        }
        val route = mapped.map { it.second.x.toFloat() / scale to it.second.y.toFloat() / scale }
        PosterMapMarkers.select(candidates, reserved, route, width / scale, height / scale).forEach {
            val art = posterMarkerArtwork(it.order.toString(), null, 0xFF303735.toInt(), scale)
            canvas.drawBitmap(art.bitmap, left + it.x * scale - art.anchorU * art.bitmap.width,
                top + it.y * scale - art.anchorV * art.bitmap.height, null)
            art.bitmap.recycle()
        }
    }

    /** 照片模式合成：裁剪照片铺满画布 → 透明路线 → 信息模块 → 品牌水印（plan §11.1）。 */
    fun renderPhotoPoster(
        spec: PosterCanvasSpec,
        document: PosterDocument,
        snapshot: PosterTrackSnapshot,
        routeModel: PosterRouteModel,
        photo: Bitmap,
        logo: android.graphics.drawable.Drawable? = null,
        endpointNames: PosterEndpointNames = PosterEndpointNames(),
    ): Bitmap {
        val widthPx = spec.widthPx.toFloat()
        val heightPx = spec.heightPx.toFloat()
        val output = Bitmap.createBitmap(spec.widthPx, spec.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val photoState = requireNotNull(document.photoState) { "照片模式缺少照片" }
        val cropDraw = PosterCrop.draw(photoState.crop, photo.width.toFloat(), photo.height.toFloat(), widthPx, heightPx)
        val matrix = Matrix().apply {
            setScale(cropDraw.scale, cropDraw.scale)
            postTranslate(cropDraw.transX, cropDraw.transY)
        }
        canvas.drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        document.photoRouteState?.let { route ->
            val box = PosterLayout.moduleRect(route.placement, routeModuleAspect(routeModel))
            drawRouteModule(canvas, routeModel, box.left * widthPx, box.top * heightPx,
                box.width * widthPx, box.height * heightPx,
                RouteStyle(route.theme, route.showCheckinMarkers, route.showEndpointNames, endpointNames))
        }
        drawInfoModule(canvas, document.photoInfoState, snapshot, widthPx, heightPx)
        drawBrandCorner(canvas, logo,
            document.photoInfoState?.textTheme ?: PosterInfoTextTheme.LIGHT, widthPx, heightPx)
        return output
    }

    /** The map and its attribution occupy the upper region; summary uses its own lower band. */
    fun renderMapPoster(
        spec: PosterCanvasSpec,
        document: PosterDocument,
        snapshot: PosterTrackSnapshot,
        map: Bitmap,
        logo: android.graphics.drawable.Drawable? = null,
    ): Bitmap {
        val widthPx = spec.widthPx.toFloat()
        val heightPx = spec.heightPx.toFloat()
        val output = Bitmap.createBitmap(spec.widthPx, spec.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        // 信息带区域也要有底色：整张画布被背景覆盖（plan §11.1），避免导出 PNG 出现透明区。
        canvas.drawColor(Color.WHITE)
        val infoState = document.mapInfoState
        val infoAspect = infoState?.let {
            InfoLayout.measure(snapshot.stats,
                InfoStyle(it.textTheme, showName = it.showName, showDate = it.showDate, showAux = it.showAux),
                it.placement.width * widthPx).aspect
        } ?: 0.4f
        val (infoRegion, mapRegion) = PosterLayout.mapModeRegions(
            infoState?.placement?.width ?: 0.8f, infoAspect, spec.widthPx, spec.heightPx)
        canvas.drawBitmap(map, null, RectF(
            mapRegion.left * widthPx, mapRegion.top * heightPx,
            mapRegion.right * widthPx, mapRegion.bottom * heightPx), Paint(Paint.FILTER_BITMAP_FLAG))
        drawMapSummary(canvas, snapshot.stats, infoState, infoRegion.left * widthPx,
            infoRegion.top * heightPx, infoRegion.width * widthPx, infoRegion.height * heightPx, logo)
        return output
    }

    fun drawMapSummary(
        canvas: Canvas, stats: PosterStats, info: PosterInfoModuleState?,
        left: Float, top: Float, width: Float, height: Float,
        logo: android.graphics.drawable.Drawable? = null,
    ) {
        val light = info?.textTheme == PosterInfoTextTheme.LIGHT
        val ink = if (light) Color.WHITE else Color.rgb(29, 33, 32)
        val muted = if (light) Color.rgb(185, 193, 190) else Color.rgb(125, 132, 128)
        canvas.save()
        canvas.clipRect(left, top, left + width, top + height)
        canvas.translate(left, top)
        canvas.drawColor(if (light) Color.rgb(28, 34, 31) else Color.WHITE)
        val margin = width * .045f
        fun line(text: String, x: Float, baseline: Float, size: Float, bold: Boolean = false,
                 color: Int = ink, available: Float = width - 2 * margin, align: Paint.Align = Paint.Align.LEFT) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.color = color; isFakeBoldText = bold; textAlign = align
            }
            val fitted = TextUtils.ellipsize(text, paint, available, TextUtils.TruncateAt.END).toString()
            canvas.drawText(fitted, x, baseline, paint)
        }
        val brandSize = width * .027f
        val logoSize = width * .041f
        logo?.let {
            it.setBounds(margin.toInt(), (height * .055f).toInt(),
                (margin + logoSize).toInt(), (height * .055f + logoSize).toInt())
            it.draw(canvas)
        }
        line(BRAND, margin + if (logo == null) 0f else logoSize + width * .012f,
            height * .055f + logoSize * .72f, brandSize, true)
        if (info?.showName != false) line(stats.name.ifBlank { "未命名轨迹" }, margin, height * .28f,
            width * .043f, true, available = width * .53f)
        if (info?.showDate != false) stats.dateText?.let {
            line(it, width - margin, height * .28f, width * .024f, color = muted,
                available = width * .36f, align = Paint.Align.RIGHT)
        }
        val formatted = formatDistance(stats.distanceMeters)
        val split = formatted.lastIndexOf(' ')
        val number = if (split < 0) formatted else formatted.substring(0, split)
        val unit = if (split < 0) "" else formatted.substring(split + 1)
        val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; textSize = width * .105f; isFakeBoldText = true
        }
        val maxValueWidth = width * .74f
        if (valuePaint.measureText(number) > maxValueWidth) valuePaint.textSize *= maxValueWidth / valuePaint.measureText(number)
        canvas.drawText(number, margin, height * .60f, valuePaint)
        line(unit, margin + valuePaint.measureText(number) + width * .012f, height * .60f,
            width * .038f, true, available = width * .16f)
        if (info?.showAux != false) {
            val values = listOf((stats.durationText ?: "--") to "旅行历时",
                "${stats.recordDays}" to "记录天数", "${stats.checkinCount}" to "打卡次数")
            values.forEachIndexed { index, (value, label) ->
                val x = width * (index + .5f) / 3f
                line(value, x, height * .82f, width * .041f, true,
                    available = width * .29f, align = Paint.Align.CENTER)
                line(label, x, height * .94f, width * .026f, color = muted,
                    available = width * .29f, align = Paint.Align.CENTER)
            }
        }
        canvas.restore()
    }

    /**
     * 品牌水印（用户要求）：固定画布左上角 = 圆形裁剪的 App logo + 「旅行牛牛」；
     * 颜色随信息主题（白字/黑字），预览与导出共用。
     */
    fun drawBrandCorner(
        canvas: Canvas,
        logo: android.graphics.drawable.Drawable?,
        theme: PosterInfoTextTheme,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
    ) {
        val base = canvasWidthPx.coerceAtMost(canvasHeightPx)
        val margin = base * 0.038f
        val logoSize = base * 0.052f
        val textSize = base * 0.028f
        val textGap = base * 0.014f
        if (logo != null) {
            canvas.save()
            val circle = android.graphics.Path().apply {
                addCircle(margin + logoSize / 2f, margin + logoSize / 2f, logoSize / 2f, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(circle)
            logo.setBounds(margin.toInt(), margin.toInt(),
                (margin + logoSize).toInt(), (margin + logoSize).toInt())
            logo.draw(canvas)
            canvas.restore()
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = base * 0.0028f
                color = if (theme == PosterInfoTextTheme.LIGHT) Color.argb(200, 255, 255, 255) else Color.argb(160, 24, 44, 32)
            }
            canvas.drawCircle(margin + logoSize / 2f, margin + logoSize / 2f, logoSize / 2f, ring)
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (theme == PosterInfoTextTheme.LIGHT) Color.argb(240, 255, 255, 255) else Color.rgb(24, 44, 32)
            this.textSize = textSize
            textAlign = Paint.Align.LEFT
            isFakeBoldText = true
        }
        val baseline = margin + logoSize / 2f + textSize * 0.34f
        val stroke = TextPaint(paint).apply {
            color = if (theme == PosterInfoTextTheme.LIGHT) Color.argb(170, 20, 32, 24) else Color.argb(150, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = max(2f, textSize * 0.06f)
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawText(BRAND, margin + logoSize + textGap, baseline, stroke)
        canvas.drawText(BRAND, margin + logoSize + textGap, baseline, paint)
    }

    private fun drawInfoModule(
        canvas: Canvas,
        state: PosterInfoModuleState?,
        snapshot: PosterTrackSnapshot,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
    ) {
        state ?: return
        val layout = InfoLayout.measure(snapshot.stats, InfoStyle(state.textTheme), state.placement.width * canvasWidthPx)
        layout.draw(canvas,
            state.placement.centerX * canvasWidthPx - state.placement.width * canvasWidthPx / 2f,
            state.placement.centerY * canvasHeightPx - layout.heightPx / 2f)
    }

    /** 先写临时文件，编码成功后原子重命名；失败删除半成品（plan §11.3）。 */
    fun writePng(bitmap: Bitmap, file: File): File {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
            }
            check(temp.renameTo(file)) { "写入文件失败" }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
        return file
    }
}
