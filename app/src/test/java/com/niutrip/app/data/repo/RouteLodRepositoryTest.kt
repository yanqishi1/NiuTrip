package com.niutrip.app.data.repo

import com.niutrip.app.core.route.RouteLodParams
import com.niutrip.app.core.route.RouteLodPayload
import com.niutrip.app.data.local.CloudPointEntity
import com.niutrip.app.data.local.FakeCloudPointDao
import com.niutrip.app.data.local.FakePendingPointDao
import com.niutrip.app.data.local.FakeRouteLodCacheDao
import com.niutrip.app.data.local.PointSyncStateEntity
import com.niutrip.app.data.local.RouteLodCacheEntity
import com.niutrip.app.data.local.TrackDao
import com.niutrip.app.data.local.TrackEntity
import com.niutrip.app.data.remote.PointChangesDto
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.PointPatchIn
import com.niutrip.app.data.remote.ShareAnnotationPatch
import com.niutrip.app.data.remote.ShareAnnotationsIn
import com.niutrip.app.data.remote.ShareAnnotationsOut
import com.niutrip.app.data.remote.StubApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLodRepositoryTest {
    @Test fun `annotation save updates cache and fresh device reads server flag through deltas`() = runTest {
        var server = point("scenic").copy(point_source = "MANUAL", point_name = "山顶")
        val api = object : StubApi() {
            override suspend fun patchShareAnnotations(trackId: String, body: ShareAnnotationsIn): ShareAnnotationsOut {
                server = server.copy(share_annotation = body.updates.single().share_annotation)
                return ShareAnnotationsOut(listOf(server))
            }
            override suspend fun pointChanges(id: String, cursor: String?) = PointChangesDto("c1", false, listOf(server))
        }
        val cache = FakeCloudPointDao()
        val first = repository(api, points = cache)
        first.updateShareAnnotations("t1", listOf(ShareAnnotationPatch("scenic", true)))
        assertTrue(cache.forTrack("t1").single().toDto().toLite()!!.shareAnnotation)
        val second = repository(api)
        assertTrue(second.points("t1").single().share_annotation)
        second.updateShareAnnotations("t1", listOf(ShareAnnotationPatch("scenic", false)))
        assertFalse(first.points("t1").single().share_annotation)
    }

    @Test fun `incomplete server annotation response fails without changing cache`() = runTest {
        val cache = FakeCloudPointDao(initialPoints = listOf(CloudPointEntity.from("t1", point("scenic"))))
        val api = object : StubApi() {
            override suspend fun patchShareAnnotations(trackId: String, body: ShareAnnotationsIn) = ShareAnnotationsOut(emptyList())
        }
        val failure = runCatching { repository(api, points = cache).updateShareAnnotations("t1", listOf(ShareAnnotationPatch("scenic", true))) }
        assertTrue(failure.isFailure)
        assertFalse(cache.forTrack("t1").single().shareAnnotation)
    }

    private fun point(id: String) = PointDto(
        point_id = id, longitude = 100.0, latitude = 30.0,
        point_time = "2026-09-24T10:00:00", point_source = "AUTO")

    private fun repository(
        api: StubApi = StubApi(),
        points: FakeCloudPointDao = FakeCloudPointDao(),
        lod: FakeRouteLodCacheDao = FakeRouteLodCacheDao(),
        pending: FakePendingPointDao = FakePendingPointDao(),
    ) = TrackRepository(api, FakeTrackDao, points, pending, lod)

    @Test
    fun `cached baseline requires complete state and matching account`() = runTest {
        val points = FakeCloudPointDao(
            initialPoints = listOf(CloudPointEntity.from("t1", point("p1"))),
            initialStates = listOf(PointSyncStateEntity("t1", "c1", true, "test-account")),
        )
        // 无状态
        assertNull(repository(points = FakeCloudPointDao()).cachedRouteBaseline("t1"))
        // 基线不完整
        assertNull(repository(points = FakeCloudPointDao(
            initialStates = listOf(PointSyncStateEntity("t1", "c1", false, "test-account"))))
            .cachedRouteBaseline("t1"))
        // 账号不匹配
        assertNull(repository(points = FakeCloudPointDao(
            initialPoints = listOf(CloudPointEntity.from("t1", point("p1"))),
            initialStates = listOf(PointSyncStateEntity("t1", "c1", true, "someone-else"))))
            .cachedRouteBaseline("t1"))
        // 命中：完整且同账号
        val baseline = repository(points = points).cachedRouteBaseline("t1")
        assertEquals(listOf("p1"), baseline!!.points.map(PointDto::point_id))
        assertTrue(baseline.baselineComplete)
    }

    @Test
    fun `sync baseline runs point-changes loop and returns complete db state`() = runTest {
        val api = object : StubApi() {
            override suspend fun pointChanges(id: String, cursor: String?) =
                PointChangesDto("c1", false, listOf(point("p1"), point("p2")))
        }
        val repo = repository(api = api)

        val baseline = repo.syncRouteBaseline("t1")

        assertEquals(listOf("p1", "p2"), baseline.points.map(PointDto::point_id))
        assertTrue(baseline.baselineComplete)
    }

    @Test
    fun `sync baseline failure keeps local baseline silently`() = runTest {
        val api = object : StubApi() {
            override suspend fun pointChanges(id: String, cursor: String?) = error("offline")
        }
        val points = FakeCloudPointDao(
            initialPoints = listOf(CloudPointEntity.from("t1", point("cached"))),
            initialStates = listOf(PointSyncStateEntity("t1", "c1", true, "test-account")),
        )
        val baseline = repository(api = api, points = points).syncRouteBaseline("t1")
        assertEquals(listOf("cached"), baseline.points.map(PointDto::point_id))
        assertTrue(baseline.baselineComplete)
    }

    @Test
    fun `saveLod then cachedLod roundtrips and touches lru`() = runTest {
        val lod = FakeRouteLodCacheDao()
        val repo = repository(lod = lod)

        repo.saveLod(RouteLodCache(
            trackId = "t1", sourceFingerprint = "fp", pointCount = 2,
            importance = floatArrayOf(1f, 2f), mandatory = booleanArrayOf(true, false)))
        val cached = repo.cachedLod("t1", fingerprint = "fp", pointCount = 2)

        assertArrayEquals(floatArrayOf(1f, 2f), cached!!.importance, 0f)
        assertArrayEquals(booleanArrayOf(true, false), cached.mandatory)
        assertEquals(listOf("t1"), lod.touched)
        // 元数据落库
        assertEquals("fp", lod.rows["t1"]?.sourceFingerprint)
        assertEquals(RouteLodParams.ALGORITHM_VERSION, lod.rows["t1"]?.algorithmVersion)
    }

    @Test
    fun `cachedLod rejects mismatched fingerprint version count or account`() = runTest {
        val lod = FakeRouteLodCacheDao()
        val repo = repository(lod = lod)
        repo.saveLod(RouteLodCache(
            trackId = "t1", sourceFingerprint = "fp", pointCount = 2,
            importance = floatArrayOf(1f, 2f), mandatory = booleanArrayOf(false, false)))

        assertNull(repo.cachedLod("t1", "fp-modified", 2))
        assertNull(repo.cachedLod("t1", "fp", 3))
        assertNull(repo.cachedLod("t1", "fp", 2, accountKey = "other"))
        // 不匹配的行直接删除（缓存失效，方案 §5.2）
        assertTrue(lod.rows.isEmpty())
    }

    @Test
    fun `corrupt payload deletes cache row and returns null`() = runTest {
        val lod = FakeRouteLodCacheDao()
        lod.upsert(RouteLodCacheEntity(
            trackId = "t1", accountKey = "test-account", sourceFingerprint = "fp",
            algorithmVersion = RouteLodParams.ALGORITHM_VERSION, pointCount = 2,
            importancePayload = byteArrayOf(1, 2, 3), createdAt = 0, lastAccessAt = 0))
        val repo = repository(lod = lod)

        assertNull(repo.cachedLod("t1", "fp", 2))
        assertTrue(lod.rows.isEmpty())
    }

    @Test
    fun `decoded payload must match stored point count`() = runTest {
        val lod = FakeRouteLodCacheDao()
        val payload = RouteLodPayload.encode(floatArrayOf(1f, 2f), booleanArrayOf(false, false))
        lod.upsert(RouteLodCacheEntity(
            trackId = "t1", accountKey = "test-account", sourceFingerprint = "fp",
            algorithmVersion = RouteLodParams.ALGORITHM_VERSION, pointCount = 5,
            importancePayload = payload, createdAt = 0, lastAccessAt = 0))
        val repo = repository(lod = lod)

        assertNull(repo.cachedLod("t1", "fp", 5))   // 点数与 payload 不符 → 损坏
        assertTrue(lod.rows.isEmpty())
    }

    @Test
    fun `invalidateRouteGeometry deletes cache row`() = runTest {
        val lod = FakeRouteLodCacheDao()
        val repo = repository(lod = lod)
        repo.saveLod(RouteLodCache("t1", "fp", 1, floatArrayOf(1f), booleanArrayOf(true)))

        repo.invalidateRouteGeometry("t1")

        assertTrue(lod.rows.isEmpty())
    }

    @Test
    fun `point update and delete invalidate lod cache`() = runTest {
        val lod = FakeRouteLodCacheDao()
        val api = object : StubApi() {
            override suspend fun patchPoint(trackId: String, pointId: String, body: PointPatchIn) =
                point("p1").copy(point_longitude = body.longitude, point_latitude = body.latitude)
            override suspend fun deletePoint(trackId: String, pointId: String) {}
        }
        val points = FakeCloudPointDao(listOf(CloudPointEntity.from("t1", point("p1"))))
        val repo = TrackRepository(api, FakeTrackDao, points, FakePendingPointDao(), lod)
        repo.saveLod(RouteLodCache("t1", "fp", 1, floatArrayOf(1f), booleanArrayOf(true)))

        repo.updatePoint("t1", "p1", PointPatchIn(longitude = 101.0, latitude = 31.0))
        assertTrue("updatePoint 必须失效 LOD 缓存", lod.rows.isEmpty())

        repo.saveLod(RouteLodCache("t1", "fp", 1, floatArrayOf(1f), booleanArrayOf(true)))
        repo.deletePoint("t1", "p1")
        assertTrue("deletePoint 必须失效 LOD 缓存", lod.rows.isEmpty())
    }

    @Test
    fun `deleting track clears lod cache`() = runTest {
        val lod = FakeRouteLodCacheDao()
        val api = object : StubApi() {
            override suspend fun deleteTrack(id: String) {}
        }
        val repo = repository(api = api, lod = lod)
        repo.saveLod(RouteLodCache("t1", "fp", 1, floatArrayOf(1f), booleanArrayOf(true)))

        repo.delete("t1")

        assertTrue(lod.rows.isEmpty())
    }

    private object FakeTrackDao : TrackDao {
        override suspend fun upsert(track: TrackEntity) {}
        override suspend fun upsertAll(tracks: List<TrackEntity>) {}
        override fun observeAll() = flowOf(emptyList<TrackEntity>())
        override suspend fun get(id: String): TrackEntity? = null
        override suspend fun recording(): TrackEntity? = null
        override suspend fun clear() {}
    }
}
