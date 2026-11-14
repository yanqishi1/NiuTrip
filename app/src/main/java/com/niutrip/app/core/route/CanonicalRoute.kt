package com.niutrip.app.core.route

import com.niutrip.app.core.GeoBbox
import java.time.LocalDate

/**
 * 轨迹几何的规范化输入点（方案 §3.1）：只保留绘线必需字段。
 * 排序规则统一为 (time ASC, pointId ASC)，由 [RouteLodBuilder.build] 强制执行。
 */
data class CanonicalRoutePoint(
    val pointId: String,
    val timeMillis: Long,
    val longitude: Double,
    val latitude: Double,
    val isCheckin: Boolean,
    val dayKey: LocalDate,
)

/** 按北京时间日期分色的轨迹分段（方案 §3.2）；点序为 [RouteGeometryIndex] 中的连续下标区间。 */
data class RoutePart(
    val id: String,
    val dayIndex: Int,
    val startIndex: Int,
    val endIndexInclusive: Int,
    val bbox: GeoBbox,
)

/** 视口查询用固定大小连续 chunk（方案 §3.3）；相邻 chunk 重叠一个边界点。 */
data class RouteChunk(
    val partId: String,
    val startIndex: Int,
    val endIndexInclusive: Int,
    val bbox: GeoBbox,
)

/**
 * 不可变轨迹几何索引（方案 §3.4）：所有 LOD 层级的唯一真源。
 *
 * 内部使用平行原始数组避免大轨迹下的对象开销；数组以 internal 暴露给同包的
 * 查询/渲染策略做零拷贝读取，对外只提供只读访问器（方案 §6.6：内部数组不暴露可变引用）。
 */
class RouteGeometryIndex(
    val trackId: String,
    val sourceFingerprint: String,
    /** 全程 bbox 中心纬度：zoom→容差换算的参考纬度（确定性，与视口无关）。 */
    val referenceLatitude: Double,
    val pointCount: Int,
    internal val ids: Array<String>,
    internal val times: LongArray,
    internal val longitudes: DoubleArray,
    internal val latitudes: DoubleArray,
    internal val dayIndices: IntArray,
    /** 强制保留点标记：任意 LOD 层级都必须保留（首尾/日边界/打卡/计算块边界）。 */
    internal val mandatory: BooleanArray,
    /** 点相对原始几何的重要度（米）；越大越应在低缩放级别保留。 */
    internal val importance: FloatArray,
    val routeParts: List<RoutePart>,
    val chunks: List<RouteChunk>,
) {
    fun idAt(index: Int): String = ids[index]
    fun timeAt(index: Int): Long = times[index]
    fun lonAt(index: Int): Double = longitudes[index]
    fun latAt(index: Int): Double = latitudes[index]
    fun dayIndexAt(index: Int): Int = dayIndices[index]
    fun isMandatoryAt(index: Int): Boolean = mandatory[index]
    fun importanceAt(index: Int): Float = importance[index]
    fun idsAt(indices: IntArray): List<String> = indices.map(::idAt)
    fun idsAt(indices: List<Int>): List<String> = indices.map(::idAt)
}
