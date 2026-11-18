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
    private val checkinIcon by lazy { PosterWireframeRenderer.loadCheckinIcon(context) }
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
            photoRouteState = PosterRouteModuleState(routePlacement, theme = PosterRouteTheme.BY_DAY),
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
        val image = PosterRenderer.renderPhotoPoster(spec, document, snapshot, routeModel, photo(), checkinIcon)
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
        val lightImage = PosterRenderer.renderPhotoPoster(spec, lightDoc, snapshot, routeModel, photo(), checkinIcon)
        val lightBox = PosterLayout.moduleRect(lightDoc.photoInfoState!!.placement, infoAspectOf(lightDoc, spec))
        assertTrue(containsColor(lightImage,
            (lightBox.left * lightImage.width).toInt(), (lightBox.top * lightImage.height).toInt(),
            (lightBox.right * lightImage.width).toInt(), (lightBox.bottom * lightImage.height).toInt()) {
            Color.red(it) > 240 && Color.green(it) > 240 && Color.blue(it) > 240 })
        lightImage.recycle()

        val darkDoc = photoDocument(spec, PosterInfoTextTheme.DARK)
        val darkImage = PosterRenderer.renderPhotoPoster(spec, darkDoc, snapshot, routeModel, photo(), checkinIcon)
        val darkBox = PosterLayout.moduleRect(darkDoc.photoInfoState!!.placement, infoAspectOf(darkDoc, spec))
        assertTrue(containsColor(darkImage,
            (darkBox.left * darkImage.width).toInt(), (darkBox.top * darkImage.height).toInt(),
            (darkBox.right * darkImage.width).toInt(), (darkBox.bottom * darkImage.height).toInt()) {
            Color.red(it) < 90 && Color.green(it) < 90 && Color.blue(it) < 90 })
        darkImage.recycle()
    }

    @Test fun `png is written atomically with real dimensions`() {
        val spec = PosterCanvasSpec.create(PosterRatioKind.SQUARE)
        val image = PosterRenderer.renderPhotoPoster(spec, photoDocument(spec), snapshot, routeModel, photo(), checkinIcon)
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
            PosterRenderer.RouteStyle(PosterRouteTheme.BY_DAY, showCheckins = true), checkinIcon)
        assertTrue(containsColor(bitmap,
            (box.left * 800).toInt() + 4, (box.top * 800).toInt() + 4,
            (box.right * 800).toInt() - 4, (box.bottom * 800).toInt() - 4, routeColorPredicate))
        // 盒子左侧区域不应出现路线颜色。
        assertTrue(!containsColor(bitmap, 0, 0, (box.left * 800).toInt() - 6, 800, routeColorPredicate))
        bitmap.recycle()
    }

    // ---------- 信息模块指标开关（plan §9.1 / 阶段 4） ----------

    @Test fun `info layout keeps stable header and collapses hidden auxiliary row`() {
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
        // 名称单行省略；辅助指标压缩字号，总高度不随内容增长。
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
        assertEquals(listOf("02:00:00" to "全程耗时", "1" to "记录天数", "1" to "打卡点"), columns)
        // 无历时（结束早于开始）时自动收列。
        val noDuration = PosterRenderer.InfoLayout
            .measure(snapshot.stats.copy(startTime = snapshot.stats.endTime!!.plusHours(1)),
                PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT), 756f)
            .auxColumns
        assertEquals(listOf("1" to "记录天数", "1" to "打卡点"), noDuration)
    }

    @Test fun `distance is dominant and auxiliary values are larger than labels`() {
        val ratios = PosterRenderer.InfoLayout.textRatios()
        assertTrue(ratios.getValue("main") > ratios.getValue("name") * 2)
        assertTrue(ratios.getValue("main") > ratios.getValue("unit"))
        assertTrue(ratios.getValue("auxValue") > ratios.getValue("auxLabel"))
        assertTrue(ratios.getValue("name") > ratios.getValue("date"))
    }

    @Test fun `font sizes preserve measured bounds and transparent summary background`() {
        var previousHeight = 0f
        for (scale in listOf(.8f, 1f, 1.6f)) {
            val style = PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT, fontScale = scale)
            val layout = PosterRenderer.InfoLayout.measure(snapshot.stats.copy(name = "超长的轨迹名称".repeat(10)), style, 600f)
            assertTrue(layout.heightPx > previousHeight)
            previousHeight = layout.heightPx
            val bitmap = Bitmap.createBitmap(640, (layout.heightPx + 40).toInt(), Bitmap.Config.ARGB_8888)
            layout.draw(Canvas(bitmap), 20f, 20f)
            var transparent = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) == 0) transparent++
                else assertTrue(x in 20 until 620 && y >= 20 && y < 20 + layout.heightPx)
            }
            assertTrue("summary must not fill the photo with a white panel", transparent > bitmap.width * bitmap.height * .7)
            bitmap.recycle()
        }
    }

    /** 用户要求：品牌署名（旅行牛牛+logo）固定在画布左上角，不再占信息模块行高。 */
    @Test fun `header and statistics use independent colors and font sizes in photo export`() {
        val spec = PosterCanvasSpec.create(PosterRatioKind.SQUARE)
        val document = photoDocument(spec).let { it.copy(photoRouteState = null,
            photoInfoState = it.photoInfoState!!.copy(placement = ModulePlacement(.5f, .5f, .88f),
                titleColor = PosterTextColor.RED, statsColor = PosterTextColor.BLUE,
                titleFontScale = 1.6f, fontScale = .8f)) }
        val info = document.photoInfoState!!
        val style = PosterRenderer.infoStyle(info)
        val w = info.placement.width * spec.widthPx
        val layout = PosterRenderer.InfoLayout.measure(snapshot.stats, style, w)
        val image = PosterRenderer.renderPhotoPoster(spec, document, snapshot, routeModel, photo(), checkinIcon)
        val left = ((spec.widthPx - w) / 2).toInt()
        val top = ((spec.heightPx - layout.heightPx) / 2).toInt()
        val statsTop = (top + w * info.titleFontScale * (.035f + .084f + .059f)).toInt()
        assertTrue(containsColor(image, left, top, left + w.toInt(), statsTop) { it == PosterTextColor.RED.argb })
        assertTrue(!containsColor(image, left, top, left + w.toInt(), statsTop) { it == PosterTextColor.BLUE.argb })
        assertTrue(containsColor(image, left, statsTop + 2, left + w.toInt(), (top + layout.heightPx).toInt()) {
            it == PosterTextColor.BLUE.argb })
        val statsOnly = snapshot.stats.copy(name = "A")
        val smallerTitle = PosterRenderer.InfoLayout.measure(statsOnly, style.copy(titleFontScale = .8f), w)
        assertTrue(smallerTitle.heightPx < layout.heightPx)
        // Changing the header size must not alter any data typography or re-enable a panel fill.
        val headerHidden = style.copy(showName = false, showDate = false)
        val a = PosterRenderer.InfoLayout.measure(statsOnly, headerHidden, w)
        val b = PosterRenderer.InfoLayout.measure(statsOnly, headerHidden.copy(titleFontScale = .8f), w)
        assertEquals(w * .035f * .8f, a.heightPx - b.heightPx, .01f)
        PosterRenderer.writePng(image, File("build/outputs/poster-photo-independent-text.png"))
        image.recycle()
    }

    @Test fun `brand contrast samples local photo and follows crop across resolutions`() {
        val photo = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        photo.eraseColor(Color.BLACK)
        Canvas(photo).drawRect(0f, 0f, 400f, 400f, android.graphics.Paint().apply { color = Color.WHITE })
        for (size in listOf(400f, 1080f)) {
            assertEquals(PosterInfoTextTheme.DARK, PosterRenderer.photoBrandTheme(photo, PhotoCrop(), size, size))
            assertEquals(PosterInfoTextTheme.LIGHT,
                PosterRenderer.photoBrandTheme(photo, PhotoCrop(offsetX = -.5f), size, size))
        }
        val spec = PosterCanvasSpec.create(PosterRatioKind.SQUARE)
        for (background in listOf(Color.BLACK, Color.WHITE)) {
            photo.eraseColor(background)
            val document = photoDocument(spec).copy(photoRouteState = null, photoInfoState = null,
                photoState = PosterPhotoState("contrast", 800, 400))
            val image = PosterRenderer.renderPhotoPoster(spec, document, snapshot, routeModel, photo, checkinIcon)
            val expected = if (background == Color.BLACK) Color.WHITE else Color.BLACK
            assertTrue(containsColor(image, 100, 40, 320, 110) { it == expected })
            image.recycle()
        }
        photo.recycle()
    }

    @Test fun `brand watermark sits in canvas top-left corner`() {
        val spec = PosterCanvasSpec.create(PosterRatioKind.R3_4)
        val document = photoDocument(spec)
        val image = PosterRenderer.renderPhotoPoster(spec, document, snapshot, routeModel, photo(), checkinIcon)
        // 左上角区域出现品牌水印（非照片颜色像素）。
        assertTrue(containsColor(image,
            (image.width * 0.02f).toInt(), (image.height * 0.015f).toInt(),
            (image.width * 0.2f).toInt(), (image.height * 0.085f).toInt()) { it != photoColor })
        image.recycle()
    }

    @Test fun `photo info fits measured bounds with long values and every display toggle`() {
        for (distance in listOf(0.0, 10460.0, 123456789.0)) for (flags in 0..7) {
            val stats = snapshot.stats.copy(name = "超长轨迹名称".repeat(15), distanceMeters = distance)
            val style = PosterRenderer.InfoStyle(PosterInfoTextTheme.LIGHT,
                showName = flags and 1 != 0, showDate = flags and 2 != 0, showAux = flags and 4 != 0)
            val layout = PosterRenderer.InfoLayout.measure(stats, style, 600f)
            val namePaint = PosterRenderer.InfoLayout.fillPaint(Color.WHITE, bold = true).apply { textSize = 600f * .064f }
            assertTrue("name width=${namePaint.measureText(layout.nameText)} text=${layout.nameText}",
                namePaint.measureText(layout.nameText) <= 600f * .94f + 1)
            val bitmap = Bitmap.createBitmap(640, (layout.heightPx + 40).toInt(), Bitmap.Config.ARGB_8888)
            layout.draw(Canvas(bitmap), 20f, 20f)
            var inkCount = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 40) {
                    inkCount++
                    assertTrue("escaped measured box distance=$distance flags=$flags ($x,$y)",
                        x >= 20 && x < 620 && y >= 20 && y < 20 + layout.heightPx)
                }
            }
            assertTrue(inkCount > 100)
            bitmap.recycle()
        }
    }

    @Test fun `photo export honors information display toggles`() {
        val spec = PosterCanvasSpec.create(PosterRatioKind.SQUARE)
        val document = photoDocument(spec).let { it.copy(photoRouteState = null,
            photoInfoState = it.photoInfoState!!.copy(showName = false, showDate = false, showAux = false)) }
        val image = PosterRenderer.renderPhotoPoster(spec, document, snapshot, routeModel, photo(), checkinIcon)
        val info = document.photoInfoState!!
        val moduleWidth = info.placement.width * image.width
        val expected = PosterRenderer.InfoLayout.measure(snapshot.stats,
            PosterRenderer.InfoStyle(info.textTheme, showName = false, showDate = false, showAux = false), moduleWidth)
        val top = info.placement.centerY * image.height - expected.heightPx / 2
        assertEquals(photoColor, image.getPixel((image.width * .8f).toInt(), (top + moduleWidth * .07f).toInt()))
        assertTrue(!containsColor(image, (image.width * .12f).toInt(), (top + expected.heightPx + 2).toInt(),
            (image.width * .88f).toInt(), (top + moduleWidth * .335f).toInt()) { it != photoColor })
        image.recycle()
    }

    @Test fun `flat photo route renders checkin names rather than numbers`() {
        fun routeImage(name: String, show: Boolean): Bitmap {
            val points = listOf(
                PointLite("a", LocalDateTime.of(2026, 11, 16, 8, 0), 114.0, 30.0, false),
                PointLite("b", LocalDateTime.of(2026, 11, 16, 9, 0), 114.5, 30.0, true, name = name),
                PointLite("c", LocalDateTime.of(2026, 11, 16, 10, 0), 115.0, 30.0, false))
            val model = PosterRouteModel.build(DayGrouper.group(points))
            return Bitmap.createBitmap(600, 160, Bitmap.Config.ARGB_8888).also {
                PosterRenderer.drawRouteModule(Canvas(it), model, 0f, 0f, 600f, 160f,
                    PosterRenderer.RouteStyle(PosterRouteTheme.SINGLE, show, showEndpointNames = false), checkinIcon)
            }
        }
        val original = routeImage("望江亭", true)
        val renamed = routeImage("观景台", true)
        assertTrue("renaming a checkin must change its photo label", !original.sameAs(renamed))
        val hidden = routeImage("望江亭", false)
        val hiddenRenamed = routeImage("观景台", false)
        assertTrue(hidden.sameAs(hiddenRenamed))
        val preview = File("build/outputs/poster-photo-named-route-preview.png")
        PosterRenderer.writePng(original, preview)
        listOf(original, renamed, hidden, hiddenRenamed).forEach(Bitmap::recycle)
    }

    @Test fun `photo reference typography and named route fixture`() {
        val start = LocalDateTime.of(2026, 11, 14, 8, 0)
        val coordinates = listOf(114.25 to 30.58, 114.19 to 30.56, 114.18 to 30.50, 114.23 to 30.46,
            114.31 to 30.50, 114.32 to 30.60, 114.28 to 30.64, 114.25 to 30.58)
        val names = listOf("江岸区", "望江亭", "滨江步道", "湖畔营地", "观景台", "山间茶舍", "日落平台", "江岸区")
        val points = coordinates.mapIndexed { index, (lon, lat) ->
            PointLite(index.toString(), start.plusMinutes(index * 30L), lon, 30.55 + (lat - 30.55) * .5,
                true, name = names[index], desc = "沿途景色与旅行记录", images = listOf("a.jpg", "b.jpg"))
        }
        val days = DayGrouper.group(points)
        val snapshot = PosterTrackSnapshot("photo-fixture", "FINISHED", 1, days, points,
            PosterStats("江畔漫步", start, start.plusMinutes(210), 10460.0, 1, 8))
        val model = PosterRouteModel.build(days)
        val spec = PosterCanvasSpec.create(PosterRatioKind.R3_4)
        val background = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        for (theme in listOf(PosterInfoTextTheme.LIGHT, PosterInfoTextTheme.DARK)) {
            background.eraseColor(if (theme == PosterInfoTextTheme.LIGHT) Color.rgb(46, 46, 46) else Color.rgb(230, 230, 230))
            val document = PosterDocument(backgroundMode = PosterBackgroundMode.PHOTO,
                photoState = PosterPhotoState("fixture", 600, 800),
                photoRouteState = PosterRouteModuleState(ModulePlacement(.5f, .68f, .8f), theme = PosterRouteTheme.SINGLE),
                photoInfoState = PosterInfoModuleState(ModulePlacement(.5f, .32f, .88f), textTheme = theme))
            val image = PosterRenderer.renderPhotoPoster(spec, document, snapshot, model, background, checkinIcon)
            assertEquals(1080, image.width)
            PosterRenderer.writePng(image, File("build/outputs/poster-photo-layout-${theme.name.lowercase()}.png"))
            image.recycle()
        }
        background.recycle()
    }

    @Test fun `offline province contours change pixels and respect outline toggle`() {
        val model = PosterRouteModel.build(DayGrouper.group(listOf(
            PointLite("a", LocalDateTime.of(2026, 11, 18, 8, 0), 100.0, 25.0, false),
            PointLite("b", LocalDateTime.of(2026, 11, 18, 9, 0), 120.0, 35.0, false))))
        val outline = PosterMapOutline.load(context)
        fun render(show: Boolean): Bitmap = Bitmap.createBitmap(800, 640, Bitmap.Config.ARGB_8888).also {
            PosterRenderer.drawRouteModule(Canvas(it), model, 40f, 40f, 720f, 560f,
                PosterRenderer.RouteStyle(PosterRouteTheme.SINGLE, false, false, showMapOutline = show), checkinIcon, outline)
        }
        val visible = render(true)
        val hidden = render(false)
        assertTrue(!visible.sameAs(hidden))
        assertEquals(Color.TRANSPARENT, visible.getPixel(10, 10))
        PosterRenderer.writePng(visible, File("build/outputs/poster-transparent-geography.png"))
        visible.recycle(); hidden.recycle()
    }
}
