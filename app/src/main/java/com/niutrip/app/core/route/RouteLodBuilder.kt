package com.niutrip.app.core.route

import com.niutrip.app.core.GeoBbox
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 层级 Douglas-Peucker 重要度构建器（方案 §4.1/§4.2）。
 *
 * 纯 Kotlin 无 Android 依赖，可跑 JVM 单测；显式栈迭代（无递归），计算块上限
 * [RouteLodParams.MAX_BUILD_BLOCK_POINTS]，可通过 [ensureActive] 支持协程取消。
 *
 * 嵌套性来源：每个点只有一个固定 importance（米），任意容差 t 的保留集
 * {i : mandatory[i] || importance[i] >= t} 随 t 单调收缩，天然满足 LOD(z) ⊆ LOD(z+1)。
 */
object RouteLodBuilder {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** 数据指纹（方案 §3.5）：pointId + time + longitudeE6 + latitudeE6 + isCheckin 的稳定 SHA-256。 */
    fun sourceFingerprint(points: List<CanonicalRoutePoint>): String {
        val sorted = points.sortedWith(compareBy({ it.timeMillis }, { it.pointId }))
        return fingerprintOf(sorted)
    }

    /**
     * 构建不可变几何索引：按 (time, pointId) 排序、计算 RoutePart/RouteChunk、
     * 强制保留标记与层级 importance（米）。
     */
    fun build(
        trackId: String,
        points: List<CanonicalRoutePoint>,
        ensureActive: (() -> Unit)? = null,
    ): RouteGeometryIndex {
        val structure = assemble(trackId, points)
        val importance = FloatArray(structure.sorted.size)
        for (part in structure.parts) {
            var blockStart = part.startIndex
            while (true) {
                val blockEnd = minOf(blockStart + structure.blockStep, part.endIndexInclusive)
                computeBlockImportance(
                    structure.lons, structure.lats, blockStart, blockEnd,
                    structure.mandatory, importance, ensureActive)
                if (blockEnd == part.endIndexInclusive) break
                blockStart = blockEnd
            }
        }
        return structure.toIndex(importance)
    }

    /** 缓存命中路径：结构重建 + 直接注入缓存的 importance/mandatory（方案 §5.2，不重算 DP）。 */
    fun buildFromCache(
        trackId: String,
        points: List<CanonicalRoutePoint>,
        importance: FloatArray,
        mandatory: BooleanArray,
    ): RouteGeometryIndex {
        require(importance.size == mandatory.size) { "缓存 payload 长度不一致" }
        val structure = assemble(trackId, points)
        require(structure.sorted.size == importance.size) { "缓存点数与完整点数不一致" }
        // 派生缓存不可信：即使 payload 长度和指纹都匹配，也不能允许损坏的 bitset
        // 取消当前算法重新计算出的首尾、打卡点和块边界强制位。
        val safeMandatory = mandatory.copyOf()
        for (i in safeMandatory.indices) {
            safeMandatory[i] = safeMandatory[i] || structure.mandatory[i]
        }
        return structure.toIndex(importance.copyOf(), safeMandatory)
    }

    /** 排序 + 数组 + part/chunk + 强制保留标记的结构装配；importance 由调用方注入。 */
    private class RouteStructure(
        val trackId: String,
        val sorted: List<CanonicalRoutePoint>,
        val ids: Array<String>,
        val times: LongArray,
        val lons: DoubleArray,
        val lats: DoubleArray,
        val dayIndices: IntArray,
        val mandatory: BooleanArray,
        val parts: List<RoutePart>,
        val chunks: List<RouteChunk>,
        val blockStep: Int,
        private val referenceLatitude: Double,
    ) {
        fun toIndex(importance: FloatArray, mandatory: BooleanArray = this.mandatory): RouteGeometryIndex =
            RouteGeometryIndex(
                trackId = trackId,
                sourceFingerprint = fingerprintOf(sorted),
                referenceLatitude = referenceLatitude,
                pointCount = sorted.size,
                ids = ids,
                times = times,
                longitudes = lons,
                latitudes = lats,
                dayIndices = dayIndices,
                mandatory = mandatory,
                importance = importance,
                routeParts = parts,
                chunks = chunks,
            )
    }

