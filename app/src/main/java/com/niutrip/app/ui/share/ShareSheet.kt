package com.niutrip.app.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
            Text("切换模式会生成新链接，旧链接立即失效", color = Muted, style = MaterialTheme.typography.bodySmall)
            options.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { viewModel.pick(option.id) }.background(if (state.selectedMode == option.id) Color(0xFFF2FCF7) else Color.White, RoundedCornerShape(8.dp))
                    .border(1.dp, if (state.selectedMode == option.id) Green500 else Line, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    RadioButton(state.selectedMode == option.id, { viewModel.pick(option.id) }); Spacer(Modifier.width(8.dp))
                    Column { Text(option.title, fontWeight = FontWeight.Bold); Text(option.detail, color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (state.selectedMode == "ONCE") Text("链接一经打开即失效，请确认接收者能够立即查看。", color = Warning, style = MaterialTheme.typography.bodySmall)
            val done = state.result as? ShareResult.Done
            if (done?.url != null) {
                Text(done.url, Modifier.fillMaxWidth().background(Background, RoundedCornerShape(8.dp)).padding(12.dp), maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NiuTrip 分享链接", done.url)) }, Modifier.weight(1f)) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("复制") }
                    OutlinedButton({ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, done.url) }, "分享轨迹")) }, Modifier.weight(1f)) { Icon(Icons.Default.IosShare, null); Spacer(Modifier.width(6.dp)); Text("更多") }
                }
            }
            (state.result as? ShareResult.Error)?.let { Text(it.message, color = Danger) }
            Button(viewModel::generate, Modifier.fillMaxWidth().height(48.dp), enabled = state.result !is ShareResult.Loading, shape = RoundedCornerShape(24.dp)) { Text(if (state.selectedMode == "PRIVATE") "设为私密" else "生成新链接") }
        }
    }
}
