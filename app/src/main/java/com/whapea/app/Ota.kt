package com.whapea.app

import android.content.Context
import java.io.File
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/**
 * Over-the-air updates for the app screen (index.html).
 * A downloaded page is used only if its signature matches the key built into this app,
 * and the built-in page is always the fallback.
 */
object Ota {
    // Public half of the update signing key. Pages signed by any other key are rejected.
    private const val PUB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEirB6DqUheA3nxIt6loea3uz88CA/UssSVVi+NenUXi5HTCbk2QYMzmS0386gs3J33dq969noXg8BVVDttguR5g=="
    private const val BASE = "https://github.com/shahzainn2129-pixel/Whapea/releases/latest/download/"

    private fun dir(c: Context) = File(c.filesDir, "ota").also { it.mkdirs() }
    private fun prefs(c: Context) = c.getSharedPreferences("ota", Context.MODE_PRIVATE)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun verify(msg: String, sig: String): Boolean = try {
        if (PUB.startsWith("__")) false else {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(android.util.Base64.decode(PUB, android.util.Base64.DEFAULT)))
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(key)
            s.update(msg.toByteArray(Charsets.UTF_8))
            s.verify(android.util.Base64.decode(sig, android.util.Base64.DEFAULT))
        }
    } catch (e: Exception) { false }

    /** The downloaded page to show, or null to use the built-in one. */
    fun current(c: Context): File? {
        val p = prefs(c)
        val v = p.getInt("ver", 0)
        val f = File(dir(c), "index.html")
        if (v <= 0 || !f.exists()) return null
        if (v <= BuildConfig.BUILD_N) { drop(c); return null }
        if (p.getInt("try", 0) == v) { p.edit().putInt("bad", v).apply(); drop(c); return null }
        return try {
            if (sha(f.readBytes()) != p.getString("sha", "")) { drop(c); null }
            else { p.edit().putInt("try", v).apply(); f }
        } catch (e: Exception) { drop(c); null }
    }

    private fun drop(c: Context) {
        prefs(c).edit().putInt("ver", 0).putInt("try", 0).apply()
        try { File(dir(c), "index.html").delete() } catch (e: Exception) { }
    }

    /** Called by the page once it has started properly. */
    fun confirm(c: Context) { prefs(c).edit().putInt("try", 0).apply() }

    fun version(c: Context): Int = if (prefs(c).getInt("try", 0) > 0) prefs(c).getInt("ver", 0) else 0

    private fun get(u: String, max: Int): ByteArray? {
        val c = URL(u).openConnection() as HttpsURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        return try {
            if (c.responseCode != 200) null
            else c.inputStream.use { it.readBytes() }.takeIf { it.size <= max }
        } finally { c.disconnect() }
    }

    /** Looks for a newer signed page and stores it for the next start. */
    fun check(c: Context) {
        Core.io.execute {
            try {
                val p = prefs(c)
                val m = JSONObject(String(get(BASE + "ota-manifest.json", 4000) ?: return@execute, Charsets.UTF_8))
                val v = m.optInt("v", 0)
                val min = m.optInt("min", 0)
                val h = m.optString("sha")
                if (v <= BuildConfig.BUILD_N || v <= p.getInt("ver", 0) || v <= p.getInt("bad", 0) || min > BuildConfig.BUILD_N) return@execute
                if (!verify("$v:$min:$h", m.optString("sig"))) return@execute
                val body = get(BASE + "ota-index.html", 4_000_000) ?: return@execute
                if (sha(body) != h) return@execute
                val tmp = File(dir(c), "index.new")
                tmp.writeBytes(body)
                val f = File(dir(c), "index.html")
                f.delete()
                if (!tmp.renameTo(f)) return@execute
                p.edit().putInt("ver", v).putString("sha", h).putInt("try", 0).apply()
            } catch (e: Exception) { }
        }
    }
}
