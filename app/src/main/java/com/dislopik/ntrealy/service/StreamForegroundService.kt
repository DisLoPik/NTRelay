package com.dislopik.ntrealy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dislopik.ntrealy.MainActivity
import com.dislopik.ntrealy.R
import com.dislopik.ntrealy.network.localAreaNetwork
import com.dislopik.ntrealy.protocol.NTRCommands
import com.dislopik.ntrealy.protocol.ScreenId
import com.dislopik.ntrealy.render.ColorMode
import com.dislopik.ntrealy.ui.StreamConfig

class StreamForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = intent?.toStreamConfig()
        // Must run before any early exit: stopping without startForeground() crashes the app
        val notification = buildNotification(config?.host.orEmpty())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (config == null || config.host.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        val network = getSystemService(ConnectivityManager::class.java).localAreaNetwork()
        StreamSession.start(config) { socket ->
            network?.let { runCatching { it.bindSocket(socket) } }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits dataSync services to 6 h a day and crashes apps that overrun. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        StreamSession.abort("Android stopped the stream: background streaming is limited to 6 hours a day")
        stopSelf()
    }

    override fun onDestroy() {
        StreamSession.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(host: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Streaming from $host")
            .setContentText("NTR remote play is active")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stream",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Active NTR remote-play stream"
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NTRelay:stream").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun Intent.toStreamConfig(): StreamConfig = StreamConfig(
        host = getStringExtra(EXTRA_HOST) ?: "",
        viewerPort = getIntExtra(EXTRA_VIEWER_PORT, NTRCommands.UDP_PORT),
        quality = getIntExtra(EXTRA_QUALITY, 70),
        priority = getIntExtra(EXTRA_PRIORITY, 4),
        qos = getIntExtra(EXTRA_QOS, 16),
        priorityScreen = if (getBooleanExtra(EXTRA_PRIORITY_TOP, true)) ScreenId.TOP else ScreenId.BOTTOM,
        colorMode = runCatching { ColorMode.valueOf(getStringExtra(EXTRA_COLOR_MODE) ?: "") }
            .getOrDefault(ColorMode.REGULAR),
    )

    companion object {
        const val CHANNEL_ID = "ntrelay_stream"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        private const val EXTRA_HOST = "host"
        private const val EXTRA_VIEWER_PORT = "viewerPort"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_PRIORITY = "priority"
        private const val EXTRA_QOS = "qos"
        private const val EXTRA_PRIORITY_TOP = "priorityTop"
        private const val EXTRA_COLOR_MODE = "colorMode"

        fun start(context: Context, config: StreamConfig) {
            val intent = Intent(context, StreamForegroundService::class.java).apply {
                putExtra(EXTRA_HOST, config.host)
                putExtra(EXTRA_VIEWER_PORT, config.viewerPort)
                putExtra(EXTRA_QUALITY, config.quality)
                putExtra(EXTRA_PRIORITY, config.priority)
                putExtra(EXTRA_QOS, config.qos)
                putExtra(EXTRA_PRIORITY_TOP, config.priorityScreen == ScreenId.TOP)
                putExtra(EXTRA_COLOR_MODE, config.colorMode.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamForegroundService::class.java))
        }
    }
}
