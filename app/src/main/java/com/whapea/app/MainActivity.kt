package com.whapea.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var wantStart = false

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            fileCb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            fileCb = null
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                askAndStart()
            } else {
                Core.say("Allow the Bluetooth (Nearby devices) permission so Whapea can work")
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pushUi()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = callback
                val t = params?.acceptTypes?.firstOrNull { it.isNotBlank() } ?: "*/*"
                pickFile.launch(if (t.contains(",")) "*/*" else t)
                return true
            }
        }
        Core.onUi = { pushUi() }
        Core.onLink = { a, up -> js("nativeLink(${q(a)},$up)") }
        Core.onData = { a, raw -> js("nativeData(${q(a)},${q(raw)})") }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("nativeBack()") { r -> if (r != "true") finish() }
            }
        })
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        if (wantStart && !Core.running && hasPermissions() && Core.btOn()) Core.start()
    }

    // ---------- helpers ----------
    private fun q(s: String): String =
        JSONObject.quote(s).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun js(code: String) {
        web.post { web.evaluateJavascript(code, null) }
    }

    private fun pushUi() {
        js("nativeUi(${q(Core.uiJson())})")
    }

    private fun neededPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return list
    }

    private fun hasPermissions(): Boolean = neededPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun askAndStart() {
        wantStart = true
        val missing = neededPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (!Core.btOn()) {
            Core.say("Turn on Bluetooth")
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        Core.start()
    }

    private fun makeVisible() {
        if (!hasPermissions() || !Core.btOn()) {
            askAndStart()
            return
        }
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        startActivity(i)
    }

    /** Functions the screen (index.html) can call. */
    inner class Bridge {
        @JavascriptInterface
        fun load(): String = Core.loadState()

        @JavascriptInterface
        fun save(s: String) {
            Core.saveState(s)
        }

        @JavascriptInterface
        fun start() {
            runOnUiThread { askAndStart() }
        }

        @JavascriptInterface
        fun scan() {
            runOnUiThread { if (Core.running) Core.scan() else askAndStart() }
        }

        @JavascriptInterface
        fun visible() {
            runOnUiThread { makeVisible() }
        }

        @JavascriptInterface
        fun stopBt() {
            runOnUiThread {
                wantStart = false
                Core.stop()
            }
        }

        @JavascriptInterface
        fun connect(addr: String) {
            runOnUiThread { Core.connect(addr) }
        }

        @JavascriptInterface
        fun send(addr: String, json: String) {
            Core.send(addr, json)
        }

        @JavascriptInterface
        fun fetchFeed() {
            runOnUiThread {
                Core.fetchFeed { t ->
                    if (t != null) js("nativeFeed(${q(t)})") else js("nativeFeedFail()")
                }
            }
        }

        @JavascriptInterface
        fun checkKey(key: String): Boolean = Core.checkOwnerKey(key)

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Whapea", text))
            }
        }
    }
}
