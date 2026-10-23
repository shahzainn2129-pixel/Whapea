package com.whapea.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.Executors

data class Found(val address: String, val name: String, val paired: Boolean)

/**
 * All Bluetooth work lives here. It uses plain Android Bluetooth (RFCOMM sockets),
 * so it does not need Google Play services. The screen (index.html) decides what
 * the messages mean; this class only moves text between phones.
 */
@SuppressLint("MissingPermission")
object Core {
    private val APP_UUID: UUID = UUID.fromString("8f2b6c1e-5d3a-4c7e-9b1a-0a6c3e5f7d21")
    private const val MAX_FRAME = 2_000_000
    private const val FEED_URL = "https://raw.githubusercontent.com/shahzainn2129-pixel/Whapea/main/sponsors.json"
    private const val OWNER_HASH = "cd3c52d11ece85816bab73c0147660f24d46de44591dbe6ef7e4d60103d43f46"

    private var ready = false
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var adapter: BluetoothAdapter? = null
    private var server: BluetoothServerSocket? = null
    private var receiverOn = false

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val writer = Executors.newSingleThreadExecutor()

    private class Conn(val socket: BluetoothSocket, val out: DataOutputStream)

    private val conns = mutableMapOf<String, Conn>()
    private val found = mutableListOf<Found>() // main thread only

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
    private var pendingRing: Triple<String, String, Long>? = null
    private var ringtone: android.media.Ringtone? = null

    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("whapea", Context.MODE_PRIVATE)
        adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        ready = true
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel(CH, "Messages", android.app.NotificationManager.IMPORTANCE_HIGH))
            val cc = android.app.NotificationChannel(CH_CALL, "Calls", android.app.NotificationManager.IMPORTANCE_HIGH)
            cc.setSound(
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN).build()
            )
            cc.enableVibration(true)
            nm.createNotificationChannel(cc)
            nm.createNotificationChannel(android.app.NotificationChannel(CH_SVC, "Whapea is running", android.app.NotificationManager.IMPORTANCE_LOW))
        }
    }

    var auto: Boolean
        get() = prefs.getBoolean("auto", false)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }

    // ---------- messages that arrive while the screen is closed ----------
    private fun names(): JSONObject = try { JSONObject(prefs.getString("names", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun ringStart() {
        try {
            if (ringtone == null) {
                ringtone = android.media.RingtoneManager.getRingtone(app,
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))
            }
            ringtone?.play()
        } catch (e: Exception) { }
    }

    fun ringStop() {
        try { ringtone?.stop() } catch (e: Exception) { }
    }

    private fun clearCallNotice() {
        pendingRing = null
        try { NotificationManagerCompat.from(app).cancel(CALL_ID) } catch (e: SecurityException) { }
    }

    private fun notifyCall(addr: String) {
        try {
            val who = names().optString(addr).ifBlank { "Someone nearby" }
            val pi = android.app.PendingIntent.getActivity(app, 2,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(app, CH_CALL)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle("Whapea call").setContentText("$who is calling you")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(pi, true).setContentIntent(pi)
                .setOngoing(true).setTimeoutAfter(40000).build()
            NotificationManagerCompat.from(app).notify(CALL_ID, n)
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

    private fun notifyMsg(addr: String, who: String, body: String) {
        try {
            val pi = android.app.PendingIntent.getActivity(app, 0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(app, CH)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(who).setContentText(body)
                .setAutoCancel(true).setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH).build()
            NotificationManagerCompat.from(app).notify(addr.hashCode(), n)
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

    fun loadState(): String = prefs.getString("state", "") ?: ""
    fun saveState(s: String) {
        prefs.edit().putString("state", s).apply()
    }

    fun btOn(): Boolean = adapter?.isEnabled == true

    fun say(t: String) {
        status = t
        onUi()
    }

    fun uiJson(): String {
        val arr = JSONArray()
        for (f in found) arr.put(JSONObject().put("a", f.address).put("n", f.name).put("p", f.paired))
        return JSONObject().put("running", running).put("scanning", scanning)
            .put("text", status).put("btOn", btOn()).put("found", arr).toString()
    }

    // ---------- start / stop ----------
    fun start() {
        if (running) return
        val a = adapter
        if (a == null) {
            say("This phone has no Bluetooth")
            return
        }
        if (!a.isEnabled) {
            say("Turn on Bluetooth, then open Whapea again")
            return
        }
        running = true
        status = "Ready. Waiting for people nearby"
        listenForever(a)
        registerScanReceiver()
        refreshPaired()
        scan()
        onUi()
    }

    fun stop() {
        running = false
        scanning = false
        try { adapter?.cancelDiscovery() } catch (e: Exception) { }
        try { server?.close() } catch (e: Exception) { }
        synchronized(conns) {
            for (c in conns.values) try { c.socket.close() } catch (e: Exception) { }
            conns.clear()
        }
        found.clear()
        status = "Stopped"
        onUi()
    }

    private fun listenForever(a: BluetoothAdapter) {
        io.execute {
            while (running) {
                try {
                    val ss = a.listenUsingInsecureRfcommWithServiceRecord("Whapea", APP_UUID)
                    server = ss
                    while (running) {
                        val s = ss.accept()
                        io.execute { setupSocket(s) }
                    }
                } catch (e: Exception) {
                    if (!running) break
                    try { Thread.sleep(1500) } catch (ie: InterruptedException) { break }
                }
            }
        }
    }

    // ---------- finding people ----------
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (d != null && found.none { it.address == d.address }) {
                        found.add(Found(d.address, d.name ?: d.address, d.bondState == BluetoothDevice.BOND_BONDED))
                        onUi()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    onUi()
                }
            }
        }
    }

    private fun registerScanReceiver() {
        if (receiverOn) return
        val f = IntentFilter()
        f.addAction(BluetoothDevice.ACTION_FOUND)
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        ContextCompat.registerReceiver(app, scanReceiver, f, ContextCompat.RECEIVER_EXPORTED)
        receiverOn = true
    }

    private fun refreshPaired() {
        val a = adapter ?: return
        try {
            for (d in a.bondedDevices) {
                if (found.none { it.address == d.address }) {
                    found.add(Found(d.address, d.name ?: d.address, true))
                }
            }
        } catch (e: SecurityException) {
            status = "Allow the Nearby devices permission"
        }
    }

    fun scan() {
        val a = adapter ?: return
        if (!running) return
        try {
            a.cancelDiscovery()
            refreshPaired()
            scanning = a.startDiscovery()
        } catch (e: SecurityException) {
            status = "Allow the Nearby devices permission"
        }
        onUi()
    }

    // ---------- connecting ----------
    fun connect(address: String) {
        val a = adapter ?: return
        val label = found.firstOrNull { it.address == address }?.name ?: address
        say("Connecting to $label...")
        io.execute {
            try {
                a.cancelDiscovery()
                val s = a.getRemoteDevice(address).createInsecureRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                setupSocket(s)
            } catch (e: Exception) {
                main.post { say("Could not connect. Is Whapea open on $label?") }
            }
        }
    }

    /** Runs on a background thread for the whole life of one connection. */
    private fun setupSocket(s: BluetoothSocket) {
        val addr = s.remoteDevice.address
        try {
            val out = DataOutputStream(s.outputStream)
            val inp = DataInputStream(s.inputStream)
            synchronized(conns) {
                conns.remove(addr)?.let { try { it.socket.close() } catch (e: Exception) { } }
                conns[addr] = Conn(s, out)
            }
            main.post {
                status = "Connected"
                onLink(addr, true)
                onUi()
            }
            while (true) {
                val n = inp.readInt()
                if (n <= 0 || n > MAX_FRAME) break
                val b = ByteArray(n)
                inp.readFully(b)
                if (b[0] == 1.toByte()) { CallAudio.onFrame(addr, b); continue }
                val raw = String(b, Charsets.UTF_8)
                main.post { dispatch(addr, raw) }
            }
        } catch (e: Exception) {
            // connection closed
        } finally {
            val last = synchronized(conns) {
                if (conns[addr]?.socket === s) { conns.remove(addr); true } else false
            }
            try { s.close() } catch (e: Exception) { }
            if (last) main.post { onLink(addr, false) }
        }
    }

    fun send(addr: String, json: String) {
        writer.execute {
            try {
                val c = synchronized(conns) { conns[addr] } ?: return@execute
                val b = json.toByteArray(Charsets.UTF_8)
                synchronized(c.out) {
                    c.out.writeInt(b.size)
                    c.out.write(b)
                    c.out.flush()
                }
            } catch (e: Exception) {
                // the reader thread notices a broken connection
            }
        }
    }

    /** Sends raw bytes (live voice) right away, without waiting behind messages. */
    fun sendFrame(addr: String, b: ByteArray) {
        try {
            val c = synchronized(conns) { conns[addr] } ?: return
            synchronized(c.out) {
                c.out.writeInt(b.size)
                c.out.write(b)
                c.out.flush()
            }
        } catch (e: Exception) { }
    }

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

    /** The owner key is never stored here, only a one-way fingerprint of it. */
    fun checkOwnerKey(key: String): Boolean {
        val d = MessageDigest.getInstance("SHA-256").digest(("whapea-owner:" + key.trim()).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) } == OWNER_HASH
    }
}
