package com.niutrip.app.core.route

import com.niutrip.app.core.GeoBbox
import com.niutrip.app.core.toBeijingDateTime
import com.niutrip.app.core.toEpochMillis
import java.time.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台无关共享夹具（方案 §8.2）：测试资源中的 JSON 与 iOS 使用相同内容，
 * 两端在每个档位保留的 point ID 集合必须完全一致（方案 §8.3）。
 */
class RouteLodFixtureTest {
    @Serializable
    private data class FixturePoint(val id: String, val time: String, val lon: Double, val lat: Double, val checkin: Boolean = false)

    @Serializable
    private data class FixtureViewport(val minLng: Double, val minLat: Double, val maxLng: Double, val maxLat: Double)

    @Serializable
    private data class RouteFixture(
        val name: String,
        val description: String = "",
        val viewport: FixtureViewport? = null,
        val expectedViewportPolylines: List<List<String>>? = null,
        val points: List<FixturePoint>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixtureText(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("route-lod/$name.json"),
    ) { "Missing route-lod/$name.json test resource" }.bufferedReader().use { it.readText() }

    private fun load(name: String): RouteFixture =
        json.decodeFromString(RouteFixture.serializer(), fixtureText(name))

    private fun RouteFixture.toPoints(): List<CanonicalRoutePoint> = points.map { point ->
        val time = LocalDateTime.parse(point.time)
        CanonicalRoutePoint(
            pointId = point.id,
            timeMillis = time.toEpochMillis(),
            longitude = point.lon,
            latitude = point.lat,
            isCheckin = point.checkin,
            dayKey = time.toEpochMillis().toBeijingDateTime().toLocalDate(),
        )
    }

    private fun expectedLod(): Map<String, Map<String, List<String>>> =
        json.decodeFromString<Map<String, Map<String, List<String>>>>(
            fixtureText("expected-lod"))

    @Test
    fun `every fixture lod output matches expected-lod json at every band`() {
        val expected = expectedLod()
        listOf("straight", "hairpin", "loop", "cross-viewport", "multi-day-checkins").forEach { name ->
            val fixture = load(name)
            val index = RouteLodBuilder.build(fixture.name, fixture.toPoints())
            RouteLodParams.LOD_ZOOM_BANDS.forEach { band ->
                val tolerance = RouteRenderPolicy.toleranceMeters(band, index.referenceLatitude)
                val actual = RouteViewportQuery.lodIndices(index, tolerance).map(index::idAt).sorted()
                assertEquals("$name band $band", expected.getValue(name).getValue(band.toString()).sorted(), actual)
            }
        }
    }

    @Test
    fun `cross viewport fixture polylines match expected ids`() {
        val fixture = load("cross-viewport")
        val index = RouteLodBuilder.build(fixture.name, fixture.toPoints())
        val viewport = fixture.viewport!!
        val model = RouteRenderPolicy.buildRenderModel(
            index,
            RouteRenderQuery(
                generation = 1,
                zoom = 14f,
                viewport = viewport.let { GeoBbox(it.minLng, it.minLat, it.maxLng, it.maxLat) },
                selectedDayIndex = null,
            ),
        )
        val actual = model.polylines.map { polyline -> polyline.pointIndices.map(index::idAt) }
        assertEquals(fixture.expectedViewportPolylines, actual)
    }

    @Test
    fun `fixtures are deterministic across rebuilds`() {
        listOf("straight", "hairpin", "loop", "cross-viewport", "multi-day-checkins").forEach { name ->
            val fixture = load(name)
            val first = RouteLodBuilder.build(fixture.name, fixture.toPoints())
            val second = RouteLodBuilder.build(fixture.name, fixture.toPoints().shuffled())
            assertEquals("$name fingerprint", first.sourceFingerprint, second.sourceFingerprint)
            assertTrue("$name importance", first.importance.contentEquals(second.importance))
            assertEquals("$name order", first.ids.toList(), second.ids.toList())
        }
    }

    @Test
    fun `lod sets stay nested for every fixture`() {
        listOf("straight", "hairpin", "loop", "cross-viewport", "multi-day-checkins").forEach { name ->
            val fixture = load(name)
            val index = RouteLodBuilder.build(fixture.name, fixture.toPoints())
            val bands = RouteLodParams.LOD_ZOOM_BANDS
            for (i in 0 until bands.size - 1) {
                val lower = RouteViewportQuery.lodIndices(
                    index, RouteRenderPolicy.toleranceMeters(bands[i], index.referenceLatitude)).toSet()
                val higher = RouteViewportQuery.lodIndices(
                    index, RouteRenderPolicy.toleranceMeters(bands[i + 1], index.referenceLatitude)).toSet()
                assertTrue("$name band ${bands[i]} subset of ${bands[i + 1]}", higher.containsAll(lower))
            }
        }
    }
}
