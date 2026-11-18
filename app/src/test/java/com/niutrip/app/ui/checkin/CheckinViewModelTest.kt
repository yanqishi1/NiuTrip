package com.niutrip.app.ui.checkin

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.niutrip.app.data.remote.PointsIn
import com.niutrip.app.data.remote.PostPointsOut
import com.niutrip.app.data.remote.StubApi
import com.niutrip.app.service.LocResult
import com.niutrip.app.service.LocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class CheckinViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `checkin title is required before upload`() = runTest {
        var uploadCalls = 0
        val api = object : StubApi() {
            override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut {
                uploadCalls++
                return PostPointsOut(1)
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = CheckinViewModel(
            "t1",
            api,
            LocationSource { LocResult.Success(116.397, 39.908, 1_000) },
            ImageCompressor(context),
        )
        viewModel.setDesc("只有描述")

        viewModel.submit()

        assertEquals(CheckinResult.Error("打卡点标题不能为空"), viewModel.state.value.result)
        assertEquals(0, uploadCalls)
    }

    @Test fun `share flag defaults unchecked and saves only after successful publish`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var posted: com.niutrip.app.data.remote.PointIn? = null
        var fail = true
        val api = object : StubApi() {
            override suspend fun postPoints(id: String, body: PointsIn): PostPointsOut {
                posted = body.points.single()
                if (fail) error("offline")
                return PostPointsOut(1)
            }
        }
        val vm = CheckinViewModel("t1", api, LocationSource { LocResult.Success(116.397, 39.908, 1_000) },
            ImageCompressor(context))
        assertEquals(false, vm.state.value.shareAnnotation)
        vm.setName("山顶")
        vm.setShareAnnotation(true)
        vm.submit()
        assertEquals(true, posted!!.share_annotation)
        assertEquals(true, vm.state.value.result is CheckinResult.Error)
        fail = false
        vm.submit()
        assertEquals(true, posted!!.share_annotation)
        assertEquals(CheckinResult.Done, vm.state.value.result)
    }
}
