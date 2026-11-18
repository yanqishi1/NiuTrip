package com.niutrip.app.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.core.toApiTime
import com.niutrip.app.core.toBeijingDateTime
import com.niutrip.app.data.local.PendingPointEntity
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.ShareAnnotationPatch
import com.niutrip.app.data.repo.SyncRepository
import com.niutrip.app.data.repo.TrackRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** 数据准备（plan §5.3）：与编辑、导出状态分离。 */
sealed interface PosterDataPhase {
    data object Loading : PosterDataPhase
    data object Ready : PosterDataPhase
    data class Error(val message: String) : PosterDataPhase
}

/** 当前交互模式：选择模块 / 照片裁剪 / 地图构图。 */
enum class PosterInteraction { SELECT, PHOTO_CROP, MAP_COMPOSE }
enum class PosterModule { ROUTE, INFO }

/** 信息模块显示开关（plan §9.1）。 */
enum class PosterInfoFlag { NAME, DATE, AUX }

sealed interface PosterPhotoPhase {
    data object Idle : PosterPhotoPhase
    data object Loading : PosterPhotoPhase
    data object Ready : PosterPhotoPhase
    data class Error(val message: String) : PosterPhotoPhase
}

/** 导出状态（plan §5.4）：绑定 requestId，旧请求结果不得覆盖新请求。 */
sealed interface PosterExport {
    data object Idle : PosterExport
    data class Rendering(val requestId: Long) : PosterExport
    data class Ready(val file: File, val editRevision: Int) : PosterExport
    data class Error(val message: String) : PosterExport
}

data class PosterEditorState(
    val phase: PosterDataPhase = PosterDataPhase.Loading,
    val document: PosterDocument = PosterDocument(),
    val snapshot: PosterTrackSnapshot? = null,
    /** 简化后的路线模型：拖动只改变换，不重新投影或简化（plan §8.2）。 */
    val routeModel: PosterRouteModel? = null,
    val interaction: PosterInteraction = PosterInteraction.SELECT,
    val selected: PosterModule? = null,
    val photoPhase: PosterPhotoPhase = PosterPhotoPhase.Idle,
    val photoPreview: Bitmap? = null,
    /** 地图 SDK 标识/版权保留区（画布高度比例），由地图宿主测量后回填。 */
    val mapReservedBottom: Float = 0.08f,
    val export: PosterExport = PosterExport.Idle,
    val hasUnsavedEdits: Boolean = false,
    /** 撤销/重做可用性；选中、参考线与导出不进入历史（plan §3.5）。 */
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** 拖动中的吸附参考线（归一化坐标），手势结束清除。 */
    val snapGuides: PosterLayout.SnapGuides? = null,
    val endpointNames: PosterEndpointNames = PosterEndpointNames(),
    val endpointLookupInProgress: Boolean = false,
    val annotationReport: PosterWireframeRenderer.AnnotationReport = PosterWireframeRenderer.AnnotationReport(),
    val annotationSaving: Boolean = false,
    val annotationSaveError: String? = null,
    val photoOutline: PosterOutlineResult? = null,
    val outlineLoading: Boolean = false,
)

/**
 * 海报编辑器状态机（plan §5）：编辑文档 + 运行时状态 + 导出版本管理。
 * 手势移动只更新预览；commitInteraction 提交一次编辑并递增 editRevision、记录历史。
 */
