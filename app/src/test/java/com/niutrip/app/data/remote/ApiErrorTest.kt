package com.niutrip.app.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiErrorTest {
    @Test fun `retry interval survives HTTP error parsing`() = runTest {
        val error = try {
            apiCall<Unit> {
                throw HttpException(Response.error<Unit>(429,
                    """{"detail":"发送过于频繁","retry_after":35}""".toResponseBody()))
            }
            error("Expected ApiException")
        } catch (error: ApiException) { error }
        assertEquals(429, error.code)
        assertEquals("发送过于频繁", error.message)
        assertEquals(35, error.retryAfter)
    }

    @Test fun `registration field errors reach the user`() = runTest {
        val error = try {
            apiCall<Unit> {
                throw HttpException(Response.error<Unit>(400,
                    """{"email_code":["请输入邮箱验证码"]}""".toResponseBody()))
            }
            error("Expected ApiException")
        } catch (error: ApiException) { error }
        assertEquals("请输入邮箱验证码", error.message)
    }
}
