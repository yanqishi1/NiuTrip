@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.niutrip.app.ui.detail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.niutrip.app.core.PointLite
import com.niutrip.app.ui.common.CameraImage
import com.niutrip.app.ui.common.absoluteMediaUrl
import com.niutrip.app.ui.common.createCameraImage
import com.niutrip.app.ui.common.hasCamera
import com.niutrip.app.ui.theme.Green700
import com.niutrip.app.ui.theme.Line
import com.niutrip.app.ui.theme.Muted
import com.niutrip.app.ui.theme.Danger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckinDetailSheet(
    point: PointLite,
    editable: Boolean,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, desc: String, longitude: Double, latitude: Double,
             existingImages: List<String>, newImages: List<Uri>) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var editing by remember(point.id) { mutableStateOf(false) }
    var name by remember(point.id, point.name) { mutableStateOf(point.name.orEmpty()) }
    var desc by remember(point.id, point.desc) { mutableStateOf(point.desc.orEmpty()) }
    var longitude by remember(point.id, point.lon) { mutableStateOf(point.lon.toString()) }
    var latitude by remember(point.id, point.lat) { mutableStateOf(point.lat.toString()) }
    var existingImages by remember(point.id, point.images) { mutableStateOf(point.images) }
    var newImages by remember(point.id) { mutableStateOf(emptyList<Uri>()) }
    var pendingCameraImage by remember { mutableStateOf<CameraImage?>(null) }
    var viewerStart by remember(point.id) { mutableStateOf<Int?>(null) }
    var deletePrompt by remember(point.id) { mutableStateOf(false) }
    val photoCount = existingImages.size + newImages.size
    val parsedLongitude = longitude.toDoubleOrNull()
    val parsedLatitude = latitude.toDoubleOrNull()
    val coordinateError = when {
        parsedLongitude == null || parsedLatitude == null -> "请输入有效的经纬度"
        parsedLongitude !in -180.0..180.0 -> "经度必须在 -180 到 180 之间"
        parsedLatitude !in -90.0..90.0 -> "纬度必须在 -90 到 90 之间"
        else -> null
    }
    val contentError = pointContentValidation(point.isCheckin, name, desc, photoCount)
    val formError = contentError ?: coordinateError
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && photoCount < MAX_CHECKIN_PHOTOS) newImages = (newImages + uri).distinct()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        pendingCameraImage?.let { image ->
            if (captured && photoCount < MAX_CHECKIN_PHOTOS) newImages = (newImages + image.uri).distinct()
            else image.file.delete()
        }
        pendingCameraImage = null
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).navigationBarsPadding()
                .imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (editing) {
                Text(if (point.isCheckin) "编辑打卡点" else "编辑轨迹点",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 100) name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题") },
                    supportingText = {
                        if (!point.isCheckin) Text("编辑自动轨迹点需填写标题，保存后将升级为打卡点")
                    },
                    isError = contentError != null,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    label = { Text("内容") },
                    minLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = longitude,
                        onValueChange = { longitude = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("经度") },
                        leadingIcon = { Icon(Icons.Outlined.LocationOn, null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = coordinateError != null,
                    )
                    OutlinedTextField(
                        value = latitude,
                        onValueChange = { latitude = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("纬度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = coordinateError != null,
                    )
                }
                if (photoCount > 0) {
                    EditablePhotos(
                        existingImages = existingImages,
                        newImages = newImages,
                        onRemoveExisting = { existingImages = existingImages - it },
                        onRemoveNew = { newImages = newImages - it },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            createCameraImage(context).also { pendingCameraImage = it; camera.launch(it.uri) }
                        },
                        enabled = !saving && photoCount < MAX_CHECKIN_PHOTOS && hasCamera(context),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.CameraAlt, null)
                        Spacer(Modifier.width(6.dp))
                        Text("拍照")
                    }
                    OutlinedButton(
                        onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !saving && photoCount < MAX_CHECKIN_PHOTOS,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.PhotoLibrary, null)
                        Spacer(Modifier.width(6.dp))
                        Text("相册")
                    }
                }
                Text("$photoCount / $MAX_CHECKIN_PHOTOS 张", color = Muted, style = MaterialTheme.typography.bodySmall)
                formError?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = {
                        name = point.name.orEmpty()
                        desc = point.desc.orEmpty()
                        longitude = point.lon.toString()
                        latitude = point.lat.toString()
                        existingImages = point.images
                        newImages = emptyList()
                        editing = false
                    }, enabled = !saving, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    Button(
                        onClick = { onSave(name, desc, parsedLongitude!!, parsedLatitude!!, existingImages, newImages) },
                        enabled = !saving && formError == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        } else Text("保存")
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(point.name?.takeIf(String::isNotBlank) ?:
                            if (point.isCheckin) "旅途打卡" else "轨迹点",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(point.time.toString().replace('T', ' '), color = Muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (editable) {
                        FilledTonalIconButton(onClick = { editing = true }, enabled = !saving) {
                            Icon(Icons.Outlined.Edit, if (point.isCheckin) "编辑打卡点" else "编辑轨迹点")
                        }
                        if (onDelete != null) IconButton(onClick = { deletePrompt = true }, enabled = !saving) {
                            Icon(Icons.Default.Delete, "删除轨迹点", tint = Danger)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn, null, tint = Green700)
                    Spacer(Modifier.width(6.dp))
                    Text("${"%.6f".format(point.lat)}, ${"%.6f".format(point.lon)}",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (point.isCheckin) Text(point.desc?.takeIf(String::isNotBlank) ?: "暂无打卡内容",
                    color = if (point.desc.isNullOrBlank()) Muted else LocalContentColor.current)
                if (point.images.isNotEmpty()) {
                    CheckinPhotoGallery(point.images) { viewerStart = it }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    viewerStart?.let { start ->
        FullScreenImageViewer(
            images = point.images.map(::absoluteMediaUrl),
            initialPage = start,
            onDismiss = { viewerStart = null },
        )
    }
    if (deletePrompt) AlertDialog(
        onDismissRequest = { if (!saving) deletePrompt = false },
        title = { Text(if (point.isCheckin) "删除这个打卡点？" else "删除这个轨迹点？") },
        text = { Text("删除后轨迹线路和里程会立即重新计算，此操作不可撤销。") },
        confirmButton = {
            TextButton(onClick = { deletePrompt = false; onDelete?.invoke() }, enabled = !saving) {
                Text("删除", color = Danger)
            }
        },
        dismissButton = { TextButton(onClick = { deletePrompt = false }, enabled = !saving) { Text("取消") } },
    )
}

@Composable
private fun EditablePhotos(
    existingImages: List<String>,
    newImages: List<Uri>,
    onRemoveExisting: (String) -> Unit,
    onRemoveNew: (Uri) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(existingImages, key = { index, image -> "remote:$index:$image" }) { _, image ->
            RemovablePhoto(absoluteMediaUrl(image)) { onRemoveExisting(image) }
        }
        items(newImages, key = { "local:$it" }) { image ->
            RemovablePhoto(image) { onRemoveNew(image) }
        }
    }
}

@Composable
private fun CheckinPhotoGallery(images: List<String>, onOpen: (Int) -> Unit) {
    val pagerState = rememberPagerState(pageCount = images::size)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxWidth().height(210.dp),
        ) { index ->
            AsyncImage(
                model = absoluteMediaUrl(images[index]),
                contentDescription = "打卡照片 ${index + 1}",
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Line, RoundedCornerShape(8.dp))
                    .clickable { onOpen(index) },
                contentScale = ContentScale.Crop,
            )
        }
        PhotoPageIndicator(pagerState.currentPage, images.size, Green700)
    }
}

