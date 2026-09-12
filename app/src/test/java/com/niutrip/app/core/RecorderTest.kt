package com.niutrip.app.core

import com.niutrip.app.data.local.FakePendingPointDao
import com.niutrip.app.service.LocResult
import com.niutrip.app.service.LocationProfile
import com.niutrip.app.service.LocationSource
import com.niutrip.app.service.Recorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTest {
    @Test fun `small stationary movement is filtered without triggering upload`() = runTest {
        val dao = FakePendingPointDao()
        var location = LocResult.Success(116.0, 39.0, 1_000, 8f)
        var uploads = 0
        val recorder = Recorder({ location }, dao, afterCollect = { uploads++ })
        assertTrue(recorder.collectOnce("t1"))
        location = LocResult.Success(116.00005, 39.00005, 2_000, 8f)
        assertTrue(recorder.collectOnce("t1"))
        assertEquals(1, dao.totalInserted)
        assertEquals(1, uploads)
    }

    @Test fun `real movement is recorded and inaccurate fixes are retried`() = runTest {
        val dao = FakePendingPointDao()
        var location = LocResult.Success(116.0, 39.0, 1_000, 8f)
        val recorder = Recorder({ location }, dao)
        assertTrue(recorder.collectOnce("t1"))
        location = LocResult.Success(116.001, 39.001, 2_000, 8f)
        assertTrue(recorder.collectOnce("t1"))
        location = LocResult.Success(117.0, 40.0, 3_000, 150f)
        assertFalse(recorder.collectOnce("t1"))
        assertEquals(2, dao.totalInserted)
    }

    @Test fun `location exception is retried instead of terminating the recorder`() = runTest {
        val dao = FakePendingPointDao()
        val recorder = Recorder({ error("location client failed") }, dao)
        assertFalse(recorder.collectOnce("t1"))
        assertEquals(0, dao.totalInserted)
    }

    @Test fun `upload exception does not discard the locally recorded point`() = runTest {
        val dao = FakePendingPointDao()
        val recorder = Recorder({ LocResult.Success(116.0, 39.0, 1_000) }, dao,
            afterCollect = { error("network failed") })
        assertTrue(recorder.collectOnce("t1"))
        assertEquals(1, dao.totalInserted)
    }

    @Test fun `stored point callback receives the durable point`() = runTest {
        val dao = FakePendingPointDao()
        var stored: LocResult.Success? = null
        val location = LocResult.Success(116.0, 39.9, 1_000, 6f)
        val recorder = Recorder({ location }, dao, onPointStored = { stored = it })
        assertTrue(recorder.collectOnce("t1"))
        assertEquals(location, stored)
    }

    @Test fun `stored point callback receives normalized persistence time`() = runTest {
        val dao = FakePendingPointDao()
        var stored: LocResult.Success? = null
        val recorder = Recorder({ LocResult.Success(116.0, 39.9, 0) }, dao,
            clock = { 12_345L }, onPointStored = { stored = it })

        assertTrue(recorder.collectOnce("t1"))

        assertEquals(12_345L, stored?.timeMillis)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `adaptive loop uploads stored points on the one minute batch timer`() = runTest {
        val dao = FakePendingPointDao()
        var fixIndex = 0
        var flushes = 0
        val source = object : LocationSource {
            override suspend fun singleShot(): LocResult = error("continuous stream expected")

            override fun updates(profile: LocationProfile) = flow {
                while (currentCoroutineContext().isActive) {
                    val time = testScheduler.currentTime
                    emit(LocResult.Success(
                        lon = 116.0 + fixIndex++ * 125.0 / 86_000.0,
                        lat = 39.0,
                        timeMillis = time,
                        accuracyMeters = 5f,
                        speedMps = 25f,
                    ))
                    delay(profile.intervalMs)
                }
            }
        }
        val recorder = Recorder(source, dao, clock = { testScheduler.currentTime },
            afterCollect = { flushes++ })
        val job = launch { recorder.runLoop("t1") }

        runCurrent()
        assertEquals(1, dao.totalInserted)
        advanceTimeBy(60_001L)
        runCurrent()
        assertEquals(1, flushes)
        assertTrue(dao.totalInserted in 2..4)

        job.cancelAndJoin()
    }
}
