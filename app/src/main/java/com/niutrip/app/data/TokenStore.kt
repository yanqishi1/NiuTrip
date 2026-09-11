package com.niutrip.app.data

import android.content.Context

class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("niutrip_auth", Context.MODE_PRIVATE)
    var token: String?
        get() = prefs.getString(KEY, null)
        private set(value) { prefs.edit().putString(KEY, value).apply() }
    fun save(value: String) { token = value }
    fun clear() { prefs.edit().remove(KEY).apply() }
    private companion object { const val KEY = "auth_token" }
}
