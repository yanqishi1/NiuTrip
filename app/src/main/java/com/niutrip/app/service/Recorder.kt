package com.niutrip.app.service

import com.niutrip.app.core.businessId
import com.niutrip.app.core.RouteGeometry
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
    private var lastAccepted: LocResult.Success? = null

    suspend fun collectOnce(trackId: String): Boolean {
        val location = runCatching { source.singleShot() }.getOrElse { return false }
        return when (location) {
            is LocResult.Failure -> false
            is LocResult.Success -> {
                if (!isUsable(location)) false
                else {
                    val previous = lastAccepted
                    if (previous != null && isStationaryDrift(previous, location)) true
                    else {
                        lastAccepted = location
                        val time = if (location.timeMillis > 0) location.timeMillis else clock()
                        dao.insert(PendingPointEntity(trackId = trackId, pointId = businessId(), lon = location.lon,
                            lat = location.lat, time = time, source = "AUTO", createdAt = clock()))
                        runCatching { afterCollect() }
                        true
                    }
                }
            }
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
        const val MIN_DRIFT_RADIUS_METERS = 25.0
        const val MAX_DRIFT_RADIUS_METERS = 60.0
        const val MAX_ACCEPTED_ACCURACY_METERS = 100f

        fun nextDelay(nowMs: Long, lastPointMs: Long?, intervalMs: Long = DEFAULT_INTERVAL_MS): Long =
            if (lastPointMs == null) 0L else (lastPointMs + intervalMs - nowMs).coerceAtLeast(0L)

        fun isUsable(location: LocResult.Success): Boolean =
            location.lat in -90.0..90.0 && location.lon in -180.0..180.0 &&
                (!location.accuracyMeters.isFinite() || location.accuracyMeters <= 0f || location.accuracyMeters <= MAX_ACCEPTED_ACCURACY_METERS)

        fun isStationaryDrift(previous: LocResult.Success, current: LocResult.Success): Boolean {
            val accuracyRadius = listOf(previous.accuracyMeters, current.accuracyMeters)
                .filter { it.isFinite() && it > 0f }.maxOrNull()?.toDouble() ?: MIN_DRIFT_RADIUS_METERS
            val radius = accuracyRadius.coerceIn(MIN_DRIFT_RADIUS_METERS, MAX_DRIFT_RADIUS_METERS)
            return RouteGeometry.distanceMeters(previous.lat, previous.lon, current.lat, current.lon) < radius
        }
    }
}
