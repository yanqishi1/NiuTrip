package com.niutrip.app.service

import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface LocResult {
    data class Success(
        val lon: Double,
        val lat: Double,
        val timeMillis: Long,
        val accuracyMeters: Float = Float.NaN,
        val speedMps: Float = Float.NaN,
        val bearingDegrees: Float = Float.NaN,
        val elapsedRealtimeMillis: Long = 0L,
        val locationType: Int = 0,
    ) : LocResult
    data class Failure(val reason: String) : LocResult
}

fun interface LocationSource {
    suspend fun singleShot(): LocResult

    fun updates(profile: LocationProfile): Flow<LocResult> = flow {
        while (currentCoroutineContext().isActive) {
            emit(singleShot())
            delay(profile.intervalMs)
        }
    }
}

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
                location.toResult()
            } else LocResult.Failure(location?.errorInfo ?: "定位失败")
            continuation.resume(result)
            client.stopLocation(); client.onDestroy()
        }
        continuation.invokeOnCancellation { client.stopLocation(); client.onDestroy() }
        client.startLocation()
    }

    override fun updates(profile: LocationProfile): Flow<LocResult> = callbackFlow {
        val client = runCatching { AMapLocationClient(appContext) }.getOrElse {
            trySend(LocResult.Failure(it.message ?: "定位初始化失败"))
            close(it)
            return@callbackFlow
        }
        val option = AMapLocationClientOption().apply {
            locationMode = when (profile.powerMode) {
                LocationPowerMode.HIGH_ACCURACY -> AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                LocationPowerMode.BALANCED -> AMapLocationClientOption.AMapLocationMode.Battery_Saving
            }
            isOnceLocation = false
            interval = profile.intervalMs
            isNeedAddress = false
            httpTimeOut = 15_000
        }
        client.setLocationOption(option)
        client.setLocationListener { location ->
            val result = if (location != null && location.errorCode == 0) {
                location.toResult()
            } else LocResult.Failure(location?.errorInfo ?: "定位失败")
            trySend(result)
        }
        runCatching { client.startLocation() }.onFailure {
            trySend(LocResult.Failure(it.message ?: "定位启动失败"))
            close(it)
        }
        awaitClose {
            client.stopLocation()
            client.onDestroy()
        }
    }

    private fun com.amap.api.location.AMapLocation.toResult() = LocResult.Success(
        lon = longitude,
        lat = latitude,
        timeMillis = time,
        accuracyMeters = accuracy,
        speedMps = speed,
        bearingDegrees = bearing,
        elapsedRealtimeMillis = elapsedRealtimeNanos.takeIf { it > 0L }?.div(1_000_000L) ?: 0L,
        locationType = locationType,
    )
}
