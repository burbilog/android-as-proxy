package net.isaeff.android.asproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val serviceIntent = Intent(context, SocksForegroundService::class.java).apply {
            action = "net.isaeff.android.asproxy.action.AUTO_CONNECT_ON_BOOT"
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