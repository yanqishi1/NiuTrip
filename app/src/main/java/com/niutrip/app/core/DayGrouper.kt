package com.niutrip.app.core

import java.time.LocalDate
import java.time.LocalDateTime

data class PointLite(
    val id: String,
    val time: LocalDateTime,
    val lon: Double,
    val lat: Double,
    val isCheckin: Boolean,
    val name: String? = null,
    val desc: String? = null,
    val images: List<String> = emptyList(),
)

data class DayGroup(val date: LocalDate, val points: List<PointLite>)

object DayGrouper {
    fun group(points: List<PointLite>): List<DayGroup> = points
        .sortedBy(PointLite::time)
        .groupBy { it.time.toLocalDate() }
        .toSortedMap()
        .map { (date, values) -> DayGroup(date, values) }
}
