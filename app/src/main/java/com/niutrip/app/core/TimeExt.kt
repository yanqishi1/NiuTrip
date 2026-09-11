package com.niutrip.app.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")
private val apiTime = DateTimeFormatter.ISO_LOCAL_DATE_TIME

fun Long.toBeijingDateTime(): LocalDateTime = Instant.ofEpochMilli(this).atZone(BEIJING).toLocalDateTime()
fun LocalDateTime.toEpochMillis(): Long = atZone(BEIJING).toInstant().toEpochMilli()
fun LocalDateTime.toApiTime(): String = format(apiTime)
fun String.toLocalDateTimeOrNull(): LocalDateTime? = runCatching {
    LocalDateTime.parse(removeSuffix("Z").substringBefore('+'), apiTime)
}.getOrNull()
