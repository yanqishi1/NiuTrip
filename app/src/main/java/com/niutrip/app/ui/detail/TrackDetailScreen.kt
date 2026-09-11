package com.niutrip.app.ui.detail

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.niutrip.app.core.asTrackStatus
import com.niutrip.app.core.dayColor
import com.niutrip.app.core.TrackStateMachine
import com.niutrip.app.ui.common.LoadingOrError
import com.niutrip.app.ui.common.StatusPill
import com.niutrip.app.ui.common.TrackCover
import com.niutrip.app.ui.detail.map.AMapView
import com.niutrip.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TrackDetailScreen(
    viewModel: TrackDetailViewModel,
    readOnly: Boolean,
    onBack: () -> Unit,
    onCheckin: (String) -> Unit,
    onShare: (String) -> Unit,
    onStartService: (String, String, String) -> Unit,
    onStopService: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        it?.let(viewModel::updateImage)
    }
    // 进入/返回本页都刷新：打卡发布完 popBackStack 回来立即可见新点位与计数
    LaunchedEffect(Unit) { viewModel.load() }
    var menu by remember { mutableStateOf(false) }; var rename by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf(false) }
    var exitPrompt by remember { mutableStateOf(false) }
    val backgroundRecording = !readOnly && state.track?.track_status == "RECORDING" && state.track?.track_record_mode == "AUTO"
    val requestBack = { if (backgroundRecording) exitPrompt = true else onBack() }
    BackHandler(enabled = backgroundRecording) { exitPrompt = true }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    Box(Modifier.fillMaxSize()) {
        if (state.loading || state.error != null && state.track == null) LoadingOrError(state.loading, state.error, viewModel::load)
        else {
            AMapView(state.days, state.selectedDay, state.days.lastOrNull()?.points?.lastOrNull(), Modifier.fillMaxSize(), current = state.current)
            TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.track?.track_name.orEmpty(), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp)); state.track?.let { StatusPill(statusText(it.track_status), statusColor(it.track_status)) }
            } }, navigationIcon = { IconButton(onClick = requestBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { if (!readOnly) Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }; DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("更换代表图") },
                        onClick = {
                            menu = false
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        enabled = !state.updatingImage,
                    )
                    DropdownMenuItem({ Text("修改名称") }, onClick = { menu = false; rename = true })
                    DropdownMenuItem({ Text("删除轨迹", color = Danger) }, onClick = { menu = false; delete = true })
                } } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White.copy(alpha = .94f)))
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
                            TrackStateMachine.canStart(track.track_status.asTrackStatus()) -> Button({ viewModel.changeStatus("RECORDING") { onStartService(it.track_id, it.track_name, it.track_record_mode) } }, Modifier.weight(1f)) { Text("开始记录") }
                            TrackStateMachine.canFinish(track.track_status.asTrackStatus()) -> OutlinedButton({ viewModel.changeStatus("FINISHED") { onStopService() } }, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Text("结束记录") }
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
    if (delete) AlertDialog({ delete = false }, title = { Text("删除轨迹？") }, text = { Text("轨迹会从列表移除，此操作不可撤销。") }, confirmButton = { TextButton({ delete = false; viewModel.delete() }) { Text("删除", color = Danger) } }, dismissButton = { TextButton({ delete = false }) { Text("取消") } })
    if (exitPrompt) AlertDialog(
        onDismissRequest = { exitPrompt = false },
        title = { Text("退出轨迹？") },
        text = { Text("退出页面后，APP 会默默在后台运行，并继续每 10 分钟记录一次轨迹点。你可以随时回来查看。") },
        confirmButton = { TextButton(onClick = { exitPrompt = false; onBack() }) { Text("退出轨迹") } },
        dismissButton = { TextButton(onClick = { exitPrompt = false }) { Text("继续查看") } },
    )
    // 操作失败（如"已有正在记录的轨迹"的 409）弹窗提示；首屏加载失败走 LoadingOrError，不在此重复
    if (state.error != null && state.track != null) AlertDialog({ viewModel.dismissError() },
        title = { Text("操作失败") }, text = { Text(state.error.orEmpty()) },
        confirmButton = { TextButton({ viewModel.dismissError() }) { Text("知道了") } })
}

@Composable private fun DayChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Text(label, color = if (selected) Color.White else Muted, modifier = Modifier.background(if (selected) color else Color(0xFFF1F3F7), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
}
@Composable private fun Stat(value: String, label: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontWeight = FontWeight.Bold); Text(label, color = Muted, style = MaterialTheme.typography.bodySmall) } }
private fun statusText(status: String) = when (status) { "RECORDING" -> "记录中"; "FINISHED" -> "已结束"; else -> "未开始" }
private fun statusColor(status: String) = when (status) { "RECORDING" -> Green700; "FINISHED" -> Muted; else -> Warning }
