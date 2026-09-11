package com.niutrip.app.ui.create

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

interface PermissionChecker { fun hasFineLocation(): Boolean; fun hasBackgroundLocation(): Boolean; fun hasBatteryWhitelist(): Boolean }

class AndroidPermissionChecker(private val context: Context) : PermissionChecker {
    override fun hasFineLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun hasBackgroundLocation() = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun hasBatteryWhitelist() = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}
