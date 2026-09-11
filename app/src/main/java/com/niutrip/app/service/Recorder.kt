package com.niutrip.app.service

import com.niutrip.app.core.businessId
import com.niutrip.app.data.local.PendingPointDao
import com.niutrip.app.data.local.PendingPointEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class Recorder(
    private val source: LocationSource,
    private val dao: PendingPointDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val afterCollect: suspend () -> Unit = {},
) {
    suspend fun collectOnce(trackId: String): Boolean = when (val location = source.singleShot()) {
        is LocResult.Failure -> false
        is LocResult.Success -> {
            val time = if (location.timeMillis > 0) location.timeMillis else clock()
            dao.insert(PendingPointEntity(trackId = trackId, pointId = businessId(), lon = location.lon,
                lat = location.lat, time = time, source = "AUTO", createdAt = clock()))
            afterCollect(); true
        }
    }

    suspend fun runLoop(trackId: String) {
        // 节奏以「上次采集时间」为准（内存），启动时从本地积压补基线；
        // 不能每轮都查 pending 表——上传成功会把点删掉，查出来是 null 就会 delay(0) 连环狂采
        var last: Long? = dao.lastTime(trackId)
        while (currentCoroutineContext().isActive) {
            delay(nextDelay(clock(), last, intervalMs))
            if (collectOnce(trackId)) last = clock() else delay(RETRY_DELAY_MS)
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 10 * 60_000L
        const val RETRY_DELAY_MS = 60_000L
        fun nextDelay(nowMs: Long, lastPointMs: Long?, intervalMs: Long = DEFAULT_INTERVAL_MS): Long =
            if (lastPointMs == null) 0L else (lastPointMs + intervalMs - nowMs).coerceAtLeast(0L)
    }
}
