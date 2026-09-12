package com.niutrip.app.ui.deeplink

import android.net.Uri

object DeepLinkHandler {
    fun parse(data: Uri?): String? {
        if (data?.scheme != "https" || data.host != "niutrip.cn") return null
        return parseTrackUri(data)
    }

    /** Clipboard links may use a development or self-hosted domain.
     *  分享文案里的使用说明（buildShareText）被自然忽略：URL 提取按空白截断，
     *  他端抹掉换行导致说明文字粘进路径时，只取路径段开头的合法 token 序列。 */
    fun parseSharedText(text: CharSequence?): String? = WEB_URL.findAll(text?.toString().orEmpty())
        .mapNotNull { match ->
            val candidate = match.value.trimEnd('.', ',', ';', ')', ']', '}', '。', '，')
            parseTrackUri(Uri.parse(candidate))
        }
        .firstOrNull()

    private fun parseTrackUri(uri: Uri): String? {
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        val parts = uri.pathSegments
        return parts.takeIf { it.size == 2 && it[0] == "t" }
            ?.get(1)
            ?.let { segment -> if (segment.matches(TOKEN)) segment else TOKEN.find(segment)?.value }
    }

    private val WEB_URL = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
    private val TOKEN = Regex("[A-Za-z0-9_-]{1,64}")
}
