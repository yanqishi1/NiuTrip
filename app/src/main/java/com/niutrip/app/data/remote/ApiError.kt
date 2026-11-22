package com.niutrip.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import retrofit2.HttpException

class ApiException(val code: Int, message: String, val retryAfter: Int? = null) : Exception(message)

suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (error: HttpException) {
    val body = error.response()?.errorBody()?.string().orEmpty()
    val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    val detail = runCatching { obj?.get("detail")?.jsonPrimitive?.contentOrNull }.getOrNull()
        ?: obj?.values?.filterIsInstance<JsonArray>()?.flatMap { it }?.mapNotNull {
            runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
        }?.joinToString("；")?.takeIf { it.isNotEmpty() }
    val retryAfter = runCatching { obj?.get("retry_after")?.jsonPrimitive?.intOrNull }.getOrNull()
    throw ApiException(error.code(), detail ?: "请求失败（${error.code()}）", retryAfter)
}
