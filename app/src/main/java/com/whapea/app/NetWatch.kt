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

    fun start() {
        if (thread?.isAlive == true || session() == null) return
        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    private fun call(path: String, method: String = "GET", body: String? = null, token: String): Pair<Int, String> {
        val c = URL(SB + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("apikey", KEY)
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val txt = try { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
        c.disconnect()
        return code to txt
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

    private fun nameOf(uid: String, token: String): String {
        nameCache[uid]?.let { return it }
        return try {
            val (c, t) = call("/rest/v1/profiles?select=name,username&id=eq.$uid", token = token)
            val o = JSONArray(t).optJSONObject(0)
            val n = (o?.optString("name") ?: "").ifBlank { o?.optString("username") ?: "" }.ifBlank { "New message" }
            nameCache[uid] = n; n
        } catch (e: Exception) { "New message" }
    }

    private fun loop() {
        while (true) {
            try {
                val s = session() ?: break
                if (Core.uiAttached) { Thread.sleep(4000); continue }
                val last = Core.prefs.getLong("netlast", 0L)
                var (code, txt) = call("/rest/v1/frames?select=id,sender,payload&id=gt.$last&order=id.asc&limit=20", token = s.optString("at"))
                if (code == 401 && refresh(s)) {
                    val r = call("/rest/v1/frames?select=id,sender,payload&id=gt.$last&order=id.asc&limit=20", token = s.optString("at"))
                    code = r.first; txt = r.second
                }
                if (code == 200) {
                    val arr = JSONArray(txt)
                    var max = last
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        max = maxOf(max, f.getLong("id"))
                        val p = try { JSONObject(f.getString("payload")) } catch (e: Exception) { continue }
                        val who = nameOf(f.getString("sender"), s.optString("at"))
                        when (p.optString("t")) {
                            "msg" -> {
                                val body = when (p.optString("type")) { "img" -> "Photo"; "voice" -> "Voice message"; "contact" -> "Contact"; else -> p.optString("text").take(120) }
                                Core.notifyMsg(f.getString("sender"), who, body)
                                try {
                                    val mid = p.optString("id")
                                    if (mid.isNotBlank() && !p.has("g") && s.optString("uid").isNotBlank()) {
                                        val ack = JSONObject().put("t", "ack").put("id", mid).toString()
                                        call("/rest/v1/frames", "POST", JSONObject().put("sender", s.optString("uid")).put("receiver", f.getString("sender")).put("payload", ack).toString(), s.optString("at"))
                                    }
                                } catch (e: Exception) { }
                            }
                            "call" -> when (p.optString("op")) {
                                "ring" -> { Core.notifyCallFrom(who); Core.ringStart(); ringUntil = System.currentTimeMillis() + 40000 }
                                "end", "rej", "busy" -> { Core.ringStop(); Core.clearCallNotice(); ringUntil = 0 }
                            }
                        }
                    }
                    if (max > last) Core.prefs.edit().putLong("netlast", max).apply()
                }
                if (ringUntil in 1..System.currentTimeMillis()) { Core.ringStop(); ringUntil = 0 }
                Thread.sleep(2500)
            } catch (e: InterruptedException) { break } catch (e: Exception) {
                try { Thread.sleep(10000) } catch (x: InterruptedException) { break }
            }
        }
        thread = null
    }
}
