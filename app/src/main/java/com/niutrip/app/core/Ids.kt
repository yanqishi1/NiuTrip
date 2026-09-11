package com.niutrip.app.core

import java.security.SecureRandom

private const val ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
private val random = SecureRandom()
fun businessId(length: Int = 12): String = buildString(length) {
    repeat(length) { append(ID_CHARS[random.nextInt(ID_CHARS.length)]) }
}
