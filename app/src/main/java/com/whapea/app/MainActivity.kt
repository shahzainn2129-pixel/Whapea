package com.whapea.app

import android.Manifest
import android.annotation.SuppressLint
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
    private var pageReady = false
    private var askedNotif = false

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            fileCb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            fileCb = null
        }

    private val camLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            js("nativeCam(${ok})")
        }

    private fun hasCam(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private val locLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            js("nativeLoc(${r.values.any { it }})")
        }

    private fun hasLoc(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            js("nativeMic(${ok})")
        }

    private var pendingApk: java.io.File? = null

    private fun openInstaller(f: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.whapea.app.files", f)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                askAndStart()
            } else {
                Core.say("Permission needed")
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
        web.settings.textZoom = 100
        web.settings.allowFileAccess = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pendingAuth?.let { u -> pendingAuth = null; js("window.nativeAuth&&nativeAuth(${q(u)})") }
                pushUi()
                if (Core.uiAttached) web.postDelayed({ Core.drainInbox() }, 400)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                runOnUiThread {
                    val r = request ?: return@runOnUiThread
                    if (r.resources.all { (it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE && hasMic()) || (it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE && hasCam()) }) r.grant(r.resources) else r.deny()
                }
            }
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
        val ota = Ota.current(this)
        if (ota != null) web.loadUrl("file://" + ota.absolutePath) else web.loadUrl("file:///android_asset/index.html")
        Ota.check(this)
        handleAuth(intent)
    }

    private var pendingAuth: String? = null

    private fun handleAuth(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "vynk" || d.host != "auth") return
        val u = d.toString()
        if (pageReady) js("window.nativeAuth&&nativeAuth(${q(u)})") else pendingAuth = u
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuth(intent)
    }

    override fun onPause() {
        super.onPause()
        Core.uiAttached = false
        js("window.appPause&&appPause()")
    }

    override fun onResume() {
        super.onResume()
        Core.uiAttached = true
        js("window.appResume&&appResume()")
        Core.ringStop()
        if (NetWatch.session() != null) try { androidx.core.app.NotificationManagerCompat.from(this).cancelAll() } catch (e: SecurityException) { }
        if (pageReady) Core.drainInbox()
        pendingApk?.let { if (packageManager.canRequestPackageInstalls()) { pendingApk = null; openInstaller(it) } }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotif = true
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
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

    private fun neededPermissions(): List<String> = emptyList()

    private fun hasPermissions(): Boolean = true

    private fun askAndStart() {
        wantStart = true
        val missing = neededPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        Core.auto = true
        askBatteryOnce()
        ContextCompat.startForegroundService(this, Intent(this, BtService::class.java))
    }

    /** Asks once to keep Vynk out of battery saving, so Android does not stop it in the background. */
    private fun askBatteryOnce() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val sp = getSharedPreferences("whapea", Context.MODE_PRIVATE)
            if (!pm.isIgnoringBatteryOptimizations(packageName) && !sp.getBoolean("askedBatt", false)) {
                sp.edit().putBoolean("askedBatt", true).apply()
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) { }
    }

    private fun makeVisible() {}

    /** Functions the screen (index.html) can call. */
    inner class Bridge {
        @JavascriptInterface
        fun alertStatus(): String {
            val j = JSONObject()
            try {
                j.put("notif", androidx.core.app.NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled())
                val m = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                j.put("calls", (m.getNotificationChannel(Core.CH_CALL)?.importance ?: 4) >= 3)
                j.put("msgs", (m.getNotificationChannel(Core.CH)?.importance ?: 4) >= 3)
                j.put("fsi", if (Build.VERSION.SDK_INT >= 34) m.canUseFullScreenIntent() else true)
                j.put("overlay", android.provider.Settings.canDrawOverlays(this@MainActivity))
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                j.put("battery", pm.isIgnoringBatteryOptimizations(packageName))
                j.put("maker", Build.MANUFACTURER.lowercase())
            } catch (e: Exception) { }
            return j.toString()
        }

        @JavascriptInterface
        fun openAlertSettings(kind: String) {
            runOnUiThread {
                try {
                    val i = when (kind) {
                        "fsi" -> if (Build.VERSION.SDK_INT >= 34)
                            Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:$packageName"))
                        else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        "overlay" -> Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        "battery" -> Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                        "app" -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        else -> Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(i)
                } catch (e: Exception) {
                    try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e2: Exception) { }
                }
            }
        }

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
                Core.auto = false
                if (NetWatch.session() == null) stopService(Intent(this@MainActivity, BtService::class.java))
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

        /** true when the microphone is allowed; otherwise asks and returns false. */
        @JavascriptInterface
        fun micOk(): Boolean {
            if (hasMic()) return true
            runOnUiThread { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            return false
        }

        @JavascriptInterface
        fun recStart(): Boolean = hasMic() && VoiceNote.start(this@MainActivity)

        @JavascriptInterface
        fun recLevel(): Int = VoiceNote.level()

        @JavascriptInterface
        fun recStop(cancel: Boolean): String {
            if (cancel) { VoiceNote.cancel(); return "" }
            return VoiceNote.stop() ?: ""
        }

        @JavascriptInterface
        fun callStart(addr: String): Boolean {
            if (!hasMic()) return false
            runOnUiThread { volumeControlStream = android.media.AudioManager.STREAM_VOICE_CALL }
            return CallAudio.start(this@MainActivity, addr)
        }

        @JavascriptInterface
        fun callStop() {
            CallAudio.stop()
            runOnUiThread { volumeControlStream = android.media.AudioManager.USE_DEFAULT_STREAM_TYPE }
        }

        @JavascriptInterface
        fun callMute(on: Boolean) { CallAudio.muted = on }

        @JavascriptInterface
        fun callSpeaker(on: Boolean) { CallAudio.speaker(this@MainActivity, on) }

        @JavascriptInterface
        fun saveImage(name: String, dataUrl: String): Boolean {
            return try {
                val m = Regex("^data:(image/[a-z+]+);base64,").find(dataUrl) ?: return false
                val mime = m.groupValues[1]
                val bytes = android.util.Base64.decode(dataUrl.substring(m.value.length), android.util.Base64.DEFAULT)
                val ext = if (mime.contains("png")) "png" else if (mime.contains("webp")) "webp" else "jpg"
                val fn = name.replace(Regex("[^A-Za-z0-9_-]"), "") + "." + ext
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val cv = android.content.ContentValues()
                    cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fn)
                    cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                    cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Vynk")
                    val uri = this@MainActivity.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
                    val os = this@MainActivity.contentResolver.openOutputStream(uri) ?: return false
                    os.use { it.write(bytes) }
                    true
                } else {
                    val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Vynk")
                    dir.mkdirs()
                    java.io.File(dir, fn).writeBytes(bytes)
                    true
                }
            } catch (e: Exception) { false }
        }

        @JavascriptInterface
        fun downloadVideo(url: String, name: String): Boolean {
            return try {
                if (!url.startsWith("https://bmcsvldqgvuahztwkune.supabase.co/storage/")) return false
                val ext = Regex("\\.(mp4|webm|3gp|mov)").find(url.substringBefore("#"))?.groupValues?.get(1) ?: "mp4"
                val fn = name.replace(Regex("[^A-Za-z0-9_-]"), "") + "." + ext
                val dm = this@MainActivity.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                val rq = android.app.DownloadManager.Request(android.net.Uri.parse(url.substringBefore("#")))
                    .setTitle(fn)
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_MOVIES, "Vynk/" + fn)
                dm.enqueue(rq)
                true
            } catch (e: Exception) { false }
        }

        @JavascriptInterface
        fun ringStart() { Core.ringStart() }

        @JavascriptInterface
        fun ringStop() { Core.ringStop() }

        @JavascriptInterface
        fun buildNo(): Int = BuildConfig.BUILD_N

        @JavascriptInterface
        fun otaOk() { Ota.confirm(this@MainActivity) }

        @JavascriptInterface
        fun otaVer(): Int = Ota.version(this@MainActivity)

        @JavascriptInterface
        fun fileSave(id: String, dataUrl: String, ext: String): String = Core.fileSave(id, dataUrl, ext)

        @JavascriptInterface
        fun fileRead(uri: String): String = Core.fileRead(uri)

        @JavascriptInterface
        fun fileExport(uri: String, name: String, mime: String): Boolean = Core.fileExport(uri, name, mime)

        @JavascriptInterface
        fun locOk(): Boolean {
            if (hasLoc()) return true
            runOnUiThread { locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
            return false
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun locGet() {
            runOnUiThread {
                try {
                    if (!hasLoc()) { js("nativeLocRes(null)"); return@runOnUiThread }
                    val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                    val provs = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
                        .filter { try { lm.isProviderEnabled(it) } catch (e: Exception) { false } }
                    if (provs.isEmpty()) { js("nativeLocRes(null)"); return@runOnUiThread }
                    var best: android.location.Location? = null
                    for (p in provs) {
                        val l = lm.getLastKnownLocation(p)
                        if (l != null && (best == null || l.accuracy < best!!.accuracy)) best = l
                    }
                    var done = false
                    val finish = { l: android.location.Location? ->
                        if (!done) {
                            done = true
                            val x = l ?: best
                            if (x == null) js("nativeLocRes(null)")
                            else js("nativeLocRes({lat:${x.latitude},lng:${x.longitude},acc:${Math.round(x.accuracy)}})")
                        }
                    }
                    if (best != null && System.currentTimeMillis() - best!!.time < 60000) { finish(best); return@runOnUiThread }
                    web.postDelayed({ finish(null) }, 12000)
                    if (Build.VERSION.SDK_INT >= 30) {
                        lm.getCurrentLocation(provs[0], null, mainExecutor) { l -> finish(l) }
                    } else {
                        lm.requestSingleUpdate(provs[0], object : android.location.LocationListener {
                            override fun onLocationChanged(l: android.location.Location) { finish(l) }
                            override fun onStatusChanged(p: String?, st: Int, e: Bundle?) { }
                            override fun onProviderEnabled(p: String) { }
                            override fun onProviderDisabled(p: String) { }
                        }, android.os.Looper.getMainLooper())
                    }
                } catch (e: Exception) { js("nativeLocRes(null)") }
            }
        }

        @JavascriptInterface
        fun checkUpdate() {
            runOnUiThread {
                Core.checkUpdate { t -> if (t != null) js("nativeUpdate(${q(t)})") else js("nativeUpdate('')") }
            }
        }

        @JavascriptInterface
        fun installUpdate(url: String) {
            Core.io.execute {
                val f = Core.downloadApk(url) { p -> js("nativeUpdProg($p)") }
                runOnUiThread {
                    if (f == null) { js("nativeUpdProg(-1)"); return@runOnUiThread }
                    if (!packageManager.canRequestPackageInstalls()) {
                        js("nativeUpdProg(-2)")
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")))
                        pendingApk = f
                        return@runOnUiThread
                    }
                    openInstaller(f)
                }
            }
        }

        /** The internet account's sign-in, so the background service can watch for messages. */
        @JavascriptInterface
        fun e2eSync(s: String) { E2e.save(s) }

        @JavascriptInterface
        fun mutedSync(s: String) { Notif.setMuted(s) }

        @JavascriptInterface
        fun takeActions(): String = Notif.take()

        @JavascriptInterface
        fun netSession(s: String) {
            NetWatch.setSession(s)
            if (s.isNotBlank()) {
                runOnUiThread { askBatteryOnce() }
                try { ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, BtService::class.java)) } catch (e: Exception) { }
                NetWatch.start()
                NetWatch.registerPush()
            }
        }

        @JavascriptInterface
        fun mediaSave(id: String, dataUrl: String): String = Core.mediaSave(id, dataUrl)

        @JavascriptInterface
        fun mediaRead(uri: String): String = Core.mediaRead(uri)

        @JavascriptInterface
        fun mediaDel(uri: String) { Core.mediaDel(uri) }

        @JavascriptInterface
        fun netGet(): String = NetWatch.session()?.toString() ?: ""

        @JavascriptInterface
        fun openUrl(u: String) {
            try {
                val x = Uri.parse(u)
                if (x.scheme == "http" || x.scheme == "https") runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, x)) }
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun bioOk(): Boolean {
            if (Build.VERSION.SDK_INT < 29) return false
            return try {
                val bm = getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                if (Build.VERSION.SDK_INT >= 30)
                    bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK) == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
                else bm.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
            } catch (e: Exception) { false }
        }

        @JavascriptInterface
        fun bioAuth() {
            runOnUiThread {
                try {
                    val exec = ContextCompat.getMainExecutor(this@MainActivity)
                    val cb = object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(r: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) { js("bioDone(true)") }
                        override fun onAuthenticationError(code: Int, msg: CharSequence?) { js("bioDone(false)") }
                    }
                    val p = android.hardware.biometrics.BiometricPrompt.Builder(this@MainActivity)
                        .setTitle("Unlock Vynk")
                        .setNegativeButton("Use code", exec) { _, _ -> js("bioDone(false)") }
                        .build()
                    p.authenticate(android.os.CancellationSignal(), exec, cb)
                } catch (e: Exception) { js("bioDone(false)") }
            }
        }

        @JavascriptInterface
        fun secure(on: Boolean) {
            runOnUiThread {
                val f = android.view.WindowManager.LayoutParams.FLAG_SECURE
                if (on) window.setFlags(f, f) else window.clearFlags(f)
            }
        }

        @JavascriptInterface
        fun camOk(): Boolean {
            if (hasCam()) return true
            runOnUiThread { camLauncher.launch(Manifest.permission.CAMERA) }
            return false
        }

        @JavascriptInterface
        fun rtcMode(on: Boolean) {
            CallAudio.mode(this@MainActivity, on)
            runOnUiThread {
                volumeControlStream = if (on) android.media.AudioManager.STREAM_VOICE_CALL else android.media.AudioManager.USE_DEFAULT_STREAM_TYPE
            }
        }

        @JavascriptInterface
        fun checkKey(key: String): Boolean = Core.checkOwnerKey(key)

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Vynk", text))
            }
        }
    }
}
