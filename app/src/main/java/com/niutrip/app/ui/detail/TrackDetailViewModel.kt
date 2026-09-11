package com.niutrip.app.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.core.*
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.TrackDto
import com.niutrip.app.data.remote.TrackPatchIn
import com.niutrip.app.data.repo.SyncRepository
import com.niutrip.app.data.repo.TrackRepository
import com.niutrip.app.service.LocResult
import com.niutrip.app.service.LocationSource
import com.niutrip.app.ui.checkin.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DetailState(
    val loading: Boolean = true,
    val track: TrackDto? = null,
    val days: List<DayGroup> = emptyList(),
    val points: List<PointDto> = emptyList(),
    val selectedDay: Int = -1,
    val error: String? = null,
    val deleted: Boolean = false,
    val current: PointLite? = null,  // 空轨迹时的当前位置，供地图聚焦
    val updatingImage: Boolean = false,
)

class TrackDetailViewModel(
    private val id: String,
    private val repository: TrackRepository,
    private val locationSource: LocationSource,
    syncRepository: SyncRepository,
    private val imageCompressor: ImageCompressor? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailState()); val state = _state.asStateFlow()

    init {
        // 本轨迹的点刚被上传（开始记录首点/自动采集/离线补传）→ 原地刷新，用户无需退出重进
        viewModelScope.launch {
            syncRepository.uploads.collect { (trackId, _) -> if (trackId == id) load() }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // 静默刷新：已有内容时不闪全屏 loading；失败时保留旧内容（只有首屏失败才显示错误页）
    fun load() = viewModelScope.launch {
        if (_state.value.track == null) _state.update { it.copy(loading = true, error = null) }
        try {
            val track = repository.detail(id); val points = repository.points(id)
            _state.update { it.copy(loading = false, error = null, track = track,
                days = DayGrouper.group(points.mapNotNull(PointDto::toLite)), points = points) }
            if (points.isEmpty()) locateCurrent()
        } catch (error: Throwable) {
            if (_state.value.track == null) _state.update { it.copy(loading = false, error = error.message ?: "加载失败") }
        }
    }

    // 空轨迹：取当前位置给地图聚焦；失败静默，保持默认视角
    private fun locateCurrent() = viewModelScope.launch {
        when (val location = locationSource.singleShot()) {
            is LocResult.Success -> {
                val time = if (location.timeMillis > 0) location.timeMillis else System.currentTimeMillis()
                _state.update { it.copy(current = PointLite("current", time.toBeijingDateTime(),
                    location.lon, location.lat, false, null, null, emptyList())) }
            }
            is LocResult.Failure -> Unit
        }
    }
    fun selectDay(index: Int) = _state.update { it.copy(selectedDay = index) }
    fun changeStatus(status: String, onChanged: (TrackDto) -> Unit = {}) = viewModelScope.launch {
        runCatching { repository.patch(id, TrackPatchIn(track_status = status)) }.onSuccess { track ->
            _state.update { it.copy(track = track) }; onChanged(track)
        }.onFailure { error -> _state.update { it.copy(error = error.message) } }
    }
    fun rename(name: String) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        runCatching { repository.patch(id, TrackPatchIn(track_name = name.trim())) }.onSuccess { value -> _state.update { it.copy(track = value) } }
            .onFailure { error -> _state.update { it.copy(error = error.message) } }
    }
    fun updateImage(uri: Uri) = viewModelScope.launch {
        val compressor = imageCompressor
        if (compressor == null) {
            _state.update { it.copy(error = "当前无法编辑代表图") }
            return@launch
        }
        _state.update { it.copy(updatingImage = true, error = null) }
        runCatching {
            val image = withContext(Dispatchers.IO) { compressor.prepareForUpload(uri) }
            try {
                repository.updateImage(id, image.file, image.mediaType)
            } finally {
                withContext(Dispatchers.IO) { image.file.delete() }
            }
        }.onSuccess { track ->
            _state.update { it.copy(track = track, updatingImage = false) }
        }.onFailure { error ->
            _state.update { it.copy(updatingImage = false, error = error.message ?: "代表图更新失败") }
        }
    }
    fun delete() = viewModelScope.launch {
        runCatching { repository.delete(id) }.onSuccess { _state.update { it.copy(deleted = true) } }
            .onFailure { error -> _state.update { it.copy(error = error.message) } }
    }
}
