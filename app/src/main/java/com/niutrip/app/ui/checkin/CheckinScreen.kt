package com.niutrip.app.ui.checkin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.niutrip.app.core.DayGroup
import com.niutrip.app.core.PointLite
import com.niutrip.app.ui.detail.map.AMapView
import com.niutrip.app.ui.common.CameraImage
import com.niutrip.app.ui.common.ImageSourceSheet
import com.niutrip.app.ui.common.createCameraImage
import com.niutrip.app.ui.common.hasCamera
import com.niutrip.app.ui.theme.*
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CheckinScreen(viewModel: CheckinViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showPhotoSource by remember { mutableStateOf(false) }
    var pendingCameraImage by remember { mutableStateOf<CameraImage?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { viewModel.addPhotos(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        pendingCameraImage?.let { image ->
            if (captured) viewModel.addPhotos(listOf(image.uri)) else image.file.delete()
        }
        pendingCameraImage = null
    }
    LaunchedEffect(state.result) { if (state.result == CheckinResult.Done) onDone() }
    Scaffold(topBar = { TopAppBar(title = { Text("手动打卡") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFFF2EFE9))) {
                if (state.lon != null && state.lat != null) {
                    val point = PointLite("now", LocalDateTime.now(), state.lon!!, state.lat!!, true)
                    AMapView(listOf(DayGroup(point.time.toLocalDate(), listOf(point))), 0, point, Modifier.fillMaxSize(), showEndpoints = false)
                } else CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.LocationOn, null, tint = Green700); Spacer(Modifier.width(8.dp)); Text(if (state.lon == null) "正在获取当前位置" else "${"%.5f".format(state.lat)}, ${"%.5f".format(state.lon)}", color = Muted) }
                OutlinedTextField(state.name, viewModel::setName, Modifier.fillMaxWidth(), label = { Text("点位名称") }, placeholder = { Text("例如：折多山垭口") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                OutlinedTextField(state.desc, viewModel::setDesc, Modifier.fillMaxWidth().heightIn(min = 110.dp), label = { Text("这一刻的想法") }, shape = RoundedCornerShape(12.dp))
                Text("照片 ${state.photos.size}/9", fontWeight = FontWeight.Bold)
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().height(((state.photos.size + 3) / 3 * 104).coerceAtLeast(104).dp), userScrollEnabled = false, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.photos) { uri -> Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))) {
                        AsyncImage(uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        IconButton({ viewModel.removePhoto(uri) }, Modifier.align(Alignment.TopEnd).size(28.dp).background(Color.Black.copy(.5f), RoundedCornerShape(50))) { Icon(Icons.Default.Close, "移除", tint = Color.White) }
                    } }
                    if (state.photos.size < 9) item { Box(Modifier.aspectRatio(1f).border(1.dp, Line, RoundedCornerShape(8.dp)).clickable { showPhotoSource = true }, contentAlignment = Alignment.Center) { Icon(Icons.Default.AddPhotoAlternate, "添加照片", tint = Muted) } }
                }
                (state.result as? CheckinResult.Error)?.let { Text(it.message, color = Danger) }
                Button(viewModel::submit, Modifier.fillMaxWidth().height(48.dp), enabled = state.result !is CheckinResult.Loading && !state.locating, shape = RoundedCornerShape(24.dp)) { Text("发布打卡") }
            }
        }
    }
    if (showPhotoSource) ImageSourceSheet(
        onDismiss = { showPhotoSource = false },
        cameraAvailable = hasCamera(context),
        onTakePhoto = {
            showPhotoSource = false
            createCameraImage(context).also { pendingCameraImage = it; camera.launch(it.uri) }
        },
        onChoosePhoto = {
            showPhotoSource = false
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
    )
}
