package com.niutrip.app.ui.shareview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.core.DayGroup
import com.niutrip.app.core.DayGrouper
import com.niutrip.app.core.PointLite
import com.niutrip.app.core.toBeijingDateTime
import com.niutrip.app.data.remote.ApiException
import com.niutrip.app.data.remote.ApiService
import com.niutrip.app.data.remote.ShareDataDto
import com.niutrip.app.data.remote.apiCall
import com.niutrip.app.service.LocResult
import com.niutrip.app.service.LocationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareViewState(
    val loading: Boolean = true,
    val data: ShareDataDto? = null,
    val days: List<DayGroup> = emptyList(),
    val selectedDay: Int = -1,
    val gone: Boolean = false,
    val error: String? = null,
    val current: PointLite? = null,
    val locatingCurrent: Boolean = false,
    val currentFocusRequest: Int = 0,
    val locationError: String? = null,
)

class ShareViewViewModel(
    private val token: String,
    private val api: ApiService,
    private val locationSource: LocationSource,
) : ViewModel() {
    private val _state = MutableStateFlow(ShareViewState()); val state = _state.asStateFlow()
    init { saveAndLoad() }
    fun saveAndLoad() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val data = apiCall { api.shareSave(token) }
            val points = data.days.flatMap { it.points }.mapNotNull { it.toLite() }
            _state.value = ShareViewState(false, data, DayGrouper.group(points))
        } catch (error: Throwable) {
            if ((error as? ApiException)?.code == 404) _state.value = ShareViewState(loading = false, gone = true)
            else _state.update { it.copy(loading = false, error = error.message ?: "加载失败") }
        }
    }
    fun selectDay(index: Int) = _state.update { it.copy(selectedDay = index) }
    fun showLocationError(message: String) = _state.update { it.copy(locationError = message) }
    fun dismissLocationError() = _state.update { it.copy(locationError = null) }
    fun focusCurrentLocation() = viewModelScope.launch {
        _state.update { it.copy(locatingCurrent = true, locationError = null) }
        when (val location = locationSource.singleShot()) {
            is LocResult.Success -> {
                val time = if (location.timeMillis > 0) location.timeMillis else System.currentTimeMillis()
                _state.update { it.copy(
                    current = PointLite("current", time.toBeijingDateTime(), location.lon, location.lat, false),
                    locatingCurrent = false,
                    currentFocusRequest = it.currentFocusRequest + 1,
                ) }
            }
            is LocResult.Failure -> _state.update {
                it.copy(locatingCurrent = false, locationError = location.reason)
            }
        }
    }
}
