package com.niutrip.app.data.remote

import com.niutrip.app.core.PointLite
import com.niutrip.app.core.toLocalDateTimeOrNull
import kotlinx.serialization.Serializable

@Serializable data class UserDto(
    val user_id: String,
    val username: String,
    val phone: String? = null,
    val email: String? = null,
    val avata_url: String? = null,
)
@Serializable data class LoginIn(val identifier: String, val password: String)
@Serializable data class RegisterIn(
    val username: String,
    val password: String,
    val phone: String? = null,
    val email: String? = null,
)
@Serializable data class LoginOut(val token: String, val user: UserDto)
@Serializable data class ProfileUpdateIn(val username: String? = null, val avata_url: String? = null)
@Serializable data class PasswordIn(val old_password: String, val new_password: String)
@Serializable data class BindingsIn(val password: String, val phone: String? = null, val email: String? = null)
@Serializable data class DetailOut(val detail: String = "")
@Serializable data class UploadOut(val url: String)

@Serializable data class TrackCreateIn(val track_name: String, val track_record_mode: String)
@Serializable data class TrackPatchIn(
    val track_name: String? = null,
    val track_img_url: String? = null,
    val track_status: String? = null,
)
@Serializable data class TrackDto(
    val track_id: String,
    val track_name: String,
    val track_img_url: String = "",
    val track_start_time: String? = null,
    val track_end_time: String? = null,
    val track_record_mode: String,
    val track_status: String,
    val share_mode: String = "PRIVATE",
    val point_count: Int = 0,
    val checkin_count: Int = 0,
    val view_count: Int = 0,
    val viewer_count: Int = 0,
    val sharer_username: String? = null,
)

@Serializable data class PointIn(
    val point_id: String,
    val longitude: Double,
    val latitude: Double,
    val point_name: String? = null,
    val point_desc: String? = null,
    val point_img_url: List<String> = emptyList(),
    val point_time: String,
    val point_source: String,
)
@Serializable data class PointsIn(val points: List<PointIn>)
@Serializable data class PostPointsOut(val count: Int)
@Serializable data class PointPatchIn(
    val longitude: Double,
    val latitude: Double,
    val point_name: String? = null,
    val point_desc: String? = null,
    val point_img_url: List<String> = emptyList(),
)
@Serializable data class PointDto(
    val point_id: String,
    val point_longitude: Double? = null,
    val point_latitude: Double? = null,
    val longitude: Double? = null,
    val latitude: Double? = null,
    val point_name: String? = null,
    val point_desc: String? = null,
    val point_img_url: List<String> = emptyList(),
    val point_time: String,
    val point_source: String,
    val report_time: String? = null,
) {
    fun toLite(): PointLite? {
        val parsed = point_time.toLocalDateTimeOrNull() ?: return null
        return PointLite(point_id, parsed, longitude ?: point_longitude ?: return null,
            latitude ?: point_latitude ?: return null, point_source == "MANUAL", point_name, point_desc, point_img_url)
    }
}
@Serializable data class PointsPage(
    val count: Int,
    val next: String? = null,
    val previous: String? = null,
    val results: List<PointDto>,
)
@Serializable data class ShareIn(val share_mode: String)
@Serializable data class ShareOut(val share_mode: String, val share_url: String? = null)
@Serializable data class ShareInspectOut(val is_owner: Boolean, val is_saved: Boolean = false)
@Serializable data class ShareTrackDto(
    val track_id: String,
    val track_name: String,
    val track_img_url: String = "",
    val start_time: String? = null,
    val end_time: String? = null,
    val record_mode: String,
    val owner_username: String,
    val share_mode: String,
    val track_status: String? = null,
)
@Serializable data class ShareStatsDto(
    val point_count: Int,
    val checkin_count: Int,
    val days: Int,
    val view_count: Int = 0,
    val viewer_count: Int = 0,
)
@Serializable data class ShareDayDto(val date: String, val points: List<PointDto>)
@Serializable data class ShareDataDto(
    val track: ShareTrackDto,
    val stats: ShareStatsDto,
    val latest: PointDto? = null,
    val days: List<ShareDayDto> = emptyList(),
)
