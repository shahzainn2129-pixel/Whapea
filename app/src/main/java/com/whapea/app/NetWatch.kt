package com.whapea.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps an eye on the internet inbox while the screen is closed and shows a
 * notification. It only reads; the screen itself collects and deletes messages.
 */
object NetWatch {
    private const val SB = "https://bmcsvldqgvuahztwkune.supabase.co"
    private const val KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJtY3N2bGRxZ3Z1YWh6dHdrdW5lIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTM0MjkzMDksImV4cCI6MjEwOTAwNTMwOX0.lejp0HGOgQN4_oWquWXvkKfUJqwUtkayu1ADp3dt5YQ"
    @Volatile private var thread: Thread? = null
    @Volatile private var ringUntil = 0L
    private val nameCache = HashMap<String, String>()

    fun session(): JSONObject? {
        val s = Core.prefs.getString("netsess", null) ?: return null
        return try { JSONObject(s) } catch (e: Exception) { null }
    }

    fun setSession(s: String) {
        if (s.isBlank()) { Core.prefs.edit().remove("netsess").remove("netlast").apply(); return }
        Core.prefs.edit().putString("netsess", s).apply()
    }

    /** Tells the server which phone to wake. Safe to call often; it only sends when needed. */
    fun registerPush() {
        Core.io.execute {
            try {
                val s = session() ?: return@execute
                val uid = s.optString("uid")
                if (uid.isBlank()) return@execute
                var tok = Core.prefs.getString("fcm", null)
                if (tok == null) {
                    try {
                        val t = com.google.android.gms.tasks.Tasks.await(com.google.firebase.messaging.FirebaseMessaging.getInstance().token, 15, java.util.concurrent.TimeUnit.SECONDS)
                        tok = t
                        Core.prefs.edit().putString("fcm", t).apply()
                    } catch (e: Exception) { return@execute }
                }
                if (Core.prefs.getBoolean("fcmSent", false) && Core.prefs.getString("fcmUid", "") == uid) return@execute
                val body = JSONObject().put("owner", uid).put("token", tok).put("updated_at", java.time.Instant.now().toString()).toString()
                var r = call("/rest/v1/push_tokens?on_conflict=owner", "POST", body, s.optString("at"), "resolution=merge-duplicates,return=minimal")
                if (r.first == 401 && refresh(s)) r = call("/rest/v1/push_tokens?on_conflict=owner", "POST", body, (session() ?: s).optString("at"), "resolution=merge-duplicates,return=minimal")
                if (r.first in 200..299) Core.prefs.edit().putBoolean("fcmSent", true).putString("fcmUid", uid).apply()
            } catch (e: Exception) { }
        }
    }

