package com.niutrip.app.core

import kotlin.math.*

data class RouteEndpoints(val start: PointLite, val end: PointLite)

object RouteGeometry {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun endpoints(days: List<DayGroup>): RouteEndpoints? {
        val points = days.flatMap(DayGroup::points).sortedBy(PointLite::time)
        return points.firstOrNull()?.let { RouteEndpoints(it, points.last()) }
    }

    fun suppressStationaryDrift(points: List<PointLite>, radiusMeters: Double = 25.0): List<PointLite> {
        if (points.size <= 2) return points
        val ordered = points.sortedBy(PointLite::time)
        val kept = mutableListOf(ordered.first())
        ordered.drop(1).dropLast(1).forEach { point ->
            if (distanceMeters(kept.last().lat, kept.last().lon, point.lat, point.lon) >= radiusMeters) kept += point
        }
        if (kept.last().id != ordered.last().id) kept += ordered.last()
        return kept
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
