package com.niutrip.app.data.repo

import com.niutrip.app.core.GeoBbox
import com.niutrip.app.core.route.RouteLodParams
import com.niutrip.app.core.route.RouteLodPayload
import com.niutrip.app.core.toQueryParam
import com.niutrip.app.data.local.CloudPointDao
import com.niutrip.app.data.local.CloudPointEntity
import com.niutrip.app.data.local.PendingPointDao
import com.niutrip.app.data.local.RouteLodCacheDao
import com.niutrip.app.data.local.RouteLodCacheEntity
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
    private val lodCache: RouteLodCacheDao? = null,
    private val accountKey: () -> String? = { "test-account" },
) {
    companion object {
        /** LOD 派生缓存总量预算（方案 §5.2：初始 64MB，按 payload 字节 LRU 回收）。 */
        const val LOD_CACHE_MAX_BYTES: Long = 64L * 1024 * 1024
    }

    // ===== 本地轨迹与嵌套 LOD（方案 §6.4）=====

    /** 本地完整基线：仅当 baselineComplete 且账号一致时返回。 */
    suspend fun cachedRouteBaseline(trackId: String): RouteBaseline? {
        val cache = pointCache ?: return null
        val ownerKey = accountKey() ?: return null
        val state = cache.syncState(trackId) ?: return null
        if (state.accountKey != ownerKey || !state.baselineComplete) return null
        val points = cache.forTrack(trackId).map(CloudPointEntity::toDto)
        return RouteBaseline(trackId, points, baselineComplete = true)
    }

    /** 后台完整基线同步：复用 point-changes 循环，返回数据库最终状态而不是网络本批结果。 */
    suspend fun syncRouteBaseline(trackId: String): RouteBaseline {
        val points = points(trackId)
        // points() 成功返回即代表拿到了完整集合：
        // 有缓存时 point-changes 循环已跑完（或已有完整基线的静默回退），无缓存时是全量分页结果。
        if (pointCache != null) {
            accountKey()?.let { ownerKey -> lodCache?.deleteOtherAccounts(ownerKey) }
        }
        return RouteBaseline(trackId, points, baselineComplete = true)
    }

    /** LOD 缓存命中：指纹/账号/算法版本/点数全一致才可用；不一致或损坏即删行重建（方案 §5.2）。 */
    suspend fun cachedLod(
        trackId: String,
        fingerprint: String,
        pointCount: Int,
        accountKey: String? = null,
    ): CachedRouteLod? {
        val cache = lodCache ?: return null
        val ownerKey = accountKey ?: this.accountKey() ?: return null
        val row = cache.get(trackId) ?: return null
        val valid = row.accountKey == ownerKey &&
            row.sourceFingerprint == fingerprint &&
            row.algorithmVersion == RouteLodParams.ALGORITHM_VERSION &&
            row.pointCount == pointCount
        if (!valid) {
            cache.delete(trackId)
            return null
        }
        val decoded = RouteLodPayload.decode(row.importancePayload, pointCount)
        if (decoded == null) {
            cache.delete(trackId)   // 损坏回退：只删派生缓存，不动事实数据
            return null
        }
        cache.touch(trackId, System.currentTimeMillis())
        return CachedRouteLod(decoded.first, decoded.second)
    }

    /** 保存 LOD 派生缓存并执行 LRU 回收。 */
    suspend fun saveLod(cache: RouteLodCache) {
        val dao = lodCache ?: return
        val ownerKey = accountKey() ?: return
        val now = System.currentTimeMillis()
        dao.upsert(RouteLodCacheEntity(
            trackId = cache.trackId,
            accountKey = ownerKey,
            sourceFingerprint = cache.sourceFingerprint,
            algorithmVersion = RouteLodParams.ALGORITHM_VERSION,
            pointCount = cache.pointCount,
            importancePayload = RouteLodPayload.encode(cache.importance, cache.mandatory),
            createdAt = now,
            lastAccessAt = now,
        ))
        dao.trimToBytes(LOD_CACHE_MAX_BYTES)
    }

    /** 点编辑/删除/清轨迹后失效派生缓存。 */
    suspend fun invalidateRouteGeometry(trackId: String) {
        lodCache?.delete(trackId)
    }


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
            .also {
                pointCache?.upsertAll(listOf(CloudPointEntity.from(trackId, it)))
                invalidateRouteGeometry(trackId)
            }
    suspend fun deletePoint(trackId: String, pointId: String) {
        apiCall { api.deletePoint(trackId = trackId, pointId = pointId) }
        pointCache?.deletePoints(listOf(pointId))
        invalidateRouteGeometry(trackId)
    }
    suspend fun delete(id: String) {
        apiCall { api.deleteTrack(id) }
        refreshCacheWithout(id)
        pointCache?.clearTrack(id)
        invalidateRouteGeometry(id)
    }
    suspend fun deleteShared(id: String) {
        apiCall { api.deleteReceivedShare(id) }
        pointCache?.clearTrack(id)
        invalidateRouteGeometry(id)
    }
    /** 视口 LOD：拉取 bbox 内全精度点（不分页、服务端 ≤4000 抽稀兜底）。 */
    suspend fun pointsInBbox(id: String, bbox: GeoBbox): List<PointDto> =
        apiCall { api.pointsInBbox(id, bbox.toQueryParam()).results }

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
