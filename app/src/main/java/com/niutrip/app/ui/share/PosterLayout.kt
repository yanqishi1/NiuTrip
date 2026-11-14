package com.niutrip.app.ui.share

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 海报画布、比例、坐标变换、边界约束与默认布局（plan §6，纯逻辑）。 */

enum class PosterRatioKind(val label: String) { R3_4("3:4"), SQUARE("1:1"), R9_16("9:16"), ORIGINAL("原图") }

data class PosterCanvasSpec(val kind: PosterRatioKind, val widthPx: Int, val heightPx: Int) {
    companion object {
        /** 原图比例：以宽 1080 px 推算高度，长边超过 2160 px 时等比缩小到长边 2160 px。 */
        fun create(kind: PosterRatioKind, photoWidth: Int = 0, photoHeight: Int = 0): PosterCanvasSpec {
            val (width, height) = when (kind) {
                PosterRatioKind.R3_4 -> 1080 to 1440
                PosterRatioKind.SQUARE -> 1080 to 1080
                PosterRatioKind.R9_16 -> 1080 to 1920
                PosterRatioKind.ORIGINAL -> {
                    require(photoWidth > 0 && photoHeight > 0) { "原图比例需要照片尺寸" }
                    val width = 1080f
                    val height = width * photoHeight / photoWidth
                    if (height > 2160f) {
                        val factor = 2160f / height
                        (width * factor).roundToInt() to 2160
                    } else {
                        1080 to height.roundToInt()
                    }
                }
            }
            return PosterCanvasSpec(kind, width, height)
        }
    }
}

/** 归一化矩形（相对画布 0..1）。 */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** 模块只保存中心点与归一化宽度；高度由固有比例推导（plan §6.2）。 */
data class ModulePlacement(val centerX: Float, val centerY: Float, val width: Float)

/** 照片裁剪参数：zoom 相对基础缩放（>=1），offset 为图片中心相对画布中心的归一化偏移（画布单位）。 */
data class PhotoCrop(val zoom: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f)

data class CropDraw(val scale: Float, val transX: Float, val transY: Float, val drawW: Float, val drawH: Float)

/** 照片模式布局预设（plan §6.5）。 */
enum class PosterPresetKind { TOP_BOTTOM, LEFT_RIGHT }

object PosterCrop {
    const val MAX_ZOOM = 4f

    /** zoom 限制在 1..4；平移限制到画布始终被图片完整覆盖，不能露出空白。 */
    fun clamp(crop: PhotoCrop, imageW: Float, imageH: Float, canvasW: Float, canvasH: Float): PhotoCrop {
        val zoom = crop.zoom.coerceIn(1f, MAX_ZOOM)
        val scale = baseScale(imageW, imageH, canvasW, canvasH) * zoom
        val drawW = imageW * scale
        val drawH = imageH * scale
        val maxOffX = if (drawW > canvasW) (drawW - canvasW) / 2f / canvasW else 0f
        val maxOffY = if (drawH > canvasH) (drawH - canvasH) / 2f / canvasH else 0f
        return PhotoCrop(
            zoom = zoom,
            offsetX = crop.offsetX.coerceIn(-maxOffX, maxOffX),
            offsetY = crop.offsetY.coerceIn(-maxOffY, maxOffY),
        )
    }

    /** baseScale = max(W/Iw, H/Ih)；scale = baseScale * zoom；预览与导出复用同一算式。 */
    fun draw(crop: PhotoCrop, imageW: Float, imageH: Float, canvasW: Float, canvasH: Float): CropDraw {
        val scale = baseScale(imageW, imageH, canvasW, canvasH) * crop.zoom
        val drawW = imageW * scale
        val drawH = imageH * scale
        return CropDraw(
            scale = scale,
            transX = canvasW / 2f + crop.offsetX * canvasW - drawW / 2f,
            transY = canvasH / 2f + crop.offsetY * canvasH - drawH / 2f,
            drawW = drawW,
            drawH = drawH,
        )
    }

    /** 按双指焦点缩放时保持焦点处内容稳定（plan §7.2）。 */
    fun zoomAbout(
        crop: PhotoCrop,
        targetZoom: Float,
        focusCanvasX: Float,
        focusCanvasY: Float,
        imageW: Float,
        imageH: Float,
        canvasW: Float,
        canvasH: Float,
    ): PhotoCrop {
        val zoom = targetZoom.coerceIn(1f, MAX_ZOOM)
        val current = draw(crop, imageW, imageH, canvasW, canvasH)
        val contentX = (focusCanvasX - current.transX) / current.scale
        val contentY = (focusCanvasY - current.transY) / current.scale
        val next = draw(PhotoCrop(zoom, crop.offsetX, crop.offsetY), imageW, imageH, canvasW, canvasH)
        val transX = focusCanvasX - contentX * next.scale
        val transY = focusCanvasY - contentY * next.scale
        val offX = (transX + next.drawW / 2f - canvasW / 2f) / canvasW
        val offY = (transY + next.drawH / 2f - canvasH / 2f) / canvasH
        return clamp(PhotoCrop(zoom, offX, offY), imageW, imageH, canvasW, canvasH)
    }

