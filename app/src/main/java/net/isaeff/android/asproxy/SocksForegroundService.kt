package net.isaeff.android.asproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SocksForegroundService : Service() {

    private val CHANNEL_ID = "SocksProxyChannel"
    private val NOTIFICATION_ID = 1

    override fun onBind(intent: Intent?): IBinder? {
        // Not a bound service
        return null
    }

    override fun onCreate() {
        super.onCreate()
        AAPLog.append("Service onCreate()")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AAPLog.append("Service onStartCommand()")
        
        // Create notification first
        val notification = createNotification()
        AAPLog.append("Notification created")
        
        // Start as foreground service with notification
        try {
            startForeground(NOTIFICATION_ID, notification)
            AAPLog.append("Foreground service started with notification")
        } catch (e: Exception) {
            AAPLog.append("Failed to start foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        // Stub: start jsocks proxy here
        try {
            startJsocks()
            AAPLog.append("Socks proxy started")
        } catch (e: Exception) {
            AAPLog.append("Error starting socks proxy: ${e.message}")
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stub: stop jsocks proxy here
        try {
            stopJsocks()
            AAPLog.append("Socks proxy stopped")
        } catch (e: Exception) {
            AAPLog.append("Error stopping socks proxy: ${e.message}")
        }
        AAPLog.append("SocksForegroundService stopped")
    }

    private fun createNotificationChannel() {
        AAPLog.append("Creating notification channel")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Socks Proxy Service",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Shows when SOCKS proxy is running"
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
                AAPLog.append("Notification channel created successfully")
            } catch (e: Exception) {
                AAPLog.append("Failed to create notification channel: ${e.message}")
            }
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android As Proxy")
            .setContentText("SOCKS proxy is running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startJsocks() {
        // TODO: Implement jsocks start logic
        AAPLog.append("Stub: startJsocks called")
    }

    private fun stopJsocks() {
        // TODO: Implement jsocks stop logic
        AAPLog.append("Stub: stopJsocks called")
    }
}

