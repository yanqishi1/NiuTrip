package com.niutrip.app.ui.detail

import android.app.Application
import com.niutrip.app.core.GeoBbox
import com.niutrip.app.core.PointLite
import com.niutrip.app.data.local.FakePendingPointDao
import com.niutrip.app.data.local.FakeCloudPointDao
import com.niutrip.app.data.local.FakeRouteLodCacheDao
import com.niutrip.app.data.local.CloudPointEntity
import com.niutrip.app.data.local.PendingPointEntity
import com.niutrip.app.data.local.PointSyncStateEntity
import com.niutrip.app.data.local.TrackDao
import com.niutrip.app.data.local.TrackEntity
import com.niutrip.app.data.remote.ApiException
import com.niutrip.app.data.remote.BboxPointsDto
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.PointPatchIn
import com.niutrip.app.data.remote.PointChangesDto
import com.niutrip.app.data.remote.PointsIn
import com.niutrip.app.data.remote.PointsPage
import com.niutrip.app.data.remote.PostPointsOut
import com.niutrip.app.data.remote.StubApi
import com.niutrip.app.data.remote.TrackDto
import com.niutrip.app.data.remote.TrackOverviewDto
import com.niutrip.app.data.remote.ShareDayDto
import com.niutrip.app.data.remote.TrackPatchIn
import com.niutrip.app.data.repo.SyncRepository
import com.niutrip.app.data.repo.TrackRepository
import com.niutrip.app.service.LocResult
import com.niutrip.app.service.LocationSource
import com.niutrip.app.ui.checkin.ImagePreparer
import com.niutrip.app.ui.checkin.PreparedImage
import com.niutrip.app.ui.detail.map.ALL_DAYS_SELECTION
import android.net.Uri
import okhttp3.MultipartBody
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TrackDetailViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun trackDto() = TrackDto(track_id = "t1", track_name = "测试轨迹",
        track_record_mode = "AUTO", track_status = "RECORDING", point_count = 0)
    private fun pointDto(id: String) = PointDto(point_id = id, longitude = 100.0, latitude = 30.0,
        point_time = "2026-09-11T10:00:00", point_source = "MANUAL")

    @Test fun `detail defaults to full data with current area camera`() {
        val state = DetailState()
        assertEquals(ALL_DAYS_SELECTION, state.selectedDay)
        assertFalse(state.fullRouteFocused)
    }

    @Test fun `selecting full route requests panorama focus`() {
        val vm = vm(emptyList(), LocationSource { LocResult.Failure("unused") })

        vm.selectDay(ALL_DAYS_SELECTION)

        assertTrue(vm.state.value.fullRouteFocused)
    }

    @Test fun `cloud point wins when a pending point has the same id`() {
        val pending = PendingPointEntity(
            trackId = "t1", pointId = "same", lon = 99.0, lat = 29.0,
            time = 1_700_000_000_000, source = "AUTO", createdAt = 0)

        val merged = mergeTrackPoints(
            listOf(pointDto("same").copy(longitude = 116.397)), listOf(pending))

        assertEquals(116.397, merged.single().longitude ?: 0.0, 0.0)
    }

    @Test fun `completed local baseline renders before network refresh finishes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val track = trackDto().copy(point_count = 1)
        val trackDao = object : TrackDao by FakeDao {
            override suspend fun get(id: String) = TrackEntity.from(track)
        }
        val cachedPoint = pointDto("cached")
        val pointCache = FakeCloudPointDao(
            initialPoints = listOf(CloudPointEntity.from("t1", cachedPoint)),
            initialStates = listOf(PointSyncStateEntity(
                "t1", "cursor-1", true, "test-account")),
        )
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean): TrackDto {
                gate.await()
                return track
            }
            override suspend fun trackOverview(id: String, recordView: Boolean): TrackOverviewDto =
                error("completed local baseline must skip full overview scan")
            override suspend fun pointChanges(id: String, cursor: String?) =
                PointChangesDto("cursor-2", false, emptyList())
        }
        val sync = SyncRepository(api, FakePendingPointDao())
        val vm = TrackDetailViewModel(
            "t1", TrackRepository(api, trackDao, pointCache),
            LocationSource { LocResult.Failure("unused") }, sync,
            ImagePreparer { error("unused") })

        vm.load()

        val cached = withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.state.first { !it.loading } }
        }
        assertEquals("cached", cached.points.single().point_id)   // 本地全量立即绘制
        // 几何后台构建完成后进入 Ready，全程未发 overview
        val ready = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.state.first { it.routeGeometry is RouteGeometryState.Ready }
            }
        }
        val geometry = ready.routeGeometry as RouteGeometryState.Ready
        assertEquals(listOf("cached"), (0 until geometry.index.pointCount).map(geometry.index::idAt))
        gate.complete(Unit)
    }

    @Test fun `overview previews first then full local sync switches to ready`() = runTest {
        var overviewCalls = 0
        val api = object : StubApi() {
            override suspend fun trackOverview(id: String, recordView: Boolean): TrackOverviewDto {
                overviewCalls++
                return overviewDto("preview")
            }
            override suspend fun pointChanges(id: String, cursor: String?) =
                PointChangesDto("cursor-1", false, listOf(pointDto("full1"), pointDto("full2")))
        }
        // 带云端点缓存：完整基线走 point-changes 增量同步
        val pointCache = FakeCloudPointDao()
        val vm = TrackDetailViewModel("t1", TrackRepository(api, FakeDao, pointCache),
            LocationSource { LocResult.Failure("unused") },
            SyncRepository(api, FakePendingPointDao()), ImagePreparer { error("unused") })
        vm.load()

        // 首屏：overview 占位（不等待全量）
        assertFalse(vm.state.value.loading)
        assertEquals("preview", vm.state.value.days.single().points.single().id)
        assertTrue(vm.state.value.routeGeometry is RouteGeometryState.Preview)
        assertEquals(1, overviewCalls)

        // 后台完整基线同步 + LOD 构建后切 Ready（方案 Phase 1）
        val ready = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.state.first { it.routeGeometry is RouteGeometryState.Ready }
            }
        }
        val geometry = ready.routeGeometry as RouteGeometryState.Ready
        assertEquals(setOf("full1", "full2"),
            (0 until geometry.index.pointCount).map(geometry.index::idAt).toSet())
        assertEquals(listOf("full1", "full2"), ready.days.single().points.map(PointLite::id))
    }

    @Test fun `background sync failure keeps local ready route silently`() = runTest {
        val track = trackDto().copy(track_status = "FINISHED")
        val trackDao = object : TrackDao by FakeDao {
            override suspend fun get(id: String) = TrackEntity.from(track)
        }
        val pointCache = FakeCloudPointDao(
            initialPoints = listOf(CloudPointEntity.from("t1", pointDto("cached"))),
            initialStates = listOf(PointSyncStateEntity("t1", "cursor-1", true, "test-account")),
        )
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = track
            override suspend fun pointChanges(id: String, cursor: String?): PointChangesDto =
                error("offline")   // 增量同步失败
        }
        val sync = SyncRepository(api, FakePendingPointDao())
        val vm = TrackDetailViewModel(
            "t1", TrackRepository(api, trackDao, pointCache),
            LocationSource { LocResult.Failure("unused") }, sync,
            ImagePreparer { error("unused") })

        vm.load()

        val ready = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.state.first { it.routeGeometry is RouteGeometryState.Ready }
            }
        }
        // 断网下：本地 Ready 保留、无错误（离线可用，方案 §2.4.7）
        assertTrue(ready.routeGeometry is RouteGeometryState.Ready)
        assertNull(ready.error)
        assertEquals("cached", ready.points.single().point_id)
    }

    @Test fun `camera idle never hits bbox api and produces render model`() = runTest {
        // pointsInBbox 未打桩（TODO()）：若被调用会直接炸掉测试
        val track = trackDto().copy(point_count = 1)
        val trackDao = object : TrackDao by FakeDao {
            override suspend fun get(id: String) = TrackEntity.from(track)
        }
        val pointCache = FakeCloudPointDao(
            initialPoints = listOf(CloudPointEntity.from("t1", pointDto("cached"))),
            initialStates = listOf(PointSyncStateEntity("t1", "cursor-1", true, "test-account")),
        )
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = track
            override suspend fun pointChanges(id: String, cursor: String?) =
                PointChangesDto("cursor-2", false, emptyList())
        }
        val sync = SyncRepository(api, FakePendingPointDao())
        val vm = TrackDetailViewModel(
            "t1", TrackRepository(api, trackDao, pointCache),
            LocationSource { LocResult.Failure("unused") }, sync,
            ImagePreparer { error("unused") })

        vm.load()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.state.first { it.routeGeometry is RouteGeometryState.Ready } }
        }
        vm.onCameraIdle(GeoBbox(99.95, 29.95, 100.05, 30.05), 15.5f)

        val model = withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.state.first { it.renderModel != null } }.renderModel!!
        }
        assertEquals(14, model.band)
        // 同一视口再次静止：key 不变，不重建
        val before = vm.state.value.renderModel
        vm.onCameraIdle(GeoBbox(99.95, 29.95, 100.05, 30.05), 15.9f)
        assertTrue(before === vm.state.value.renderModel ||
            vm.state.value.renderModel?.key == before?.key)
    }

    @Test fun `stale generation results cannot overwrite newer track`() = runTest {
        val track = trackDto()
        val trackDao = object : TrackDao by FakeDao {
            override suspend fun get(id: String) = TrackEntity.from(track)
        }
        val pointCache = FakeCloudPointDao(
            initialPoints = listOf(CloudPointEntity.from("t1", pointDto("first"))),
            initialStates = listOf(PointSyncStateEntity("t1", "cursor-1", true, "test-account")),
        )
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = track
            override suspend fun pointChanges(id: String, cursor: String?) =
                PointChangesDto("cursor-2", false, emptyList())
        }
        val sync = SyncRepository(api, FakePendingPointDao())
        val vm = TrackDetailViewModel(
            "t1", TrackRepository(api, trackDao, pointCache),
            LocationSource { LocResult.Failure("unused") }, sync,
            ImagePreparer { error("unused") })

        vm.load()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.state.first { it.routeGeometry is RouteGeometryState.Ready } }
        }

        // 数据变化后重进：第二次 load 的 generation 必须胜出
        pointCache.rows.removeAll { it.pointId == "first" }
        pointCache.upsertAll(listOf(CloudPointEntity.from("t1",
            pointDto("second").copy(longitude = 100.5, point_time = "2026-09-11T11:00:00"))))
        vm.load()
        val ready = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.state.first { state ->
                    (state.routeGeometry as? RouteGeometryState.Ready)?.generation == 2L
                }
            }
        }
        val geometry = ready.routeGeometry as RouteGeometryState.Ready
        assertEquals(listOf("second"), (0 until geometry.index.pointCount).map(geometry.index::idAt))
    }

    @Test fun `finished track lod cache is persisted for next visit`() = runTest {
        val track = trackDto().copy(track_status = "FINISHED")
        val trackDao = object : TrackDao by FakeDao {
            override suspend fun get(id: String) = TrackEntity.from(track)
        }
        val pointCache = FakeCloudPointDao(
            initialPoints = listOf(CloudPointEntity.from("t1", pointDto("cached"))),
            initialStates = listOf(PointSyncStateEntity("t1", "cursor-1", true, "test-account")),
        )
        val lod = FakeRouteLodCacheDao()
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = track
            override suspend fun pointChanges(id: String, cursor: String?) =
                PointChangesDto("cursor-2", false, emptyList())
        }
        val sync = SyncRepository(api, FakePendingPointDao())
        val vm = TrackDetailViewModel(
            "t1", TrackRepository(api, trackDao, pointCache, FakePendingPointDao(), lod),
            LocationSource { LocResult.Failure("unused") }, sync,
            ImagePreparer { error("unused") })

        vm.load()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.state.first { it.routeGeometry is RouteGeometryState.Ready } }
        }
        // 非录制轨迹：LOD 构建结果落缓存（方案 §5.3）
        assertEquals(1, lod.rows.size)
        assertEquals(1, lod.rows.values.single().pointCount)
    }

    @Test fun `recorded point during recording extends live tail locally`() = runTest {
        val track = trackDto().copy(track_status = "RECORDING")
        val trackDao = object : TrackDao by FakeDao {
            override suspend fun get(id: String) = TrackEntity.from(track)
        }
        val pointCache = FakeCloudPointDao(
            initialPoints = listOf(CloudPointEntity.from("t1", pointDto("cached"))),
            initialStates = listOf(PointSyncStateEntity("t1", "cursor-1", true, "test-account")),
        )
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = track
            override suspend fun pointChanges(id: String, cursor: String?) =
                PointChangesDto("cursor-2", false, emptyList())
        }
        val syncDao = FakePendingPointDao()
        val sync = SyncRepository(api, syncDao)
        val vm = TrackDetailViewModel(
            "t1", TrackRepository(api, trackDao, pointCache),
            LocationSource { LocResult.Failure("unused") }, sync,
            ImagePreparer { error("unused") })

        vm.load()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.state.first { it.routeGeometry is RouteGeometryState.Ready } }
        }
        sync.notifyPointRecorded(PendingPointEntity(trackId = "t1", pointId = "local1",
            lon = 116.397, lat = 39.908, time = 1_700_000_001_000,
            source = "AUTO", createdAt = 0))

        // 尾部全精度附加显示，且不打网络（方案 §5.3）
        val state = vm.state.value
        assertTrue(state.liveTail.isNotEmpty())
        assertEquals("local1", state.liveTail.last().id)
        assertEquals(setOf("cached", "local1"), state.points.map { it.point_id }.toSet())
    }

    private fun overviewDto(pointId: String) = TrackOverviewDto(
        trackDto().copy(point_count = 1), 88.0,
        listOf(ShareDayDto("2026-09-11", listOf(pointDto(pointId)))))
    private fun overviewApi(pointId: String): StubApi = object : StubApi() {
        override suspend fun trackOverview(id: String, recordView: Boolean) = overviewDto(pointId)
    }

    @Test fun `full point load requests large server pages`() = runTest {
        val requests = mutableListOf<Pair<Int, Int>>()
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) =
                trackDto().copy(point_count = 2)
            override suspend fun pointsLargePage(id: String, page: Int, pageSize: Int): PointsPage {
                requests += page to pageSize
                return if (page == 1) PointsPage(2, "next", null, listOf(pointDto("p1")))
                else PointsPage(2, null, "previous", listOf(pointDto("p2")))
            }
        }

        val vm = vm(emptyList(), LocationSource { LocResult.Failure("unused") }, api)
        vm.load()

        assertEquals(listOf(1 to 1000, 2 to 1000), requests)
        assertEquals(listOf("p1", "p2"), vm.state.value.points.map { it.point_id })
    }

    @Test fun `finishing uploads pending points before setting finished`() = runTest {
        val calls = mutableListOf<String>()
        val dao = FakePendingPointDao(listOf(PendingPointEntity(id = 1, trackId = "t1", pointId = "p1",
            lon = 100.0, lat = 30.0, time = 1_700_000_000_000, source = "AUTO", createdAt = 0)))
        val api = object : StubApi() {
            override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut {
                calls.add("upload")
                return PostPointsOut(body.points.size)
            }
            override suspend fun patchTrack(id: String, body: TrackPatchIn): TrackDto {
                calls.add("finish")
                return trackDto().copy(track_status = "FINISHED")
            }
        }
        val vm = TrackDetailViewModel("t1", TrackRepository(api, FakeDao),
            LocationSource { LocResult.Failure("unused") }, SyncRepository(api, dao), ImagePreparer { error("unused") })
        vm.changeStatus("FINISHED")
        assertEquals(listOf("upload", "finish"), calls)
        assertEquals("FINISHED", vm.state.value.track?.track_status)
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun `finishing rebuilds final geometry and absorbs live tail`() = runTest {
        val track = trackDto().copy(point_count = 1)
        val trackDao = object : TrackDao by FakeDao {
            override suspend fun get(id: String) = TrackEntity.from(track)
        }
        val pointCache = FakeCloudPointDao(
            initialPoints = listOf(CloudPointEntity.from("t1", pointDto("cached"))),
            initialStates = listOf(PointSyncStateEntity("t1", "cursor-1", true, "test-account")),
        )
        val lod = FakeRouteLodCacheDao()
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = track
            override suspend fun pointChanges(id: String, cursor: String?) =
                PointChangesDto("cursor-2", false, emptyList())
            override suspend fun patchTrack(id: String, body: TrackPatchIn) =
                track.copy(track_status = "FINISHED")
        }
        val sync = SyncRepository(api, FakePendingPointDao())
        val vm = TrackDetailViewModel(
            "t1", TrackRepository(api, trackDao, pointCache, lodCache = lod),
            LocationSource { LocResult.Failure("unused") }, sync,
            ImagePreparer { error("unused") })
        vm.load()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.state.first { it.routeGeometry is RouteGeometryState.Ready } }
        }
        sync.notifyPointRecorded(PendingPointEntity(
            trackId = "t1", pointId = "local1", lon = 100.01, lat = 30.0,
            time = 1_700_000_001_000, source = "AUTO", createdAt = 0))
        assertTrue(vm.state.value.liveTail.isNotEmpty())

        vm.changeStatus("FINISHED").join()
        val finished = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                vm.state.first {
                    it.track?.track_status == "FINISHED" &&
                        it.routeGeometry is RouteGeometryState.Ready && it.liveTail.isEmpty()
                }
            }
        }

        val geometry = finished.routeGeometry as RouteGeometryState.Ready
        assertEquals(setOf("cached", "local1"),
            (0 until geometry.index.pointCount).map(geometry.index::idAt).toSet())
        assertTrue(lod.rows.containsKey("t1"))
    }

    @Test fun `preview live point adds distance once before full baseline is ready`() = runTest {
        val first = pointDto("preview").copy(
            point_source = "AUTO", longitude = 100.0, latitude = 30.0,
            point_time = "2026-09-11T10:00:00")
        val api = object : StubApi() {
            override suspend fun trackOverview(id: String, recordView: Boolean) = TrackOverviewDto(
                trackDto().copy(point_count = 1), 500.0,
                listOf(ShareDayDto("2026-09-11", listOf(first))))
            override suspend fun pointChanges(id: String, cursor: String?): PointChangesDto =
                error("offline")
        }
        val sync = SyncRepository(api, FakePendingPointDao())
        val vm = TrackDetailViewModel(
            "t1", TrackRepository(api, FakeDao, FakeCloudPointDao()),
            LocationSource { LocResult.Failure("unused") }, sync,
            ImagePreparer { error("unused") })
        vm.load()
        val live = PendingPointEntity(
            trackId = "t1", pointId = "live1", lon = 100.01, lat = 30.0,
            time = 1_778_583_600_000, source = "AUTO", createdAt = 0)

        sync.notifyPointRecorded(live)
        val once = vm.state.value.totalDistanceMeters
        sync.notifyPointRecorded(live)

        assertTrue(once > 500.0)
        assertEquals(once, vm.state.value.totalDistanceMeters, 0.0)
    }

    @Test fun `failed final sync preserves points and does not finish`() = runTest {
        val dao = FakePendingPointDao(listOf(PendingPointEntity(id = 1, trackId = "t1", pointId = "p1",
            lon = 100.0, lat = 30.0, time = 1_700_000_000_000, source = "AUTO", createdAt = 0)))
        var finished = false
        val api = object : StubApi() {
            override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut = error("offline")
            override suspend fun patchTrack(id: String, body: TrackPatchIn): TrackDto {
                finished = true
                return trackDto().copy(track_status = "FINISHED")
            }
        }
        val vm = TrackDetailViewModel("t1", TrackRepository(api, FakeDao),
            LocationSource { LocResult.Failure("unused") }, SyncRepository(api, dao), ImagePreparer { error("unused") })
        vm.changeStatus("FINISHED")
        assertFalse(finished)
        assertEquals(1, dao.rows.size)
        assertTrue(vm.state.value.error!!.contains("暂未结束"))
        assertFalse(vm.state.value.changingStatus)
    }

    private fun vm(points: List<PointDto>, source: LocationSource, api: StubApi? = null): TrackDetailViewModel {
        val resolved = api ?: object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = trackDto()
            override suspend fun points(id: String, page: Int) = PointsPage(points.size, null, null, points)
        }
        return TrackDetailViewModel("t1", TrackRepository(resolved, FakeDao), source,
            SyncRepository(resolved, FakePendingPointDao()), ImagePreparer { error("unused") })
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

    @Test fun `shared detail records view only on first load`() = runTest {
        val flags = mutableListOf<Boolean>()
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean): TrackDto {
                flags += recordView
                return trackDto()
            }
            override suspend fun points(id: String, page: Int) =
                PointsPage(0, null, null, emptyList())
        }
        val vm = TrackDetailViewModel(
            "t1",
            TrackRepository(api, FakeDao),
            LocationSource { LocResult.Failure("unused") },
            SyncRepository(api, FakePendingPointDao()),
            ImagePreparer { error("unused") },
            recordSharedView = true,
        )

        vm.load()
        vm.load()

        assertEquals(listOf(true, false), flags)
    }

    @Test fun `recording track uses latest point when device location fails`() = runTest {
        val vm = vm(points = listOf(pointDto("p1")), source = { LocResult.Failure("unavailable") })
        vm.load()
        assertEquals(100.0, vm.state.value.current?.lon ?: 0.0, 1e-9)
        assertEquals(30.0, vm.state.value.current?.lat ?: 0.0, 1e-9)
    }

    @Test fun `opening track locates immediately and location button can focus again`() = runTest {
        var locationCalls = 0
        val vm = vm(points = listOf(pointDto("p1")), source = {
            locationCalls++
            LocResult.Success(101.25, 30.75, 1_700_000_000_000)
        })
        vm.load()
        assertEquals(1, locationCalls)
        assertEquals(1, vm.state.value.currentFocusRequest)
        vm.focusCurrentLocation()
        assertEquals(2, locationCalls)
        assertEquals(101.25, vm.state.value.current?.lon ?: 0.0, 1e-9)
        assertEquals(30.75, vm.state.value.current?.lat ?: 0.0, 1e-9)
        assertEquals(2, vm.state.value.currentFocusRequest)
        assertFalse(vm.state.value.locatingCurrent)
    }

    @Test fun `editing checkin updates point and regrouped map data`() = runTest {
        val original = pointDto("p1")
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = trackDto()
            override suspend fun points(id: String, page: Int) = PointsPage(1, null, null, listOf(original))
            override suspend fun patchPoint(trackId: String, pointId: String, body: PointPatchIn) =
                original.copy(longitude = body.longitude, latitude = body.latitude,
                    point_name = body.point_name, point_desc = body.point_desc,
                    point_img_url = body.point_img_url,
                    point_source = if (body.point_name.isNullOrBlank()) "AUTO" else "MANUAL")
        }
        val vm = vm(points = listOf(original), source = { LocResult.Failure("x") }, api = api)
        vm.load()
        vm.updatePoint("p1", true, " 新标题 ", " 新内容 ", 116.397, 39.908,
            listOf("/media/new.jpg"), emptyList())
        val updated = vm.state.value.days.single().points.single()
        assertEquals("新标题", updated.name)
        assertEquals("新内容", updated.desc)
        assertEquals(116.397, updated.lon, 1e-9)
        assertEquals(39.908, updated.lat, 1e-9)
        assertEquals(listOf("/media/new.jpg"), updated.images)
        assertFalse(vm.state.value.updatingPoint)
    }

    @Test fun `adding checkin photos refreshes automatic track cover`() = runTest {
        val original = pointDto("p1")
        var cover = "/static/covers/default.svg"
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) =
                trackDto().copy(track_img_url = cover)
            override suspend fun points(id: String, page: Int) =
                PointsPage(1, null, null, listOf(original))
            override suspend fun patchPoint(trackId: String, pointId: String, body: PointPatchIn): PointDto {
                cover = body.point_img_url.first()
                return original.copy(point_name = body.point_name, point_img_url = body.point_img_url)
            }
        }
        val vm = vm(listOf(original), LocationSource { LocResult.Failure("unused") }, api)
        vm.load()
        vm.updatePoint("p1", true, "Check-in", "", 100.0, 30.0,
            listOf("/media/first.jpg", "/media/second.jpg"), emptyList())
        assertEquals("/media/first.jpg", vm.state.value.track?.track_img_url)
        assertFalse(vm.state.value.updatingPoint)
    }

    @Test fun `auto point edit requires title and upgrades to checkin`() = runTest {
        val original = pointDto("p1").copy(point_source = "AUTO", point_name = null)
        var patchCalls = 0
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = trackDto()
            override suspend fun points(id: String, page: Int) = PointsPage(1, null, null, listOf(original))
            override suspend fun patchPoint(trackId: String, pointId: String, body: PointPatchIn): PointDto {
                patchCalls++
                return original.copy(longitude = body.longitude, latitude = body.latitude,
                    point_name = body.point_name, point_source = "MANUAL")
            }
        }
        val vm = vm(listOf(original), LocationSource { LocResult.Failure("unused") }, api)
        vm.load()

        vm.updatePoint("p1", false, "", "", 100.1, 30.1, emptyList(), emptyList())
        assertEquals("编辑自动轨迹点时必须填写标题", vm.state.value.error)
        assertEquals(0, patchCalls)

        vm.dismissError()
        vm.updatePoint("p1", false, "修正点", "", 100.1, 30.1, emptyList(), emptyList())
        assertEquals(1, patchCalls)
        assertEquals(true, vm.state.value.days.single().points.single().isCheckin)
    }

    @Test fun `clearing all checkin content demotes it to auto point`() = runTest {
        val original = pointDto("p1").copy(point_name = "原打卡", point_desc = "原描述",
            point_img_url = listOf("/media/a.jpg"))
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = trackDto()
            override suspend fun points(id: String, page: Int) = PointsPage(1, null, null, listOf(original))
            override suspend fun patchPoint(trackId: String, pointId: String, body: PointPatchIn) =
                original.copy(point_name = body.point_name, point_desc = body.point_desc,
                    point_img_url = body.point_img_url, point_source = "AUTO")
        }
        val vm = vm(listOf(original), LocationSource { LocResult.Failure("unused") }, api)
        vm.load()

        vm.updatePoint("p1", true, "", "", 100.0, 30.0, emptyList(), emptyList())

        assertFalse(vm.state.value.days.single().points.single().isCheckin)
    }

    @Test fun `description or image without title is rejected locally`() = runTest {
        val vm = vm(listOf(pointDto("p1")), LocationSource { LocResult.Failure("unused") })
        vm.load()

        vm.updatePoint("p1", true, "", "描述", 100.0, 30.0, emptyList(), emptyList())
        assertEquals("填写描述或添加图片前，请先填写标题", vm.state.value.error)
    }

    @Test fun `cloud point delete removes it from route`() = runTest {
        val original = pointDto("p1")
        var deletedId: String? = null
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = trackDto()
            override suspend fun points(id: String, page: Int) = PointsPage(1, null, null, listOf(original))
            override suspend fun deletePoint(trackId: String, pointId: String) { deletedId = pointId }
        }
        val vm = vm(listOf(original), LocationSource { LocResult.Failure("unused") }, api)
        vm.load()

        vm.deletePoint("p1")

        assertEquals("p1", deletedId)
        assertEquals(emptyList<PointDto>(), vm.state.value.points)
    }

    @Test fun `pending point can be edited and deleted without cloud detail call`() = runTest {
        val pending = PendingPointEntity(id = 7, trackId = "t1", pointId = "local",
            lon = 100.0, lat = 30.0, imgs = "[\"/media/pending.jpg\"]",
            time = 1_700_000_001_000, source = "AUTO", createdAt = 0)
        val dao = FakePendingPointDao(listOf(pending))
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = trackDto()
            override suspend fun points(id: String, page: Int) = PointsPage(0, null, null, emptyList())
            override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut = error("offline")
            override suspend fun patchPoint(trackId: String, pointId: String, body: PointPatchIn): PointDto =
                error("local point must not be patched in cloud")
            override suspend fun deletePoint(trackId: String, pointId: String) =
                error("local point must not be deleted in cloud")
        }
        val vm = TrackDetailViewModel("t1", TrackRepository(api, FakeDao),
            LocationSource { LocResult.Failure("unused") }, SyncRepository(api, dao),
            ImagePreparer { error("unused") })
        vm.load()
        assertEquals(listOf("/media/pending.jpg"), vm.state.value.points.single().point_img_url)

        vm.updatePoint("local", false, "本地点", "", 101.0, 31.0, emptyList(), emptyList())
        assertEquals("MANUAL", dao.rows.single().source)
        assertEquals(101.0, dao.rows.single().lon, 1e-9)

        vm.deletePoint("local")
        assertTrue(dao.rows.isEmpty())
        assertTrue(vm.state.value.points.isEmpty())
    }

    @Test fun `refresh after checkin reloads points without loading flash`() = runTest {
        val points = mutableListOf(pointDto("p1"))
        val gate = CompletableDeferred<Unit>()
        var firstLoad = true
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = trackDto()
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
        val refreshed = withTimeout(5_000) { vm.state.first { it.points.size == 2 } }
        assertEquals(2, refreshed.points.size)       // 刷新完成后看到新点
    }

    @Test fun `pending points render locally and upload does not reload cloud points`() = runTest {
        val points = mutableListOf(pointDto("p1"))
        var pointLoads = 0
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = trackDto()
            override suspend fun points(id: String, page: Int): PointsPage {
                pointLoads++
                return PointsPage(points.size, null, null, points.toList())
            }
            override suspend fun postPoints(id: String, body: PointsIn) = PostPointsOut(body.points.size)
        }
        val sync = SyncRepository(api, FakePendingPointDao(listOf(
            PendingPointEntity(trackId = "t1", pointId = "p2", lon = 100.0, lat = 30.0,
                time = 1_700_000_001_000, source = "AUTO", createdAt = 0))))
        val vm = TrackDetailViewModel("t1", TrackRepository(api, FakeDao), LocationSource { LocResult.Failure("x") },
            sync, ImagePreparer { error("unused") })
        vm.load()
        assertEquals(setOf("p1", "p2"), vm.state.value.points.map { it.point_id }.toSet())
        sync.flushOnce()
        assertEquals(1, pointLoads)
        assertEquals(setOf("p1", "p2"), vm.state.value.points.map { it.point_id }.toSet())
    }

    @Test fun `locally recorded point moves current marker before upload`() = runTest {
        val api = object : StubApi() {}
        val sync = SyncRepository(api, FakePendingPointDao())
        val vm = TrackDetailViewModel("t1", TrackRepository(api, FakeDao),
            LocationSource { LocResult.Failure("unused") }, sync, ImagePreparer { error("unused") })

        sync.notifyPointRecorded(PendingPointEntity(trackId = "t1", pointId = "local1",
            lon = 116.397, lat = 39.908, time = 1_700_000_001_000,
            source = "AUTO", createdAt = 0))

        assertEquals(116.397, vm.state.value.current?.lon ?: 0.0, 1e-9)
        assertEquals(39.908, vm.state.value.current?.lat ?: 0.0, 1e-9)
        assertEquals(0, vm.state.value.currentFocusRequest)
    }

    @Test fun `locally recorded point from another track is ignored`() = runTest {
        val api = object : StubApi() {}
        val sync = SyncRepository(api, FakePendingPointDao())
        val vm = TrackDetailViewModel("t1", TrackRepository(api, FakeDao),
            LocationSource { LocResult.Failure("unused") }, sync, ImagePreparer { error("unused") })

        sync.notifyPointRecorded(PendingPointEntity(trackId = "t2", pointId = "local2",
            lon = 116.397, lat = 39.908, time = 1_700_000_001_000,
            source = "AUTO", createdAt = 0))

        assertNull(vm.state.value.current)
    }

    @Test fun `location button stores a point locally while recording`() = runTest {
        val dao = FakePendingPointDao()
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = trackDto()
            override suspend fun points(id: String, page: Int) = PointsPage(0, null, null, emptyList())
            override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut = error("offline")
        }
        val vm = TrackDetailViewModel("t1", TrackRepository(api, FakeDao),
            LocationSource { LocResult.Success(116.397, 39.908, 1_700_000_001_000) },
            SyncRepository(api, dao), ImagePreparer { error("unused") })

        vm.load()
        vm.focusCurrentLocation()

        assertEquals(1, dao.rows.size)
        assertEquals(1, vm.state.value.points.size)
        assertEquals(116.397, vm.state.value.points.single().longitude ?: 0.0, 1e-9)
    }

    @Test fun `start conflict surfaces error and dismiss clears it`() = runTest {
        val api = object : StubApi() {
            override suspend fun track(id: String, recordView: Boolean) = trackDto()
            override suspend fun points(id: String, page: Int) = PointsPage(0, null, null, emptyList())
            override suspend fun patchTrack(id: String, body: TrackPatchIn): TrackDto = throw ApiException(409, "已有正在记录的轨迹")
        }
        val vm = vm(points = emptyList(), source = { LocResult.Failure("x") }, api = api)
        vm.changeStatus("RECORDING")
        assertEquals("已有正在记录的轨迹", vm.state.value.error)  // 用户能看见失败原因
        vm.dismissError()
        assertNull(vm.state.value.error)
    }

    @Test fun `cover image is uploaded through track cover endpoint`() = runTest {
        var updatedTrackId: String? = null
        val api = object : StubApi() {
            override suspend fun updateTrackCover(id: String, image: MultipartBody.Part): TrackDto {
                updatedTrackId = id
                return trackDto().copy(track_img_url = "/media/uploads/new-cover.jpg")
            }
        }
        val imageFile = File.createTempFile("track-cover-test", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val viewModel = TrackDetailViewModel(
            "t1",
            TrackRepository(api, FakeDao),
            LocationSource { LocResult.Failure("unused") },
            SyncRepository(api, FakePendingPointDao()),
            ImagePreparer { PreparedImage(imageFile, "image/jpeg", false) },
        )

        viewModel.updateImage(Uri.EMPTY).join()

        assertEquals("t1", updatedTrackId)
        assertEquals("/media/uploads/new-cover.jpg", viewModel.state.value.track?.track_img_url)
        assertFalse(viewModel.state.value.updatingImage)
        assertFalse(imageFile.exists())
    }

    @Test fun `rename trims and updates track name`() = runTest {
        var submittedName: String? = null
        val api = object : StubApi() {
            override suspend fun patchTrack(id: String, body: TrackPatchIn): TrackDto {
                submittedName = body.track_name
                return trackDto().copy(track_name = body.track_name.orEmpty())
            }
        }
        val viewModel = vm(emptyList(), LocationSource { LocResult.Failure("unused") }, api)

        viewModel.rename("  新轨迹名称  ")

        assertEquals("新轨迹名称", submittedName)
        assertEquals("新轨迹名称", viewModel.state.value.track?.track_name)
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
