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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.ceil

/** 画布像素矩形（预览坐标）。 */
internal data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    /** 角点手柄命中（点击范围 ≥48dp，plan §6.3）。 */
    fun isNearCorner(x: Float, y: Float, radius: Float): Boolean {
        val corners = listOf(left to top, right to top, left to bottom, right to bottom)
        return corners.any { (cx, cy) -> hypot((x - cx).toDouble(), (y - cy).toDouble()) <= radius }
    }
}

internal data class ModuleBoxGeometry(val route: PxRect?, val info: PxRect?) {
    /** A selected module remains draggable when it overlaps the other module. */
    fun hit(x: Float, y: Float, selected: PosterModule?): PosterModule? = when {
        selected != null && rectOf(selected)?.contains(x, y) == true -> selected
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
        if (!state.annotationSaving) {
            if (state.hasUnsavedEdits) showExitConfirm = true else onExit()
        }
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
                    IconButton({ if (state.hasUnsavedEdits) showExitConfirm = true else onExit() }, enabled = !state.annotationSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                title = {
                    Text("轨迹海报", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    IconButton({ viewModel.undo() },
                        enabled = state.canUndo && export !is PosterExport.Rendering && !state.annotationSaving) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "撤销")
                    }
                    IconButton({ viewModel.redo() },
                        enabled = state.canRedo && export !is PosterExport.Rendering && !state.annotationSaving) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "重做")
                    }
                    RatioSelector(state.document, state.routeModel != null, viewModel::setRatio)
                    // 顶栏只放图标，避免动作按钮挤压标题导致逐字换行（真机反馈回归）。
                    val ready = export as? PosterExport.Ready
                    val readyUsable = ready != null && state.document.editRevision == ready.editRevision
                    IconButton(
                        enabled = export !is PosterExport.Rendering && state.phase is PosterDataPhase.Ready && !state.endpointLookupInProgress && !state.annotationSaving && !(state.document.backgroundMode == PosterBackgroundMode.PHOTO && state.outlineLoading),
                        onClick = {
                            if (readyUsable) performGallerySave(context.applicationContext, ready!!.file)
                            else requestExport("save")
                        }) { Icon(Icons.Default.Download, "保存到相册") }
                    IconButton(
                        enabled = export !is PosterExport.Rendering && state.phase is PosterDataPhase.Ready && !state.endpointLookupInProgress && !state.annotationSaving && !(state.document.backgroundMode == PosterBackgroundMode.PHOTO && state.outlineLoading),
                        onClick = {
                            if (readyUsable) runCatching { TrackImageFiles.share(context, ready!!.file) }
                            else requestExport("share")
                        }) { Icon(Icons.Default.IosShare, "分享") }
                })
        },
        bottomBar = { PosterToolbar(state, viewModel, ::launchPhotoPicker) },
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
) {
    val document = state.document
    val isPhoto = document.backgroundMode == PosterBackgroundMode.PHOTO
    val enabled = state.phase is PosterDataPhase.Ready && state.export !is PosterExport.Rendering && !state.annotationSaving
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(state.selected) {
        if (state.selected == PosterModule.INFO) tab = 1
        else if (state.selected == PosterModule.ROUTE) tab = 0
    }
    val panelHeight = (LocalConfiguration.current.screenHeightDp * .24f).coerceIn(92f, 176f).dp
    Surface(shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(
                        selected = !isPhoto, enabled = enabled,
                        onClick = { viewModel.setBackgroundMode(PosterBackgroundMode.MAP) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        colors = posterSegmentColors(),
                        icon = { Icon(Icons.Default.Map, null, Modifier.size(18.dp)) },
                    ) { Text("地图") }
                    SegmentedButton(
                        selected = isPhoto, enabled = enabled,
                        onClick = {
                            if (document.photoState == null) launchPhotoPicker()
                            else viewModel.setBackgroundMode(PosterBackgroundMode.PHOTO)
                        },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        colors = posterSegmentColors(),
                        icon = { Icon(Icons.Default.Photo, null, Modifier.size(18.dp)) },
                    ) { Text("图片") }
                }
                if (isPhoto) {
                    PosterToolIcon(Icons.Default.Photo, "更换照片", enabled, launchPhotoPicker)
                    PosterToolIcon(Icons.Default.Crop, "裁剪照片", enabled, viewModel::enterPhotoCrop)
                } else {
                    PosterToolIcon(Icons.Default.Tune, "地图构图", enabled, viewModel::enterMapCompose)
                }
            }
            when (state.interaction) {
                PosterInteraction.SELECT -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.surface) {
                        listOf("路线" to Icons.Default.Route, "文字" to Icons.Default.TextFields)
                            .forEachIndexed { index, (title, icon) ->
                                Tab(selected = tab == index, enabled = enabled,
                                    selectedContentColor = MaterialTheme.colorScheme.primary,
                                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, onClick = {
                                    viewModel.commitInteraction()
                                    tab = index
                                    if (isPhoto) viewModel.selectModule(if (index == 0) PosterModule.ROUTE else PosterModule.INFO)
                                }) {
                                    Row(Modifier.height(42.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(icon, null, Modifier.size(18.dp)); Text(title, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                    }
                    PosterToolIcon(Icons.Default.RestartAlt, "恢复默认布局", enabled, viewModel::resetLayout)
                    }
                    Column(Modifier.fillMaxWidth().height(panelHeight).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PosterStyleControls(state, viewModel, tab, enabled)
                    }
                }
                PosterInteraction.PHOTO_CROP -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("照片裁剪", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    TextButton(viewModel::cancelPhotoCrop) { Text("取消") }
                    FilledTonalButton({ viewModel.commitInteraction(); viewModel.exitInteraction() },
                        colors = posterTonalColors(), shape = RoundedCornerShape(8.dp)) { Text("完成") }
                }
                PosterInteraction.MAP_COMPOSE -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("地图构图", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    PosterToolIcon(Icons.Default.FitScreen, "显示全程", enabled, viewModel::requestMapFit)
                    FilledTonalButton({
                        viewModel.commitInteraction()
                        viewModel.exitInteraction()
                    }, colors = posterTonalColors(), shape = RoundedCornerShape(8.dp)) { Text("完成") }
                }
            }
        }
    }
}

