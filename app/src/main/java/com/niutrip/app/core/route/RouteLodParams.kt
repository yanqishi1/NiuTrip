package com.niutrip.app.core.route

/**
 * 轨迹本地嵌套 LOD 的共享算法参数（方案 §8.1）。
 *
 * Android 与 iOS 必须逐项一致；禁止在 UI 或渲染层散落魔法数字。
 * 修改任何值都必须同步 bump [ALGORITHM_VERSION]，使两端 LOD 缓存整体失效。
 */
object RouteLodParams {
    /** 算法版本：影响 importance 结果的任何改动都 +1，用于缓存失效。 */
    const val ALGORITHM_VERSION = 1

    /** LOD zoom 档位（方案 §4.3）；实际相机 zoom 取不高于它的最近档位。 */
    val LOD_ZOOM_BANDS = intArrayOf(8, 10, 12, 14, 16, 18, 20)

    /** 容差像素（方案 §4.3）：以地图逻辑屏幕点计，不含设备像素倍率。 */
    const val TOLERANCE_PIXELS = 0.75

    /** importance 计算块上限（方案 §4.1）；相邻块重叠一个点，块边界强制保留。 */
    const val MAX_BUILD_BLOCK_POINTS = 4096

    /** 视口查询 chunk 大小（方案 §3.3）；相邻 chunk 重叠一个边界点。 */
    const val ROUTE_CHUNK_POINTS = 512

    /** 视口查询每边外扩倍率（方案 §4.5，0.5 = 半屏）。 */
    const val VIEWPORT_PADDING_RATIO = 0.5

    /** 单条 Polyline 顶点上限（方案 §6.7，512~1000 取下限与 chunk 对齐）。 */
    const val MAX_POLYLINE_POINTS = 512

    /** 录制中轨迹尾部始终全精度绘制的点数（方案 §5.3）。 */
    const val RECORDING_TAIL_FULL_RESOLUTION_POINTS = 256

    /** 录制中每新增 N 点触发一次后台尾部重建（方案 §5.3）。 */
    const val RECORDING_REBUILD_POINT_INTERVAL = 32

    /** 录制中后台尾部重建的最小时间间隔（方案 §5.3）。 */
    const val RECORDING_REBUILD_INTERVAL_MS = 30_000L

    /** Web 墨卡托 zoom 0 赤道分辨率（米/像素），方案 §4.3 公式常量。 */
    const val EQUATOR_METERS_PER_PIXEL_AT_ZOOM_0 = 156_543.03392
}
