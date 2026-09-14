package com.niutrip.app.ui.tracks

import android.content.ClipboardManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.niutrip.app.data.remote.TrackDto
import com.niutrip.app.ui.common.*
import com.niutrip.app.ui.theme.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TrackListScreen(
    viewModel: TrackListViewModel,
    onCreate: () -> Unit,
    onTrack: (TrackDto) -> Unit,
    onStopRecording: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    var revealedTrackId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("轨迹", fontWeight = FontWeight.Bold) }, actions = { IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, "刷新") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            val addingShared = state.selected == TrackScope.SHARED
            FloatingActionButton(onClick = if (addingShared) viewModel::openImportDialog else onCreate, containerColor = Green500, contentColor = Color.White) {
                Icon(Icons.Default.Add, if (addingShared) "添加分享轨迹" else "创建轨迹")
            }
        }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SegmentedControl(listOf("我的轨迹", "分享给我的"), if (state.selected == TrackScope.MINE) 0 else 1,
                { viewModel.select(if (it == 0) TrackScope.MINE else TrackScope.SHARED) }, Modifier.padding(14.dp))
            val rows = if (state.selected == TrackScope.MINE) state.mine else state.shared
            if (state.loading || state.error != null) LoadingOrError(state.loading, state.error, viewModel::refresh)
            else if (rows.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (state.selected == TrackScope.MINE) "还没有轨迹" else "还没有收到分享", color = Muted) }
            else LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                items(rows, key = { it.track_id }) { track ->
                    val shared = state.selected == TrackScope.SHARED
                    SwipeRevealTrackCard(
                        track = track,
                        shared = shared,
                        revealed = revealedTrackId == track.track_id,
                        deleting = state.deletingTrackId == track.track_id,
                        onReveal = { reveal -> revealedTrackId = track.track_id.takeIf { reveal } },
                        onDelete = {
                            revealedTrackId = null
                            pendingDelete = PendingDelete(track, shared)
                        },
                        onClick = { onTrack(track) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
    if (state.showImportDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissImportDialog,
            title = { Text("添加分享轨迹") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("粘贴好友发来的轨迹分享链接，验证成功后会添加到「分享给我的」。", color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = state.importLink,
                        onValueChange = viewModel::setImportLink,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("轨迹分享链接") },
                        placeholder = { Text("https://.../t/...") },
                        minLines = 2,
                        maxLines = 3,
                        enabled = !state.importing,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    val text = runCatching {
                                        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                                    }.getOrNull()
                                    if (text != null) viewModel.setImportLink(text)
                                },
                                enabled = !state.importing,
                            ) { Icon(Icons.Default.ContentPaste, "粘贴") }
                        },
                        isError = state.importError != null,
                        supportingText = state.importError?.let { message -> { Text(message, color = Danger) } },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::importShared, enabled = !state.importing) {
                    if (state.importing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.importing) "正在添加" else "添加")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissImportDialog, enabled = !state.importing) { Text("取消") } },
        )
    }
    pendingDelete?.let { deletion ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (deletion.shared) "移除分享轨迹？" else "删除轨迹？") },
            text = {
                Text(if (deletion.shared) "只会从「分享给我的」移除，不会删除对方的原轨迹。"
                else "轨迹及其轨迹点会被删除，此操作不可撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(deletion.track, deletion.shared) {
                        if (!deletion.shared && deletion.track.track_status == "RECORDING") onStopRecording()
                    }
                }) { Text(if (deletion.shared) "移除" else "删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

private data class PendingDelete(val track: TrackDto, val shared: Boolean)

@Composable private fun SwipeRevealTrackCard(
    track: TrackDto,
    shared: Boolean,
    revealed: Boolean,
    deleting: Boolean,
    onReveal: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val actionWidth = 80.dp
    val actionWidthPx = with(LocalDensity.current) { actionWidth.toPx() }
    val targetOffset = if (revealed) -actionWidthPx else 0f
    val animatedOffset by animateFloatAsState(targetOffset, label = "track-swipe")
    var draggedOffset by remember(track.track_id) { mutableStateOf<Float?>(null) }
    val shownOffset = draggedOffset ?: animatedOffset

    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
        Box(Modifier.matchParentSize().background(Danger), contentAlignment = Alignment.CenterEnd) {
            TextButton(
                onClick = onDelete,
                modifier = Modifier.width(actionWidth).fillMaxHeight(),
                enabled = revealed && !deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                if (deleting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Default.Delete, "删除")
            }
        }
        Box(
            Modifier.offset { IntOffset(shownOffset.roundToInt(), 0) }
                .pointerInput(track.track_id, revealed) {
                    detectHorizontalDragGestures(
                        onDragStart = { draggedOffset = targetOffset },
                        onDragCancel = { draggedOffset = null },
                        onDragEnd = {
                            val open = (draggedOffset ?: targetOffset) <= -actionWidthPx / 2f
                            draggedOffset = null
                            onReveal(open)
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            draggedOffset = ((draggedOffset ?: targetOffset) + amount)
                                .coerceIn(-actionWidthPx, 0f)
                        },
                    )
                },
        ) {
            TrackCard(track, shared) {
                if (revealed) onReveal(false) else onClick()
            }
        }
    }
}

@Composable private fun TrackCard(track: TrackDto, shared: Boolean, onClick: () -> Unit) {
    val recording = track.track_status == "RECORDING"
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (recording) Color(0xFFF2FCF7) else Color.White)
        .border(if (recording) 1.dp else 0.dp, if (recording) Color(0xFFB8EAD2) else Color.Transparent, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(11.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        TrackCover(track.track_img_url, Modifier.size(86.dp), "${track.track_name}的代表图")
        Column(Modifier.weight(1f).heightIn(min = 86.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.track_name, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusPill(when (track.track_status) { "RECORDING" -> "记录中"; "FINISHED" -> "已结束"; else -> "未开始" }, when (track.track_status) { "RECORDING" -> Green700; "FINISHED" -> Muted; else -> Warning })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(track.track_start_time?.take(10) ?: "尚未开始记录", Modifier.weight(1f), color = Muted,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (shared) SharedSourcePill(track.sharer_username ?: "好友")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(if (track.track_record_mode == "AUTO") "自动" else "仅手动", if (recording) Green700 else Muted)
                StatusPill("${track.point_count} 点", Muted)
                if (track.checkin_count > 0) StatusPill("${track.checkin_count} 打卡", Warning)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text("查看次数 ${track.view_count}", color = Muted,
                    style = MaterialTheme.typography.labelSmall)
                Box(Modifier.size(3.dp).background(Line, RoundedCornerShape(50)))
                Text("查看人数 ${track.viewer_count}", color = Muted,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable private fun SharedSourcePill(username: String) {
    Text(
        text = "来自 $username",
        color = Info,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 116.dp)
            .background(Info.copy(alpha = .12f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
