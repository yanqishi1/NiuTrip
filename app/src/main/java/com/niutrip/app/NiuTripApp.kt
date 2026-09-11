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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class NiuTripApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
        container = AppContainer(this)
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
