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
import org.bbottema.javasocksproxyserver.SocksServer;
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.util.Properties


class SocksForegroundService : Service() {

    private val CHANNEL_ID = "SocksProxyChannel"
    private val NOTIFICATION_ID = 1
    private val ACTION_STOP_SERVICE = "net.isaeff.android.asproxy.action.STOP_SERVICE"

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

        // Handle stop action from notification swipe or explicit stop
        if (intent?.action == ACTION_STOP_SERVICE) {
            AAPLog.append("Stop action received from notification")
            stopSelf()
            return START_NOT_STICKY
        }
        
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

        // Stub: start proxy here
        try {
            startJsocks()
            startSSHtunnel()
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
            stopSSHtunnel()
            sshTunnelManager.destroy()
            AAPLog.append("Socks proxy stopped")
            // Show toast on service stop
            android.os.Handler(mainLooper).post {
                android.widget.Toast.makeText(
                    this,
                    "SOCKS proxy is stopped",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
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

        // Intent to stop the service when notification is swiped away or dismissed
        val stopIntent = Intent(this, SocksForegroundService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android As Proxy")
            .setContentText("SOCKS proxy is running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(stopPendingIntent) // Called when notification is dismissed/swiped
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private var socksServer: SocksServer? = null

    // TODO: listen on 127.0.0.1 for security, currently it listens on 0.0.0.0 by default
    private fun startJsocks() {
        try {
            socksServer = SocksServer(1080).apply {
                start()
                AAPLog.append("SOCKS proxy started on port 1080")
            }
        } catch (e: Exception) {
            socksServer = null
            AAPLog.append("Failed to start SOCKS proxy: ${e.message}")
            throw e
        }
    }

    private fun stopJsocks() {
        try {
            socksServer?.let { server ->
                server.stop()
                socksServer = null
                AAPLog.append("SOCKS proxy stopped")
            } ?: run {
                AAPLog.append("No running SOCKS proxy to stop")
            }
        } catch (e: Exception) {
            AAPLog.append("Failed to stop SOCKS proxy: ${e.message}")
            throw e
        }
    }

    private val sshTunnelManager = SSHTunnelManager()

    private fun startSSHtunnel() {
        sshTunnelManager.onError = { errorMessage ->
            AAPLog.append(errorMessage)
            stopSelf()
            // Show error toast
            android.os.Handler(mainLooper).post {
                android.widget.Toast.makeText(
                    this,
                    "SSH connection failed, see log",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        sshTunnelManager.startSSHTunnel()
        AAPLog.append("SFS: SSH tunnel started")
    }

    private fun stopSSHtunnel() {
        sshTunnelManager.stopSSHTunnel()
        AAPLog.append("SFS: SSH tunnel stopped")
    }
}

