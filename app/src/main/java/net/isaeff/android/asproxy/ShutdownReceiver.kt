package net.isaeff.android.asproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Ensures the SOCKS tunnel is gracefully closed when the device
 * is shutting down or rebooting, so the remote port is freed.
 */
class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_SHUTDOWN ||
            intent?.action == "android.intent.action.ACTION_SHUTDOWN") {

            AAPLog.append("ShutdownReceiver: device is shutting down, stopping service")
            val stopIntent = Intent(context, SocksForegroundService::class.java)
            context.stopService(stopIntent)
        }
    }
}