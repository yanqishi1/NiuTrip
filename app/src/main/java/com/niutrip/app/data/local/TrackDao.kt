package com.niutrip.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Upsert suspend fun upsert(track: TrackEntity)
    @Upsert suspend fun upsertAll(tracks: List<TrackEntity>)
    @Query("SELECT * FROM tracks ORDER BY CASE WHEN status = 'RECORDING' THEN 0 ELSE 1 END, startTime DESC") fun observeAll(): Flow<List<TrackEntity>>
    @Query("SELECT * FROM tracks WHERE trackId = :id") suspend fun get(id: String): TrackEntity?
    @Query("SELECT * FROM tracks WHERE status = 'RECORDING' LIMIT 1") suspend fun recording(): TrackEntity?
    @Query("DELETE FROM tracks") suspend fun clear()
}
