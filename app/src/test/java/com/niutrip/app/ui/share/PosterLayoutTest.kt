package com.niutrip.app.ui.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterLayoutTest {
    @Test fun `standard ratios map to fixed export sizes`() {
        assertEquals(1080 to 1440, PosterCanvasSpec.create(PosterRatioKind.R3_4).let { it.widthPx to it.heightPx })
        assertEquals(1080 to 1080, PosterCanvasSpec.create(PosterRatioKind.SQUARE).let { it.widthPx to it.heightPx })
        assertEquals(1080 to 1920, PosterCanvasSpec.create(PosterRatioKind.R9_16).let { it.widthPx to it.heightPx })
    }

    @Test fun `original ratio derives from photo and clamps long edge`() {
        assertEquals(1080 to 810, PosterCanvasSpec.create(PosterRatioKind.ORIGINAL, 4000, 3000).let { it.widthPx to it.heightPx })
        assertEquals(540 to 2160, PosterCanvasSpec.create(PosterRatioKind.ORIGINAL, 1000, 4000).let { it.widthPx to it.heightPx })
        assertEquals(1080 to 1440, PosterCanvasSpec.create(PosterRatioKind.ORIGINAL, 3000, 4000).let { it.widthPx to it.heightPx })
    }

    @Test fun `crop zoom clamps between one and four`() {
        val imageW = 1000f; val imageH = 1000f; val canvasW = 500f; val canvasH = 500f
        assertEquals(1f, PosterCrop.clamp(PhotoCrop(zoom = 0.5f), imageW, imageH, canvasW, canvasH).zoom, 1e-6f)
        assertEquals(4f, PosterCrop.clamp(PhotoCrop(zoom = 9f), imageW, imageH, canvasW, canvasH).zoom, 1e-6f)
    }

    @Test fun `crop offsets clamp so photo always covers canvas`() {
        val imageW = 1000f; val imageH = 500f; val canvasW = 500f; val canvasH = 500f
        val clamped = PosterCrop.clamp(PhotoCrop(zoom = 1f, offsetX = 5f, offsetY = -5f), imageW, imageH, canvasW, canvasH)
        val draw = PosterCrop.draw(clamped, imageW, imageH, canvasW, canvasH)
        assertTrue(draw.transX <= 0f && draw.transX + draw.drawW >= canvasW)
        assertTrue(draw.transY <= 0f && draw.transY + draw.drawH >= canvasH)
    }

    @Test fun `zoom about focus keeps content under the focus stable`() {
        val imageW = 1000f; val imageH = 1000f; val canvasW = 1000f; val canvasH = 1000f
        val zoomed = PosterCrop.zoomAbout(PhotoCrop(), 2f, 700f, 300f, imageW, imageH, canvasW, canvasH)
        val draw = PosterCrop.draw(zoomed, imageW, imageH, canvasW, canvasH)
        // 焦点 (700,300) 处的图片内容在缩放前后保持不变。
        val contentX = (700f - draw.transX) / draw.scale
        val contentY = (300f - draw.transY) / draw.scale
        assertEquals(700f, contentX, 1f)
        assertEquals(300f, contentY, 1f)
    }

    @Test fun `preview and export crop draw the same normalized content`() {
        val crop = PhotoCrop(zoom = 2f, offsetX = -0.1f, offsetY = 0.05f)
        val imageW = 2000f; val imageH = 1500f
        val preview = PosterCrop.draw(crop, imageW, imageH, 300f, 400f)
        val export = PosterCrop.draw(crop, imageW, imageH, 1080f, 1440f)
        fun contentFractionX(draw: CropDraw, canvasX: Float) = (canvasX - draw.transX) / draw.scale / imageW
        fun contentFractionY(draw: CropDraw, canvasY: Float) = (canvasY - draw.transY) / draw.scale / imageH
        assertEquals(contentFractionX(preview, 75f), contentFractionX(export, 270f), 1e-4f)
        assertEquals(contentFractionY(preview, 200f), contentFractionY(export, 720f), 1e-4f)
    }

    @Test fun `oversized module shrinks before its center is clamped`() {
        val safe = NormRect(0.05f, 0.05f, 0.95f, 0.95f)
        // aspect=2（h=w/2）的模块宽 1.2（超过安全区宽 0.9），高度 0.6 > 安全区高 0.9 的一半约束。
        val clamped = PosterLayout.clampPlacement(ModulePlacement(1.5f, -0.2f, 1.2f), 2f, safe)
        val rect = PosterLayout.moduleRect(clamped, 2f)
        assertTrue(clamped.width <= 0.9f)
        assertTrue(rect.left >= safe.left - 1e-4f && rect.right <= safe.right + 1e-4f)
        assertTrue(rect.top >= safe.top - 1e-4f && rect.bottom <= safe.bottom + 1e-4f)
    }

    @Test fun `module edges not just center stay inside safe area`() {
        val safe = NormRect(0.03f, 0.03f, 0.97f, 0.97f)
        val clamped = PosterLayout.clampPlacement(ModulePlacement(0.0f, 0.0f, 0.4f), 1f, safe)
        val rect = PosterLayout.moduleRect(clamped, 1f)
        assertTrue(rect.left >= safe.left - 1e-4f)
        assertTrue(rect.top >= safe.top - 1e-4f)
        assertTrue(rect.right <= safe.right + 1e-4f)
        assertTrue(rect.bottom <= safe.bottom + 1e-4f)
    }

    @Test fun `default photo layout stacks info above route without overlap`() {
        val safe = PosterLayout.safeArea()
        val (info, route) = PosterLayout.defaultPhotoPlacements(routeAspect = 2f, infoAspect = 0.5f, safe = safe)
        val infoRect = PosterLayout.moduleRect(info, 0.5f)
        val routeRect = PosterLayout.moduleRect(route, 2f)
        assertTrue(infoRect.bottom <= routeRect.top + 1e-4f)
        fun inside(rect: NormRect) = rect.left >= safe.left - 1e-4f && rect.top >= safe.top - 1e-4f &&
            rect.right <= safe.right + 1e-4f && rect.bottom <= safe.bottom + 1e-4f
        assertTrue(inside(infoRect)); assertTrue(inside(routeRect))
        assertTrue(info.width > 0.1f && route.width > 0.1f)
    }

    @Test fun `default map layout places info near top`() {
        val safe = PosterLayout.safeArea(reservedBottom = 0.08f)
        val info = PosterLayout.defaultMapPlacement(infoAspect = 0.4f, safe = safe)
        val rect = PosterLayout.moduleRect(info, 0.4f)
        assertTrue(rect.top >= safe.top - 1e-4f)
        assertTrue(rect.bottom <= safe.top + safe.height * 0.45f)
    }

    @Test fun `map safe area reserves bottom strip for sdk attribution`() {
        val plain = PosterLayout.safeArea()
        val mapArea = PosterLayout.safeArea(reservedBottom = 0.08f)
        assertEquals(plain.bottom - 0.08f, mapArea.bottom, 1e-4f)
        assertEquals(plain.top, mapArea.top, 1e-6f)
        assertEquals(plain.left, mapArea.left, 1e-6f)
    }

    // ---------- 吸附参考线（plan §6.4 / 阶段 4） ----------

    @Test fun `snap pulls module center to canvas center within threshold`() {
        val safe = PosterLayout.safeArea()
        val result = PosterLayout.snapPlacement(
            ModulePlacement(0.52f, 0.48f, 0.4f), 1f, safe, null,
            PosterLayout.SnapOptions(thresholdX = 0.03f, thresholdY = 0.03f))
        assertEquals(0.5f, result.placement.centerX, 1e-4f)
        assertEquals(0.5f, result.placement.centerY, 1e-4f)
        assertEquals(0.5f, result.guides.x!!, 1e-4f)
        assertEquals(0.5f, result.guides.y!!, 1e-4f)
    }

    @Test fun `snap aligns module edges to safe area and other module`() {
        val safe = PosterLayout.safeArea()
        val other = NormRect(0.4f, 0.6f, 0.9f, 0.9f)
        // 模块左缘 0.03 恰好压在 safe.left 上（位移 0）；顶缘 -0.05 距 safe.top 0.08 超过阈值不吸。
        val result = PosterLayout.snapPlacement(
            ModulePlacement(0.28f, 0.2f, 0.5f), 1f, safe, other,
            PosterLayout.SnapOptions(thresholdX = 0.04f, thresholdY = 0.04f))
        assertEquals(0.28f, result.placement.centerX, 1e-4f)
        assertEquals(safe.left, result.guides.x!!, 1e-4f)
        org.junit.Assert.assertNull(result.guides.y)
    }

    @Test fun `snap stays locked until release threshold is exceeded`() {
        val safe = PosterLayout.safeArea()
        val locked = PosterLayout.snapPlacement(
            ModulePlacement(0.52f, 0.5f, 0.4f), 1f, safe, null,
            PosterLayout.SnapOptions(thresholdX = 0.03f, thresholdY = 0.03f))
        assertEquals(0.5f, locked.placement.centerX, 1e-4f)
        // 手指已离开普通阈值（0.05 > 0.03）但仍在脱离阈值内：保持锁定。
        val stillLocked = PosterLayout.snapPlacement(
            ModulePlacement(0.55f, 0.5f, 0.4f), 1f, safe, null,
            PosterLayout.SnapOptions(thresholdX = 0.03f, thresholdY = 0.03f, lockedX = 0.5f, lockedY = null))
        assertEquals(0.5f, stillLocked.placement.centerX, 1e-4f)
        assertEquals(0.5f, stillLocked.guides.x!!, 1e-4f)
        // 超过脱离阈值（0.03*2.2≈0.066）：释放。
        val released = PosterLayout.snapPlacement(
            ModulePlacement(0.60f, 0.5f, 0.4f), 1f, safe, null,
            PosterLayout.SnapOptions(thresholdX = 0.03f, thresholdY = 0.03f, lockedX = 0.5f, lockedY = null))
        assertEquals(0.6f, released.placement.centerX, 1e-4f)
        org.junit.Assert.assertNull(released.guides.x)
    }

    @Test fun `no snap happens beyond threshold`() {
        val safe = PosterLayout.safeArea()
        val result = PosterLayout.snapPlacement(
            ModulePlacement(0.75f, 0.25f, 0.3f), 1f, safe, null,
            PosterLayout.SnapOptions(thresholdX = 0.02f, thresholdY = 0.02f))
        assertEquals(0.75f, result.placement.centerX, 1e-6f)
        org.junit.Assert.assertNull(result.guides.x)
        org.junit.Assert.assertNull(result.guides.y)
    }

    // ---------- 布局预设（plan §6.5 / 阶段 4） ----------

    @Test fun `left right preset places route left and info right without overlap`() {
        val safe = PosterLayout.safeArea()
        val (info, route) = PosterLayout.photoPresetPlacements(
            PosterPresetKind.LEFT_RIGHT, routeAspect = 1.2f, infoAspect = 0.5f, safe = safe)
        val infoRect = PosterLayout.moduleRect(info, 0.5f)
        val routeRect = PosterLayout.moduleRect(route, 1.2f)
        assertTrue(infoRect.left >= routeRect.right - 1e-4f)
        fun inside(rect: NormRect) = rect.left >= safe.left - 1e-4f && rect.top >= safe.top - 1e-4f &&
            rect.right <= safe.right + 1e-4f && rect.bottom <= safe.bottom + 1e-4f
        assertTrue(inside(infoRect)); assertTrue(inside(routeRect))
    }

    // ---------- 地图模式：信息在地图上方，不叠加（用户要求） ----------

    @Test fun `map mode regions put map first and summary below without overlap`() {
        val (info, map) = PosterLayout.mapModeRegions(
            infoWidth = 0.8f, infoAspect = 0.45f, canvasWidthPx = 1080, canvasHeightPx = 1440)
        assertEquals(map.bottom, info.top, 1e-4f)
        assertEquals(0.5f, (info.left + info.right) / 2f, 1e-4f) // 信息水平居中
        assertEquals(0f, map.left, 1e-6f)
        assertEquals(1f, map.right, 1e-6f)
        assertEquals(0f, map.top, 1e-6f)
        assertEquals(1f, info.bottom, 1e-6f)
        assertTrue(map.height >= 0.6f)
    }

    @Test fun `map mode clamps oversized info to keep map usable`() {
        val (info, map) = PosterLayout.mapModeRegions(
            infoWidth = 0.9f, infoAspect = 1.6f, canvasWidthPx = 1080, canvasHeightPx = 1440)
        assertTrue(info.height <= 0.40f)
        assertTrue(map.height >= 0.60f)
        assertEquals(0.5f, (info.left + info.right) / 2f, 1e-4f)
    }
}
