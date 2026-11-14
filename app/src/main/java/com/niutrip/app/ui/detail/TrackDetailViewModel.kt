package com.niutrip.app.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.core.*
import com.niutrip.app.core.route.*
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.ApiException
import com.niutrip.app.data.remote.PointPatchIn
import com.niutrip.app.data.remote.TrackDto
import com.niutrip.app.data.remote.TrackPatchIn
import com.niutrip.app.data.local.PendingPointEntity
import com.niutrip.app.data.repo.CachedRouteLod
import com.niutrip.app.data.repo.RouteLodCache
import com.niutrip.app.data.repo.SyncRepository
import com.niutrip.app.data.repo.RecordedTrackPoint
import com.niutrip.app.data.repo.TrackRepository
import com.niutrip.app.service.LocResult
import com.niutrip.app.service.LocationSource
import com.niutrip.app.ui.checkin.ImagePreparer
import com.niutrip.app.ui.detail.map.ALL_DAYS_SELECTION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** 轨迹几何渲染状态（方案 §6.1）：地图轨迹线的唯一数据源。 */
sealed interface RouteGeometryState {
    data object Empty : RouteGeometryState
    /** 无完整基线：overview 粗线首屏占位（方案 §2.2）。 */
    data class Preview(val days: List<DayGroup>) : RouteGeometryState
    /** 本地基线已就绪，嵌套 LOD 构建中；期间继续显示 preview/上一份几何。 */
    data class Building(val preview: List<DayGroup>) : RouteGeometryState
    data class Ready(
        val index: RouteGeometryIndex,
        val generation: Long,
    ) : RouteGeometryState
    /** 无本地内容且同步失败；有 preview 时保留 preview 显示。 */
    data class Failed(val preview: List<DayGroup>?) : RouteGeometryState
}

data class DetailState(
    val loading: Boolean = true,
    val track: TrackDto? = null,
    val days: List<DayGroup> = emptyList(),
    val points: List<PointDto> = emptyList(),
    /** 轨迹线几何状态（替代旧 pointsLoaded/detailDays，方案 §6.5）。 */
    val routeGeometry: RouteGeometryState = RouteGeometryState.Empty,
    /** 相机静止后按 (generation, lodBand, viewportCell, selectedDay) 生成的渲染模型。 */
    val renderModel: RouteRenderModel? = null,
    /** 录制中轨迹尾部全精度附加线（方案 §5.3）。 */
    val liveTail: List<PointLite> = emptyList(),
    val totalDistanceMeters: Double = 0.0,
    val selectedDay: Int = ALL_DAYS_SELECTION,
    val fullRouteFocused: Boolean = false,
    val error: String? = null,
    val deleted: Boolean = false,
    val current: PointLite? = null,
    val currentIsDeviceLocation: Boolean = false,
    val locatingCurrent: Boolean = false,
    val currentFocusRequest: Int = 0,
    val updatingImage: Boolean = false,
    val updatingPoint: Boolean = false,
    val changingStatus: Boolean = false,
)

