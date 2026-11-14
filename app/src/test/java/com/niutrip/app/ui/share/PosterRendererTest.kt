package com.niutrip.app.ui.share

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.niutrip.app.core.DayGrouper
import com.niutrip.app.core.PointLite
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.TrackDto
import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterRendererTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val photoColor = Color.rgb(60, 90, 140)
    private val routeColorPredicate: (Int) -> Boolean = {
        Color.red(it) > 200 && Color.green(it) in 120..190 && Color.blue(it) < 80
    }

    private val snapshot: PosterTrackSnapshot by lazy {
        val cloud = listOf(
            PointDto(point_id = "p1", longitude = 100.0, latitude = 30.0, point_time = "2026-09-11T08:00:00", point_source = "AUTO"),
            PointDto(point_id = "p2", longitude = 101.5, latitude = 30.0, point_time = "2026-09-11T09:00:00", point_source = "MANUAL"),
            PointDto(point_id = "p3", longitude = 103.0, latitude = 30.0, point_time = "2026-09-11T10:00:00", point_source = "AUTO"))
        PosterSnapshotBuilder.build(
            TrackDto(track_id = "t1", track_name = "川西环线", track_record_mode = "AUTO", track_status = "FINISHED",
                track_start_time = "2026-09-11T08:00:00", track_end_time = "2026-09-11T10:00:00"),
            cloud, emptyList(), 1)!!
    }
    private val routeModel: PosterRouteModel by lazy { PosterRouteModel.build(snapshot.days) }

    private fun photo(): Bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        .also { it.eraseColor(photoColor) }

    private fun photoDocument(spec: PosterCanvasSpec, theme: PosterInfoTextTheme = PosterInfoTextTheme.LIGHT): PosterDocument {
        val routeAspect = PosterRenderer.routeModuleAspect(routeModel)
        val infoAspect = PosterRenderer.InfoLayout.measure(
            snapshot.stats, PosterRenderer.InfoStyle(theme), 0.72f * spec.widthPx).aspect
        val routePlacement = PosterLayout.clampPlacement(
            ModulePlacement(0.5f, 0.72f, 0.72f), routeAspect, PosterLayout.safeArea())
        val infoPlacement = PosterLayout.clampPlacement(
            ModulePlacement(0.5f, 0.18f, 0.72f), infoAspect, PosterLayout.safeArea())
        return PosterDocument(
            backgroundMode = PosterBackgroundMode.PHOTO,
            photoRatioKind = spec.kind,
            photoState = PosterPhotoState("s1", 400, 300),
            photoRouteState = PosterRouteModuleState(routePlacement),
            photoInfoState = PosterInfoModuleState(infoPlacement, theme))
    }

    private fun infoAspectOf(document: PosterDocument, spec: PosterCanvasSpec): Float =
        PosterRenderer.InfoLayout.measure(
            snapshot.stats, PosterRenderer.InfoStyle(document.photoInfoState!!.textTheme),
            document.photoInfoState!!.placement.width * spec.widthPx).aspect

    private fun containsColor(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        predicate: (Int) -> Boolean,
    ): Boolean {
        val l = left.coerceIn(0, bitmap.width - 1); val r = right.coerceIn(l + 1, bitmap.width)
        val t = top.coerceIn(0, bitmap.height - 1); val b = bottom.coerceIn(t + 1, bitmap.height)
        for (y in t until b) for (x in l until r) if (predicate(bitmap.getPixel(x, y))) return true
        return false
    }

    @Test fun `photo poster composes crop route and info at target size`() {
        val spec = PosterCanvasSpec.create(PosterRatioKind.R3_4)
        val document = photoDocument(spec)
        val image = PosterRenderer.renderPhotoPoster(spec, document, snapshot, routeModel, photo())
        assertEquals(1080, image.width)
        assertEquals(1440, image.height)
        // 照片裁剪铺满画布：远离模块的角落保持照片颜色。
        assertEquals(photoColor, image.getPixel(20, 20))
        // 路线模块区域出现按天颜色（首日 DAY_COLORS[0] 橙色）。
        val routeBox = PosterLayout.moduleRect(document.photoRouteState!!.placement,
            PosterRenderer.routeModuleAspect(routeModel))
        assertTrue(containsColor(image,
            (routeBox.left * image.width).toInt(), (routeBox.top * image.height).toInt(),
            (routeBox.right * image.width).toInt(), (routeBox.bottom * image.height).toInt(), routeColorPredicate))
        // 信息模块区域出现白色文字。
        val infoBox = PosterLayout.moduleRect(document.photoInfoState!!.placement, infoAspectOf(document, spec))
        assertTrue(containsColor(image,
            (infoBox.left * image.width).toInt(), (infoBox.top * image.height).toInt(),
            (infoBox.right * image.width).toInt(), (infoBox.bottom * image.height).toInt()) {
            Color.red(it) > 240 && Color.green(it) > 240 && Color.blue(it) > 240 })
        image.recycle()
    }

    @Test fun `map poster preserves full map above compact summary`() {
        val spec = PosterCanvasSpec.create(PosterRatioKind.R3_4)
        val mapColor = Color.rgb(210, 228, 240)
        val map = Bitmap.createBitmap(810, 1080, Bitmap.Config.ARGB_8888)
        map.eraseColor(mapColor)
        val document = PosterDocument(
            backgroundMode = PosterBackgroundMode.MAP,
            mapInfoState = PosterInfoModuleState(
                PosterLayout.defaultMapPlacement(0.4f, PosterLayout.safeArea(reservedBottom = 0.08f)),
                PosterInfoTextTheme.DARK))
        val infoAspect = PosterRenderer.InfoLayout.measure(
            snapshot.stats, PosterRenderer.InfoStyle(PosterInfoTextTheme.DARK), 1080f).aspect
        val image = PosterRenderer.renderMapPoster(spec, document, snapshot, map)
        assertEquals(1080, image.width)
        assertEquals(1440, image.height)
        val (info, mapRegion) = PosterLayout.mapModeRegions(
            document.mapInfoState!!.placement.width, infoAspect, spec.widthPx, spec.heightPx)
        val darkText = { pixel: Int ->
            Color.alpha(pixel) > 200 && Color.red(pixel) < 90 && Color.green(pixel) < 90 && Color.blue(pixel) < 90
        }
        // 信息带内出现深色文字。
        assertTrue(containsColor(image,
            (info.left * image.width).toInt(), (info.top * image.height).toInt(),
            (info.right * image.width).toInt(), (info.bottom * image.height).toInt(), darkText))
        // 地图区域内不再绘制信息文字（保持纯地图色）。
        val mapTop = (mapRegion.top * image.height).toInt()
        val mapBottom = (mapRegion.bottom * image.height).toInt()
        var offender: String? = null
        outer@ for (y in mapTop until mapBottom) {
            for (x in 0 until image.width) {
                if (darkText(image.getPixel(x, y))) {
                    offender = "($x,$y) rgb=${Color.red(image.getPixel(x, y))},${Color.green(image.getPixel(x, y))},${Color.blue(image.getPixel(x, y))} mapTop=$mapTop"
                    break@outer
                }
            }
        }
        assertTrue("地图区域发现深色像素 $offender", offender == null)
        assertEquals(mapColor, image.getPixel(10, 0))
        assertEquals(Color.WHITE, image.getPixel(10, 1435))
        val preview = File("build/outputs/poster-map-summary-preview.png")
        preview.parentFile!!.mkdirs()
        PosterRenderer.writePng(image, preview)
        image.recycle()
        map.recycle()
    }

    @Test fun `light theme uses white text and dark theme keeps dark text`() {
        val spec = PosterCanvasSpec.create(PosterRatioKind.R3_4)
        val lightDoc = photoDocument(spec, PosterInfoTextTheme.LIGHT)
        val lightImage = PosterRenderer.renderPhotoPoster(spec, lightDoc, snapshot, routeModel, photo())
        val lightBox = PosterLayout.moduleRect(lightDoc.photoInfoState!!.placement, infoAspectOf(lightDoc, spec))
        assertTrue(containsColor(lightImage,
            (lightBox.left * lightImage.width).toInt(), (lightBox.top * lightImage.height).toInt(),
            (lightBox.right * lightImage.width).toInt(), (lightBox.bottom * lightImage.height).toInt()) {
            Color.red(it) > 240 && Color.green(it) > 240 && Color.blue(it) > 240 })
        lightImage.recycle()

        val darkDoc = photoDocument(spec, PosterInfoTextTheme.DARK)
        val darkImage = PosterRenderer.renderPhotoPoster(spec, darkDoc, snapshot, routeModel, photo())
        val darkBox = PosterLayout.moduleRect(darkDoc.photoInfoState!!.placement, infoAspectOf(darkDoc, spec))
        assertTrue(containsColor(darkImage,
            (darkBox.left * darkImage.width).toInt(), (darkBox.top * darkImage.height).toInt(),
            (darkBox.right * darkImage.width).toInt(), (darkBox.bottom * darkImage.height).toInt()) {
            Color.red(it) < 90 && Color.green(it) < 90 && Color.blue(it) < 90 })
        darkImage.recycle()
    }

    @Test fun `png is written atomically with real dimensions`() {
        val spec = PosterCanvasSpec.create(PosterRatioKind.SQUARE)
        val image = PosterRenderer.renderPhotoPoster(spec, photoDocument(spec), snapshot, routeModel, photo())
        val directory = File(context.cacheDir, "poster_test_out").apply { mkdirs() }
        val file = PosterRenderer.writePng(image, File(directory, "poster.png"))
        assertTrue(file.exists())
        val decoded = BitmapFactory.decodeFile(file.path)
        assertEquals(1080, decoded!!.width)
        assertEquals(1080, decoded.height)
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
        decoded.recycle()
        image.recycle()
        directory.deleteRecursively()
    }

    @Test fun `route module draws within its box and respects aspect`() {
        val bitmap = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val aspect = PosterRenderer.routeModuleAspect(routeModel)
        assertTrue(aspect > 0f && aspect < 0.5f) // 3 度横向路线 → 扁盒子
        val box = PosterLayout.moduleRect(ModulePlacement(0.5f, 0.7f, 0.6f), aspect)
        PosterRenderer.drawRouteModule(canvas, routeModel,
            box.left * 800, box.top * 800, box.width * 800, box.height * 800,
            PosterRenderer.RouteStyle(PosterRouteTheme.BY_DAY, showCheckins = true))
        assertTrue(containsColor(bitmap,
            (box.left * 800).toInt() + 4, (box.top * 800).toInt() + 4,
            (box.right * 800).toInt() - 4, (box.bottom * 800).toInt() - 4, routeColorPredicate))
        // 盒子左侧区域不应出现路线颜色。
        assertTrue(!containsColor(bitmap, 0, 0, (box.left * 800).toInt() - 6, 800, routeColorPredicate))
        bitmap.recycle()
    }

    // ---------- 信息模块指标开关（plan §9.1 / 阶段 4） ----------

    @Test fun `info layout shrinks when name date and aux are hidden`() {
        val width = 756f
        val full = PosterRenderer.InfoLayout.measure(snapshot.stats, PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT), width)
        val noName = PosterRenderer.InfoLayout.measure(snapshot.stats,
            PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT, showName = false), width)
        val noAux = PosterRenderer.InfoLayout.measure(snapshot.stats,
            PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT, showAux = false), width)
        val minimal = PosterRenderer.InfoLayout.measure(snapshot.stats,
            PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT, showName = false, showDate = false, showAux = false), width)
        assertTrue(full.heightPx > noName.heightPx)
        assertTrue(full.heightPx > noAux.heightPx)
        assertTrue(minimal.heightPx < noName.heightPx)
        assertTrue(minimal.heightPx < noAux.heightPx)
        // 主里程保留：即使全部关闭也仍有内容。
        assertTrue(minimal.heightPx > width * 0.1f)
    }

    @Test fun `long name and long aux stay within module width`() {
        val longTrack = TrackDto(track_id = "t2",
            track_name = "An extremely long track name that must wrap into two lines and then be ellipsized safely",
            track_record_mode = "AUTO", track_status = "FINISHED",
            track_start_time = "2026-09-11T08:00:00", track_end_time = "2026-09-24T07:59:00")
        val longStats = PosterStats.from(longTrack, snapshot.days)
        val layout = PosterRenderer.InfoLayout.measure(longStats, PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT), 600f)
        // 名称最多两行；辅助指标压缩字号后仍放得下：总高度有界，不随内容无限增长。
        assertTrue(layout.heightPx < 600f * 1.4f)
        assertTrue(layout.heightPx.isFinite())
    }

    /**
     * 回归（真机反馈”轨迹标题有两个”）：长名称两行换行在照片上看起来像两个标题。
     * 名称改为单行省略，超长时省略号截断。
     */
    @Test fun `long name renders on a single ellipsized line`() {
        val short = PosterRenderer.InfoLayout.measure(snapshot.stats, PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT), 756f)
        val longNameStats = snapshot.stats.copy(name = "超长轨迹名称用来验证单行省略" + "特别长的旅途名字".repeat(6))
        val long = PosterRenderer.InfoLayout.measure(longNameStats, PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT), 756f)
        assertEquals(short.heightPx, long.heightPx, 1f) // 名称不再增加行高
    }

    /** 参考图排版：辅助指标为「数值上、标签下」的分列数据组，缺失项自动收列。 */
    @Test fun `aux metrics become value over label columns and skip missing`() {
        val columns = PosterRenderer.InfoLayout
            .measure(snapshot.stats, PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT), 756f)
            .auxColumns
        assertEquals(listOf("02:00:00" to "历时", "1" to "天数", "1" to "打卡"), columns)
        // 无历时（结束早于开始）时自动收列。
        val noDuration = PosterRenderer.InfoLayout
            .measure(snapshot.stats.copy(startTime = snapshot.stats.endTime!!.plusHours(1)),
                PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT), 756f)
            .auxColumns
        assertEquals(listOf("1" to "天数", "1" to "打卡"), noDuration)
    }

    /** 用户要求：轨迹名称是所有文字中最大的一级。 */
    @Test fun `track name is the largest text of info module`() {
        val ratios = PosterRenderer.InfoLayout.textRatios()
        assertTrue(ratios.getValue("name") > ratios.getValue("main"))
        assertTrue(ratios.getValue("main") > ratios.getValue("unit"))
        assertTrue(ratios.getValue("unit") >= ratios.getValue("auxValue"))
        assertTrue(ratios.getValue("auxValue") > ratios.getValue("date"))
        assertTrue(ratios.getValue("date") > ratios.getValue("auxLabel"))
    }

    /** 用户要求：品牌署名（旅行牛牛+logo）固定在画布左上角，不再占信息模块行高。 */
    @Test fun `brand watermark sits in canvas top-left corner`() {
        val spec = PosterCanvasSpec.create(PosterRatioKind.R3_4)
        val document = photoDocument(spec)
        val image = PosterRenderer.renderPhotoPoster(spec, document, snapshot, routeModel, photo())
        // 左上角区域出现品牌水印（非照片颜色像素）。
        assertTrue(containsColor(image,
            (image.width * 0.02f).toInt(), (image.height * 0.015f).toInt(),
            (image.width * 0.2f).toInt(), (image.height * 0.085f).toInt()) { it != photoColor })
        image.recycle()
    }

    /**
     * 用户要求：名称/日期/英雄行每行各自水平居中。
     * 辅助三列内容宽度天然不对称，不参与包围盒断言。
     */
    @Test fun `name date and hero rows are horizontally centered`() {
        val width = 756f
        fun bboxOf(style: PosterRenderer.InfoStyle): IntArray {
            val bitmap = Bitmap.createBitmap(width.toInt(), 500, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(bitmap)
            val layout = PosterRenderer.InfoLayout.measure(snapshot.stats, style, width)
            layout.draw(canvas, 0f, 10f)
            var minX = Int.MAX_VALUE; var maxX = -1
            for (x in 0 until bitmap.width) {
                var hasInk = false
                for (y in 0 until bitmap.height) {
                    if (Color.alpha(bitmap.getPixel(x, y)) > 40) { hasInk = true; break }
                }
                if (hasInk) { if (x < minX) minX = x; if (x > maxX) maxX = x }
            }
            bitmap.recycle()
            return intArrayOf(minX, maxX)
        }
        val nameOnly = bboxOf(PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT, showDate = false, showAux = false))
        val dateHero = bboxOf(PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT, showName = false, showAux = false))
        val all = bboxOf(PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT, showAux = false))
        assertTrue("名称行 bbox=${nameOnly.contentToString()} 未居中(期望中心${width / 2f})",
            kotlin.math.abs((nameOnly[0] + nameOnly[1]) / 2f - width / 2f) <= 6f)
        assertTrue("日期+英雄行 bbox=${dateHero.contentToString()} 未居中",
            kotlin.math.abs((dateHero[0] + dateHero[1]) / 2f - width / 2f) <= 6f)
        assertTrue("整体 bbox=${all.contentToString()} 未居中",
            kotlin.math.abs((all[0] + all[1]) / 2f - width / 2f) <= 6f)
    }
}
