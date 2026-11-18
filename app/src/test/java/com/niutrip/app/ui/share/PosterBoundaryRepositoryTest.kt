package com.niutrip.app.ui.share

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.niutrip.app.core.DayGrouper
import com.niutrip.app.core.PointLite
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterBoundaryRepositoryTest {
    private fun area(code: Int, level: String, west: Double, east: Double, south: Double = 20.0, north: Double = 22.0) =
        JSONObject().put("type", "Feature").put("properties", JSONObject().put("adcode", code).put("level", level))
            .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(JSONArray().apply {
                listOf(west to south, east to south, east to north, west to north, west to south).forEach { (x, y) ->
                    put(JSONArray().put(x).put(y))
                }
            })))
    private fun json(vararg areas: JSONObject) = JSONObject().put("features", JSONArray(areas.toList())).toString()
    private fun outline(vararg areas: JSONObject) = PosterMapOutline.fromJson(json(*areas))
    private fun points(vararg longitude: Double) = longitude.mapIndexed { index, lon ->
        PointLite("p$index", LocalDateTime.of(2026, 11, 18, 10, index), lon, 21.0, false)
    }
    private val provinces get() = outline(area(100000, "province", 100.0, 102.0), area(200000, "province", 102.0, 104.0))
    private val cities get() = outline(area(100100, "city", 100.0, 101.0), area(100200, "city", 101.0, 102.0))
    private val districts get() = outline(area(100101, "district", 100.0, 100.5), area(100102, "district", 100.5, 101.0))

    @Test fun `cross province route uses packaged province contours without requests`() = runTest {
        val result = PosterBoundaryRepository.select(provinces, points(100.5, 102.5)) { error("must stay offline") }
        assertEquals("省界", result.level)
        assertNull(result.warning)
    }

    @Test fun `same province crossing cities loads city boundaries`() = runTest {
        val requested = mutableListOf<Int>()
        val result = PosterBoundaryRepository.select(provinces, points(100.2, 101.5)) {
            requested.add(it); cities
        }
        assertEquals(listOf(100000), requested)
        assertEquals("市界", result.level)
    }

    @Test fun `same city crossing districts loads district boundaries`() = runTest {
        val requested = mutableListOf<Int>()
        val result = PosterBoundaryRepository.select(provinces, points(100.2, 100.8)) {
            requested.add(it); if (it == 100000) cities else districts
        }
        assertEquals(listOf(100000, 100100), requested)
        assertEquals("区县界", result.level)
    }

    @Test fun `same county keeps county context and transparent interior`() = runTest {
        val points = points(100.2, 100.3)
        val result = PosterBoundaryRepository.select(provinces, points) { if (it == 100000) cities else districts }
        val model = PosterRouteModel.build(DayGrouper.group(points))
        val transform = result.outline.photoTransform(model, 600f, 400f, 30f)
        val a = transform.map(model.bounds.minX, model.bounds.minY)
        val b = transform.map(model.bounds.maxX, model.bounds.maxY)
        assertTrue("county context should surround the short route", b.x - a.x < 500)
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        result.outline.draw(Canvas(bitmap), model, transform, 0f, 0f, 600f, 400f, true, attribution = false)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(300, 200))
        var lines = 0
        var opaque = 0
        for (y in 0 until 400) for (x in 0 until 600) {
            val alpha = Color.alpha(bitmap.getPixel(x, y))
            if (alpha in 1..254) lines++
            if (alpha == 255) opaque++
        }
        assertTrue(lines > 50)
        assertEquals(0, opaque)
        var longestRun = 0
        var run = 0
        for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, 200)) > 100) {
                run++
                longestRun = maxOf(longestRun, run)
            } else run = 0
        }
        assertTrue("photo contours must remain legible at preview resolution", longestRun >= 2)
        bitmap.recycle()
    }

    @Test fun `municipality goes directly to district level`() = runTest {
        var requests = 0
        val result = PosterBoundaryRepository.select(provinces, points(100.2, 100.8)) { requests++; districts }
        assertEquals(1, requests)
        assertEquals("区县界", result.level)
    }

    @Test fun `failed detail fetch retains last valid level and cancellation propagates`() = runTest {
        val result = PosterBoundaryRepository.select(provinces, points(100.2, 100.3)) {
            if (it == 100000) cities else error("offline")
        }
        assertEquals("市界", result.level)
        assertNotNull(result.warning)
        val cancelled = runCatching { PosterBoundaryRepository.select(provinces, points(100.2)) { throw CancellationException() } }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
    }

    @Test fun `cached administrative data is reused by a new repository while offline`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        java.io.File(context.cacheDir, "poster_boundaries").deleteRecursively()
        val wuhan = listOf(PointLite("a", LocalDateTime.of(2026, 11, 18, 10, 0), 114.3, 30.59, false))
        var calls = 0
        val first = PosterBoundaryRepository(context) { code ->
            calls++
            if (code == 420000) json(area(420100, "city", 114.0, 115.0, 30.0, 31.0))
            else json(area(420102, "district", 114.0, 115.0, 30.0, 31.0))
        }.forPoints(wuhan)
        assertEquals("区县界", first.level)
        assertEquals(2, calls)
        val second = PosterBoundaryRepository(context) { error("offline") }.forPoints(wuhan)
        assertEquals("区县界", second.level)
        assertNull(second.warning)
    }
}
