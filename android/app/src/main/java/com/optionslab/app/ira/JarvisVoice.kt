package com.optionslab.app.ira

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.optionslab.app.R
import com.optionslab.app.work.Notifier
import com.optionslab.ira.Wake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Jarvis's ears and voice, always on while the owner keeps it switched on. It listens with Android's ON-DEVICE
 * speech recognizer only (Android 12+: the audio never leaves the phone; there is no fallback to an online one), wakes
 * on "Jarvis", answers through [IraHub] like a typed question and speaks with an offline voice of the phone's own
 * text-to-speech. Nothing heard is recorded, logged or kept beyond the question in the conversation. An order asked
 * by voice is only prepared on the Ira screen. The one spoken yes: when Jarvis itself asks about a news trade, the
 * owner's yes or no (approve / reject, positive / negative) within a minute answers it. A microphone foreground
 * service with its own notification (and a Stop button) while it runs; only Jarvis declares it.
 */
class JarvisVoice : Service() {
    enum class Mode { OFF, LISTENING, AWAKE, THINKING, SPEAKING }
    data class VoiceState(val mode: Mode = Mode.OFF, val problem: String? = null,
                          /** What the recognizer last heard (shown in Settings to check the voice; never stored). */ val heard: String? = null)
    /** How Jarvis sounds (see [style]). */
    enum class Style(val label: String, val pitch: Float, val rate: Float) {
        MAN("Man", 0.92f, 1.0f), GIRL("Young girl", 1.6f, 1.08f), WOMAN("Woman", 1.1f, 1.0f), DEEP("Deep", 0.8f, 0.95f)
    }

