package com.niutrip.app.core

import com.niutrip.app.service.AdaptiveTrackPolicy
import com.niutrip.app.service.LocResult
import com.niutrip.app.service.LocationPowerMode
import com.niutrip.app.service.MotionState
import com.niutrip.app.service.TrackDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveTrackPolicyTest {
    @Test fun `first reliable point is stored immediately`() {
        val policy = AdaptiveTrackPolicy()
        assertTrue(policy.onLocation(point(0.0, 0L)) is TrackDecision.Store)
    }

    @Test fun `stationary fixes are not stored and switch to balanced power`() {
        val policy = AdaptiveTrackPolicy()
        var stores = 0
        for (second in 0..180 step 10) {
            if (policy.onLocation(point(0.0, second * 1_000L, speed = 0f)) is TrackDecision.Store) stores++
        }
        assertEquals(1, stores)
        assertEquals(MotionState.STATIONARY, policy.state)
        assertEquals(LocationPowerMode.BALANCED, policy.profile.powerMode)
        assertEquals(AdaptiveTrackPolicy.STATIONARY_INTERVAL_MS, policy.profile.intervalMs)
    }

    @Test fun `persisted stationary state resumes directly in balanced power`() {
        val previous = point(0.0, 1_000L)
        val policy = AdaptiveTrackPolicy(previous, MotionState.STATIONARY)
        assertEquals(LocationPowerMode.BALANCED, policy.profile.powerMode)
        assertTrue(policy.onLocation(point(2.0, 121_000L, speed = 0f)) is TrackDecision.Ignore)
        assertEquals(MotionState.STATIONARY, policy.state)
    }

    @Test fun `significant motion wakes stationary tracking and two fixes confirm departure`() {
        val policy = AdaptiveTrackPolicy()
        for (second in 0..180 step 10) policy.onLocation(point(0.0, second * 1_000L, speed = 0f))

        assertTrue(policy.onMotionDetected())
        assertEquals(LocationPowerMode.HIGH_ACCURACY, policy.profile.powerMode)
        assertTrue(policy.onLocation(point(100.0, 185_000L, speed = 3f)) is TrackDecision.Ignore)
        assertTrue(policy.onLocation(point(120.0, 190_000L, speed = 3f)) is TrackDecision.Store)
        assertFalse(policy.state == MotionState.STATIONARY)
    }

    @Test fun `low power displacement immediately requests a high accuracy confirmation`() {
        val policy = AdaptiveTrackPolicy(point(0.0, 1_000L), MotionState.STATIONARY)
        assertTrue(policy.onLocation(point(100.0, 121_000L, speed = 2f)) is TrackDecision.Ignore)
        assertEquals(LocationPowerMode.HIGH_ACCURACY, policy.profile.powerMode)
        assertTrue(policy.onLocation(point(120.0, 126_000L, speed = 2f)) is TrackDecision.Store)
    }

    @Test fun `highway points are bounded by time but not stored on every fix`() {
        val policy = AdaptiveTrackPolicy()
        var stores = 0
        for (index in 0..12) {
            val decision = policy.onLocation(point(index * 125.0, index * 5_000L, speed = 25f))
            if (decision is TrackDecision.Store) stores++
        }
        assertEquals(2, stores)
        assertEquals(MotionState.HIGHWAY, policy.state)
        assertEquals(AdaptiveTrackPolicy.FAST_INTERVAL_MS, policy.profile.intervalMs)
    }

    @Test fun `corner keeps the point before direction changes`() {
        val policy = AdaptiveTrackPolicy()
        policy.onLocation(point(0.0, 0L))
        val corner = point(50.0, 10_000L)
        assertTrue(policy.onLocation(corner) is TrackDecision.Ignore)
        val afterCorner = pointXY(50.0, 50.0, 20_000L)
        val decision = policy.onLocation(afterCorner)
        assertTrue(decision is TrackDecision.Store)
        assertEquals(corner, (decision as TrackDecision.Store).location)
    }

    @Test fun `inaccurate and physically impossible jumps are ignored`() {
        val policy = AdaptiveTrackPolicy()
        policy.onLocation(point(0.0, 0L))
        assertTrue(policy.onLocation(point(10.0, 10_000L, accuracy = 150f)) is TrackDecision.Ignore)
        assertTrue(policy.onLocation(point(100_000.0, 10_000L, accuracy = 5f)) is TrackDecision.Ignore)
        assertEquals(null, policy.finish())
    }

    @Test fun `different point with stale timestamp is ignored`() {
        val policy = AdaptiveTrackPolicy()
        policy.onLocation(point(0.0, 10_000L))
        assertTrue(policy.onLocation(point(100.0, 10_000L)) is TrackDecision.Ignore)
        assertEquals(null, policy.finish())
    }

    @Test fun `medium accuracy needs two mutually consistent fixes`() {
        val policy = AdaptiveTrackPolicy()
        policy.onLocation(point(0.0, 0L))
        assertTrue(policy.onLocation(point(100.0, 10_000L, accuracy = 70f)) is TrackDecision.Ignore)
        val confirmed = policy.onLocation(point(110.0, 20_000L, accuracy = 70f))
        assertTrue(confirmed is TrackDecision.Ignore)
        assertEquals(point(110.0, 20_000L, accuracy = 70f), policy.finish())
    }

    private fun point(
        eastMeters: Double,
        timeMs: Long,
        accuracy: Float = 5f,
        speed: Float = Float.NaN,
    ) = pointXY(eastMeters, 0.0, timeMs, accuracy, speed)

    private fun pointXY(
        eastMeters: Double,
        northMeters: Double,
        timeMs: Long,
        accuracy: Float = 5f,
        speed: Float = Float.NaN,
    ) = LocResult.Success(
        lon = 116.0 + eastMeters / METERS_PER_LONGITUDE_DEGREE,
        lat = 39.0 + northMeters / METERS_PER_LATITUDE_DEGREE,
        timeMillis = timeMs,
        accuracyMeters = accuracy,
        speedMps = speed,
    )

    companion object {
        private const val METERS_PER_LONGITUDE_DEGREE = 86_000.0
        private const val METERS_PER_LATITUDE_DEGREE = 111_000.0
    }
}
