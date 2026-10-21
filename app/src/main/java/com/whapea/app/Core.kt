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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/** status: 0 = waiting (peer not in range), 1 = sent, 2 = delivered. peer = Bluetooth address of the other phone. */
data class Msg(
    val id: String,
    val peer: String,
    val mine: Boolean,
    val text: String,
    val ts: Long,
    val status: Int
)

data class Found(val address: String, val name: String, val paired: Boolean)

/**
 * All Bluetooth work lives here. It uses plain Android Bluetooth (RFCOMM sockets),
 * so it does not need Google Play services and works on any Android phone.
 */
@SuppressLint("MissingPermission")
object Core {
    private val APP_UUID: UUID = UUID.fromString("8f2b6c1e-5d3a-4c7e-9b1a-0a6c3e5f7d21")

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

    var myName by mutableStateOf("")
    var running by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    var statusText by mutableStateOf("Not started")
    var openPeer by mutableStateOf<String?>(null)

    val found = mutableStateListOf<Found>()
    val connected = mutableStateMapOf<String, String>() // address -> name
    val names = mutableStateMapOf<String, String>()     // address -> last known name
    val messages = mutableStateListOf<Msg>()
    val unread = mutableStateMapOf<String, Int>()

    fun nameOf(address: String): String = connected[address] ?: names[address] ?: address

    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("whapea", Context.MODE_PRIVATE)
        adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        myName = prefs.getString("name", "") ?: ""
        load()
        ready = true
    }

    fun btOn(): Boolean = adapter?.isEnabled == true

    fun setName(n: String) {
        myName = n.trim()
        prefs.edit().putString("name", myName).apply()
    }

    // ---------- saving ----------
    private fun save() {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(
                JSONObject().put("id", m.id).put("peer", m.peer).put("mine", m.mine)
                    .put("text", m.text).put("ts", m.ts).put("st", m.status)
            )
        }
        val nm = JSONObject()
        for ((k, v) in names) nm.put(k, v)
        prefs.edit().putString("msgs", arr.toString()).putString("names", nm.toString()).apply()
    }

    private fun load() {
        try {
            val arr = JSONArray(prefs.getString("msgs", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                messages.add(
                    Msg(
                        o.getString("id"), o.getString("peer"), o.getBoolean("mine"),
                        o.getString("text"), o.getLong("ts"), o.getInt("st")
                    )
                )
            }
            val nm = JSONObject(prefs.getString("names", "{}"))
            for (k in nm.keys()) names[k] = nm.getString(k)
        } catch (e: Exception) {
            // start empty if saved data is unreadable
        }
    }

    // ---------- start / stop ----------
    fun start() {
        if (running || myName.isBlank()) return
        val a = adapter
        if (a == null) {
            statusText = "This phone has no Bluetooth"
            return
        }
        if (!a.isEnabled) {
            statusText = "Turn on Bluetooth, then open Whapea again"
            return
        }
        running = true
        statusText = "Ready. Waiting for people nearby"
        listenForever(a)
        registerScanReceiver()
        refreshPaired()
        scan()
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
        connected.clear()
        found.clear()
        statusText = "Stopped"
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
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
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
            statusText = "Allow Nearby devices permission"
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
            statusText = "Allow Nearby devices permission"
        }
    }

    // ---------- connecting ----------
    fun connect(f: Found) {
        val a = adapter ?: return
        statusText = "Connecting to ${f.name}..."
        io.execute {
            try {
                a.cancelDiscovery()
                val s = a.getRemoteDevice(f.address).createInsecureRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                setupSocket(s)
            } catch (e: Exception) {
                main.post { statusText = "Could not connect. Is Whapea open on ${f.name}?" }
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
            send(addr, JSONObject().put("t", "hi").put("name", myName))
            while (true) {
                val j = JSONObject(inp.readUTF())
                main.post { handle(addr, j) }
            }
        } catch (e: Exception) {
            // connection closed
        } finally {
            synchronized(conns) { if (conns[addr]?.socket === s) conns.remove(addr) }
            try { s.close() } catch (e: Exception) { }
            main.post {
                val n = connected.remove(addr)
                if (n != null) statusText = "$n went out of range"
            }
        }
    }

    private fun send(addr: String, j: JSONObject) {
        writer.execute {
            try {
                val c = synchronized(conns) { conns[addr] } ?: return@execute
                c.out.writeUTF(j.toString())
                c.out.flush()
            } catch (e: Exception) {
                // the reader thread will notice a broken connection
            }
        }
    }

    // ---------- messages ----------
    private fun handle(addr: String, j: JSONObject) {
        when (j.optString("t")) {
            "hi" -> {
                val n = j.optString("name", addr).ifBlank { addr }
                names[addr] = n
                connected[addr] = n
                statusText = "Connected to $n"
                save()
                flushQueue(addr)
            }
            "msg" -> {
                val id = j.getString("id")
                if (messages.none { it.id == id }) {
                    messages.add(Msg(id, addr, false, j.getString("text"), System.currentTimeMillis(), 2))
                    if (openPeer != addr) unread[addr] = (unread[addr] ?: 0) + 1
                    save()
                }
                send(addr, JSONObject().put("t", "ack").put("id", id))
            }
            "ack" -> {
                val id = j.getString("id")
                val i = messages.indexOfFirst { it.id == id }
                if (i >= 0) {
                    messages[i] = messages[i].copy(status = 2)
                    save()
                }
            }
        }
    }

    fun sendText(peer: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val id = UUID.randomUUID().toString()
        val online = connected.containsKey(peer)
        if (online) send(peer, JSONObject().put("t", "msg").put("id", id).put("text", t))
        messages.add(Msg(id, peer, true, t, System.currentTimeMillis(), if (online) 1 else 0))
        save()
    }

    private fun flushQueue(peer: String) {
        for (i in messages.indices) {
            val m = messages[i]
            if (m.mine && m.peer == peer && m.status == 0) {
                send(peer, JSONObject().put("t", "msg").put("id", m.id).put("text", m.text))
                messages[i] = m.copy(status = 1)
            }
        }
        save()
    }

    fun clearChat(peer: String) {
        messages.removeAll { it.peer == peer }
        save()
    }
}
