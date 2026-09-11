package com.niutrip.app.data

import com.niutrip.app.data.remote.ShareDataDto
import com.niutrip.app.data.remote.TrackDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DtoSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun `track dto follows backend contract`() {
        val value = json.decodeFromString<TrackDto>("""{"track_id":"TK123","track_name":"川西","track_img_url":"/static/covers/default.svg","track_start_time":null,"track_end_time":null,"track_record_mode":"AUTO","track_status":"NOT_STARTED","share_mode":"PRIVATE","point_count":0,"checkin_count":0}""")
        assertEquals("AUTO", value.track_record_mode); assertEquals(0, value.point_count)
    }
    @Test fun `share payload reads public coordinate names`() {
        val value = json.decodeFromString<ShareDataDto>("""{"track":{"track_id":"t","track_name":"n","track_img_url":"","start_time":null,"end_time":null,"record_mode":"AUTO","owner_username":"张三","share_mode":"ONCE"},"stats":{"point_count":1,"checkin_count":0,"days":1},"latest":null,"days":[{"date":"2026-08-12","points":[{"point_id":"p1","longitude":100.0,"latitude":30.0,"point_time":"2026-08-12T10:00:00","point_source":"AUTO"}]}]}""")
        assertEquals("张三", value.track.owner_username)
        assertEquals(100.0, value.days.first().points.first().toLite()!!.lon, 0.0)
    }
}
