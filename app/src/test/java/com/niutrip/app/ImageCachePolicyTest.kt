package com.niutrip.app

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageCachePolicyTest {
    @Test fun `successful image response is cached for one day`() {
        val response = response(200)
            .newBuilder()
            .header("Cache-Control", "no-store")
            .header("Pragma", "no-cache")
            .header("Expires", "0")
            .build()
            .withOneDayImageCache()

        assertEquals("public, max-age=86400", response.header("Cache-Control"))
        assertNull(response.header("Pragma"))
        assertNull(response.header("Expires"))
    }

    @Test fun `failed image response keeps its cache headers`() {
        val response = response(404)
            .newBuilder()
            .header("Cache-Control", "no-store")
            .build()
            .withOneDayImageCache()

        assertEquals("no-store", response.header("Cache-Control"))
    }

    private fun response(code: Int) = Response.Builder()
        .request(Request.Builder().url("https://example.test/image.jpg").build())
        .protocol(Protocol.HTTP_1_1)
        .message("test")
        .code(code)
        .build()
}
