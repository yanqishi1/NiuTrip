package com.niutrip.app.core

val DAY_COLORS = listOf(0xFFF59E0BL, 0xFF3B82F6L, 0xFF00B96BL, 0xFF8B5CF6L, 0xFFEC4899L, 0xFF14B8A6L)
fun dayColor(index: Int): Long = DAY_COLORS[Math.floorMod(index, DAY_COLORS.size)]
