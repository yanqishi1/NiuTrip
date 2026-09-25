package com.niutrip.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PendingPointEntity::class, TrackEntity::class, CloudPointEntity::class,
        PointSyncStateEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingPointDao(): PendingPointDao
    abstract fun trackDao(): TrackDao
    abstract fun cloudPointDao(): CloudPointDao
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cloud_points (
                pointId TEXT NOT NULL PRIMARY KEY,
                trackId TEXT NOT NULL,
                longitude REAL NOT NULL,
                latitude REAL NOT NULL,
                name TEXT,
                description TEXT,
                imagesJson TEXT NOT NULL,
                pointTime TEXT NOT NULL,
                source TEXT NOT NULL,
                reportTime TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS index_cloud_points_trackId_pointTime
            ON cloud_points(trackId, pointTime)
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS point_sync_state (
                trackId TEXT NOT NULL PRIMARY KEY,
                cursor TEXT NOT NULL,
                baselineComplete INTEGER NOT NULL,
                accountKey TEXT NOT NULL
            )
        """.trimIndent())
    }
}
