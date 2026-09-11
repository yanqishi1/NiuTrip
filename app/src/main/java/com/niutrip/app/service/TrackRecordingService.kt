package com.niutrip.app.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.niutrip.app.MainActivity
import com.niutrip.app.NiuTripApp
import com.niutrip.app.R
import kotlinx.coroutines.*

class TrackRecordingService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var recordingJob: Job? = null

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:track-recording")
            .apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_RESTART && recordingJob?.isActive == true) return START_STICKY
        val saved = active(this)
        val trackId = intent?.getStringExtra(EXTRA_TRACK_ID) ?: saved?.first
        if (trackId == null) { stopSelf(); return START_NOT_STICKY }
        val name = intent?.getStringExtra(EXTRA_TRACK_NAME) ?: saved?.second.orEmpty()
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: saved?.third ?: "AUTO"
        saveActive(trackId, name, mode)
        startForeground(NOTIFICATION_ID, notification(name))
        recordingJob?.cancel()
        val container = (application as NiuTripApp).container
        recordingJob = scope.launch {
            val recorder = Recorder(container.locationSource, container.db.pendingPointDao(),
                afterCollect = { container.syncRepository.flushOnce() })
            if (mode == "MANUAL") {
                // 手动模式：开始时采一个首点即结束（collectOnce 内部会触发一次补传）
                recorder.collectOnce(trackId); stopSelf()
            } else recorder.runLoop(trackId)
        }
        return if (mode == "MANUAL") START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        wakeLock?.takeIf(PowerManager.WakeLock::isHeld)?.release()
        wakeLock = null
        // 停止记录时收尾补传；service scope 已取消，挂到 applicationScope
        (application as NiuTripApp).container.let {
            it.applicationScope.launch { it.syncRepository.flushOnce() }
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        active(this)?.takeIf { it.third == "AUTO" }?.let { scheduleRestart() }
        super.onTaskRemoved(rootIntent)
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(name: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("旅行牛牛正在记录")
        .setContentText(if (name.isBlank()) "正在记录旅程" else "正在记录「$name」")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "轨迹记录", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun scheduleRestart() {
        val intent = Intent(this, TrackRecordingService::class.java).setAction(ACTION_RESTART)
        val pending = PendingIntent.getForegroundService(this, RESTART_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pending)
    }

    private fun saveActive(id: String?, name: String? = null, mode: String? = null) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(ACTIVE_ID, id)
            .apply {
                if (name != null) putString(ACTIVE_NAME, name)
                // mode 必须持久化：START_STICKY 重启时没有 extra，缺失会退回 AUTO，
                // MANUAL 轨迹会被当成 AUTO 跑循环狂采
                if (mode != null) putString(ACTIVE_MODE, mode)
            }.apply()
    }

    companion object {
        private const val CHANNEL_ID = "track_recording"
        private const val NOTIFICATION_ID = 4102
        private const val RESTART_REQUEST_CODE = 4103
        private const val RESTART_DELAY_MS = 5_000L
        private const val PREFS = "recording_service"
        private const val ACTIVE_ID = "active_track_id"
        private const val ACTIVE_NAME = "active_track_name"
        private const val ACTIVE_MODE = "active_track_mode"
        private const val EXTRA_TRACK_ID = "track_id"
        private const val EXTRA_TRACK_NAME = "track_name"
        private const val EXTRA_MODE = "mode"
        private const val ACTION_STOP = "com.niutrip.app.STOP_RECORDING"
        private const val ACTION_RESTART = "com.niutrip.app.RESTART_RECORDING"

        fun start(context: Context, trackId: String, trackName: String, mode: String = "AUTO") {
            val intent = Intent(context, TrackRecordingService::class.java)
                .putExtra(EXTRA_TRACK_ID, trackId).putExtra(EXTRA_TRACK_NAME, trackName)
                .putExtra(EXTRA_MODE, mode)
            ContextCompat.startForegroundService(context, intent)
        }

        fun resumeIfActive(context: Context) {
            active(context)?.takeIf { it.third == "AUTO" }?.let { (id, name, mode) ->
                start(context, id, name, mode)
            }
        }
        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
            context.stopService(Intent(context, TrackRecordingService::class.java))
        }
        fun active(context: Context): Triple<String, String, String>? {
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            return prefs.getString(ACTIVE_ID, null)?.let {
                Triple(it, prefs.getString(ACTIVE_NAME, "").orEmpty(), prefs.getString(ACTIVE_MODE, null) ?: "AUTO")
            }
        }
    }
}
