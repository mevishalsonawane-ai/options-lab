package com.optionslab.app.ira

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.optionslab.ira.VoicePrint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * "Only Boss's voice can trade" (Jarvis; the owner's wish, 2026-10-02). The owner teaches Jarvis their voice once
 * (five short phrases, on the Ira screen); from then on a spoken yes to a trade, a spoken order, or a spoken command
 * that adds risk is done at once only when the voice that said it matches ([VoicePrint]); otherwise a command waits for
 * a spoken yes and the riskiest are refused. Before the voice is taught, or on a phone that cannot
 * share the audio with its on-device recognizer (Android 13+ is needed), voice can ask but not trade - Approve on the
 * pop-up or the Ira screen still works. The audio is never stored: only the print's numbers, encrypted.
 */
object VoiceGuard {
    private const val KEY = "jarvis.voiceprint"
    /** The phrases asked for while teaching (any words work; these cover the voice's range). */
    val PHRASES = listOf("Jarvis, yes, place the trade.", "Jarvis, how is Nifty doing today?", "No, reject that one.",
        "Jarvis, stop all strategies.", "BankNifty, FinNifty, Sensex and gold.")

    data class Teach(val step: Int = 0, val busy: Boolean = false, val message: String? = null)
    private val _teach = MutableStateFlow(Teach())
    val teach: StateFlow<Teach> = _teach
    private val samples = ArrayList<DoubleArray>()

    @Volatile private var cached: VoicePrint.Print? = null
    private fun print(): VoicePrint.Print? = cached ?: runCatching {
        com.optionslab.app.security.SecurePrefs.getString(KEY)?.let { VoicePrint.Print.load(it) } }.getOrNull()?.also { cached = it }

    val enrolled: Boolean get() = print() != null
    /** The phone can hand the recognizer our own audio (so the same words are both understood and checked). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun forget() {
        cached = null
        runCatching { com.optionslab.app.security.SecurePrefs.put(KEY, null) }
        synchronized(samples) { samples.clear() }
        _teach.value = Teach(message = "Your voice print is deleted: voice can ask, not trade, until you teach me again.")
    }

    /** Does [pcm] (16 kHz mono, what the recognizer just heard) sound like the owner? */
    fun isBoss(pcm: ShortArray?): Boolean {
        val p = print() ?: return false
        return pcm != null && VoicePrint.matches(p, VoicePrint.features(pcm))
    }

    /** Why voice may not trade now (null: it may, when the voice matches). */
    fun blocked(): String? = when {
        !supported -> "Boss, this phone can't let me check your voice (Android 13 or later is needed), so voice can't trade here. Tap Approve instead."
        !enrolled -> "Boss, teach me your voice first on the Ira screen: until then voice can ask, not trade. Tap Approve instead."
        else -> null
    }

    /** Records the next teaching phrase (about 3 seconds); after the fifth, the print is made and kept. */
    suspend fun teachNext(context: Context) {
        if (_teach.value.busy) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _teach.value = _teach.value.copy(message = "Jarvis needs the microphone permission: switch listening on once to grant it."); return
        }
        _teach.value = _teach.value.copy(busy = true, message = "Listening... say: \"${PHRASES[_teach.value.step % PHRASES.size]}\"")
        JarvisVoice.hold(true)
        try {
            val pcm = withContext(Dispatchers.IO) { record(3_200) }
            val f = pcm?.let { VoicePrint.features(it) }
            if (f == null) { _teach.value = _teach.value.copy(busy = false, message = "I didn't hear enough voice: try that phrase again, a little closer."); return }
            val n = synchronized(samples) { samples += f; samples.size }
            if (n < VoicePrint.SAMPLES_NEEDED) { _teach.value = Teach(step = n, message = "Got it ($n of ${VoicePrint.SAMPLES_NEEDED}). Next phrase."); return }
            val p = synchronized(samples) { VoicePrint.enroll(samples.toList()).also { samples.clear() } }
            if (p == null) { _teach.value = Teach(message = "That did not work: start again."); return }
            runCatching { com.optionslab.app.security.SecurePrefs.put(KEY, p.save()) }
            cached = p
            _teach.value = Teach(message = "Done, Boss: only your voice can trade by voice now.")
        } finally {
            if (_teach.value.busy) _teach.value = _teach.value.copy(busy = false)
            JarvisVoice.hold(false)
        }
    }

    /**
     * The phone's noise suppression and gain control on one capture (Boss, 4 Oct: background noise heard, a soft voice
     * missed). Used on the teaching recordings and the shared capture alike, so the voice print is compared like with
     * like. Released with the capture; a phone without them keeps the plain capture. [echo]: also the phone's echo
     * canceller (the shared capture only, so Jarvis's own voice is taken out while he talks and Boss cuts in -
     * [com.optionslab.ira.CutIn]); with nothing playing it leaves the voice as it is.
     */
    private class Clean(session: Int, echo: Boolean = false) {
        private val ns = runCatching { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(session)?.also { it.setEnabled(true) } else null }.getOrNull()
        private val agc = runCatching { if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(session)?.also { it.setEnabled(true) } else null }.getOrNull()
        private val aec = if (!echo) null else runCatching { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(session)?.also { it.setEnabled(true) } else null }.getOrNull()
        /** The echo canceller is on for this capture. */
        val echoOn: Boolean get() = runCatching { aec?.enabled == true }.getOrDefault(false)
        fun release() { runCatching { ns?.release() }; runCatching { agc?.release() }; runCatching { aec?.release() } }
    }

    @SuppressLint("MissingPermission")
    private fun record(ms: Int): ShortArray? {
        val min = AudioRecord.getMinBufferSize(VoicePrint.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val r = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, VoicePrint.RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, VoicePrint.RATE)) }.getOrNull() ?: return null
        var clean: Clean? = null
        return try {
            if (r.state != AudioRecord.STATE_INITIALIZED) return null
            clean = Clean(r.audioSessionId)
            val out = ShortArray(VoicePrint.RATE * ms / 1000)
            r.startRecording()
            var got = 0
            while (got < out.size) { val n = r.read(out, got, minOf(1_600, out.size - got)); if (n <= 0) break; got += n }
            out.copyOf(got)
        } finally { runCatching { r.stop() }; clean?.release(); r.release() }
    }

    /**
     * The microphone shared with the on-device recognizer (Android 13+): our capture feeds the recognizer through a
     * pipe and keeps the last [KEEP_S] seconds, so the words understood are the words checked. One per listening turn.
     */
    class Tap @SuppressLint("MissingPermission") constructor() {
        val read: android.os.ParcelFileDescriptor
        private val write: android.os.ParcelFileDescriptor
        private val rec: AudioRecord
        private var clean: Clean? = null
        private val buf = ShortArray(VoicePrint.RATE * KEEP_S)
        private var filled = 0
        private var at = 0
        @Volatile private var running = true
        private val thread: Thread

        init {
            val p = android.os.ParcelFileDescriptor.createPipe()
            read = p[0]; write = p[1]
            val min = AudioRecord.getMinBufferSize(VoicePrint.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            var r: AudioRecord? = null
            try {
                r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, VoicePrint.RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, VoicePrint.RATE))
                check(r.state == AudioRecord.STATE_INITIALIZED) { "no microphone" }
                clean = Clean(r.audioSessionId, echo = true)
                r.startRecording()
            } catch (e: Exception) {
                // Nothing may leak when the microphone cannot be had: both ends of the pipe and the recorder go.
                clean?.release(); runCatching { r?.release() }; runCatching { read.close() }; runCatching { write.close() }
                throw e
            }
            rec = r!!
            thread = Thread {
                val chunk = ShortArray(800)
                val bytes = ByteArray(chunk.size * 2)
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    while (running) {
                        val n = rec.read(chunk, 0, chunk.size)
                        if (n <= 0) break
                        synchronized(buf) { for (i in 0 until n) { buf[at] = chunk[i]; at = (at + 1) % buf.size }; filled = minOf(buf.size, filled + n) }
                        for (i in 0 until n) { bytes[2 * i] = (chunk[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (chunk[i].toInt() shr 8).toByte() }
                        try { out.write(bytes, 0, n * 2) } catch (_: java.io.IOException) { break }
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        }

        /** What was heard this turn (up to [KEEP_S] seconds), oldest first. */
        fun heard(): ShortArray = synchronized(buf) { ShortArray(filled) { buf[(at - filled + it + buf.size) % buf.size] } }

        /** The phone's echo canceller is on for this capture (Jarvis's own voice taken out while he talks). */
        val echoCancelled: Boolean get() = clean?.echoOn == true

        fun close() {
            running = false
            runCatching { rec.stop() }; clean?.release(); runCatching { rec.release() }
            runCatching { read.close() }
        }

        companion object { const val KEEP_S = 8 }
    }
}
