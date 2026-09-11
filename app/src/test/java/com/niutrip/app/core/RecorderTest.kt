package com.niutrip.app.core

import com.niutrip.app.data.local.FakePendingPointDao
import com.niutrip.app.service.LocResult
import com.niutrip.app.service.Recorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderTest {
    @Test fun `automatic mode records every ten minutes`() = assertEquals(10 * 60_000L, Recorder.DEFAULT_INTERVAL_MS)
    @Test fun `first collection is immediate`() = assertEquals(0L, Recorder.nextDelay(1_000_000, null, 900_000))
    @Test fun `returns remaining interval`() = assertEquals(300_000L, Recorder.nextDelay(1_200_000, 600_000, 900_000))
    @Test fun `overdue collection is immediate`() = assertEquals(0L, Recorder.nextDelay(2_000_000, 100_000, 900_000))

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `loop paces from last collected point even after upload deletes it`() = runTest {
        val dao = FakePendingPointDao()
        val clock = { testScheduler.currentTime }
        val recorder = Recorder({ LocResult.Success(116.0, 39.9, testScheduler.currentTime) }, dao, clock,
            intervalMs = Recorder.DEFAULT_INTERVAL_MS, afterCollect = { dao.rows.clear() })  // 模拟上传成功后清空本地
        val job = launch { recorder.runLoop("t1") }
        advanceTimeBy(5_000)
        assertEquals(1, dao.totalInserted)  // 只有首点，上传删除后不会连环狂采
        advanceTimeBy(10 * 60_000)
        assertEquals(2, dao.totalInserted)  // 满 10 分钟才采第二个
        job.cancel()
    }
}
