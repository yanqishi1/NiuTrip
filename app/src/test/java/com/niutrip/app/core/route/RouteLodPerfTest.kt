package com.niutrip.app.core.route

import com.niutrip.app.core.GeoBbox
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能基准（方案 §6.8/§9）：10k/50k/100k 点构建与视口查询。
 * 只记录耗时并做宽松的防回归上限，不把具体毫秒写成产品承诺（§9.1）。
 */
class RouteLodPerfTest {
    private val day = LocalDate.of(2026, 1, 1)

    private fun syntheticTrack(count: Int): List<CanonicalRoutePoint> {
        val random = java.util.Random(42)
        return (0 until count).map { i ->
            val phase = i / 37.0
            CanonicalRoutePoint(
                pointId = "p%06d".format(i),
                timeMillis = 1_768_230_000_000 + i * 1_000,
                longitude = 116.0 + i * 0.00002 + random.nextDouble() * 0.000008,
                latitude = 39.0 + kotlin.math.sin(phase) * 0.004 + random.nextDouble() * 0.000008,
                isCheckin = i % 997 == 0,
                dayKey = day,
            )
        }
    }

    @Test
    fun `build and viewport query scale to 100k points`() {
        for (count in listOf(10_000, 50_000, 100_000)) {
            val points = syntheticTrack(count)
            val buildStart = System.nanoTime()
            val index = RouteLodBuilder.build("perf", points)
            val buildMs = (System.nanoTime() - buildStart) / 1_000_000.0

            val tolerance = RouteRenderPolicy.toleranceMeters(16, index.referenceLatitude)
            val box = GeoBbox(116.05, 38.999, 116.15, 39.002)
            val queryStart = System.nanoTime()
            val result = RouteViewportQuery.query(index, tolerance, box)
            val queryMs = (System.nanoTime() - queryStart) / 1_000_000.0

            println("RouteLod perf: build %dk = %.1fms; viewport query = %.2fms; kept=%d; intervals=%d"
                .format(count / 1_000, buildMs, queryMs, result.keptIndices.size, result.intervals.size))
            assertTrue("build $count took ${buildMs}ms", buildMs < 30_000)
            assertTrue("query $count took ${queryMs}ms", queryMs < 5_000)
            assertTrue("query must return something", result.intervals.isNotEmpty())
        }
    }

    @Test
    fun `nesting holds on 10k synthetic track`() {
        val index = RouteLodBuilder.build("perf", syntheticTrack(10_000))
        val bands = RouteLodParams.LOD_ZOOM_BANDS
        for (i in 0 until bands.size - 1) {
            val lower = RouteViewportQuery.lodIndices(
                index, RouteRenderPolicy.toleranceMeters(bands[i], index.referenceLatitude)).toSet()
            val higher = RouteViewportQuery.lodIndices(
                index, RouteRenderPolicy.toleranceMeters(bands[i + 1], index.referenceLatitude)).toSet()
            assertTrue("band ${bands[i]} must nest into ${bands[i + 1]}", higher.containsAll(lower))
        }
    }
}
