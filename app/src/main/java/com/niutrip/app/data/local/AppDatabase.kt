package com.niutrip.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PendingPointEntity::class, TrackEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingPointDao(): PendingPointDao
    abstract fun trackDao(): TrackDao
}
