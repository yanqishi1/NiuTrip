@file:OptIn(ExperimentalMaterial3Api::class)

package com.niutrip.app.ui.share

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas as NativeCanvas
import android.graphics.Matrix
import android.graphics.Paint as NativePaint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import com.niutrip.app.ui.theme.Danger
import java.io.File
import kotlin.math.hypot

/** 画布像素矩形（预览坐标）。 */
private data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    /** 角点手柄命中（点击范围 ≥48dp，plan §6.3）。 */
    fun isNearCorner(x: Float, y: Float, radius: Float): Boolean {
        val corners = listOf(left to top, right to top, left to bottom, right to bottom)
        return corners.any { (cx, cy) -> hypot((x - cx).toDouble(), (y - cy).toDouble()) <= radius }
    }
}

private data class ModuleBoxGeometry(val route: PxRect?, val info: PxRect?) {
    /** 重叠处优先选中信息（plan §3.3）；空白点击返回 null 取消选中。 */
    fun hit(x: Float, y: Float): PosterModule? = when {
        info?.contains(x, y) == true -> PosterModule.INFO
        route?.contains(x, y) == true -> PosterModule.ROUTE
        else -> null
    }

    fun rectOf(module: PosterModule): PxRect? = when (module) {
        PosterModule.ROUTE -> route
        PosterModule.INFO -> info
    }
}

private fun normRect(placement: ModulePlacement, aspect: Float, width: Float, height: Float): PxRect {
    val box = PosterLayout.moduleRect(placement, aspect)
    return PxRect(box.left * width, box.top * height, box.right * width, box.bottom * height)
}

private fun PosterDocument.placementOf(module: PosterModule): ModulePlacement = when (backgroundMode) {
    PosterBackgroundMode.PHOTO -> when (module) {
        PosterModule.ROUTE -> photoRouteState?.placement
        PosterModule.INFO -> photoInfoState?.placement
    }
    PosterBackgroundMode.MAP -> when (module) {
        PosterModule.ROUTE -> null // 地图模式路线与地图地理绑定，不可拖动（plan §3.4）
        PosterModule.INFO -> mapInfoState?.placement
    }
} ?: ModulePlacement(0.5f, 0.5f, 0.5f)

/**
 * 轨迹结算海报全屏编辑器（plan §3）：
 * 顶部返回/比例/保存/分享；画布承载背景（地图或照片）与两个可拖动模块；底部工具栏切换背景、
 * 进入照片裁剪或地图构图、选择模块。编辑中的画布手势与工具栏分离。
 * 工具栏（系统 UI）限制字体缩放：画布内文字为像素绘制不受系统字体影响，两者比例失配会显得按钮文字过大。
 */
