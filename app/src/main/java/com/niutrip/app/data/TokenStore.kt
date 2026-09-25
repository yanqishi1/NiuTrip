package com.niutrip.app.data

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("niutrip_auth", Context.MODE_PRIVATE)
    private val _avatarUrl = MutableStateFlow(prefs.getString(AVATAR_KEY, null))
    val avatarUrl = _avatarUrl.asStateFlow()

    var token: String?
        get() = prefs.getString(KEY, null)
        private set(value) { prefs.edit().putString(KEY, value).apply() }
    val hasAvatarSnapshot: Boolean get() = prefs.contains(AVATAR_LOADED_KEY)
    val userId: String? get() = prefs.getString(USER_ID_KEY, null)
    val cacheOwnerKey: String?
        get() = userId ?: token?.let { value ->
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

    fun save(value: String, userId: String? = null) {
        token = value
        if (userId != null) saveUserId(userId)
    }
    fun saveUserId(value: String) { prefs.edit().putString(USER_ID_KEY, value).apply() }
    fun saveAvatarUrl(value: String?) {
        val editor = prefs.edit().putBoolean(AVATAR_LOADED_KEY, true)
        if (value.isNullOrBlank()) editor.remove(AVATAR_KEY) else editor.putString(AVATAR_KEY, value)
        editor.apply()
        _avatarUrl.value = value?.takeUnless(String::isBlank)
    }
    fun clear() {
        prefs.edit().remove(KEY).remove(USER_ID_KEY).remove(AVATAR_KEY)
            .remove(AVATAR_LOADED_KEY).apply()
        _avatarUrl.value = null
    }

    private companion object {
        const val KEY = "auth_token"
        const val USER_ID_KEY = "user_id"
        const val AVATAR_KEY = "avatar_url"
        const val AVATAR_LOADED_KEY = "avatar_loaded"
    }
}
