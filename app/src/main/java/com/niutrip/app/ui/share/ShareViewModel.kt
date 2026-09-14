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

/** 分享文案（百度网盘式）：URL 原样独占一行（接收端 DeepLinkHandler 按空白截断提取、
 *  忽略其余说明文字），后附使用方式说明，引导接收方回到 App 打开。 */
fun buildShareText(url: String): String =
    "【旅行牛牛 旅行轨迹分享】\n$url\n点击这个链接，打开网页可以直接查看轨迹。\n复制这条消息，打开旅行牛牛 App 即可查看并保存这条轨迹"

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
