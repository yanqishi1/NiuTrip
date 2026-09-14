package com.niutrip.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.niutrip.app.data.remote.TrackDto

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val trackId: String,
    val name: String,
    val imageUrl: String,
    val startTime: String?,
    val endTime: String?,
    val recordMode: String,
    val status: String,
    val shareMode: String,
    val pointCount: Int,
    val checkinCount: Int,
    val viewCount: Int,
    val viewerCount: Int,
    val sharerUsername: String? = null,
) {
    fun toDto() = TrackDto(trackId, name, imageUrl, startTime, endTime, recordMode,
        status, shareMode, pointCount, checkinCount, viewCount, viewerCount,
        sharerUsername)
    companion object {
        fun from(dto: TrackDto) = TrackEntity(dto.track_id, dto.track_name, dto.track_img_url,
            dto.track_start_time, dto.track_end_time, dto.track_record_mode, dto.track_status,
            dto.share_mode, dto.point_count, dto.checkin_count, dto.view_count,
            dto.viewer_count, dto.sharer_username)
    }
}
