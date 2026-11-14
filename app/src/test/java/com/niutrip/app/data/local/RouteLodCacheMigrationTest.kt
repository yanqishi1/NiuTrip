package com.niutrip.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    fun `v3 database migrates to v4 keeping all existing rows`() = runBlocking {
        val context = context()
        val dbName = "route-lod-migration-test.db"
        context.deleteDatabase(dbName)
        // 1. 用相同实体建 v3 库（Room 生成的 schema 与线上 v3 一致）并写入数据
        val v3 = Room.databaseBuilder(context, V3Database::class.java, dbName)
            .allowMainThreadQueries().build()
        v3.trackDao().upsert(TrackEntity("t1", "轨迹", "", null, null, "AUTO", "FINISHED", "PRIVATE", 3, 1, 2, 1))
        v3.cloudPointDao().upsertAll(listOf(cloudPoint("p1"), cloudPoint("p2")))
        v3.cloudPointDao().applyChanges("t1", emptyList(), emptyList(), "c9", hasMore = false, accountKey = "acc")
        v3.pendingPointDao().insert(PendingPointEntity(trackId = "t1", pointId = "local", lon = 116.0,
            lat = 39.0, time = 1L, source = "AUTO", createdAt = 1L))
        // 移除 room_master_table：让 v4 打开时走 schema 校验 + 真实迁移路径
        v3.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS room_master_table")
        v3.close()

        // 2. 以 v4 打开：执行 MIGRATION_3_4 并校验 schema
        val v4 = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        // 3. 四张旧表数据完全保留（方案 §6.3）
        assertEquals("轨迹", v4.trackDao().get("t1")?.name)
        assertEquals(listOf("p1", "p2"), v4.cloudPointDao().forTrack("t1").map { it.pointId })
        assertEquals("c9", v4.cloudPointDao().syncState("t1")?.cursor)
        assertTrue(v4.cloudPointDao().syncState("t1")?.baselineComplete == true)
        assertEquals(1, v4.pendingPointDao().count())

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

/** 与线上 v3 完全同构的测试库：只用于生成真实 v3 schema。 */
@Database(
    entities = [PendingPointEntity::class, TrackEntity::class, CloudPointEntity::class,
        PointSyncStateEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class V3Database : RoomDatabase() {
    abstract fun pendingPointDao(): PendingPointDao
    abstract fun trackDao(): TrackDao
    abstract fun cloudPointDao(): CloudPointDao
}
