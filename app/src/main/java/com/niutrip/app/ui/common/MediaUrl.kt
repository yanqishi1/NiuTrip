package com.niutrip.app.ui.common

import com.niutrip.app.BuildConfig

fun absoluteMediaUrl(path: String): String =
    if (path.startsWith("http")) path else BuildConfig.API_BASE_URL.substringBefore("/api/") + path
