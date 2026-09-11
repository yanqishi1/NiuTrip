package com.niutrip.app.ui.shareview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.niutrip.app.core.dayColor
import com.niutrip.app.ui.common.GonePage
import com.niutrip.app.ui.common.LoadingOrError
import com.niutrip.app.ui.detail.map.AMapView
import com.niutrip.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ShareViewScreen(viewModel: ShareViewViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    when {
        state.gone -> GonePage(onBack = onBack)
        state.loading || state.error != null -> LoadingOrError(state.loading, state.error, viewModel::saveAndLoad)
        else -> Box(Modifier.fillMaxSize()) {
            AMapView(state.days, state.selectedDay, state.days.lastOrNull()?.points?.lastOrNull(), Modifier.fillMaxSize())
            TopAppBar(title = { Column { Text(state.data?.track?.track_name.orEmpty(), fontWeight = FontWeight.Bold); Text("${state.data?.track?.owner_username} 的轨迹", color = Info, style = MaterialTheme.typography.bodySmall) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White.copy(.94f)))
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
}

@Composable private fun ShareDayChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) { Text(label, color = if (selected) Color.White else Muted, modifier = Modifier.background(if (selected) color else Color(0xFFF1F3F7), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)) }
@Composable private fun ShareStat(value: Int, label: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value.toString(), fontWeight = FontWeight.Bold); Text(label, color = Muted, style = MaterialTheme.typography.bodySmall) } }