@Composable
private fun FullScreenImageViewer(
    images: List<String>,
    initialPage: Int,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, images.lastIndex),
        pageCount = images::size,
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                AsyncImage(
                    model = images[index],
                    contentDescription = "大图 ${index + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            FilledTonalIconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.Black.copy(alpha = .55f),
                    contentColor = Color.White,
                ),
            ) { Icon(Icons.Default.Close, "关闭大图") }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
                color = Color.Black.copy(alpha = .55f),
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    "${pagerState.currentPage + 1} / ${images.size}",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun PhotoPageIndicator(current: Int, count: Int, activeColor: Color) {
    if (count <= 1) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(
                Modifier.padding(horizontal = 3.dp).height(4.dp)
                    .width(if (index == current) 20.dp else 7.dp)
                    .background(if (index == current) activeColor else Line, RoundedCornerShape(50)),
            )
        }
    }
}

@Composable
private fun RemovablePhoto(model: Any, onRemove: () -> Unit) {
    Box {
        AsyncImage(
            model = model,
            contentDescription = "打卡照片",
            modifier = Modifier.size(92.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, Line, RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
                .clip(CircleShape).background(Color.Black.copy(alpha = .55f)),
        ) {
            Icon(Icons.Default.Close, "移除图片", tint = Color.White)
        }
    }
}

private const val MAX_CHECKIN_PHOTOS = 9

internal fun pointContentValidation(
    wasCheckin: Boolean,
    name: String,
    desc: String,
    photoCount: Int,
): String? = when {
    name.isBlank() && (desc.isNotBlank() || photoCount > 0) -> "填写描述或添加图片前，请先填写标题"
    !wasCheckin && name.isBlank() -> "编辑自动轨迹点时必须填写标题"
    else -> null
}
