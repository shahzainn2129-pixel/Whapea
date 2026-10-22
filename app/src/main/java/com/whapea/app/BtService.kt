package com.whapea.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps Whapea listening for Bluetooth messages while the screen is closed. */
class BtService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Core.init(this)
        val pi = android.app.PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, Core.CH_SVC)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Whapea")
            .setContentText("Listening for messages over Bluetooth")
            .setOngoing(true).setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(7, n)
        }
        if (Core.auto && !Core.running && Core.btOn()) Core.start()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
