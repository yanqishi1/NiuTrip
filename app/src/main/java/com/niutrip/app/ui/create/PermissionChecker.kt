package com.niutrip.app.ui.create

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

interface PermissionChecker { fun hasFineLocation(): Boolean; fun hasBackgroundLocation(): Boolean; fun hasBatteryWhitelist(): Boolean }

data class RecordingPermissionStatus(
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val batteryWhitelist: Boolean,
    val notifications: Boolean,
) {
    val canRecordInBackground: Boolean get() = fineLocation && backgroundLocation && batteryWhitelist
}

class AndroidPermissionChecker(private val context: Context) : PermissionChecker {
    override fun hasFineLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun hasBackgroundLocation() = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun hasBatteryWhitelist() = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun status() = RecordingPermissionStatus(
        fineLocation = hasFineLocation(),
        backgroundLocation = hasBackgroundLocation(),
        batteryWhitelist = hasBatteryWhitelist(),
        notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    )
}

fun openAppPermissionSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)))
}

fun requestBatteryWhitelist(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(request) }.getOrElse { openAppPermissionSettings(context) }
}
