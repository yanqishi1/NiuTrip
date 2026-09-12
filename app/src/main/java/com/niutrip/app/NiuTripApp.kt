package com.niutrip.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.room.Room
import com.amap.api.location.AMapLocationClient
import com.niutrip.app.data.TokenStore
import com.niutrip.app.data.local.AppDatabase
import com.niutrip.app.data.remote.ApiService
import com.niutrip.app.data.remote.AuthInterceptor
import com.niutrip.app.data.repo.AuthRepository
import com.niutrip.app.data.repo.SyncRepository
import com.niutrip.app.data.repo.TrackRepository
import com.niutrip.app.service.AMapLocationSource
import com.niutrip.app.service.LocationSource
import com.niutrip.app.service.TrackRecordingService
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class NiuTripApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
        container = AppContainer(this)
        if (container.tokenStore.token != null && !container.tokenStore.hasAvatarSnapshot) {
            container.applicationScope.launch {
                runCatching { container.api.profile() }
                    .onSuccess { container.tokenStore.saveAvatarUrl(it.avata_url) }
            }
        }
        // 系统回收进程后，SharedPreferences 中仍保留正在自动记录的轨迹；进程重建时恢复前台服务。
        runCatching { TrackRecordingService.resumeIfActive(this) }
        // 离线补传触发：App 启动 + 网络恢复（记录中的采集点另有 afterCollect 触发）。
        // 网络回调是非关键增强，注册失败不应炸掉启动路径
        container.applicationScope.launch { container.syncRepository.flushOnce() }
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        container.applicationScope.launch { container.syncRepository.flushOnce() }
                    }
                })
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.20)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("coil_image_cache"))
                .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                .build()
        }
        .okHttpClient {
            OkHttpClient.Builder()
                .addNetworkInterceptor { chain ->
                    chain.proceed(chain.request()).withOneDayImageCache()
                }
                .build()
        }
        .build()
}

internal const val IMAGE_CACHE_MAX_AGE_SECONDS = 24 * 60 * 60
private const val IMAGE_DISK_CACHE_BYTES = 100L * 1024 * 1024

internal fun Response.withOneDayImageCache(): Response {
    if (!isSuccessful) return this
    return newBuilder()
        .removeHeader("Pragma")
        .removeHeader("Expires")
        .header("Cache-Control", "public, max-age=$IMAGE_CACHE_MAX_AGE_SECONDS")
        .build()
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class AppContainer(app: Application) {
    // 随进程存活的 scope：网络回调/service 销毁时的收尾补传挂在这里（不随组件生命周期取消）
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val tokenStore = TokenStore(app)
    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "niutrip.db").fallbackToDestructiveMigration().build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenStore))
        .addInterceptor(HttpLoggingInterceptor().apply { level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE })
        .build()
    val api: ApiService = Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL).client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(ApiService::class.java)
    val authRepository = AuthRepository(api, tokenStore)
    val trackRepository = TrackRepository(api, db.trackDao())
    val syncRepository = SyncRepository(api, db.pendingPointDao())
    val locationSource: LocationSource = AMapLocationSource(app)
}
