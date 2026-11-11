package com.whapea.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Starts Vynk's background listening again after the phone restarts or Vynk is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Core.init(context)
        if (Core.auto || NetWatch.session() != null) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BtService::class.java))
            } catch (e: Exception) { }
        }
    }
}
