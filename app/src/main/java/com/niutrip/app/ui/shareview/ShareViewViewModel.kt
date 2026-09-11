package com.niutrip.app.ui.shareview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.core.DayGroup
import com.niutrip.app.core.DayGrouper
import com.niutrip.app.data.remote.ApiException
import com.niutrip.app.data.remote.ApiService
import com.niutrip.app.data.remote.ShareDataDto
import com.niutrip.app.data.remote.apiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareViewState(val loading: Boolean = true, val data: ShareDataDto? = null, val days: List<DayGroup> = emptyList(), val selectedDay: Int = -1, val gone: Boolean = false, val error: String? = null)

class ShareViewViewModel(private val token: String, private val api: ApiService) : ViewModel() {
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
}
