package com.niutrip.app.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niutrip.app.ui.theme.*

private data class ShareModeOption(val id: String, val title: String, val detail: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ShareSheet(viewModel: ShareViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState(); val context = LocalContext.current
    val options = listOf(ShareModeOption("PRIVATE", "私密", "仅自己可见，不生成链接"), ShareModeOption("ONCE", "一次性分享", "打开一次后链接立即失效"), ShareModeOption("PUBLIC", "公开", "知道链接的人可重复查看"))
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("分享轨迹", style = MaterialTheme.typography.titleLarge)
            Text("公开链接生成一次即可反复分享（重复生成返回同一条）；切换分享模式会使旧链接失效", color = Muted, style = MaterialTheme.typography.bodySmall)
            options.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { viewModel.pick(option.id) }.background(if (state.selectedMode == option.id) Color(0xFFF2FCF7) else Color.White, RoundedCornerShape(8.dp))
                    .border(1.dp, if (state.selectedMode == option.id) Green500 else Line, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    RadioButton(state.selectedMode == option.id, { viewModel.pick(option.id) }); Spacer(Modifier.width(8.dp))
                    Column { Text(option.title, fontWeight = FontWeight.Bold); Text(option.detail, color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (state.selectedMode == "ONCE") Text("链接一经打开即失效，请确认接收者能够立即查看。", color = Warning, style = MaterialTheme.typography.bodySmall)
            val done = state.result as? ShareResult.Done
            val doneUrl = done?.url
            if (doneUrl != null) {
                // 生成即复制：无需用户二次点击复制按钮
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
            Button(viewModel::generate, Modifier.fillMaxWidth().height(48.dp), enabled = state.result !is ShareResult.Loading, shape = RoundedCornerShape(24.dp)) { Text(if (state.selectedMode == "PRIVATE") "设为私密" else "生成新链接") }
        }
    }
}