    companion object {
        const val ACTION_STOP = "com.optionslab.app.ira.JarvisVoice.STOP"
        private const val ID = 1050
        /** How long "Jarvis" alone keeps it awake for the question. */
        private const val AWAKE_MS = 8_000L
        /** After an answer, a question without "Jarvis" is still heard for this long (questions only, never an action). */
        private const val FOLLOW_MS = 30_000L
        /** The running service, to speak a notice (the morning briefing). */
        @Volatile private var instance: java.lang.ref.WeakReference<JarvisVoice>? = null

        /**
         * Speaks [text] if Jarvis is listening now; false when it is not. Unasked ([prompted] false) during quiet hours
         * it is shown as a pop-up instead.
         */
        fun announce(text: String, prompted: Boolean = false): Boolean {
            val v = instance?.get() ?: return false
            // Unasked on a locked phone (it may be overheard): never an amount, a P&L or a symbol - only that it is in
            // the chat (every caller has already put the full line there).
            val said = if (prompted) text else com.optionslab.ira.Overheard.said(text, runCatching { IraHub.locked() }.getOrDefault(true))
            if (!prompted && quietNow()) { runCatching { JarvisPopup.show(v, "Jarvis", said) }; return true }
            v.main.post { v.say(said, "answer") }
            return true
        }

        /**
         * "Why can't I hear you?": everything that stops the voice, said plainly (muted, quiet hours, typed replies not
         * spoken, no offline voice, the phone's media volume at zero), or that all looks right.
         */
        /** The last turns of the ears, for the voice check: when, and what happened (never the words heard). */
        private val trace = java.util.ArrayDeque<String>()
        internal fun note(what: String) = synchronized(trace) {
            trace.addLast("%tT %s".format(java.util.Locale.ENGLISH, System.currentTimeMillis(), what)); while (trace.size > 60) trace.removeFirst()
        }
        /** The last 8 turns (the voice check); [all] for the diagnostics report (the last 60). */
        fun traceLines(all: Boolean = false): List<String> = synchronized(trace) { if (all) trace.toList() else trace.toList().takeLast(8) }

        /** For the diagnostics report: Jarvis's ears in full - settings, state, the voice check and the last 60 turns (never words). */
        fun report(context: Context?): String = buildString {
            append("Listen for Jarvis: $wanted · running: ${instance?.get() != null} · started from the app on screen: ${instance?.get()?.visibleStart}\n")
            append("Ears: ${if (googleSpeech) "Google's speech service" else "on the phone only"} · language: ${instance?.get()?.lang} · voice taught: ${VoiceGuard.enrolled} · only my voice: $onlyBoss · muted: $muted\n")
            append("On-device recognition available: ${context?.let { c -> runCatching { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(c) }.getOrNull() }} · " +
                "any recognizer: ${context?.let { c -> runCatching { SpeechRecognizer.isRecognitionAvailable(c) }.getOrNull() }}\n")
            append("Voice check: ${runCatching { diagnose(context) }.getOrElse { "could not run" }}\n")
            com.optionslab.ira.Latency.say(latencies)?.let { append(it).append('\n') }
            append("Last turns:\n"); traceLines(all = true).forEach { append("  ").append(it).append('\n') }
        }

        /** The speech recognizer's last failure (its code, when) - not silence (for the voice check). */
        @Volatile var lastError: Pair<Int, Long>? = null

        private fun errorName(e: Int): String = when (e) {
            SpeechRecognizer.ERROR_AUDIO -> "the microphone could not be read (another app may be using it)"
            SpeechRecognizer.ERROR_CLIENT -> "the phone's speech service refused the request (I restart it by myself; if it keeps failing, update " +
                "\"Speech Services by Google\" in the Play Store and download English under Settings, System, Languages, On-device speech recognition)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "the phone's speech service is busy (another app may be using it)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "the microphone permission is missing"
            // 11: the speech service crashed or restarted (often just after a language pack is added or updated).
            11 -> "the phone's speech service disconnected (it restarted - often just after a language is added); I reconnect by myself"
            10 -> "the phone's speech service had too many requests; I slow down by myself"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "the speech service wanted the network (code $e)"
            else -> "the speech service failed (code $e)"
        }

        fun diagnose(context: Context?): String {
            val out = ArrayList<String>()
            val v = instance?.get()
            if (wanted && v == null) out += "Listening is switched on but not running: open the Jarvis screen, or switch \"Listen for Jarvis\" off and on."
            // No on-device recognizer on the phone: Jarvis cannot hear at all (he listens on the phone only).
            if (v != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { !SpeechRecognizer.isOnDeviceRecognitionAvailable(v) }.getOrDefault(false))
                out += "This phone has no on-device speech recognition ready: update \"Speech Services by Google\" in the Play Store, then add English under Settings, System, Languages, On-device speech recognition."
            lastError?.takeIf { SystemClock.elapsedRealtime() - it.second < 10 * 60_000 }?.let { (e, at) ->
                out += "My ears failed %d seconds ago: %s.".format(java.util.Locale.ENGLISH, (SystemClock.elapsedRealtime() - at) / 1000, errorName(e))
            }
            if (v != null && !v.visibleStart) out += "I was started in the background, where Android gives me no microphone (it is allowed only while using the app): open the app once and I listen again."
            v?.let { s -> out += "Ears: " + (if (googleSpeech) "Google's speech service" else "on the phone only") + ", listening in ${s.lang}, " + (if (s.tap != null || (VoiceGuard.enrolled && !s.tapFailed)) "my own microphone shared (for your voice check)" else "the phone's own microphone") + "." }
            traceLines().takeIf { it.isNotEmpty() }?.let { out += "Last turns: " + it.joinToString("; ") + "." }
            if (muted) out += "I'm muted: say \"Jarvis, unmute\" or switch Mute off in Settings, Voice and AI model."
            if (quietNow()) out += "It's quiet hours (22:00 to 07:00): I only speak when you ask."
            if (!JarvisSpeaker.speakTyped) out += "Speaking typed replies is off (Settings, Voice and AI model)."
            if (instance?.get()?.voiceReady == false) out += "This phone has no offline English voice ready: add one in Settings, Accessibility, Text-to-speech."
            context?.let { c -> runCatching {
                val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) out += "Your phone's media volume is at zero: turn it up."
            } }
            if (!wanted) out += "Listening is off, so I only speak replies to typed questions (switch on Jarvis voice to talk to me)."
            if (lastLatencyMs > 0) out += "My last spoken reply took %.1f seconds.".format(java.util.Locale.ENGLISH, lastLatencyMs / 1000.0)
            return if (out.isEmpty()) "Boss, my voice looks fine: not muted, volume up, a voice ready. If you still hear nothing, tap Listen under a reply."
                else "Boss, here's why you may not hear me: " + out.joinToString(" ")
        }

        /** Quiet hours: nothing said unasked from 22:00 to 07:00 (on by default; "Jarvis, quiet hours off"). */
        var quietHours: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.quiet", true) }.getOrDefault(true)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.quiet", v) } }

        fun quietNow(): Boolean = quietHours && com.optionslab.ira.Quiet.now(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")))

        /** After Jarvis asks a yes-or-no question (a news trade), the answer is heard for this long without "Jarvis". */
        private const val ANSWER_MS = 60_000L

        /**
         * Says [text] (the news, good or bad, and the trade) and waits for the owner's yes or no to action [id]: yes
         * approves it, no rejects it. False when Jarvis is not listening (the pop-up and the Ira screen still ask).
         */
        fun askYesNo(id: Long, text: String): Boolean {
            val v = instance?.get() ?: return false
            v.main.post { v.asking = id; v.askingNeedsBoss = true; v.askingUntil = 0; v.say(text, "question") }
            return true
        }

        /** Pauses listening while the owner teaches Jarvis their voice (the microphone is needed for that). */
        fun hold(on: Boolean) {
            val v = instance?.get() ?: return
            v.main.post { v.held = on; v.readyAt = SystemClock.elapsedRealtime(); if (on) { runCatching { v.rec?.cancel() }; v.listening = false; v.endTap() } else v.again(300) }
        }

        /** [id] was answered elsewhere (a button, the Ira screen) or lapsed: stop waiting for it. */
        fun answered(id: Long) {
            val v = instance?.get() ?: return
            v.main.post { if (v.asking == id) v.asking = null }
        }

        /** The utterance after which the service stops ("Jarvis, stop listening"). */
        private const val STOP_AFTER = "stop"

        private val _state = MutableStateFlow(VoiceState())
        val state: StateFlow<VoiceState> = _state

        /** The owner's switch, kept on the phone: listening starts again when the app is opened. */
        var wanted: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.listen", false) }.getOrDefault(false)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.listen", v) } }

        /** Can this phone listen on the device alone? (Android 12+ with an on-device recognizer.) */
        fun available(context: Context): Boolean = com.optionslab.app.BuildConfig.JARVIS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || (googleSpeech && SpeechRecognizer.isRecognitionAvailable(context)) }.getOrDefault(false)

        fun permitted(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Boss's choice (default off): Jarvis listens through the phone's speech service (Google), which may send speech to Google. */
        var googleSpeech: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.voice.google", false) }.getOrDefault(false)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.voice.google", v) } }

        /**
         * Boss's choice (default off, Boss 4 Oct: "hear only my voice"): with his voice taught, words in any other voice -
         * the TV, people nearby - are ignored, not only for trades.
         */
        var onlyBoss: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.voice.onlyboss", false) }.getOrDefault(false)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.voice.onlyboss", v) } }

        /** From the app on screen only (Android lets a microphone service start only then). */
        /** Started from the app on screen (Android then lets it use the microphone "while using the app"). */
        private const val EXTRA_VISIBLE = "ol.jarvis.visible"

        fun start(context: Context) {
            if (!available(context) || !permitted(context)) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, JarvisVoice::class.java).putExtra(EXTRA_VISIBLE, true)) }
                .onFailure { _state.value = VoiceState(problem = "Android did not let Jarvis start listening; try the switch again.") }
        }

        /**
         * The app came on screen: listening switched on but not running (Android stopped it while the app stayed in
         * memory, and a microphone service may not restart itself from the background) - started again now.
         */
        fun resume(context: Context) {
            if (!wanted) return
            val v = instance?.get()
            if (v == null) { start(context); return }
            // Running, but started by Android in the background (after an update or a restart): with the microphone
            // allowed "only while using the app", Android gives such a service silence - no error, no sound (Boss,
            // 4 Oct: every turn "error 7, no sound level"). Started again now, from the app on screen.
            if (!v.visibleStart) {
                note("restarted from the app on screen (it had started in the background, without the microphone)")
                stop(context)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (wanted) start(context) }, 700)
            }
        }

        fun stop(context: Context) { context.stopService(Intent(context, JarvisVoice::class.java)) }

        const val ACTION_TALK = "com.optionslab.app.ira.JarvisVoice.TALK"

        /**
         * The mic button: Jarvis says "Yes, Boss?" and takes the next sentence as the question, no "Jarvis" needed.
         * With listening off it listens just for that one question, then stops again. False when it cannot listen.
         */
        fun talk(context: Context): Boolean {
            if (!available(context) || !permitted(context)) return false
            return runCatching { ContextCompat.startForegroundService(context, Intent(context, JarvisVoice::class.java).setAction(ACTION_TALK).putExtra(EXTRA_VISIBLE, true)) }
                .onFailure { _state.value = VoiceState(problem = "Android did not let Jarvis listen; try again with the app open.") }.isSuccess
        }

        /**
         * How Jarvis sounds. Android's voices are adult ones; a young girl's voice is the phone's voice pitched up and a
         * little quicker (the owner's choice, 2026-10-02).
         */
        /** How fast he speaks, times the style's own rate: 0.7 to 1.45 (Boss's "speak slower" / "faster"). */
        var pace: Float
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getString("jarvis.voice.pace")?.toFloat() }.getOrNull()?.coerceIn(0.7f, 1.45f) ?: 1f
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.voice.pace", v.coerceIn(0.7f, 1.45f).toString()) } }

        var style: Style
            // A normal male voice by default (the owner's wish, 2026-10-02); a new key, so an earlier choice starts from it.
            get() = runCatching { Style.valueOf(com.optionslab.app.security.SecurePrefs.getString("jarvis.voice.style2") ?: "MAN") }.getOrDefault(Style.MAN)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.voice.style2", v.name) } }

        /**
         * Cutting in while Jarvis talks: it listens during its own speech (only for "Jarvis"). Off by default: on
         * many phones listening silences the speech. Switched off by itself when the phone does that.
         */
        var cutIn: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.cutin", false) }.getOrDefault(false)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.cutin", v) } }

        /**
         * Muted (the owner's wish, 2026-10-02: "Jarvis, mute"): nothing is spoken - replies, news and questions stay on
         * the screen and in pop-ups - while Jarvis still listens, so "Jarvis, unmute" brings the voice back.
         */
        var muted: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.mute", false) }.getOrDefault(false) ||
                System.currentTimeMillis() < mutedUntil
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.mute", v); if (!v) com.optionslab.app.security.SecurePrefs.put("jarvis.mute.until", null) }
                if (v) { instance?.get()?.hush(); JarvisSpeaker.stop() } }

        /** "Be quiet for 30 minutes": muted until this time (epoch ms), then speaking again by itself. */
        val mutedUntil: Long get() = runCatching { com.optionslab.app.security.SecurePrefs.getString("jarvis.mute.until")?.toLong() }.getOrNull() ?: 0L

        fun muteFor(minutes: Int) {
            // A timed quiet replaces a lasting mute: he speaks again by itself when it ends, as he says.
            runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.mute", false)
                com.optionslab.app.security.SecurePrefs.put("jarvis.mute.until", (System.currentTimeMillis() + minutes.coerceIn(1, 480) * 60_000L).toString()) }
            instance?.get()?.hush(); JarvisSpeaker.stop()
        }

        /** Replies in Hindi (the AI model translates; figures are checked, and English is used when it cannot). */
        var hindi: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.hindi", false) }.getOrDefault(false)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.hindi", v) } }

        /** How long the last spoken reply took, from Boss's last word to Jarvis's first sound (ms), or 0. */
        @Volatile var lastLatencyMs = 0L
        /** This run's answer waits (for the diagnostics). */
        @Volatile var latencies: List<Long> = emptyList()

        /** Jarvis is speaking now. */
        val speakingNow: Boolean get() = instance?.get()?.speaking == true

        /** When the last sound started (elapsed ms): the model starts once the voice is under way. */
        @Volatile var speechStartedAt = 0L

        /** What the recognizer heard last, for the Settings check. */
        @Volatile var heardText: String? = null; private set

        /** What the recognizer heard the time before (redacted): "what did you hear?" asked by voice repeats this. */
        @Volatile var heardBefore: String? = null; private set

        /** Keeps [words] as the last heard (redacted); the name alone ("Jarvis" before "Yes, Boss?") does not push out the words before. */
        private fun remember(words: String) {
            val prev = heardText
            if (prev != null && !Regex("(?i)^\\W*((hey|ok|okay)\\s+)?j[ae]rv[ia]s\\W*$").matches(prev)) heardBefore = prev
            heardText = com.optionslab.ira.Secrets.redact(words)
        }

        /** The phone voice chosen by name, or null for the first offline English one (an Indian English one first). */
        var voiceName: String?
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getString("jarvis.voice.name2") }.getOrNull()
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.voice.name2", v) } }

        /**
         * Android does not say which voices are male, so the known male voices of Google's speech engine come first
         * (Indian English first), then any voice named or marked male; without one, the phone's voice a little lower.
         */
        private val MALE = listOf("en-in-x-end", "en-in-x-ene", "en-us-x-iom", "en-us-x-iol", "en-us-x-tpd", "en-gb-x-rjs", "en-gb-x-gbd", "en-au-x-aud", "en-au-x-aub")
        fun maleVoice(all: List<android.speech.tts.Voice>): android.speech.tts.Voice? =
            MALE.firstNotNullOfOrNull { k -> all.firstOrNull { it.name.lowercase().startsWith(k) } }
                ?: all.firstOrNull { v -> val n = v.name.lowercase(); (Regex("(^|[^e])male").containsMatchIn(n) && "female" !in n) ||
                    v.features.orEmpty().any { it.lowercase() == "male" || it.lowercase().endsWith("gender=male") } }

        /** The phone's English voices that need no network (nothing Jarvis says leaves the phone). */
        fun offlineVoices(t: TextToSpeech): List<android.speech.tts.Voice> = runCatching { t.voices }.getOrNull().orEmpty()
            .filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
            .sortedWith(compareBy({ if (it.locale.country == "IN") 0 else 1 }, { it.name }))

        /** The phone's offline Hindi voice (a male one first), or null when none is installed. */
        fun hindiVoice(t: TextToSpeech): android.speech.tts.Voice? = runCatching { t.voices }.getOrNull().orEmpty()
            .filter { !it.isNetworkConnectionRequired && it.locale.language == "hi" }
            .sortedWith(compareBy({ if (it.name.contains("male", true) && !it.name.contains("female", true)) 0 else 1 }, { it.name })).firstOrNull()

        /**
         * [english] in Hindi when the owner chose Hindi replies and the model is ready (every figure checked), else null.
         */
        suspend fun inHindi(english: String): String? {
            if (!hindi || IraModel.state.value.status != IraModel.Status.READY) return null
            val out = runCatching { IraModel.complete(com.optionslab.ira.Hindi.prompt(english), maxTokens = 200) }.getOrNull()
            return com.optionslab.ira.Hindi.accept(english, out)
        }

        /** What each speech engine was last set to: setting the same voice again is skipped (it can take a moment). */
        private val applied = java.util.WeakHashMap<TextToSpeech, String>()

        /** Sets [t] to the chosen offline voice and style; false when the phone has no offline English voice. */
        fun applyStyle(t: TextToSpeech): Boolean {
            val key = "${style.name}|${voiceName}|${pace}"
            if (synchronized(applied) { applied[t] } == key) return true
            val all = offlineVoices(t)
            val v = all.firstOrNull { it.name == voiceName } ?: (if (style == Style.MAN || style == Style.DEEP) maleVoice(all) else null) ?: all.firstOrNull() ?: return false
            t.voice = v
            t.setPitch(style.pitch); t.setSpeechRate(style.rate * pace)
            synchronized(applied) { applied[t] = key }
            return true
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var voiceReady = false
    private var stopped = false
    private var awakeUntil = 0L
    /** Jarvis is talking; it keeps listening meanwhile, but only for its name (the owner can cut in). */
    @Volatile private var speaking = false
    /** The recognizer is running a turn now (never started twice). */
    private var listening = false
    /** Ends the recognizer's turn early (its results come at once). */
    /** When [finish] last asked the recognizer to close a turn (a "client" error right after it is that, not a failure). */
    @Volatile private var stoppedAt = 0L
    private val finish = Runnable {
        if (!listening || speaking) return@Runnable
        // A plain question whose words have stood still is answered from them now: the recognizer's final reading, which
        // can take a second more after the turn is closed, is not waited for (Boss, 4 Oct: "late response"). Not with
        // shared audio (its voice check needs the turn's end), a yes or no awaited, a turn begun while Jarvis talked, or
        // the strict wake word (it weighs all of the final's readings).
        val now = SystemClock.elapsedRealtime()
        val early = if (tap == null && asking == null && !turnInSpeech && (awake() || !IraTools.wakeStrict))
            com.optionslab.ira.Turn.early(turnPartial, awake(), now - turnPartialAt) else null
        if (early != null) { answerEarly(early); return@Runnable }
        stoppedAt = now; runCatching { rec?.stopListening() }
    }
    /** Words stopped changing this long: the turn ends (a short pause inside a sentence must not cut it). */
    private val END_AFTER_MS = 900L
    /** When the pending [finish] is due (elapsed ms; 0: none this turn), so "speech ended" never puts it off. */
    private var finishAt = 0L
    /** The sentence being spoken now ("id#n"), so a replaced one is ignored. */
    @Volatile private var utterance: String? = null
    private var said = 0
    @Volatile private var stoppedByUs = false
    /** The recognizer's turn began while Jarvis was talking: its words may be Jarvis's own. */
    @Volatile private var turnInSpeech = false
    /** Started by the mic button with listening off: stop after the one question. */
    @Volatile private var oneShot = false
    private var talkAt = 0L
    private var listenedAt = 0L
    private var spokeAt = 0L
    /** Nothing may stick: a recognizer turn that never ends, or speech that never reports its end, is reset. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            val now = SystemClock.elapsedRealtime()
            // A shared-capture turn that the recognizer opened and then never heard anything with (no words, no error) for
            // 12 s: this phone's recognizer cannot take our audio - back to its own microphone, for good.
            if (listening && tap != null && turnReadyAt > 0 && !turnHeardAny && now - turnReadyAt > 12_000 && !speaking) {
                tapFailed = true
                val n = silentShared + 1; silentShared = n
                if (n >= 3) runCatching { com.optionslab.app.security.SecurePrefs.put(TAP_BROKEN, true) }
                note("shared audio gave nothing ($n in a row): back to the phone's own microphone" + if (n >= 3) " for good" else " for now")
                IraActivity.add("Listening switched to the phone's own microphone (the shared one heard nothing).")
                main.removeCallbacks(finish); runCatching { rec?.cancel() }; listening = false; endTap(); turnReadyAt = 0; again(300)
            }
            if (listening && now - listenedAt > 25_000) {
                // (Partial words read before the reset still count, as a lost turn does.)
                val lost = com.optionslab.ira.Wake.lostTurn(7, turnPartial, awake())
                note("turn timed out" + if (turnPartial != null) " (read words mid-turn)" else " (nothing read)")
                main.removeCallbacks(finish); runCatching { rec?.cancel() }; listening = false; endTap()
                if (lost != null && !speaking) { remember(lost); heard(listOf(lost), recovered = true) } else again()
            }
            if (speaking && now - spokeAt > 60_000) { speaking = false; again() }
            // The mic button's one question was asked and answered (or never came): listening stops again.
            if (oneShot && !speaking && !awake() && !lateWaiting && _state.value.mode != Mode.THINKING && now - talkAt > 14_000) { stopSelf(); return }
            if (!listening && !speaking && !held && _state.value.mode != Mode.THINKING) again()
            // Self-healing: no listening turn for 3 minutes (the phone took the microphone, the recognizer died):
            // a new recognizer, noted in the activity log.
            if (Automations.on(Automations.Auto.SELFHEAL) && com.optionslab.ira.VoiceHealth.stuck(now, readyAt, speaking, !held && _state.value.mode != Mode.THINKING) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                readyAt = now
                runCatching { rec?.destroy() }
                rec = runCatching { newRecognizer().also { it.setRecognitionListener(listener) } }.getOrNull()
                listening = false; endTap()
                IraActivity.add("Restarted listening (the microphone had gone quiet).")
                Automations.acted(Automations.Auto.SELFHEAL, "Restarted listening.")
                again()
            }
            main.postDelayed(this, 5_000)
        }
    }
    private val WAKE = Regex("\\bj[ae]rv[ia]s+\\b|\\bjar vis\\b|\\b(jarvish|jarwis|jaarvis|jarviz|jarbis)\\b", RegexOption.IGNORE_CASE)
    @Volatile private var held = false
    /** When the recognizer last opened the microphone (for the self-healing check). */
    @Volatile private var readyAt = SystemClock.elapsedRealtime()
    /** This turn's own capture, shared with the recognizer (Android 13+ with a taught voice), or null. */
    private var tap: VoiceGuard.Tap? = null
    /** The phone's recognizer refused our audio: plain microphone from now on (voice cannot trade then). */
    /**
     * The shared capture does not work on this phone (Boss, 4 Oct: the recognizer said "ready" and then never heard
     * anything with it - silence, no error). Remembered on the phone, so it is not tried again on every restart.
     */
    private var tapFailedNow = false
    private var tapFailed: Boolean
        get() = tapFailedNow || runCatching { com.optionslab.app.security.SecurePrefs.getBoolean(TAP_BROKEN, false) }.getOrDefault(false)
        set(v) { tapFailedNow = v }
    private val TAP_BROKEN = "jarvis.voice.tapbroken"
    /** Shared turns in a row that heard nothing at all: only 3 in a row (not one quiet moment) drop it for good. */
    private var silentShared: Int
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getString("jarvis.voice.tapsilent")?.toInt() }.getOrNull() ?: 0
        set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.voice.tapsilent", v.toString()) } }
    /** When the recognizer said ready this turn, and whether it heard anything (for the silent-turn check). */
    @Volatile private var turnReadyAt = 0L
    @Volatile private var turnHeardAny = false
    /** The loudest sound this turn (the recognizer's dB scale: about -2 silence, 6+ speech), for the trace. */
    @Volatile private var turnLoudest = -100f
    /** The recognizer's latest partial reading this turn (its final answer can drop a lone name as "no match"). */
    @Volatile private var turnPartial: String? = null
    /** When this turn's partial words last changed (a repeated reading is no change). */
    @Volatile private var turnPartialAt = 0L
    /** When the recognizer said "speech ended" this turn (0: not yet), for the reply time from Boss's last word. */
    @Volatile private var turnEndAt = 0L
    /** This turn was answered from its partial words ([answerEarly]): a late result or error from it is not a new turn. */
    @Volatile private var answeredEarly = false
    /** Turns with clear sound in which the recognizer found no words, in a row (the language may be wrong). */
    private var loudNoMatch = 0
    /** The recognizer said "speech began" this turn (a loud room alone is not Boss speaking). */
    @Volatile private var turnSpeech = false
    /** The current language has given words since listening began: it works, so it is never switched away from. */
    private var langWorks = false
    /** What the recognizer heard last turn (for the voice check), then dropped. */
    private var lastHeard: ShortArray? = null
    /** The action Jarvis asked a yes or no about, heard until [askingUntil]. */
    private var asking: Long? = null
    /** A trade needs Boss's own voice for its yes; a command (start, stop...) needs only a yes. */
    private var askingNeedsBoss = true
    private var askingUntil = 0L
    private var lang = "en-US"
    private var triedOtherLanguage = false
    /** This listening was started from the app on screen (so Android allows the microphone). */
    @Volatile private var visibleStart = false
    private var errorsInRow = 0
    /**
     * The longest wait between failed turns. It was 30 s (against the beep, 4 Oct) - which left Jarvis deaf most of the
     * time on a phone whose speech service keeps failing (Boss, 4 Oct: "yesterday morning it was working fine"): 5 s.
     */
    private val MAX_BACKOFF_MS = 5_000L
    /** "The speech service refused the request" in a row: the recognizer is made anew at once each time. */
    private var clientErrors = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { wanted = false; stopSelf(); return START_NOT_STICKY }
        // The mic button with listening off: this one question only.
        val talkNow = intent?.action == ACTION_TALK
        if (talkNow && rec == null) oneShot = !wanted
        if (!talkNow) oneShot = false                    // the switch turned on: listening stays on
        // Restarted by the system after a one-question listen with the switch off: do not listen.
        if (intent == null && !wanted) { stopSelf(); return START_NOT_STICKY }
        // Listening on: the AI model loaded ahead for typing leaves the memory to the speech recognizer.
        if (wanted && !talkNow) runCatching { IraModel.leaveForListening() }
        if (intent?.getBooleanExtra(EXTRA_VISIBLE, false) == true) visibleStart = true
        else if (rec == null) note("started in the background: Android may give no microphone until the app is opened")
        val why = when {
            !com.optionslab.app.BuildConfig.JARVIS -> "Voice is in IraAlgo only."
            !permitted(this) -> "Jarvis needs the microphone permission to listen."
            !available(this) -> "This phone has no on-device speech recognizer (Android 12 or later), so Jarvis does not listen: it never sends your voice off the phone."
            else -> null
        }
        if (why != null) { _state.value = VoiceState(problem = why); stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (e: Exception) {
            _state.value = VoiceState(problem = "Android did not let Jarvis listen in the background; open the app to start it again.")
            stopSelf(); return START_NOT_STICKY
        }
        instance = java.lang.ref.WeakReference(this)
        restoreMuted()
        if (rec == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rec = newRecognizer().also { it.setRecognitionListener(listener) }
            pickLanguage()
            tts = TextToSpeech(this) { status -> main.post { voiceReady = status == TextToSpeech.SUCCESS && pickOfflineVoice() } }
            listen()
            main.postDelayed(watchdog, 5_000)
            // While listening, the slow answers are kept ready so none waits: prices every minute in market hours, your
            // account and the trade check every 30 seconds.
            // (The model is NOT loaded here any more - root cause, 4 Oct: kept in memory the whole time Jarvis listened,
            // 1 GB and more on 4 cores starved the phone's on-device recognizer, which then heard nothing. It loads only
            // when an answer needs it, and leaves memory after 10 minutes unused.)
            scope.launch(Dispatchers.Default) {
                var n = 0
                while (true) {
                    val open = com.optionslab.ira.Market.NIFTY.trading(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")))
                    if (open && n % 2 == 0) runCatching { IraHub.refresh() }
                    // The account and the trade check need the internet: not tried while offline.
                    if ((open || n % 10 == 0) && IraHub.online()) runCatching { IraHub.warm() }
                    n++
                    // Low battery and not charging: kept ready less often (answers then read afresh when asked).
                    kotlinx.coroutines.delay(com.optionslab.app.work.Battery.gap(this@JarvisVoice, 30_000))
                }
            }
        }
        if (talkNow) {
            talkAt = SystemClock.elapsedRealtime()
            // Wait for the voice to be ready (the first time), then ask.
            main.postDelayed({ awakeUntil = SystemClock.elapsedRealtime() + AWAKE_MS + 4_000; called = true; say("Yes, Boss?") }, if (voiceReady) 0L else 800L)
        }
        return if (oneShot) START_NOT_STICKY else START_STICKY
    }

    /** An English voice that needs no network; without one Jarvis answers on screen only. */
    private fun pickOfflineVoice(): Boolean {
        val t = tts ?: return false
        if (!applyStyle(t)) return false
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                speechStartedAt = SystemClock.elapsedRealtime()
                val h = heardAt
                // Only a reply to what was just heard counts (an answer or a yes-or-no question within a minute): a later
                // announcement is not a reply, and a stale time would read as minutes.
                val took = SystemClock.elapsedRealtime() - h
                if (h > 0 && (id?.startsWith("answer") == true || id?.startsWith("question") == true)) { if (took < 60_000) lastLatencyMs = took; latencies = com.optionslab.ira.Latency.add(latencies, took); heardAt = 0L
                    slowNudge() }
            }
            override fun onDone(id: String?) { main.post { if (id == utterance) afterSpeech(id?.substringBefore('#')) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { if (id == utterance) afterSpeech(id?.substringBefore('#')) } }
            override fun onStop(id: String?, interrupted: Boolean) {
                main.post {
                    if (id != utterance) return@post                     // replaced by a newer sentence: nothing to do
                    if (stoppedByUs) { stoppedByUs = false; return@post }  // Boss cut in
                    // The speech was cut off by something else - listening at the same time, on this phone.
                    if (turnInSpeech) {
                        cutIn = false
                        _state.value = VoiceState(Mode.LISTENING, problem = "Cutting in is off: this phone stops speaking while it listens.")
                    }
                    afterSpeech(id?.substringBefore('#'))
                }
            }
        })
        return true
    }

    private fun notification() = NotificationCompat.Builder(this, Notifier.VOICE)
        .setSmallIcon(R.drawable.ic_notification_art)
        .setContentTitle("Jarvis is listening")
        .setContentText("Say \"Jarvis\" and your question. What it hears stays on this phone.")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setContentIntent(Notifier.openApp(this, "almanac"))
        .addAction(0, "Talk", talkIntent())
        .addAction(0, "Stop", PendingIntent.getService(this, 1, Intent(this, JarvisVoice::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        // On the lock screen: only "Jarvis" and a Talk button (nothing private); while locked Jarvis answers
        // questions but trades and changes nothing.
        .setPublicVersion(NotificationCompat.Builder(this, Notifier.VOICE)
            .setSmallIcon(R.drawable.ic_notification_art).setContentTitle("Jarvis").setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).addAction(0, "Talk", talkIntent()).build())
        .build()

    private fun talkIntent() = PendingIntent.getService(this, 2, Intent(this, JarvisVoice::class.java).setAction(ACTION_TALK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun locked(): Boolean = runCatching { (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked }.getOrDefault(false)

    private fun awake() = SystemClock.elapsedRealtime() < awakeUntil

    // ---- the listening beep (Google's speech service plays one at each turn's start and end; Boss, 4 Oct) ----------
    /** Streams muted for a beep, to put back exactly (only those not muted before). */
    private val mutedForBeep = HashSet<Int>()
    private val unmuteBeep = Runnable { unmuteNow() }

    /**
     * For [ms]: the media sound muted - only with Google's service, never while Jarvis speaks or music plays. Never the
     * system stream (it is the ringer on most phones). A mute already on is never extended (turns failing fast must not
     * keep the phone silent), and what is muted is written down, so a crash cannot leave it muted ([restoreMuted]).
     */
    private fun hushBeep(ms: Long) {
        if (!googleSpeech || speaking || mutedForBeep.isNotEmpty()) return
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        val st = android.media.AudioManager.STREAM_MUSIC
        if (am.isMusicActive || runCatching { am.isStreamMute(st) }.getOrDefault(true)) return
        if (!runCatching { am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_MUTE, 0); true }.getOrDefault(false)) return
        if (runCatching { am.isStreamMute(st) }.getOrDefault(false)) {
            mutedForBeep += st
            runCatching { com.optionslab.app.security.SecurePrefs.put(MUTED_KEY, st.toString()) }
        }
        main.removeCallbacks(unmuteBeep); main.postDelayed(unmuteBeep, ms)
    }

    /** A mute this service set and could not put back (it was killed): put back when it starts again. */
    private fun restoreMuted() {
        val st = runCatching { com.optionslab.app.security.SecurePrefs.getString(MUTED_KEY)?.toInt() }.getOrNull() ?: return
        runCatching { getSystemService(android.media.AudioManager::class.java)?.adjustStreamVolume(st, android.media.AudioManager.ADJUST_UNMUTE, 0) }
        runCatching { com.optionslab.app.security.SecurePrefs.put(MUTED_KEY, null) }
    }
    private val MUTED_KEY = "jarvis.voice.mutedstream"

    /** Puts back only what [hushBeep] muted (before Jarvis speaks, and when listening stops). */
    private fun unmuteNow() {
        main.removeCallbacks(unmuteBeep)
        if (mutedForBeep.isEmpty()) return
        val am = getSystemService(android.media.AudioManager::class.java)
        for (st in mutedForBeep) runCatching { am?.adjustStreamVolume(st, android.media.AudioManager.ADJUST_UNMUTE, 0) }
        mutedForBeep.clear()
        runCatching { com.optionslab.app.security.SecurePrefs.put(MUTED_KEY, null) }
    }

    /**
     * Jarvis's ears: the phone's on-device recognizer (nothing leaves the phone), or - when Boss switched on "Use
     * Google's speech service" (4 Oct: the keyboard's voice typing hears him fast; the on-device one did not) - the
     * phone's speech service, which may send his speech to Google.
     */
    private fun newRecognizer(): SpeechRecognizer =
        if (googleSpeech && SpeechRecognizer.isRecognitionAvailable(this)) SpeechRecognizer.createSpeechRecognizer(this)
        else SpeechRecognizer.createOnDeviceSpeechRecognizer(this)

    /**
     * The English the phone's on-device recognizer actually has (Android 13+ can say): English (US) when installed,
     * else English (India), else any English - so Jarvis never asks for a language the phone lacks.
     */
    private fun pickLanguage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val r = rec ?: return
        runCatching {
            r.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), ContextCompat.getMainExecutor(this),
                object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(s: android.speech.RecognitionSupport) {
                        val have = s.installedOnDeviceLanguages.map { it.lowercase(java.util.Locale.ROOT).replace('_', '-') }
                        // English (US) first: it is what heard Boss when it worked (4 Oct); English (India) when it is the only one.
                        val pick = listOf("en-us", "en-in").firstOrNull { it in have } ?: have.firstOrNull { it.startsWith("en") }
                        pick?.let { p -> main.post { lang = if (p.length == 5) p.substring(0, 3) + p.substring(3).uppercase(java.util.Locale.ROOT) else p } }
                    }
                    override fun onError(error: Int) {}
                })
        }
    }

    private fun listen() {
        if (stopped || held || listening) return
        lastHeard = null                                 // a voice check only ever uses this turn's own audio
        turnInSpeech = speaking
        listenedAt = SystemClock.elapsedRealtime()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !googleSpeech)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // End the turn soon after Boss stops speaking (recognizers that honour it answer sooner).
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        // With a taught voice (Android 13+): our own capture feeds the recognizer, so the words are also voice-checked.
        endTap()
        if (VoiceGuard.supported && VoiceGuard.enrolled && !tapFailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tap = runCatching { VoiceGuard.Tap() }.getOrNull()
            if (tap == null) tapFailed = true                // the microphone could not be shared: do not retry each turn
            tap?.let { t ->
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, t.read)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, com.optionslab.ira.VoicePrint.RATE)
            }
        }
        listening = true
        turnReadyAt = 0; turnHeardAny = false; turnLoudest = -100f; turnPartial = null; turnPartialAt = 0L; turnEndAt = 0L; turnSpeech = false; answeredEarly = false; finishAt = 0L
        hushBeep(1_500)                                   // the start beep (put back once the turn is ready, or in 1.5 s)
        runCatching { rec?.startListening(i) }.onFailure { listening = false; endTap(); again(1_000) }
        _state.value = VoiceState(if (awake()) Mode.AWAKE else Mode.LISTENING)
    }

    private fun again(delayMs: Long = 250) { if (!stopped) main.postDelayed({ listen() }, delayMs) }

    /** Ends this turn's capture, keeping what it heard for the voice check. */
    private fun endTap() {
        tap?.let { t -> lastHeard = t.heard(); t.close() }
        tap = null
    }

    /** Was the last thing heard said by the owner? (False without a taught voice or a shared capture.) */
    private fun boss(): Boolean = VoiceGuard.isBoss(lastHeard).also { lastHeard = null }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            errorsInRow = 0; clientErrors = 0; readyAt = SystemClock.elapsedRealtime(); turnReadyAt = readyAt; turnHeardAny = false
            if (mutedForBeep.isNotEmpty()) { main.removeCallbacks(unmuteBeep); main.postDelayed(unmuteBeep, 400) }
            note(if (tap != null) "ready (shared audio)" else "ready")
        }
        override fun onBeginningOfSpeech() { turnSpeech = true; note("speech began") }
        override fun onRmsChanged(rmsdB: Float) { if (rmsdB > turnLoudest) turnLoudest = rmsdB }
        override fun onBufferReceived(buffer: ByteArray?) {}
        // Boss, 4 Oct: "speech began", then nothing - the recognizer never closed the turn. Once he stops speaking, the
        // turn is closed for it 0.7 s later (was 1.5 s: Boss, 4 Oct, "late response") (stopListening makes it give its result), not left to a 25 s reset.
        // A close already due sooner (the words stood still) is kept, never put off (Boss, 5 Oct: speed).
        override fun onEndOfSpeech() {
            note("speech ended")
            val now = SystemClock.elapsedRealtime()
            if (turnEndAt == 0L) turnEndAt = now
            hushBeep(1_200)                               // the end beep
            if (!speaking) {
                val wait = com.optionslab.ira.Turn.closeIn(now, finishAt)
                main.removeCallbacks(finish); finishAt = now + wait; main.postDelayed(finish, wait)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            // Only words that changed restart the end-of-turn wait (a recognizer repeating the same reading pushed it back).
            var fresh = false
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }?.let {
                fresh = com.optionslab.ira.Turn.changed(turnPartial, it)
                if (fresh) turnPartialAt = SystemClock.elapsedRealtime()
                turnHeardAny = true; turnPartial = it
                if (tap != null && silentShared != 0) silentShared = 0      // the shared capture does hear
            }
            // The owner says "Jarvis" while Jarvis is talking: stop at once and listen (the question follows).
            val words = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (speaking) { if (words.any { WAKE.containsMatchIn(it) }) interrupt(); return }
            // Boss is speaking to Jarvis: once the words stop changing for a moment, end the turn now instead of
            // waiting for the recognizer's own long silence.
            val first = words.firstOrNull()?.trim().orEmpty()
            if (first.isNotEmpty() && fresh && (WAKE.containsMatchIn(first) || awake() || asking != null)) {
                main.removeCallbacks(finish)
                // Only the name so far ("Jarvis..." and a breath before the question): never cut there, or the question
                // is lost and only "Yes, Boss?" is said. The recognizer's own silence ends that turn.
                val nameOnly = asking == null && com.optionslab.ira.Wake.heard(first, awake()) is com.optionslab.ira.Wake.Heard.Awake
                // Only the name: still closed, a little later - a lone "Jarvis" left to the recognizer's own silence often
                // ended as "no match" and was lost (the mic button's turns are always closed, which is why they worked).
                val wait = if (nameOnly) 1_800L else END_AFTER_MS
                finishAt = SystemClock.elapsedRealtime() + wait
                main.postDelayed(finish, wait)
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            main.removeCallbacks(finish)
            if (answeredEarly) { answeredEarly = false; return }      // already answered from its partial words
            listening = false
            loudNoMatch = 0
            if (results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.any { it.isNotBlank() } == true) langWorks = true
            note("heard " + (results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { t ->
                if (WAKE.containsMatchIn(t)) "the name" else "${t.split(Regex("\\s+")).size} words, no name" } ?: "nothing"))
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { remember(it) }
            errorsInRow = 0
            endTap()
            heard(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
        }

        override fun onError(error: Int) {
            main.removeCallbacks(finish)
            if (answeredEarly) { answeredEarly = false; return }      // the cancelled turn already answered: not a failure
            listening = false
            // Boss, 4 Oct: "Jarvis" alone was caught while he spoke, then the final answer said "no match" (error 7) and
            // the name was lost - every turn. The name read mid-turn counts: as if the recognizer had said it.
            val partial = com.optionslab.ira.Wake.lostTurn(error, turnPartial, awake())
            if (partial != null && !stopped) {
                note("kept the words read mid-turn (final: error $error)")
                errorsInRow = 0; loudNoMatch = 0
                endTap()
                remember(partial)
                heard(listOf(partial), recovered = true)
                return
            }
            val shared = tap != null
            endTap(); lastHeard = null
            if (stopped) return
            // The recognizer would not take our audio: back to its own microphone (voice can ask, not trade).
            // (11, the speech service disconnecting, too: some phones' recognizers crash on our shared audio - Boss, 4 Oct.)
            if (shared && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_AUDIO || error == 11 ||
                    (Build.VERSION.SDK_INT >= 33 && error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT))) {
                tapFailed = true; note("error $error with shared audio: back to the phone's own microphone")
                if (error == 11 || error == SpeechRecognizer.ERROR_CLIENT) {
                    runCatching { rec?.destroy() }
                    rec = runCatching { newRecognizer().also { it.setRecognitionListener(this) } }.getOrNull()
                }
                again(500); return
            }
            // The loudest sound says whether the microphone gave the recognizer anything (Boss, 4 Oct: error 7 each turn).
            note("error $error" + (if (turnLoudest > -100f) ", loudest %.0f dB".format(java.util.Locale.ENGLISH, turnLoudest) else ", no sound level") +
                (turnPartial?.let { ", read ${it.trim().split(Regex("\\s+")).size} word(s) mid-turn" } ?: ", read nothing"))
            // Clear speech, no words found, three turns in a row: the language pack may be the trouble - the other English.
            // Only turns where speech began, and never away from a language that has given words (Boss, 4 Oct: a quiet
            // room at 6-8 dB switched a working en-US to the missing en-IN, and listening stopped).
            if (error == SpeechRecognizer.ERROR_NO_MATCH && turnSpeech && turnLoudest >= 6f && !langWorks) {
                if (++loudNoMatch >= 3) {
                    loudNoMatch = 0
                    lang = if (lang == "en-IN") "en-US" else "en-IN"
                    note("words not found in clear speech 3 times: now listening in $lang")
                    IraActivity.add("Listening switched to $lang (no words found in clear speech).")
                }
            } else if (error != SpeechRecognizer.ERROR_NO_MATCH) loudNoMatch = 0
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> giveUp("Jarvis lost the microphone permission.")
                // Boss, 4 Oct ("why can't you hear me" -> "the speech service refused the request"): the on-device
                // recognizer is left in a bad state - it is made anew at once (not after 3 failures) and given a second
                // before the next turn; when the phone has no on-device recognition at all, he says what to install.
                // 11: the speech service crashed or restarted - the recognizer is dead; made anew at once (Boss, 4 Oct).
                11 -> {
                    errorsInRow++; lastError = error to SystemClock.elapsedRealtime()
                    runCatching { rec?.destroy() }
                    rec = runCatching { newRecognizer().also { it.setRecognitionListener(this) } }.getOrNull()
                    IraActivity.add("Reconnected listening (the phone's speech service had restarted).")
                    again(minOf(MAX_BACKOFF_MS, 1_000L shl minOf(errorsInRow - 1, 5)))
                }
                SpeechRecognizer.ERROR_CLIENT -> run client@{
                    // Our own close racing the results (the turn was already over): not a failure - just listen again.
                    if (SystemClock.elapsedRealtime() - stoppedAt < 3_000) { again(); return@client }
                    clientErrors++; errorsInRow++; lastError = error to SystemClock.elapsedRealtime()
                    // Boss's phone (4 Oct) has English (US) and Hindi on-device, not English (India): some recognizers
                    // refuse a missing language this way rather than "language unavailable". The other English first.
                    // Never away from a language that has given words; a refused en-IN goes back to English (US).
                    if (!triedOtherLanguage && !langWorks) {
                        triedOtherLanguage = true; lang = if (lang == "en-IN") "en-US" else "en-IN"
                        IraActivity.add("Listening in $lang (the speech service refused the other English).")
                    } else if (lang == "en-IN" && !langWorks) {
                        lang = "en-US"; IraActivity.add("Listening in $lang (the speech service refused English (India)).")
                    }
                    if (clientErrors >= 6 && runCatching { !SpeechRecognizer.isOnDeviceRecognitionAvailable(this@JarvisVoice) }.getOrDefault(false)) {
                        giveUp("This phone has no on-device speech recognition ready: update \"Speech Services by Google\" in the Play Store, then add English under Settings, System, Languages, On-device speech recognition.")
                        return
                    }
                    runCatching { rec?.destroy() }
                    rec = runCatching { newRecognizer().also { it.setRecognitionListener(this) } }.getOrNull()
                    if (clientErrors == 1) IraActivity.add("Restarted listening (the speech service refused a request).")
                    again(if (clientErrors <= 3) 1_000L else minOf(MAX_BACKOFF_MS, 1_000L shl minOf(clientErrors - 3, 5)))
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    // The other English is missing on this phone: back to English (US) at once, never deaf for it.
                    if (lang != "en-US") { lang = "en-US"; triedOtherLanguage = true; note("$lang back: the other English is not on this phone"); again() }
                    else if (!triedOtherLanguage) { triedOtherLanguage = true; lang = "en-IN"; again() }
                    else giveUp("No on-device English speech model: add one in the phone's Settings (System → Languages → On-device speech recognition).")
                else -> {
                    // Silence and no-match are normal between sentences; a run of other errors backs off up to 5 s.
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { errorsInRow++; lastError = error to SystemClock.elapsedRealtime() }
                    // A recognizer stuck failing (busy, client, server errors) beeped on every retry, once a second, until
                    // listening was switched off and on (Boss, 4 Oct): after 3 failures in a row it is made anew - what the
                    // switch did - and further failures wait longer, up to 30 s.
                    if (errorsInRow == 3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching { rec?.destroy() }
                        rec = runCatching { newRecognizer().also { it.setRecognitionListener(this) } }.getOrNull()
                        IraActivity.add("Restarted listening (the speech recognizer kept failing).")
                    }
                    again(if (errorsInRow == 0) 200L else minOf(MAX_BACKOFF_MS, 250L shl minOf(errorsInRow, 7)))
                }
            }
        }
    }

    /**
     * The turn's still partial words answered now, as if they were the recognizer's final reading (a plain question
     * only, [com.optionslab.ira.Turn.early]): the turn is cancelled, and [heard] takes the words with every usual rule.
     */
    private fun answerEarly(words: String) {
        main.removeCallbacks(finish)
        answeredEarly = true
        stoppedAt = SystemClock.elapsedRealtime()
        runCatching { rec?.cancel() }
        listening = false
        loudNoMatch = 0; errorsInRow = 0; langWorks = true
        note("heard " + (if (WAKE.containsMatchIn(words)) "the name" else "${words.trim().split(Regex("\\s+")).size} words, no name") +
            " (answered from the partial words; the final reading not waited for)")
        remember(words)
        endTap()
        heard(listOf(words))
    }

    /** Slow answers three times running, not on the fastest model: a suggestion in the chat, once a day. */
    private fun slowNudge() {
        // Not while a late answer is awaited ("still working on it" is said as an answer too - review, 4 Oct).
        if (lateWaiting) return
        val text = com.optionslab.ira.Latency.suggestFaster(latencies, IraModel.choice == IraModel.FASTEST) ?: return
        val day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString()
        if (runCatching { com.optionslab.app.security.SecurePrefs.getString("jarvis.slow.told") }.getOrNull() == day) return
        runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.slow.told", day) }
        runCatching { IraHub.note(text) }
    }

    /** Cut in on: Jarvis stops talking and waits for the owner's question. */
    private fun interrupt() {
        if (!speaking) return
        speaking = false
        // The utterance is forgotten: its onStop is then not ours to judge, and a reply still being prepared (Hindi) is not said.
        utterance = null
        runCatching { tts?.stop() }
        awakeUntil = SystemClock.elapsedRealtime() + AWAKE_MS
        _state.value = VoiceState(Mode.AWAKE)
    }

    /** When Boss's last words were heard (for the reply time). */
    @Volatile private var heardAt = 0L

    private fun heard(alternatives: List<String>, recovered: Boolean = false) {
        // While Jarvis talks (or the turn began while it talked) it hears itself too: only its name counts then.
        val cutIn = speaking || turnInSpeech
        // Just woken ("Yes, Boss?" said, now finished): the question may have started over those two words - it is
        // Boss's, unless it is only Jarvis's own "yes boss" heard back.
        // (Only after the wake prompt: a turn that began during an ANSWER is its own words heard back - the loop that
        // repeated one answer - and needs the name, as any cut-in does.)
        val afterWake = !speaking && turnInSpeech && awake() && lastSpokenId == "say" &&
            alternatives.firstOrNull()?.lowercase()?.replace(Regex("[^a-z ]"), " ")?.trim()?.let { it.isNotEmpty() && !Regex("^(yes )?boss( yes boss)?$").matches(it.replace(Regex("\\s+"), " ")) } == true
        if (afterWake) turnInSpeech = false
        else if (speaking || turnInSpeech) {
            turnInSpeech = false
            if (alternatives.none { WAKE.containsMatchIn(it) }) { again(); return }
            interrupt()
        }
        // The answer to Jarvis's yes-or-no question: only the recognizer's best reading, and anything unclear is not a yes.
        val id = asking
        if (id != null && SystemClock.elapsedRealtime() < askingUntil) {
            val yes = alternatives.firstOrNull()?.let { Wake.yesNo(it) }
            if (yes != null) {
                // A locked phone: a "no" still cancels, a "yes" never acts (trades and commands wait for the unlock).
                if (yes && locked()) { say(com.optionslab.ira.LockRule.refuse(true, true, false, false)!!, "question"); return }
                // Only Boss's voice approves a trade; a no from anyone is still a no.
                // "Answer only my voice": a yes in another voice never approves anything (a no from anyone still cancels).
                if (yes && onlyBoss && VoiceGuard.enrolled && lastHeard != null && !VoiceGuard.isBoss(lastHeard)) { note("a yes in another voice: ignored"); again(); return }
                if (yes && askingNeedsBoss && !boss()) { say(VoiceGuard.blocked() ?: "Boss, that didn't sound like you, so I won't place it. Say yes again, or tap Approve.", "question"); return }
                asking = null
                _state.value = VoiceState(Mode.THINKING)
                scope.launch {
                    // The emergency exit takes Boss's own voice in place of the fingerprint (checked just above).
                    val r = if (yes) withContext(Dispatchers.Default) { IraHub.confirm(id, ownerVoice = askingNeedsBoss && IraHub.isExit(id)) } ?: "That had already lapsed; nothing was placed."
                        else { IraHub.cancelAction(id); "Rejected. Nothing was placed." }
                    say(com.optionslab.ira.Address.boss(Wake.spoken(r)), "answer")
                }
                return
            }
        }
        val awake = awake()
        // Strict wake word (the owner's setting): only the best reading, with "Jarvis" first, wakes it.
        val alts = if (IraTools.wakeStrict && !awake) com.optionslab.ira.WakeSense.accept(alternatives, com.optionslab.ira.WakeSense.Level.STRICT) else alternatives
        val h = alts.asSequence().map { Wake.heard(it, awake) }.firstOrNull { it !is Wake.Heard.Ignore } ?: Wake.Heard.Ignore
        // Only Boss's voice (his choice): words someone else said are let go. Checked on this turn's own audio; with
        // no shared audio this turn (the phone would not share it) there is nothing to check, and the words count.
        if (onlyBoss && VoiceGuard.enrolled && (h is Wake.Heard.Ask || h is Wake.Heard.Awake || h is Wake.Heard.Stop)) {
            val pcm = lastHeard
            if (pcm != null && !VoiceGuard.isBoss(pcm)) { note("words in another voice: ignored"); again(); return }
            if (pcm == null) note("no shared audio this turn: voice not checked")
        }
        when (h) {
            Wake.Heard.Ignore -> again()
            // A soft misreading of the name ("service") opens the window for a question, but never counts as named.
            Wake.Heard.Awake -> { awakeUntil = SystemClock.elapsedRealtime() + AWAKE_MS; called = alternatives.any { WAKE.containsMatchIn(it) }; say("Yes, Boss?") }
            Wake.Heard.Stop -> if (alternatives.none { WAKE.containsMatchIn(it) }) again() else { wanted = false; say("Going to sleep, Boss. Switch me on again in the app.", STOP_AFTER) }
            // "Jarvis, stop" / "enough" / "quiet": it has stopped talking (the name cut in); nothing else is done.
            Wake.Heard.Hush -> { if (speaking) runCatching { IraTools.noteHush() }; interrupt(); answerJob?.cancel(); awakeUntil = 0; called = false; _state.value = VoiceState(Mode.LISTENING); again() }
            is Wake.Heard.Ask -> {
                // "Jarvis, stop talking" said over Jarvis: it has already stopped; that is not a lasting mute.
                if (cutIn && Regex("^(stop|please stop|ok stop) (talking|speaking)$").matches(h.question.lowercase().trim())) { again(); return }
                // A follow-up (no "Jarvis" in it) may ask, never act: trades and commands need the name, so talk nearby cannot trigger one.
                // Just called ("Jarvis" alone, or the mic button, then "Yes, Boss?"): this question was asked by name.
                // Words recovered from a broken turn count as named only if they hold the name: they may be someone else's.
                val named = alternatives.any { Regex("\\bj[ae]rv[ia]s").containsMatchIn(it.lowercase()) } || (!recovered && called && awake)
                val called0 = called
                called = false
                // Its own last words heard back without the name are not a question (the follow-up window stays open).
                if (!named && Wake.echo(h.question, lastSpoken?.takeIf { SystemClock.elapsedRealtime() - lastSpokenEnd < 15_000 })) { again(); return }
                awakeUntil = 0
                // A command for later ("start all arms tomorrow at 9") is judged as the command itself.
                val laterRest = runCatching { com.optionslab.ira.Later.split(h.question, java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")))?.rest }.getOrNull()
                val parsedQ = com.optionslab.ira.Ask.parse(laterRest ?: h.question)
                val topics = parsedQ.topics
                // Muting, unmuting and the reply language are not actions: a follow-up "mute" works without the name.
                val voiceOnly = parsedQ.command?.kind in VOICE_KINDS
                val acts = !voiceOnly && (com.optionslab.ira.Topic.COMMAND in topics || com.optionslab.ira.Topic.ORDER in topics)
                // Locked phone: questions only; the account needs Boss's own voice.
                // Mute, unmute and the voice check are not actions: they work on a locked phone too.
                val lockedNo = if (locked()) com.optionslab.ira.LockRule.refuse(true, acts || com.optionslab.ira.Topic.COMMAND in topics && !voiceOnly && parsedQ.command?.kind != com.optionslab.ira.Command.Kind.VOICE_CHECK,
                    com.optionslab.ira.Topic.ACCOUNT in topics, com.optionslab.ira.Topic.ACCOUNT in topics && boss()) else null
                if (lockedNo != null) say(lockedNo)
                else if (!named && acts) { IraTools.count("nameFirst"); say(if (recovered && called0) "Boss, I only caught part of that. Say it again with my name." else "Boss, to do that call me first: say my name, or tap the mic.") }
                else {
                    // Trades, and commands that add risk (live mode, kill switch off, autopilot, starting arms), need
                    // Boss's own voice; without it a command is asked as a yes or no instead of done at once, and
                    // the riskiest ones are refused. Stopping, closing and questions need only the name.
                    val cmd = parsedQ.command
                    // Loosening one of the app's limits (more lots, a bigger loss limit, a limit off) is Boss's alone.
                    val loosens = cmd != null && runCatching { IraActions.loosens(cmd) }.getOrDefault(true)
                    val risky = cmd != null && (!cmd.kind.reduces || loosens)
                    val verified = (com.optionslab.ira.Topic.ORDER in topics || risky) && boss()
                    when {
                        com.optionslab.ira.Topic.ORDER in topics && !verified ->
                            say(VoiceGuard.blocked() ?: "Boss, that didn't sound like you, so I won't place it. Say it again, or use the Ira screen.")
                        // A start set for later runs while Boss may be away: only his own voice sets one.
                        risky && !verified && (cmd!!.kind in HIGH_RISK || loosens || laterRest != null) ->
                            say(VoiceGuard.blocked() ?: "Boss, that didn't sound like you, so I won't do it. Use the Ira screen.")
                        else -> answer(h.question, confirm = risky && !verified, named = named)
                    }
                }
            }
        }
    }

    /** Said without the name as a follow-up: unmute and the reply language (never mute: a stray word must not silence Jarvis). */
    private val VOICE_KINDS = setOf(com.optionslab.ira.Command.Kind.UNMUTE, com.optionslab.ira.Command.Kind.VOICE_CHECK,
        com.optionslab.ira.Command.Kind.HINDI, com.optionslab.ira.Command.Kind.ENGLISH,
        com.optionslab.ira.Command.Kind.QUIET_ON, com.optionslab.ira.Command.Kind.QUIET_OFF,
        com.optionslab.ira.Command.Kind.PACE_SLOWER, com.optionslab.ira.Command.Kind.PACE_FASTER, com.optionslab.ira.Command.Kind.PACE_NORMAL)

    /** Commands that are never done on an unrecognised voice, even with a yes. */
    private val HIGH_RISK = setOf(com.optionslab.ira.Command.Kind.MODE_LIVE, com.optionslab.ira.Command.Kind.KILL_OFF,
        com.optionslab.ira.Command.Kind.JTRADES_LIVE, com.optionslab.ira.Command.Kind.AUTOPILOT_ON)

    /** What was said last (its id, and its words for an answer or question) and when that speech ended. */
    /** How long a slow answer (a backtest, the account) is still waited for after Boss was told it is coming. */
    private val LATE_MS = 180_000L

    @Volatile private var lastSpokenId: String? = null
    /** Boss has just called Jarvis (its name alone, or the mic button): the next question in the awake window counts as named. */
    @Volatile private var called = false
    @Volatile private var lastSpoken: String? = null
    @Volatile private var lastSpokenEnd = 0L

    /** The last answer said aloud and when (a follow-up's same answer is not said again). */
    private data class Said(val text: String)
    @Volatile private var lastAnswer: Said? = null
    @Volatile private var lastAnswerAt = 0L

    /** The answer being worked out (cancelled by "Jarvis, stop"). */
    private var answerJob: kotlinx.coroutines.Job? = null
    /** A slow answer Boss was told is coming: the mic button's one-question listen is not ended before it. */
    @Volatile private var lateWaiting = false

    private fun answer(q: String, confirm: Boolean = false, named: Boolean = true) {
        // From Boss's last word (Boss, 5 Oct): the turn's closing wait and the recognizer's final reading count too.
        heardAt = com.optionslab.ira.Turn.spokeEnd(turnPartialAt, turnEndAt, SystemClock.elapsedRealtime())
        _state.value = VoiceState(Mode.THINKING)
        answerJob = scope.launch {
            // Answer at once from what Jarvis already knows (kept fresh every minute while listening in market hours);
            // only with no prices at all is the first answer held for a refresh.
            val st = IraHub.state.value
            // Small talk ("how are you") needs no prices: never held for a refresh.
            val chat = com.optionslab.ira.Chat.smallTalk(q, 0) != null
            // Waited on for 4 seconds at most (no internet, a slow feed): the answer then comes from what the phone keeps.
            if (!chat && st.snaps.isEmpty()) { val r = launch(Dispatchers.Default) { runCatching { IraHub.refresh() } }; kotlinx.coroutines.withTimeoutOrNull(4_000) { r.join() } }
            // Prices are re-read in the background only while the market trades (a closed day's prices do not change, and
            // a full re-read competes with the voice for the phone's processor).
            else if (!chat && IraHub.online() && com.optionslab.ira.Market.NIFTY.trading(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))) &&
                st.liveAt?.isBefore(java.time.Instant.now().minusSeconds(120)) != false) launch(Dispatchers.Default) { runCatching { IraHub.refresh() } }
            if (confirm) IraHub.askConfirmed(q) else IraHub.ask(q)
            // A slow answer (your account, the trade check): say so at once instead of going quiet.
            val hold = launch { kotlinx.coroutines.delay(1_000); say("Working on it, Boss.", "wait") }
            val said = com.optionslab.ira.Secrets.redact(q.trim())
            // Some answers (your account, a backtest) arrive a moment later: wait for Ira's reply to THIS question.
            suspend fun reply(ms: Long) = kotlinx.coroutines.withTimeoutOrNull(ms) {
                IraHub.state.first { st -> IraHub.replyAfter(st.messages, said) != null }.let { st -> IraHub.replyAfter(st.messages, said)!! }
            }
            var late = false
            val a = reply(15_000) ?: run {
                // Longer than that: Boss is told, and free to ask something else meanwhile; the answer comes when ready.
                hold.cancel()
                say("Still working on it, Boss. I'll tell you as soon as it's done. What else can I do for you meanwhile?", "answer")
                late = true
                lateWaiting = true
                (try { reply(LATE_MS) } finally { lateWaiting = false })?.also {
                    // Not over Jarvis talking, or another question being answered (half a minute at most).
                    kotlinx.coroutines.withTimeoutOrNull(30_000) { while (speaking || _state.value.mode == Mode.THINKING) kotlinx.coroutines.delay(500) }
                }
            }
            hold.cancel()
            // How long Boss waited, for the diagnostics (Boss, 4 Oct: "getting late response").
            if (a != null) note("answer ready %.1f s after the words".format(java.util.Locale.ENGLISH, (SystemClock.elapsedRealtime() - heardAt) / 1000.0))
            val o = a?.order
            // A suggested trade is asked aloud by itself (yes or no): nothing more to say here.
            if (a?.action != null && IraHub.asksYesNo(a.action)) return@launch
            say(when {
                a == null -> if (late) "Boss, I could not finish what you asked earlier. Please ask me again." else "I could not work that out."
                o != null && o.missing.isEmpty() && o.refusal == null -> "I have put that order on the Ira screen. Nothing is sent until you confirm it there."
                a.action != null -> {
                    // Asked aloud instead of a button hidden in the chat: "Shall I stop ORB? Yes or no?"
                    asking = a.action; askingNeedsBoss = IraHub.isExit(a.action); askingUntil = 0
                    say(com.optionslab.ira.Address.boss("Shall I " + a.text.removePrefix("Tap Confirm to ").trimEnd('.') + "? Yes or no?"), "question")
                    return@launch
                }
                // Short answers (the owner's setting): the first sentence; "tell me more" says the whole answer.
                else -> (if (late) "About what you asked earlier: " else "") + com.optionslab.ira.Address.boss(Wake.spoken(a.text, when {
                    com.optionslab.ira.Ask.parse(q).command?.kind == com.optionslab.ira.Command.Kind.MORE -> 8
                    IraTools.brief -> 1
                    else -> 3 }))
            }.let { text ->
                // A follow-up (no "Jarvis") that brings back the very answer just given is not said again: the
                // follow-up window closes instead, so one answer can never repeat itself in a loop.
                val prev = lastAnswer
                if (!named && prev != null && prev.text == text && SystemClock.elapsedRealtime() - lastAnswerAt < 120_000) {
                    awakeUntil = 0; again(); return@launch
                }
                lastAnswer = Said(text); lastAnswerAt = SystemClock.elapsedRealtime()
                text
            }, "answer")
        }
    }

    /** Stops any speech now (muted). */
    fun hush() { main.post {
        val id = utterance?.substringBefore('#')
        utterance = null                                 // nothing still being prepared is said, and its onStop is ignored
        runCatching { tts?.stop() }
        if (speaking) afterSpeech(id)
    } }

    /**
     * Speaks, still listening - but only for "Jarvis" while it talks, so the owner can cut in and it never answers
     * itself (its own name is said as "my name", so it does not hear it) - then listens again, or stops after
     * [id] STOP_AFTER.
     */
    private fun say(text: String, id: String = "say") {
        unmuteNow()                                       // Jarvis's own voice is never muted
        val t = tts
        lastSpokenId = id
        if (id == "answer" || id == "question") lastSpoken = text
        // Muted: the words go on screen as a pop-up instead (answers and questions only; "One moment" is dropped).
        if (muted && !text.startsWith("Voice on")) {
            if (id == "answer" || id == "question") runCatching { JarvisPopup.show(this, "Jarvis (muted)", "$text\n\nSay \"Jarvis, unmute\" to hear me.") }
            afterSpeech(id); return
        }
        if (!voiceReady || t == null) { afterSpeech(id); return }
        _state.value = VoiceState(Mode.SPEAKING)
        applyStyle(t)                                   // a style or voice changed on the Ira screen takes effect now
        if (cutIn) {
            // Keep listening (for "Jarvis" only) once the sentence is under way.
            if (id != STOP_AFTER) main.postDelayed({ if (speaking) listen() }, 900)
        } else {
            // Take turns: not listening while speaking, so Jarvis never hears itself.
            main.removeCallbacks(finish)
            runCatching { rec?.cancel() }; listening = false; endTap(); lastHeard = null
        }
        speaking = true
        spokeAt = SystemClock.elapsedRealtime()
        val u = "$id#${++said}"
        utterance = u
        // Hindi replies: an answer is translated by the model (figures checked) and said in the phone's Hindi voice.
        if (hindi && id == "answer") {
            scope.launch {
                val h = withContext(Dispatchers.Default) { inHindi(text) }
                val hv = if (h != null) hindiVoice(t) else null
                if (utterance != u || !speaking || muted) return@launch
                if (h != null && hv != null) { t.voice = hv; synchronized(applied) { applied.remove(t) } }
                if (!speakPieces(t, spokenName(if (h != null && hv != null) h else text), u)) { speaking = false; afterSpeech(id) }
            }
            return
        }
        if (!speakPieces(t, spokenName(text), u)) { speaking = false; afterSpeech(id) }
    }

    /**
     * Says [text] as utterance [u]: the first sentence alone, the rest queued behind it ([com.optionslab.ira.Wake.pieces]),
     * so the voice starts once the first sentence is made into sound, not the whole answer (Boss, 5 Oct: speed). Pieces
     * before the last are "[u].k" (an answer's first sound still starts with its id, for the reply time); [utterance] is
     * the last piece queued, so speech counts as over only when all of it is said. False: nothing could be said.
     */
    private fun speakPieces(t: TextToSpeech, text: String, u: String): Boolean {
        val parts = com.optionslab.ira.Wake.pieces(text)
        for ((k, p) in parts.withIndex()) {
            val uid = if (k == parts.lastIndex) u else "$u.$k"
            if (t.speak(p, if (k == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, uid) != TextToSpeech.SUCCESS) {
                if (k == 0) return false
                // The rest could not be queued: speech ends with the piece already queued.
                if (utterance == u) utterance = "$u.${k - 1}"
                return true
            }
        }
        return true
    }

    /**
     * Its own name is never said aloud (it listens while it talks, and would hear itself and cut itself off): spoken,
     * "say Jarvis" is "say my name". (It was spelled out, J.A.R.V.I.S., which Boss found odd - 3 Oct.)
     */
    private fun spokenName(text: String) = text
        .replace(Regex("(?i)\\b(I'm|I am)\\s+(?:j[ae]rv[ia]s+|jar vis)\\b"), "$1 your assistant")
        .replace(Regex("(?i)\\b(say|call|saying)\\s+\"?(?:j[ae]rv[ia]s+|jar vis)\\b"), "$1 my name")
        .let { WAKE.replace(it, "my name") }

    private fun afterSpeech(id: String?) {
        speaking = false
        lastSpokenEnd = SystemClock.elapsedRealtime()
        if (stopped) return
        if (id == STOP_AFTER) { stopSelf(); return }
        if (id == "answer") { awakeUntil = SystemClock.elapsedRealtime() + FOLLOW_MS; called = false }   // a follow-up needs no "Jarvis" (and may not act)
        if (id == "question" && askingUntil < SystemClock.elapsedRealtime()) askingUntil = SystemClock.elapsedRealtime() + ANSWER_MS
        again(150)
    }

    private fun giveUp(why: String) { _state.value = VoiceState(problem = why); stopSelf() }

    override fun onDestroy() {
        unmuteNow()
        stopped = true
        if (instance?.get() === this) instance = null
        main.removeCallbacksAndMessages(null)
        endTap(); lastHeard = null
        runCatching { rec?.destroy() }; rec = null
        runCatching { tts?.stop(); tts?.shutdown() }; tts = null
        scope.cancel()
        _state.value = VoiceState(problem = _state.value.problem)
        super.onDestroy()
    }
}
