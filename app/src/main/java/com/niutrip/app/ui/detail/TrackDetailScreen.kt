package com.niutrip.app.ui.detail

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.niutrip.app.core.asTrackStatus
import com.niutrip.app.core.dayColor
import com.niutrip.app.core.TrackStateMachine
import com.niutrip.app.ui.common.LoadingOrError
import com.niutrip.app.ui.common.CameraImage
import com.niutrip.app.ui.common.ImageSourceSheet
import com.niutrip.app.ui.common.RecordingPermissionDialog
import com.niutrip.app.ui.common.StatusPill
import com.niutrip.app.ui.common.TrackCover
import com.niutrip.app.ui.common.createCameraImage
import com.niutrip.app.ui.common.hasCamera
import com.niutrip.app.ui.create.AndroidPermissionChecker
import com.niutrip.app.ui.create.openAppPermissionSettings
import com.niutrip.app.ui.create.requestBatteryWhitelist
import com.niutrip.app.ui.detail.map.AMapView
import com.niutrip.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TrackDetailScreen(
    viewModel: TrackDetailViewModel,
    readOnly: Boolean,
    currentUserAvatarUrl: String?,
    onBack: () -> Unit,
    onCheckin: (String) -> Unit,
    onShare: (String) -> Unit,
    onStartService: (String, String, String) -> Unit,
    onStopService: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permissionChecker = remember(context) { AndroidPermissionChecker(context) }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var showRecordingPermissions by remember { mutableStateOf(false) }
    val recordingPermissions = remember(permissionRefresh) { permissionChecker.status() }
    var showImageSource by remember { mutableStateOf(false) }
    var selectedPointId by remember { mutableStateOf<String?>(null) }
    var pendingCameraImage by remember { mutableStateOf<CameraImage?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        it?.let(viewModel::updateImage)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        pendingCameraImage?.let { image ->
            if (captured) viewModel.updateImage(image.uri) else image.file.delete()
        }
        pendingCameraImage = null
    }
    val fineLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRefresh++ }
    val selfLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionRefresh++
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            viewModel.focusCurrentLocation()
        } else viewModel.showError("需要定位权限才能跳转到当前位置")
    }
    val backgroundLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRefresh++ }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRefresh++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionRefresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // 进入/返回本页都刷新：打卡发布完 popBackStack 回来立即可见新点位与计数
    LaunchedEffect(Unit) { viewModel.load() }
    var menu by remember { mutableStateOf(false) }; var rename by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf(false) }
    var finishPrompt by remember { mutableStateOf(false) }
    var exitPrompt by remember { mutableStateOf(false) }
    val backgroundRecording = !readOnly && state.track?.track_status == "RECORDING" && state.track?.track_record_mode == "AUTO"
    val requestBack = { if (backgroundRecording) exitPrompt = true else onBack() }
    BackHandler(enabled = backgroundRecording) { exitPrompt = true }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    Box(Modifier.fillMaxSize()) {
        if (state.loading || state.error != null && state.track == null) LoadingOrError(state.loading, state.error, viewModel::load)
        else {
            AMapView(
                days = state.days,
                selectedDayIdx = state.selectedDay,
                latest = state.days.lastOrNull()?.points?.lastOrNull(),
                modifier = Modifier.fillMaxSize(),
                onPointClick = { selectedPointId = it.id },
                current = state.current,
                currentAvatarUrl = currentUserAvatarUrl,
                focusCurrentRequest = state.currentFocusRequest,
                showRouteEnd = state.track?.track_status == "FINISHED",
            )
            TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.track?.track_name.orEmpty(), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp)); state.track?.let { StatusPill(statusText(it.track_status), statusColor(it.track_status)) }
            } }, navigationIcon = { IconButton(onClick = requestBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { if (!readOnly) Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }; DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("更换代表图") },
                        onClick = {
                            menu = false
                            showImageSource = true
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        enabled = !state.updatingImage,
                    )
                    DropdownMenuItem({ Text("修改名称") }, onClick = { menu = false; rename = true })
                    DropdownMenuItem({ Text("删除轨迹", color = Danger) }, onClick = { menu = false; delete = true })
                } } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White.copy(alpha = .94f)))
            SmallFloatingActionButton(
                onClick = {
                    if (permissionChecker.hasFineLocation()) viewModel.focusCurrentLocation()
                    else selfLocationPermission.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 84.dp, end = 16.dp),
                containerColor = Color.White,
                contentColor = Green700,
            ) {
                if (state.locatingCurrent) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.MyLocation, "跳转到当前位置")
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackCover(state.track?.track_img_url.orEmpty(), Modifier.size(44.dp), "轨迹代表图")
                    Spacer(Modifier.width(10.dp))
                    Text(if (readOnly) "只读轨迹" else "行程轨迹", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    if (state.updatingImage) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    item { DayChip("全程", state.selectedDay == -1, Green500) { viewModel.selectDay(-1) } }
                    itemsIndexed(state.days) { index, day -> DayChip(day.date.toString().substring(5), state.selectedDay == index, Color(dayColor(index))) { viewModel.selectDay(index) } }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    Stat("${state.track?.point_count ?: 0}", "轨迹点"); Stat("${state.track?.checkin_count ?: 0}", "打卡"); Stat("${state.days.size}", "天数")
                }
                val track = state.track
                if (!readOnly && track != null) {
                    Spacer(Modifier.height(14.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            TrackStateMachine.canStart(track.track_status.asTrackStatus()) -> Button({
                                if (permissionChecker.status().canRecordInBackground || track.track_record_mode != "AUTO") {
                                    viewModel.changeStatus("RECORDING") { onStartService(it.track_id, it.track_name, it.track_record_mode) }
                                } else {
                                    permissionRefresh++
                                    showRecordingPermissions = true
                                }
                            }, Modifier.weight(1f)) { Text("开始记录") }
                            TrackStateMachine.canFinish(track.track_status.asTrackStatus()) -> OutlinedButton(
                                onClick = { finishPrompt = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                            ) { Text("结束记录") }
                        }
                        if (TrackStateMachine.canCheckin(track.track_status.asTrackStatus())) OutlinedButton({ onCheckin(track.track_id) }, Modifier.weight(1f)) { Text("手动打卡") }
                        OutlinedButton({ onShare(track.track_id) }, Modifier.weight(1f)) { Text("分享") }
                    }
                }
            }
        }
    }
    if (rename) {
        var value by remember(state.track?.track_name) { mutableStateOf(state.track?.track_name.orEmpty()) }
        AlertDialog({ rename = false }, title = { Text("修改轨迹名称") }, text = { OutlinedTextField(value, { value = it }, singleLine = true) }, confirmButton = { TextButton({ viewModel.rename(value); rename = false }) { Text("保存") } }, dismissButton = { TextButton({ rename = false }) { Text("取消") } })
    }
    if (delete) AlertDialog({ delete = false }, title = { Text("删除轨迹？") }, text = { Text("轨迹会从列表移除，此操作不可撤销。") }, confirmButton = { TextButton({
        delete = false
        if (backgroundRecording) onStopService()
        viewModel.delete()
    }) { Text("删除", color = Danger) } }, dismissButton = { TextButton({ delete = false }) { Text("取消") } })
    if (finishPrompt) AlertDialog(
        onDismissRequest = { finishPrompt = false },
        title = { Text("结束这条轨迹？") },
        text = { Text("结束后将停止记录当前位置，轨迹不能继续记录。") },
        confirmButton = {
            TextButton(onClick = {
                finishPrompt = false
                viewModel.changeStatus("FINISHED") { onStopService() }
            }) { Text("结束轨迹", color = Danger) }
        },
        dismissButton = {
            TextButton(onClick = { finishPrompt = false }) { Text("手误，继续记录") }
        },
    )
    if (exitPrompt) AlertDialog(
        onDismissRequest = { exitPrompt = false },
        text = { Text("退出页面后，APP 会默默在后台运行，并继续记录轨迹点。你可以随时回来查看。") },
        confirmButton = { TextButton(onClick = { exitPrompt = false; onBack() }) { Text("确定") } },
    )
    // 操作失败（如"已有正在记录的轨迹"的 409）弹窗提示；首屏加载失败走 LoadingOrError，不在此重复
    if (state.error != null && state.track != null) AlertDialog({ viewModel.dismissError() },
        title = { Text("操作失败") }, text = { Text(state.error.orEmpty()) },
        confirmButton = { TextButton({ viewModel.dismissError() }) { Text("知道了") } })
    selectedPointId?.let { pointId ->
        state.days.flatMap { it.points }.firstOrNull { it.id == pointId }?.let { point ->
            CheckinDetailSheet(
                point = point,
                editable = !readOnly,
                saving = state.updatingCheckin,
                onDismiss = { selectedPointId = null },
                onSave = { name, desc, existingImages, newImages ->
                    viewModel.updateCheckin(point.id, name, desc, existingImages, newImages) {
                        selectedPointId = null
                    }
                },
            )
        }
    }
    if (showImageSource) ImageSourceSheet(
        onDismiss = { showImageSource = false },
        cameraAvailable = hasCamera(context),
        onTakePhoto = {
            showImageSource = false
            createCameraImage(context).also { pendingCameraImage = it; camera.launch(it.uri) }
        },
        onChoosePhoto = {
            showImageSource = false
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
    )
    if (showRecordingPermissions) RecordingPermissionDialog(
        status = recordingPermissions,
        onFineLocation = { fineLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
        onBackgroundLocation = {
            if (!recordingPermissions.fineLocation) fineLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else if (Build.VERSION.SDK_INT == 29) backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else openAppPermissionSettings(context)
        },
        onBatteryWhitelist = { requestBatteryWhitelist(context) },
        onNotifications = {
            if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else permissionRefresh++
        },
        onDismiss = { showRecordingPermissions = false },
        onReady = {
            val track = state.track
            if (track != null && permissionChecker.status().canRecordInBackground) {
                showRecordingPermissions = false
                viewModel.changeStatus("RECORDING") { onStartService(it.track_id, it.track_name, it.track_record_mode) }
            } else permissionRefresh++
        },
    )
}

@Composable private fun DayChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Text(label, color = if (selected) Color.White else Muted, modifier = Modifier.background(if (selected) color else Color(0xFFF1F3F7), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
}
@Composable private fun Stat(value: String, label: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontWeight = FontWeight.Bold); Text(label, color = Muted, style = MaterialTheme.typography.bodySmall) } }
private fun statusText(status: String) = when (status) { "RECORDING" -> "记录中"; "FINISHED" -> "已结束"; else -> "未开始" }
private fun statusColor(status: String) = when (status) { "RECORDING" -> Green700; "FINISHED" -> Muted; else -> Warning }
