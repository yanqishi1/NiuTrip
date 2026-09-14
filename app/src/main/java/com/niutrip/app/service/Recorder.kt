package com.niutrip.app.service

import com.niutrip.app.core.businessId
import com.niutrip.app.core.RouteGeometry
import com.niutrip.app.data.local.PendingPointDao
import com.niutrip.app.data.local.PendingPointEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext

class Recorder(
    private val source: LocationSource,
    private val dao: PendingPointDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val afterCollect: suspend () -> Unit = {},
    private val onPointStored: (LocResult.Success) -> Unit = {},
    private val onPendingPointStored: (PendingPointEntity) -> Unit = {},
    private val onStateChanged: (MotionState) -> Unit = {},
    initialPoint: LocResult.Success? = null,
    private val initialState: MotionState = MotionState.WARMUP,
) {
    private var lastAccepted: LocResult.Success? = initialPoint

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
                        store(trackId, location)
                        runCatching { afterCollect() }
                        true
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun runLoop(trackId: String, motionEvents: Flow<Unit> = emptyFlow()) {
        val policy = AdaptiveTrackPolicy(lastAccepted, initialState)
        val profile = MutableStateFlow(policy.profile)
        var storedSinceSync = 0

        suspend fun flush() {
            runCatching { afterCollect() }
            storedSinceSync = 0
        }

        suspend fun storeAdaptive(location: LocResult.Success) {
            store(trackId, location)
            lastAccepted = location
            storedSinceSync++
            if (storedSinceSync >= AdaptiveTrackPolicy.SYNC_BATCH_SIZE) flush()
        }

        val locations = profile.flatMapLatest { selected ->
            source.updates(selected).retryWhen { _, _ ->
                delay(RETRY_DELAY_MS)
                true
            }
        }.map<LocResult, RecorderEvent> { RecorderEvent.Location(it) }
        val motion = motionEvents.map<Unit, RecorderEvent> { RecorderEvent.Motion }
        val sync = flow<RecorderEvent> {
            while (true) {
                delay(AdaptiveTrackPolicy.SYNC_INTERVAL_MS)
                emit(RecorderEvent.Sync)
            }
        }

        try {
            merge(locations, motion, sync).collect { event ->
                when (event) {
                    is RecorderEvent.Location -> if (event.result is LocResult.Success) {
                        val previousState = policy.state
                        when (val decision = policy.onLocation(event.result)) {
                            is TrackDecision.Store -> storeAdaptive(decision.location)
                            TrackDecision.Ignore -> Unit
                        }
                        if (policy.state != previousState) onStateChanged(policy.state)
                        if (profile.value != policy.profile) profile.value = policy.profile
                    }
                    RecorderEvent.Motion -> if (policy.onMotionDetected()) profile.value = policy.profile
                    RecorderEvent.Sync -> flush()
                }
            }
        } finally {
            withContext(NonCancellable) {
                policy.finish()?.let { storeAdaptive(it) }
                flush()
            }
        }
    }

    private suspend fun store(trackId: String, location: LocResult.Success) {
        val time = if (location.timeMillis > 0) location.timeMillis else clock()
        val point = PendingPointEntity(trackId = trackId, pointId = businessId(), lon = location.lon,
            lat = location.lat, time = time, source = "AUTO", createdAt = clock())
        dao.insert(point)
        onPendingPointStored(point)
        onPointStored(if (location.timeMillis == time) location else location.copy(timeMillis = time))
    }

    companion object {
        const val RETRY_DELAY_MS = 60_000L
        const val MIN_DRIFT_RADIUS_METERS = 25.0
        const val MAX_DRIFT_RADIUS_METERS = 60.0
        const val MAX_ACCEPTED_ACCURACY_METERS = 100f

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

    private sealed interface RecorderEvent {
        data class Location(val result: LocResult) : RecorderEvent
        data object Motion : RecorderEvent
        data object Sync : RecorderEvent
    }
}
