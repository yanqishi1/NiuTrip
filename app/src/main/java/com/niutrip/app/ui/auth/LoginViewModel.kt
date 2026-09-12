package com.niutrip.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.data.repo.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { LOGIN, REGISTER }
sealed interface AuthSubmitState { data object Idle : AuthSubmitState; data object Loading : AuthSubmitState; data object Success : AuthSubmitState; data class Error(val message: String) : AuthSubmitState }
data class LoginState(val mode: AuthMode = AuthMode.LOGIN, val identifier: String = "", val username: String = "", val password: String = "", val confirmPassword: String = "", val submit: AuthSubmitState = AuthSubmitState.Idle)

class LoginViewModel(private val repository: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()
    fun setMode(mode: AuthMode) = _state.update { it.copy(mode = mode, submit = AuthSubmitState.Idle) }
    fun setIdentifier(value: String) = _state.update { it.copy(identifier = value) }
    fun setUsername(value: String) = _state.update { it.copy(username = value) }
    fun setPassword(value: String) = _state.update { it.copy(password = value) }
    fun setConfirmPassword(value: String) = _state.update { it.copy(confirmPassword = value) }
    fun submit() {
        val current = _state.value
        if (current.identifier.isBlank() || current.password.length < 8 || (current.mode == AuthMode.REGISTER && current.username.isBlank())) {
            _state.update { it.copy(submit = AuthSubmitState.Error("请完整填写账号信息，密码至少 8 位")) }; return
        }
        // 注册时两次密码须一致，防止手误设错密码
        if (current.mode == AuthMode.REGISTER && current.password != current.confirmPassword) {
            _state.update { it.copy(submit = AuthSubmitState.Error("两次输入的密码不一致")) }; return
        }
        // 注册时校验手机号/邮箱格式：不合法直接拦下，不发请求
        if (current.mode == AuthMode.REGISTER) {
            AuthValidation.identifierError(current.identifier)?.let { hint ->
                _state.update { it.copy(submit = AuthSubmitState.Error(hint)) }; return
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(submit = AuthSubmitState.Loading) }
            val result = if (current.mode == AuthMode.LOGIN) repository.login(current.identifier, current.password)
                else repository.register(current.username, current.identifier, current.password)
            _state.update { it.copy(submit = result.fold({ AuthSubmitState.Success }, { AuthSubmitState.Error(it.message ?: "请求失败") })) }
        }
    }
}