    private fun assemble(
        trackId: String,
        points: List<CanonicalRoutePoint>,
    ): RouteStructure {
        require(points.isNotEmpty()) { "轨迹点为空，无法构建几何索引" }
        val sorted = points.sortedWith(compareBy({ it.timeMillis }, { it.pointId }))
        val n = sorted.size
        val ids = Array(n) { sorted[it].pointId }
        val times = LongArray(n) { sorted[it].timeMillis }
        val lons = DoubleArray(n) { sorted[it].longitude }
        val lats = DoubleArray(n) { sorted[it].latitude }
        val dayIndices = IntArray(n)
        val mandatory = BooleanArray(n)
        val blockStep = RouteLodParams.MAX_BUILD_BLOCK_POINTS - 1  // 相邻块重叠一个点
        val chunkStep = RouteLodParams.ROUTE_CHUNK_POINTS - 1      // 相邻 chunk 重叠一个点

        val parts = mutableListOf<RoutePart>()
        val chunks = mutableListOf<RouteChunk>()
        var runStart = 0
        while (runStart < n) {
            val dayKey = sorted[runStart].dayKey
            var end = runStart
            while (end + 1 < n && sorted[end + 1].dayKey == dayKey) end++
            val dayIndex = parts.size
            for (i in runStart..end) dayIndices[i] = dayIndex
            parts += RoutePart(
                id = dayKey.toString(),
                dayIndex = dayIndex,
                startIndex = runStart,
                endIndexInclusive = end,
                bbox = bboxOf(lons, lats, runStart, end),
            )
            // 强制保留：每日首尾、MANUAL 打卡点、计算块边界（含全程首尾，方案 §2.4/§4.1）
            mandatory[runStart] = true
            mandatory[end] = true
            var boundary = runStart + blockStep
            while (boundary < end) {
                mandatory[boundary] = true
                boundary += blockStep
            }
            for (i in runStart..end) if (sorted[i].isCheckin) mandatory[i] = true
            // chunk：固定大小、连续、重叠一个边界点（方案 §3.3）
            var chunkStart = runStart
            while (true) {
                val chunkEnd = minOf(chunkStart + chunkStep, end)
                chunks += RouteChunk(
                    partId = dayKey.toString(),
                    startIndex = chunkStart,
                    endIndexInclusive = chunkEnd,
                    bbox = bboxOf(lons, lats, chunkStart, chunkEnd),
                )
                if (chunkEnd == end) break
                chunkStart = chunkEnd
            }
            runStart = end + 1
        }
        val trackBbox = bboxOf(lons, lats, 0, n - 1)
        return RouteStructure(
            trackId = trackId,
            sorted = sorted,
            ids = ids,
            times = times,
            lons = lons,
            lats = lats,
            dayIndices = dayIndices,
            mandatory = mandatory,
            parts = parts,
            chunks = chunks,
            blockStep = blockStep,
            referenceLatitude = (trackBbox.minLat + trackBbox.maxLat) / 2,
        )
    }

    private fun fingerprintOf(sorted: List<CanonicalRoutePoint>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val line = StringBuilder(64)
        for (point in sorted) {
            line.setLength(0)
            line.append(point.pointId).append('|')
                .append(point.timeMillis).append('|')
                .append(Math.round(point.longitude * 1_000_000.0)).append('|')
                .append(Math.round(point.latitude * 1_000_000.0)).append('|')
                .append(if (point.isCheckin) '1' else '0').append('\n')
            digest.update(line.toString().toByteArray(Charsets.UTF_8))
        }
        val hex = StringBuilder(digest.digestLength * 2)
        for (byte in digest.digest()) hex.append("%02x".format(byte))
        return hex.toString()
    }

