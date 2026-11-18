package com.niutrip.app.ui.share

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.TrackDto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterWireframeRendererTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun snapshot() = PosterSnapshotBuilder.build(
        TrackDto("t", "测试环线", track_record_mode = "AUTO", track_status = "FINISHED"),
        listOf(
            PointDto("s", longitude = 110.0, latitude = 30.0, point_name = "武汉", point_time = "2026-09-11T08:00:00", point_source = "MANUAL"),
            PointDto("a", longitude = 100.0, latitude = 28.0, point_name = "雪山", point_time = "2026-09-11T09:00:00", point_source = "MANUAL"),
            PointDto("b", longitude = 120.0, latitude = 23.0, point_name = "海边", point_time = "2026-09-11T10:00:00", point_source = "MANUAL"),
            PointDto("e", longitude = 110.0001, latitude = 30.0001, point_name = "武汉", point_time = "2026-09-11T11:00:00", point_source = "AUTO")),
        emptyList(), 1)!!

    @Test fun `empty selection keeps route and endpoints without checkin names across all ratios`() {
        val snapshot = snapshot()
        val model = PosterRouteModel.build(snapshot.days)
        val outline = PosterMapOutline.load(context)
        for (ratio in listOf(PosterRatioKind.R3_4, PosterRatioKind.SQUARE, PosterRatioKind.R9_16)) {
            val spec = PosterCanvasSpec.create(ratio)
            val bitmap = Bitmap.createBitmap(spec.widthPx, spec.heightPx, Bitmap.Config.ARGB_8888)
            val report = PosterWireframeRenderer.draw(Canvas(bitmap), bitmap.width.toFloat(), bitmap.height.toFloat(),
                PosterDocument(), snapshot, model, PosterEndpointNames(), outline, PosterWireframeRenderer.loadCheckinIcon(context))
            assertEquals(PosterWireframeRenderer.AnnotationReport(), report)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.all { Color.alpha(it) == 255 })
            assertTrue(pixels.contains(PosterWireframeRenderer.CORAL))
            assertTrue(pixels.contains(PosterWireframeRenderer.RED))
            assertEquals(PosterWireframeRenderer.PAPER, bitmap.getPixel(0, 0))
            bitmap.recycle()
        }
    }

    @Test fun `explicit inclusion excludes other checkins and layout is independent of preview resolution`() {
        val snapshot = snapshot()
        val model = PosterRouteModel.build(snapshot.days)
        val outline = PosterMapOutline.load(context)
        val document = PosterDocument(mapRouteStyle = PosterRouteStyleState(selectedCheckinIds = setOf("a")))
        val reports = listOf(360 to 480, 2160 to 2880).map { (w, h) ->
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val report = PosterWireframeRenderer.draw(Canvas(bitmap), w.toFloat(), h.toFloat(), document,
                snapshot, model, PosterEndpointNames(), outline, PosterWireframeRenderer.loadCheckinIcon(context))
            bitmap.recycle()
            report
        }
        assertEquals(reports.first(), reports.last())
        assertEquals(1, reports.first().selected)
        assertEquals(1, reports.first().named)
    }

    @Test fun `compact names avoid entire route segments and other labels without budget cap`() {
        val time = java.time.LocalDateTime.of(2026, 9, 11, 12, 0)
        val candidates = (0 until 32).map {
            PosterMarkerCandidate(com.niutrip.app.core.PointLite("$it", time, 110.0, 30.0, true, "景点$it"),
                90f + it % 8 * 200, 100f + it / 8 * 200, it)
        }
        val route = listOf(20f to 180f, 1780f to 180f)
        val result = PosterCompactLabels.layout(candidates, emptyList(), route, 1800f, 1200f, emptyMap()) { name, size -> name.length * size }
        assertEquals(32, result.size)
        result.forEachIndexed { i, label ->
            assertFalse(label.box.expanded(2f).intersectsSegment(route[0], route[1]))
            result.drop(i + 1).forEach { assertFalse(label.box.overlaps(it.box)) }
            assertTrue(label.box.left >= 0 && label.box.right <= 1800)
        }
    }

    @Test fun `photo route shares map icons endpoint colors and explicit checkin selection`() {
        val snapshot = snapshot()
        val model = PosterRouteModel.build(snapshot.days)
        val icon = PosterWireframeRenderer.loadCheckinIcon(context)
        val selected = setOf("a")
        val map = Bitmap.createBitmap(1800, 2400, Bitmap.Config.ARGB_8888)
        val mapReport = PosterWireframeRenderer.draw(Canvas(map), 1800f, 2400f,
            PosterDocument(mapRouteStyle = PosterRouteStyleState(selectedCheckinIds = selected)), snapshot, model,
            PosterEndpointNames(), PosterMapOutline.load(context), icon)
        val photo = Bitmap.createBitmap(1640, 1360, Bitmap.Config.ARGB_8888)
        val photoReport = PosterRenderer.drawRouteModule(Canvas(photo), model, 0f, 0f, 1640f, 1360f,
            PosterRenderer.RouteStyle(PosterRouteTheme.SINGLE, true, selectedCheckinIds = selected), icon)
        assertEquals(mapReport, photoReport)
        assertEquals(1, photoReport.named)
        fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        val mapPixels = pixels(map)
        val photoPixels = pixels(photo)
        for (actual in listOf(mapPixels, photoPixels)) {
            assertTrue("single route uses map coral", actual.contains(PosterWireframeRenderer.CORAL))
            assertTrue("endpoints use map red", actual.contains(PosterWireframeRenderer.RED))
            assertTrue("checkins use the yellow pin asset", actual.any {
                Color.alpha(it) > 200 && Color.red(it) > 220 && Color.green(it) > 210 && Color.blue(it) in 80..170
            })
        }
        val unselected = Bitmap.createBitmap(1640, 1360, Bitmap.Config.ARGB_8888)
        val emptyReport = PosterRenderer.drawRouteModule(Canvas(unselected), model, 0f, 0f, 1640f, 1360f,
            PosterRenderer.RouteStyle(PosterRouteTheme.SINGLE, true, selectedCheckinIds = emptySet()), icon)
        assertEquals(PosterWireframeRenderer.AnnotationReport(), emptyReport)
        assertFalse(photo.sameAs(unselected))
        val small = Bitmap.createBitmap(328, 272, Bitmap.Config.ARGB_8888)
        val previewReport = PosterRenderer.drawRouteModule(Canvas(small), model, 0f, 0f, 328f, 272f,
            PosterRenderer.RouteStyle(PosterRouteTheme.SINGLE, true, selectedCheckinIds = selected), icon)
        assertEquals(photoReport, previewReport)
        java.io.File("build/outputs").mkdirs()
        PosterRenderer.writePng(photo, java.io.File("build/outputs/poster-photo-shared-route.png"))
        listOf(map, photo, unselected, small).forEach(Bitmap::recycle)
    }

    @Test fun `photo single route has the same relative stroke width as map route`() {
        val track = PosterSnapshotBuilder.build(TrackDto("flat", "横向轨迹", track_record_mode = "AUTO", track_status = "FINISHED"),
            listOf(PointDto("s", longitude = 110.0, latitude = 30.0, point_time = "2026-09-11T08:00:00", point_source = "AUTO"),
                PointDto("e", longitude = 120.0, latitude = 30.0, point_time = "2026-09-11T09:00:00", point_source = "AUTO")),
            emptyList(), 1)!!
        val model = PosterRouteModel.build(track.days)
        val icon = PosterWireframeRenderer.loadCheckinIcon(context)
        val map = Bitmap.createBitmap(1800, 2400, Bitmap.Config.ARGB_8888)
        PosterWireframeRenderer.draw(Canvas(map), 1800f, 2400f, PosterDocument(), track, model,
            PosterEndpointNames(), PosterMapOutline.load(context), icon)
        val photo = Bitmap.createBitmap(1640, 480, Bitmap.Config.ARGB_8888)
        PosterRenderer.drawRouteModule(Canvas(photo), model, 0f, 0f, 1640f, 480f,
            PosterRenderer.RouteStyle(PosterRouteTheme.SINGLE, false, false), icon)
        val rect = PosterWireframeRenderer.mapRect(2400f)
        fun thickness(bitmap: Bitmap, x: Int, centerY: Int) = (centerY - 30..centerY + 30).count {
            bitmap.getPixel(x, it) == PosterWireframeRenderer.CORAL
        }
        val mapWidth = thickness(map, 900, rect.centerY().toInt())
        val photoWidth = thickness(photo, 820, 240)
        assertTrue(mapWidth in 4..6)
        assertTrue(photoWidth in 4..6)
        assertTrue(kotlin.math.abs(mapWidth - photoWidth) <= 1)
        assertEquals(Color.TRANSPARENT, photo.getPixel(820, 200))
        map.recycle(); photo.recycle()
    }
}
