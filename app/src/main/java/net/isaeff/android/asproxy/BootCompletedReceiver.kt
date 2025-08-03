package net.isaeff.android.asproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("aap_prefs", Context.MODE_PRIVATE)
        val autoConnect = prefs.getBoolean("auto_connect", false)
        if (!autoConnect) {
            AAPLog.append("BootCompletedReceiver: auto-connect disabled")
            return
        }

        val sshServer = prefs.getString("ssh_server", "") ?: ""
        val remotePortStr = prefs.getString("remote_port", "") ?: ""
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        if (sshServer.isBlank() || remotePortStr.isBlank() || !remotePortStr.all { it.isDigit() } ||
            username.isBlank() || password.isBlank()) {
            AAPLog.append("BootCompletedReceiver: required prefs missing, skipping auto-connect")
            return
        }

        val remotePort = remotePortStr.toInt()
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
            AAPLog.append("BootCompletedReceiver: started service for auto-connect")
        } catch (e: Exception) {
            AAPLog.append("BootCompletedReceiver: failed to start service: ${e.message}")
        }
    }
}