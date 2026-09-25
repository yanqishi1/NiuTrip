package com.niutrip.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.niutrip.app.data.local.AppDatabase
import com.niutrip.app.data.local.CloudPointEntity
import com.niutrip.app.data.local.PendingPointEntity
import com.niutrip.app.data.remote.PointDto
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingPointDaoTest {
    private lateinit var db: AppDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()
    private fun row(time: Long, id: String) = PendingPointEntity(trackId = "TK1", pointId = id, lon = 100.0, lat = 30.0, time = time, source = "AUTO", createdAt = time)
    @Test fun `takes oldest rows and deletes uploaded`() = runBlocking {
        db.pendingPointDao().insert(row(300, "c")); db.pendingPointDao().insert(row(100, "a")); db.pendingPointDao().insert(row(200, "b"))
        val first = db.pendingPointDao().takeFirst(2)
        assertEquals(listOf("a", "b"), first.map { it.pointId })
        db.pendingPointDao().deleteAll(first.map { it.id })
        assertEquals(1, db.pendingPointDao().count())
    }

    @Test fun `reads only local points for the requested track`() = runBlocking {
        db.pendingPointDao().insert(row(200, "b"))
        db.pendingPointDao().insert(row(100, "a"))
        db.pendingPointDao().insert(row(50, "other").copy(trackId = "TK2"))

        assertEquals(listOf("a", "b"), db.pendingPointDao().forTrack("TK1").map { it.pointId })
    }

    @Test fun `finds and deletes one pending point by business id`() = runBlocking {
        db.pendingPointDao().insert(row(100, "keep"))
        db.pendingPointDao().insert(row(200, "remove"))

        assertEquals("remove", db.pendingPointDao().get("remove")?.pointId)
        assertEquals(1, db.pendingPointDao().delete("remove"))
        assertEquals(listOf("keep"), db.pendingPointDao().forTrack("TK1").map { it.pointId })
    }

    @Test fun `cloud changes and cursor are applied together`() = runBlocking {
        val first = CloudPointEntity.from("TK1", PointDto(
            point_id = "cloud-1", longitude = 100.0, latitude = 30.0,
            point_time = "2026-09-24T10:00:00", point_source = "AUTO"))
        db.cloudPointDao().applyChanges(
            "TK1", listOf(first), emptyList(), "cursor-1", false, "account-1")

        assertEquals(listOf("cloud-1"), db.cloudPointDao().forTrack("TK1").map { it.pointId })
        assertEquals("cursor-1", db.cloudPointDao().syncState("TK1")?.cursor)
        assertEquals(true, db.cloudPointDao().syncState("TK1")?.baselineComplete)

        db.cloudPointDao().applyChanges(
            "TK1", emptyList(), listOf("cloud-1"), "cursor-2", false, "account-1")
        assertEquals(emptyList<CloudPointEntity>(), db.cloudPointDao().forTrack("TK1"))
        assertEquals("cursor-2", db.cloudPointDao().syncState("TK1")?.cursor)
    }
}
