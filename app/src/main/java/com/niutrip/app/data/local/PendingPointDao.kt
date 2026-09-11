package com.niutrip.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(entity: PendingPointEntity): Long
    @Query("SELECT * FROM pending_points ORDER BY time ASC LIMIT :count") suspend fun takeFirst(count: Int): List<PendingPointEntity>
    @Query("DELETE FROM pending_points WHERE id IN (:ids)") suspend fun deleteAll(ids: List<Long>)
    @Query("SELECT COUNT(*) FROM pending_points WHERE (:trackId IS NULL OR trackId = :trackId)") suspend fun count(trackId: String? = null): Int
    @Query("SELECT MAX(time) FROM pending_points WHERE trackId = :trackId") suspend fun lastTime(trackId: String): Long?
}
