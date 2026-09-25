package com.niutrip.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.niutrip.app.data.remote.PointDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "cloud_points", indices = [Index(value = ["trackId", "pointTime"])])
data class CloudPointEntity(
    @PrimaryKey val pointId: String,
    val trackId: String,
    val longitude: Double,
    val latitude: Double,
    val name: String?,
    val description: String?,
    val imagesJson: String,
    val pointTime: String,
    val source: String,
    val reportTime: String?,
) {
    fun toDto() = PointDto(
        point_id = pointId,
        longitude = longitude,
        latitude = latitude,
        point_name = name,
        point_desc = description,
        point_img_url = runCatching { Json.decodeFromString<List<String>>(imagesJson) }
            .getOrDefault(emptyList()),
        point_time = pointTime,
        point_source = source,
        report_time = reportTime,
    )

    companion object {
        fun from(trackId: String, point: PointDto) = CloudPointEntity(
            pointId = point.point_id,
            trackId = trackId,
            longitude = point.longitude ?: point.point_longitude
                ?: error("云端轨迹点缺少经度"),
            latitude = point.latitude ?: point.point_latitude
                ?: error("云端轨迹点缺少纬度"),
            name = point.point_name,
            description = point.point_desc,
            imagesJson = Json.encodeToString(point.point_img_url),
            pointTime = point.point_time,
            source = point.point_source,
            reportTime = point.report_time,
        )
    }
}

@Entity(tableName = "point_sync_state")
data class PointSyncStateEntity(
    @PrimaryKey val trackId: String,
    val cursor: String,
    val baselineComplete: Boolean,
    val accountKey: String,
)