class TrackDetailViewModel(
    private val id: String,
    private val repository: TrackRepository,
    private val locationSource: LocationSource,
    private val syncRepository: SyncRepository,
    private val imagePreparer: ImagePreparer,
    private val recordSharedView: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailState()); val state = _state.asStateFlow()
    private var viewRequestSent = false

    // 几何管线作业与代数（方案 §6.1/§6.5）：generation 防止旧协程结果覆盖新轨迹
    private var generation: Long = 0
    private var geometryRevision: Long = 0
    private var metadataJob: Job? = null
    private var geometrySyncJob: Job? = null
    private var geometryBuildJob: Job? = null
    private var renderJob: Job? = null
    // 构建任务在 Default 线程写、load/sync 在主线程读
    @Volatile private var lastBuiltFingerprint: String? = null

    // 相机状态只存在于本地，onCameraIdle 不再访问网络（方案 §6.5）
    private var cameraViewport: GeoBbox? = null
    private var cameraZoom: Float = RouteLodParams.LOD_ZOOM_BANDS.first().toFloat()

    // 录制尾部节流（方案 §5.3）
    private var tailPointsSinceRebuild = 0
    private var tailLastRebuildAt = 0L

    // 里程基准：粗线是抽稀的，不能按已加载点整段重算——
    // 服务端总里程 + 进入页面后实时记录的增量腿
    private var serverDistanceMeters = 0.0
    private var liveLegsMeters = 0.0
    private var lastKnownPoint: PointLite? = null

    init {
        // 本地入库即更新地图和里程；上传成功后不再整页回拉云端点。
        viewModelScope.launch {
            syncRepository.recordedPoints.collect { point ->
                if (point.trackId == id) addLocalPoint(point)
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun showError(message: String) = _state.update { it.copy(error = message) }

    /**
     * 加载状态机（方案 §6.5）：
     * 1. 取消旧 job，generation++
     * 2. 本地完整基线 → 立即绘制并后台构建几何
     * 3. 无基线 → overview 占位 Preview
     * 4. 后台完整基线同步；变化则重建几何
     * 5. 同步失败：已有本地 Ready/Building 时静默，仅无任何内容时显示错误
     */
    fun load() {
        metadataJob?.cancel()
        geometrySyncJob?.cancel()
        geometryBuildJob?.cancel()
        generation++
        val myGeneration = generation
        _state.update { it.copy(fullRouteFocused = false) }
        locateCurrent(focus = true, showError = false, recordPoint = false)
        metadataJob = viewModelScope.launch {
            if (_state.value.track == null) {
                val cachedTrack = repository.cachedDetail(id)
                val baseline = runCatching { repository.cachedRouteBaseline(id) }.getOrNull()
                if (cachedTrack != null && baseline != null) {
                    val points = mergeTrackPoints(baseline.points, syncRepository.localPoints(id))
                    val prepared = withContext(Dispatchers.Default) { preparePoints(points) }
                    applyFullPoints(cachedTrack, points, prepared)
                    scheduleGeometryBuild(myGeneration)
                }
            }
            val hasLocalBaseline = _state.value.routeGeometry.let {
                it is RouteGeometryState.Building || it is RouteGeometryState.Ready
            }
            if (_state.value.track == null) _state.update { it.copy(loading = true, error = null) }
            val shouldRecordView = recordSharedView && !viewRequestSent
            if (shouldRecordView) viewRequestSent = true
            var detailLoaded = false
            var fullPointsFetched = false
            try {
                val overview = if (hasLocalBaseline) null else try {
                    repository.overview(id, recordView = shouldRecordView)
                } catch (error: ApiException) {
                    if (error.code != 404) throw error
                    null // 允许老服务端没有 overview 端点
                }
                val track = overview?.track ?: repository.detail(id, recordView = shouldRecordView)
                detailLoaded = true
                if (overview == null) {
                    // 老服务端（无 overview）：详情 + 全量点拉取（基线在时只刷新元数据）
                    if (hasLocalBaseline) {
                        _state.update { it.copy(loading = false, error = null, track = track) }
                    } else {
                        applyFullPoints(track, mergeTrackPoints(
                            repository.points(id), syncRepository.localPoints(id)))
                        fullPointsFetched = true
                        scheduleGeometryBuild(myGeneration)
                    }
                } else {
                    applyOverview(track, overview.days.flatMap { it.points },
                        overview.total_distance_meters)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (shouldRecordView && !detailLoaded) viewRequestSent = false
                if (_state.value.track == null) {
                    _state.update { it.copy(loading = false, error = error.message ?: "加载失败") }
                }
            }
            // 无论有无基线，后台完整基线同步（方案 Phase 1）；
            // 本次 load 已全量拉到点时无需再同步一遍
            if (myGeneration == generation && !fullPointsFetched) {
                syncBaselineInBackground(myGeneration)
            }
        }
    }

    /** 后台完整基线同步；失败静默，保留本地轨迹（方案 §6.5.8）。 */
    private fun syncBaselineInBackground(myGeneration: Long) {
        geometrySyncJob?.cancel()
        geometrySyncJob = viewModelScope.launch {
            try {
                val baseline = repository.syncRouteBaseline(id)
                if (myGeneration != generation || !baseline.baselineComplete) return@launch
                val track = _state.value.track
                if (track == null) return@launch
                val points = mergeTrackPoints(baseline.points, syncRepository.localPoints(id))
                val prepared = withContext(Dispatchers.Default) { preparePoints(points) }
                applyFullPoints(track, points, prepared)
                val fingerprint = withContext(Dispatchers.Default) {
                    RouteLodBuilder.sourceFingerprint(points.toCanonicalPoints())
                }
                if (fingerprint != lastBuiltFingerprint) scheduleGeometryBuild(myGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // 已有本地几何时静默；overview/Preview 继续显示（不覆盖成功状态）
                _state.update { state ->
                    if (state.routeGeometry is RouteGeometryState.Preview) {
                        state.copy(routeGeometry = RouteGeometryState.Failed(
                            (state.routeGeometry as RouteGeometryState.Preview).days))
                    } else state
                }
            }
        }
    }

    /** overview 粗线（含完整 MANUAL 打卡点）+ 本地未同步点；里程用服务端精确值。 */
    private suspend fun applyOverview(
        track: TrackDto,
        overviewPoints: List<PointDto>,
        distanceMeters: Double,
        keepCurrent: Boolean = false,
    ) {
        val points = mergeTrackPoints(overviewPoints, syncRepository.localPoints(id))
        val litePoints = points.mapNotNull(PointDto::toLite)
        serverDistanceMeters = distanceMeters
        liveLegsMeters = 0.0
        lastKnownPoint = litePoints.maxByOrNull(PointLite::time)
        val recordedFallback = lastKnownPoint
            ?.takeIf { track.track_status == "RECORDING" && track.track_record_mode == "AUTO" }
            ?.copy(id = "current")
        _state.update { old ->
            val days = DayGrouper.group(litePoints)
            old.copy(loading = false, error = null,
                track = track,
                days = days, points = points,
                routeGeometry = when (old.routeGeometry) {
                    is RouteGeometryState.Building, is RouteGeometryState.Ready -> old.routeGeometry
                    else -> RouteGeometryState.Preview(days)
                },
                totalDistanceMeters = serverDistanceMeters + liveLegsMeters,
                current = if (old.currentIsDeviceLocation || keepCurrent) old.current
                    else newerCurrent(old.current, recordedFallback))
        }
    }

    /** 点位编辑/删除后静默刷新 overview，校正里程与统计。 */
    private fun refreshOverviewAfterEdit() = viewModelScope.launch {
        try {
            val overview = repository.overview(id)
            applyOverview(overview.track, overview.days.flatMap { it.points },
                overview.total_distance_meters, keepCurrent = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 静默失败：本地视图已是编辑后的近似值，下次进页/刷新自然校正
        }
    }

    /**
     * 相机静止（方案 §6.5）：只更新本地视口与 zoom 并重建渲染模型；
     * 完整基线模式下不访问任何网络。
     */
    fun onCameraIdle(bbox: GeoBbox, zoom: Float) {
        cameraViewport = bbox
        cameraZoom = zoom
        refreshRenderModel()
    }

    private fun refreshRenderModel() {
        val ready = _state.value.routeGeometry as? RouteGeometryState.Ready ?: return
        val myGeneration = generation
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val selectedDay = _state.value.selectedDay.takeIf { it != ALL_DAYS_SELECTION }
            val query = RouteRenderQuery(
                generation = ready.generation,
                zoom = cameraZoom,
                viewport = cameraViewport,
                selectedDayIndex = selectedDay,
            )
            val key = RouteRenderPolicy.renderKey(query)
            if (_state.value.renderModel?.key == key) return@launch
            val model = withContext(Dispatchers.Default) {
                RouteRenderPolicy.buildRenderModel(ready.index, query)
            }
            if (myGeneration != generation) return@launch
            _state.update { state ->
                val currentGeneration = (state.routeGeometry as? RouteGeometryState.Ready)?.generation
                if (currentGeneration != ready.generation) state
                else state.copy(renderModel = model)
            }
        }
    }

    /** 后台构建/重建 RouteGeometryIndex：优先命中 LOD 缓存，未命中跑 DP 并落缓存（方案 §5/§6.5）。 */
    private fun scheduleGeometryBuild(
        myGeneration: Long = generation,
        invalidateCache: Boolean = false,
    ) {
        val canonical = _state.value.points.toCanonicalPoints()
        if (canonical.isEmpty()) return
        val shouldCache = _state.value.track?.track_status != "RECORDING"
        geometryRevision++
        val revision = geometryRevision
        geometryBuildJob?.cancel()
        geometryBuildJob = viewModelScope.launch {
            if (invalidateCache) runCatching { repository.invalidateRouteGeometry(id) }
            val fingerprint = if (canonical.size <= 1_024) {
                RouteLodBuilder.sourceFingerprint(canonical)
            } else withContext(Dispatchers.Default) {
                RouteLodBuilder.sourceFingerprint(canonical)
            }
            val cached = runCatching { repository.cachedLod(id, fingerprint, canonical.size) }
                .getOrNull()
            val usable = cached?.takeIf {
                it.importance.size == canonical.size && it.mandatory.size == canonical.size
            }
            val buildBlock: suspend () -> RouteGeometryIndex? = {
                usable?.let {
                    runCatching {
                        RouteLodBuilder.buildFromCache(id, canonical, it.importance, it.mandatory)
                    }.getOrNull()
                } ?: buildFresh(canonical)
            }
            val index = if (canonical.size <= 1_024) buildBlock()
                else withContext(Dispatchers.Default) { buildBlock() }
            if (index != null && usable == null && shouldCache) {
                runCatching {
                    repository.saveLod(RouteLodCache(
                        trackId = id,
                        sourceFingerprint = fingerprint,
                        pointCount = index.pointCount,
                        importance = index.importance,
                        mandatory = index.mandatory,
                    ))
                }
            }
            if (index != null && myGeneration == generation && revision == geometryRevision) {
                lastBuiltFingerprint = fingerprint
                _state.update {
                    it.copy(
                        routeGeometry = RouteGeometryState.Ready(index, revision),
                        // FINISHED 后这次索引已包含最终点集，不再需要重复覆盖的实时尾线。
                        liveTail = if (it.track?.track_status == "FINISHED") emptyList() else it.liveTail,
                    )
                }
                refreshRenderModel()
            }
        }
    }

    private suspend fun buildFresh(canonical: List<CanonicalRoutePoint>): RouteGeometryIndex? = try {
        val buildContext = coroutineContext
        RouteLodBuilder.build(id, canonical) { buildContext.ensureActive() }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        null // 派生数据构建失败不阻塞展示（days 仍在）
    }

    private data class PreparedPoints(val days: List<DayGroup>, val distance: Double, val latest: PointLite?)

    private fun preparePoints(points: List<PointDto>): PreparedPoints {
        val litePoints = points.mapNotNull(PointDto::toLite)
        return PreparedPoints(DayGrouper.group(litePoints), RouteGeometry.totalDistanceMeters(litePoints),
            litePoints.maxByOrNull(PointLite::time))
    }

    private fun applyFullPoints(track: TrackDto, points: List<PointDto>,
                                prepared: PreparedPoints = preparePoints(points)) {
        val recordedFallback = prepared.latest
            ?.takeIf { track.track_status == "RECORDING" && track.track_record_mode == "AUTO" }
            ?.copy(id = "current")
        _state.update { old ->
            old.copy(loading = false, error = null,
                track = if (old.routeGeometry is RouteGeometryState.Ready) old.track ?: track else track,
                days = prepared.days, points = points,
                routeGeometry = if (old.routeGeometry is RouteGeometryState.Ready) old.routeGeometry
                    else RouteGeometryState.Building(prepared.days),
                totalDistanceMeters = prepared.distance,
                current = if (old.currentIsDeviceLocation) old.current
                    else newerCurrent(old.current, recordedFallback))
        }
    }

    private fun newerCurrent(current: PointLite?, candidate: PointLite?): PointLite? =
        candidate?.takeIf { current == null || it.time.isAfter(current.time) } ?: current

    fun focusCurrentLocation(recordPoint: Boolean = true) =
        locateCurrent(focus = true, showError = true, recordPoint = recordPoint)

    private fun addLocalPoint(point: RecordedTrackPoint) {
        updateCurrent(point.lon, point.lat, point.timeMillis)
        val isBrandNew = _state.value.points.none { it.point_id == point.pointId }
        _state.update { old ->
            val points = mergePointDtos(old.points, listOf(point.toPointDto()))
            val litePoints = points.mapNotNull(PointDto::toLite)
            val fullLocal = old.routeGeometry.let {
                it is RouteGeometryState.Building || it is RouteGeometryState.Ready
            }
            old.copy(
                points = points,
                days = if (fullLocal) DayGrouper.group(litePoints) else old.days,
                totalDistanceMeters = if (fullLocal)
                    RouteGeometry.totalDistanceMeters(litePoints)
                else old.totalDistanceMeters,
            )
        }
        val fullLocal = _state.value.routeGeometry.let {
            it is RouteGeometryState.Building || it is RouteGeometryState.Ready
        }
        if (fullLocal) {
            // 录制尾部：全精度附加显示 + 节流重建（方案 §5.3）
            val fresh = point.toPointDto().toLite() ?: return
            _state.update { old ->
                val tail = (if (old.liveTail.isEmpty())
                    old.days.lastOrNull()?.points?.lastOrNull()?.let { listOf(it) } ?: emptyList()
                else old.liveTail) + fresh
                old.copy(liveTail = tail.takeLast(RouteLodParams.RECORDING_TAIL_FULL_RESOLUTION_POINTS + 1))
            }
            tailPointsSinceRebuild++
            val now = System.currentTimeMillis()
            if (tailPointsSinceRebuild >= RouteLodParams.RECORDING_REBUILD_POINT_INTERVAL ||
                now - tailLastRebuildAt >= RouteLodParams.RECORDING_REBUILD_INTERVAL_MS
            ) {
                tailPointsSinceRebuild = 0
                tailLastRebuildAt = now
                scheduleGeometryBuild()
            }
        } else {
            // 粗线路径：总里程 = 服务端值 + 实时增量腿（上一已知点 → 新点）
            val fresh = point.toPointDto().toLite() ?: return
            val known = lastKnownPoint
            if (known != null && isBrandNew) {
                liveLegsMeters += RouteGeometry.distanceMeters(known.lat, known.lon, fresh.lat, fresh.lon)
            }
            lastKnownPoint = fresh
            _state.update { it.copy(totalDistanceMeters = serverDistanceMeters + liveLegsMeters) }
        }
    }

    private fun updateCurrent(lon: Double, lat: Double, timeMillis: Long) {
        val time = (if (timeMillis > 0) timeMillis else System.currentTimeMillis()).toBeijingDateTime()
        _state.update { old ->
            if (old.current?.time?.isAfter(time) == true) old
            else old.copy(
                current = PointLite("current", time, lon, lat, false),
                currentIsDeviceLocation = true,
            )
        }
    }

    private fun locateCurrent(
        focus: Boolean,
        showError: Boolean,
        recordPoint: Boolean = false,
    ) = viewModelScope.launch {
        _state.update { it.copy(locatingCurrent = true) }
        when (val location = locationSource.singleShot()) {
            is LocResult.Success -> {
                val time = if (location.timeMillis > 0) location.timeMillis else System.currentTimeMillis()
                _state.update { _state.value.copy(current = PointLite("current", time.toBeijingDateTime(),
                    location.lon, location.lat, false, null, null, emptyList()),
                    currentIsDeviceLocation = true,
                    fullRouteFocused = if (focus) false else it.fullRouteFocused,
                    locatingCurrent = false,
                    currentFocusRequest = if (focus) it.currentFocusRequest + 1 else it.currentFocusRequest) }
                if (recordPoint && !recordSharedView && _state.value.track?.track_status == "RECORDING") {
                    runCatching {
                        syncRepository.enqueueAutoPoint(id, location.lon, location.lat, time)
                    }.onSuccess {
                        viewModelScope.launch { syncRepository.flushOnce() }
                    }.onFailure { error ->
                        _state.update { it.copy(error = error.message ?: "轨迹点保存失败") }
                    }
                }
            }
            is LocResult.Failure -> _state.update {
                it.copy(locatingCurrent = false,
                    error = if (showError) location.reason else it.error)
            }
        }
    }
    fun selectDay(index: Int) {
        _state.update {
            it.copy(
                selectedDay = index,
                fullRouteFocused = index == ALL_DAYS_SELECTION,
            )
        }
        refreshRenderModel()
    }
    fun changeStatus(status: String, onChanged: (TrackDto) -> Unit = {}) = viewModelScope.launch {
        if (_state.value.changingStatus) return@launch
        _state.update { it.copy(changingStatus = true, error = null) }
        runCatching {
            if (status == "FINISHED" && syncRepository.flushTrack(id) is com.niutrip.app.data.repo.FlushResult.Failed) {
                error("轨迹点尚未同步，暂未结束轨迹，请联网后重试")
            }
            repository.patch(id, TrackPatchIn(track_status = status))
        }.onSuccess { track ->
            _state.update { it.copy(track = track, changingStatus = false) }
            if (status == "FINISHED") scheduleGeometryBuild()
            onChanged(track)
        }.onFailure { error -> _state.update { it.copy(error = error.message ?: "状态更新失败", changingStatus = false) } }
    }
    fun rename(name: String) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        runCatching { repository.patch(id, TrackPatchIn(track_name = name.trim())) }.onSuccess { value -> _state.update { it.copy(track = value) } }
            .onFailure { error -> _state.update { it.copy(error = error.message ?: "重命名失败") } }
    }
    fun updateImage(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(updatingImage = true, error = null) }
        runCatching {
            val image = withContext(Dispatchers.IO) { imagePreparer.prepareForUpload(uri) }
            try {
                repository.updateImage(id, image.file, image.mediaType)
            } finally {
                withContext(Dispatchers.IO) { image.file.delete() }
            }
        }.onSuccess { track ->
            _state.update { it.copy(track = track, updatingImage = false) }
        }.onFailure { error ->
            _state.update { it.copy(updatingImage = false, error = error.message ?: "代表图更新失败") }
        }
    }
    fun updatePoint(
        pointId: String,
        wasCheckin: Boolean,
        name: String,
        desc: String,
        longitude: Double,
        latitude: Double,
        existingImages: List<String>,
        newImages: List<Uri>,
        onDone: () -> Unit = {},
    ) = viewModelScope.launch {
        pointContentValidation(wasCheckin, name, desc, existingImages.size + newImages.size)?.let { message ->
            _state.update { it.copy(error = message) }
            return@launch
        }
        if (!longitude.isFinite() || longitude !in -180.0..180.0 ||
            !latitude.isFinite() || latitude !in -90.0..90.0) {
            _state.update { it.copy(error = "请输入有效的经纬度") }
            return@launch
        }
        if (existingImages.size + newImages.size > 9) {
            _state.update { it.copy(error = "最多保留 9 张图片") }
            return@launch
        }
        _state.update { it.copy(updatingPoint = true, error = null) }
        runCatching {
            val uploaded = newImages.map { uri ->
                val image = withContext(Dispatchers.IO) { imagePreparer.prepareForUpload(uri) }
                try {
                    repository.uploadImage(image.file, image.mediaType)
                } finally {
                    withContext(Dispatchers.IO) { image.file.delete() }
                }
            }
            val normalizedName = name.trim().ifBlank { null }
            val normalizedDesc = desc.trim().ifBlank { null }
            val images = existingImages + uploaded
            val source = if (normalizedName == null) "AUTO" else "MANUAL"
            val localUpdated = syncRepository.updateLocalPoint(
                pointId, longitude, latitude, normalizedName, normalizedDesc, images, source)
            if (localUpdated) {
                _state.value.points.first { it.point_id == pointId }.copy(
                    point_longitude = null,
                    point_latitude = null,
                    longitude = longitude,
                    latitude = latitude,
                    point_name = normalizedName,
                    point_desc = normalizedDesc,
                    point_img_url = images,
                    point_source = source,
                ).also { viewModelScope.launch { syncRepository.flushOnce() } }
            } else {
                val updated = repository.updatePoint(id, pointId, PointPatchIn(
                    longitude = longitude,
                    latitude = latitude,
                    point_name = normalizedName,
                    point_desc = normalizedDesc,
                    point_img_url = images,
                ))
                runCatching { repository.detail(id) }.onSuccess { track ->
                    _state.update { it.copy(track = track) }
                }
                updated
            }
        }.onSuccess { updated ->
            _state.update { old ->
                val points = old.points.map { if (it.point_id == updated.point_id) updated else it }
                val litePoints = points.mapNotNull(PointDto::toLite)
                old.copy(points = points, days = DayGrouper.group(litePoints), updatingPoint = false)
            }
            rebuildGeometryAfterEdit()
            onDone()
        }.onFailure { error ->
            _state.update { it.copy(updatingPoint = false, error = error.message ?: "轨迹点更新失败") }
        }
    }

    fun deletePoint(pointId: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        _state.update { it.copy(updatingPoint = true, error = null) }
        runCatching {
            if (!syncRepository.deleteLocalPoint(pointId)) repository.deletePoint(id, pointId)
        }.onSuccess {
            _state.update { old ->
                val points = old.points.filterNot { it.point_id == pointId }
                val litePoints = points.mapNotNull(PointDto::toLite)
                val days = DayGrouper.group(litePoints)
                old.copy(
                    points = points,
                    days = days,
                    totalDistanceMeters = RouteGeometry.totalDistanceMeters(litePoints),
                    selectedDay = old.selectedDay.takeIf {
                        it == ALL_DAYS_SELECTION || it in days.indices
                    } ?: ALL_DAYS_SELECTION,
                    updatingPoint = false,
                )
            }
            rebuildGeometryAfterEdit()
            onDone()
        }.onFailure { error ->
            _state.update { it.copy(updatingPoint = false, error = error.message ?: "轨迹点删除失败") }
        }
    }

    /** 点编辑/删除：立即失效缓存并后台重建几何（方案 §5.2）。 */
    private fun rebuildGeometryAfterEdit() {
        if (_state.value.points.isEmpty()) {
            _state.update { it.copy(routeGeometry = RouteGeometryState.Empty, renderModel = null, liveTail = emptyList()) }
        } else {
            scheduleGeometryBuild(invalidateCache = true)
        }
    }
    fun delete() = viewModelScope.launch {
        runCatching { repository.delete(id) }.onSuccess { _state.update { it.copy(deleted = true) } }
            .onFailure { error -> _state.update { it.copy(error = error.message) } }
    }
}

internal fun mergeTrackPoints(
    cloud: List<PointDto>,
    local: List<PendingPointEntity>,
): List<PointDto> = mergePointDtos(local.map(PendingPointEntity::toPointDto), cloud)

private fun mergePointDtos(base: List<PointDto>, additions: List<PointDto>): List<PointDto> =
    (base + additions).associateBy(PointDto::point_id).values
        .sortedBy { it.point_time }

private fun PendingPointEntity.toPointDto() = PointDto(
    point_id = pointId,
    longitude = lon,
    latitude = lat,
    point_name = name,
    point_desc = desc,
    point_img_url = runCatching { Json.decodeFromString<List<String>>(imgs) }
        .getOrDefault(emptyList()),
    point_time = time.toBeijingDateTime().toApiTime(),
    point_source = source,
)

private fun RecordedTrackPoint.toPointDto() = PointDto(
    point_id = pointId,
    longitude = lon,
    latitude = lat,
    point_time = timeMillis.toBeijingDateTime().toApiTime(),
    point_source = "AUTO",
)

/** 几何只保留绘线必需字段（方案 §3.1）；point_time 即北京时间本地时间。 */
private fun List<PointDto>.toCanonicalPoints(): List<CanonicalRoutePoint> = mapNotNull { dto ->
    val time = dto.point_time.toLocalDateTimeOrNull() ?: return@mapNotNull null
    val lon = dto.longitude ?: dto.point_longitude ?: return@mapNotNull null
    val lat = dto.latitude ?: dto.point_latitude ?: return@mapNotNull null
    CanonicalRoutePoint(
        pointId = dto.point_id,
        timeMillis = time.toEpochMillis(),
        longitude = lon,
        latitude = lat,
        isCheckin = dto.point_source == "MANUAL",
        dayKey = time.toLocalDate(),
    )
}
