package com.multiprofileemu.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps this app classified as "foreground" for exactly as long as a cloud sync/authorize
 * operation is in flight. Confirmed on real hardware (`dumpsys netpolicy`) that some devices cut
 * ALL network access for a backgrounded app with zero grace period — and the Google Drive OAuth
 * flow inherently backgrounds this app the moment the browser takes the screen, right as rclone
 * needs to exchange the auth code for a token. A foreground service with a visible notification is
 * Android's sanctioned way to stay exempt from that. Started/stopped from [MainActivity] based on
 * [com.multiprofileemu.app.AppController.busy]'s phase — see the `CLOUD_PHASES` check there.
 */
class CloudSyncForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Sincronizando con la nube...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sincronización cloud", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "cloud_sync"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CloudSyncForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CloudSyncForegroundService::class.java))
        }
    }
}