@Composable
private fun posterSegmentColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    inactiveContainerColor = MaterialTheme.colorScheme.surface,
    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun posterTonalColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer)

@Composable
private fun PosterToolIcon(icon: ImageVector, name: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(name) } }, state = rememberTooltipState()) {
        IconButton(onClick, enabled = enabled) { Icon(icon, name, Modifier.size(22.dp)) }
    }
}

@Composable
private fun PosterToggle(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun PosterStyleControls(state: PosterEditorState, viewModel: PosterEditorViewModel, tab: Int, enabled: Boolean) {
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
    var choosingCheckins by remember { mutableStateOf(false) }
    if (choosingCheckins) PosterCheckinDialog(state, onDismiss = { choosingCheckins = false }) { names ->
        viewModel.setSelectedCheckins(names) { choosingCheckins = false }
    }
    if (!choosingCheckins && state.annotationSaveError != null) AlertDialog(
        onDismissRequest = viewModel::dismissAnnotationError,
        title = { Text("标注保存失败") }, text = { Text(state.annotationSaveError) },
        confirmButton = { TextButton(viewModel::dismissAnnotationError) { Text("知道了") } })
    if (tab == 0) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("纯色路线" to PosterRouteTheme.SINGLE, "按天分色" to PosterRouteTheme.BY_DAY).forEachIndexed { index, (label, theme) ->
                SegmentedButton(selected = routeTheme == theme, onClick = { viewModel.setRouteTheme(theme) }, enabled = enabled,
                    colors = posterSegmentColors(), shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }
        FilledTonalButton({ viewModel.dismissAnnotationError(); choosingCheckins = true }, enabled = enabled,
            colors = posterTonalColors(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
            Icon(Icons.Default.Place, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("标注打卡点 · ${document.mapRouteStyle.selectedCheckinIds?.size ?: 0}")
        }
        val showNames = if (isPhoto) document.photoRouteState?.showEndpointNames == true else document.mapRouteStyle.showEndpointNames
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PosterToggle("打卡标记", showCheckins == true, enabled, Modifier.weight(1f), viewModel::setCheckinMarkers)
            PosterToggle("起终点名称", showNames, enabled, Modifier.weight(1f), viewModel::setEndpointNames)
        }
        if (isPhoto) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PosterToggle("地图轮廓", document.photoRouteState?.showMapOutline == true, enabled,
                Modifier.weight(1f), viewModel::setMapOutline)
            Text(if (state.outlineLoading) "加载中" else state.photoOutline?.level ?: "省界",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.photoOutline?.warning != null) PosterToolIcon(Icons.Default.Refresh, "重试地图轮廓", enabled && !state.outlineLoading, viewModel::loadPhotoOutline)
        }
        if (state.annotationReport.selected > state.annotationReport.named) Text(
            "${state.annotationReport.named} 个名称 · ${state.annotationReport.selected - state.annotationReport.named} 个仅显示位置",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isPhoto) state.photoOutline?.warning?.let { Text(it, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else if (tab == 1) {
        if (isPhoto) {
            var expandedColorGroup by remember { mutableStateOf<PosterTextGroup?>(null) }
            PosterPhotoTextControls(info, enabled, expandedColorGroup,
                onExpand = { expandedColorGroup = if (expandedColorGroup == it) null else it },
                onScale = viewModel::setInfoFontScale,
                onScaleFinished = viewModel::commitInteraction,
                onColor = viewModel::setInfoTextColor)
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("文字颜色", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            listOf(PosterInfoTextTheme.LIGHT to Color.White, PosterInfoTextTheme.DARK to Color(0xFF202C30)).forEach { (theme, color) ->
                TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(if (theme == PosterInfoTextTheme.LIGHT) "白字" else "黑字") } }, state = rememberTooltipState()) {
                    IconButton({ viewModel.setInfoTextTheme(theme) }, enabled = enabled) {
                        Box(Modifier.size(30.dp).background(color, CircleShape).border(
                            if (info.textTheme == theme) 2.dp else 1.dp,
                            if (info.textTheme == theme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                            contentAlignment = Alignment.Center) {
                            if (info.textTheme == theme) Icon(Icons.Default.Check, if (theme == PosterInfoTextTheme.LIGHT) "白字" else "黑字",
                                Modifier.size(18.dp), tint = if (theme == PosterInfoTextTheme.LIGHT) Color.Black else Color.White)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(Triple("名称", PosterInfoFlag.NAME, info.showName), Triple("日期", PosterInfoFlag.DATE, info.showDate),
                Triple("统计", PosterInfoFlag.AUX, info.showAux)).forEach { (label, flag, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(value, { viewModel.setInfoFlag(flag, it) }, enabled = enabled)
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun PosterPhotoTextControls(
    info: PosterInfoModuleState,
    enabled: Boolean,
    expandedColorGroup: PosterTextGroup?,
    onExpand: (PosterTextGroup) -> Unit,
    onScale: (Float, PosterTextGroup) -> Unit,
    onScaleFinished: () -> Unit,
    onColor: (PosterTextGroup, PosterTextColor) -> Unit,
) {
    val defaultColor = if (info.textTheme == PosterInfoTextTheme.LIGHT) PosterTextColor.WHITE else PosterTextColor.BLACK
    PosterTextGroup.entries.forEach { group ->
        val label = if (group == PosterTextGroup.HEADER) "标题与时间" else "行程数据"
        val scale = if (group == PosterTextGroup.HEADER) info.titleFontScale else info.fontScale
        val selectedColor = (if (group == PosterTextGroup.HEADER) info.titleColor else info.statsColor) ?: defaultColor
        val expanded = expandedColorGroup == group
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
            Slider(scale, { onScale(it, group) }, enabled = enabled, valueRange = .8f..1.6f,
                onValueChangeFinished = onScaleFinished,
                modifier = Modifier.weight(1f).semantics { contentDescription = "${label}字号" })
            Text("${(scale * 100).roundToInt()}%", Modifier.width(38.dp), style = MaterialTheme.typography.labelSmall)
            TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("${label}颜色") } }, state = rememberTooltipState()) {
                IconButton({ onExpand(group) }, enabled = enabled, modifier = Modifier.semantics {
                    contentDescription = "${label}颜色：${selectedColor.label}"
                    stateDescription = if (expanded) "已展开" else "已收起"
                }) {
                    Box(Modifier.size(28.dp).background(Color(selectedColor.argb), CircleShape)
                        .border(if (expanded) 2.dp else 1.dp,
                            if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                }
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(220)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(160))) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PosterTextColor.entries.forEach { color ->
                    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(color.label) } }, state = rememberTooltipState()) {
                        IconButton({ onColor(group, color) }, enabled = enabled, modifier = Modifier.semantics {
                            contentDescription = "${label}${color.label}"
                            stateDescription = if (selectedColor == color) "已选择" else "未选择"
                        }) {
                            Box(Modifier.size(30.dp).background(Color(color.argb), CircleShape)
                                .border(if (selectedColor == color) 2.dp else 1.dp,
                                    if (selectedColor == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    CircleShape), contentAlignment = Alignment.Center) {
                                if (selectedColor == color) Icon(Icons.Default.Check, null, Modifier.size(18.dp),
                                    tint = if (Color(color.argb).luminance() > .45f) Color.Black else Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCheckinDialog(state: PosterEditorState, onDismiss: () -> Unit, onApply: (Map<String, String>) -> Unit) {
    val endpoints = setOf(state.snapshot?.points?.firstOrNull()?.id, state.snapshot?.points?.lastOrNull()?.id)
    val points = state.snapshot?.points.orEmpty().filter { it.isCheckin && it.id !in endpoints }.distinctBy { it.id }
    var draft by remember {
        mutableStateOf(points.filter { point -> state.document.mapRouteStyle.selectedCheckinIds?.contains(point.id) != false }
            .associate { it.id to (state.document.mapRouteStyle.preferredCheckins[it.id] ?: PosterMapMarkers.checkinName(it)) })
    }
    var editingName by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!state.annotationSaving) onDismiss() },
        title = { Text("标注打卡点 (${draft.size}/${points.size})") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("搜索打卡点") }, singleLine = true, enabled = !state.annotationSaving)
                Row {
                    TextButton({ draft = points.associate { it.id to (draft[it.id] ?: state.document.mapRouteStyle.preferredCheckins[it.id] ?: PosterMapMarkers.checkinName(it)) } }, enabled = !state.annotationSaving) { Text("全选") }
                    TextButton({ draft = emptyMap() }, enabled = !state.annotationSaving) { Text("全不选") }
                }
                if (points.isEmpty()) Text("暂无打卡点", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    items(points.filter { query.isBlank() || PosterMapMarkers.checkinName(it).contains(query, true) ||
                        draft[it.id].orEmpty().contains(query, true) || it.desc.orEmpty().contains(query, true) }, key = { it.id }) { point ->
                        val chosen = point.id in draft
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                            Checkbox(chosen, { checked -> draft = if (checked) draft + (point.id to (state.document.mapRouteStyle.preferredCheckins[point.id] ?: PosterMapMarkers.checkinName(point))) else draft - point.id }, enabled = !state.annotationSaving)
                            Column(Modifier.weight(1f)) {
                                Text(PosterMapMarkers.checkinName(point), modifier = Modifier.padding(top = 12.dp))
                                if (chosen && editingName == point.id) OutlinedTextField(draft[point.id].orEmpty(),
                                    { draft = draft + (point.id to it.take(40)) }, label = { Text("分享名称") }, singleLine = true, enabled = !state.annotationSaving)
                            }
                            if (chosen) IconButton({ editingName = if (editingName == point.id) null else point.id }, enabled = !state.annotationSaving) {
                                Icon(Icons.Default.Edit, "编辑分享名称")
                            }
                        }
                    }
                }
                state.annotationSaveError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton({ onApply(draft) }, enabled = !state.annotationSaving) { Text(if (state.annotationSaving) "保存中" else "完成") } },
        dismissButton = { TextButton(onDismiss, enabled = !state.annotationSaving) { Text("取消") } })
}

/** 画布容器：按导出比例约束预览区域，背景层（地图/照片）+ 模块层。地图模式画布分为信息带 + 地图区域。 */
@Composable
private fun PosterCanvas(
    modifier: Modifier,
    state: PosterEditorState,
    viewModel: PosterEditorViewModel,
) {
    val document = state.document
    val snapshot = state.snapshot ?: return
    val spec = remember(document.ratioKind, document.photoState?.resourceId, document.photoState?.imageWidth, document.photoState?.imageHeight) {
        document.canvasSpec()
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

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
    val mapOutline = remember(context) { PosterMapOutline.load(context) }
    val checkinIcon = remember(context) { PosterWireframeRenderer.loadCheckinIcon(context) }
    val mapPreview = remember(state.document, state.endpointNames, snapshot, routeModel, canvasSize) {
        if (state.document.backgroundMode == PosterBackgroundMode.MAP && routeModel != null) {
            val bitmap = android.graphics.Bitmap.createBitmap(canvasSize.width, canvasSize.height, android.graphics.Bitmap.Config.ARGB_8888)
            val report = PosterWireframeRenderer.draw(NativeCanvas(bitmap), width, height, state.document,
                snapshot, routeModel, state.endpointNames, mapOutline, checkinIcon)
            bitmap to report
        } else null
    }
    DisposableEffect(mapPreview?.first) { onDispose { mapPreview?.first?.recycle() } }
    val photoRouteStyle = state.document.photoRouteState?.let {
        it.copy(placement = it.placement.copy(centerX = 0f, centerY = 0f))
    }
    val outlineTheme = state.document.photoInfoState?.textTheme ?: PosterInfoTextTheme.LIGHT
    val photoRoutePreview = remember(state.document.backgroundMode, photoRouteStyle, outlineTheme,
        routeModel, state.endpointNames, state.photoOutline?.outline, canvasSize) {
        if (state.document.backgroundMode == PosterBackgroundMode.PHOTO && routeModel != null && photoRouteStyle != null) {
            val moduleWidth = photoRouteStyle.placement.width * width
            val moduleHeight = photoRouteStyle.placement.width * PosterRenderer.routeModuleAspect(routeModel) * height
            // Record once per route style/size; moving the module only translates this layer.
            val picture = android.graphics.Picture()
            val recording = picture.beginRecording(ceil(moduleWidth).toInt().coerceAtLeast(1),
                ceil(moduleHeight).toInt().coerceAtLeast(1))
            val report = try {
                PosterRenderer.drawRouteModule(recording, routeModel, 0f, 0f, moduleWidth, moduleHeight,
                    PosterRenderer.RouteStyle(photoRouteStyle.theme, photoRouteStyle.showCheckinMarkers,
                        photoRouteStyle.showEndpointNames, state.endpointNames, photoRouteStyle.preferredCheckins,
                        photoRouteStyle.showMapOutline, outlineTheme, photoRouteStyle.selectedCheckinIds),
                    checkinIcon, state.photoOutline?.outline ?: mapOutline)
            } finally { picture.endRecording() }
            picture to report
        } else null
    }
    val annotationReport = mapPreview?.second ?: photoRoutePreview?.second ?: PosterWireframeRenderer.AnnotationReport()
    LaunchedEffect(annotationReport) {
        viewModel.updateAnnotationReport(annotationReport)
    }

    // pointerInput 的 lambda 跨重组存活，必须经 rememberUpdatedState 读取最新文档。
    val document by rememberUpdatedState(state.document)
    val interaction by rememberUpdatedState(state.interaction)
    val selected by rememberUpdatedState(state.selected)
    val photoPreview by rememberUpdatedState(state.photoPreview)
    val brandTheme = remember(state.photoPreview, state.document.photoState?.crop, canvasSize) {
        val photo = state.photoPreview
        val crop = state.document.photoState?.crop
        if (photo != null && crop != null) PosterRenderer.photoBrandTheme(photo, crop, width, height)
        else PosterInfoTextTheme.LIGHT
    }

    val routeAspect = routeModel?.let(PosterRenderer::routeModuleAspect)
    val infoAspectFor: (PosterInfoModuleState) -> Float = { infoState ->
        PosterRenderer.InfoLayout.measure(
            snapshot.stats,
            PosterRenderer.infoStyle(infoState),
            1080f).aspect
    }

    fun geometryOf(doc: PosterDocument): ModuleBoxGeometry {
        val routeRect = doc.photoRouteState
            ?.takeIf { doc.backgroundMode == PosterBackgroundMode.PHOTO }
            ?.let { normRect(it.placement, routeAspect ?: 1f, width, height) }
        val infoRect = when (doc.backgroundMode) {
            PosterBackgroundMode.PHOTO -> doc.photoInfoState?.let { normRect(it.placement, infoAspectFor(it) * width / height, width, height) }
            // 地图模式：信息位置由画布分区派生（信息带在地图上方）。
            PosterBackgroundMode.MAP -> mapRegions?.first?.let {
                PxRect(it.left * width, it.top * height, it.right * width, it.bottom * height)
            }
        }
        return ModuleBoxGeometry(routeRect, infoRect)
    }

    val gestureEnabled = state.export !is PosterExport.Rendering && !state.annotationSaving &&
        (interaction == PosterInteraction.SELECT || interaction == PosterInteraction.PHOTO_CROP)
    // 吸附阈值：屏幕 6dp 换算为画布像素（plan §6.4）。
    val density = LocalDensity.current
    val snapThresholdPx = with(density) { 6.dp.toPx() }
    val cornerTouchPx = with(density) { 24.dp.toPx() }
    val snapGuides by rememberUpdatedState(state.snapGuides)

    Canvas(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(interaction, width, height) {
                if (interaction == PosterInteraction.MAP_COMPOSE) {
                    val logicalHeight = height * 1800f / width
                    val rect = PosterWireframeRenderer.mapRect(logicalHeight)
                    val scale = width / 1800f
                    detectTransformGestures { focus, pan, zoom, _ ->
                        viewModel.transformOutline(pan.x / (rect.width() * scale), pan.y / (rect.height() * scale), zoom,
                            (focus.x / scale - rect.left) / rect.width(), (focus.y / scale - rect.top) / rect.height())
                    }
                }
            }
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
                    photoRoutePreview?.first?.let { picture ->
                        val saved = native.save()
                        native.translate(box.left, box.top)
                        native.drawPicture(picture)
                        native.restoreToCount(saved)
                    }
                }
            }
        }
        when (doc.backgroundMode) {
            PosterBackgroundMode.PHOTO -> doc.photoInfoState?.let { info ->
                drawInfo(native, snapshot, info, infoAspectFor(info), width, height)
            }
            PosterBackgroundMode.MAP -> mapPreview?.first?.let { native.drawBitmap(it, 0f, 0f, null) }
        }
        // 品牌水印固定左上角：预览与导出共用同一绘制（plan §11.2 所见即所得）。
        if (doc.backgroundMode == PosterBackgroundMode.PHOTO) PosterRenderer.drawBrandCorner(native, brandLogo,
            brandTheme, width, height)
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
        PosterRenderer.infoStyle(info),
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
    val module = geometry.hit(down.position.x, down.position.y, selectedModule)
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
