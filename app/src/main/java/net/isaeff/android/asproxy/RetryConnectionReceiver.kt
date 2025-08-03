package net.isaeff.android.asproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class RetryConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "net.isaeff.android.asproxy.action.RETRY_CONNECTION") return

        AAPLog.append("RetryConnectionReceiver: received retry request")

        // Extract parameters from intent
        val sshServer = intent.getStringExtra("ssh_server") ?: ""
        val remotePort = intent.getIntExtra("remote_port", -1)
        val username = intent.getStringExtra("username") ?: ""
        val password = intent.getStringExtra("password") ?: ""

        if (sshServer.isBlank() || remotePort == -1 || username.isBlank() || password.isBlank()) {
            AAPLog.append("RetryConnectionReceiver: required params missing, skipping retry")
            return
        }

        val serviceIntent = Intent(context, SocksForegroundService::class.java).apply {
            putExtra("ssh_server", sshServer)
            putExtra("remote_port", remotePort)
            putExtra("username", username)
            putExtra("password", password)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            AAPLog.append("RetryConnectionReceiver: started service for retry")
        } catch (e: Exception) {
            AAPLog.append("RetryConnectionReceiver: failed to start service: ${e.message}")
        }
    }
}