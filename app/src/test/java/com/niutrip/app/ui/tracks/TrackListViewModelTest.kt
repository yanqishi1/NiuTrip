package com.niutrip.app.ui.tracks

import android.app.Application
import com.niutrip.app.data.local.TrackDao
import com.niutrip.app.data.local.TrackEntity
import com.niutrip.app.data.remote.ShareDataDto
import com.niutrip.app.data.remote.ShareStatsDto
import com.niutrip.app.data.remote.ShareTrackDto
import com.niutrip.app.data.remote.StubApi
import com.niutrip.app.data.remote.TrackDto
import com.niutrip.app.data.repo.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class TrackListViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `valid shared link is saved and shared list refreshes`() {
        var savedToken: String? = null
        val sharedTrack = TrackDto(
            track_id = "t1",
            track_name = "好友的旅程",
            track_record_mode = "AUTO",
            track_status = "FINISHED",
            sharer_username = "好友",
        )
        val api = object : StubApi() {
            override suspend fun shareSave(token: String): ShareDataDto {
                savedToken = token
                return shareData()
            }

            override suspend fun tracks(scope: String) = listOf(sharedTrack)
        }
        val vm = TrackListViewModel(TrackRepository(api, FakeDao))

        vm.openImportDialog()
        vm.setImportLink("好友分享：https://niutrip.cn/t/token_123")
        vm.importShared()

        assertEquals("token_123", savedToken)
        assertFalse(vm.state.value.showImportDialog)
        assertEquals(listOf(sharedTrack), vm.state.value.shared)
        assertEquals("轨迹已添加到「分享给我的」", vm.state.value.notice)
    }

    @Test fun `invalid shared link stays in dialog with validation error`() {
        val vm = TrackListViewModel(TrackRepository(object : StubApi() {}, FakeDao))
        vm.openImportDialog()
        vm.setImportLink("这不是轨迹链接")

        vm.importShared()

        assertTrue(vm.state.value.showImportDialog)
        assertEquals("请输入有效的轨迹分享链接", vm.state.value.importError)
        assertNull(vm.state.value.notice)
    }

    private fun shareData() = ShareDataDto(
        track = ShareTrackDto(
            track_id = "t1",
            track_name = "好友的旅程",
            record_mode = "AUTO",
            owner_username = "好友",
            share_mode = "PUBLIC",
        ),
        stats = ShareStatsDto(0, 0, 0),
    )

    private object FakeDao : TrackDao {
        override suspend fun upsert(track: TrackEntity) {}
        override suspend fun upsertAll(tracks: List<TrackEntity>) {}
        override fun observeAll() = flowOf(emptyList<TrackEntity>())
        override suspend fun get(id: String): TrackEntity? = null
        override suspend fun recording(): TrackEntity? = null
        override suspend fun clear() {}
    }
}
