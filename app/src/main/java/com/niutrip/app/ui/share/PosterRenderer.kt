package com.niutrip.app.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import com.niutrip.app.core.formatDistance
import java.io.File
import kotlin.math.max
import kotlin.math.pow

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
        val fontScale: Float = 1f,
        val titleFontScale: Float = fontScale,
        val titleColor: PosterTextColor? = null,
        val statsColor: PosterTextColor? = null,
    )

    fun infoStyle(state: PosterInfoModuleState) = InfoStyle(
        state.textTheme, showName = state.showName, showDate = state.showDate, showAux = state.showAux,
        fontScale = state.fontScale, titleFontScale = state.titleFontScale,
        titleColor = state.titleColor, statsColor = state.statsColor)
    data class RouteStyle(val theme: PosterRouteTheme, val showCheckins: Boolean,
                          val showEndpointNames: Boolean = true,
                          val endpointNames: PosterEndpointNames = PosterEndpointNames(),
                          val preferredCheckins: Map<String, String> = emptyMap(),
                          val showMapOutline: Boolean = true,
                          val outlineTheme: PosterInfoTextTheme = PosterInfoTextTheme.LIGHT,
                          val selectedCheckinIds: Set<String>? = null)

    private const val BRAND = "旅行牛牛"

    /** Transparent editorial summary: rule, title/date, distance, then three statistics. */
    class InfoLayout private constructor(
        private val stats: PosterStats,
        private val style: InfoStyle,
        private val width: Float,
        internal val heightPx: Float,
        internal val nameText: String,
        private val distanceValue: String,
        private val distanceUnit: String,
        internal val auxColumns: List<Pair<String, String>>,
    ) {
        val aspect: Float get() = if (width > 0f) heightPx / width else 0f

        fun draw(canvas: Canvas, left: Float, top: Float) {
            val checkpoint = canvas.save()
            canvas.clipRect(left, top, left + width, top + heightPx)
            val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = secondaryColor(this@InfoLayout.style, header = true); strokeWidth = max(1f, width * .002f)
            }
            canvas.drawLine(left + pad(), top + headerGap(.008f), left + width - pad(), top + headerGap(.008f), rule)
            var y = top + headerGap(.035f)
            if (style.showName) {
                val paint = fillPaint(fillColor(style, header = true), bold = true, align = Paint.Align.LEFT)
                    .apply { textSize = nameSize(width) * titleScale() }
                y += paint.textSize
                drawText(canvas, nameText, left + pad(), y, paint)
                y += headerGap(.02f)
            }
            if (style.showDate) {
                stats.dateText?.let { date ->
                    val paint = fillPaint(secondaryColor(style, header = true), align = Paint.Align.LEFT)
                        .apply { textSize = dateSize(width) * titleScale() }
                    fit(paint, date, width - pad() * 2)
                    y += dateSize(width) * titleScale()
                    drawText(canvas, date, left + pad(), y, paint)
                    y += headerGap(.025f)
                }
            }
            val label = fillPaint(secondaryColor(style), align = Paint.Align.LEFT).apply { textSize = gap(.03f) }
            y += gap(.03f)
            drawText(canvas, "行程总里程", left + pad(), y, label)
            y += gap(.025f)
            val mainPaint = fillPaint(fillColor(style), bold = true, align = Paint.Align.LEFT)
                .apply { textSize = mainSize(width) * scale() }
            val unitPaint = fillPaint(fillColor(style), bold = true, align = Paint.Align.LEFT)
                .apply { textSize = unitSize(width) * scale() }
            val unitGap = width * 0.016f
            val available = width - pad() * 2
            val total = mainPaint.measureText(distanceValue) + unitGap + unitPaint.measureText(distanceUnit)
            if (total > available) {
                val fit = available / total
                mainPaint.textSize *= fit
                unitPaint.textSize *= fit
            }
            val mainWidth = mainPaint.measureText(distanceValue)
            val startX = left + pad()
            val baseline = y + mainSize(width) * scale()
            drawText(canvas, distanceValue, startX, baseline, mainPaint)
            if (distanceUnit.isNotEmpty()) {
                drawText(canvas, distanceUnit, startX + mainWidth + unitGap, baseline, unitPaint)
            }
            y = baseline + gap(.035f)
            if (auxColumns.isNotEmpty()) {
                val columnWidth = (width - pad() * 2) / auxColumns.size
                auxColumns.forEachIndexed { index, (value, label) ->
                    val centerX = left + pad() + columnWidth * index
                    val valuePaint = fillPaint(fillColor(style), bold = true, align = Paint.Align.LEFT)
                        .apply { textSize = auxValueSize(width) * scale() }
                    val columnAvailable = columnWidth * 0.92f
                    val measured = valuePaint.measureText(value)
                    if (measured > columnAvailable) {
                        valuePaint.textSize = valuePaint.textSize * columnAvailable / measured
                    }
                    drawText(canvas, value, centerX, y + auxValueSize(width) * scale(), valuePaint)
                    val labelPaint = fillPaint(secondaryColor(style), align = Paint.Align.LEFT).apply { textSize = auxLabelSize(width) * scale() }
                    fit(labelPaint, label, columnAvailable)
                    drawText(canvas, label, centerX, y + auxValueSize(width) * scale() + gap(0.018f) + auxLabelSize(width) * scale(), labelPaint)
                }
            }
            canvas.restoreToCount(checkpoint)
        }

        private fun pad(): Float = width * 0.03f
        private fun scale(): Float = style.fontScale.coerceIn(.8f, 1.6f)
        private fun titleScale(): Float = style.titleFontScale.coerceIn(.8f, 1.6f)
        private fun gap(ratio: Float): Float = width * ratio * scale()
        private fun headerGap(ratio: Float): Float = width * ratio * titleScale()
        private fun fit(paint: TextPaint, text: String, available: Float) {
            val measured = paint.measureText(text)
            if (measured > available) paint.textSize *= available / measured
        }

        /** 双 pass 文本绘制：需要时先画描边再画填充。 */
        private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, paint: TextPaint) {
            if (style.outline) {
                canvas.drawText(text, x, y, strokePaint(style, paint.textSize, paint.color).apply {
                    textAlign = paint.textAlign
                    isFakeBoldText = paint.isFakeBoldText
                })
            }
            canvas.drawText(text, x, y, paint)
        }

        companion object {
            fun measure(stats: PosterStats, style: InfoStyle, moduleWidthPx: Float): InfoLayout {
                val width = moduleWidthPx.coerceAtLeast(1f)
                val scale = style.fontScale.coerceIn(.8f, 1.6f)
                val titleScale = style.titleFontScale.coerceIn(.8f, 1.6f)
                val namePaint = fillPaint(fillColor(style, header = true), bold = true).apply { textSize = nameSize(width) * titleScale }
                val name = stats.name.replace(Regex("\\s+"), " ").trim().ifBlank { "未命名轨迹" }
                val available = width * .94f
                val nameText = if (namePaint.measureText(name) <= available) name else {
                    val suffix = "…"
                    var end = namePaint.breakText(name, true, available - namePaint.measureText(suffix), null)
                    if (end > 0 && name[end - 1].isHighSurrogate()) end--
                    name.take(end) + suffix
                }
                val (value, unit) = splitDistance(stats.distanceMeters)
                val auxColumns = if (style.showAux) buildList {
                    stats.durationText?.let { add(it to "全程耗时") }
                    if (stats.recordDays > 0) add("${stats.recordDays}" to "记录天数")
                    if (stats.checkinCount > 0) add("${stats.checkinCount}" to "打卡点")
                } else emptyList()
                val headerHeight = width * titleScale * (.035f +
                    (if (style.showName) .084f else 0f) +
                    (if (style.showDate && stats.dateText != null) .059f else 0f))
                val height = headerHeight + width * scale * (
                    .03f + .025f + .16f + .035f +
                    (if (auxColumns.isEmpty()) .01f else .058f + .018f + .034f + .025f))
                return InfoLayout(stats, style, width, height, nameText, value, unit, auxColumns)
            }

            private fun splitDistance(meters: Double): Pair<String, String> {
                val formatted = formatDistance(meters)
                val index = formatted.indexOfLast { it == ' ' }
                return if (index <= 0) formatted to ""
                else formatted.substring(0, index) to formatted.substring(index + 1)
            }

            private fun nameSize(width: Float) = width * 0.064f
            private fun dateSize(width: Float) = width * 0.034f
            private fun mainSize(width: Float) = width * 0.16f
            private fun unitSize(width: Float) = width * 0.042f
            private fun auxValueSize(width: Float) = width * 0.058f
            private fun auxLabelSize(width: Float) = width * 0.034f

            /** 字号比例（相对模块宽度），供测试锁定层级关系。 */
            internal fun textRatios(): Map<String, Float> = mapOf(
                "name" to 0.064f,
                "main" to 0.16f,
                "unit" to 0.042f,
                "auxValue" to 0.058f,
                "date" to 0.034f,
                "auxLabel" to 0.034f,
            )

            private fun fillColor(style: InfoStyle, header: Boolean = false) =
                (if (header) style.titleColor else style.statsColor)?.argb
                    ?: if (style.textTheme == PosterInfoTextTheme.LIGHT) Color.WHITE else Color.rgb(32, 34, 36)

            private fun secondaryColor(style: InfoStyle, header: Boolean = false): Int {
                val custom = if (header) style.titleColor else style.statsColor
                return custom?.argb ?: if (style.textTheme == PosterInfoTextTheme.LIGHT) Color.argb(235, 255, 255, 255)
                else Color.rgb(88, 91, 94)
            }

            internal fun fillPaint(
                color: Int,
                bold: Boolean = false,
                align: Paint.Align = Paint.Align.CENTER,
            ) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textAlign = align; isFakeBoldText = bold
            }

            internal fun strokePaint(style: InfoStyle, textSize: Float, fill: Int = fillColor(style)) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                // 轻描边：与文字反色的半透明描边，保证复杂照片上的可读性（plan §9.2）。
                color = if (relativeLuminance(fill) > .45) Color.argb(140, 24, 24, 24)
                else Color.argb(150, 255, 255, 255)
                this.textSize = textSize
                isFakeBoldText = true // 与填充字重一致，保证描边与填充布局完全重合
                this.style = Paint.Style.STROKE
                strokeWidth = max(.5f, textSize * 0.025f)
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

    /** Keep the existing transparent contours, then draw the shared map-style route layer. */
    fun drawRouteModule(
        canvas: Canvas,
        model: PosterRouteModel,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        style: RouteStyle,
        checkinIcon: Bitmap,
        outline: PosterMapOutline? = null,
    ): PosterWireframeRenderer.AnnotationReport {
        if (width <= 0f || height <= 0f || model.paths.isEmpty()) return PosterWireframeRenderer.AnnotationReport()
        val padding = (width * .14f).coerceAtMost(minOf(width, height) * .40f)
        val transform = if (style.showMapOutline && outline != null) outline.photoTransform(model, width, height, padding)
            else model.fit(width.toDouble(), height.toDouble(), padding.toDouble())
        if (style.showMapOutline) outline?.draw(canvas, model, transform, left, top, width, height,
            style.outlineTheme == PosterInfoTextTheme.LIGHT, attribution = false)
        val scale = width / PosterRouteLayerRenderer.REFERENCE_WIDTH
        val logicalWidth = PosterRouteLayerRenderer.REFERENCE_WIDTH
        val logicalHeight = height / scale
        val logicalTransform = if (style.showMapOutline && outline != null)
            outline.photoTransform(model, logicalWidth, logicalHeight, padding / scale)
        else model.fit(logicalWidth.toDouble(), logicalHeight.toDouble(), (padding / scale).toDouble())
        val saved = canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, logicalWidth, logicalHeight)
        val light = style.outlineTheme == PosterInfoTextTheme.LIGHT
        val report = PosterRouteLayerRenderer.draw(canvas, model, logicalTransform, logicalWidth, logicalHeight,
            PosterRouteStyleState(style.theme, style.showCheckins, style.showEndpointNames,
                style.preferredCheckins, style.selectedCheckinIds), style.endpointNames, checkinIcon,
            if (light) Color.WHITE else PosterRouteLayerRenderer.INK,
            if (light) Color.argb(220, 20, 28, 32) else Color.WHITE,
            routeUnderlayColor = Color.argb(230, 255, 255, 255))
        canvas.restoreToCount(saved)
        return report
    }

    /** 照片模式合成：裁剪照片铺满画布 → 透明路线 → 信息模块 → 品牌水印（plan §11.1）。 */
    fun renderPhotoPoster(
        spec: PosterCanvasSpec,
        document: PosterDocument,
        snapshot: PosterTrackSnapshot,
        routeModel: PosterRouteModel,
        photo: Bitmap,
        checkinIcon: Bitmap,
        logo: android.graphics.drawable.Drawable? = null,
        endpointNames: PosterEndpointNames = PosterEndpointNames(),
        outline: PosterMapOutline? = null,
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
                RouteStyle(route.theme, route.showCheckinMarkers, route.showEndpointNames, endpointNames,
                    route.preferredCheckins, route.showMapOutline,
                    document.photoInfoState?.textTheme ?: PosterInfoTextTheme.LIGHT, route.selectedCheckinIds), checkinIcon, outline)
        }
        drawInfoModule(canvas, document.photoInfoState, snapshot, widthPx, heightPx)
        drawBrandCorner(canvas, logo, photoBrandTheme(photo, photoState.crop, widthPx, heightPx), widthPx, heightPx)
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

    fun renderWireframePoster(spec: PosterCanvasSpec, document: PosterDocument,
                             snapshot: PosterTrackSnapshot, model: PosterRouteModel,
                             names: PosterEndpointNames, outline: PosterMapOutline, checkinIcon: Bitmap): Bitmap {
        val bitmap = Bitmap.createBitmap(spec.widthPx, spec.heightPx, Bitmap.Config.ARGB_8888)
        PosterWireframeRenderer.draw(Canvas(bitmap), spec.widthPx.toFloat(), spec.heightPx.toFloat(),
            document, snapshot, model, names, outline, checkinIcon)
        return bitmap
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

    private fun relativeLuminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(Color.red(color)) + .7152 * linear(Color.green(color)) + .0722 * linear(Color.blue(color))
    }

    /** Sample the photo underneath the brand text, using the same crop as preview/export. */
    internal fun photoBrandTheme(photo: Bitmap, crop: PhotoCrop, width: Float, height: Float): PosterInfoTextTheme {
        val draw = PosterCrop.draw(crop, photo.width.toFloat(), photo.height.toFloat(), width, height)
        val base = minOf(width, height)
        val textStart = base * (.038f + .052f + .014f)
        val textWidth = TextPaint().apply { textSize = base * .028f; isFakeBoldText = true }.measureText(BRAND)
        var luminance = 0.0
        for (row in 0 until 8) for (column in 0 until 24) {
            val x = textStart + textWidth * (column + .5f) / 24f
            val y = base * (.038f + .052f / 2f - .028f * .7f + .028f * (row + .5f) / 8f)
            val sourceX = ((x - draw.transX) / draw.scale).toInt().coerceIn(0, photo.width - 1)
            val sourceY = ((y - draw.transY) / draw.scale).toInt().coerceIn(0, photo.height - 1)
            luminance += relativeLuminance(photo.getPixel(sourceX, sourceY))
        }
        val average = luminance / (8 * 24)
        return if ((average + .05) / .05 >= 1.05 / (average + .05)) PosterInfoTextTheme.DARK else PosterInfoTextTheme.LIGHT
    }

    /** Fixed top-left brand, with a contrasting outline for detailed photo backgrounds. */
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
            color = if (theme == PosterInfoTextTheme.LIGHT) Color.WHITE else Color.BLACK
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
        val layout = InfoLayout.measure(snapshot.stats,
            infoStyle(state),
            state.placement.width * canvasWidthPx)
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
