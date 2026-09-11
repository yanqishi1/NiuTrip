package com.niutrip.app.service

import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface LocResult {
    data class Success(val lon: Double, val lat: Double, val timeMillis: Long) : LocResult
    data class Failure(val reason: String) : LocResult
}

fun interface LocationSource { suspend fun singleShot(): LocResult }

class AMapLocationSource(context: Context) : LocationSource {
    private val appContext = context.applicationContext
    override suspend fun singleShot(): LocResult = suspendCancellableCoroutine { continuation ->
        val client = AMapLocationClient(appContext)
        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = true
            isOnceLocationLatest = true
            httpTimeOut = 15_000
        }
        client.setLocationOption(option)
        client.setLocationListener { location ->
            if (!continuation.isActive) return@setLocationListener
            val result = if (location != null && location.errorCode == 0) {
                LocResult.Success(location.longitude, location.latitude, location.time)
            } else LocResult.Failure(location?.errorInfo ?: "定位失败")
            continuation.resume(result)
            client.stopLocation(); client.onDestroy()
        }
        continuation.invokeOnCancellation { client.stopLocation(); client.onDestroy() }
        client.startLocation()
    }
}
