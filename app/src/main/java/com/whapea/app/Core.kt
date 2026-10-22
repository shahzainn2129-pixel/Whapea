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

    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("whapea", Context.MODE_PRIVATE)
        adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        ready = true
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
                val raw = String(b, Charsets.UTF_8)
                main.post { onData(addr, raw) }
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
                c.out.writeInt(b.size)
                c.out.write(b)
                c.out.flush()
            } catch (e: Exception) {
                // the reader thread notices a broken connection
            }
        }
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
