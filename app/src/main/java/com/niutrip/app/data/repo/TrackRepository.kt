package com.niutrip.app.data.repo

import com.niutrip.app.data.local.TrackDao
import com.niutrip.app.data.local.TrackEntity
import com.niutrip.app.data.remote.*
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class TrackRepository(private val api: ApiService, private val tracks: TrackDao) {
    suspend fun listMine(): Result<List<TrackDto>> = list("mine", cache = true)
    suspend fun listShared(): Result<List<TrackDto>> = list("shared", cache = false)
    suspend fun saveShared(token: String): Result<ShareDataDto> = runCatching {
        apiCall { api.shareSave(token) }
    }

    private suspend fun list(scope: String, cache: Boolean): Result<List<TrackDto>> = runCatching {
        apiCall { api.tracks(scope) }.sortedWith(compareBy<TrackDto> { it.track_status != "RECORDING" }
            .thenByDescending { it.track_start_time.orEmpty() }).also { result ->
            if (cache) tracks.upsertAll(result.map(TrackEntity::from))
        }
    }.recoverCatching { error ->
        if (!cache) throw error
        tracks.observeAll().first().map(TrackEntity::toDto).ifEmpty { throw error }
    }

    suspend fun create(name: String, mode: String) = apiCall { api.createTrack(TrackCreateIn(name.trim(), mode)) }
        .also { tracks.upsert(TrackEntity.from(it)) }
    suspend fun detail(id: String) = apiCall { api.track(id) }
    suspend fun patch(id: String, body: TrackPatchIn) = apiCall { api.patchTrack(id, body) }
        .also { tracks.upsert(TrackEntity.from(it)) }
    suspend fun updateImage(id: String, file: File, mediaType: String): TrackDto {
        val part = MultipartBody.Part.createFormData("image", file.name, file.asRequestBody(mediaType.toMediaType()))
        return apiCall { api.updateTrackCover(id, part) }
            .also { tracks.upsert(TrackEntity.from(it)) }
    }
    suspend fun uploadImage(file: File, mediaType: String): String {
        val part = MultipartBody.Part.createFormData("image", file.name, file.asRequestBody(mediaType.toMediaType()))
        return apiCall { api.upload(part) }.url
    }
    suspend fun updateCheckin(trackId: String, pointId: String, body: PointPatchIn): PointDto =
        apiCall { api.patchPoint(trackId = trackId, pointId = pointId, body = body) }
    suspend fun delete(id: String) { apiCall { api.deleteTrack(id) }; refreshCacheWithout(id) }
    suspend fun deleteShared(id: String) { apiCall { api.deleteReceivedShare(id) } }
    suspend fun points(id: String): List<PointDto> {
        val all = mutableListOf<PointDto>()
        var page = 1
        do {
            val result = apiCall { api.points(id, page) }
            all += result.results
            page++
        } while (result.next != null)
        return all
    }

    private suspend fun refreshCacheWithout(id: String) {
        val remaining = tracks.observeAll().first().filter { it.trackId != id }
        tracks.clear(); tracks.upsertAll(remaining)
    }
}
