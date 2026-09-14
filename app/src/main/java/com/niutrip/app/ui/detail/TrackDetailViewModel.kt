package com.niutrip.app.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.core.*
import com.niutrip.app.data.remote.PointDto
import com.niutrip.app.data.remote.PointPatchIn
import com.niutrip.app.data.remote.TrackDto
import com.niutrip.app.data.remote.TrackPatchIn
import com.niutrip.app.data.repo.SyncRepository
import com.niutrip.app.data.repo.TrackRepository
import com.niutrip.app.service.LocResult
import com.niutrip.app.service.LocationSource
import com.niutrip.app.ui.checkin.ImagePreparer
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
    val locatingCurrent: Boolean = false,
    val currentFocusRequest: Int = 0,
    val updatingImage: Boolean = false,
    val updatingCheckin: Boolean = false,
)

class TrackDetailViewModel(
    private val id: String,
    private val repository: TrackRepository,
    private val locationSource: LocationSource,
    syncRepository: SyncRepository,
    private val imagePreparer: ImagePreparer,
    private val recordSharedView: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailState()); val state = _state.asStateFlow()
    private var viewRequestSent = false

    init {
        // 本轨迹的点刚被上传（开始记录首点/自动采集/离线补传）→ 原地刷新，用户无需退出重进
        viewModelScope.launch {
            syncRepository.uploads.collect { (trackId, _) -> if (trackId == id) load() }
        }
        // 本地写入成功即移动当前位置图标；不增加 focus request，避免采集时强制移动地图视角
        viewModelScope.launch {
            syncRepository.recordedPoints.collect { point ->
                if (point.trackId == id) updateCurrent(point.lon, point.lat, point.timeMillis)
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun showError(message: String) = _state.update { it.copy(error = message) }

    // 静默刷新：已有内容时不闪全屏 loading；失败时保留旧内容（只有首屏失败才显示错误页）
    fun load() = viewModelScope.launch {
        if (_state.value.track == null) _state.update { it.copy(loading = true, error = null) }
        val shouldRecordView = recordSharedView && !viewRequestSent
        if (shouldRecordView) viewRequestSent = true
        var detailLoaded = false
        try {
            val track = repository.detail(id, recordView = shouldRecordView)
            detailLoaded = true
            val points = repository.points(id)
            val litePoints = points.mapNotNull(PointDto::toLite)
            val recordedFallback = if (track.track_status == "RECORDING" && track.track_record_mode == "AUTO") {
                litePoints.maxByOrNull(PointLite::time)?.copy(id = "current")
            } else null
            _state.update { old ->
                val current = recordedFallback?.takeIf { fallback ->
                    old.current == null || fallback.time.isAfter(old.current.time)
                } ?: old.current
                old.copy(loading = false, error = null, track = track,
                    days = DayGrouper.group(litePoints), points = points, current = current)
            }
            if (points.isEmpty() && _state.value.current == null) locateCurrent(focus = true, showError = false)
        } catch (error: Throwable) {
            if (shouldRecordView && !detailLoaded) viewRequestSent = false
            if (_state.value.track == null) _state.update { it.copy(loading = false, error = error.message ?: "加载失败") }
        }
    }

    fun focusCurrentLocation() = locateCurrent(focus = true, showError = true)

    private fun updateCurrent(lon: Double, lat: Double, timeMillis: Long) {
        val time = (if (timeMillis > 0) timeMillis else System.currentTimeMillis()).toBeijingDateTime()
        _state.update { old ->
            if (old.current?.time?.isAfter(time) == true) old
            else old.copy(current = PointLite("current", time, lon, lat, false))
        }
    }

    private fun locateCurrent(focus: Boolean, showError: Boolean) = viewModelScope.launch {
        _state.update { it.copy(locatingCurrent = true) }
        when (val location = locationSource.singleShot()) {
            is LocResult.Success -> {
                val time = if (location.timeMillis > 0) location.timeMillis else System.currentTimeMillis()
                _state.update { it.copy(current = PointLite("current", time.toBeijingDateTime(),
                    location.lon, location.lat, false, null, null, emptyList()),
                    locatingCurrent = false,
                    currentFocusRequest = if (focus) it.currentFocusRequest + 1 else it.currentFocusRequest) }
            }
            is LocResult.Failure -> _state.update {
                it.copy(locatingCurrent = false,
                    error = if (showError) location.reason else it.error)
            }
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
        _state.update { it.copy(updatingImage = true, error = null) }
        runCatching {
            val image = withContext(Dispatchers.IO) { imagePreparer.prepareForUpload(uri) }
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
    fun updateCheckin(
        pointId: String,
        name: String,
        desc: String,
        existingImages: List<String>,
        newImages: List<Uri>,
        onDone: () -> Unit = {},
    ) = viewModelScope.launch {
        if (existingImages.size + newImages.size > 9) {
            _state.update { it.copy(error = "最多保留 9 张图片") }
            return@launch
        }
        _state.update { it.copy(updatingCheckin = true, error = null) }
        runCatching {
            val uploaded = newImages.map { uri ->
                val image = withContext(Dispatchers.IO) { imagePreparer.prepareForUpload(uri) }
                try {
                    repository.uploadImage(image.file, image.mediaType)
                } finally {
                    withContext(Dispatchers.IO) { image.file.delete() }
                }
            }
            repository.updateCheckin(id, pointId, PointPatchIn(
                point_name = name.trim(),
                point_desc = desc.trim(),
                point_img_url = existingImages + uploaded,
            ))
        }.onSuccess { updated ->
            _state.update { old ->
                val points = old.points.map { if (it.point_id == updated.point_id) updated else it }
                old.copy(points = points, days = DayGrouper.group(points.mapNotNull(PointDto::toLite)),
                    updatingCheckin = false)
            }
            onDone()
        }.onFailure { error ->
            _state.update { it.copy(updatingCheckin = false, error = error.message ?: "打卡更新失败") }
        }
    }
    fun delete() = viewModelScope.launch {
        runCatching { repository.delete(id) }.onSuccess { _state.update { it.copy(deleted = true) } }
            .onFailure { error -> _state.update { it.copy(error = error.message) } }
    }
}
