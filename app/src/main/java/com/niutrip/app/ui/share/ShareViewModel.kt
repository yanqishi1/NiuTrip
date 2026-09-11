package com.niutrip.app.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.BuildConfig
import com.niutrip.app.data.remote.ApiService
import com.niutrip.app.data.remote.ShareIn
import com.niutrip.app.data.remote.apiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ShareResult { data object Idle : ShareResult; data object Loading : ShareResult; data class Done(val mode: String, val url: String?) : ShareResult; data class Error(val message: String) : ShareResult }
data class ShareState(val selectedMode: String = "PRIVATE", val result: ShareResult = ShareResult.Idle)

class ShareViewModel(private val trackId: String, private val api: ApiService) : ViewModel() {
    private val _state = MutableStateFlow(ShareState()); val state = _state.asStateFlow()
    fun pick(mode: String) = _state.update { it.copy(selectedMode = mode, result = ShareResult.Idle) }
    fun generate() = viewModelScope.launch {
        val mode = _state.value.selectedMode
        _state.update { it.copy(result = ShareResult.Loading) }
        try {
            val response = apiCall { api.share(trackId, ShareIn(mode)) }
            _state.update { it.copy(result = ShareResult.Done(response.share_mode, response.share_url?.let { path -> BuildConfig.SHARE_BASE_URL + path })) }
        } catch (error: Throwable) { _state.update { it.copy(result = ShareResult.Error(error.message ?: "生成失败")) } }
    }
}
