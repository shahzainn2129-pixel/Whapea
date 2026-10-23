package com.whapea.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/** Records a voice message to a small AAC file and returns it as text. */
object VoiceNote {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var t0 = 0L

    @Suppress("DEPRECATION")
    fun start(ctx: Context): Boolean {
        cancel()
        return try {
            val f = File(ctx.cacheDir, "note.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(24000)
            r.setAudioChannels(1)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r; file = f; t0 = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            cancel()
            false
        }
    }

    /** Returns {"src":"data:audio/mp4;base64,...","dur":seconds} or null. */
    fun stop(): String? {
        val r = rec ?: return null
        val f = file
        rec = null
        return try {
            r.stop()
            r.release()
            val secs = ((System.currentTimeMillis() - t0) / 1000).toInt().coerceAtLeast(1)
            val b64 = Base64.encodeToString(f!!.readBytes(), Base64.NO_WRAP)
            f.delete()
            JSONObject().put("src", "data:audio/mp4;base64,$b64").put("dur", secs).toString()
        } catch (e: Exception) {
            try { r.release() } catch (x: Exception) { }
            f?.delete()
            null
        }
    }

    fun cancel() {
        try { rec?.stop() } catch (e: Exception) { }
        try { rec?.release() } catch (e: Exception) { }
        rec = null
        file?.delete()
    }
}

/**
 * Live voice during a call. Sound is 16 kHz, squeezed to one byte per sample
 * (mu-law), so it needs only about 16 KB per second over Bluetooth.
 */
@SuppressLint("MissingPermission")
object CallAudio {
    private const val RATE = 16000
    private const val FRAME = 640 // 40 ms

    @Volatile var active = false
    @Volatile var muted = false
    var peer = ""
        private set

    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var am: AudioManager? = null
    private val queue = LinkedBlockingQueue<ByteArray>()

    fun start(ctx: Context, addr: String): Boolean {
        if (active) stop()
        return try {
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am = audio
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val inMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(inMin, FRAME * 8)
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release(); audio.mode = AudioManager.MODE_NORMAL
                return false
            }
            val outMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(maxOf(outMin, FRAME * 2 * 6))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            rec = r; track = t; peer = addr
            queue.clear(); muted = false; active = true
            r.startRecording(); t.play()
            Thread { capture(r, addr) }.start()
            Thread { playback(t) }.start()
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    private fun capture(r: AudioRecord, addr: String) {
        val pcm = ShortArray(FRAME)
        while (active) {
            var got = 0
            while (got < FRAME && active) {
                val n = r.read(pcm, got, FRAME - got)
                if (n <= 0) break
                got += n
            }
            if (got < FRAME || !active) continue
            if (muted) continue
            val out = ByteArray(FRAME + 1)
            out[0] = 1
            for (i in 0 until FRAME) out[i + 1] = enc(pcm[i].toInt())
            Core.sendFrame(addr, out)
        }
    }

    private fun playback(t: AudioTrack) {
        val pcm = ShortArray(FRAME)
        while (active) {
            val f = try { queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null } ?: continue
            val n = minOf(FRAME, f.size - 1)
            for (i in 0 until n) pcm[i] = dec(f[i + 1])
            try { t.write(pcm, 0, n) } catch (e: Exception) { }
        }
    }

    /** A frame of the other person's voice arrived. */
    fun onFrame(addr: String, b: ByteArray) {
        if (!active || addr != peer) return
        if (queue.size > 12) queue.poll() // keep the delay small
        queue.offer(b)
    }

    fun speaker(on: Boolean) {
        try { am?.isSpeakerphoneOn = on } catch (e: Exception) { }
    }

    fun stop() {
        active = false
        try { rec?.stop() } catch (e: Exception) { }
        try { rec?.release() } catch (e: Exception) { }
        try { track?.stop() } catch (e: Exception) { }
        try { track?.release() } catch (e: Exception) { }
        rec = null; track = null
        queue.clear()
        try { am?.isSpeakerphoneOn = false; am?.mode = AudioManager.MODE_NORMAL } catch (e: Exception) { }
        peer = ""
    }

    // mu-law
    private fun enc(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) { s = -s; 0x80 } else 0
        if (s > 32635) s = 32635
        s += 0x84
        var exp = 7
        var mask = 0x4000
        while (exp > 0 && (s and mask) == 0) { exp--; mask = mask shr 1 }
        val man = (s shr (exp + 3)) and 0x0F
        return (sign or (exp shl 4) or man).inv().toByte()
    }

    private fun dec(b: Byte): Short {
        val u = b.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exp = (u shr 4) and 0x07
        val man = u and 0x0F
        val s = (((man shl 3) + 0x84) shl exp) - 0x84
        return (if (sign != 0) -s else s).toShort()
    }
}
