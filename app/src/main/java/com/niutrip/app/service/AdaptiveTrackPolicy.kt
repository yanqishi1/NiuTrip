package com.niutrip.app.service

import com.niutrip.app.core.RouteGeometry
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class MotionState { WARMUP, WALKING, CYCLING, DRIVING, HIGHWAY, STATIONARY }

enum class LocationPowerMode { HIGH_ACCURACY, BALANCED }

data class LocationProfile(
    val intervalMs: Long,
    val powerMode: LocationPowerMode,
)

sealed interface TrackDecision {
    data class Store(val location: LocResult.Success) : TrackDecision
    data object Ignore : TrackDecision
}

/**
 * Converts a dense location stream into a compact route. It keeps corners and movement
 * boundaries while discarding straight-line and stationary noise.
 */
class AdaptiveTrackPolicy(
    initialPoint: LocResult.Success? = null,
    initialState: MotionState = MotionState.WARMUP,
) {
    private var startedAtMs: Long? = null
    private var lastObserved: LocResult.Success? = initialPoint
    private var lastStored: LocResult.Success? = initialPoint
    private var pending: LocResult.Success? = null
    private var uncertainAccuracy: LocResult.Success? = null
    private val recentSpeeds = ArrayDeque<Double>()

    private var stillAnchor: LocResult.Success? = initialPoint
    private var stillSinceMs: Long? = null
    private var nextAccuracyFallbackMs: Long? = null
    private var reacquiring = false
    private var reacquireUntilMs: Long? = null
    private var departureCandidate: LocResult.Success? = null
    private var departureConfirmations = 0
    private var candidateState: MotionState? = null
    private var candidateStateCount = 0

    var state: MotionState = if (initialPoint == null) MotionState.WARMUP else initialState
        private set

    val profile: LocationProfile
        get() = when {
            state == MotionState.STATIONARY && !reacquiring ->
                LocationProfile(STATIONARY_INTERVAL_MS, LocationPowerMode.BALANCED)
            state == MotionState.WARMUP || reacquiring || state == MotionState.HIGHWAY ->
                LocationProfile(FAST_INTERVAL_MS, LocationPowerMode.HIGH_ACCURACY)
            else -> LocationProfile(MOVING_INTERVAL_MS, LocationPowerMode.HIGH_ACCURACY)
        }

    fun onMotionDetected(): Boolean {
        if (state != MotionState.STATIONARY || reacquiring) return false
        reacquiring = true
        reacquireUntilMs = null
        departureCandidate = null
        departureConfirmations = 0
        return true
    }

    fun onLocation(location: LocResult.Success): TrackDecision {
        if (!isUsable(location)) return TrackDecision.Ignore
        val now = sampleTime(location)
        if (startedAtMs == null) startedAtMs = now

        if (location.accuracyMeters.isFinite() && location.accuracyMeters > GOOD_ACCURACY_METERS) {
            val previous = uncertainAccuracy
            uncertainAccuracy = location
            if (previous == null || !mutuallyConsistent(previous, location)) return TrackDecision.Ignore
            uncertainAccuracy = null
        } else {
            uncertainAccuracy = null
        }

        val previous = lastObserved
        if (previous != null && impliedSpeed(previous, location) > MAX_PLAUSIBLE_SPEED_MPS) {
            return TrackDecision.Ignore
        }

        val segmentSpeed = previous?.let { impliedSpeed(it, location) } ?: 0.0
        val reportedSpeed = location.speedMps.takeIf { it.isFinite() && it >= 0f }?.toDouble() ?: 0.0
        addSpeed(max(segmentSpeed, reportedSpeed))
        val speed = medianSpeed()
        lastObserved = location

        if (state == MotionState.STATIONARY) return handleStationary(location, speed, now)

        updateStationaryCandidate(location, speed, now)?.let { return it }
        updateMovingState(speed, now)
        return selectRoutePoint(location, speed)
    }

    fun finish(): LocResult.Success? {
        val candidate = pending ?: return null
        val stored = lastStored
        pending = null
        if (stored == null || meaningfulDistance(stored, candidate)) {
            lastStored = candidate
            return candidate
        }
        return null
    }

    private fun handleStationary(location: LocResult.Success, speed: Double, now: Long): TrackDecision {
        if (nextAccuracyFallbackMs == null) nextAccuracyFallbackMs = now + ACCURACY_FALLBACK_MS
        val fallbackAt = nextAccuracyFallbackMs
        if (!reacquiring && fallbackAt != null && now >= fallbackAt) {
            reacquiring = true
            reacquireUntilMs = now + REACQUIRE_WINDOW_MS
            nextAccuracyFallbackMs = now + ACCURACY_FALLBACK_MS
        }
        if (reacquiring && reacquireUntilMs == null) reacquireUntilMs = now + REACQUIRE_WINDOW_MS

        val anchor = stillAnchor ?: lastStored ?: location
        val moved = RouteGeometry.distanceMeters(anchor.lat, anchor.lon, location.lat, location.lon)
        val exitRadius = max(EXIT_STATIONARY_METERS, accuracyRadius(anchor, location))
        if (speed >= EXIT_STATIONARY_SPEED_MPS || moved >= exitRadius) {
            if (!reacquiring) {
                reacquiring = true
                reacquireUntilMs = now + REACQUIRE_WINDOW_MS
            }
            if (departureCandidate == null) departureCandidate = location
            departureConfirmations++
        } else {
            departureCandidate = null
            departureConfirmations = 0
            if (reacquiring && now >= (reacquireUntilMs ?: now)) {
                reacquiring = false
                reacquireUntilMs = null
            }
            return TrackDecision.Ignore
        }

        if (departureConfirmations < DEPARTURE_CONFIRMATIONS) return TrackDecision.Ignore

        val departure = departureCandidate ?: location
        state = classify(speed.coerceAtLeast(EXIT_STATIONARY_SPEED_MPS))
        reacquiring = false
        reacquireUntilMs = null
        stillSinceMs = null
        stillAnchor = null
        departureCandidate = null
        departureConfirmations = 0
        recentSpeeds.clear()
        pending = if (departure === location) null else location
        lastStored = departure
        return TrackDecision.Store(departure)
    }

    private fun updateStationaryCandidate(
        location: LocResult.Success,
        speed: Double,
        now: Long,
    ): TrackDecision? {
        val anchor = stillAnchor
        val withinRadius = anchor == null || RouteGeometry.distanceMeters(
            anchor.lat, anchor.lon, location.lat, location.lon,
        ) <= max(STATIONARY_RADIUS_METERS, accuracyRadius(anchor, location))

        if (speed < STATIONARY_CANDIDATE_SPEED_MPS && withinRadius) {
            if (stillAnchor == null) stillAnchor = location
            if (stillSinceMs == null) stillSinceMs = now
            if (now - stillSinceMs!! >= STATIONARY_CONFIRM_MS) {
                state = MotionState.STATIONARY
                reacquiring = false
                nextAccuracyFallbackMs = now + ACCURACY_FALLBACK_MS
                val arrival = pending
                pending = null
                if (arrival != null && (lastStored == null || meaningfulDistance(lastStored!!, arrival))) {
                    lastStored = arrival
                    return TrackDecision.Store(arrival)
                }
                return TrackDecision.Ignore
            }
        } else {
            stillAnchor = location
            stillSinceMs = null
        }
        return null
    }

    private fun updateMovingState(speed: Double, now: Long) {
        if (now - (startedAtMs ?: now) < WARMUP_MS) return
        val classified = classify(speed)
        if (classified == candidateState) candidateStateCount++ else {
            candidateState = classified
            candidateStateCount = 1
        }
        if (candidateStateCount >= STATE_CONFIRMATIONS) state = classified
    }

    private fun selectRoutePoint(location: LocResult.Success, speed: Double): TrackDecision {
        val stored = lastStored
        if (stored == null) {
            lastStored = location
            pending = null
            return TrackDecision.Store(location)
        }

        val previousPending = pending
        if (previousPending != null) {
            val config = configFor(speed)
            if (isCorner(stored, previousPending, location, config)) {
                lastStored = previousPending
                pending = location
                return TrackDecision.Store(previousPending)
            }
        }

        val config = configFor(speed)
        val distance = RouteGeometry.distanceMeters(stored.lat, stored.lon, location.lat, location.lon)
        val elapsed = elapsedBetween(stored, location)
        if (distance >= config.maxDistanceMeters ||
            (elapsed >= config.maxIntervalMs && meaningfulDistance(stored, location))) {
            lastStored = location
            pending = null
            return TrackDecision.Store(location)
        }

        pending = location
        return TrackDecision.Ignore
    }

    private fun isCorner(
        anchor: LocResult.Success,
        middle: LocResult.Success,
        current: LocResult.Success,
        config: RouteConfig,
    ): Boolean {
        if (!meaningfulDistance(anchor, middle) || !meaningfulDistance(middle, current)) return false
        val turn = angleDifference(bearing(anchor, middle), bearing(middle, current))
        val deviation = perpendicularDistanceMeters(middle, anchor, current)
        return turn >= config.turnDegrees || deviation >= config.corridorMeters
    }

    private fun isUsable(location: LocResult.Success): Boolean =
        location.lat in -90.0..90.0 && location.lon in -180.0..180.0 &&
            (!location.accuracyMeters.isFinite() || location.accuracyMeters <= 0f ||
                location.accuracyMeters <= MAX_ACCURACY_METERS) &&
            (!location.speedMps.isFinite() || location.speedMps < 0f ||
                location.speedMps.toDouble() <= MAX_PLAUSIBLE_SPEED_MPS)

    private fun mutuallyConsistent(first: LocResult.Success, second: LocResult.Success): Boolean {
        val elapsedSeconds = elapsedBetween(first, second).coerceAtLeast(1L) / 1_000.0
        val distance = RouteGeometry.distanceMeters(first.lat, first.lon, second.lat, second.lon)
        val allowance = MAX_PLAUSIBLE_SPEED_MPS * elapsedSeconds + accuracyRadius(first, second)
        return distance <= allowance
    }

    private fun meaningfulDistance(first: LocResult.Success, second: LocResult.Success): Boolean =
        RouteGeometry.distanceMeters(first.lat, first.lon, second.lat, second.lon) >=
            max(MIN_MEANINGFUL_METERS, accuracyRadius(first, second))

    private fun addSpeed(speed: Double) {
        recentSpeeds.addLast(speed.coerceIn(0.0, MAX_PLAUSIBLE_SPEED_MPS))
        while (recentSpeeds.size > SPEED_WINDOW_SIZE) recentSpeeds.removeFirst()
    }

    private fun medianSpeed(): Double {
        if (recentSpeeds.isEmpty()) return 0.0
        val sorted = recentSpeeds.sorted()
        return sorted[sorted.size / 2]
    }

    private fun classify(speed: Double): MotionState = when {
        speed < WALKING_MAX_MPS -> MotionState.WALKING
        speed < CYCLING_MAX_MPS -> MotionState.CYCLING
        speed < DRIVING_MAX_MPS -> MotionState.DRIVING
        else -> MotionState.HIGHWAY
    }

    private fun configFor(speed: Double): RouteConfig = when {
        speed < WALKING_MAX_MPS -> RouteConfig(80.0, 3 * 60_000L, 35.0, 25.0)
        speed < CYCLING_MAX_MPS -> RouteConfig(200.0, 2 * 60_000L, 30.0, 35.0)
        speed < DRIVING_MAX_MPS -> RouteConfig(600.0, 90_000L, 25.0, 50.0)
        else -> RouteConfig(1_500.0, 60_000L, 20.0, 60.0)
    }

    private data class RouteConfig(
        val maxDistanceMeters: Double,
        val maxIntervalMs: Long,
        val turnDegrees: Double,
        val corridorMeters: Double,
    )

    companion object {
        const val FAST_INTERVAL_MS = 10_000L
        const val MOVING_INTERVAL_MS = 15_000L
        const val STATIONARY_INTERVAL_MS = 2 * 60_000L
        const val SYNC_INTERVAL_MS = 60_000L
        const val SYNC_BATCH_SIZE = 20

        private const val WARMUP_MS = 30_000L
        private const val STATIONARY_CONFIRM_MS = 3 * 60_000L
        private const val ACCURACY_FALLBACK_MS = 15 * 60_000L
        private const val REACQUIRE_WINDOW_MS = 30_000L
        private const val GOOD_ACCURACY_METERS = 50f
        private const val MAX_ACCURACY_METERS = 100f
        private const val MIN_MEANINGFUL_METERS = 15.0
        private const val STATIONARY_RADIUS_METERS = 40.0
        private const val EXIT_STATIONARY_METERS = 80.0
        private const val STATIONARY_CANDIDATE_SPEED_MPS = 1.5
        private const val EXIT_STATIONARY_SPEED_MPS = 1.5
        private const val MAX_PLAUSIBLE_SPEED_MPS = 80.0
        private const val WALKING_MAX_MPS = 2.5
        private const val CYCLING_MAX_MPS = 8.0
        private const val DRIVING_MAX_MPS = 20.0
        private const val SPEED_WINDOW_SIZE = 5
        private const val STATE_CONFIRMATIONS = 2
        private const val DEPARTURE_CONFIRMATIONS = 2

        private fun sampleTime(location: LocResult.Success): Long =
            location.elapsedRealtimeMillis.takeIf { it > 0L } ?: location.timeMillis

        private fun impliedSpeed(first: LocResult.Success, second: LocResult.Success): Double {
            val seconds = elapsedBetween(first, second) / 1_000.0
            val distance = RouteGeometry.distanceMeters(first.lat, first.lon, second.lat, second.lon)
            if (seconds <= 0.0) {
                return if (distance <= accuracyRadius(first, second)) 0.0 else Double.POSITIVE_INFINITY
            }
            return distance / seconds
        }

        private fun elapsedBetween(first: LocResult.Success, second: LocResult.Success): Long {
            val firstElapsed = first.elapsedRealtimeMillis
            val secondElapsed = second.elapsedRealtimeMillis
            if (firstElapsed > 0L && secondElapsed >= firstElapsed) return secondElapsed - firstElapsed
            return (second.timeMillis - first.timeMillis).coerceAtLeast(0L)
        }

        private fun accuracyRadius(first: LocResult.Success, second: LocResult.Success): Double =
            listOf(first.accuracyMeters, second.accuracyMeters)
                .filter { it.isFinite() && it > 0f }
                .maxOrNull()?.toDouble()?.coerceAtMost(MAX_ACCURACY_METERS.toDouble()) ?: MIN_MEANINGFUL_METERS

        private fun bearing(first: LocResult.Success, second: LocResult.Success): Double {
            val lat1 = Math.toRadians(first.lat)
            val lat2 = Math.toRadians(second.lat)
            val deltaLon = Math.toRadians(second.lon - first.lon)
            val y = sin(deltaLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        private fun angleDifference(first: Double, second: Double): Double {
            val raw = abs(first - second) % 360.0
            return min(raw, 360.0 - raw)
        }

        private fun perpendicularDistanceMeters(
            point: LocResult.Success,
            start: LocResult.Success,
            end: LocResult.Success,
        ): Double {
            val referenceLat = Math.toRadians((start.lat + end.lat + point.lat) / 3.0)
            fun x(lon: Double) = Math.toRadians(lon) * EARTH_RADIUS_METERS * cos(referenceLat)
            fun y(lat: Double) = Math.toRadians(lat) * EARTH_RADIUS_METERS
            val sx = x(start.lon); val sy = y(start.lat)
            val ex = x(end.lon); val ey = y(end.lat)
            val px = x(point.lon); val py = y(point.lat)
            val dx = ex - sx; val dy = ey - sy
            if (dx == 0.0 && dy == 0.0) return RouteGeometry.distanceMeters(
                point.lat, point.lon, start.lat, start.lon,
            )
            val ratio = (((px - sx) * dx + (py - sy) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            val closestX = sx + ratio * dx
            val closestY = sy + ratio * dy
            return kotlin.math.hypot(px - closestX, py - closestY)
        }

        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
