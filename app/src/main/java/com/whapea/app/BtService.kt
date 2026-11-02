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

    private var receiverOn = false
    private var wl: android.os.PowerManager.WakeLock? = null
    private val btState = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val st = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)
            if (st == android.bluetooth.BluetoothAdapter.STATE_ON) {
                if (Core.auto && !Core.running) Core.start()
            } else if (st == android.bluetooth.BluetoothAdapter.STATE_OFF) {
                if (Core.running) Core.stop()
                Core.say("Turn on Bluetooth")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        if (!receiverOn) {
            androidx.core.content.ContextCompat.registerReceiver(
                this, btState,
                android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
            receiverOn = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Core.init(this)
        val pi = android.app.PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, Core.CH_SVC)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Whapea")
            .setContentText(if (NetWatch.session() != null) "Waiting for messages" else "Listening for messages over Bluetooth")
            .setOngoing(true).setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT >= 29) {
            val btOk = Build.VERSION.SDK_INT < 31 || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val type = if (btOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                else if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            try { startForeground(7, n, type) } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        } else {
            startForeground(7, n)
        }
        if (Core.auto && !Core.running && Core.btOn()) Core.start()
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
        if (receiverOn) {
            try { unregisterReceiver(btState) } catch (e: Exception) { }
            receiverOn = false
        }
    }
}