@Composable
fun PosterEditorScreen(viewModel: PosterEditorViewModel, onExit: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val systemDensity = LocalDensity.current
    val cappedDensity = Density(systemDensity.density, fontScale = systemDensity.fontScale.coerceIn(0.9f, 1.1f))

    LaunchedEffect(viewModel) { viewModel.load() }

    var pendingAction by remember { mutableStateOf<String?>(null) } // "save" / "share"
    var showExitConfirm by remember { mutableStateOf(false) }
    var mapCamera by remember { mutableStateOf<Triple<Double, Double, Double>?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.pickPhoto(uri)
    }
    fun launchPhotoPicker() {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val ready = state.export as? PosterExport.Ready
        if (granted && ready != null) performGallerySave(context.applicationContext, ready.file)
        else if (!granted) Toast.makeText(context, "需要存储权限才能保存到相册", Toast.LENGTH_SHORT).show()
    }

    fun consumeReady(ready: PosterExport.Ready) {
        when (pendingAction) {
            "save" -> {
                if (Build.VERSION.SDK_INT >= 29 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    performGallerySave(context.applicationContext, ready.file)
                } else {
                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            "share" -> runCatching { TrackImageFiles.share(context, ready.file) }
                .onFailure { Toast.makeText(context, "无法打开分享应用", Toast.LENGTH_SHORT).show() }
        }
        pendingAction = null
    }

    fun requestExport(action: String) {
        pendingAction = action
        viewModel.export()
    }

    val export = state.export
    LaunchedEffect(export) {
        val ready = export as? PosterExport.Ready ?: return@LaunchedEffect
        consumeReady(ready)
    }

    BackHandler(true) {
        if (state.hasUnsavedEdits) showExitConfirm = true else onExit()
    }
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("放弃编辑？") },
            text = { Text("当前海报还未导出，退出后将丢失本次调整。") },
            confirmButton = { TextButton({ showExitConfirm = false; onExit() }) { Text("放弃编辑") } },
            dismissButton = { TextButton({ showExitConfirm = false }) { Text("继续编辑") } },
        )
    }

    CompositionLocalProvider(LocalDensity provides cappedDensity) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton({ if (state.hasUnsavedEdits) showExitConfirm = true else onExit() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                title = {
                    Text("轨迹海报", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    IconButton({ viewModel.undo() },
                        enabled = state.canUndo && export !is PosterExport.Rendering) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "撤销")
                    }
                    IconButton({ viewModel.redo() },
                        enabled = state.canRedo && export !is PosterExport.Rendering) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "重做")
                    }
                    RatioSelector(state.document, state.routeModel != null, viewModel::setRatio)
                    // 顶栏只放图标，避免动作按钮挤压标题导致逐字换行（真机反馈回归）。
                    val ready = export as? PosterExport.Ready
                    val readyUsable = ready != null && state.document.editRevision == ready.editRevision
                    IconButton(
                        enabled = export !is PosterExport.Rendering && state.phase is PosterDataPhase.Ready && !state.endpointLookupInProgress,
                        onClick = {
                            if (readyUsable) performGallerySave(context.applicationContext, ready!!.file)
                            else requestExport("save")
                        }) { Icon(Icons.Default.Download, "保存到相册") }
                    IconButton(
                        enabled = export !is PosterExport.Rendering && state.phase is PosterDataPhase.Ready && !state.endpointLookupInProgress,
                        onClick = {
                            if (readyUsable) runCatching { TrackImageFiles.share(context, ready!!.file) }
                            else requestExport("share")
                        }) { Icon(Icons.Default.IosShare, "分享") }
                })
        },
        bottomBar = { PosterToolbar(state, viewModel, ::launchPhotoPicker, mapCamera) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val phase = state.phase) {
                is PosterDataPhase.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is PosterDataPhase.Error -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(phase.message, color = Danger)
                    Button({ viewModel.load() }) { Text("重试") }
                }
                PosterDataPhase.Ready -> PosterCanvas(
                    Modifier.weight(1f).fillMaxWidth(),
                    state, viewModel,
                    onMapCamera = { lat, lon, zoom -> mapCamera = Triple(lat, lon, zoom) },
                )
            }
            when (val photoPhase = state.photoPhase) {
                is PosterPhotoPhase.Error -> Text(photoPhase.message, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Danger)
                PosterPhotoPhase.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                else -> Unit
            }
            when (val current = export) {
                is PosterExport.Rendering -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("正在合成海报…")
                        TextButton(viewModel::cancelExport) { Text("取消") }
                    }
                }
                is PosterExport.Error -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(current.message, color = Danger)
                    TextButton(viewModel::dismissExportError) { Text("知道了") }
                }
                else -> Unit
            }
        }
    }
    }
}

/** 保存到相册：复用 MediaStore add-only 流程；写盘移出主线程，失败提示可重试（plan §11.3）。 */
private fun performGallerySave(context: Context, file: File) {
    Toast.makeText(context, "开始保存到相册…", Toast.LENGTH_SHORT).show()
    Thread {
        runCatching { TrackImageFiles.saveToGallery(context, file) }
            .onSuccess { Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "图片已保存到相册", Toast.LENGTH_SHORT).show() } }
            .onFailure { error -> Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, error.message ?: "保存失败", Toast.LENGTH_LONG).show() } }
    }.start()
}

