package com.niutrip.app.ui.checkin

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.core.businessId
import com.niutrip.app.core.toApiTime
import com.niutrip.app.core.toBeijingDateTime
import com.niutrip.app.data.remote.*
import com.niutrip.app.service.LocResult
import com.niutrip.app.service.LocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

sealed interface CheckinResult { data object Idle : CheckinResult; data object Loading : CheckinResult; data object Done : CheckinResult; data class Error(val message: String) : CheckinResult }
data class CheckinState(
    val locating: Boolean = true,
    val lon: Double? = null,
    val lat: Double? = null,
    val name: String = "",
    val desc: String = "",
    val photos: List<Uri> = emptyList(),
    val result: CheckinResult = CheckinResult.Idle,
)

class CheckinViewModel(
    private val trackId: String,
    private val api: ApiService,
    private val source: LocationSource,
    private val compressor: ImageCompressor,
) : ViewModel() {
    private val _state = MutableStateFlow(CheckinState()); val state = _state.asStateFlow()
    init { locate() }
    fun setName(value: String) = _state.update { it.copy(name = value) }
    fun setDesc(value: String) = _state.update { it.copy(desc = value) }
    fun addPhotos(values: List<Uri>) = _state.update { old -> old.copy(photos = (old.photos + values).distinct().take(9), result = if (old.photos.size + values.size > 9) CheckinResult.Error("最多选择 9 张图片") else old.result) }
    fun removePhoto(uri: Uri) = _state.update { it.copy(photos = it.photos - uri) }
    fun locate() = viewModelScope.launch {
        _state.update { it.copy(locating = true) }
        when (val result = source.singleShot()) {
            is LocResult.Success -> _state.update { it.copy(locating = false, lon = result.lon, lat = result.lat) }
            is LocResult.Failure -> _state.update { it.copy(locating = false, result = CheckinResult.Error(result.reason)) }
        }
    }
    fun submit() = viewModelScope.launch {
        val current = _state.value
        val lon = current.lon; val lat = current.lat
        if (lon == null || lat == null) { _state.update { it.copy(result = CheckinResult.Error("尚未获取当前位置")) }; return@launch }
        _state.update { it.copy(result = CheckinResult.Loading) }
        try {
            val urls = current.photos.map { uri ->
                val file = withContext(Dispatchers.IO) { compressor.compressToUnder1Mb(uri) }
                val part = MultipartBody.Part.createFormData("image", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                apiCall { api.upload(part) }.url
            }
            val now = System.currentTimeMillis()
            apiCall { api.postPoints(trackId, PointsIn(listOf(PointIn(businessId(), lon, lat,
                current.name.ifBlank { null }, current.desc.ifBlank { null }, urls,
                now.toBeijingDateTime().toApiTime(), "MANUAL")))) }
            _state.update { it.copy(result = CheckinResult.Done) }
        } catch (error: Throwable) { _state.update { it.copy(result = CheckinResult.Error(error.message ?: "发布失败")) } }
    }
}
