package com.niutrip.app.ui.common

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

data class CameraImage(val file: File, val uri: Uri)

fun createCameraImage(context: Context): CameraImage {
    val directory = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File.createTempFile("photo_", ".jpg", directory)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return CameraImage(file, uri)
}

fun hasCamera(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ImageSourceSheet(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    cameraAvailable: Boolean,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ListItem(
            headlineContent = { Text("拍照") },
            supportingContent = if (cameraAvailable) null else ({ Text("当前设备没有可用相机") }),
            leadingContent = { Icon(Icons.Outlined.CameraAlt, null) },
            modifier = Modifier.fillMaxWidth().clickable(enabled = cameraAvailable, onClick = onTakePhoto),
        )
        ListItem(
            headlineContent = { Text("从相册选择") },
            leadingContent = { Icon(Icons.Outlined.PhotoLibrary, null) },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onChoosePhoto),
        )
        Spacer(Modifier.height(8.dp).navigationBarsPadding())
    }
}
