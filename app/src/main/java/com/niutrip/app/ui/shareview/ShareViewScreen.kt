package com.niutrip.app.ui.shareview

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.niutrip.app.core.dayColor
import com.niutrip.app.ui.common.GonePage
import com.niutrip.app.ui.common.LoadingOrError
import com.niutrip.app.ui.detail.map.AMapView
import com.niutrip.app.ui.detail.CheckinDetailSheet
import com.niutrip.app.ui.create.AndroidPermissionChecker
import com.niutrip.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ShareViewScreen(
    viewModel: ShareViewViewModel,
    currentUserAvatarUrl: String?,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val permissionChecker = remember(context) { AndroidPermissionChecker(context) }
    var selectedPointId by remember { mutableStateOf<String?>(null) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            viewModel.focusCurrentLocation()
        } else viewModel.showLocationError("需要定位权限才能跳转到当前位置")
    }
    when {
        state.gone -> GonePage(onBack = onBack)
        state.loading || state.error != null -> LoadingOrError(state.loading, state.error, viewModel::saveAndLoad)
        else -> Box(Modifier.fillMaxSize()) {
            AMapView(
                days = state.days,
                selectedDayIdx = state.selectedDay,
                latest = state.days.lastOrNull()?.points?.lastOrNull(),
                modifier = Modifier.fillMaxSize(),
                onPointClick = { selectedPointId = it.id },
                current = state.current,
                currentAvatarUrl = currentUserAvatarUrl,
                focusCurrentRequest = state.currentFocusRequest,
                showRouteEnd = state.data?.track?.end_time != null,
            )
            TopAppBar(title = { Column { Text(state.data?.track?.track_name.orEmpty(), fontWeight = FontWeight.Bold); Text("${state.data?.track?.owner_username} 的轨迹", color = Info, style = MaterialTheme.typography.bodySmall) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White.copy(.94f)))
            SmallFloatingActionButton(
                onClick = {
                    if (permissionChecker.hasFineLocation()) viewModel.focusCurrentLocation()
                    else locationPermission.launch(arrayOf(
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
                if (state.data?.track?.share_mode == "ONCE") Text("这是一次性分享，内容已保存到你的列表", color = Warning, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp)); LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    item { ShareDayChip("全程", state.selectedDay == -1, Green500) { viewModel.selectDay(-1) } }
                    itemsIndexed(state.days) { index, day -> ShareDayChip(day.date.toString().substring(5), state.selectedDay == index, Color(dayColor(index))) { viewModel.selectDay(index) } }
                }
                Spacer(Modifier.height(14.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    ShareStat(state.data?.stats?.point_count ?: 0, "轨迹点"); ShareStat(state.data?.stats?.checkin_count ?: 0, "打卡"); ShareStat(state.data?.stats?.days ?: 0, "天数")
                }
            }
        }
    }
    selectedPointId?.let { pointId ->
        state.days.flatMap { it.points }.firstOrNull { it.id == pointId }?.let { point ->
            CheckinDetailSheet(
                point = point,
                editable = false,
                saving = false,
                onDismiss = { selectedPointId = null },
                onSave = { _, _, _, _ -> },
            )
        }
    }
    state.locationError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissLocationError,
            title = { Text("定位失败") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissLocationError) { Text("知道了") } },
        )
    }
}

@Composable private fun ShareDayChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) { Text(label, color = if (selected) Color.White else Muted, modifier = Modifier.background(if (selected) color else Color(0xFFF1F3F7), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)) }
@Composable private fun ShareStat(value: Int, label: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value.toString(), fontWeight = FontWeight.Bold); Text(label, color = Muted, style = MaterialTheme.typography.bodySmall) } }
