package com.whapea.app

import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.KeyFactory
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The same end-to-end scheme the screen uses (ECDH P-256, HKDF-SHA256, AES-GCM),
 * so the phone can read and answer a message while the screen is closed.
 * The keys are copied here from the screen and stay inside the app's private storage.
 */
object E2e {
    private val curve: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }
    private val info = "vynk-e2e-2".toByteArray()
    private val rnd = SecureRandom()
    private val peerCache = HashMap<String, Pair<String, Long>>()

    private fun u(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
    private fun big(s: String) = BigInteger(1, u(s))

    private fun priv(j: JSONObject): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(big(j.getString("d")), curve))

    private fun pub(x: String, y: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(big(x), big(y)), curve))

    private fun pubOf(s: String): PublicKey { val a = s.split("."); return pub(a[0], a[1]) }

    private fun ecdh(p: PrivateKey, q: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(p); ka.doPhase(q, true)
        return ka.generateSecret()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data)
    }

    private fun mix(a: ByteArray, b: ByteArray?): ByteArray {
        val ikm = if (b == null) a else a + b
        val prk = hmac(ByteArray(32), ikm)
        return hmac(prk, info + byteArrayOf(1))
    }

    fun save(json: String) {
        try { JSONObject(json); Core.prefs.edit().putString("e2keys", json).apply() } catch (e: Exception) { }
    }

    fun mine(): JSONObject? = try { Core.prefs.getString("e2keys", null)?.let { JSONObject(it) } } catch (e: Exception) { null }

    fun ready(): Boolean { val m = mine(); return m != null && m.optJSONObject("pr") != null && m.optString("uid").isNotBlank() }

    /** Public key of another person (x.y), from the server, kept for a few minutes. */
    fun peerPub(uid: String, token: String): String? {
        val c = peerCache[uid]
        if (c != null && System.currentTimeMillis() - c.second < 300000) return c.first
        return try {
            val (code, txt) = NetWatch.call("/rest/v1/profiles?select=pubkey&id=eq.$uid", token = token)
            if (code != 200) return null
            val a = org.json.JSONArray(txt)
            val k = a.optJSONObject(0)?.optString("pubkey") ?: ""
            if (k.isBlank() || !k.contains(".")) null else { peerCache[uid] = k to System.currentTimeMillis(); k }
        } catch (e: Exception) { null }
    }

    /** Opens a frame sent by [sender]. Returns the readable text, or null if it cannot be opened here. */
    fun decrypt(sender: String, raw: String, peerKey: String): String? {
        return try {
            val m = mine() ?: return null
            val me = m.getString("uid")
            val o = JSONObject(raw)
            val e = o.optInt("e")
            if (e != 1 && e != 2) return null
            val stat = ecdh(priv(m.getJSONObject("pr")), pubOf(peerKey))
            val key: ByteArray = if (e == 1) stat else {
                var dh2: ByteArray? = null
                if (o.has("p")) {
                    val pj = m.optJSONObject("pp")?.optJSONObject(o.get("p").toString()) ?: return null
                    val ek = o.getString("k").split(".")
                    dh2 = ecdh(priv(pj), pub(ek[0], ek[1]))
                }
                mix(stat, dh2)
            }
            val cph = Cipher.getInstance("AES/GCM/NoPadding")
            cph.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(o.getString("i"))))
            cph.updateAAD("$sender>$me".toByteArray())
            String(cph.doFinal(Base64.getDecoder().decode(o.getString("c"))), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    /** Seals [plain] for [peer]. Same envelope as the screen makes (without a one-time key). */
    fun encrypt(peer: String, peerKey: String, plain: String): String? {
        return try {
            val m = mine() ?: return null
            val me = m.getString("uid")
            val key = mix(ecdh(priv(m.getJSONObject("pr")), pubOf(peerKey)), null)
            val iv = ByteArray(12).also { rnd.nextBytes(it) }
            val cph = Cipher.getInstance("AES/GCM/NoPadding")
            cph.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cph.updateAAD("$me>$peer".toByteArray())
            val ct = cph.doFinal(plain.toByteArray(Charsets.UTF_8))
            JSONObject().put("e", 2).put("i", Base64.getEncoder().encodeToString(iv)).put("c", Base64.getEncoder().encodeToString(ct)).toString()
        } catch (e: Exception) { null }
    }
}
