package com.niutrip.app.ui.share

import com.niutrip.app.BuildConfig
import com.niutrip.app.data.remote.ShareIn
import com.niutrip.app.data.remote.ShareOut
import com.niutrip.app.data.remote.StubApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `share link uses SHARE_BASE_URL origin not API origin`() {
        val vm = ShareViewModel("t1", FakeApi(ShareOut("PUBLIC", "/t/tok123")))
        vm.pick("PUBLIC"); vm.generate()
        assertEquals(BuildConfig.SHARE_BASE_URL + "/t/tok123", (vm.state.value.result as ShareResult.Done).url)
    }

    private class FakeApi(private val out: ShareOut) : StubApi() {
        override suspend fun share(id: String, body: ShareIn) = out
    }
}