    fun start() {
        if (thread?.isAlive == true || session() == null) return
        registerPush()
        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    internal fun call(path: String, method: String = "GET", body: String? = null, token: String, prefer: String? = null): Pair<Int, String> {
        val c = URL(SB + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("apikey", KEY)
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        if (prefer != null) c.setRequestProperty("Prefer", prefer)
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val txt = try { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
        c.disconnect()
        return code to txt
    }

    /** Puts one frame (already sealed) into someone's inbox. */
    fun postFrame(receiver: String, payload: String): Boolean {
        return try {
            val s = session() ?: return false
            val body = JSONObject().put("sender", s.optString("uid")).put("receiver", receiver).put("payload", payload).toString()
            var r = call("/rest/v1/frames", "POST", body, s.optString("at"), "return=minimal")
            if (r.first == 401 && refresh(s)) r = call("/rest/v1/frames", "POST", body, (session() ?: s).optString("at"), "return=minimal")
            r.first in 200..299
        } catch (e: Exception) { false }
    }

    private fun refresh(s: JSONObject): Boolean {
        return try {
            val (code, txt) = call("/auth/v1/token?grant_type=refresh_token", "POST",
                JSONObject().put("refresh_token", s.optString("rt")).toString(), KEY)
            if (code != 200) return false
            val j = JSONObject(txt)
            s.put("at", j.getString("access_token")).put("rt", j.getString("refresh_token")).put("ts", System.currentTimeMillis())
            setSession(s.toString()); true
        } catch (e: Exception) { false }
    }

    private val avaCache = HashMap<String, android.graphics.Bitmap?>()
    private val nameAt = HashMap<String, Long>()

    /** The sender's profile picture as a round bitmap, if they have one (filled in by nameOf). */
    fun avatarOf(uid: String): android.graphics.Bitmap? = avaCache[uid]

    private fun roundAvatar(src: String): android.graphics.Bitmap? = try {
        val raw = android.util.Base64.decode(src.substringAfter("base64,"), android.util.Base64.DEFAULT)
        val b = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
        if (b == null) null else {
            val sz = 128
            val side = minOf(b.width, b.height)
            val sq = android.graphics.Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
            val sc = android.graphics.Bitmap.createScaledBitmap(sq, sz, sz, true)
            val out = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(out)
            val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            cv.drawCircle(sz / 2f, sz / 2f, sz / 2f, pt)
            pt.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            cv.drawBitmap(sc, 0f, 0f, pt)
            out
        }
    } catch (e: Exception) { null }

    private fun nameOf(uid: String, token: String): String {
        val old = nameCache[uid]
        if (old != null && System.currentTimeMillis() - (nameAt[uid] ?: 0L) < 600000L) return old
        return try {
            val (c, t) = call("/rest/v1/profiles?select=name,username,avatar&id=eq.$uid", token = token)
            val o = JSONArray(t).optJSONObject(0)
            val n = (o?.optString("name") ?: "").trim().ifBlank { o?.optString("username") ?: "" }.ifBlank { "New message" }
            val av = o?.optString("avatar") ?: ""
            if (av.startsWith("data:image/")) avaCache[uid] = roundAvatar(av) else avaCache.remove(uid)
            nameCache[uid] = n; nameAt[uid] = System.currentTimeMillis(); n
        } catch (e: Exception) { old ?: "New message" }
    }

    private var socTick = 0

    private fun isFresh(createdAt: String): Boolean = try {
        val df = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        System.currentTimeMillis() - (df.parse(createdAt.take(19))?.time ?: 0L) < 45000
    } catch (e: Exception) { false }

    /** Likes, comments, follows and mentions while the screen is closed. */
    private fun social(s: JSONObject) {
        try {
            val (code, txt) = call("/rest/v1/rpc/my_notifs", "POST", "{\"p_limit\":10}", s.optString("at"))
            if (code != 200) return
            val arr = JSONArray(txt)
            var max = 0L
            for (i in 0 until arr.length()) max = maxOf(max, arr.getJSONObject(i).getLong("id"))
            val last = Core.prefs.getLong("soclast", -1L)
            if (last < 0) { Core.prefs.edit().putLong("soclast", max).apply(); return }
            for (i in arr.length() - 1 downTo 0) {
                val o = arr.getJSONObject(i)
                val id = o.getLong("id")
                if (id <= last || o.optBoolean("read")) continue
                val who = o.optString("name").ifBlank { o.optString("username") }.ifBlank { "Someone" }
                val what = when (o.optString("kind")) {
                    "like" -> "liked your post"; "comment" -> "commented on your post"
                    "follow" -> "started following you"; "mention" -> "mentioned you"
                    "request" -> "wants to follow you"; else -> continue
                }
                Core.notifySocial(id, who, what)
            }
            if (max > last) Core.prefs.edit().putLong("soclast", max).apply()
        } catch (e: Exception) { }
    }

    private fun loop() {
        while (true) {
            try {
                val s = session() ?: break
                if (Core.uiAttached) { Notif.clearAll(); if (Core.prefs.getLong("soclast", -1L) != -1L) Core.prefs.edit().putLong("soclast", -1L).apply(); socTick = 0; Thread.sleep(4000); continue }
                val last = Core.prefs.getLong("netlast", 0L)
                var (code, txt) = call("/rest/v1/frames?select=id,sender,payload,created_at&id=gt.$last&order=id.asc&limit=8", token = s.optString("at"))
                if (code == 401 && refresh(s)) {
                    val r = call("/rest/v1/frames?select=id,sender,payload,created_at&id=gt.$last&order=id.asc&limit=8", token = s.optString("at"))
                    code = r.first; txt = r.second
                }
                if (code == 200) {
                    val arr = JSONArray(txt)
                    var max = last
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        max = maxOf(max, f.getLong("id"))
                        var p = try { JSONObject(f.getString("payload")) } catch (e: Exception) { continue }
                        val who = nameOf(f.getString("sender"), s.optString("at"))
                        if (p.has("e") && p.has("c") && E2e.ready()) {
                            val pk = E2e.peerPub(f.getString("sender"), s.optString("at"))
                            val t = if (pk != null) E2e.decrypt(f.getString("sender"), f.getString("payload"), pk) else null
                            val o = try { if (t != null) JSONObject(t) else null } catch (e: Exception) { null }
                            if (o != null) p = o
                        }
                        if (p.has("e") && p.has("c")) {
                            // End-to-end encrypted: the phone cannot read it here, only the hint (m = message, c = call ring).
                            val fresh = isFresh(f.optString("created_at"))
                            when (p.optString("h")) {
                                "m" -> {
                                    Core.notifyMsg(f.getString("sender"), who, "New message")
                                    try {
                                        val mid = p.optString("mid")
                                        if (mid.isNotBlank() && mid.length <= 40 && s.optString("uid").isNotBlank()) {
                                            val ack = JSONObject().put("t", "ack").put("id", mid).toString()
                                            call("/rest/v1/frames", "POST", JSONObject().put("sender", s.optString("uid")).put("receiver", f.getString("sender")).put("payload", ack).toString(), s.optString("at"))
                                        }
                                    } catch (e: Exception) { }
                                }
                                "c" -> if (fresh) { Core.notifyCallFrom(who); Core.ringStart(); ringUntil = System.currentTimeMillis() + 40000
                                    try {
                                        if (s.optString("uid").isNotBlank()) {
                                            val rg = JSONObject().put("t", "call").put("op", "rng").toString()
                                            call("/rest/v1/frames", "POST", JSONObject().put("sender", s.optString("uid")).put("receiver", f.getString("sender")).put("payload", rg).toString(), s.optString("at"))
                                        }
                                    } catch (e: Exception) { }
                                }
                            }
                            continue
                        }
                        when (p.optString("t")) {
                            "msg" -> {
                                val body = when (p.optString("type")) {
                                    "img" -> if (p.optInt("once") == 1) "Photo (view once)" else "Photo"
                                    "video" -> if (p.optInt("once") == 1) "Video (view once)" else "Video"
                                    "voice" -> "Voice message"; "audio" -> "Audio"; "file" -> "Document"; "contact" -> "Contact"
                                    "loc" -> "Location"; "poll" -> "Poll"; else -> p.optString("text").take(300)
                                }
                                if (p.has("g")) Core.notifyMsg(f.getString("sender"), who, body)
                                else Notif.push(f.getString("sender"), who, p.optString("id"), body)
                                try {
                                    val mid = p.optString("id")
                                    if (mid.isNotBlank() && !p.has("g") && s.optString("uid").isNotBlank()) {
                                        val ack = JSONObject().put("t", "ack").put("id", mid).toString()
                                        call("/rest/v1/frames", "POST", JSONObject().put("sender", s.optString("uid")).put("receiver", f.getString("sender")).put("payload", ack).toString(), s.optString("at"))
                                    }
                                } catch (e: Exception) { }
                            }
                            "gcall" -> if (p.optString("op") == "ring" && System.currentTimeMillis() - p.optLong("ts", 0L) < 45000) {
                                Core.notifyCallFrom(who + " (group)"); Core.ringStart(); ringUntil = System.currentTimeMillis() + 40000
                            } else if (p.optString("op") == "leave" || p.optString("op") == "cancel") { Core.ringStop(); Core.clearCallNotice(); ringUntil = 0 }
                            "call" -> when (p.optString("op")) {
                                "ring" -> if (isFresh(f.optString("created_at"))) { Core.notifyCallFrom(who); Core.ringStart(); ringUntil = System.currentTimeMillis() + 40000
                                    try {
                                        if (s.optString("uid").isNotBlank()) {
                                            val rg = JSONObject().put("t", "call").put("op", "rng").toString()
                                            call("/rest/v1/frames", "POST", JSONObject().put("sender", s.optString("uid")).put("receiver", f.getString("sender")).put("payload", rg).toString(), s.optString("at"))
                                        }
                                    } catch (e: Exception) { }
                                }
                                "end", "rej", "busy" -> { Core.ringStop(); Core.clearCallNotice(); ringUntil = 0 }
                            }
                        }
                    }
                    if (max > last) Core.prefs.edit().putLong("netlast", max).apply()
                }
                if (++socTick % 12 == 1) social(s)
                if (ringUntil in 1..System.currentTimeMillis()) { Core.ringStop(); ringUntil = 0 }
                Thread.sleep(2500)
            } catch (e: InterruptedException) { break } catch (e: Exception) {
                try { Thread.sleep(10000) } catch (x: InterruptedException) { break }
            }
        }
        thread = null
    }
}
