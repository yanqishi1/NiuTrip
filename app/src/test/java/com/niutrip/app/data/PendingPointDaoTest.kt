package com.niutrip.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.niutrip.app.data.local.AppDatabase
import com.niutrip.app.data.local.PendingPointEntity
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
}