@Composable
private fun RatioSelector(document: PosterDocument, enabled: Boolean, onPick: (PosterRatioKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton({ expanded = true }, enabled = enabled) {
        Icon(Icons.Default.AspectRatio, "画布比例")
        DropdownMenu(expanded, { expanded = false }) {
            PosterRatioKind.entries
                .filterNot { it == PosterRatioKind.ORIGINAL && document.backgroundMode == PosterBackgroundMode.MAP }
                .forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(if (kind == PosterRatioKind.ORIGINAL) "原图比例" else kind.label) },
                        onClick = { expanded = false; onPick(kind) },
                        trailingIcon = { if (document.ratioKind == kind) RadioButton(true, null) })
                }
        }
    }
}

@Composable
private fun PosterToolbar(
    state: PosterEditorState,
    viewModel: PosterEditorViewModel,
    launchPhotoPicker: () -> Unit,
    mapCamera: Triple<Double, Double, Double>?,
) {
    val document = state.document
    Surface(tonalElevation = 2.dp) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 背景：地图 / 照片；照片需先成功选图才真正切换（plan §3.2）。
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = document.backgroundMode == PosterBackgroundMode.MAP,
                        onClick = { viewModel.setBackgroundMode(PosterBackgroundMode.MAP) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("地图", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                    SegmentedButton(
                        selected = document.backgroundMode == PosterBackgroundMode.PHOTO,
                        onClick = {
                            if (document.photoState == null) launchPhotoPicker()
                            else viewModel.setBackgroundMode(PosterBackgroundMode.PHOTO)
                        },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("照片", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                }
                if (document.backgroundMode == PosterBackgroundMode.PHOTO) {
                    TextButton(launchPhotoPicker) { Text(if (document.photoState == null) "选择照片" else "更换照片", style = MaterialTheme.typography.labelMedium) }
                }
            }
            when (state.interaction) {
                PosterInteraction.SELECT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (document.backgroundMode == PosterBackgroundMode.PHOTO) {
                        FilterChip(state.selected == PosterModule.ROUTE,
                            { viewModel.selectModule(if (state.selected == PosterModule.ROUTE) null else PosterModule.ROUTE) },
                            label = { Text("路线", style = MaterialTheme.typography.labelMedium) })
                    }
                    if (document.backgroundMode == PosterBackgroundMode.PHOTO) FilterChip(state.selected == PosterModule.INFO,
                        { viewModel.selectModule(if (state.selected == PosterModule.INFO) null else PosterModule.INFO) },
                        label = { Text("信息", style = MaterialTheme.typography.labelMedium) })
                    Spacer(Modifier.weight(1f))
                    if (document.backgroundMode == PosterBackgroundMode.PHOTO) {
                        TextButton(viewModel::enterPhotoCrop) { Text("调整照片") }
                    } else {
                        TextButton(viewModel::enterMapCompose) { Text("调整地图") }
                    }
                }
                PosterInteraction.PHOTO_CROP -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("移动或缩放照片，模块保持不动", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(viewModel::cancelPhotoCrop) { Text("取消") }
                    Button({ viewModel.commitInteraction(); viewModel.exitInteraction() }) { Text("完成") }
                }
                PosterInteraction.MAP_COMPOSE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("拖动或缩放地图调整构图", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(viewModel::requestMapFit) { Text("全程") }
                    Button({
                        val camera = mapCamera
                        if (camera != null) viewModel.saveMapCamera(camera.first, camera.second, camera.third)
                        else viewModel.exitInteraction()
                    }) { Text("完成") }
                }
            }
            PosterStyleControls(state, viewModel)
        }
    }
}

