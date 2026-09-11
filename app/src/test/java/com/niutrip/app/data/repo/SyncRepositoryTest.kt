package com.niutrip.app.data.repo

import com.niutrip.app.data.local.FakePendingPointDao
import com.niutrip.app.data.local.PendingPointEntity
import com.niutrip.app.data.remote.ApiException
import com.niutrip.app.data.remote.PointsIn
import com.niutrip.app.data.remote.PostPointsOut
import com.niutrip.app.data.remote.StubApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SyncRepositoryTest {
    private fun row(id: Long, pointId: String, trackId: String = "t1") = PendingPointEntity(
        id = id, trackId = trackId, pointId = pointId, lon = 100.0, lat = 30.0,
        time = 1_700_000_000_000 + id, source = "AUTO", createdAt = 0)

    @Test fun `concurrent flushes serialize so a batch is uploaded once`() = runTest {
        val dao = FakePendingPointDao(listOf(row(1, "p1"), row(2, "p2")))
        val api = OverlapApi()
        val repo = SyncRepository(api, dao)
        listOf(async { repo.flushOnce() }, async { repo.flushOnce() }).awaitAll()
        assertEquals(1, api.uploads.get())
        assertEquals(1, api.maxConcurrent.get())
    }

    @Test fun `failed upload keeps rows for next flush`() = runTest {
        val dao = FakePendingPointDao(listOf(row(1, "p1")))
        val repo = SyncRepository(object : StubApi() {
            override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut = error("offline")
        }, dao)
        assertTrue(repo.flushOnce() is FlushResult.Failed)
        assertEquals(1, dao.rows.size)
    }

    @Test fun `permanently rejected points are dropped and do not block other tracks`() = runTest {
        val dao = FakePendingPointDao(listOf(row(1, "pOld", trackId = "tOld"), row(2, "pNew", trackId = "tNew")))
        var uploadedToNew = 0
        val repo = SyncRepository(object : StubApi() {
            override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut {
                if (id == "tOld") throw ApiException(400, "轨迹已结束")  // 后端拒绝已结束轨迹的点
                uploadedToNew++; return PostPointsOut(body.points.size)
            }
        }, dao)
        val result = repo.flushOnce()
        assertEquals(1, uploadedToNew)   // 合法轨迹的点正常上传
        assertEquals(0, dao.rows.size)  // 被拒的点被丢弃，不再毒化队列
        assertTrue(result is FlushResult.Success && result.count == 1)
    }

    @Test fun `one tracks network failure does not block other tracks`() = runTest {
        val dao = FakePendingPointDao(listOf(row(1, "pOld", trackId = "tOld"), row(2, "pNew", trackId = "tNew")))
        val repo = SyncRepository(object : StubApi() {
            override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut {
                if (id == "tOld") throw RuntimeException("network down")
                return PostPointsOut(body.points.size)
            }
        }, dao)
        val result = repo.flushOnce()
        assertEquals(listOf(1L), dao.rows.map { it.id })  // 失败轨迹的点保留待重试
        assertTrue(result is FlushResult.Success && result.count == 1)
    }

    private class OverlapApi : StubApi() {
        val uploads = AtomicInteger(); val maxConcurrent = AtomicInteger(); val inFlight = AtomicInteger()
        override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut {
            maxConcurrent.accumulateAndGet(inFlight.incrementAndGet()) { m, c -> maxOf(m, c) }
            delay(10)
            inFlight.decrementAndGet(); uploads.incrementAndGet()
            return PostPointsOut(body.points.size)
        }
    }
}
