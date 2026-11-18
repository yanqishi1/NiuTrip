package com.niutrip.app.data

import android.content.Context

/** Legacy on-device preferences, read only for one-time migration to the server. */
class ShareAnnotationStore(context: Context, private val ownerKey: () -> String? = { TokenStore(context).cacheOwnerKey }) {
    private val prefs = context.getSharedPreferences("share_annotations", Context.MODE_PRIVATE)

    fun selected(trackId: String): Set<String> = key(trackId)?.let {
        prefs.getStringSet(it, emptySet())?.toSet()
    }.orEmpty()

    fun legacySelection(trackId: String): Set<String>? = key(trackId)?.takeIf(prefs::contains)?.let {
        prefs.getStringSet(it, emptySet())?.toSet().orEmpty()
    }

    fun clearLegacy(trackId: String) {
        key(trackId)?.let { prefs.edit().remove(it).apply() }
    }

    fun replace(trackId: String, pointIds: Set<String>) {
        key(trackId)?.let { prefs.edit().putStringSet(it, pointIds.toSet()).apply() }
    }

    fun set(trackId: String, pointId: String, selected: Boolean) {
        val old = selected(trackId)
        replace(trackId, if (selected) old + pointId else old - pointId)
    }

    private fun key(trackId: String): String? = ownerKey()?.let { "${it.length}:$it:$trackId" }
}
