package com.niutrip.app.ui.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.data.TokenStore
import com.niutrip.app.data.remote.*
import com.niutrip.app.ui.checkin.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

data class ProfileState(
    val loading: Boolean = true,
    val user: UserDto? = null,
    val tracks: Int = 0,
    val checkins: Int = 0,
    val shared: Int = 0,
    val message: String? = null,
    val recording: Boolean = false,
)

class ProfileViewModel(private val api: ApiService, private val tokens: TokenStore, private val compressor: ImageCompressor) : ViewModel() {
    private val _state = MutableStateFlow(ProfileState()); val state = _state.asStateFlow()
    init { refresh() }
    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true, message = null) }
        try {
            val user = apiCall { api.profile() }; tokens.saveAvatarUrl(user.avata_url)
            val mine = apiCall { api.tracks("mine") }; val shared = apiCall { api.tracks("shared") }
            _state.value = ProfileState(false, user, mine.size, mine.sumOf { it.checkin_count }, shared.size, recording = mine.any { it.track_status == "RECORDING" })
        } catch (error: Throwable) { _state.update { it.copy(loading = false, message = error.message ?: "加载失败") } }
    }
    fun rename(value: String) = launchAction {
        _state.update { it.copy(user = apiCall { api.updateProfile(ProfileUpdateIn(username = value.trim())) }) }
    }
    fun updateAvatar(uri: Uri) = launchAction {
        val image = withContext(Dispatchers.IO) { compressor.prepareForUpload(uri) }
        try {
            val part = MultipartBody.Part.createFormData("image", image.file.name,
                image.file.asRequestBody(image.mediaType.toMediaType()))
            val url = apiCall { api.upload(part) }.url
            val user = apiCall { api.updateProfile(ProfileUpdateIn(avata_url = url)) }
            tokens.saveAvatarUrl(user.avata_url)
            _state.update { it.copy(user = user) }
        } finally {
            withContext(Dispatchers.IO) { image.file.delete() }
        }
    }
    fun changePassword(old: String, new: String) = launchAction {
        require(new.length >= 8) { "新密码至少 8 位" }; apiCall { api.changePassword(PasswordIn(old, new)) }; _state.update { it.copy(message = "密码已更新") }
    }
    fun updateBinding(password: String, phone: String?, email: String?) = launchAction {
        _state.update { it.copy(user = apiCall { api.updateBindings(BindingsIn(password, phone?.ifBlank { null }, email?.ifBlank { null })) }, message = "绑定信息已更新") }
    }
    fun logout() = tokens.clear()
    fun clearMessage() = _state.update { it.copy(message = null) }
    private fun launchAction(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { error -> _state.update { it.copy(message = error.message ?: "操作失败") } }
    }
}
