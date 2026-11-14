package com.niutrip.app.ui.detail.map

import com.niutrip.app.core.GeoBbox
import com.niutrip.app.core.route.CanonicalRoutePoint
import com.niutrip.app.core.route.RouteLodBuilder
import com.niutrip.app.core.route.RouteRenderPolicy
import com.niutrip.app.core.route.RouteRenderQuery
import com.niutrip.app.core.route.RouteRenderKey
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteOverlayControllerTest {
    private val day = LocalDate.of(2026, 1, 1)

    private fun index(points: Int) = RouteLodBuilder.build("t1", (0 until points).map {
        CanonicalRoutePoint("p%02d".format(it), it * 60_000L, 116.0 + it * 0.001, 39.0, false, day)
    })

    private fun model(generation: Long, zoom: Float) = RouteRenderPolicy.buildRenderModel(
        index(10), RouteRenderQuery(generation, zoom, null, null), useLod = false)

    /** 录制宿主：按顺序记录 add/remove，用于验证原子切换顺序（方案 §6.8）。 */
    private class RecordingHost : RouteOverlayHost {
        val calls = mutableListOf<String>()
        val live = mutableSetOf<Int>()
        private var next = 0
        override fun addRoutePolyline(spec: RouteOverlaySpec): Int {
            val handle = next++
            calls += "add$handle"
            live += handle
            return handle
        }
        override fun removeRoutePolyline(handle: Int) {
            calls += "remove$handle"
            live -= handle
        }
    }

    @Test
    fun `all new polylines are added before any old one is removed`() {
        val host = RecordingHost()
        val controller = RouteOverlayController(host)
        val geometry = index(10)

        controller.replace(model(1, 12f), geometry)
        val firstCalls = host.calls.toList()
        controller.replace(model(2, 16f), geometry)
        val secondCalls = host.calls.drop(firstCalls.size)

        // 第二次替换：所有 add 在任何 remove 之前（方案 §2.4.6 原子切换）
        val firstRemove = secondCalls.indexOfFirst { it.startsWith("remove") }
        val lastAdd = secondCalls.indexOfLast { it.startsWith("add") }
        assertTrue("adds must precede removes, got $secondCalls", lastAdd < firstRemove)
        // 无泄漏：最终活跃 overlay 与模型 polyline 数一致
        assertEquals(1, host.live.size)
    }

    @Test
    fun `replace with same key does not rebuild`() {
        val host = RecordingHost()
        val controller = RouteOverlayController(host)
        val geometry = index(10)
        val model = model(1, 12f)

        controller.replace(model, geometry)
        val callsAfterFirst = host.calls.size
        controller.replace(model, geometry)

        assertEquals(callsAfterFirst, host.calls.size)
        assertEquals(model.key, controller.appliedKey)
    }

    @Test
    fun `clear removes all overlays`() {
        val host = RecordingHost()
        val controller = RouteOverlayController(host)
        controller.replace(model(1, 12f), index(10))

        controller.clear()

        assertTrue(host.live.isEmpty())
        assertEquals(null, controller.appliedKey)
    }

    @Test
    fun `different keys rebuild`() {
        val host = RecordingHost()
        val controller = RouteOverlayController(host)
        val geometry = index(10)

        controller.replace(model(1, 8f), geometry)
        controller.replace(model(1, 16f), geometry)

        assertTrue(host.calls.count { it.startsWith("add") } == 2)
    }
}
