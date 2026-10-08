package com.mcai.ubuntudsu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class DsuInstallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("dsu_install", "DSU Installation", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, "dsu_install")
            .setContentTitle("DSU Installation进行中")
            .setContentText("The system is reading the GSI package through a local service; keep the app running in the background")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopForeground(true)
        super.onDestroy()
    }

}
