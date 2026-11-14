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

/** Finite cloud transfer protection. Its dataSync budget is shared with every app dataSync service. */
class LanSyncGroupCloudTransferService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val owner get() = ChatBarApp.instance.lanSyncOwner
    private var generation = -1L
    private var expectedStop = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra(EXTRA_GENERATION, -1L) ?: -1L
        val stale = generation >= 0 && requested < generation
        val promotedGeneration = if (stale) generation else requested
        generation = promotedGeneration
        expectedStop = false
        try {
            // An accepted FGS start, including a stale stop action, must promote before stopping.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(promotedGeneration),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (_: Exception) {
            owner.cloudServicePromotionFailed(promotedGeneration)
            expectedStop = true
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        owner.cloudServicePromoted(promotedGeneration) {
            handler.post {
                if (generation == promotedGeneration && stopSelfResult(startId)) {
                    expectedStop = true
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
        if (!stale && intent?.action == ACTION_STOP) owner.stopCloudFromNotification(requested)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // The system gives only a few seconds. Never wait for HTTP, journals or coroutine drain here.
        stopSelf()
        expectedStop = true
        owner.cloudServiceTimedOut(generation)
    }

    override fun onDestroy() {
        owner.cloudServiceDestroyed(generation, expectedStop)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(generation: Long): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "设备组网盘传输", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, LanSyncActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, LanSyncGroupCloudTransferService::class.java)
            .setAction(ACTION_STOP).setData(android.net.Uri.parse("chatbar-group-cloud://stop/$generation"))
            .putExtra(EXTRA_GENERATION, generation), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("设备组网盘传输")
            .setContentText("正在处理待传资料；完成情况请查看网盘连接。")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "停止设备组同步", stop).build()
    }

    companion object {
        internal const val CHANNEL_ID = "lan-sync-group-cloud-transfer"
        private const val NOTIFICATION_ID = 1040
        internal const val ACTION_START = "com.example.chatbar.lanSync.GROUP_CLOUD_START"
        internal const val ACTION_STOP = "com.example.chatbar.lanSync.GROUP_CLOUD_STOP"
        internal const val EXTRA_GENERATION = "lan-sync-cloud-generation"
    }
}
