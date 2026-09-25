package com.niutrip.app.data.repo

import com.niutrip.app.data.local.FakeCloudPointDao
import com.niutrip.app.data.local.FakePendingPointDao
import com.niutrip.app.data.local.PendingPointEntity
import com.niutrip.app.data.local.PointSyncStateEntity
import com.niutrip.app.data.local.TrackDao
import com.niutrip.app.data.local.TrackEntity
import com.niutrip.app.data.remote.PointChangesDto
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.StubApi
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPointSyncTest {
    private fun point(id: String, lon: Double = 100.0, deleted: Boolean = false) = PointDto(
        point_id = id,
        longitude = lon,
        latitude = 30.0,
        point_time = "2026-09-24T10:00:00",
        point_source = "AUTO",
        is_deleted = deleted,
        updated_at = "2026-09-24T10:01:00",
    )

    @Test fun `first load builds baseline and later loads request only deltas`() = runTest {
        val cursors = mutableListOf<String?>()
        var call = 0
        val api = object : StubApi() {
            override suspend fun pointChanges(id: String, cursor: String?): PointChangesDto {
                cursors += cursor
                return if (call++ == 0) PointChangesDto("c1", false, listOf(point("p1")))
                else PointChangesDto("c2", false, listOf(point("p2")))
            }
        }
        val cache = FakeCloudPointDao()
        val repo = TrackRepository(api, FakeTrackDao, cache, FakePendingPointDao())

        assertEquals(listOf("p1"), repo.points("t1").map(PointDto::point_id))
        assertEquals(listOf("p1", "p2"), repo.points("t1").map(PointDto::point_id))
        assertEquals(listOf(null, "c1"), cursors)
        assertEquals("c2", cache.syncState("t1")?.cursor)
    }

    @Test fun `cloud update and tombstone discard conflicting pending rows`() = runTest {
        val pending = FakePendingPointDao(listOf(
            pending("p1", 1),
            pending("deleted", 2),
        ))
        val api = object : StubApi() {
            override suspend fun pointChanges(id: String, cursor: String?) = PointChangesDto(
                "c2", false, listOf(point("p1", lon = 116.397), point("deleted", deleted = true)))
        }
        val repo = TrackRepository(api, FakeTrackDao, FakeCloudPointDao(), pending)

        val result = repo.points("t1")

        assertEquals(listOf("p1"), result.map(PointDto::point_id))
        assertEquals(116.397, result.single().longitude ?: 0.0, 0.0)
        assertTrue(pending.rows.isEmpty())
    }

    @Test fun `existing baseline is available when delta request is offline`() = runTest {
        val cache = FakeCloudPointDao(
            initialPoints = listOf(com.niutrip.app.data.local.CloudPointEntity.from("t1", point("cached"))),
            initialStates = listOf(PointSyncStateEntity("t1", "c1", true, "test-account")),
        )
        val api = object : StubApi() {
            override suspend fun pointChanges(id: String, cursor: String?): PointChangesDto =
                throw IOException("offline")
        }

        val result = TrackRepository(api, FakeTrackDao, cache).points("t1")

        assertEquals(listOf("cached"), result.map(PointDto::point_id))
    }

    @Test fun `initial sync failure does not expose a partial baseline`() = runTest {
        val api = object : StubApi() {
            override suspend fun pointChanges(id: String, cursor: String?): PointChangesDto =
                throw IOException("offline")
        }

        val failure = runCatching {
            TrackRepository(api, FakeTrackDao, FakeCloudPointDao()).points("t1")
        }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    @Test fun `interrupted first pagination stays incomplete until the last page`() = runTest {
        val cursors = mutableListOf<String?>()
        val api = object : StubApi() {
            override suspend fun pointChanges(id: String, cursor: String?): PointChangesDto {
                cursors += cursor
                if (cursor == null) return PointChangesDto("c1", true, listOf(point("p1")))
                throw IOException("page failed")
            }
        }
        val cache = FakeCloudPointDao()
        val repo = TrackRepository(api, FakeTrackDao, cache)

        assertTrue(runCatching { repo.points("t1") }.exceptionOrNull() is IOException)
        assertEquals(false, cache.syncState("t1")?.baselineComplete)
        assertTrue(runCatching { repo.points("t1") }.exceptionOrNull() is IOException)
        assertEquals(listOf(null, "c1", "c1"), cursors)
    }

    @Test fun `cache from another account is cleared before synchronization`() = runTest {
        val oldPoint = com.niutrip.app.data.local.CloudPointEntity.from("t1", point("old"))
        val cache = FakeCloudPointDao(
            initialPoints = listOf(oldPoint),
            initialStates = listOf(PointSyncStateEntity("t1", "old-cursor", true, "old-account")),
        )
        var requestedCursor: String? = "not-called"
        val api = object : StubApi() {
            override suspend fun pointChanges(id: String, cursor: String?): PointChangesDto {
                requestedCursor = cursor
                return PointChangesDto("new-cursor", false, listOf(point("new")))
            }
        }

        val result = TrackRepository(
            api, FakeTrackDao, cache, accountKey = { "new-account" }).points("t1")

        assertEquals(null, requestedCursor)
        assertEquals(listOf("new"), result.map(PointDto::point_id))
        assertEquals("new-account", cache.syncState("t1")?.accountKey)
    }

    private fun pending(pointId: String, id: Long) = PendingPointEntity(
        id = id,
        trackId = "t1",
        pointId = pointId,
        lon = 99.0,
        lat = 29.0,
        time = 1_700_000_000_000 + id,
        source = "AUTO",
        createdAt = 0,
    )

    private object FakeTrackDao : TrackDao {
        override suspend fun upsert(track: TrackEntity) = Unit
        override suspend fun upsertAll(tracks: List<TrackEntity>) = Unit
        override fun observeAll() = flowOf(emptyList<TrackEntity>())
        override suspend fun get(id: String): TrackEntity? = null
        override suspend fun recording(): TrackEntity? = null
        override suspend fun clear() = Unit
    }
}
