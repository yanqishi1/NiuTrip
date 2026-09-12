package com.niutrip.app.ui.shareview

import com.niutrip.app.data.remote.ShareDataDto
import com.niutrip.app.data.remote.ShareStatsDto
import com.niutrip.app.data.remote.ShareTrackDto
import com.niutrip.app.data.remote.StubApi
import com.niutrip.app.service.LocResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val data = ShareDataDto(
        track = ShareTrackDto("t1", "分享轨迹", "", null, null, "AUTO", "好友", "PUBLIC"),
        stats = ShareStatsDto(0, 0, 0),
    )

    @Test fun `shared track location button focuses current position`() = runTest {
        val api = object : StubApi() {
            override suspend fun shareSave(token: String) = data
        }
        val viewModel = ShareViewViewModel("token", api) {
            LocResult.Success(113.3, 23.1, 1_700_000_000_000)
        }
        viewModel.focusCurrentLocation()
        assertEquals(113.3, viewModel.state.value.current?.lon ?: 0.0, 1e-9)
        assertEquals(1, viewModel.state.value.currentFocusRequest)
        assertFalse(viewModel.state.value.locatingCurrent)
    }

    @Test fun `shared track location failure is visible`() = runTest {
        val api = object : StubApi() {
            override suspend fun shareSave(token: String) = data
        }
        val viewModel = ShareViewViewModel("token", api) { LocResult.Failure("定位超时") }
        viewModel.focusCurrentLocation()
        assertEquals("定位超时", viewModel.state.value.locationError)
        assertFalse(viewModel.state.value.locatingCurrent)
    }
}