    private fun bboxOf(lons: DoubleArray, lats: DoubleArray, start: Int, end: Int): GeoBbox {
        var minLng = lons[start]; var maxLng = lons[start]
        var minLat = lats[start]; var maxLat = lats[start]
        for (i in start + 1..end) {
            if (lons[i] < minLng) minLng = lons[i]
            if (lons[i] > maxLng) maxLng = lons[i]
            if (lats[i] < minLat) minLat = lats[i]
            if (lats[i] > maxLat) maxLat = lats[i]
        }
        return GeoBbox(minLng, minLat, maxLng, maxLat)
    }

    /**
     * 单个计算块的 importance：先做局部等距投影（方案 §4.2），再以强制保留点为固定
     * 切分点分段，每段用显式栈的层级 DP。父区间重要度向子区间做单调约束（§4.1）。
     */
    private fun computeBlockImportance(
        lons: DoubleArray,
        lats: DoubleArray,
        blockStart: Int,
        blockEnd: Int,
        mandatory: BooleanArray,
        importance: FloatArray,
        ensureActive: (() -> Unit)?,
    ) {
        ensureActive?.invoke()
        val size = blockEnd - blockStart + 1
        var minLat = lats[blockStart]; var maxLat = lats[blockStart]
        for (i in blockStart..blockEnd) {
            if (lats[i] < minLat) minLat = lats[i]
            if (lats[i] > maxLat) maxLat = lats[i]
        }
        val cosRef = cos(Math.toRadians((minLat + maxLat) / 2))
        val xs = DoubleArray(size)
        val ys = DoubleArray(size)
        for (i in blockStart..blockEnd) {
            xs[i - blockStart] = Math.toRadians(lons[i]) * cosRef * EARTH_RADIUS_METERS
            ys[i - blockStart] = Math.toRadians(lats[i]) * EARTH_RADIUS_METERS
        }
        // 强制保留点作为固定切分点（含块两端），相邻切分点之间跑层级 DP
        var intervalStart = 0
        for (i in 1 until size) {
            if (mandatory[blockStart + i] || i == size - 1) {
                if (i - 1 > intervalStart) {
                    dpInterval(xs, ys, intervalStart, i, importance, blockStart)
                }
                intervalStart = i
            }
        }
    }

    /** 显式栈层级 DP：栈元素为 (lo, hi, parentBound)。 */
    private fun dpInterval(
        xs: DoubleArray,
        ys: DoubleArray,
        from: Int,
        to: Int,
        importance: FloatArray,
        offset: Int,
    ) {
        var stackLo = IntArray(64)
        var stackHi = IntArray(64)
        var stackBound = DoubleArray(64)
        var sp = 0
        fun push(lo: Int, hi: Int, bound: Double) {
            if (sp == stackLo.size) {
                stackLo = stackLo.copyOf(sp * 2)
                stackHi = stackHi.copyOf(sp * 2)
                stackBound = stackBound.copyOf(sp * 2)
            }
            stackLo[sp] = lo; stackHi[sp] = hi; stackBound[sp] = bound
            sp++
        }
        push(from, to, Double.POSITIVE_INFINITY)
        while (sp > 0) {
            sp--
            val a = stackLo[sp]; val b = stackHi[sp]; val bound = stackBound[sp]
            if (b - a < 2) continue
            val ax = xs[a]; val ay = ys[a]
            val dx = xs[b] - ax
            val dy = ys[b] - ay
            val chordLength = sqrt(dx * dx + dy * dy)
            var maxDistance = -1.0
            var maxIndex = -1
            for (k in a + 1 until b) {
                val ex = xs[k] - ax
                val ey = ys[k] - ay
                val distance = if (chordLength == 0.0) {
                    sqrt(ex * ex + ey * ey)
                } else {
                    abs(dx * ey - dy * ex) / chordLength
                }
                if (distance > maxDistance) {  // 严格大于：并列时取首个，保证跨端确定
                    maxDistance = distance
                    maxIndex = k
                }
            }
            if (maxIndex < 0 || maxDistance <= 0.0) continue
            val value = minOf(maxDistance, bound).toFloat()  // 单调约束：不越过父区间
            importance[maxIndex + offset] = value
            push(a, maxIndex, value.toDouble())
            push(maxIndex, b, value.toDouble())
        }
    }
}
