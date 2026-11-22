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

    suspend fun sendEmailCode(email: String): Result<EmailCodeOut> = runCatching {
        apiCall { api.sendEmailCode(EmailCodeIn(email.trim().lowercase())) }
    }

    suspend fun register(username: String, identifier: String, password: String, confirmPassword: String, emailCode: String): Result<LoginOut> = runCatching {
        val value = identifier.trim()
        val input = if ('@' in value) RegisterIn(username.trim(), password, confirmPassword, email = value.lowercase(), email_code = emailCode.trim())
            else RegisterIn(username.trim(), password, confirmPassword, phone = value)
        apiCall { api.register(input) }.also {
            tokens.save(it.token, it.user.user_id)
            tokens.saveAvatarUrl(it.user.avata_url)
        }
    }

    fun logout() = tokens.clear()
}