class PosterEditorViewModel(
    private val trackId: String,
    private val repository: TrackRepository,
    private val syncRepository: SyncRepository,
    private val appContext: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val resolvePlace: suspend (com.niutrip.app.core.PointLite) -> String? = { null },
    private val annotations: com.niutrip.app.data.ShareAnnotationStore = com.niutrip.app.data.ShareAnnotationStore(appContext),
    private val boundaryLoader: suspend (List<com.niutrip.app.core.PointLite>) -> PosterOutlineResult = {
        PosterBoundaryRepository(appContext).forPoints(it)
    },
) : ViewModel() {

    private val sessionId = "poster_${UUID.randomUUID()}"
    private val _state = MutableStateFlow(PosterEditorState())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null
    private var exportJob: Job? = null
    private var exportRequestId = 0L
    private var hasPendingGesture = false
    /** 手势开始时的文档快照（plan §3.5）：结束时作为一条历史记录，而不是提交时的已改动文档。 */
    private var gestureStartDocument: PosterDocument? = null
    private var photoCropBeforeAdjust: PhotoCrop? = null
    private var photoCounter = 0
    /** 会话内全部照片资源；撤销引用旧照片时不提前删除文件（plan §3.5）。 */
    private val photoResources = mutableMapOf<String, PosterPhotoResource>()
    private val photoPreviews = linkedMapOf<String, Bitmap>()
    /** 撤销历史（plan §3.5）：保存编辑前的文档快照；恢复内容但 editRevision 仍递增。 */
    private val undoStack = ArrayDeque<PosterDocument>()
    private val redoStack = ArrayDeque<PosterDocument>()
    /** 地图截图入口由地图宿主注册；回调始终在主线程执行。 */
    private var mapCapture: (suspend (PosterCanvasSpec) -> Bitmap?)? = null

    // ---------- 数据准备 ----------

    fun load() {
        if (_state.value.phase is PosterDataPhase.Ready) return
        loadJob?.cancel()
        _state.value = PosterEditorState()
        loadJob = viewModelScope.launch {
            try {
                val track = repository.detail(trackId)
                require(track.track_status == "FINISHED") { "仅已结束轨迹支持图片分享" }
                // 只使用同步清理后的本地点集合（plan §4.1），不复活已删 pending。
                var cloud = repository.points(trackId)
                annotations.legacySelection(trackId)?.let { legacy ->
                    val updates = cloud.filter { it.point_source == "MANUAL" && it.point_id in legacy && !it.share_annotation }
                        .map { ShareAnnotationPatch(it.point_id, true) }
                    val migrated = repository.updateShareAnnotations(trackId, updates).associateBy { it.point_id }
                    cloud = cloud.map { migrated[it.point_id] ?: it }
                    syncRepository.migrateLocalShareAnnotations(trackId, legacy)
                    annotations.clearLegacy(trackId)
                }
                val local = syncRepository.localPoints(trackId).map { it.toPosterPointDto() }
                val snapshot = PosterSnapshotBuilder.build(
                    track, cloud, local, dataRevision = 1)
                    ?: error("暂无有效轨迹点，无法生成图片")
                val fullModel = PosterRouteModel.build(snapshot.days)
                // 投影偏差阈值按 1080px 模块宽度 1.5px 目标换算（plan §8.2）。
                val epsilon = 1.5 * fullModel.bounds.spanX.coerceAtLeast(1e-6) / 1080.0
                val routeModel = fullModel.simplified(epsilon)
                val safe = PosterLayout.safeArea(reservedBottom = _state.value.mapReservedBottom)
                val infoAspect = measureInfoAspectDefaults(PosterInfoTextTheme.DARK)
                val endpointIds = setOf(snapshot.points.first().id, snapshot.points.last().id)
                val selected = snapshot.points.filter { it.isCheckin && it.shareAnnotation && it.id !in endpointIds }.map { it.id }.toSet()
                _state.update {
                    it.copy(
                        phase = PosterDataPhase.Ready,
                        snapshot = snapshot,
                        endpointLookupInProgress = true,
                        routeModel = routeModel,
                        document = it.document.copy(
                            mapRouteStyle = it.document.mapRouteStyle.copy(selectedCheckinIds = selected),
                            mapInfoState = PosterInfoModuleState(
                                PosterLayout.defaultMapPlacement(infoAspect, safe),
                                PosterInfoTextTheme.DARK)))
                }
                coroutineScope {
                    val start = async { lookupPlace(snapshot.points.first()) }
                    val end = async { lookupPlace(snapshot.points.last()) }
                    val names = PosterEndpointNames(start.await(), end.await())
                    if (names != _state.value.endpointNames) {
                        cancelExport()
                        _state.update { current -> current.copy(endpointNames = names, export = PosterExport.Idle,
                            document = current.document.copy(editRevision = current.document.editRevision + 1)) }
                    }
                    _state.update { it.copy(endpointLookupInProgress = false) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(phase = PosterDataPhase.Error(error.message ?: "加载失败")) }
            }
        }
    }

    private suspend fun lookupPlace(point: com.niutrip.app.core.PointLite): String? {
        if (!point.name.isNullOrBlank()) return point.name.trim()
        return try { resolvePlace(point) } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
    }

    // ---------- 照片 ----------

    /** 系统图库单选结果；失败保留切换前画面与编辑内容（plan §3.2）。 */
    fun pickPhoto(uri: Uri) {
        if (_state.value.photoPhase is PosterPhotoPhase.Loading) return
        _state.update { it.copy(photoPhase = PosterPhotoPhase.Loading) }
        viewModelScope.launch {
            try {
                val imported = withContext(ioDispatcher) { PosterPhotoLoader.import(appContext, uri, sessionId) }
                val preview = withContext(ioDispatcher) { imported.decode(maxDimension = PREVIEW_MAX_DIMENSION) }
                    ?: error("无法读取图片")
                requireNotNull(_state.value.snapshot) { "轨迹数据未就绪" }
                val routeAspect = PosterRenderer.routeModuleAspect(
                    _state.value.routeModel ?: error("轨迹数据未就绪"))
                val resource = imported.copy(id = "${sessionId}_photo_${photoCounter++}")
                rememberPhoto(resource, preview)
                val documentBefore = _state.value.document
                // 更换照片默认保留模块位置，重新初始化裁剪（plan §3.2）。
                val safe = _state.value.safeAreaFor(PosterBackgroundMode.PHOTO)
                val routeState = documentBefore.photoRouteState ?: PosterRouteModuleState(
                    PosterLayout.clampPlacement(ModulePlacement(0.5f, 0.72f, 0.7f), routeAspect, safe),
                    theme = documentBefore.mapRouteStyle.theme,
                    showCheckinMarkers = documentBefore.mapRouteStyle.showCheckinMarkers,
                    showEndpointNames = documentBefore.mapRouteStyle.showEndpointNames,
                    preferredCheckins = documentBefore.mapRouteStyle.preferredCheckins,
                    selectedCheckinIds = documentBefore.mapRouteStyle.selectedCheckinIds)
                val infoState = documentBefore.photoInfoState ?: PosterInfoModuleState(
                    PosterLayout.clampPlacement(ModulePlacement(0.5f, 0.3f, 0.88f),
                        measureInfoAspectDefaults(PosterInfoTextTheme.LIGHT), safe),
                    PosterInfoTextTheme.LIGHT)
                recordHistory(documentBefore)
                _state.update {
                    it.copy(
                        photoPhase = PosterPhotoPhase.Ready,
                        photoPreview = preview,
                        hasUnsavedEdits = true,
                        document = it.document.copy(
                            backgroundMode = PosterBackgroundMode.PHOTO,
                            photoRatioKind = if (it.document.photoRatioKind == PosterRatioKind.ORIGINAL)
                                PosterRatioKind.R3_4 else it.document.photoRatioKind,
                            photoState = PosterPhotoState(resource.id, resource.width, resource.height),
                            photoRouteState = routeState,
                            photoInfoState = infoState,
                            editRevision = it.document.editRevision + 1))
                }
                loadPhotoOutline()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(photoPhase = PosterPhotoPhase.Error(error.message ?: "无法读取图片")) }
            }
        }
    }

    fun dismissPhotoError() = _state.update {
        if (it.photoPhase is PosterPhotoPhase.Error) it.copy(photoPhase = PosterPhotoPhase.Idle) else it
    }

    fun loadPhotoOutline() {
        val current = _state.value
        if (current.outlineLoading) return
        val points = current.snapshot?.points ?: return
        _state.update { it.copy(outlineLoading = true) }
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { boundaryLoader(points) }
                if (_state.value.document.backgroundMode == PosterBackgroundMode.PHOTO) cancelExport()
                _state.update { it.copy(photoOutline = result, outlineLoading = false,
                    export = if (it.document.backgroundMode == PosterBackgroundMode.PHOTO) PosterExport.Idle else it.export) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (_state.value.document.backgroundMode == PosterBackgroundMode.PHOTO) cancelExport()
                _state.update { it.copy(outlineLoading = false,
                    export = if (it.document.backgroundMode == PosterBackgroundMode.PHOTO) PosterExport.Idle else it.export,
                    photoOutline = PosterOutlineResult(PosterMapOutline.load(appContext), warning = "细级轮廓暂不可用，已使用省界")) }
            }
        }
    }

    // ---------- 撤销 / 重做（plan §3.5 / 阶段 4） ----------

    fun undo() {
        if (locked()) return
        val before = undoStack.lastOrNull() ?: return
        val current = _state.value.document
        syncAnnotationSelection(before.mapRouteStyle.selectedCheckinIds.orEmpty()) {
            undoStack.removeLast()
            redoStack.addLast(current)
            applyRestoredDocument(before)
        }
    }

    fun redo() {
        if (locked()) return
        val after = redoStack.lastOrNull() ?: return
        val current = _state.value.document
        syncAnnotationSelection(after.mapRouteStyle.selectedCheckinIds.orEmpty()) {
            redoStack.removeLast()
            undoStack.addLast(current)
            applyRestoredDocument(after)
        }
    }

    /** 恢复文档内容，但 editRevision 仍递增，不回退成历史版本号（plan §5.4）。 */
    private fun applyRestoredDocument(restored: PosterDocument) {
        hasPendingGesture = false
        gestureStartDocument = null
        val photoId = restored.photoState?.resourceId
        _state.update {
            it.copy(
                document = restored.copy(editRevision = it.document.editRevision + 1),
                interaction = PosterInteraction.SELECT,
                selected = null,
                snapGuides = null,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                hasUnsavedEdits = true,
                export = PosterExport.Idle,
                photoPreview = photoId?.let(photoPreviews::get))
        }
        // 预览被回收时按需重解码（历史引用的旧照片不提前删除）。
        val missing = photoId?.takeIf { photoPreviews[it] == null && photoResources[it] != null }
        if (missing != null) {
            viewModelScope.launch {
                val decoded = withContext(ioDispatcher) {
                    photoResources[missing]?.decode(maxDimension = PREVIEW_MAX_DIMENSION)
                } ?: return@launch
                photoPreviews[missing] = decoded
                trimPreviewCache()
                if (_state.value.document.photoState?.resourceId == missing) {
                    _state.update { current -> current.copy(photoPreview = decoded) }
                }
            }
        }
    }

    private fun recordHistory(before: PosterDocument) {
        undoStack.addLast(before)
        while (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        _state.update { it.copy(canUndo = true, canRedo = false) }
    }

    // ---------- 背景 / 比例 ----------

    fun setBackgroundMode(mode: PosterBackgroundMode) {
        if (locked()) return
        if (mode == PosterBackgroundMode.PHOTO && _state.value.document.photoState == null) return
        val document = _state.value.document
        if (document.backgroundMode == mode) return
        recordHistory(document)
        _state.update {
            it.copy(
                interaction = PosterInteraction.SELECT,
                selected = null,
                document = it.document.copy(
                    backgroundMode = mode,
                    editRevision = it.document.editRevision + 1))
        }
    }

    /** 原图比例只允许照片模式；切换时保留照片模块的自由摆放位置。 */
    fun setRatio(kind: PosterRatioKind) {
        if (locked()) return
        val document = _state.value.document
        if (kind == PosterRatioKind.ORIGINAL &&
            (document.backgroundMode == PosterBackgroundMode.MAP || document.photoState == null)) return
        if (document.ratioKind == kind) return
        recordHistory(document)
        val updated = document.copy(
            mapRatioKind = if (document.backgroundMode == PosterBackgroundMode.MAP) kind else document.mapRatioKind,
            photoRatioKind = if (document.backgroundMode == PosterBackgroundMode.PHOTO) kind else document.photoRatioKind,
            editRevision = document.editRevision + 1)
        _state.update { it.copy(document = reclampForSpec(updated), hasUnsavedEdits = true, export = PosterExport.Idle) }
    }

    // ---------- 模块手势（移动中只更新预览，commit 提交一次编辑） ----------

    fun selectModule(module: PosterModule?) {
        if (_state.value.interaction != PosterInteraction.SELECT) return
        _state.update { it.copy(selected = module) } // 选中不进入历史（plan §3.5）
    }

    /** 退出构图等模式但不提交编辑（例如地图未改动时直接返回）。 */
    fun exitInteraction() {
        _state.update { if (it.interaction == PosterInteraction.SELECT) it else it.copy(interaction = PosterInteraction.SELECT) }
    }

    /**
     * 绝对位置更新，由屏幕从手势起始值计算（plan §6.4）；
     * [snapThresholdX]/[snapThresholdY] 为归一化吸附阈值（屏幕 6dp 换算），传入后启用吸附并输出参考线。
     */
    fun moveModule(
        module: PosterModule,
        centerX: Float,
        centerY: Float,
        snapThresholdX: Float = 0f,
        snapThresholdY: Float = 0f,
    ) {
        if (locked() || !centerX.isFinite() || !centerY.isFinite()) return
        // Map content stays geographically bound; photo modules may leave the canvas.
        if (_state.value.document.backgroundMode == PosterBackgroundMode.MAP) return
        hasPendingGesture = true
        captureGestureStart()
        _state.update { current ->
            val document = current.document
            val base = document.placementOf(module)
            val (moved, guides) = if (snapThresholdX > 0f || snapThresholdY > 0f) {
                val other = current.otherModuleRect(module, document)
                val result = PosterLayout.snapPlacement(
                    base.copy(centerX = centerX, centerY = centerY),
                    current.aspectOf(module, document),
                    current.safeAreaFor(document.backgroundMode),
                    other,
                    PosterLayout.SnapOptions(
                        thresholdX = snapThresholdX,
                        thresholdY = snapThresholdY,
                        lockedX = current.snapGuides?.x,
                        lockedY = current.snapGuides?.y))
                result.placement to result.guides.takeIf { it.x != null || it.y != null }
            } else {
                base.copy(centerX = centerX, centerY = centerY) to null
            }
            current.copy(
                document = document.withModule(module) { it.copy(centerX = moved.centerX, centerY = moved.centerY) },
                snapGuides = guides,
            )
        }
    }

    /** 等比缩放：只改归一化宽度，高度由固有比例推导。 */
    fun scaleModule(module: PosterModule, width: Float) {
        if (locked() || !width.isFinite()) return
        hasPendingGesture = true
        captureGestureStart()
        _state.update { current ->
            current.copy(document = current.document.withModule(module) { placement ->
                placement.copy(width = width.coerceIn(MIN_MODULE_WIDTH, MAX_MODULE_WIDTH))
            })
        }
    }

    private fun captureGestureStart() {
        if (gestureStartDocument == null) gestureStartDocument = _state.value.document
    }

    fun commitInteraction() {
        if (locked()) return
        _state.update { current ->
            val document = current.document
            var updated = document
            document.photoState?.let { photo ->
                updated = updated.copy(photoState = photo.copy(
                    crop = PosterCrop.clamp(photo.crop, photo.imageWidth.toFloat(), photo.imageHeight.toFloat(),
                        updated.canvasSpec().widthPx.toFloat(), updated.canvasSpec().heightPx.toFloat())))
            }
            if (updated == document && !hasPendingGesture) {
                current
            } else {
                hasPendingGesture = false
                // 手势开始时的文档才是这条历史的“编辑前”状态（plan §3.5）。
                recordHistory(gestureStartDocument ?: document)
                gestureStartDocument = null
                current.copy(
                    document = updated.copy(editRevision = document.editRevision + 1),
                    hasUnsavedEdits = true,
                    export = PosterExport.Idle,
                    snapGuides = null)
            }
        }
    }

    // ---------- 照片裁剪 ----------

    fun enterPhotoCrop() {
        val document = _state.value.document
        if (document.backgroundMode != PosterBackgroundMode.PHOTO || locked()) return
        photoCropBeforeAdjust = document.photoState?.crop
        _state.update { it.copy(interaction = PosterInteraction.PHOTO_CROP, selected = null) }
    }

    fun updateCrop(crop: PhotoCrop) {
        if (locked()) return
        hasPendingGesture = true
        captureGestureStart()
        _state.update { current ->
            val photo = current.document.photoState ?: return@update current
            current.copy(document = current.document.copy(photoState = photo.copy(crop = crop)))
        }
    }

    /** 取消裁剪恢复进入前状态（plan §3.3），不产生编辑历史。 */
    fun cancelPhotoCrop() {
        val restore = photoCropBeforeAdjust ?: return
        hasPendingGesture = false
        gestureStartDocument = null
        _state.update { current ->
            val photo = current.document.photoState ?: return@update current
            current.copy(interaction = PosterInteraction.SELECT,
                document = current.document.copy(photoState = photo.copy(crop = restore)))
        }
        photoCropBeforeAdjust = null
    }

    // ---------- 地图构图 ----------

    fun enterMapCompose() {
        if (_state.value.document.backgroundMode != PosterBackgroundMode.MAP || locked()) return
        _state.update { it.copy(interaction = PosterInteraction.MAP_COMPOSE, selected = null) }
    }

    fun transformOutline(panX: Float, panY: Float, zoom: Float, focusX: Float = .5f, focusY: Float = .5f) {
        if (locked() || _state.value.interaction != PosterInteraction.MAP_COMPOSE ||
            !listOf(panX, panY, zoom, focusX, focusY).all { it.isFinite() } || zoom <= 0) return
        captureGestureStart()
        hasPendingGesture = true
        _state.update { current ->
            val old = current.document.mapState
            val nextZoom = (old.outlineZoom * zoom).coerceIn(.65f, 4f)
            val factor = nextZoom / old.outlineZoom
            val next = old.copy(isAutoFit = false, outlineZoom = nextZoom,
                outlinePanX = ((old.outlinePanX - focusX + .5f) * factor + focusX - .5f + panX).coerceIn(-1.5f, 1.5f),
                outlinePanY = ((old.outlinePanY - focusY + .5f) * factor + focusY - .5f + panY).coerceIn(-1.5f, 1.5f))
            current.copy(document = current.document.copy(mapState = next), export = PosterExport.Idle)
        }
    }

    fun saveMapCamera(lat: Double, lon: Double, zoom: Double) {
        if (locked()) return
        val document = _state.value.document
        recordHistory(document)
        _state.update {
            it.copy(document = it.document.copy(
                mapState = PosterMapState(isAutoFit = false, centerLat = lat, centerLon = lon, zoom = zoom),
                editRevision = it.document.editRevision + 1),
                hasUnsavedEdits = true,
                export = PosterExport.Idle)
        }
    }

    fun requestMapFit() {
        if (locked()) return
        commitInteraction()
        val document = _state.value.document
        if (document.mapState.isAutoFit) return
        recordHistory(document)
        _state.update {
            it.copy(document = it.document.copy(mapState = PosterMapState(),
                editRevision = it.document.editRevision + 1))
        }
    }

    /** 地图宿主测量 SDK 标识/版权保留区后回填；布局修正不产生编辑历史。 */
    fun setMapReservedBottom(fraction: Float) {
        if (fraction !in 0.01f..0.3f) return
        _state.update { current ->
            if (current.mapReservedBottom == fraction) return@update current
            val safe = PosterLayout.safeArea(reservedBottom = fraction)
            val document = current.document
            document.mapInfoState?.let { state ->
                current.copy(mapReservedBottom = fraction, document = document.copy(mapInfoState = state.copy(
                    placement = PosterLayout.clampPlacement(state.placement, measureInfoAspect(state), safe))))
            } ?: current.copy(mapReservedBottom = fraction)
        }
    }

    fun registerMapCapture(capture: (suspend (PosterCanvasSpec) -> Bitmap?)?) {
        mapCapture = capture
    }

    fun updateAnnotationReport(report: PosterWireframeRenderer.AnnotationReport) {
        _state.update { it.copy(annotationReport = report) }
    }

    // ---------- 样式开关 / 预设 / 恢复默认（阶段 4） ----------

    /** 路线颜色主题：作用于当前背景（照片模块样式 / 地图路线样式），互不影响（plan §5.2）。 */
    fun setRouteTheme(theme: PosterRouteTheme) {
        mutateStyle { document ->
            when (document.backgroundMode) {
                PosterBackgroundMode.PHOTO -> document.photoRouteState?.let {
                    document.copy(photoRouteState = it.copy(theme = theme))
                }
                PosterBackgroundMode.MAP -> document.copy(mapRouteStyle = document.mapRouteStyle.copy(theme = theme))
            }
        }
    }

    fun setCheckinMarkers(visible: Boolean) {
        mutateStyle { document ->
            when (document.backgroundMode) {
                PosterBackgroundMode.PHOTO -> document.photoRouteState?.let {
                    document.copy(photoRouteState = it.copy(showCheckinMarkers = visible))
                }
                PosterBackgroundMode.MAP -> document.copy(
                    mapRouteStyle = document.mapRouteStyle.copy(showCheckinMarkers = visible))
            }
        }
    }

    fun setEndpointNames(visible: Boolean) {
        mutateStyle { document ->
            if (document.backgroundMode == PosterBackgroundMode.PHOTO) document.copy(
                photoRouteState = document.photoRouteState?.copy(showEndpointNames = visible))
            else document.copy(mapRouteStyle = document.mapRouteStyle.copy(showEndpointNames = visible))
        }
    }

    fun setMapOutline(visible: Boolean) {
        mutateStyle { document -> document.copy(photoRouteState = document.photoRouteState?.copy(showMapOutline = visible)) }
    }

    fun setInfoFontScale(value: Float, group: PosterTextGroup? = null) {
        if (locked() || !value.isFinite()) return
        val current = _state.value
        val info = current.document.photoInfoState ?: return
        if (current.document.backgroundMode != PosterBackgroundMode.PHOTO) return
        captureGestureStart()
        hasPendingGesture = true
        val scale = value.coerceIn(.8f, 1.6f)
        val document = current.document.copy(photoInfoState = info.copy(
            fontScale = if (group == PosterTextGroup.HEADER) info.fontScale else scale,
            titleFontScale = if (group == PosterTextGroup.STATS) info.titleFontScale else scale))
        _state.update { it.copy(document = withClampedInfo(document, current.safeAreaFor(PosterBackgroundMode.PHOTO)),
            export = PosterExport.Idle) }
    }

    fun setInfoTextColor(group: PosterTextGroup, color: PosterTextColor) {
        mutateStyle { document ->
            if (document.backgroundMode != PosterBackgroundMode.PHOTO) return@mutateStyle null
            val info = document.photoInfoState ?: return@mutateStyle null
            document.copy(photoInfoState = when (group) {
                PosterTextGroup.HEADER -> info.copy(titleColor = color)
                PosterTextGroup.STATS -> info.copy(statsColor = color)
            })
        }
    }

    fun setPreferredCheckins(names: Map<String, String>) {
        val points = _state.value.snapshot?.points?.filter { it.isCheckin }?.associateBy { it.id } ?: return
        val validated = names.entries.filter { it.key in points }.take(16).associate { (id, name) ->
            id to name.trim().take(40).ifBlank { PosterMapMarkers.checkinName(points.getValue(id)) }
        }
        mutateStyle { document -> document.copy(
            mapRouteStyle = document.mapRouteStyle.copy(preferredCheckins = validated),
            photoRouteState = document.photoRouteState?.copy(preferredCheckins = validated)) }
    }

    fun setSelectedCheckins(names: Map<String, String>, onSaved: () -> Unit = {}) {
        if (locked()) return
        val points = _state.value.snapshot?.points.orEmpty()
        val endpoints = setOf(points.firstOrNull()?.id, points.lastOrNull()?.id)
        val valid = points.filter { it.isCheckin && it.id !in endpoints }.associateBy { it.id }
        val chosen = names.filterKeys { it in valid }.mapValues { (id, name) ->
            name.trim().take(40).ifBlank { PosterMapMarkers.checkinName(valid.getValue(id)) }
        }
        syncAnnotationSelection(chosen.keys) {
            mutateStyle { document -> document.copy(
                mapRouteStyle = document.mapRouteStyle.copy(selectedCheckinIds = chosen.keys, preferredCheckins = chosen),
                photoRouteState = document.photoRouteState?.copy(selectedCheckinIds = chosen.keys, preferredCheckins = chosen)) }
            onSaved()
        }
    }

    fun dismissAnnotationError() = _state.update { it.copy(annotationSaveError = null) }

    private fun syncAnnotationSelection(selected: Set<String>, onSaved: () -> Unit) {
        if (locked()) return
        val snapshot = _state.value.snapshot ?: return
        val endpoints = setOf(snapshot.points.firstOrNull()?.id, snapshot.points.lastOrNull()?.id)
        val updates = snapshot.points.filter { it.isCheckin && it.id !in endpoints && it.shareAnnotation != (it.id in selected) }
            .map { ShareAnnotationPatch(it.id, it.id in selected) }
        if (updates.isEmpty()) { onSaved(); return }
        _state.update { it.copy(annotationSaving = true, annotationSaveError = null) }
        viewModelScope.launch {
            try {
                val saved = repository.updateShareAnnotations(trackId, updates).associateBy { it.point_id }
                val points = snapshot.points.map { point -> saved[point.id]?.let { point.copy(shareAnnotation = it.share_annotation) } ?: point }
                _state.update { it.copy(annotationSaving = false,
                    snapshot = snapshot.copy(points = points, days = com.niutrip.app.core.DayGrouper.group(points))) }
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(annotationSaveError = error.message ?: "分享标注保存失败，请重试") }
            } finally { _state.update { it.copy(annotationSaving = false) } }
        }
    }

    fun setInfoTextTheme(theme: PosterInfoTextTheme) {
        mutateStyle { document ->
            document.currentInfoState()?.let { state ->
                val updated = document.withInfoState(state.copy(textTheme = theme))
                // 主题影响信息模块固有比例，重新钳制位置。
                val safe = _state.value.safeAreaFor(document.backgroundMode)
                withClampedInfo(updated, safe)
            }
        }
    }

    fun setInfoFlag(flag: PosterInfoFlag, visible: Boolean) {
        mutateStyle { document ->
            document.currentInfoState()?.let { state ->
                val changed = when (flag) {
                    PosterInfoFlag.NAME -> state.copy(showName = visible)
                    PosterInfoFlag.DATE -> state.copy(showDate = visible)
                    PosterInfoFlag.AUX -> state.copy(showAux = visible)
                }
                val updated = document.withInfoState(changed)
                val safe = _state.value.safeAreaFor(document.backgroundMode)
                withClampedInfo(updated, safe)
            }
        }
    }

    /** 布局预设（plan §6.5）：仅照片模式，路线与信息整组重排。 */
    fun applyPhotoPreset(kind: PosterPresetKind) {
        mutateStyle { document ->
            if (document.backgroundMode != PosterBackgroundMode.PHOTO) return@mutateStyle null
            val routeAspect = _state.value.routeModel?.let(PosterRenderer::routeModuleAspect) ?: return@mutateStyle null
            val infoState = document.photoInfoState ?: return@mutateStyle null
            val safe = _state.value.safeAreaFor(PosterBackgroundMode.PHOTO)
            val (info, route) = PosterLayout.photoPresetPlacements(
                kind, routeAspect, measureInfoAspect(infoState), safe)
            document.copy(
                photoRouteState = document.photoRouteState?.copy(placement = route),
                photoInfoState = infoState.copy(placement = info))
        }
    }

    /** 恢复默认布局：只重置当前背景的模块位置、大小和样式，不换照片、不动地图相机（plan §3.5）。 */
    fun resetLayout() {
        mutateStyle { document ->
            val routeAspect = _state.value.routeModel?.let(PosterRenderer::routeModuleAspect) ?: return@mutateStyle null
            when (document.backgroundMode) {
                PosterBackgroundMode.PHOTO -> {
                    val safe = _state.value.safeAreaFor(PosterBackgroundMode.PHOTO)
                    val info = PosterLayout.clampPlacement(
                        ModulePlacement(0.5f, 0.3f, 0.88f),
                        measureInfoAspectDefaults(PosterInfoTextTheme.LIGHT), safe)
                    val route = PosterLayout.clampPlacement(
                        ModulePlacement(0.5f, 0.72f, 0.7f), routeAspect, safe)
                    document.copy(
                        photoRouteState = document.photoRouteState?.copy(
                            placement = route, theme = PosterRouteTheme.SINGLE, showCheckinMarkers = true,
                            showEndpointNames = true, showMapOutline = true),
                        photoInfoState = (document.photoInfoState ?: return@mutateStyle null).copy(
                            placement = info, textTheme = PosterInfoTextTheme.LIGHT,
                            showName = true, showDate = true, showAux = true, fontScale = 1f,
                            titleFontScale = 1f, titleColor = null, statsColor = null))
                }
                PosterBackgroundMode.MAP -> {
                    val safe = PosterLayout.safeArea(reservedBottom = _state.value.mapReservedBottom)
                    document.copy(
                        mapState = PosterMapState(),
                        mapRouteStyle = PosterRouteStyleState(preferredCheckins = document.mapRouteStyle.preferredCheckins,
                            selectedCheckinIds = document.mapRouteStyle.selectedCheckinIds),
                        mapInfoState = (document.mapInfoState ?: return@mutateStyle null).copy(
                            placement = PosterLayout.defaultMapPlacement(
                                measureInfoAspectDefaults(PosterInfoTextTheme.DARK), safe),
                            textTheme = PosterInfoTextTheme.DARK,
                            showName = true, showDate = true, showAux = true))
                }
            }
        }
    }

    /** 样式类修改的统一入口：记录历史、递增 revision、使导出结果失效。 */
    private fun mutateStyle(transform: (PosterDocument) -> PosterDocument?) {
        if (locked()) return
        val document = _state.value.document
        val updated = transform(document) ?: return
        if (updated == document) return
        recordHistory(document)
        _state.update {
            it.copy(
                document = updated.copy(editRevision = document.editRevision + 1),
                hasUnsavedEdits = true,
                export = PosterExport.Idle)
        }
    }

    // ---------- 导出（plan §5.4） ----------

    fun export() {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        if (locked() || current.endpointLookupInProgress ||
            (current.document.backgroundMode == PosterBackgroundMode.PHOTO && current.outlineLoading)) return
        exportJob?.cancel()
        val requestId = ++exportRequestId
        val frozen = current.document
        val endpointNames = current.endpointNames
        val spec = frozen.canvasSpec()
        _state.update { it.copy(export = PosterExport.Rendering(requestId)) }
        exportJob = viewModelScope.launch {
            var generatedFile: File? = null
            var keepFile = false
            try {
                val logo = androidx.core.content.ContextCompat.getDrawable(appContext, com.niutrip.app.R.mipmap.ic_launcher)
                val frozenModel = requireNotNull(current.routeModel)
                val file = withContext(ioDispatcher) {
                    val bitmap = when (frozen.backgroundMode) {
                        PosterBackgroundMode.PHOTO -> {
                            val resource = frozen.photoState?.resourceId?.let(photoResources::get)
                                ?: error("照片尚未就绪")
                            val maxSide = (maxOf(spec.widthPx, spec.heightPx) *
                                PosterCrop.MAX_ZOOM.toInt()).coerceAtMost(EXPORT_DECODE_MAX)
                            val decoded = resource.decode(maxDimension = maxSide) ?: error("无法读取图片")
                            try {
                                PosterRenderer.renderPhotoPoster(spec, frozen, snapshot, frozenModel, decoded,
                                    PosterWireframeRenderer.loadCheckinIcon(appContext), logo, endpointNames,
                                    current.photoOutline?.outline ?: PosterMapOutline.load(appContext))
                            } finally { decoded.recycle() }
                        }
                        PosterBackgroundMode.MAP -> {
                            PosterRenderer.renderWireframePoster(spec, frozen, snapshot, frozenModel, endpointNames,
                                PosterMapOutline.load(appContext), PosterWireframeRenderer.loadCheckinIcon(appContext))
                        }
                    }
                    try {
                        val directory = File(appContext.cacheDir, "track_shares")
                            .apply { check(isDirectory || mkdirs()) }
                        // 已发出的文件保留一天，避免接收应用延迟读取时 URI 失效（plan §11.3）。
                        directory.listFiles()?.filter {
                            it.isFile && it.lastModified() < System.currentTimeMillis() - 86_400_000
                        }?.forEach(File::delete)
                        PosterRenderer.writePng(bitmap, File.createTempFile("poster_", ".png", directory)).also { generatedFile = it }
                    } finally { bitmap.recycle() }
                }
                if (exportRequestId != requestId) {
                    file.delete() // 旧请求的结果只能释放自己的资源
                    return@launch
                }
                _state.update {
                    if (it.export is PosterExport.Rendering) it.copy(
                        export = PosterExport.Ready(file, frozen.editRevision),
                        hasUnsavedEdits = false)
                    else it
                }
                keepFile = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (exportRequestId == requestId) {
                    _state.update {
                        if (it.export is PosterExport.Rendering) it.copy(export = PosterExport.Error(error.message ?: "导出失败"))
                        else it
                    }
                }
            } finally { if (!keepFile) generatedFile?.delete() }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportRequestId++ // 后到的回调只能释放自己的资源
        _state.update {
            if (it.export is PosterExport.Rendering || it.export is PosterExport.Error) it.copy(export = PosterExport.Idle) else it
        }
    }

    fun dismissExportError() = _state.update {
        if (it.export is PosterExport.Error) it.copy(export = PosterExport.Idle) else it
    }

    fun markEditsConsumed() = _state.update { it.copy(hasUnsavedEdits = false) }

    override fun onCleared() {
        exportJob?.cancel()
        photoPreviews.values.forEach(Bitmap::recycle)
        photoPreviews.clear()
        // 会话资源清理；最终分享文件位于 track_shares，不受影响（plan §11.3）。
        PosterPhotoLoader.cleanup(appContext, sessionId)
    }

    // ---------- 内部 ----------

    private fun locked() = _state.value.export is PosterExport.Rendering || _state.value.annotationSaving

    private fun rememberPhoto(resource: PosterPhotoResource, preview: Bitmap) {
        photoResources[resource.id] = resource
        photoPreviews[resource.id] = preview
        trimPreviewCache()
    }

    /** 只回收最旧的预览位图，保留资源文件供历史引用（plan §3.5）。 */
    private fun trimPreviewCache() {
        while (photoPreviews.size > PREVIEW_CACHE_LIMIT) {
            val eldest = photoPreviews.keys.first()
            photoPreviews.remove(eldest)?.recycle()
        }
    }

    private fun PosterEditorState.safeAreaFor(mode: PosterBackgroundMode): NormRect =
        if (mode == PosterBackgroundMode.MAP) PosterLayout.safeArea(reservedBottom = mapReservedBottom)
        else PosterLayout.safeArea().copy(top = .12f)

    private fun PosterEditorState.aspectOf(module: PosterModule, document: PosterDocument): Float =
        when (module) {
            PosterModule.ROUTE -> routeModel?.let(PosterRenderer::routeModuleAspect) ?: 1f
            PosterModule.INFO -> document.currentInfoState()?.let(::measureInfoAspect) ?: 0.4f
        }

    /** 照片模式下另一模块的盒子（吸附目标）；地图模式无独立路线模块。 */
    private fun PosterEditorState.otherModuleRect(module: PosterModule, document: PosterDocument): NormRect? {
        if (document.backgroundMode != PosterBackgroundMode.PHOTO) return null
        return when (module) {
            PosterModule.ROUTE -> document.photoInfoState?.let {
                PosterLayout.moduleRect(it.placement, measureInfoAspect(it))
            }
            PosterModule.INFO -> routeModel?.let { model ->
                document.photoRouteState?.let {
                    PosterLayout.moduleRect(it.placement, PosterRenderer.routeModuleAspect(model))
                }
            }
        }
    }

    private fun PosterDocument.currentInfoState(): PosterInfoModuleState? = when (backgroundMode) {
        PosterBackgroundMode.PHOTO -> photoInfoState
        PosterBackgroundMode.MAP -> mapInfoState
    }

    private fun PosterDocument.withInfoState(state: PosterInfoModuleState): PosterDocument = when (backgroundMode) {
        PosterBackgroundMode.PHOTO -> copy(photoInfoState = state)
        PosterBackgroundMode.MAP -> copy(mapInfoState = state)
    }

    private fun withClampedInfo(document: PosterDocument, safe: NormRect): PosterDocument {
        if (document.backgroundMode == PosterBackgroundMode.PHOTO) return document
        val state = document.currentInfoState() ?: return document
        return document.withInfoState(state.copy(
            placement = PosterLayout.clampPlacement(state.placement, measureInfoAspect(state), safe)))
    }

    /** 信息模块 aspect 与宽度无关（尺寸全部由模块宽度等比推导），测量一次即可。 */
    private fun measureInfoAspect(state: PosterInfoModuleState): Float {
        val stats = _state.value.snapshot?.stats ?: return 0.4f
        return PosterRenderer.InfoLayout.measure(
            stats,
            PosterRenderer.infoStyle(state),
            REFERENCE_MODULE_WIDTH).aspect
    }

    /** 默认（全开）开关下的信息模块比例，用于初始化布局。 */
    private fun measureInfoAspectDefaults(theme: PosterInfoTextTheme): Float {
        val stats = _state.value.snapshot?.stats ?: return 0.4f
        return PosterRenderer.InfoLayout.measure(stats, PosterRenderer.InfoStyle(theme), REFERENCE_MODULE_WIDTH).aspect
    }

    private fun reclampForSpec(document: PosterDocument): PosterDocument {
        if (document.backgroundMode == PosterBackgroundMode.PHOTO) {
            val photo = document.photoState ?: return document
            val spec = document.canvasSpec()
            return document.copy(photoState = photo.copy(crop = PosterCrop.clamp(photo.crop,
                photo.imageWidth.toFloat(), photo.imageHeight.toFloat(), spec.widthPx.toFloat(), spec.heightPx.toFloat())))
        }
        val current = _state.value
        val safe = current.safeAreaFor(document.backgroundMode)
        var updated = document
        document.mapInfoState?.let { state ->
            updated = updated.copy(mapInfoState = state.copy(
                placement = PosterLayout.clampPlacement(state.placement, measureInfoAspect(state), safe)))
        }
        return updated
    }

    private fun PosterDocument.placementOf(module: PosterModule): ModulePlacement =
        when (backgroundMode) {
            PosterBackgroundMode.PHOTO -> when (module) {
                PosterModule.ROUTE -> photoRouteState?.placement
                PosterModule.INFO -> photoInfoState?.placement
            }
            PosterBackgroundMode.MAP -> when (module) {
                PosterModule.ROUTE -> null // 地图模式路线与地图地理绑定，不可拖动（plan §3.4）
                PosterModule.INFO -> mapInfoState?.placement
            }
        } ?: ModulePlacement(0.5f, 0.5f, 0.5f)

    private fun PosterDocument.withModule(
        module: PosterModule,
        transform: (ModulePlacement) -> ModulePlacement,
    ): PosterDocument = when (backgroundMode) {
        PosterBackgroundMode.PHOTO -> when (module) {
            PosterModule.ROUTE -> photoRouteState?.let { copy(photoRouteState = it.copy(placement = transform(it.placement))) } ?: this
            PosterModule.INFO -> photoInfoState?.let { copy(photoInfoState = it.copy(placement = transform(it.placement))) } ?: this
        }
        PosterBackgroundMode.MAP -> when (module) {
            PosterModule.ROUTE -> this
            PosterModule.INFO -> mapInfoState?.let { copy(mapInfoState = it.copy(placement = transform(it.placement))) } ?: this
        }
    }

    private fun PendingPointEntity.toPosterPointDto(): PointDto = PointDto(
        point_id = pointId,
        longitude = lon,
        latitude = lat,
        point_name = name,
        point_desc = desc,
        point_img_url = runCatching { Json.decodeFromString<List<String>>(imgs) }.getOrDefault(emptyList()),
        point_time = time.toBeijingDateTime().toApiTime(),
        point_source = source,
        share_annotation = shareAnnotation,
    )

    companion object {
        private const val PREVIEW_MAX_DIMENSION = 1440
        private const val PREVIEW_CACHE_LIMIT = 4
        private const val EXPORT_DECODE_MAX = 2160
        private const val REFERENCE_MODULE_WIDTH = 1080f
        private const val MIN_MODULE_WIDTH = 0.12f
        private const val MAX_MODULE_WIDTH = 0.96f
        /** 历史最多 30 条（plan §3.5）。 */
        private const val HISTORY_LIMIT = 30
    }
}
