package com.niutrip.app.data.repo

import com.niutrip.app.data.TokenStore
import com.niutrip.app.data.remote.*

class AuthRepository(private val api: ApiService, private val tokens: TokenStore) {
    suspend fun login(identifier: String, password: String): Result<LoginOut> = runCatching {
        apiCall { api.login(LoginIn(identifier.trim(), password)) }.also {
            tokens.save(it.token, it.user.user_id)
            tokens.saveAvatarUrl(it.user.avata_url)
        }
    }

    suspend fun sendEmailCode(email: String): Result<VerificationCodeOut> = runCatching {
        apiCall { api.sendEmailCode(EmailCodeIn(email.trim().lowercase())) }
    }

    suspend fun sendSmsCode(phone: String, purpose: String): Result<VerificationCodeOut> = runCatching {
        apiCall { api.sendSmsCode(SmsCodeIn(phone.trim(), purpose)) }
    }

    suspend fun smsLogin(phone: String, code: String): Result<LoginOut> = runCatching {
        apiCall { api.smsLogin(SmsLoginIn(phone.trim(), code.trim())) }.also {
            tokens.save(it.token, it.user.user_id)
            tokens.saveAvatarUrl(it.user.avata_url)
        }
    }

    suspend fun register(username: String, identifier: String, password: String, confirmPassword: String, emailCode: String, smsCode: String = ""): Result<LoginOut> = runCatching {
        val value = identifier.trim()
        val input = if ('@' in value) RegisterIn(username.trim(), password, confirmPassword, email = value.lowercase(), email_code = emailCode.trim())
            else RegisterIn(username.trim(), password, confirmPassword, phone = value, sms_code = smsCode.trim())
        apiCall { api.register(input) }.also {
            tokens.save(it.token, it.user.user_id)
            tokens.saveAvatarUrl(it.user.avata_url)
        }
    }

    fun logout() = tokens.clear()
}
