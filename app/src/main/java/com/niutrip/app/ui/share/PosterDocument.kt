package com.niutrip.app.ui.share

import com.niutrip.app.core.DayGroup
import com.niutrip.app.core.DayGrouper
import com.niutrip.app.core.PointLite
import com.niutrip.app.core.RouteGeometry
import com.niutrip.app.core.toLocalDateTimeOrNull
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.TrackDto
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale

/**
 * 轨迹结算海报的不可变数据快照与统计口径（纯逻辑，不依赖地图 SDK 与 Android UI）。
 *
 * 统计规则见 docs/track-share-poster-implementation-plan.md §4：
 * - 名称、起止时间来自轨迹元数据，缺失时回退首末有效点；
 * - 里程使用完整有效点的连线距离（RouteGeometry.totalDistanceMeters）；
 * - 记录天数为有效点对应的不同日期数；打卡次数以合并后 isCheckin 计数。
 */
data class PosterStats(
    val name: String,
    val startTime: LocalDateTime?,
    val endTime: LocalDateTime?,
    val distanceMeters: Double,
    val recordDays: Int,
    val checkinCount: Int,
) {
    /** 同日只显示一个日期，跨日显示区间；完全无时间时为 null。 */
    val dateText: String? get() = dateText(startTime, endTime)

    /** 短于 24 小时显示 HH:mm:ss，达到 24 小时显示“X天 HH:mm”；结束早于开始时为 null，不猜测数值。 */
    val durationText: String? get() = durationText(startTime, endTime)

    companion object {
        fun from(track: TrackDto, days: List<DayGroup>): PosterStats {
            val points = days.flatMap(DayGroup::points)
            val start = track.track_start_time?.toLocalDateTimeOrNull()
                ?: points.minByOrNull(PointLite::time)?.time
            val end = track.track_end_time?.toLocalDateTimeOrNull()
                ?: points.maxByOrNull(PointLite::time)?.time
            return PosterStats(
                name = track.track_name,
                startTime = start,
                endTime = end,
                distanceMeters = RouteGeometry.totalDistanceMeters(points),
                recordDays = days.size,
                checkinCount = points.count { it.isCheckin },
            )
        }

        internal fun dateText(start: LocalDateTime?, end: LocalDateTime?): String? = when {
            start == null || end == null -> null
            start.toLocalDate() == end.toLocalDate() -> start.toLocalDate().toString()
            else -> "${start.toLocalDate()} ~ ${end.toLocalDate()}"
        }

        internal fun durationText(start: LocalDateTime?, end: LocalDateTime?): String? {
            if (start == null || end == null) return null
            val seconds = Duration.between(start, end).seconds
            if (seconds < 0) return null
            val days = seconds / 86_400
            val remainder = seconds % 86_400
            val hours = remainder / 3_600
            val minutes = remainder % 3_600 / 60
            val secs = remainder % 60
            return if (days == 0L) String.format(Locale.CHINA, "%02d:%02d:%02d", hours, minutes, secs)
            else String.format(Locale.CHINA, "%d天 %02d:%02d", days, hours, minutes)
        }
    }
}

/** 进入编辑器时构建一次的完整轨迹快照；拖动、换色、裁剪不再重新拉取。 */
data class PosterTrackSnapshot(
    val trackId: String,
    val trackStatus: String,
    val dataRevision: Int,
    val days: List<DayGroup>,
    val points: List<PointLite>,
    val stats: PosterStats,
)

/** 海报编辑文档（plan §5.2）：模式切换保存各自的信息布局与比例，不互相覆盖。 */
enum class PosterBackgroundMode { MAP, PHOTO }
enum class PosterRouteTheme { SINGLE, BY_DAY }
enum class PosterInfoTextTheme { LIGHT, DARK }
enum class PosterInfoLayoutKind { COMPACT, HORIZONTAL }

/** 照片资源与会话内裁剪参数；尺寸为方向归一化后的值。 */
data class PosterPhotoState(
    val resourceId: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val crop: PhotoCrop = PhotoCrop(),
)

/** 地图相机：isAutoFit 为真时导出前按画布比例全程 fit；用户构图后保存中心与缩放。 */
data class PosterMapState(
    val isAutoFit: Boolean = true,
    val centerLat: Double? = null,
    val centerLon: Double? = null,
    val zoom: Double? = null,
    val outlineZoom: Float = 1f,
    val outlinePanX: Float = 0f,
    val outlinePanY: Float = 0f,
)

/** 照片模式透明路线模块；地图模式路线与地图地理绑定，不存在该模块。 */
data class PosterRouteModuleState(
    val placement: ModulePlacement,
    val theme: PosterRouteTheme = PosterRouteTheme.SINGLE,
    val showCheckinMarkers: Boolean = true,
    val showEndpointNames: Boolean = true,
    val showMapOutline: Boolean = true,
    val preferredCheckins: Map<String, String> = emptyMap(),
    /** Empty = endpoints only; null is supported by legacy rendering callers as all checkins. */
    val selectedCheckinIds: Set<String>? = emptySet(),
)

