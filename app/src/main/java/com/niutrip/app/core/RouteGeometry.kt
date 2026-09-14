package com.niutrip.app.core

import kotlin.math.*

data class RouteEndpoints(val start: PointLite, val end: PointLite)

object RouteGeometry {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun endpoints(days: List<DayGroup>): RouteEndpoints? {
        val points = days.flatMap(DayGroup::points).sortedBy(PointLite::time)
        return points.firstOrNull()?.let { RouteEndpoints(it, points.last()) }
    }

    fun orderedPath(points: List<PointLite>): List<PointLite> = points.sortedBy(PointLite::time)

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun totalDistanceMeters(points: List<PointLite>): Double = orderedPath(points)
        .zipWithNext { first, second ->
            distanceMeters(first.lat, first.lon, second.lat, second.lon)
        }
        .sum()
}