    private fun baseScale(imageW: Float, imageH: Float, canvasW: Float, canvasH: Float): Float =
        max(canvasW / imageW, canvasH / imageH)
}

object PosterLayout {
    /** 普通安全边距为画布短边的 3%；地图模式再扣除 SDK 标识、版权和比例尺的底部保留区域。 */
    fun safeArea(reservedBottom: Float = 0f): NormRect {
        val margin = 0.03f
        return NormRect(margin, margin, 1f - margin, 1f - margin - reservedBottom)
    }

    /** aspect 为高度/宽度；高度由宽度与固有比例推导，不单独保存。 */
    fun moduleRect(placement: ModulePlacement, aspect: Float): NormRect {
        val halfW = placement.width / 2f
        val halfH = placement.width * aspect / 2f
        return NormRect(
            left = placement.centerX - halfW,
            top = placement.centerY - halfH,
            right = placement.centerX + halfW,
            bottom = placement.centerY + halfH,
        )
    }

    /** 模块大于可用区域时先缩小到允许尺寸，再按完整边界（含描边、标记、留白推导后的框）钳制中心。 */
    fun clampPlacement(
        placement: ModulePlacement,
        aspect: Float,
        safe: NormRect,
        minWidth: Float = 0.08f,
    ): ModulePlacement {
        val maxByWidth = safe.width
        val maxByHeight = if (aspect > 0f) safe.height / aspect else maxByWidth
        val maxWidth = max(minWidth, min(maxByWidth, maxByHeight))
        val width = placement.width.coerceIn(minWidth, maxWidth)
        val halfW = width / 2f
        val halfH = width * aspect / 2f
        return ModulePlacement(
            centerX = placement.centerX.coerceIn(safe.left + halfW, safe.right - halfW),
            centerY = placement.centerY.coerceIn(safe.top + halfH, safe.bottom - halfH),
            width = width,
        )
    }

    /** “上下布局”：信息在上方、路线在下方（照片模式默认，plan §6.5）。 */
    fun defaultPhotoPlacements(
        routeAspect: Float,
        infoAspect: Float,
        safe: NormRect,
    ): Pair<ModulePlacement, ModulePlacement> {
        val gap = 0.03f
        val routeMaxHeight = safe.height * 0.62f
        val routeWidth = min(safe.width * 0.96f, if (routeAspect > 0f) routeMaxHeight / routeAspect else safe.width)
        val routeHeight = routeWidth * routeAspect
        val infoMaxHeight = (safe.height - routeHeight - gap).coerceAtLeast(0f)
        val infoWidth = min(safe.width * 0.9f, if (infoAspect > 0f) infoMaxHeight / infoAspect else safe.width)
        val infoHeight = infoWidth * infoAspect
        val info = ModulePlacement(
            centerX = (safe.left + safe.right) / 2f,
            centerY = safe.top + infoHeight / 2f,
            width = infoWidth,
        )
        val route = ModulePlacement(
            centerX = (safe.left + safe.right) / 2f,
            centerY = safe.bottom - routeHeight / 2f,
            width = routeWidth,
        )
        return info to route
    }

    /** 地图模式默认信息宽度（位置由 [mapModeRegions] 派生，不再自由摆放）。 */
    fun defaultMapPlacement(infoAspect: Float, safe: NormRect): ModulePlacement {
        val maxHeight = safe.height * 0.38f
        val width = min(safe.width * 0.9f, if (infoAspect > 0f) maxHeight / infoAspect else safe.width)
        val height = width * infoAspect
        return ModulePlacement(
            centerX = (safe.left + safe.right) / 2f,
            centerY = safe.top + height / 2f,
            width = width,
        )
    }

    /** Map first, compact summary below; the whole SDK image remains unobscured. */
    fun mapModeRegions(
        infoWidth: Float,
        infoAspect: Float,
        canvasWidthPx: Int,
        canvasHeightPx: Int,
    ): Pair<NormRect, NormRect> {
        val panelHeight = (canvasWidthPx * 0.46f / canvasHeightPx).coerceIn(0.25f, 0.40f)
        val info = NormRect(0f, 1f - panelHeight, 1f, 1f)
        val map = NormRect(0f, 0f, 1f, info.top)
        return info to map
    }

