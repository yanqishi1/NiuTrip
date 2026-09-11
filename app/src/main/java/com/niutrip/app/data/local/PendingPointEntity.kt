package com.niutrip.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "pending_points", indices = [Index(value = ["pointId"], unique = true)])
data class PendingPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val pointId: String,
    val lon: Double,
    val lat: Double,
    val name: String? = null,
    val desc: String? = null,
    val imgs: String = "[]",
    val time: Long,
    val source: String,
    val createdAt: Long,
)
