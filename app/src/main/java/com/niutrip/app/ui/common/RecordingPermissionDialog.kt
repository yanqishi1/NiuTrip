package com.niutrip.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.niutrip.app.ui.create.RecordingPermissionStatus
import com.niutrip.app.ui.theme.Green700

@Composable fun RecordingPermissionDialog(
    status: RecordingPermissionStatus,
    onFineLocation: () -> Unit,
    onBackgroundLocation: () -> Unit,
    onBatteryWhitelist: () -> Unit,
    onNotifications: () -> Unit,
    onDismiss: () -> Unit,
    onReady: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("允许后台记录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("自动轨迹需要以下权限。切换到其他 APP 或锁屏后，系统仍会通过前台服务每 10 分钟记录位置。")
                Spacer(Modifier.height(2.dp))
                PermissionAction("精确定位", status.fineLocation, Icons.Outlined.LocationOn, onFineLocation)
                PermissionAction("始终允许定位", status.backgroundLocation, Icons.Outlined.LocationOn, onBackgroundLocation)
                PermissionAction("允许后台运行", status.batteryWhitelist, Icons.Outlined.BatterySaver, onBatteryWhitelist)
                PermissionAction("显示记录通知", status.notifications, Icons.Outlined.Notifications, onNotifications, required = false)
            }
        },
        confirmButton = {
            TextButton(onClick = onReady, enabled = status.canRecordInBackground) { Text("开始记录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable private fun PermissionAction(
    label: String,
    granted: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
    required: Boolean = true,
) {
    OutlinedButton(onClick = onClick, enabled = !granted, modifier = Modifier.fillMaxWidth()) {
        Icon(if (granted) Icons.Outlined.CheckCircle else icon, null, tint = if (granted) Green700 else androidx.compose.ui.graphics.Color.Unspecified)
        Spacer(Modifier.weight(1f))
        Text(when {
            granted -> "$label：已允许"
            required -> label
            else -> "$label（建议）"
        })
    }
}
