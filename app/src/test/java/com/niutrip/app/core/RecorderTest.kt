package com.niutrip.app.core

import com.niutrip.app.data.local.FakePendingPointDao
import com.niutrip.app.service.LocResult
import com.niutrip.app.service.Recorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTest {
    @Test fun `automatic mode records every ten minutes`() = assertEquals(10 * 60_000L, Recorder.DEFAULT_INTERVAL_MS)
    @Test fun `first collection is immediate`() = assertEquals(0L, Recorder.nextDelay(1_000_000, null, 900_000))
    @Test fun `returns remaining interval`() = assertEquals(300_000L, Recorder.nextDelay(1_200_000, 600_000, 900_000))
    @Test fun `overdue collection is immediate`() = assertEquals(0L, Recorder.nextDelay(2_000_000, 100_000, 900_000))

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `loop paces from last collected point even after upload deletes it`() = runTest {
        val dao = FakePendingPointDao()
        val clock = { testScheduler.currentTime }
        val recorder = Recorder({
            val movement = testScheduler.currentTime / Recorder.DEFAULT_INTERVAL_MS.toDouble() * 0.001
            LocResult.Success(116.0 + movement, 39.9 + movement, testScheduler.currentTime)
        }, dao, clock,
            intervalMs = Recorder.DEFAULT_INTERVAL_MS, afterCollect = { dao.rows.clear() })  // 模拟上传成功后清空本地
        val job = launch { recorder.runLoop("t1") }
        advanceTimeBy(5_000)
        assertEquals(1, dao.totalInserted)  // 只有首点，上传删除后不会连环狂采
        advanceTimeBy(10 * 60_000)
        assertEquals(2, dao.totalInserted)  // 满 10 分钟才采第二个
        job.cancel()
    }
}
