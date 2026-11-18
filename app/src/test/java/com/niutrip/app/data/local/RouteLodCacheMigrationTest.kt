package com.niutrip.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.niutrip.app.core.route.RouteLodParams
import com.niutrip.app.core.route.RouteLodPayload
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouteLodCacheMigrationTest {
    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun cloudPoint(id: String) = CloudPointEntity(
        pointId = id, trackId = "t1", longitude = 116.0, latitude = 39.0,
        name = null, description = null, imagesJson = "[]",
        pointTime = "2026-01-01T08:00:00", source = "AUTO", reportTime = null)

    private fun cache(trackId: String, payloadBytes: Int, lastAccessAt: Long, accountKey: String = "acc") =
        RouteLodCacheEntity(
            trackId = trackId, accountKey = accountKey, sourceFingerprint = "fp-$trackId",
            algorithmVersion = RouteLodParams.ALGORITHM_VERSION, pointCount = 1,
            importancePayload = ByteArray(payloadBytes), createdAt = lastAccessAt, lastAccessAt = lastAccessAt)

    @Test
    fun `v3 and v4 databases migrate to v5 retaining points and refreshing sync metadata`() = runBlocking {
        for (oldVersion in listOf(3, 4)) {
        val context = context()
        val dbName = "route-lod-migration-$oldVersion-test.db"
        context.deleteDatabase(dbName)
        // Build the actual old schema; current entities already contain the v5 columns.
        val old = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(oldVersion) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE tracks (trackId TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL, imageUrl TEXT NOT NULL, startTime TEXT, endTime TEXT,
                            recordMode TEXT NOT NULL, status TEXT NOT NULL, shareMode TEXT NOT NULL,
                            pointCount INTEGER NOT NULL, checkinCount INTEGER NOT NULL,
                            viewCount INTEGER NOT NULL, viewerCount INTEGER NOT NULL, sharerUsername TEXT)""")
                        db.execSQL("""CREATE TABLE pending_points (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            trackId TEXT NOT NULL, pointId TEXT NOT NULL, lon REAL NOT NULL, lat REAL NOT NULL,
                            name TEXT, `desc` TEXT, imgs TEXT NOT NULL, time INTEGER NOT NULL,
                            source TEXT NOT NULL, createdAt INTEGER NOT NULL)""")
                        db.execSQL("CREATE UNIQUE INDEX index_pending_points_pointId ON pending_points(pointId)")
                        MIGRATION_2_3.migrate(db)
                        if (oldVersion == 4) MIGRATION_3_4.migrate(db)
                        db.execSQL("INSERT INTO tracks VALUES ('t1', '轨迹', '', NULL, NULL, 'AUTO', 'FINISHED', 'PRIVATE', 3, 1, 2, 1, NULL)")
                        for (id in listOf("p1", "p2")) db.execSQL(
                            "INSERT INTO cloud_points VALUES (?, 't1', 116, 39, NULL, NULL, '[]', '2026-01-01T08:00:00', 'AUTO', NULL)", arrayOf(id))
                        db.execSQL("INSERT INTO point_sync_state VALUES ('t1', 'c9', 1, 'acc')")
                        db.execSQL("INSERT INTO pending_points VALUES (1, 't1', 'local', 116, 39, NULL, NULL, '[]', 1, 'MANUAL', 1)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        old.writableDatabase
        old.close()

        // 2. 以 v4 打开：执行 MIGRATION_3_4 并校验 schema
        val v4 = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

        // 3. 四张旧表数据完全保留（方案 §6.3）
        assertEquals("轨迹", v4.trackDao().get("t1")?.name)
        assertEquals(listOf("p1", "p2"), v4.cloudPointDao().forTrack("t1").map { it.pointId })
        assertNull(v4.cloudPointDao().syncState("t1"))
        assertTrue(v4.cloudPointDao().forTrack("t1").none { it.shareAnnotation })
        assertEquals(1, v4.pendingPointDao().count())
        val pending = v4.pendingPointDao().forTrack("t1").single()
        assertEquals(false, pending.shareAnnotation)
        v4.pendingPointDao().insert(pending.copy(shareAnnotation = true))
        assertTrue(v4.pendingPointDao().forTrack("t1").single().shareAnnotation)
        v4.cloudPointDao().upsertAll(listOf(cloudPoint("p1").copy(shareAnnotation = true)))
        assertTrue(v4.cloudPointDao().forTrack("t1").first().toDto().toLite()!!.shareAnnotation)

        // 4. 新表可读写
        val payload = RouteLodPayload.encode(floatArrayOf(1f, 2f), booleanArrayOf(true, false))
        v4.routeLodCacheDao().upsert(
            cache("t1", payloadBytes = 0, lastAccessAt = 1).copy(importancePayload = payload, pointCount = 2))
        val stored = v4.routeLodCacheDao().get("t1")
        assertNotNull(stored)
        assertEquals("fp-t1", stored!!.sourceFingerprint)
        assertTrue(payload.contentEquals(stored.importancePayload))
        v4.close()
        }
    }

    @Test
    fun `trim drops least recently used rows first by payload bytes`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val dao = db.routeLodCacheDao()
        try {
            dao.upsert(cache("a", 100, lastAccessAt = 10))
            dao.upsert(cache("b", 300, lastAccessAt = 20))
            dao.upsert(cache("c", 200, lastAccessAt = 30))

            dao.trimToBytes(500)   // 600 → 淘汰最旧的 a → 500
            assertEquals(setOf("b", "c"), dao.allTrackIds().toSet())
            dao.trimToBytes(200)   // 500 → 淘汰 b → 200
            assertEquals(setOf("c"), dao.allTrackIds().toSet())
            dao.trimToBytes(0)     // 全部清空也不抛错
            assertTrue(dao.allTrackIds().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `deleteOtherAccounts keeps only current account rows`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val dao = db.routeLodCacheDao()
        try {
            dao.upsert(cache("mine", 10, 1, accountKey = "me"))
            dao.upsert(cache("theirs", 10, 2, accountKey = "other"))
            dao.deleteOtherAccounts("me")
            assertEquals(listOf("mine"), dao.allTrackIds())
        } finally {
            db.close()
        }
    }

    @Test
    fun `touch updates last access so lru keeps hot rows`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val dao = db.routeLodCacheDao()
        try {
            dao.upsert(cache("old-cold", 100, lastAccessAt = 10))
            dao.upsert(cache("hot", 400, lastAccessAt = 20))
            dao.touch("old-cold", 40)   // 旧行被访问 → 变热
            dao.trimToBytes(400)        // 总 500 → 淘汰 now-coldest "hot"
            assertEquals(listOf("old-cold"), dao.allTrackIds())
        } finally {
            db.close()
        }
    }

    @Test
    fun `delete removes single track cache`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val dao = db.routeLodCacheDao()
        try {
            dao.upsert(cache("a", 10, 1))
            dao.upsert(cache("b", 10, 2))
            dao.delete("a")
            assertNull(dao.get("a"))
            assertEquals(listOf("b"), dao.allTrackIds())
        } finally {
            db.close()
        }
    }
}
