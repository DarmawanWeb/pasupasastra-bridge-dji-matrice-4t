package com.darmawan.dronebridge

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** Foreground service: bridge tetap hidup walau UI Flutter ditutup / app di background. */
class BridgeService : Service() {
    private var telemetry: TelemetryBridge? = null
    private var video: VideoBridge? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "bridge"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(ch, "Bridge", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, ch)
            .setContentTitle("M4T Bridge aktif")
            .setContentText("Telemetri, video, dan perintah GCS")
            .setSmallIcon(android.R.drawable.stat_notify_sync).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)

        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "m4t:bridge").apply { acquire() }
        }

        val wsUrl = intent?.getStringExtra("ws") ?: return START_STICKY
        val rtmp = intent.getStringExtra("rtmp") ?: return START_STICKY

        // Semua error dibungkus: crash di bridge tidak boleh mengganggu apa pun.
        val control = intent.getBooleanExtra("control", false)
        runCatching { telemetry?.stop(); telemetry = TelemetryBridge(applicationContext, wsUrl, control).also { it.start() } }
            .onFailure { BridgeEvents.log("Telemetry error: ${it.message}") }
        runCatching { video?.stop(); video = VideoBridge(rtmp).also { it.start() } }
            .onFailure { BridgeEvents.log("Video error: ${it.message}") }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { telemetry?.stop() }
        runCatching { video?.stop() }
        wake?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
