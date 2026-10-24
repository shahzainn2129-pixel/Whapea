package com.whapea.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Starts Whapea's background listening again after the phone restarts or Whapea is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Core.init(context)
        if (Core.auto) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BtService::class.java))
            } catch (e: Exception) { }
        }
    }
}
