package com.niutrip.app.ui.create

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.niutrip.app.ui.common.StatusPill
import com.niutrip.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CreateTrackScreen(viewModel: CreateTrackViewModel, onBack: () -> Unit, onDone: (String) -> Unit) {
    val state by viewModel.state.collectAsState(); val context = LocalContext.current
    val fine = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.permissionsChanged() }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.permissionsChanged() }
    LaunchedEffect(state.result) { (state.result as? CreateResult.Done)?.let { onDone(it.track.track_id) } }
    Scaffold(topBar = { TopAppBar(title = { Text("创建轨迹") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("轨迹名称", fontWeight = FontWeight.Bold)
            OutlinedTextField(state.name, viewModel::setName, Modifier.fillMaxWidth(), placeholder = { Text("例如：川西环线之旅") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            Text("记录方式", fontWeight = FontWeight.Bold)
            ModeCard("AUTO", "自动记录", "每 10 分钟记录一次位置", Icons.Outlined.LocationOn, state.mode == "AUTO", viewModel::setMode)
            ModeCard("MANUAL", "仅手动", "只在打卡时记录位置", Icons.Outlined.PanTool, state.mode == "MANUAL", viewModel::setMode)
            if (state.result == CreateResult.NeedPermission) {
                Column(Modifier.fillMaxWidth().background(Color(0xFFFFFAF0), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFFFFE6BD), RoundedCornerShape(8.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("自动记录需要定位与后台运行权限", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { fine.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("精确定位") }
                        if (Build.VERSION.SDK_INT >= 29) OutlinedButton(onClick = { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("后台定位") }
                    }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }) { Text("允许后台运行") }
                    TextButton(onClick = viewModel::degradeToManual) { Text("改为仅手动") }
                }
            }
            (state.result as? CreateResult.Error)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            Button(viewModel::submit, Modifier.fillMaxWidth().height(48.dp), enabled = state.result !is CreateResult.Loading, shape = RoundedCornerShape(24.dp)) { Text("创建轨迹") }
            Text("使用系统默认封面，创建后可在详情中更换", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ModeCard(mode: String, title: String, desc: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onSelect(mode) }.background(if (selected) Color(0xFFF2FCF7) else Color.White, RoundedCornerShape(8.dp))
        .border(1.5.dp, if (selected) Green500 else Line, RoundedCornerShape(8.dp)).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, { onSelect(mode) }); Icon(icon, null, tint = if (selected) Green700 else Muted)
        Column(Modifier.weight(1f)) { Row { Text(title, fontWeight = FontWeight.Bold); if (mode == "AUTO") { Spacer(Modifier.width(8.dp)); StatusPill("推荐", Green700) } }; Text(desc, color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
}
