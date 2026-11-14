package com.niutrip.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地 LOD 派生缓存（方案 §5.1）：只存派生数据，完整坐标仍在 cloud_points。
 * payload 编码见 [com.niutrip.app.core.route.RouteLodPayload]。
 */
@Entity(tableName = "route_lod_cache", indices = [Index("lastAccessAt")])
data class RouteLodCacheEntity(
    @PrimaryKey val trackId: String,
    val accountKey: String,
    val sourceFingerprint: String,
    val algorithmVersion: Int,
    val pointCount: Int,
    val importancePayload: ByteArray,
    val createdAt: Long,
    val lastAccessAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RouteLodCacheEntity
        return trackId == other.trackId && accountKey == other.accountKey &&
            sourceFingerprint == other.sourceFingerprint &&
            algorithmVersion == other.algorithmVersion && pointCount == other.pointCount &&
            importancePayload.contentEquals(other.importancePayload) &&
            createdAt == other.createdAt && lastAccessAt == other.lastAccessAt
    }

    override fun hashCode(): Int {
        var result = trackId.hashCode()
        result = 31 * result + accountKey.hashCode()
        result = 31 * result + sourceFingerprint.hashCode()
        result = 31 * result + algorithmVersion
        result = 31 * result + pointCount
        result = 31 * result + importancePayload.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + lastAccessAt.hashCode()
        return result
    }
}
