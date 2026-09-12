package com.niutrip.app.ui.detail

import com.niutrip.app.data.local.FakePendingPointDao
import com.niutrip.app.data.local.PendingPointEntity
import com.niutrip.app.data.local.TrackDao
import com.niutrip.app.data.local.TrackEntity
import com.niutrip.app.data.remote.ApiException
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.PointPatchIn
import com.niutrip.app.data.remote.PointsIn
import com.niutrip.app.data.remote.PointsPage
import com.niutrip.app.data.remote.PostPointsOut
import com.niutrip.app.data.remote.StubApi
import com.niutrip.app.data.remote.TrackDto
import com.niutrip.app.data.remote.TrackPatchIn
import com.niutrip.app.data.repo.SyncRepository
import com.niutrip.app.data.repo.TrackRepository
import com.niutrip.app.service.LocResult
import com.niutrip.app.service.LocationSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackDetailViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun trackDto() = TrackDto(track_id = "t1", track_name = "测试轨迹",
        track_record_mode = "AUTO", track_status = "RECORDING", point_count = 0)
    private fun pointDto(id: String) = PointDto(point_id = id, longitude = 100.0, latitude = 30.0,
        point_time = "2026-09-11T10:00:00", point_source = "MANUAL")

    private fun vm(points: List<PointDto>, source: LocationSource, api: StubApi? = null): TrackDetailViewModel {
        val resolved = api ?: object : StubApi() {
            override suspend fun track(id: String) = trackDto()
            override suspend fun points(id: String, page: Int) = PointsPage(points.size, null, null, points)
        }
        return TrackDetailViewModel("t1", TrackRepository(resolved, FakeDao), source, SyncRepository(resolved, FakePendingPointDao()))
    }

    @Test fun `empty track locates current position for map focus`() = runTest {
        val vm = vm(points = emptyList(), source = { LocResult.Success(100.1, 30.2, 1_700_000_000_000) })
        vm.load()
        assertEquals(100.1, vm.state.value.current?.lon ?: 0.0, 1e-9)
        assertEquals(30.2, vm.state.value.current?.lat ?: 0.0, 1e-9)
    }

    @Test fun `location failure keeps current null without crashing`() = runTest {
        val vm = vm(points = emptyList(), source = { LocResult.Failure("authfail") })
        vm.load()
        assertNull(vm.state.value.current)
    }

    @Test fun `track with points skips current location lookup`() = runTest {
        val vm = vm(points = listOf(pointDto("p1")), source = { LocResult.Success(99.9, 9.9, 1) })
        vm.load()
        assertNull(vm.state.value.current)
    }

    @Test fun `location button focuses current position even when track has points`() = runTest {
        val vm = vm(points = listOf(pointDto("p1")), source = {
            LocResult.Success(101.25, 30.75, 1_700_000_000_000)
        })
        vm.load()
        vm.focusCurrentLocation()
        assertEquals(101.25, vm.state.value.current?.lon ?: 0.0, 1e-9)
        assertEquals(30.75, vm.state.value.current?.lat ?: 0.0, 1e-9)
        assertEquals(1, vm.state.value.currentFocusRequest)
        assertFalse(vm.state.value.locatingCurrent)
    }

    @Test fun `editing checkin updates point and regrouped map data`() = runTest {
        val original = pointDto("p1")
        val api = object : StubApi() {
            override suspend fun track(id: String) = trackDto()
            override suspend fun points(id: String, page: Int) = PointsPage(1, null, null, listOf(original))
            override suspend fun patchPoint(trackId: String, pointId: String, body: PointPatchIn) =
                original.copy(point_name = body.point_name, point_desc = body.point_desc,
                    point_img_url = body.point_img_url)
        }
        val vm = vm(points = listOf(original), source = { LocResult.Failure("x") }, api = api)
        vm.load()
        vm.updateCheckin("p1", " 新标题 ", " 新内容 ", listOf("/media/new.jpg"), emptyList())
        val updated = vm.state.value.days.single().points.single()
        assertEquals("新标题", updated.name)
        assertEquals("新内容", updated.desc)
        assertEquals(listOf("/media/new.jpg"), updated.images)
        assertFalse(vm.state.value.updatingCheckin)
    }

    @Test fun `refresh after checkin reloads points without loading flash`() = runTest {
        val points = mutableListOf(pointDto("p1"))
        val gate = CompletableDeferred<Unit>()
        var firstLoad = true
        val api = object : StubApi() {
            override suspend fun track(id: String) = trackDto()
            override suspend fun points(id: String, page: Int): PointsPage {
                // 第一次加载立即返回；刷新挂起直到 gate 放行（模拟慢请求期间用户看到旧内容）
                if (!firstLoad) gate.await()
                firstLoad = false
                return PointsPage(points.size, null, null, points.toList())
            }
        }
        val vm = vm(points = emptyList(), source = { LocResult.Failure("x") }, api = api)
        vm.load()
        assertEquals(1, vm.state.value.points.size)
        points += pointDto("p2")  // 模拟打卡后服务器多了一个点
        vm.load()                  // 返回详情页触发的刷新
        assertFalse(vm.state.value.loading)          // 刷新期间不闪全屏 loading
        assertEquals(1, vm.state.value.points.size)  // 旧内容仍在
        gate.complete(Unit)
        assertEquals(2, vm.state.value.points.size)  // 刷新完成后看到新点
    }

    @Test fun `uploaded point event for this track reloads detail live`() = runTest {
        val points = mutableListOf(pointDto("p1"))
        val api = object : StubApi() {
            override suspend fun track(id: String) = trackDto()
            override suspend fun points(id: String, page: Int) = PointsPage(points.size, null, null, points.toList())
            override suspend fun postPoints(id: String, body: PointsIn) = PostPointsOut(body.points.size)
        }
        val sync = SyncRepository(api, FakePendingPointDao(listOf(
            PendingPointEntity(trackId = "t1", pointId = "p2", lon = 100.0, lat = 30.0,
                time = 1_700_000_001_000, source = "AUTO", createdAt = 0))))
        val vm = TrackDetailViewModel("t1", TrackRepository(api, FakeDao), LocationSource { LocResult.Failure("x") }, sync)
        vm.load()
        assertEquals(1, vm.state.value.points.size)
        points += pointDto("p3")                     // 服务器即将多一个点
        sync.flushOnce()                             // 队列把 t1 的点传上去 → 发事件
        assertEquals(2, vm.state.value.points.size)  // 详情页原地刷新，无需退出重进
    }

    @Test fun `start conflict surfaces error and dismiss clears it`() = runTest {
        val api = object : StubApi() {
            override suspend fun track(id: String) = trackDto()
            override suspend fun points(id: String, page: Int) = PointsPage(0, null, null, emptyList())
            override suspend fun patchTrack(id: String, body: TrackPatchIn): TrackDto = throw ApiException(409, "已有正在记录的轨迹")
        }
        val vm = vm(points = emptyList(), source = { LocResult.Failure("x") }, api = api)
        vm.changeStatus("RECORDING")
        assertEquals("已有正在记录的轨迹", vm.state.value.error)  // 用户能看见失败原因
        vm.dismissError()
        assertNull(vm.state.value.error)
    }

    private object FakeDao : TrackDao {
        override suspend fun upsert(track: TrackEntity) {}
        override suspend fun upsertAll(tracks: List<TrackEntity>) {}
        override fun observeAll() = flowOf(emptyList<TrackEntity>())
        override suspend fun get(id: String): TrackEntity? = null
        override suspend fun recording(): TrackEntity? = null
        override suspend fun clear() {}
    }
}
