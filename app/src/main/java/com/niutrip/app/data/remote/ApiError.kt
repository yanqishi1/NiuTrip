package com.niutrip.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException

class ApiException(val code: Int, message: String) : Exception(message)

suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (error: HttpException) {
    val body = error.response()?.errorBody()?.string().orEmpty()
    val detail = runCatching { Json.parseToJsonElement(body).jsonObject["detail"]?.toString()?.trim('"') }.getOrNull()
    throw ApiException(error.code(), detail ?: "请求失败（${error.code()}）")
}
