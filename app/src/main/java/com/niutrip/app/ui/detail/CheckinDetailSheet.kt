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
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckinDetailSheet(
    point: PointLite,
    editable: Boolean,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, desc: String, existingImages: List<String>, newImages: List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    var editing by remember(point.id) { mutableStateOf(false) }
    var name by remember(point.id, point.name) { mutableStateOf(point.name.orEmpty()) }
    var desc by remember(point.id, point.desc) { mutableStateOf(point.desc.orEmpty()) }
    var existingImages by remember(point.id, point.images) { mutableStateOf(point.images) }
    var newImages by remember(point.id) { mutableStateOf(emptyList<Uri>()) }
    var pendingCameraImage by remember { mutableStateOf<CameraImage?>(null) }
    var viewerStart by remember(point.id) { mutableStateOf<Int?>(null) }
    val photoCount = existingImages.size + newImages.size
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
                Text("编辑打卡点", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 100) name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    label = { Text("内容") },
                    minLines = 3,
                )
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = {
                        name = point.name.orEmpty()
                        desc = point.desc.orEmpty()
                        existingImages = point.images
                        newImages = emptyList()
                        editing = false
                    }, enabled = !saving, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    Button(
                        onClick = { onSave(name, desc, existingImages, newImages) },
                        enabled = !saving,
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
                        Text(point.name?.takeIf(String::isNotBlank) ?: "旅途打卡",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(point.time.toString().replace('T', ' '), color = Muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (editable) FilledTonalIconButton(onClick = { editing = true }) {
                        Icon(Icons.Outlined.Edit, "编辑打卡点")
                    }
                }
                Text(point.desc?.takeIf(String::isNotBlank) ?: "暂无打卡内容", color = if (point.desc.isNullOrBlank()) Muted else LocalContentColor.current)
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
