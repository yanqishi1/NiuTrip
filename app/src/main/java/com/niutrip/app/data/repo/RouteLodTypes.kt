package com.niutrip.app.data.repo

import com.niutrip.app.data.remote.PointDto

/** 完整本地基线：数据库中的完整有序点 + 同步状态（方案 §6.4）。 */
data class RouteBaseline(
    val trackId: String,
    val points: List<PointDto>,
    val baselineComplete: Boolean,
)

/** 待保存的 LOD 派生缓存（不含账号/时间戳等仓储关注点）。 */
data class RouteLodCache(
    val trackId: String,
    val sourceFingerprint: String,
    val pointCount: Int,
    val importance: FloatArray,
    val mandatory: BooleanArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RouteLodCache
        return trackId == other.trackId && sourceFingerprint == other.sourceFingerprint &&
            pointCount == other.pointCount && importance.contentEquals(other.importance) &&
            mandatory.contentEquals(other.mandatory)
    }

    override fun hashCode(): Int {
        var result = trackId.hashCode()
        result = 31 * result + sourceFingerprint.hashCode()
        result = 31 * result + pointCount
        result = 31 * result + importance.contentHashCode()
        result = 31 * result + mandatory.contentHashCode()
        return result
    }
}

/** 命中的 LOD 缓存：与按 (time, pointId) 排序后的完整点数组一一对应。 */
class CachedRouteLod(
    val importance: FloatArray,
    val mandatory: BooleanArray,
)
