package com.niutrip.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface RouteLodCacheDao {
    @Query("SELECT * FROM route_lod_cache WHERE trackId = :trackId")
    suspend fun get(trackId: String): RouteLodCacheEntity?

    /** LRU 访问时间戳更新；命中缓存后由 Repository 调用。 */
    @Query("UPDATE route_lod_cache SET lastAccessAt = :accessAt WHERE trackId = :trackId")
    suspend fun touch(trackId: String, accessAt: Long)

    @Upsert
    suspend fun upsert(cache: RouteLodCacheEntity)

    @Query("DELETE FROM route_lod_cache WHERE trackId = :trackId")
    suspend fun delete(trackId: String)

    /** 换账号/退出登录时清理：只保留当前账号的派生缓存（方案 §5.2）。 */
    @Query("DELETE FROM route_lod_cache WHERE accountKey != :accountKey")
    suspend fun deleteOtherAccounts(accountKey: String)

    @Query("SELECT trackId FROM route_lod_cache")
    suspend fun allTrackIds(): List<String>

    /** 按 LRU 升序的 payload 大小（trim 用投影，避免整行加载）。 */
    @Query("SELECT trackId AS trackId, importancePayload AS payload FROM route_lod_cache ORDER BY lastAccessAt ASC")
    suspend fun payloadSizesLruAsc(): List<RouteLodCacheSizeRow>

    /**
     * LRU 空间回收（方案 §5.2）：按 payload 字节总量计，超限从最久未访问开始删。
     */
    @Transaction
    suspend fun trimToBytes(limitBytes: Long) {
        val rows = payloadSizesLruAsc()
        var total = rows.sumOf { it.payload.size.toLong() }
        for (row in rows) {
            if (total <= limitBytes) break
            total -= row.payload.size.toLong()
            delete(row.trackId)
        }
    }
}

/** trim 投影行。 */
data class RouteLodCacheSizeRow(val trackId: String, val payload: ByteArray) {
    override fun equals(other: Any?) =
        other is RouteLodCacheSizeRow && other.trackId == trackId && other.payload.contentEquals(payload)

    override fun hashCode() = 31 * trackId.hashCode() + payload.contentHashCode()
}
