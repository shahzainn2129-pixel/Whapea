package com.whapea.app

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Wakes Vynk when the server says something new is waiting. The push carries no
 * message content: it only starts the background listener, which fetches the
 * (encrypted) messages itself.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        try {
            Core.init(applicationContext)
            Core.prefs.edit().putString("fcm", token).putBoolean("fcmSent", false).apply()
            NetWatch.registerPush()
        } catch (e: Exception) { }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        try {
            Core.init(applicationContext)
            if (NetWatch.session() == null) return
            try {
                ContextCompat.startForegroundService(applicationContext, Intent(applicationContext, BtService::class.java))
            } catch (e: Exception) { }
            NetWatch.start()
        } catch (e: Exception) { }
    }
}
