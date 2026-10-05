package com.btmicfix.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.btmicfix.BTMicFixApp
import com.btmicfix.MainActivity
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.util.Logger

/**
 * Foreground service whose only job is to keep the app process alive while the
 * user has routing switched on.
 *
 * The Bluetooth mic route set with setCommunicationDevice() belongs to our
 * process: if Android (or Samsung's battery manager) kills the process, the
 * route disappears with it. A foreground service prevents that, and START_STICKY
 * brings the process back — and with it the route — if it is killed anyway.
 */
class RoutingKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = AudioRoutingManager.getInstance(applicationContext)
        if (!manager.routingWanted) {
            // Restarted after routing was switched off — nothing to keep alive.
            stopSelf()
            return START_NOT_STICKY
        }
        // Registers the listeners once and re-checks the route.
        manager.onKeepAliveServiceStarted()
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        true
    } catch (e: Exception) {
        // E.g. Bluetooth permission revoked, or a start from the background the
        // system refused. Routing still works while the app is open.
        Logger.e("Could not start keep-alive foreground service", e)
        false
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, BTMicFixApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BTMicFix")
            .setContentText("Bluetooth mic routing is on")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i("Keep-alive service stopped")
    }

    companion object {
        private const val NOTIFICATION_ID = BTMicFixApp.NOTIFICATION_ID + 1

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RoutingKeepAliveService::class.java),
                )
            } catch (e: Exception) {
                Logger.e("Could not start keep-alive service", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, RoutingKeepAliveService::class.java))
            } catch (e: Exception) {
                Logger.e("Could not stop keep-alive service", e)
            }
        }
    }
}
