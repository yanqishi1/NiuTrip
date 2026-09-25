package com.niutrip.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface CloudPointDao {
    @Query("SELECT * FROM cloud_points WHERE trackId = :trackId ORDER BY pointTime, pointId")
    suspend fun forTrack(trackId: String): List<CloudPointEntity>

    @Query("SELECT * FROM point_sync_state WHERE trackId = :trackId")
    suspend fun syncState(trackId: String): PointSyncStateEntity?

    @Upsert
    suspend fun upsertAll(points: List<CloudPointEntity>)

    @Query("DELETE FROM cloud_points WHERE pointId IN (:pointIds)")
    suspend fun deletePoints(pointIds: List<String>)

    @Upsert
    suspend fun saveSyncState(state: PointSyncStateEntity)

    @Query("DELETE FROM cloud_points WHERE trackId = :trackId")
    suspend fun deleteTrackPoints(trackId: String)

    @Query("DELETE FROM point_sync_state WHERE trackId = :trackId")
    suspend fun deleteSyncState(trackId: String)

    @Transaction
    suspend fun applyChanges(
        trackId: String,
        upserts: List<CloudPointEntity>,
        deletedPointIds: List<String>,
        cursor: String,
        hasMore: Boolean,
        accountKey: String,
    ) {
        if (deletedPointIds.isNotEmpty()) deletePoints(deletedPointIds)
        if (upserts.isNotEmpty()) upsertAll(upserts)
        val baselineComplete = syncState(trackId)?.baselineComplete == true || !hasMore
        saveSyncState(PointSyncStateEntity(trackId, cursor, baselineComplete, accountKey))
    }

    @Transaction
    suspend fun clearTrack(trackId: String) {
        deleteTrackPoints(trackId)
        deleteSyncState(trackId)
    }
}
