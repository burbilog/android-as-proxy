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
import org.bbottema.javasocksproxyserver.SocksServer

class SocksForegroundService : Service() {

    private val CHANNEL_ID = "SocksProxyChannel"
    private val NOTIFICATION_ID = 1
    private val ACTION_STOP_SERVICE = "net.isaeff.android.asproxy.action.STOP_SERVICE"

    private var socksServer: SocksServer? = null
    private var sshTunnelManager: SSHTunnelManager? = null // Make it nullable

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

        // Extract parameters from intent
        val sshServer = intent?.getStringExtra("ssh_server") ?: run {
            AAPLog.append("Error: SSH server parameter missing")
            ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }
        val remotePort = intent.getIntExtra("remote_port", -1).takeIf { it != -1 } ?: run {
            AAPLog.append("Error: Remote port parameter missing or invalid")
            ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }
        val username = intent.getStringExtra("username") ?: run {
            AAPLog.append("Error: Username parameter missing")
            ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }
        val password = intent.getStringExtra("password") ?: run {
            AAPLog.append("Error: Password parameter missing")
            ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
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
            ConnectionStateHolder.setState(ConnectionState.CONNECTING) // Set state to connecting early
        } catch (e: Exception) {
            AAPLog.append("Failed to start foreground: ${e.message}")
            ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }

        // Initialize and start SSHTunnelManager with parameters
        sshTunnelManager = SSHTunnelManager(
            sshUser = username,
            sshHost = sshServer.split(":")[0], // Assuming format host:port
            sshPort = sshServer.split(":").getOrNull(1)?.toIntOrNull() ?: 22, // Default SSH port 22
            sshPassword = password,
            remotePort = remotePort
        ).apply {
            onError = { errorMessage ->
                AAPLog.append(errorMessage)
                // SSHTunnelManager already sets state to DISCONNECTED on error
                stopSelf() // Stop the service if SSH fails
                // Show error toast
                android.os.Handler(mainLooper).post {
                    android.widget.Toast.makeText(
                        this@SocksForegroundService, // Use service context
                        "SSH connection failed, see log",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            startSSHTunnel()
            AAPLog.append("SFS: SSH tunnel start requested")
        }


        // TODO: start jsocks here, ideally after SSH tunnel is confirmed CONNECTED
        // For now, keeping it here as in original code, but timing needs review
        try {
            startJsocks()
            AAPLog.append("Socks proxy started on local port 1080")
        } catch (e: Exception) {
            AAPLog.append("Error starting socks proxy: ${e.message}")
            // If SOCKS fails, stop everything
            sshTunnelManager?.stopSSHTunnel() // Stop SSH tunnel if it started
            ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)
            stopSelf()
            return START_NOT_STICKY
        }


        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        AAPLog.append("Service onDestroy()")
        // Stop jsocks proxy
        try {
            stopJsocks()
        } catch (e: Exception) {
            AAPLog.append("Error stopping socks proxy: ${e.message}")
        }

        // Stop SSH tunnel and destroy manager
        try {
            sshTunnelManager?.stopSSHTunnel()
            sshTunnelManager?.destroy() // Clean up coroutine scope etc.
            sshTunnelManager = null
        } catch (e: Exception) {
            AAPLog.append("Error stopping SSH tunnel: ${e.message}")
        }

        // Update state to disconnected
        ConnectionStateHolder.setState(ConnectionState.DISCONNECTED)

        // Show toast on service stop
        android.os.Handler(mainLooper).post {
            android.widget.Toast.makeText(
                this,
                "SOCKS proxy is stopped",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        // Broadcast service stopped event - This is no longer strictly needed for UI update
        // but might be useful for other components. Keeping it for now.
        // val intent = Intent("net.isaeff.android.asproxy.SERVICE_STOPPED") // Removed broadcast
        // sendBroadcast(intent) // Removed broadcast

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


    // TODO: listen on 127.0.0.1 for security, currently it listens on 0.0.0.0 by default
    private fun startJsocks() {
        try {
            // The SOCKS server should listen on the local port (1080)
            // and forward requests through the SSH tunnel which is forwarding
            // the remote port (e.g., 10800) back to the local port (1080).
            // This setup is reversed. Typically, the SOCKS server listens locally (e.g., 1080)
            // and connects to the SSH server's *local* forwarding port (e.g., 10800 on localhost)
            // which is then forwarded *remotely* by SSH.
            // The traffic arriving at the SSH server on port 10800 is forwarded to
            // localhost:1080 *on the Android device*. The SOCKS server should listen on 1080
            // *on the Android device*.
            // The current SSHTunnelManager sets up -R remotePort:localhost:localPort (10800:localhost:1080).
            // The SocksServer is created with port 1080.
            socksServer = SocksServer(1080).apply {
                start()
                AAPLog.append("SOCKS proxy started on local port 1080")
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
}
