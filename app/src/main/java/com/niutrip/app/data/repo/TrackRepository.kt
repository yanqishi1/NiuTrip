package com.niutrip.app.data.repo

import com.niutrip.app.data.local.CloudPointDao
import com.niutrip.app.data.local.CloudPointEntity
import com.niutrip.app.data.local.PendingPointDao
import com.niutrip.app.data.local.TrackDao
import com.niutrip.app.data.local.TrackEntity
import com.niutrip.app.data.remote.*
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import kotlinx.coroutines.CancellationException

class TrackRepository(
    private val api: ApiService,
    private val tracks: TrackDao,
    private val pointCache: CloudPointDao? = null,
    private val pendingPoints: PendingPointDao? = null,
    private val accountKey: () -> String? = { "test-account" },
) {
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
    suspend fun detail(id: String, recordView: Boolean = false) =
        apiCall { api.track(id, recordView) }
    suspend fun overview(id: String, recordView: Boolean = false) =
        apiCall { api.trackOverview(id, recordView) }
    suspend fun cachedDetail(id: String): TrackDto? = tracks.get(id)?.toDto()
    suspend fun cachedPoints(id: String): List<PointDto>? {
        val cache = pointCache ?: return null
        val state = cache.syncState(id)
        if (state?.baselineComplete != true || state.accountKey != accountKey()) return null
        return cache.forTrack(id).map(CloudPointEntity::toDto)
    }
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
    suspend fun updatePoint(trackId: String, pointId: String, body: PointPatchIn): PointDto =
        apiCall { api.patchPoint(trackId = trackId, pointId = pointId, body = body) }
            .also { pointCache?.upsertAll(listOf(CloudPointEntity.from(trackId, it))) }
    suspend fun deletePoint(trackId: String, pointId: String) {
        apiCall { api.deletePoint(trackId = trackId, pointId = pointId) }
        pointCache?.deletePoints(listOf(pointId))
    }
    suspend fun delete(id: String) {
        apiCall { api.deleteTrack(id) }
        refreshCacheWithout(id)
        pointCache?.clearTrack(id)
    }
    suspend fun deleteShared(id: String) {
        apiCall { api.deleteReceivedShare(id) }
        pointCache?.clearTrack(id)
    }
    suspend fun points(id: String): List<PointDto> {
        val cache = pointCache ?: return loadAllPoints(id)
        val ownerKey = accountKey() ?: error("当前账号信息不可用")
        var state = cache.syncState(id)
        if (state != null && state.accountKey != ownerKey) {
            cache.clearTrack(id)
            state = null
        }
        val hadBaseline = state?.baselineComplete == true
        try {
            var cursor = state?.cursor
            var hasMore: Boolean
            do {
                val changes = apiCall { api.pointChanges(id, cursor) }
                if (changes.has_more && changes.cursor == cursor) {
                    error("云端轨迹点同步游标未前进")
                }
                val changedIds = changes.results.map(PointDto::point_id)
                if (changedIds.isNotEmpty()) pendingPoints?.deletePoints(changedIds)
                cache.applyChanges(
                    trackId = id,
                    upserts = changes.results.filterNot(PointDto::is_deleted)
                        .map { CloudPointEntity.from(id, it) },
                    deletedPointIds = changes.results.filter(PointDto::is_deleted)
                        .map(PointDto::point_id),
                    cursor = changes.cursor,
                    hasMore = changes.has_more,
                    accountKey = ownerKey,
                )
                cursor = changes.cursor
                hasMore = changes.has_more
            } while (hasMore)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!hadBaseline || (error is ApiException && error.code in 400..499)) throw error
        }
        return cache.forTrack(id).map(CloudPointEntity::toDto)
    }

    private suspend fun loadAllPoints(id: String): List<PointDto> {
        val all = mutableListOf<PointDto>()
        var page = 1
        do {
            val result = apiCall { api.pointsLargePage(id, page) }
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
