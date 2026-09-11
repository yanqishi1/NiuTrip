package com.niutrip.app.data.remote

import com.niutrip.app.data.TokenStore
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val tokens: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder().apply {
            tokens.token?.let { header("Authorization", "Token $it") }
        }.build()
        return chain.proceed(request).also { if (it.code == 401) tokens.clear() }
    }
}
