package com.niutrip.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackRecordingServiceTest {
    private fun prefs(context: Context) = context.getSharedPreferences("recording_service", Context.MODE_PRIVATE)

    @Test fun `sticky restart restores persisted mode`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs(context).edit()
            .putString("active_track_id", "t1").putString("active_track_name", "薄刀峰")
            .putString("active_track_mode", "MANUAL").apply()
        assertEquals(Triple("t1", "薄刀峰", "MANUAL"), TrackRecordingService.active(context))
    }

    @Test fun `missing mode falls back to AUTO`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs(context).edit().putString("active_track_id", "t2").apply()
        assertEquals(Triple("t2", "", "AUTO"), TrackRecordingService.active(context))
    }
}
