package com.niutrip.app.ui

import android.app.Application
import android.net.Uri
import com.niutrip.app.ui.deeplink.DeepLinkHandler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeepLinkHandlerTest {
    @Test fun `parses only niutrip track links`() {
        assertEquals("abc123", DeepLinkHandler.parse(Uri.parse("https://niutrip.cn/t/abc123/")))
        assertNull(DeepLinkHandler.parse(Uri.parse("https://niutrip.cn/other/abc123")))
        assertNull(DeepLinkHandler.parse(Uri.parse("https://example.com/t/abc123")))
    }

    @Test fun `parses shared links from clipboard text on any web host`() {
        assertEquals("abc_123-Z", DeepLinkHandler.parseSharedText("https://niutrip.cn/t/abc_123-Z"))
        assertEquals("devToken", DeepLinkHandler.parseSharedText("好友分享： http://192.168.1.8:8000/t/devToken 。"))
        assertNull(DeepLinkHandler.parseSharedText("https://example.com/other/abc123"))
        assertNull(DeepLinkHandler.parseSharedText("javascript://niutrip.cn/t/abc123"))
    }
}
