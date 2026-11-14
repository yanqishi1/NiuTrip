package com.niutrip.app.core.route

import com.niutrip.app.core.toEpochMillis
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLodBuilderTest {
    private val baseTime = LocalDateTime.of(2026, 1, 1, 8, 0).toEpochMillis()
    private val day1 = LocalDate.of(2026, 1, 1)
    private val day2 = LocalDate.of(2026, 1, 2)

    private fun p(
        id: String,
        minute: Long,
        lon: Double,
        lat: Double,
        checkin: Boolean = false,
        day: LocalDate = day1,
    ) = CanonicalRoutePoint(id, baseTime + minute * 60_000, lon, lat, checkin, day)

    private fun lodIds(index: RouteGeometryIndex, band: Int): Set<String> {
        val tolerance = RouteRenderPolicy.toleranceMeters(band, index.referenceLatitude)
        return RouteViewportQuery.lodIndices(index, tolerance).map(index::idAt).toSet()
    }

    /** 带抖动的锯齿路径：不同偏移的点在不同容差下逐级浮现，用来验证层级嵌套。 */
    private fun zigzagPoints(count: Int): List<CanonicalRoutePoint> = (0 until count).map { i ->
        val lon = 116.0 + i * 0.0008
        val lat = 39.0 + when {
            i % 7 == 0 -> 0.0100   // 大偏移：低档也保留
            i % 5 == 0 -> 0.0030   // 中偏移：中档保留
            i % 3 == 0 -> 0.0010   // 小偏移：高档保留
            else -> 0.0
        }
        p("z%03d".format(i), i.toLong(), lon, lat)
    }

    @Test
    fun `lod sets are nested across all bands`() {
        val index = RouteLodBuilder.build("t1", zigzagPoints(120))
        val bands = RouteLodParams.LOD_ZOOM_BANDS
        for (i in 0 until bands.size - 1) {
            val lower = lodIds(index, bands[i])
            val higher = lodIds(index, bands[i + 1])
            assertTrue("band ${bands[i]} set must be subset of band ${bands[i + 1]}",
                higher.containsAll(lower))
        }
    }

    @Test
    fun `mandatory points survive at every band`() {
        val points = listOf(
            p("a", 0, 116.000, 39.000),
            p("b", 10, 116.002, 39.002),
            p("c", 20, 116.004, 39.000, checkin = true),
            p("d", 30, 116.006, 39.002),
            p("e", 40, 116.008, 39.000),
            p("f", 50, 116.010, 39.000, day = day2),
            p("g", 60, 116.012, 39.002, day = day2),
        )
        val index = RouteLodBuilder.build("t1", points)
        // 全程首尾 + 每日首尾 + MANUAL 打卡点
        val expectedMandatory = setOf("a", "e", "f", "g", "c")
        val actualMandatory = (0 until index.pointCount)
            .filter(index::isMandatoryAt).map(index::idAt).toSet()
        assertEquals(expectedMandatory, actualMandatory)
        RouteLodParams.LOD_ZOOM_BANDS.forEach { band ->
            assertTrue("mandatory missing at band $band", lodIds(index, band).containsAll(expectedMandatory))
        }
    }

    @Test
    fun `same input yields identical fingerprint and importance`() {
        val points = zigzagPoints(50)
        val first = RouteLodBuilder.build("t1", points)
        val second = RouteLodBuilder.build("t1", points)
        assertEquals(first.sourceFingerprint, second.sourceFingerprint)
        assertEquals(RouteLodBuilder.sourceFingerprint(points), first.sourceFingerprint)
        assertTrue(first.importance.contentEquals(second.importance))
        assertTrue(first.mandatory.contentEquals(second.mandatory))
    }

    @Test
    fun `fingerprint changes on any semantic field change`() {
        val base = listOf(p("a", 0, 116.0, 39.0), p("b", 1, 116.001, 39.0))
        val baseline = RouteLodBuilder.sourceFingerprint(base)
        // 新增/删除/编辑/类型变化都必须改变指纹
        assertTrue(RouteLodBuilder.sourceFingerprint(base + p("c", 2, 116.002, 39.0)) != baseline)
        assertTrue(RouteLodBuilder.sourceFingerprint(base.drop(1)) != baseline)
        assertTrue(RouteLodBuilder.sourceFingerprint(listOf(p("a", 0, 116.0001, 39.0), base[1])) != baseline)
        assertTrue(RouteLodBuilder.sourceFingerprint(listOf(p("a", 0, 116.0, 39.0001), base[1])) != baseline)
        assertTrue(RouteLodBuilder.sourceFingerprint(listOf(p("a", 5, 116.0, 39.0), base[1])) != baseline)
        assertTrue(RouteLodBuilder.sourceFingerprint(listOf(p("a", 0, 116.0, 39.0, checkin = true), base[1])) != baseline)
    }

    @Test
    fun `collinear interior points get zero importance and drop out`() {
        val points = (0 until 10).map { i -> p("s%02d".format(i), i.toLong(), 116.0 + i * 0.001, 39.0) }
        val index = RouteLodBuilder.build("t1", points)
        for (i in 1 until 9) assertEquals("collinear point must have zero importance", 0f, index.importanceAt(i), 0f)
        assertEquals(setOf("s00", "s09"), lodIds(index, 20))  // 最高档也只有首尾
    }

    @Test
    fun `hairpin apex is retained at low band`() {
        val points = listOf(
            p("h01", 0, 116.000, 39.000),
            p("h02", 1, 116.002, 39.004),
            p("h03", 2, 116.004, 39.008),
            p("h04", 3, 116.006, 39.012),   // 顶点：距首尾弦约 1.3km
            p("h05", 4, 116.008, 39.008),
            p("h06", 5, 116.010, 39.004),
            p("h07", 6, 116.012, 39.000),
        )
        val index = RouteLodBuilder.build("t1", points)
        assertEquals(setOf("h01", "h04", "h07"), lodIds(index, 8))
        assertTrue("apex importance should exceed 1km", index.importanceAt(3) > 1_000f)
    }

    @Test
    fun `closed loop with coincident endpoints does not produce NaN`() {
        val points = listOf(
            p("l1", 0, 116.000, 39.000),
            p("l2", 1, 116.000, 39.005),
            p("l3", 2, 116.005, 39.010),
            p("l4", 3, 116.010, 39.005),
            p("l5", 4, 116.010, 39.000),
            p("l6", 5, 116.000, 39.000),   // 回到起点坐标（不同 id/time）
        )
        val index = RouteLodBuilder.build("t1", points)
        for (i in 0 until index.pointCount) {
            assertTrue("importance must be finite", index.importanceAt(i).isFinite())
        }
        // 退化弦（起点=终点）按到点距离处理：最远角点必须保留
        assertTrue(index.importanceAt(2) > 800f)
        assertTrue(index.importanceAt(4) > 400f)
    }

    @Test
    fun `same time different ids sort deterministically by id`() {
        val points = listOf(
            p("b", 0, 116.003, 39.0),
            p("a", 0, 116.001, 39.0),
            p("c", 0, 116.002, 39.0),
            p("d", 1, 116.004, 39.0),
        )
        val index = RouteLodBuilder.build("t1", points)
        assertEquals(listOf("a", "b", "c", "d"), (0 until index.pointCount).map(index::idAt))
    }

    @Test
    fun `high latitude projection stays accurate`() {
        // 60°N 的北南向弦 + 东向偏移点：等距投影必须用 cos(refLat) 缩放经度差
        val points = listOf(
            p("n0", 0, 24.000, 60.000),
            p("n1", 1, 24.002, 60.002),  // 距北南向弦正东 0.002° 经度
            p("n2", 2, 24.000, 60.004),
        )
        val index = RouteLodBuilder.build("t1", points)
        val n1 = index.importanceAt(1)
        // 0.002° 经度在 60°N ≈ 111m；若漏乘 cos(60°) 会得到 ~222m
        assertTrue("expected ~111m importance, got $n1", abs(n1 - 111f) < 25f)
    }

    @Test
    fun `multi day parts and chunks cover every point exactly`() {
        val day1Points = (0 until 30).map { i -> p("a%02d".format(i), i.toLong(), 116.0 + i * 0.0002, 39.0) }
        val day2Points = (0 until 20).map { i -> p("b%02d".format(i), 60L + i, 116.0 + i * 0.0002, 39.001, day = day2) }
        val index = RouteLodBuilder.build("t1", day1Points + day2Points.shuffled(java.util.Random(7)))
        assertEquals(2, index.routeParts.size)
        assertEquals(listOf(30, 20), index.routeParts.map { it.endIndexInclusive - it.startIndex + 1 })
        assertEquals(0, index.routeParts[0].startIndex)
        assertEquals(49, index.routeParts[1].endIndexInclusive)
        // chunk 无缝覆盖全部点：排序后首=0、尾=pointCount-1；相邻 chunk 只在同一个 part 内共享边界点
        val sortedChunks = index.chunks.sortedBy(RouteChunk::startIndex)
        assertEquals(0, sortedChunks.first().startIndex)
        assertEquals(index.pointCount - 1, sortedChunks.last().endIndexInclusive)
        sortedChunks.groupBy(RouteChunk::partId).values.forEach { partChunks ->
            partChunks.sortedBy(RouteChunk::startIndex).zipWithNext { left, right ->
                assertEquals(left.endIndexInclusive, right.startIndex)
            }
        }
        assertTrue(index.chunks.all { it.endIndexInclusive - it.startIndex + 1 <= RouteLodParams.ROUTE_CHUNK_POINTS })
    }

    @Test
    fun `build block boundaries are mandatory for long parts`() {
        // 超过两个计算块的直线：块边界点必须强制保留
        val blockStep = RouteLodParams.MAX_BUILD_BLOCK_POINTS - 1  // 相邻块重叠一个点
        val count = 2 * blockStep + 10
        val points = (0 until count).map { i -> p("m%05d".format(i), i.toLong(), 116.0 + i * 0.00001, 39.0) }
        val index = RouteLodBuilder.build("t1", points)
        assertTrue(index.isMandatoryAt(0))
        assertTrue(index.isMandatoryAt(blockStep))
        assertTrue(index.isMandatoryAt(2 * blockStep))
        assertTrue(index.isMandatoryAt(index.pointCount - 1))
    }

    @Test
    fun `buildFromCache assembles structure around cached payload`() {
        val points = zigzagPoints(60)
        val full = RouteLodBuilder.build("t1", points)
        // 缓存命中路径：importance 全 0 也必须按输入使用，不重算 DP；结构（part/chunk）一致
        val zeros = FloatArray(full.pointCount)
        val cached = RouteLodBuilder.buildFromCache("t1", points, zeros, full.mandatory.copyOf())
        assertEquals(full.sourceFingerprint, cached.sourceFingerprint)
        assertEquals(full.routeParts, cached.routeParts)
        assertEquals(full.chunks, cached.chunks)
        for (i in 0 until cached.pointCount) assertEquals(0f, cached.importanceAt(i), 0f)
        assertTrue(full.mandatory.contentEquals(cached.mandatory))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildFromCache rejects payload length mismatch`() {
        val points = zigzagPoints(10)
        RouteLodBuilder.buildFromCache("t1", points, FloatArray(3), BooleanArray(3))
    }
}
