package com.niutrip.app.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.niutrip.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ShareModeOption(val id: String, val title: String, val detail: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ShareSheet(viewModel: ShareViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState(); val context = LocalContext.current
    val captureWidth = minOf(440, LocalConfiguration.current.screenHeightDp - 180).coerceAtLeast(180).dp
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.load() }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelImage() } }
    fun save(file: File) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { TrackImageFiles.saveToGallery(context.applicationContext, file) }
                Toast.makeText(context, "图片已保存到相册", Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) {
                Toast.makeText(context, error.message ?: "保存失败", Toast.LENGTH_LONG).show()
            } finally { saving = false }
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) (state.image as? ImageResult.Ready)?.file?.let(::save)
        else Toast.makeText(context, "需要存储权限才能保存到相册", Toast.LENGTH_SHORT).show()
    }
    ModalBottomSheet(onDismissRequest = { viewModel.cancelImage(); onDismiss() }, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("分享轨迹", style = MaterialTheme.typography.titleLarge)
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                state.loadError != null -> {
                    Text(state.loadError.orEmpty(), color = Danger)
                    OutlinedButton({ viewModel.load() }) { Text("重试") }
                }
                else -> {
                    if (state.track?.track_status == "FINISHED") {
                        TabRow(selectedTabIndex = if (state.kind == ShareKind.IMAGE) 0 else 1) {
                            Tab(state.kind == ShareKind.IMAGE, { viewModel.selectKind(ShareKind.IMAGE) },
                                text = { Text("轨迹图片") }, icon = { Icon(Icons.Default.Image, null) })
                            Tab(state.kind == ShareKind.LINK, { viewModel.selectKind(ShareKind.LINK) },
                                text = { Text("分享链接") }, icon = { Icon(Icons.Default.Link, null) })
                        }
                    }
                    if (state.kind == ShareKind.IMAGE) {
                        when (val image = state.image) {
                            is ImageResult.Ready -> {
                                AsyncImage(image.file, "全程轨迹图片", Modifier.fillMaxWidth().height(320.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton({
                                        if (Build.VERSION.SDK_INT >= 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) save(image.file)
                                        else storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    }, Modifier.weight(1f), enabled = !saving) {
                                        Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text(if (saving) "保存中" else "保存到相册")
                                    }
                                    Button({
                                        try { TrackImageFiles.share(context, image.file) }
                                        catch (error: Exception) { Toast.makeText(context, "无法打开分享应用", Toast.LENGTH_SHORT).show() }
                                    }, Modifier.weight(1f)) { Icon(Icons.Default.IosShare, null); Spacer(Modifier.width(6.dp)); Text("分享图片") }
                                }
                            }
                            is ImageResult.Error -> Text(image.message, color = Danger)
                            ImageResult.Loading -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                TextButton(viewModel::cancelImage) { Text("取消") }
                            }
                            ImageResult.Idle -> Unit
                        }
                        Button(viewModel::generateImage, Modifier.fillMaxWidth().height(48.dp), enabled = state.image !is ImageResult.Loading && !saving) {
                            Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp))
                            Text(if (state.image is ImageResult.Ready) "重新生成图片" else "生成轨迹图片")
                        }
                    } else LinkShareControls(state, viewModel)
                }
            }
        }
    }
    state.imageData?.let { data ->
        Dialog(onDismissRequest = viewModel::cancelImage, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = RoundedCornerShape(8.dp), modifier = Modifier.widthIn(max = captureWidth).fillMaxWidth(.92f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("生成轨迹图片", style = MaterialTheme.typography.titleMedium)
                    TrackImageCapture(data, Modifier.fillMaxWidth().aspectRatio(1f), viewModel::imageReady, viewModel::imageFailed)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(viewModel::cancelImage, Modifier.align(Alignment.End)) { Text("取消") }
                }
            }
        }
    }
}

@Composable private fun LinkShareControls(state: ShareState, viewModel: ShareViewModel) {
    val context = LocalContext.current
    val options = listOf(ShareModeOption("PRIVATE", "私密", "仅自己可见，不生成链接"), ShareModeOption("ONCE", "一次性分享", "打开一次后链接立即失效"), ShareModeOption("PUBLIC", "公开", "知道链接的人可重复查看"))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("公开链接可重复分享；切换分享权限会使旧链接失效", color = Muted, style = MaterialTheme.typography.bodySmall)
        options.forEach { option ->
            Row(Modifier.fillMaxWidth().clickable(enabled = state.result !is ShareResult.Loading) { viewModel.pick(option.id) }.background(if (state.selectedMode == option.id) Color(0xFFF2FCF7) else Color.White, RoundedCornerShape(8.dp))
                .border(1.dp, if (state.selectedMode == option.id) Green500 else Line, RoundedCornerShape(8.dp)).padding(12.dp)) {
                RadioButton(state.selectedMode == option.id, { viewModel.pick(option.id) }, enabled = state.result !is ShareResult.Loading); Spacer(Modifier.width(8.dp))
                Column { Text(option.title, fontWeight = FontWeight.Bold); Text(option.detail, color = Muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (state.selectedMode == "ONCE") Text("链接一经打开即失效，请确认接收者能够立即查看。", color = Warning, style = MaterialTheme.typography.bodySmall)
        state.warning?.let { Text(it, color = Warning, style = MaterialTheme.typography.bodySmall) }
        val done = state.result as? ShareResult.Done
        val doneUrl = done?.url
        if (doneUrl != null) {
            LaunchedEffect(doneUrl) {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("旅行牛牛分享链接", buildShareText(doneUrl)))
                Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
            }
            Text(buildShareText(doneUrl), Modifier.fillMaxWidth().background(Background, RoundedCornerShape(8.dp)).padding(12.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("旅行牛牛分享链接", buildShareText(doneUrl))) }, Modifier.weight(1f)) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("再次复制") }
                OutlinedButton({ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, buildShareText(doneUrl)) }, "分享轨迹")) }, Modifier.weight(1f)) { Icon(Icons.Default.IosShare, null); Spacer(Modifier.width(6.dp)); Text("更多") }
            }
        }
        (state.result as? ShareResult.Error)?.let { Text(it.message, color = Danger) }
        if (done?.mode == "PRIVATE") Text("已设为私密", color = Green700)
        Button({ viewModel.generate() }, Modifier.fillMaxWidth().height(48.dp), enabled = state.result !is ShareResult.Loading) {
            Text(if (state.result is ShareResult.Loading) "处理中" else if (state.selectedMode == "PRIVATE") "设为私密" else "生成 / 复用链接")
        }
    }
}
