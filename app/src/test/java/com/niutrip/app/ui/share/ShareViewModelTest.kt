package com.niutrip.app.ui.share

import com.niutrip.app.BuildConfig
import com.niutrip.app.data.remote.ShareIn
import com.niutrip.app.data.remote.ShareOut
import com.niutrip.app.data.remote.StubApi
import com.niutrip.app.data.remote.TrackDto
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.PointsPage
import com.niutrip.app.data.remote.PointsIn
import com.niutrip.app.data.remote.PostPointsOut
import com.niutrip.app.data.local.*
import com.niutrip.app.data.repo.TrackRepository
import com.niutrip.app.data.repo.SyncRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `share link uses SHARE_BASE_URL origin not API origin`() {
        val vm = imageVm(FakeApi(ShareOut("PUBLIC", "/t/tok123")))
        vm.pick("PUBLIC"); vm.generate()
        assertEquals(BuildConfig.SHARE_BASE_URL + "/t/tok123", (vm.state.value.result as ShareResult.Done).url)
    }

    @Test fun `share text keeps url unchanged on its own line with usage hint`() {
        val url = "https://niutrip.gyberpunk123.asia/t/tok123/"
        assertEquals(
            listOf(
                "【旅行牛牛 旅行轨迹分享】",
                url,
                "点击这个链接，打开网页可以直接查看轨迹。",
                "复制这条消息，打开旅行牛牛 App 即可查看并保存这条轨迹",
            ),
            buildShareText(url).lines(),
        )
    }

    @Test fun `finished defaults to image and tab switching preserves link permission`() = runTest {
        val api = ImageApi("FINISHED", "ONCE")
        val vm = imageVm(api)
        vm.load()
        assertEquals(ShareKind.IMAGE, vm.state.value.kind)
        vm.selectKind(ShareKind.LINK)
        vm.selectKind(ShareKind.IMAGE)
        assertEquals("ONCE", vm.state.value.selectedMode)
        assertEquals(0, api.shareCalls)
    }

    @Test fun `recording only supports links`() = runTest {
        val vm = imageVm(ImageApi("RECORDING"))
        vm.load()
        vm.selectKind(ShareKind.IMAGE)
        vm.generateImage()
        assertEquals(ShareKind.LINK, vm.state.value.kind)
        assertEquals(ImageResult.Idle, vm.state.value.image)
    }

    @Test fun `image merges all cloud pages and local points without generating or consuming a link`() = runTest {
        val api = ImageApi("FINISHED", "ONCE")
        val local = PendingPointEntity(id = 1, trackId = "t1", pointId = "local",
            lon = 101.0, lat = 31.0, time = 1_700_000_000_000, source = "AUTO", createdAt = 0)
        val dao = FakePendingPointDao(listOf(local))
        val vm = imageVm(api, dao)
        vm.load()
        vm.generateImage()
        assertEquals(listOf(1, 2), api.pages)
        assertEquals(setOf("p1", "p2", "local"), vm.state.value.imageData!!.days.flatMap { it.points }.map { it.id }.toSet())
        assertEquals(0, api.shareCalls)
        assertEquals(0, api.uploadCalls)
        assertEquals(1, dao.rows.size)
        assertEquals("ONCE", vm.state.value.selectedMode)
    }

    @Test fun `empty route shows image error while link still works`() = runTest {
        val api = ImageApi("FINISHED", empty = true)
        val vm = imageVm(api)
        vm.load()
        vm.generateImage()
        assertTrue(vm.state.value.image is ImageResult.Error)
        vm.selectKind(ShareKind.LINK)
        vm.pick("PUBLIC")
        vm.generate()
        assertTrue(vm.state.value.result is ShareResult.Done)
    }

    @Test fun `image generation is single flight and cancellation ignores late data`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = ImageApi("FINISHED", gate = gate)
        val vm = imageVm(api)
        vm.load()
        vm.generateImage()
        vm.generateImage()
        assertEquals(listOf(1), api.pages)
        vm.cancelImage()
        gate.complete(Unit)
        assertEquals(ImageResult.Idle, vm.state.value.image)
        assertNull(vm.state.value.imageData)
    }

    @Test fun `sync failure warns but does not prevent sharing finished link`() = runTest {
        val api = ImageApi("FINISHED")
        val local = PendingPointEntity(id = 1, trackId = "t1", pointId = "local",
            lon = 101.0, lat = 31.0, time = 1_700_000_000_000, source = "AUTO", createdAt = 0)
        val dao = FakePendingPointDao(listOf(local))
        val vm = imageVm(api, dao)
        vm.load()
        vm.pick("PUBLIC")
        vm.generate()
        assertTrue(vm.state.value.result is ShareResult.Done)
        assertTrue(vm.state.value.warning != null)
        assertEquals(1, dao.rows.size)
    }

    @Test fun `image result and link result coexist without rotating the link`() = runTest {
        val api = ImageApi("FINISHED", "PUBLIC")
        val vm = imageVm(api)
        vm.load()
        vm.selectKind(ShareKind.LINK)
        vm.generate()
        val link = vm.state.value.result
        vm.selectKind(ShareKind.IMAGE)
        vm.generateImage()
        vm.imageReady(File("route.png"))
        vm.selectKind(ShareKind.LINK)
        assertEquals(link, vm.state.value.result)
        assertTrue(vm.state.value.image is ImageResult.Ready)
        assertEquals(1, api.shareCalls)
    }

    private fun imageVm(api: StubApi, dao: FakePendingPointDao = FakePendingPointDao()) =
        ShareViewModel("t1", api, TrackRepository(api, FakeDao), SyncRepository(api, dao))

    private class ImageApi(
        private val status: String,
        private val mode: String = "PRIVATE",
        private val empty: Boolean = false,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : StubApi() {
        val pages = mutableListOf<Int>()
        var shareCalls = 0
        var uploadCalls = 0
        override suspend fun track(id: String, recordView: Boolean) = TrackDto(
            track_id = id, track_name = "Trip", track_record_mode = "AUTO", track_status = status, share_mode = mode)
        override suspend fun points(id: String, page: Int): PointsPage {
            pages.add(page)
            gate?.await()
            if (empty) return PointsPage(0, results = emptyList())
            val point = PointDto(point_id = "p$page", longitude = 100.0 + page, latitude = 30.0,
                point_time = "2026-09-1${page}T10:00:00", point_source = "AUTO")
            return PointsPage(2, next = if (page == 1) "/next" else null, results = listOf(point))
        }
        override suspend fun share(id: String, body: ShareIn): ShareOut {
            shareCalls++
            return ShareOut(body.share_mode, "/t/token")
        }
        override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut {
            uploadCalls++
            error("offline")
        }
    }

    private object FakeDao : TrackDao {
        override suspend fun upsert(track: TrackEntity) = Unit
        override suspend fun upsertAll(tracks: List<TrackEntity>) = Unit
        override fun observeAll() = flowOf(emptyList<TrackEntity>())
        override suspend fun get(id: String): TrackEntity? = null
        override suspend fun recording(): TrackEntity? = null
        override suspend fun clear() = Unit
    }

    private class FakeApi(private val out: ShareOut) : StubApi() {
        override suspend fun share(id: String, body: ShareIn) = out
    }
}
