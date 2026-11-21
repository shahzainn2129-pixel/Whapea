package com.whapea.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import org.json.JSONArray
import org.json.JSONObject

/** Message notifications that show the text and can be answered, marked as read or muted without opening the app. */
object Notif {
    const val A_REPLY = "com.whapea.app.NOTIF_REPLY"
    const val A_READ = "com.whapea.app.NOTIF_READ"
    const val A_MUTE = "com.whapea.app.NOTIF_MUTE"

    private fun arr(key: String): JSONArray = try { JSONArray(Core.prefs.getString(key, "[]")) } catch (e: Exception) { JSONArray() }

    fun isMuted(uid: String): Boolean {
        val a = arr("muted")
        for (i in 0 until a.length()) if (a.optString(i) == uid) return true
        return false
    }

    /** Notification options chosen in Settings: hide message text, quiet hours. */
    fun setPrefs(json: String) {
        try { JSONObject(json); Core.prefs.edit().putString("npref", json).apply() } catch (e: Exception) { }
    }

    private fun pref(): JSONObject = try { JSONObject(Core.prefs.getString("npref", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun hideText(): Boolean = pref().optBoolean("hide", false)

    /** True during the quiet hours window (may cross midnight). */
    fun quietNow(): Boolean {
        val p = pref()
        if (!p.optBoolean("qh", false)) return false
        val c = java.util.Calendar.getInstance()
        val now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        val f = p.optInt("qf", 23 * 60); val t = p.optInt("qt", 7 * 60)
        return if (f <= t) now in f until t else (now >= f || now < t)
    }

    fun setMuted(json: String) {
        try { JSONArray(json); Core.prefs.edit().putString("muted", json).apply() } catch (e: Exception) { }
    }

    private fun add(key: String, v: String) {
        val a = arr(key)
        for (i in 0 until a.length()) if (a.optString(i) == v) return
        Core.prefs.edit().putString(key, a.put(v).toString()).apply()
    }

    /** What the screen should apply when it opens: answers sent, chats read, chats muted. */
    fun take(): String {
        val o = JSONObject().put("out", arr("outbox")).put("read", arr("readq")).put("mute", arr("mutenew"))
        Core.prefs.edit().remove("outbox").remove("readq").remove("mutenew").apply()
        return o.toString()
    }

    private fun unread(uid: String): JSONArray = arr("nt_$uid")

    fun clearAll() {
        val ed = Core.prefs.edit()
        for (k in Core.prefs.all.keys) if (k.startsWith("nt_")) ed.remove(k)
        ed.apply()
    }

    fun push(uid: String, who: String, id: String, text: String) {
        if (isMuted(uid)) return
        val a = unread(uid)
        a.put(JSONObject().put("id", id).put("text", text).put("t", System.currentTimeMillis()))
        val keep = JSONArray()
        for (i in maxOf(0, a.length() - 8) until a.length()) keep.put(a.get(i))
        Core.prefs.edit().putString("nt_$uid", keep.toString()).apply()
        show(uid, who, keep)
    }

    private fun act(c: Context, action: String, uid: String) =
        Intent(c, NotifAction::class.java).setAction(action).putExtra("uid", uid)

    private fun show(uid: String, who: String, list: JSONArray) {
        try {
            val c = Core.ctx()
            val me = Person.Builder().setName("You").build()
            val ava = NetWatch.avatarOf(uid)
            val sp = Person.Builder().setName(who).apply { if (ava != null) setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(ava)) }.build()
            val st = NotificationCompat.MessagingStyle(me)
            var last = ""
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                last = if (hideText()) "New message" else o.optString("text")
                st.addMessage(last, o.optLong("t"), sp)
            }
            val h = uid.hashCode()
            val open = PendingIntent.getActivity(c, h, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val mut = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val replyPi = PendingIntent.getBroadcast(c, h + 1, act(c, A_REPLY, uid), PendingIntent.FLAG_UPDATE_CURRENT or mut)
            val readPi = PendingIntent.getBroadcast(c, h + 2, act(c, A_READ, uid), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val mutePi = PendingIntent.getBroadcast(c, h + 3, act(c, A_MUTE, uid), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val ri = RemoteInput.Builder("r").setLabel("Reply").build()
            val n = NotificationCompat.Builder(c, Core.CH)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(who).setContentText(last).setStyle(st).apply { if (ava != null) setLargeIcon(ava) }
                .setAutoCancel(true).setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(if (quietNow()) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
                .setSilent(quietNow())
                .addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", replyPi)
                    .addRemoteInput(ri).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setAllowGeneratedReplies(true).build())
                .addAction(android.R.drawable.ic_menu_view, "Mark as read", readPi)
                .addAction(android.R.drawable.ic_lock_silent_mode, "Mute", mutePi)
                .build()
            NotificationManagerCompat.from(c).notify(h, n)
        } catch (e: SecurityException) { } catch (e: Exception) { }
    }

    fun cancel(uid: String) {
        try { NotificationManagerCompat.from(Core.ctx()).cancel(uid.hashCode()) } catch (e: Exception) { }
    }

    fun markRead(uid: String) {
        val a = unread(uid)
        val ids = JSONArray()
        for (i in 0 until a.length()) ids.put(a.getJSONObject(i).optString("id"))
        Core.prefs.edit().remove("nt_$uid").apply()
        add("readq", "u:$uid")
        if (ids.length() > 0 && (E2e.mine()?.optBoolean("rc", true) != false)) {
            val s = NetWatch.session()
            val pk = if (s != null) E2e.peerPub(uid, s.optString("at")) else null
            if (pk != null) {
                val env = E2e.encrypt(uid, pk, JSONObject().put("t", "read").put("ids", ids).toString())
                if (env != null) NetWatch.postFrame(uid, env)
            }
        }
        cancel(uid)
    }

    fun mute(uid: String) {
        add("muted", uid)
        add("mutenew", "u:$uid")
        Core.prefs.edit().remove("nt_$uid").apply()
        cancel(uid)
    }

    fun reply(uid: String, text: String): Boolean {
        val s = NetWatch.session() ?: return false
        val pk = E2e.peerPub(uid, s.optString("at")) ?: return false
        val id = "n" + java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1e6).toLong(), 36)
        val plain = JSONObject().put("t", "msg").put("id", id).put("type", "text").put("text", text.take(4000))
            .put("reply", JSONObject.NULL).put("fwd", false).toString()
        val env = E2e.encrypt(uid, pk, plain) ?: return false
        val o = JSONObject(env).put("h", "m").put("mid", id)
        if (!NetWatch.postFrame(uid, o.toString())) return false
        val out = arr("outbox")
        out.put(JSONObject().put("cid", "u:$uid").put("id", id).put("text", text.take(4000)).put("t", System.currentTimeMillis()))
        Core.prefs.edit().putString("outbox", out.toString()).apply()
        return true
    }
}

class NotifAction : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uid = intent.getStringExtra("uid") ?: return
        val action = intent.action ?: return
        Core.init(context)
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("r")?.toString()?.trim()
        val pending = goAsync()
        Core.io.execute {
            try {
                when (action) {
                    Notif.A_REPLY -> { if (!text.isNullOrEmpty()) Notif.reply(uid, text); Notif.markRead(uid) }
                    Notif.A_READ -> Notif.markRead(uid)
                    Notif.A_MUTE -> Notif.mute(uid)
                }
            } catch (e: Exception) {
            } finally { pending.finish() }
        }
    }
}