    // ---------- 布局预设（plan §6.5 / 阶段 4） ----------

    /** “左右布局”：路线左上、信息右上，接近参考图；空间不足时按可用区域缩小（plan §6.5）。 */
    fun photoPresetPlacements(
        kind: PosterPresetKind,
        routeAspect: Float,
        infoAspect: Float,
        safe: NormRect,
    ): Pair<ModulePlacement, ModulePlacement> {
        if (kind == PosterPresetKind.TOP_BOTTOM) return defaultPhotoPlacements(routeAspect, infoAspect, safe)
        val gap = 0.025f
        val routeMaxWidth = safe.width * 0.56f
        val routeWidth = min(routeMaxWidth,
            if (routeAspect > 0f) safe.height * 0.84f / routeAspect else routeMaxWidth)
        val routeHeight = routeWidth * routeAspect
        val infoMaxWidth = (safe.width - routeWidth - gap * 2).coerceAtLeast(safe.width * 0.2f)
        val infoWidth = min(infoMaxWidth,
            if (infoAspect > 0f) safe.height * 0.5f / infoAspect else infoMaxWidth)
        val infoHeight = infoWidth * infoAspect
        val info = ModulePlacement(
            centerX = safe.right - gap - infoWidth / 2f,
            centerY = safe.top + infoHeight / 2f,
            width = infoWidth,
        )
        val route = ModulePlacement(
            centerX = safe.left + gap + routeWidth / 2f,
            centerY = safe.top + routeHeight / 2f,
            width = routeWidth,
        )
        return info to route
    }

    // ---------- 吸附参考线（plan §6.4 / 阶段 4） ----------

    /** 单轴吸附结果：吸附后的位置与参考线位置（未吸附时为 null）。 */
    data class SnapGuides(val x: Float?, val y: Float?)

    data class SnapResult(val placement: ModulePlacement, val guides: SnapGuides)

    /**
     * 吸附阈值（归一化画布单位）与已锁定的参考线；锁定后需超过
     * [releaseFactor] 倍阈值才脱离，避免边缘来回抖动（plan §6.4）。
     */
    data class SnapOptions(
        val thresholdX: Float,
        val thresholdY: Float,
        val releaseFactor: Float = 2.2f,
        val lockedX: Float? = null,
        val lockedY: Float? = null,
    )

    /** 接近画布中心、安全边缘或另一模块边缘时对齐并输出参考线（plan §6.4）。 */
    fun snapPlacement(
        placement: ModulePlacement,
        aspect: Float,
        safe: NormRect,
        other: NormRect?,
        options: SnapOptions,
    ): SnapResult {
        val rect = moduleRect(placement, aspect)
        var shiftX = 0f
        var shiftY = 0f
        var guideX: Float? = null
        var guideY: Float? = null

        fun snapAxis(
            candidates: List<Float>,
            targets: List<Float>,
            threshold: Float,
            locked: Float?,
        ): Pair<Float, Float?> {
            // 先看锁定目标：仍在脱离阈值内则保持对齐。
            if (locked != null) {
                val lockedDelta = candidates.minOf { kotlin.math.abs(it - locked) }
                if (lockedDelta <= threshold * options.releaseFactor) {
                    return (locked - candidates.minByOrNull { kotlin.math.abs(it - locked) }!!) to locked
                }
            }
            var bestShift = 0f
            var bestGuide: Float? = null
            var bestDistance = threshold
            candidates.forEach { candidate ->
                targets.forEach { target ->
                    val delta = target - candidate
                    val distance = kotlin.math.abs(delta)
                    if (distance <= bestDistance) {
                        bestDistance = distance
                        bestShift = delta
                        bestGuide = target
                    }
                }
            }
            return bestShift to bestGuide
        }

        val xTargets = buildList {
            add(safe.left); add(safe.right); add((safe.left + safe.right) / 2f)
            other?.let { add(it.left); add(it.right); add((it.left + it.right) / 2f) }
        }
        val yTargets = buildList {
            add(safe.top); add(safe.bottom); add((safe.top + safe.bottom) / 2f)
            other?.let { add(it.top); add(it.bottom); add((it.top + it.bottom) / 2f) }
        }
        val (dx, gx) = snapAxis(listOf(rect.left, rect.right, placement.centerX), xTargets, options.thresholdX, options.lockedX)
        val (dy, gy) = snapAxis(listOf(rect.top, rect.bottom, placement.centerY), yTargets, options.thresholdY, options.lockedY)
        shiftX = dx; shiftY = dy; guideX = gx; guideY = gy
        return SnapResult(
            placement = ModulePlacement(placement.centerX + shiftX, placement.centerY + shiftY, placement.width),
            guides = SnapGuides(guideX, guideY),
        )
    }
}
