package com.whapea.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.Executors

/** Notifications, local storage, media and updates. (Bluetooth was removed.) */
@SuppressLint("MissingPermission")
object Core {
    private const val FEED_URL = "https://raw.githubusercontent.com/shahzainn2129-pixel/Whapea/main/sponsors.json"
    private const val OWNER_HASH = "cd3c52d11ece85816bab73c0147660f24d46de44591dbe6ef7e4d60103d43f46"

    private var ready = false
    private lateinit var app: Context
    lateinit var prefs: SharedPreferences
    fun ctx(): Context = app

    private val main = Handler(Looper.getMainLooper())
    val io = Executors.newCachedThreadPool()
    private val writer = Executors.newSingleThreadExecutor()


    var running = false
    var scanning = false
    var status = "Not started"

    var onLink: (String, Boolean) -> Unit = { _, _ -> }
    var onData: (String, String) -> Unit = { _, _ -> }
    var onUi: () -> Unit = {}
    /** True while the screen is open and can handle messages itself. */
    var uiAttached = false

    const val CH = "whapea_msgs"
    const val CH_SVC = "whapea_svc"
    const val CH_CALL = "whapea_calls"
    private const val CALL_ID = 99
    private const val LIVE_ID = 98
    const val CH_LIVE = "whapea_live_call"
    private var pendingRing: Triple<String, String, Long>? = null
    private var ringtone: android.media.Ringtone? = null
    private var ringPlayer: android.media.MediaPlayer? = null

    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("whapea", Context.MODE_PRIVATE)
        ready = true
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel(CH, "Messages", android.app.NotificationManager.IMPORTANCE_HIGH))
            makeCallChannel(nm)
            val cc = android.app.NotificationChannel(CH_CALL, "Calls", android.app.NotificationManager.IMPORTANCE_HIGH)
            cc.setSound(
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN).build()
            )
            cc.enableVibration(true)
            nm.createNotificationChannel(cc)
            nm.createNotificationChannel(android.app.NotificationChannel(CH_LIVE, "Ongoing call", android.app.NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(android.app.NotificationChannel(CH_SVC, "Vynk is running", android.app.NotificationManager.IMPORTANCE_LOW))
        }
    }

    var auto: Boolean
        get() = prefs.getBoolean("auto", false)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }

    // ---------- messages that arrive while the screen is closed ----------
    private fun names(): JSONObject = try { JSONObject(prefs.getString("names", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    /** The ringtone the person picked for calls. Empty means the phone's default. */
    fun ringUriStr(): String = prefs.getString("ringUri", "") ?: ""

    private fun isAssetRing() = ringUriStr().startsWith("asset:")

    private fun ringUri(): android.net.Uri {
        val u = ringUriStr()
        return if (u.isNotEmpty() && !isAssetRing()) android.net.Uri.parse(u)
        else android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
    }

    fun ringName(): String {
        val u = ringUriStr()
        if (u.isEmpty()) return ""
        if (isAssetRing()) return "Vynk · " + when (u.removePrefix("asset:")) {
            "r1" -> "Classic"; "r2" -> "Waves"; "r3" -> "Sunrise"; "r4" -> "Digital"; "r5" -> "Morning"; "r6" -> "Bazaar"; "r7" -> "Two-tone"; "r8" -> "Calm"; else -> "Tone" }
        return try { android.media.RingtoneManager.getRingtone(app, android.net.Uri.parse(u))?.getTitle(app) ?: "" } catch (e: Exception) { "" }
    }

    fun callCh(): String = if (ringUriStr().isEmpty()) CH_CALL else if (isAssetRing()) CH_CALL + "_silent" else CH_CALL + "_" + Integer.toHexString(ringUriStr().hashCode())

    private fun makeCallChannel(nm: android.app.NotificationManager) {
        if (Build.VERSION.SDK_INT < 26 || ringUriStr().isEmpty()) return
        val id = callCh()
        if (nm.getNotificationChannel(id) != null) return
        val cc = android.app.NotificationChannel(id, "Calls", android.app.NotificationManager.IMPORTANCE_HIGH)
        if (isAssetRing()) cc.setSound(null, null)
        else cc.setSound(ringUri(), android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
        cc.enableVibration(true)
        nm.createNotificationChannel(cc)
    }

    fun setRingUri(u: String) {
        prefs.edit().putString("ringUri", u).apply()
        ringStop(); ringtone = null; try { ringPlayer?.release() } catch (e: Exception) { }; ringPlayer = null
        try { makeCallChannel(app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager) } catch (e: Exception) { }
    }

    fun ringStart() {
        try {
            if (isAssetRing()) {
                ringPlayer?.release()
                val fd = app.assets.openFd("tones/" + ringUriStr().removePrefix("asset:") + ".ogg")
                val mp = android.media.MediaPlayer()
                mp.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length); fd.close()
                mp.isLooping = true; mp.prepare(); mp.start()
                ringPlayer = mp
                return
            }
            if (ringtone == null) {
                ringtone = android.media.RingtoneManager.getRingtone(app, ringUri())
            }
            if (Build.VERSION.SDK_INT >= 28) ringtone?.isLooping = true
            ringtone?.play()
        } catch (e: Exception) { }
    }

    fun ringStop() {
        try { ringtone?.stop() } catch (e: Exception) { }
        try { ringPlayer?.let { if (it.isPlaying) it.stop() }; ringPlayer?.release() } catch (e: Exception) { }
        ringPlayer = null
    }

    fun clearCallNotice() {
        pendingRing = null
        ringStop()
        try { NotificationManagerCompat.from(app).cancel(CALL_ID) } catch (e: SecurityException) { }
    }

    private fun callPi(code: Int, act: String): android.app.PendingIntent =
        android.app.PendingIntent.getActivity(app, code,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("callAct", act),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

    fun liveCall(name: String, on: Boolean) {
        try {
            if (!on) { NotificationManagerCompat.from(app).cancel(LIVE_ID); return }
            val n = NotificationCompat.Builder(app, CH_LIVE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(name).setContentText("Vynk call in progress")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true).setOnlyAlertOnce(true).setUsesChronometer(true)
                .setContentIntent(callPi(10, "open"))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang up", callPi(11, "hangup"))
                .build()
            NotificationManagerCompat.from(app).notify(LIVE_ID, n)
        } catch (e: SecurityException) { }
    }

    private fun notifyCall(addr: String) = notifyCallFrom(names().optString(addr).ifBlank { "Someone nearby" })

    fun notifyCallFrom(who: String) {
        try {
            val pi = android.app.PendingIntent.getActivity(app, 2,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(app, callCh())
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Vynk call").setContentText("$who is calling you")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(pi, true).setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", callPi(12, "decline"))
                .addAction(android.R.drawable.ic_menu_call, "Answer", callPi(13, "answer"))
                .setOngoing(true).setTimeoutAfter(40000).build()
            NotificationManagerCompat.from(app).notify(CALL_ID, n)
            // With "Display over other apps" allowed, Android lets us open the call screen directly,
            // even when the phone is unlocked and Vynk is closed.
            try {
                if (android.provider.Settings.canDrawOverlays(app))
                    app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (e: Exception) { }
        } catch (e: SecurityException) { }
    }

    private fun dispatch(addr: String, raw: String) {
        try {
            val o = JSONObject(raw)
            if (o.optString("t") == "call") {
                val op = o.optString("op")
                if (op == "end" || op == "rej" || op == "busy") {
                    if (CallAudio.active && CallAudio.peer == addr) CallAudio.stop()
                    ringStop(); clearCallNotice()
                }
                if (op == "ring" && !uiAttached) {
                    pendingRing = Triple(addr, raw, System.currentTimeMillis())
                    notifyCall(addr)
                    return
                }
            }
            if (o.optString("t") == "hi") {
                val n = names(); n.put(addr, o.optString("name").take(40))
                prefs.edit().putString("names", n.toString()).apply()
            }
        } catch (e: Exception) { }
        if (uiAttached) { onData(addr, raw); return }
        val arr = try { JSONArray(prefs.getString("inbox", "[]")) } catch (e: Exception) { JSONArray() }
        if (arr.length() >= 300) return
        arr.put(JSONObject().put("a", addr).put("r", raw))
        prefs.edit().putString("inbox", arr.toString()).apply()
        try {
            val o = JSONObject(raw)
            if (o.optString("t") == "msg") {
                val who = names().optString(addr).ifBlank { "New message" }
                val body = when (o.optString("type")) { "img" -> "Photo"; "voice" -> "Voice message"; "contact" -> "Contact"; else -> o.optString("text").take(120) }
                notifyMsg(addr, who, body)
            }
        } catch (e: Exception) { }
    }

    /** WhatsApp-style "Missed voice call" with Call back and Message buttons. */
    fun notifyMissed(uid: String, who: String) {
        try {
            val id = ("missed:" + uid).hashCode()
            fun pi(code: Int, call: Boolean) = android.app.PendingIntent.getActivity(app, code + (id and 0xffff),
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    .apply { if (call) { putExtra("callAct", "callback"); putExtra("callUid", uid) } else putExtra("openUid", uid) },
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(app, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(who).setContentText("Missed voice call")
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setAutoCancel(true).setContentIntent(pi(1, false))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(android.R.drawable.ic_menu_call, "Call back", pi(2, true))
                .addAction(android.R.drawable.ic_menu_send, "Message", pi(3, false))
                .apply { NetWatch.avatarOf(uid)?.let { setLargeIcon(it) } }.build()
            NotificationManagerCompat.from(app).notify(id, n)
        } catch (e: SecurityException) { }
    }

    fun notifyMsg(addr: String, who: String, body: String) {
        try {
            val pi = android.app.PendingIntent.getActivity(app, 0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openUid", addr),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(app, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(who).setContentText(if (Notif.hideText()) "New message" else body)
                .setAutoCancel(true).setContentIntent(pi)
                .setPriority(if (Notif.quietNow()) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
                .setSilent(Notif.quietNow())
                .apply { NetWatch.avatarOf(addr)?.let { setLargeIcon(it) } }.build()
            NotificationManagerCompat.from(app).notify(addr.hashCode(), n)
        } catch (e: SecurityException) { }
    }

    fun notifySocial(id: Long, who: String, body: String) {
        try {
            val pi = android.app.PendingIntent.getActivity(app, 0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(app, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(who).setContentText(body)
                .setAutoCancel(true).setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
            NotificationManagerCompat.from(app).notify(("soc$id").hashCode(), n)
        } catch (e: SecurityException) { }
    }

    /** Hands everything that arrived in the background to the screen. */
    fun drainInbox() {
        val pr = pendingRing
        pendingRing = null
        try { NotificationManagerCompat.from(app).cancel(CALL_ID) } catch (e: SecurityException) { }
        val arr = try { JSONArray(prefs.getString("inbox", "[]")) } catch (e: Exception) { JSONArray() }
        if (arr.length() > 0) prefs.edit().putString("inbox", "[]").apply()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            onData(o.getString("a"), o.getString("r"))
        }
        if (arr.length() > 0) {
            try { NotificationManagerCompat.from(app).cancelAll() } catch (e: SecurityException) { }
        }
        if (pr != null && System.currentTimeMillis() - pr.third < 40000) onData(pr.first, pr.second)
    }

    // ---------- photos and videos kept as files, so the saved chat state stays small ----------
    private fun mediaDir(): java.io.File = java.io.File(app.filesDir, "media").also { it.mkdirs() }

    /** Saves a data: URL to a file and returns its file:// address, or "" on failure. */
    fun mediaSave(id: String, dataUrl: String): String {
        return try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0 || !dataUrl.startsWith("data:video/")) return ""
            val ext = if (dataUrl.startsWith("data:video/mp4")) "mp4" else "webm"
            val name = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40).ifEmpty { "m" }
            val f = java.io.File(mediaDir(), "$name.$ext")
            f.writeBytes(android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT))
            "file://" + f.absolutePath
        } catch (e: Exception) { "" }
    }

    /** Saves a document or audio file (any type, up to 1 MB) in the app's own folder. */
    fun fileSave(id: String, dataUrl: String, ext: String): String {
        return try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0 || !dataUrl.startsWith("data:")) return ""
            val raw = android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT)
            if (raw.size > 1_000_000) return ""
            val name = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40).ifEmpty { "f" }
            val e = ext.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(5).ifEmpty { "bin" }
            val f = java.io.File(mediaDir(), "$name.$e")
            f.writeBytes(raw)
            "file://" + f.absolutePath
        } catch (e: Exception) { "" }
    }

    /** Copies a saved file into the phone's Downloads/Vynk folder. */
    fun fileExport(uri: String, name: String, mime: String): Boolean {
        return try {
            val f = java.io.File(uri.removePrefix("file://"))
            if (!f.exists() || f.parentFile?.canonicalPath != mediaDir().canonicalPath) return false
            val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifEmpty { "file" }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues()
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Vynk")
                val u = app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
                val os = app.contentResolver.openOutputStream(u) ?: return false
                os.use { o -> f.inputStream().use { it.copyTo(o) } }
                true
            } else {
                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Vynk")
                dir.mkdirs()
                f.copyTo(java.io.File(dir, safe), true)
                true
            }
        } catch (e: Exception) { false }
    }

    /** Reads a saved file as base64 (for sending). */
    fun fileRead(uri: String): String {
        return try {
            val f = java.io.File(uri.removePrefix("file://"))
            if (f.parentFile?.canonicalPath != mediaDir().canonicalPath) return ""
            android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }

    fun mediaRead(uri: String): String {
        return try {
            val f = java.io.File(uri.removePrefix("file://"))
            if (f.parentFile?.canonicalPath != mediaDir().canonicalPath) return ""
            val mime = if (f.name.endsWith(".mp4")) "video/mp4" else "video/webm"
            "data:$mime;base64," + android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }

    fun mediaDel(uri: String) {
        try {
            val f = java.io.File(uri.removePrefix("file://"))
            if (f.parentFile?.canonicalPath == mediaDir().canonicalPath) f.delete()
        } catch (e: Exception) { }
    }

    fun loadState(): String = prefs.getString("state", "") ?: ""
    fun saveState(s: String) {
        prefs.edit().putString("state", s).apply()
    }

    fun btOn(): Boolean = false

    fun say(t: String) {
        status = t
        onUi()
    }

    fun uiJson(): String = JSONObject().put("running", false).put("scanning", false)
        .put("text", status).put("btOn", false).put("found", JSONArray()).toString()

    fun start() {}
    fun stop() { running = false }
    fun scan() {}
    fun connect(address: String) {}
    fun send(addr: String, json: String) {}
    fun sendFrame(addr: String, b: ByteArray) {}

    // ---------- sponsor posts (need internet, nothing else does) ----------
    fun fetchFeed(done: (String?) -> Unit) {
        io.execute {
            var result: String? = null
            try {
                val c = URL(FEED_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpsURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.instanceFollowRedirects = false
                if (c.responseCode == 200) {
                    val buf = java.io.ByteArrayOutputStream()
                    c.inputStream.use { inp ->
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = inp.read(chunk)
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                            if (buf.size() > 1_500_000) throw java.io.IOException("feed too large")
                        }
                    }
                    result = String(buf.toByteArray(), Charsets.UTF_8)
                }
                c.disconnect()
            } catch (e: Exception) {
                result = null
            }
            main.post { done(result) }
        }
    }

    // ---------- app updates (from the Releases page) ----------
    const val UPDATE_PREFIX = "https://github.com/shahzainn2129-pixel/Whapea/releases/download/"

    /** Calls back with {"n":build number,"url":apk link} or null when nothing could be read. */
    fun checkUpdate(done: (String?) -> Unit) {
        io.execute {
            var result: String? = null
            try {
                val c = URL("https://api.github.com/repos/shahzainn2129-pixel/Whapea/releases/latest").openConnection() as HttpsURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode == 200) {
                    val j = JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
                    val n = j.optString("tag_name").removePrefix("build-").toIntOrNull()
                    val assets = j.optJSONArray("assets")
                    var url = ""
                    if (assets != null) for (i in 0 until assets.length()) {
                        val u = assets.getJSONObject(i).optString("browser_download_url")
                        if (u.startsWith(UPDATE_PREFIX) && u.endsWith(".apk")) { url = u; break }
                    }
                    if (n != null && url.isNotEmpty()) result = JSONObject().put("n", n).put("url", url).toString()
                }
                c.disconnect()
            } catch (e: Exception) { result = null }
            main.post { done(result) }
        }
    }

    /** Downloads the new APK to the cache folder. Returns the file or null. */
    fun downloadApk(url: String, progress: (Int) -> Unit): java.io.File? {
        if (!url.startsWith(UPDATE_PREFIX)) return null
        return try {
            val dir = java.io.File(app.cacheDir, "updates").also { it.mkdirs() }
            val f = java.io.File(dir, "whapea.apk")
            var c = URL(url).openConnection() as HttpsURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000
            c.instanceFollowRedirects = true
            val total = c.contentLength
            var got = 0L
            c.inputStream.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (got > 40_000_000) throw java.io.IOException("too big")
                        if (total > 0) main.post { progress((got * 100 / total).toInt()) }
                    }
                }
            }
            if (got < 500_000) null else f
        } catch (e: Exception) { null }
    }

    /** The owner key is never stored here, only a one-way fingerprint of it. */
    fun checkOwnerKey(key: String): Boolean {
        val d = MessageDigest.getInstance("SHA-256").digest(("whapea-owner:" + key.trim()).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) } == OWNER_HASH
    }
}