/** 预设、路线样式与信息开关（plan §1.3 / 阶段 4）。 */
@Composable
private fun PosterStyleControls(state: PosterEditorState, viewModel: PosterEditorViewModel) {
    val document = state.document
    val isPhoto = document.backgroundMode == PosterBackgroundMode.PHOTO
    val routeTheme = if (isPhoto) document.photoRouteState?.theme else document.mapRouteStyle.theme
    val showCheckins = if (isPhoto) document.photoRouteState?.showCheckinMarkers
    else document.mapRouteStyle.showCheckinMarkers
    val info = when (document.backgroundMode) {
        PosterBackgroundMode.PHOTO -> document.photoInfoState
        PosterBackgroundMode.MAP -> document.mapInfoState
    }
    if (state.interaction != PosterInteraction.SELECT || routeTheme == null || info == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isPhoto) {
            Text("预设", style = MaterialTheme.typography.labelMedium)
            TextButton({ viewModel.applyPhotoPreset(PosterPresetKind.TOP_BOTTOM) }) { Text("上下", style = MaterialTheme.typography.labelMedium) }
            TextButton({ viewModel.applyPhotoPreset(PosterPresetKind.LEFT_RIGHT) }) { Text("左右", style = MaterialTheme.typography.labelMedium) }
        }
        Spacer(Modifier.weight(1f))
        TextButton(viewModel::resetLayout) { Text("恢复默认", style = MaterialTheme.typography.labelMedium) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("路线", style = MaterialTheme.typography.labelMedium)
        FilterChip(routeTheme == PosterRouteTheme.SINGLE,
            { viewModel.setRouteTheme(PosterRouteTheme.SINGLE) }, label = { Text("纯色", style = MaterialTheme.typography.labelMedium) })
        FilterChip(routeTheme == PosterRouteTheme.BY_DAY,
            { viewModel.setRouteTheme(PosterRouteTheme.BY_DAY) }, label = { Text("按天分色", style = MaterialTheme.typography.labelMedium) })
        FilterChip(showCheckins == true,
            { viewModel.setCheckinMarkers(!(showCheckins == true)) }, label = { Text("打卡标记", style = MaterialTheme.typography.labelMedium) })
        val showNames = if (isPhoto) document.photoRouteState?.showEndpointNames == true else document.mapRouteStyle.showEndpointNames
        FilterChip(showNames,
            { viewModel.setEndpointNames(!showNames) },
            label = { Text("起终点名称", style = MaterialTheme.typography.labelMedium) })
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("信息", style = MaterialTheme.typography.labelMedium)
        FilterChip(info.showName, { viewModel.setInfoFlag(PosterInfoFlag.NAME, !info.showName) }, label = { Text("名称", style = MaterialTheme.typography.labelMedium) })
        FilterChip(info.showDate, { viewModel.setInfoFlag(PosterInfoFlag.DATE, !info.showDate) }, label = { Text("日期", style = MaterialTheme.typography.labelMedium) })
        FilterChip(info.showAux, { viewModel.setInfoFlag(PosterInfoFlag.AUX, !info.showAux) }, label = { Text("指标", style = MaterialTheme.typography.labelMedium) })
        FilterChip(info.textTheme == PosterInfoTextTheme.LIGHT,
            { viewModel.setInfoTextTheme(PosterInfoTextTheme.LIGHT) }, label = { Text("白字") })
        FilterChip(info.textTheme == PosterInfoTextTheme.DARK,
            { viewModel.setInfoTextTheme(PosterInfoTextTheme.DARK) }, label = { Text("黑字") })
    }
}

