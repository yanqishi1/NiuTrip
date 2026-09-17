package com.niutrip.app.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.BuildConfig
import com.niutrip.app.data.remote.ApiService
import com.niutrip.app.data.remote.ShareIn
import com.niutrip.app.data.remote.apiCall
import com.niutrip.app.data.remote.TrackDto
import com.niutrip.app.data.repo.TrackRepository
import com.niutrip.app.data.repo.SyncRepository
import com.niutrip.app.data.repo.FlushResult
import com.niutrip.app.core.DayGroup
import com.niutrip.app.core.DayGrouper
import com.niutrip.app.ui.detail.mergeTrackPoints
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ShareResult { data object Idle : ShareResult; data object Loading : ShareResult; data class Done(val mode: String, val url: String?) : ShareResult; data class Error(val message: String) : ShareResult }
enum class ShareKind { IMAGE, LINK }
data class TrackImageData(val track: TrackDto, val days: List<DayGroup>)
sealed interface ImageResult {
    data object Idle : ImageResult
    data object Loading : ImageResult
    data class Ready(val file: File) : ImageResult
    data class Error(val message: String) : ImageResult
}
data class ShareState(
    val selectedMode: String = "PRIVATE",
    val result: ShareResult = ShareResult.Idle,
    val track: TrackDto? = null,
    val loading: Boolean = true,
    val loadError: String? = null,
    val kind: ShareKind = ShareKind.LINK,
    val image: ImageResult = ImageResult.Idle,
    val imageData: TrackImageData? = null,
    val warning: String? = null,
)

/** 分享文案（百度网盘式）：URL 原样独占一行（接收端 DeepLinkHandler 按空白截断提取、
 *  忽略其余说明文字），后附使用方式说明，引导接收方回到 App 打开。 */
fun buildShareText(url: String): String =
    "【旅行牛牛 旅行轨迹分享】\n$url\n点击这个链接，打开网页可以直接查看轨迹。\n复制这条消息，打开旅行牛牛 App 即可查看并保存这条轨迹"

class ShareViewModel(
    private val trackId: String,
    private val api: ApiService,
    private val repository: TrackRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ShareState()); val state = _state.asStateFlow()
    private var imageJob: Job? = null
    private var loadJob: Job? = null
    private var linkJob: Job? = null
    fun load() {
        loadJob?.cancel()
        linkJob?.cancel()
        cancelImage()
        _state.value = ShareState()
        loadJob = viewModelScope.launch {
            try {
                val track = repository.detail(trackId)
                _state.update { it.copy(track = track, loading = false, selectedMode = track.share_mode,
                    kind = if (track.track_status == "FINISHED") ShareKind.IMAGE else ShareKind.LINK) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(loading = false, loadError = error.message ?: "加载失败") }
            }
        }
    }
    fun selectKind(kind: ShareKind) {
        if (kind == ShareKind.IMAGE && _state.value.track?.track_status != "FINISHED") return
        _state.update { it.copy(kind = kind) }
    }
    fun pick(mode: String) {
        if (_state.value.result is ShareResult.Loading) return
        _state.update { it.copy(selectedMode = mode, result = ShareResult.Idle, warning = null) }
    }
    fun generate() {
        if (_state.value.result is ShareResult.Loading) return
        val mode = _state.value.selectedMode
        _state.update { it.copy(result = ShareResult.Loading, warning = null) }
        linkJob = viewModelScope.launch {
            try {
                if (mode != "PRIVATE" && syncRepository.flushTrack(trackId) is FlushResult.Failed) {
                    _state.update { it.copy(warning = "部分轨迹点尚未上传，链接可能缺少这些点；轨迹图片仍可包含本地点") }
                }
                val response = apiCall { api.share(trackId, ShareIn(mode)) }
                _state.update { it.copy(result = ShareResult.Done(response.share_mode, response.share_url?.let { path -> BuildConfig.SHARE_BASE_URL + path }),
                    track = it.track?.copy(share_mode = response.share_mode)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(result = ShareResult.Error(error.message ?: "生成失败")) }
            }
        }
    }

    fun generateImage() {
        val track = _state.value.track ?: return
        if (track.track_status != "FINISHED" || _state.value.image is ImageResult.Loading) return
        _state.update { it.copy(image = ImageResult.Loading, imageData = null) }
        imageJob = viewModelScope.launch {
            try {
                val localBefore = syncRepository.localPoints(trackId)
                val points = mergeTrackPoints(repository.points(trackId),
                    localBefore + syncRepository.localPoints(trackId))
                    .mapNotNull { it.toLite() }
                    .filter { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
                require(points.isNotEmpty()) { "暂无有效轨迹点，无法生成图片" }
                _state.update { it.copy(imageData = TrackImageData(track, DayGrouper.group(points))) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                imageFailed(error.message ?: "图片生成失败")
            }
        }
    }
    fun imageReady(file: File) = _state.update {
        if (it.image is ImageResult.Loading) it.copy(image = ImageResult.Ready(file), imageData = null) else it
    }
    fun imageFailed(message: String) = _state.update {
        if (it.image is ImageResult.Loading) it.copy(image = ImageResult.Error(message), imageData = null) else it
    }
    fun cancelImage() {
        imageJob?.cancel()
        _state.update { if (it.image is ImageResult.Loading) it.copy(image = ImageResult.Idle, imageData = null) else it }
    }
}
