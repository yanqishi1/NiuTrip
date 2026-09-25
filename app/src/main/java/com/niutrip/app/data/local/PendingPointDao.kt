package com.niutrip.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(entity: PendingPointEntity): Long
    @Query("SELECT * FROM pending_points ORDER BY time ASC LIMIT :count") suspend fun takeFirst(count: Int): List<PendingPointEntity>
    @Query("SELECT * FROM pending_points WHERE trackId = :trackId ORDER BY time ASC") suspend fun forTrack(trackId: String): List<PendingPointEntity>
    @Query("SELECT * FROM pending_points WHERE pointId = :pointId LIMIT 1") suspend fun get(pointId: String): PendingPointEntity?
    @Query("DELETE FROM pending_points WHERE id IN (:ids)") suspend fun deleteAll(ids: List<Long>)
    @Query("DELETE FROM pending_points WHERE pointId = :pointId") suspend fun delete(pointId: String): Int
    @Query("DELETE FROM pending_points WHERE pointId IN (:pointIds)") suspend fun deletePoints(pointIds: List<String>): Int
    @Query("SELECT COUNT(*) FROM pending_points WHERE (:trackId IS NULL OR trackId = :trackId)") suspend fun count(trackId: String? = null): Int
}
