package com.whapea.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps Vynk online for messages while the screen is closed. */
class BtService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private var wl: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Core.init(this)
        val pi = android.app.PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, Core.CH_SVC)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Vynk")
            .setContentText("Waiting for messages")
            .setOngoing(true).setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT >= 34) {
            try { startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING) } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        } else {
            startForeground(7, n)
        }
        NetWatch.start()
        try {
            if (NetWatch.session() != null && wl == null) {
                wl = (getSystemService(POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "whapea:watch")
                wl?.setReferenceCounted(false); wl?.acquire()
            }
        } catch (e: Exception) { }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wl?.release() } catch (e: Exception) { }
        wl = null
    }
}
