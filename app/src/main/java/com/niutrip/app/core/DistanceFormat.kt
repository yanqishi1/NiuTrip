package com.niutrip.app.core

import java.util.Locale
import kotlin.math.roundToInt

fun formatDistance(meters: Double): String = when {
    !meters.isFinite() || meters <= 0.0 -> "0 米"
    meters < 1_000.0 -> "${meters.roundToInt()} 米"
    else -> String.format(Locale.CHINA, "%.2f 公里", meters / 1_000.0)
}
