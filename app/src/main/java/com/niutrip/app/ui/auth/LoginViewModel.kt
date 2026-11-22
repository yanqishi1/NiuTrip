package com.niutrip.app.ui.auth

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.data.repo.AuthRepository
import com.niutrip.app.data.remote.ApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { LOGIN, REGISTER }
sealed interface AuthSubmitState { data object Idle : AuthSubmitState; data object Loading : AuthSubmitState; data object Success : AuthSubmitState; data class Error(val message: String) : AuthSubmitState }
data class LoginState(
    val mode: AuthMode = AuthMode.LOGIN, val identifier: String = "", val username: String = "",
    val password: String = "", val confirmPassword: String = "", val emailCode: String = "",
    val sendingCode: Boolean = false, val codeMessage: String? = null, val codeError: String? = null,
    val retryAt: Map<String, Long> = emptyMap(), val clock: Long = 0,
    val submit: AuthSubmitState = AuthSubmitState.Idle,
) {
    val emailRegistration: Boolean get() = mode == AuthMode.REGISTER && '@' in identifier
    val resendSeconds: Int get() = (((retryAt[identifier.trim().lowercase()] ?: 0) - clock + 999) / 1000).coerceAtLeast(0).toInt()
}

class LoginViewModel(private val repository: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()
    private var countdown: Job? = null
    fun setMode(mode: AuthMode) = _state.update { if (it.submit is AuthSubmitState.Loading) it else it.copy(mode = mode, submit = AuthSubmitState.Idle) }
    fun setIdentifier(value: String) = _state.update {
        val changed = value.trim().lowercase() != it.identifier.trim().lowercase()
        it.copy(identifier = value, emailCode = if (changed) "" else it.emailCode, codeMessage = null, codeError = null)
    }
    fun setUsername(value: String) = _state.update { it.copy(username = value) }
    fun setPassword(value: String) = _state.update { it.copy(password = value) }
    fun setConfirmPassword(value: String) = _state.update { it.copy(confirmPassword = value) }
    fun setEmailCode(value: String) = _state.update { it.copy(emailCode = value.filter { c -> c in '0'..'9' }.take(6)) }

    fun sendEmailCode() {
        val current = _state.value
        if (!current.emailRegistration || current.sendingCode || current.submit is AuthSubmitState.Loading) return
        val email = current.identifier.trim().lowercase()
        val hint = AuthValidation.identifierError(email)
        if (hint != null) {
            _state.update { it.copy(codeError = hint) }; return
        }
        if ((current.retryAt[email] ?: 0) > SystemClock.elapsedRealtime()) return
        _state.update { it.copy(sendingCode = true, codeError = null, codeMessage = null) }
        viewModelScope.launch {
            val result = repository.sendEmailCode(email)
            val retry = result.getOrNull()?.retry_after ?: (result.exceptionOrNull() as? ApiException)?.retryAfter
            if (retry != null && retry > 0) {
                val now = SystemClock.elapsedRealtime()
                _state.update { it.copy(retryAt = it.retryAt + (email to now + retry.toLong() * 1000), clock = now) }
                countdown?.cancel()
                countdown = viewModelScope.launch {
                    while (_state.value.retryAt.values.any { it > SystemClock.elapsedRealtime() }) {
                        delay(250)
                        _state.update { it.copy(clock = SystemClock.elapsedRealtime()) }
                    }
                }
            }
            _state.update {
                val sameEmail = it.identifier.trim().lowercase() == email
                it.copy(sendingCode = false,
                    codeMessage = if (sameEmail) result.getOrNull()?.detail else null,
                    codeError = if (sameEmail) result.exceptionOrNull()?.message else null)
            }
        }
    }

    fun submit() {
        val current = _state.value
        if (current.submit is AuthSubmitState.Loading || current.sendingCode) return
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
            if (current.emailRegistration && !Regex("^[0-9]{6}$").matches(current.emailCode)) {
                _state.update { it.copy(submit = AuthSubmitState.Error("请输入 6 位邮箱验证码")) }; return
            }
        }
        _state.update { it.copy(submit = AuthSubmitState.Loading) }
        viewModelScope.launch {
            val result = if (current.mode == AuthMode.LOGIN) repository.login(current.identifier, current.password)
                else repository.register(current.username, current.identifier, current.password, current.confirmPassword, current.emailCode)
            _state.update { it.copy(submit = result.fold({ AuthSubmitState.Success }, { AuthSubmitState.Error(it.message ?: "请求失败") })) }
        }
    }
}