/** 画布容器：按导出比例约束预览区域，背景层（地图/照片）+ 模块层。地图模式画布分为信息带 + 地图区域。 */
@Composable
private fun PosterCanvas(
    modifier: Modifier,
    state: PosterEditorState,
    viewModel: PosterEditorViewModel,
    onMapCamera: (Double, Double, Double) -> Unit,
) {
    val document = state.document
    val snapshot = state.snapshot ?: return
    val spec = remember(document.ratioKind, document.photoState?.resourceId, document.photoState?.imageWidth, document.photoState?.imageHeight) {
        document.canvasSpec()
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    // 地图模式分区（用户要求）：信息在地图上方独立展示，不叠加。
    val mapInfoState = document.mapInfoState
    val mapInfoAspect = remember(snapshot, mapInfoState?.textTheme, mapInfoState?.showName, mapInfoState?.showDate, mapInfoState?.showAux) {
        mapInfoState?.let {
            PosterRenderer.InfoLayout.measure(
                snapshot.stats,
                PosterRenderer.InfoStyle(it.textTheme,
                    showName = it.showName, showDate = it.showDate, showAux = it.showAux),
                1080f).aspect
        }
    }
    val mapRegions = remember(document.backgroundMode, mapInfoState?.placement?.width, mapInfoAspect, spec) {
        if (document.backgroundMode == PosterBackgroundMode.MAP) {
            PosterLayout.mapModeRegions(mapInfoState?.placement?.width ?: 0.8f, mapInfoAspect ?: 0.4f, spec.widthPx, spec.heightPx)
        } else null
    }

    BoxWithConstraints(modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
        val ratio = spec.widthPx.toFloat() / spec.heightPx
        val previewWidth = minOf(maxWidth, maxHeight * ratio)
        Box(
            Modifier
                .size(previewWidth, previewWidth / ratio)
                .onSizeChanged { canvasSize = it },
        ) {
            if (document.backgroundMode == PosterBackgroundMode.MAP && mapRegions != null && canvasSize.width > 0) {
                val mapRegion = mapRegions.second
                val mapLeft = mapRegion.left * canvasSize.width
                val mapTop = mapRegion.top * canvasSize.height
                val mapWidth = mapRegion.width * canvasSize.width
                val mapHeight = mapRegion.height * canvasSize.height
                PosterMapHost(
                    days = snapshot.days,
                    mapState = document.mapState,
                    canvasAspect = mapWidth / mapHeight,
                    interactive = state.interaction == PosterInteraction.MAP_COMPOSE,
                    modifier = Modifier
                        .absoluteOffset { IntOffset(mapLeft.roundToInt(), mapTop.roundToInt()) }
                        .size(with(density) { mapWidth.toDp() }, with(density) { mapHeight.toDp() }),
                    routeStyle = document.mapRouteStyle,
                    endpointNames = state.endpointNames,
                    onCameraPosition = onMapCamera,
                    onReservedBottomMeasured = viewModel::setMapReservedBottom,
                    registerCapture = viewModel::registerMapCapture,
                )
            }
            PosterCanvasOverlay(state, viewModel, canvasSize, mapRegions)
        }
    }
}

/** 模块绘制 + 选择辅助层 + 手势；地图构图或导出中整层不拦截手势（plan §6.4 / §5.4）。 */
@Composable
private fun PosterCanvasOverlay(
    state: PosterEditorState,
    viewModel: PosterEditorViewModel,
    canvasSize: IntSize,
    mapRegions: Pair<NormRect, NormRect>?,
) {
    val snapshot = state.snapshot ?: return
    val routeModel = state.routeModel
    val width = canvasSize.width.toFloat()
    val height = canvasSize.height.toFloat()
    if (width <= 0f || height <= 0f) return
    val context = LocalContext.current
    val brandLogo = remember { ContextCompat.getDrawable(context, com.niutrip.app.R.mipmap.ic_launcher) }

    // pointerInput 的 lambda 跨重组存活，必须经 rememberUpdatedState 读取最新文档。
    val document by rememberUpdatedState(state.document)
    val interaction by rememberUpdatedState(state.interaction)
    val selected by rememberUpdatedState(state.selected)
    val photoPreview by rememberUpdatedState(state.photoPreview)

    val routeAspect = routeModel?.let(PosterRenderer::routeModuleAspect)
    val infoAspectFor: (PosterInfoModuleState) -> Float = { infoState ->
        PosterRenderer.InfoLayout.measure(
            snapshot.stats,
            PosterRenderer.InfoStyle(
                infoState.textTheme,
                showName = infoState.showName,
                showDate = infoState.showDate,
                showAux = infoState.showAux),
            1080f).aspect
    }

    fun geometryOf(doc: PosterDocument): ModuleBoxGeometry {
        val routeRect = doc.photoRouteState
            ?.takeIf { doc.backgroundMode == PosterBackgroundMode.PHOTO }
            ?.let { normRect(it.placement, routeAspect ?: 1f, width, height) }
        val infoRect = when (doc.backgroundMode) {
            PosterBackgroundMode.PHOTO -> doc.photoInfoState?.let { normRect(it.placement, infoAspectFor(it), width, height) }
            // 地图模式：信息位置由画布分区派生（信息带在地图上方）。
            PosterBackgroundMode.MAP -> mapRegions?.first?.let {
                PxRect(it.left * width, it.top * height, it.right * width, it.bottom * height)
            }
        }
        return ModuleBoxGeometry(routeRect, infoRect)
    }

    val gestureEnabled = state.export !is PosterExport.Rendering &&
        (interaction == PosterInteraction.SELECT || interaction == PosterInteraction.PHOTO_CROP)
    // 吸附阈值：屏幕 6dp 换算为画布像素（plan §6.4）。
    val density = LocalDensity.current
    val snapThresholdPx = with(density) { 6.dp.toPx() }
    val cornerTouchPx = with(density) { 24.dp.toPx() }
    val snapGuides by rememberUpdatedState(state.snapGuides)

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(gestureEnabled, document.backgroundMode, width, height) {
                if (!gestureEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startDocument = document
                    when (interaction) {
                        PosterInteraction.PHOTO_CROP -> photoCropGesture(viewModel, startDocument, width, height)
                        PosterInteraction.SELECT -> selectGesture(
                            viewModel, startDocument, geometryOf(startDocument),
                            startDocument.backgroundMode, width, height, snapThresholdPx, cornerTouchPx,
                            selected, down)
                        PosterInteraction.MAP_COMPOSE -> Unit
                    }
                }
            },
    ) {
        val native = drawContext.canvas.nativeCanvas
        val doc = document
        if (doc.backgroundMode == PosterBackgroundMode.PHOTO) {
            // 背景照片：预览使用裁剪算式的低分辨率资源，导出同规则更高分辨率（plan §11.2）。
            photoPreview?.let { bitmap ->
                val photoState = doc.photoState ?: return@let
                val draw = PosterCrop.draw(
                    photoState.crop, bitmap.width.toFloat(), bitmap.height.toFloat(), width, height)
                val matrix = Matrix().apply {
                    setScale(draw.scale, draw.scale); postTranslate(draw.transX, draw.transY)
                }
                native.drawBitmap(bitmap, matrix, NativePaint(NativePaint.FILTER_BITMAP_FLAG))
            }
            if (routeModel != null) {
                doc.photoRouteState?.let { routeState ->
                    val box = normRect(routeState.placement, routeAspect ?: 1f, width, height)
                    PosterRenderer.drawRouteModule(
                        native, routeModel, box.left, box.top, box.width, box.height,
                        PosterRenderer.RouteStyle(routeState.theme, routeState.showCheckinMarkers,
                            routeState.showEndpointNames, state.endpointNames))
                }
            }
        }
        when (doc.backgroundMode) {
            PosterBackgroundMode.PHOTO -> doc.photoInfoState?.let { info ->
                drawInfo(native, snapshot, info, infoAspectFor(info), width, height)
            }
            PosterBackgroundMode.MAP -> doc.mapInfoState?.let { info ->
                mapRegions?.let { (infoRegion, _) ->
                    PosterRenderer.drawMapSummary(native, snapshot.stats, info,
                        infoRegion.left * width, infoRegion.top * height,
                        infoRegion.width * width, infoRegion.height * height, brandLogo)
                }
            }
        }
        // 品牌水印固定左上角：预览与导出共用同一绘制（plan §11.2 所见即所得）。
        if (doc.backgroundMode == PosterBackgroundMode.PHOTO) PosterRenderer.drawBrandCorner(native, brandLogo,
            when (doc.backgroundMode) {
                PosterBackgroundMode.PHOTO -> doc.photoInfoState?.textTheme ?: PosterInfoTextTheme.LIGHT
                PosterBackgroundMode.MAP -> doc.mapInfoState?.textTheme ?: PosterInfoTextTheme.DARK
            }, width, height)
        // 编辑辅助层只在屏幕显示，不进入导出（plan §11.1）。
        if (interaction == PosterInteraction.SELECT) {
            val geometry = geometryOf(doc)
            geometry.route?.takeIf { selected == PosterModule.ROUTE }?.let { drawSelection(it) }
            geometry.info?.takeIf { selected == PosterModule.INFO && doc.backgroundMode == PosterBackgroundMode.PHOTO }?.let { drawSelection(it) }
        }
        // 吸附参考线：拖动中显示，提交后清除（plan §6.4）。
        snapGuides?.let { guides ->
            val lineColor = Color(0xFF00B96B)
            guides.x?.let { x ->
                drawLine(lineColor, Offset(x * width, 0f), Offset(x * width, height), strokeWidth = 1.dp.toPx())
            }
            guides.y?.let { y ->
                drawLine(lineColor, Offset(0f, y * height), Offset(width, y * height), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

private fun drawInfo(
    native: NativeCanvas,
    snapshot: PosterTrackSnapshot,
    info: PosterInfoModuleState,
    aspect: Float,
    width: Float,
    height: Float,
) {
    val moduleWidthPx = info.placement.width * width
    val layout = PosterRenderer.InfoLayout.measure(
        snapshot.stats,
        PosterRenderer.InfoStyle(
            info.textTheme,
            showName = info.showName,
            showDate = info.showDate,
            showAux = info.showAux),
        moduleWidthPx)
    layout.draw(native,
        info.placement.centerX * width - moduleWidthPx / 2f,
        info.placement.centerY * height - moduleWidthPx * aspect / 2f)
}

/**
 * 照片裁剪手势（plan §7.2）：单指平移、双指以焦点缩放；
 * 缩放目标从手势起始值计算，不逐帧累加。结束时提交一次编辑。
 */
private suspend fun AwaitPointerEventScope.photoCropGesture(
    viewModel: PosterEditorViewModel,
    startDocument: PosterDocument,
    width: Float,
    height: Float,
) {
    val photo = startDocument.photoState ?: return
    val startCrop = photo.crop
    var latestCrop = startCrop
    var startDistance = -1f
    var moved = false
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) break
        if (pressed.size >= 2) {
            val distance = hypot(
                (pressed[0].position.x - pressed[1].position.x).toDouble(),
                (pressed[0].position.y - pressed[1].position.y).toDouble()).toFloat()
            if (startDistance < 0f) startDistance = distance
            if (startDistance > 10f) {
                latestCrop = PosterCrop.zoomAbout(
                    latestCrop,
                    startCrop.zoom * distance / startDistance,
                    (pressed[0].position.x + pressed[1].position.x) / 2f,
                    (pressed[0].position.y + pressed[1].position.y) / 2f,
                    photo.imageWidth.toFloat(), photo.imageHeight.toFloat(), width, height)
                viewModel.updateCrop(latestCrop)
                moved = true
            }
        } else if (pressed.size == 1) {
            val delta = pressed[0].positionChange()
            if (delta != Offset.Zero) {
                latestCrop = latestCrop.copy(
                    offsetX = latestCrop.offsetX + delta.x / width,
                    offsetY = latestCrop.offsetY + delta.y / height)
                viewModel.updateCrop(latestCrop)
                moved = true
            }
        }
    }
    if (moved) viewModel.commitInteraction()
}

/**
 * 模块选择 + 拖动 + 缩放（plan §6.4）：
 * 缩放两条路径 —— 已选中模块的角点手柄拖拽（可见、易发现）与双指捏合；
 * 位置和缩放都从手势起始值计算，绝对设置，避免逐帧累加漂移；
 * 接近对齐目标时吸附并输出参考线。
 */
private suspend fun AwaitPointerEventScope.selectGesture(
    viewModel: PosterEditorViewModel,
    startDocument: PosterDocument,
    geometry: ModuleBoxGeometry,
    backgroundMode: PosterBackgroundMode,
    width: Float,
    height: Float,
    snapThresholdPx: Float,
    cornerTouchPx: Float,
    selectedModule: PosterModule?,
    down: PointerInputChange,
) {
    // 1) 角点手柄：按住已选中模块的角拖拽缩放，中心不动（plan §6.3 手柄点击范围 ≥48dp）。
    val selectedRect = selectedModule?.let(geometry::rectOf)
    val draggableSelected = selectedModule != null && backgroundMode == PosterBackgroundMode.PHOTO
    if (selectedRect != null && draggableSelected &&
        selectedRect.isNearCorner(down.position.x, down.position.y, cornerTouchPx)) {
        val module = selectedModule!!
        val centerX = (selectedRect.left + selectedRect.right) / 2f
        val centerY = (selectedRect.top + selectedRect.bottom) / 2f
        val startDistance = hypot((down.position.x - centerX).toDouble(),
            (down.position.y - centerY).toDouble()).toFloat().coerceAtLeast(1f)
        val startWidth = startDocument.placementOf(module).width
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size == 1) {
                val position = pressed[0].position
                val distance = hypot((position.x - centerX).toDouble(),
                    (position.y - centerY).toDouble()).toFloat()
                if (distance > 1f) {
                    viewModel.scaleModule(module, startWidth * distance / startDistance)
                    moved = true
                }
            }
        }
        if (moved) viewModel.commitInteraction()
        return
    }
    // 2) 常规：点选 → 单指拖动 / 双指等比缩放。
    val module = geometry.hit(down.position.x, down.position.y)
    viewModel.selectModule(module)
    if (module == null) return
    if (backgroundMode == PosterBackgroundMode.MAP) return
    val snapThresholdX = if (width > 0f) snapThresholdPx / width else 0f
    val snapThresholdY = if (height > 0f) snapThresholdPx / height else 0f
    val startPlacement = startDocument.placementOf(module)
    val startWidth = startPlacement.width
    var startDistance = -1f
    var moved = false
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) break
        if (pressed.size >= 2) {
            val distance = hypot(
                (pressed[0].position.x - pressed[1].position.x).toDouble(),
                (pressed[0].position.y - pressed[1].position.y).toDouble()).toFloat()
            if (startDistance < 0f) startDistance = distance
            if (startDistance > 10f) {
                viewModel.scaleModule(module, startWidth * distance / startDistance)
                moved = true
            }
        } else if (pressed.size == 1) {
            val delta = pressed[0].position - down.position
            if (delta != Offset.Zero) {
                viewModel.moveModule(
                    module,
                    startPlacement.centerX + delta.x / width,
                    startPlacement.centerY + delta.y / height,
                    snapThresholdX = snapThresholdX,
                    snapThresholdY = snapThresholdY)
                moved = true
            }
        }
    }
    if (moved) viewModel.commitInteraction()
}

private fun DrawScope.drawSelection(rect: PxRect) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
    drawRect(
        Color.White,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        style = Stroke(width = 2.dp.toPx(), pathEffect = dash),
    )
    drawRect(
        Color.Black,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        alpha = 0.6f,
        style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
    )
    // 角点缩放手柄：白底黑圈小圆点，可拖拽等比缩放（可见性提示）。
    val radius = 5.dp.toPx()
    listOf(
        Offset(rect.left, rect.top),
        Offset(rect.right, rect.top),
        Offset(rect.left, rect.bottom),
        Offset(rect.right, rect.bottom),
    ).forEach { corner ->
        drawCircle(Color.White, radius, corner)
        drawCircle(Color(0xFF18281C), radius, corner, style = Stroke(1.5.dp.toPx()))
    }
}
