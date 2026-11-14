package com.niutrip.app.data.local

class FakeRouteLodCacheDao : RouteLodCacheDao {
    val rows = mutableMapOf<String, RouteLodCacheEntity>()
    val touched = mutableListOf<String>()

    override suspend fun get(trackId: String): RouteLodCacheEntity? = rows[trackId]

    override suspend fun touch(trackId: String, accessAt: Long) {
        touched += trackId
        rows[trackId]?.let { rows[trackId] = it.copy(lastAccessAt = accessAt) }
    }

    override suspend fun upsert(cache: RouteLodCacheEntity) {
        rows[cache.trackId] = cache
    }

    override suspend fun delete(trackId: String) {
        rows.remove(trackId)
    }

    override suspend fun deleteOtherAccounts(accountKey: String) {
        rows.entries.removeAll { it.value.accountKey != accountKey }
    }

    override suspend fun allTrackIds(): List<String> = rows.keys.toList()

    override suspend fun payloadSizesLruAsc(): List<RouteLodCacheSizeRow> =
        rows.values.sortedBy(RouteLodCacheEntity::lastAccessAt)
            .map { RouteLodCacheSizeRow(it.trackId, it.importancePayload) }

    override suspend fun trimToBytes(limitBytes: Long) {
        var total = rows.values.sumOf { it.importancePayload.size.toLong() }
        for (row in payloadSizesLruAsc()) {
            if (total <= limitBytes) break
            total -= row.payload.size.toLong()
            delete(row.trackId)
        }
    }
}
