package com.niutrip.app.ui.profile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.niutrip.app.BuildConfig
import com.niutrip.app.ui.theme.*

private enum class ProfileDialog { RENAME, PASSWORD, BINDING, LOGOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ProfileScreen(viewModel: ProfileViewModel, onLogout: () -> Unit) {
    val state by viewModel.state.collectAsState(); val context = LocalContext.current
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(viewModel::updateAvatar) }
    fun granted(permission: String): Boolean = androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    Scaffold(topBar = { TopAppBar(title = { Text("我的", fontWeight = FontWeight.Bold) }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Green50).clickable { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, contentAlignment = Alignment.Center) {
                    if (!state.user?.avata_url.isNullOrBlank()) AsyncImage(absoluteMedia(state.user!!.avata_url!!), null, Modifier.fillMaxSize()) else Icon(Icons.Outlined.Person, null, tint = Green700, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(state.user?.username ?: "旅行者", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium); Text(state.user?.user_id.orEmpty(), color = Muted, style = MaterialTheme.typography.bodySmall) }
                IconButton({ dialog = ProfileDialog.RENAME }) { Icon(Icons.Default.Edit, "编辑用户名") }
            }
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceAround) {
                ProfileStat(state.tracks, "轨迹"); ProfileStat(state.checkins, "打卡"); ProfileStat(state.shared, "收到分享")
            }
            SectionLabel("应用权限")
            Column(Modifier.background(Color.White, RoundedCornerShape(8.dp))) {
                PermissionRow("精确定位", granted(Manifest.permission.ACCESS_FINE_LOCATION), Icons.Outlined.LocationOn)
                PermissionRow("后台定位", Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION), Icons.Outlined.Route)
                PermissionRow("通知", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS), Icons.Outlined.Notifications)
                PermissionRow("相机", granted(Manifest.permission.CAMERA), Icons.Outlined.CameraAlt)
            }
            SectionLabel("账号与安全")
            Column(Modifier.background(Color.White, RoundedCornerShape(8.dp))) {
                ActionRow("手机号 / 邮箱", state.user?.phone ?: state.user?.email ?: "未绑定", Icons.Outlined.AlternateEmail) { dialog = ProfileDialog.BINDING }
                ActionRow("修改密码", "", Icons.Outlined.Lock) { dialog = ProfileDialog.PASSWORD }
            }
            OutlinedButton({ dialog = ProfileDialog.LOGOUT }, Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Icon(Icons.AutoMirrored.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("退出登录") }
            Text("旅行牛牛 ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth(), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    state.message?.let { AlertDialog(viewModel::clearMessage, text = { Text(it) }, confirmButton = { TextButton(viewModel::clearMessage) { Text("知道了") } }) }
    when (dialog) {
        ProfileDialog.RENAME -> TextInputDialog("修改用户名", state.user?.username.orEmpty(), { dialog = null }) { viewModel.rename(it); dialog = null }
        ProfileDialog.PASSWORD -> PasswordDialog({ dialog = null }) { old, new -> viewModel.changePassword(old, new); dialog = null }
        ProfileDialog.BINDING -> BindingDialog({ dialog = null }) { password, phone, email -> viewModel.updateBinding(password, phone, email); dialog = null }
        ProfileDialog.LOGOUT -> AlertDialog({ dialog = null }, title = { Text("退出登录？") }, text = { Text(if (state.recording) "当前仍有轨迹正在记录。退出会停止本机记录服务。" else "确认退出当前账号？") }, confirmButton = { TextButton({ viewModel.logout(); dialog = null; onLogout() }) { Text("退出", color = Danger) } }, dismissButton = { TextButton({ dialog = null }) { Text("取消") } })
        null -> Unit
    }
}

@Composable private fun SectionLabel(value: String) { Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
@Composable private fun ProfileStat(value: Int, label: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value.toString(), fontWeight = FontWeight.Bold); Text(label, color = Muted, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun PermissionRow(label: String, ok: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (ok) Green700 else Warning); Spacer(Modifier.width(12.dp)); Text(label, Modifier.weight(1f)); Text(if (ok) "已允许" else "去设置", color = if (ok) Green700 else Warning, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun ActionRow(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Green700); Spacer(Modifier.width(12.dp)); Text(label, Modifier.weight(1f)); Text(value, color = Muted, style = MaterialTheme.typography.bodySmall); Icon(Icons.Default.ChevronRight, null, tint = Muted) } }

@Composable private fun TextInputDialog(title: String, initial: String, dismiss: () -> Unit, confirm: (String) -> Unit) { var value by remember { mutableStateOf(initial) }; AlertDialog(dismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, singleLine = true) }, confirmButton = { TextButton({ confirm(value) }, enabled = value.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(dismiss) { Text("取消") } }) }
@Composable private fun PasswordDialog(dismiss: () -> Unit, confirm: (String, String) -> Unit) { var old by remember { mutableStateOf("") }; var new by remember { mutableStateOf("") }; AlertDialog(dismiss, title = { Text("修改密码") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(old, { old = it }, label = { Text("当前密码") }); OutlinedTextField(new, { new = it }, label = { Text("新密码（至少 8 位）") }) } }, confirmButton = { TextButton({ confirm(old, new) }, enabled = old.isNotBlank() && new.length >= 8) { Text("保存") } }, dismissButton = { TextButton(dismiss) { Text("取消") } }) }
@Composable private fun BindingDialog(dismiss: () -> Unit, confirm: (String, String?, String?) -> Unit) { var password by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }; AlertDialog(dismiss, title = { Text("更新绑定") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(phone, { phone = it }, label = { Text("手机号") }); OutlinedTextField(email, { email = it }, label = { Text("邮箱") }); OutlinedTextField(password, { password = it }, label = { Text("当前密码") }) } }, confirmButton = { TextButton({ confirm(password, phone, email) }, enabled = password.isNotBlank() && (phone.isNotBlank() || email.isNotBlank())) { Text("保存") } }, dismissButton = { TextButton(dismiss) { Text("取消") } }) }
private fun absoluteMedia(path: String): String = if (path.startsWith("http")) path else BuildConfig.API_BASE_URL.substringBefore("/api/") + path
