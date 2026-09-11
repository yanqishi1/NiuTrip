package com.niutrip.app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.niutrip.app.ui.deeplink.DeepLinkHandler
import com.niutrip.app.ui.nav.AppNav
import com.niutrip.app.ui.theme.NiuTripTheme

class MainActivity : ComponentActivity() {
    private val pendingToken = mutableStateOf<String?>(null)
    private val clipboardToken = mutableStateOf<String?>(null)
    private var lastClipboardText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingToken.value = DeepLinkHandler.parse(intent?.data)
        val debugTrackId = if (BuildConfig.DEBUG) intent.getStringExtra("debug_track_id") else null
        setContent {
            NiuTripTheme {
                AppNav(
                    container = (application as NiuTripApp).container,
                    pendingToken = pendingToken.value,
                    clipboardToken = clipboardToken.value,
                    debugTrackId = debugTrackId,
                    consumeToken = { pendingToken.value = null },
                    consumeClipboardToken = { clipboardToken.value = null },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inspectClipboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) inspectClipboard()
    }

    private fun inspectClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = runCatching {
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()
        if (text == null || text == lastClipboardText) return
        val parsed = DeepLinkHandler.parseSharedText(text)
        when {
            parsed == null -> lastClipboardText = text
            pendingToken.value == null -> {
                lastClipboardText = text
                clipboardToken.value = parsed
            }
            pendingToken.value == parsed -> lastClipboardText = text
            // 先处理本次深链；剪切板里的另一条链接留待弹窗关闭后再次检测。
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        clipboardToken.value = null
        pendingToken.value = DeepLinkHandler.parse(intent.data)
    }
}