enum class PosterTextGroup { HEADER, STATS }

enum class PosterTextColor(val argb: Int, val label: String) {
    WHITE(0xFFFFFFFF.toInt(), "白色"), BLACK(0xFF202224.toInt(), "黑色"),
    RED(0xFFE5484D.toInt(), "红色"), GRAY(0xFF85898E.toInt(), "灰色"),
    YELLOW(0xFFFFD452.toInt(), "黄色"), GREEN(0xFF27AE80.toInt(), "绿色"),
    BLUE(0xFF4285E8.toInt(), "蓝色"), ORANGE(0xFFFF9545.toInt(), "橙色"),
    PINK(0xFFF47AA8.toInt(), "粉色"), CYAN(0xFF46CED1.toInt(), "青色"),
}

data class PosterInfoModuleState(
    val placement: ModulePlacement,
    val textTheme: PosterInfoTextTheme,
    val layoutKind: PosterInfoLayoutKind = PosterInfoLayoutKind.COMPACT,
    /** 显示开关（plan §9.1）：主里程保留，名称/日期/辅助指标可隐藏。 */
    val showName: Boolean = true,
    val showDate: Boolean = true,
    val showAux: Boolean = true,
    val fontScale: Float = 1f,
    val titleFontScale: Float = fontScale,
    val titleColor: PosterTextColor? = null,
    val statsColor: PosterTextColor? = null,
)

/** 地图模式路线样式（plan §5.2）：颜色主题与标记开关。 */
data class PosterRouteStyleState(
    val theme: PosterRouteTheme = PosterRouteTheme.SINGLE,
    val showCheckinMarkers: Boolean = true,
    val showEndpointNames: Boolean = true,
    val preferredCheckins: Map<String, String> = emptyMap(),
    val selectedCheckinIds: Set<String>? = emptySet(),
)

data class PosterDocument(
    val layoutVersion: Int = 1,
    val backgroundMode: PosterBackgroundMode = PosterBackgroundMode.MAP,
    /** 地图与照片各自记住上次使用的比例；原图比例只允许出现在照片侧。 */
    val mapRatioKind: PosterRatioKind = PosterRatioKind.R3_4,
    val photoRatioKind: PosterRatioKind = PosterRatioKind.R3_4,
    val photoState: PosterPhotoState? = null,
    val mapState: PosterMapState = PosterMapState(),
    val photoRouteState: PosterRouteModuleState? = null,
    val photoInfoState: PosterInfoModuleState? = null,
    val mapInfoState: PosterInfoModuleState? = null,
    val mapRouteStyle: PosterRouteStyleState = PosterRouteStyleState(),
    /** 每次已提交编辑递增；导出与缓存文件以它判断失效。 */
    val editRevision: Int = 0,
) {
    val ratioKind: PosterRatioKind
        get() = if (backgroundMode == PosterBackgroundMode.MAP) mapRatioKind else photoRatioKind

    /** 当前背景与照片尺寸下的输出规格。 */
    fun canvasSpec(): PosterCanvasSpec = when (ratioKind) {
        PosterRatioKind.ORIGINAL -> {
            val photo = requireNotNull(photoState) { "原图比例依赖照片" }
            PosterCanvasSpec.create(ratioKind, photo.imageWidth, photo.imageHeight)
        }
        else -> PosterCanvasSpec.create(ratioKind).let {
            if (backgroundMode == PosterBackgroundMode.MAP) it.copy(widthPx = it.widthPx * 2, heightPx = it.heightPx * 2) else it
        }
    }
}

/**
 * 由云端有效点与仓库清理后的 pending 点构建快照（plan §4.1）：
 * - 本地点以点 ID 去重，云端同 ID 数据优先；
 * - 只使用同步清理后的本地点集合，不把已随同步删除的旧 pending 快照加回；
 * - 过滤非法时间、非有限经纬度和越界坐标，按 (time, id) 稳定排序；
 * - 无有效点时返回 null，由调用方提示错误。
 */
object PosterSnapshotBuilder {
    fun build(
        track: TrackDto,
        cloud: List<PointDto>,
        local: List<PointDto>,
        dataRevision: Int,
    ): PosterTrackSnapshot? {
        val merged = (local.filterNot(PointDto::is_deleted) + cloud.filterNot(PointDto::is_deleted))
            .associateBy(PointDto::point_id)
            .values
        val points = merged.mapNotNull { it.toLite() }
            .filter { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
            .sortedWith(compareBy({ it.time }, { it.id }))
        if (points.isEmpty()) return null
        val days = DayGrouper.group(points)
        return PosterTrackSnapshot(
            trackId = track.track_id,
            trackStatus = track.track_status,
            dataRevision = dataRevision,
            days = days,
            points = points,
            stats = PosterStats.from(track, days),
        )
    }
}
