package com.example.chatbar.lanSync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.chatbar.ChatBarApp

/** Foreground protection belongs to device sync, independently of AI generation leases. */
class LanSyncGroupForegroundService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val owner get() = ChatBarApp.instance.lanSyncOwner
    private var generation = -1L
    private var expectedStop = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra(EXTRA_GENERATION, -1L) ?: -1L
        val stale = generation >= 0 && requested < generation
        val notificationGeneration = if (stale) generation else requested
        try {
            // Even a stop or obsolete start must satisfy a successful startForegroundService call.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(notificationGeneration),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (_: Exception) {
            owner.servicePromotionFailed(if (generation >= 0) generation else requested)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        generation = notificationGeneration
        expectedStop = false
        owner.servicePromoted(notificationGeneration) {
            mainHandler.post {
                if (generation == notificationGeneration) {
                    // Do not stop a newer start already delivered to the system but not this process.
                    if (stopSelfResult(startId)) {
                        expectedStop = true
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
            }
        }
        if (!stale && intent?.action == ACTION_STOP) owner.stopFromNotification(requested)
        return START_NOT_STICKY
    }

    private fun notification(generation: Long): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "设备组后台同步", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, LanSyncActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // The immutable token belongs to this service generation; an older stop cannot stop a new run.
        val stop = PendingIntent.getService(this, 0, Intent(this, LanSyncGroupForegroundService::class.java)
            .setAction(ACTION_STOP).setData(android.net.Uri.parse("chatbar-group-sync://stop/$generation"))
            .putExtra(EXTRA_GENERATION, generation), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("设备组后台运行")
            .setContentText("允许同网设备连接；需要核对的资料仍会保留待确认。")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "停止设备组同步", stop)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        owner.serviceDestroyed(generation, expectedStop)
        super.onDestroy()
    }

    companion object {
        internal const val CHANNEL_ID = "lan-sync-group-background"
        private const val NOTIFICATION_ID = 1039
        internal const val ACTION_START = "com.example.chatbar.lanSync.GROUP_BACKGROUND_START"
        internal const val ACTION_STOP = "com.example.chatbar.lanSync.GROUP_BACKGROUND_STOP"
        internal const val EXTRA_GENERATION = "lan-sync-service-generation"
    }
}
