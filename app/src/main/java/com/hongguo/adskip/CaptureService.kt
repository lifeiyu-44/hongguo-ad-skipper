package com.hongguo.adskip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 屏幕捕捉的配套前台服务。
 * Android 10 起系统强制要求：使用 MediaProjection（屏幕捕捉）必须有一个
 * foregroundServiceType=mediaProjection 的前台服务，否则授权会被系统直接失效。
 * 本服务只维持这个"资格"，不显示任何界面；状态栏会有一条静默的常驻通知
 * （系统强制要求的可视化提示，无声音、不打扰）。
 */
class CaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "capture_ready"
        private const val NOTIF_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val ch = NotificationChannel(
                CHANNEL_ID,
                "反馈截图",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                description = "反馈截图功能就绪提示（系统要求）"
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("反馈截图已就绪")
            .setContentText("点击反馈按钮时将自动附带当前画面")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
}
