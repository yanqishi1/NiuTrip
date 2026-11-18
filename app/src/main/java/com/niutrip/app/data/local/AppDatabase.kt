package com.niutrip.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PendingPointEntity::class, TrackEntity::class, CloudPointEntity::class,
        PointSyncStateEntity::class, RouteLodCacheEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingPointDao(): PendingPointDao
    abstract fun trackDao(): TrackDao
    abstract fun cloudPointDao(): CloudPointDao
    abstract fun routeLodCacheDao(): RouteLodCacheDao
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cloud_points ADD COLUMN shareAnnotation INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE pending_points ADD COLUMN shareAnnotation INTEGER NOT NULL DEFAULT 0")
        // Old clients may have consumed deltas without storing this field; fetch a fresh baseline.
        db.execSQL("DELETE FROM point_sync_state")
    }
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

/** v3 → v4：新增本地 LOD 派生缓存表（方案 §6.3）；失败只影响派生缓存，不动事实数据。 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS route_lod_cache (
                trackId TEXT NOT NULL PRIMARY KEY,
                accountKey TEXT NOT NULL,
                sourceFingerprint TEXT NOT NULL,
                algorithmVersion INTEGER NOT NULL,
                pointCount INTEGER NOT NULL,
                importancePayload BLOB NOT NULL,
                createdAt INTEGER NOT NULL,
                lastAccessAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS index_route_lod_cache_lastAccessAt
            ON route_lod_cache(lastAccessAt)
        """.trimIndent())
    }
}
