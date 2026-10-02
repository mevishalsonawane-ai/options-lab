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
 * JarvisAlgo's ears and voice, always on while the owner keeps it switched on. It listens with Android's ON-DEVICE
 * speech recognizer only (Android 12+: the audio never leaves the phone; there is no fallback to an online one), wakes
 * on "Jarvis", answers through [IraHub] like a typed question and speaks with an offline voice of the phone's own
 * text-to-speech. Nothing heard is recorded, logged or kept beyond the question in the conversation. An order asked
 * by voice is only prepared on the Ira screen. The one spoken yes: when Jarvis itself asks about a news trade, the
 * owner's yes or no (approve / reject, positive / negative) within a minute answers it. A microphone foreground
 * service with its own notification (and a Stop button) while it runs; only JarvisAlgo declares it.
 */
class JarvisVoice : Service() {
    enum class Mode { OFF, LISTENING, AWAKE, THINKING, SPEAKING }
    data class VoiceState(val mode: Mode = Mode.OFF, val problem: String? = null)
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

        /** Speaks [text] if Jarvis is listening now; false when it is not. */
        fun announce(text: String): Boolean {
            val v = instance?.get() ?: return false
            v.main.post { v.say(text, "answer") }
            return true
        }

        /** After Jarvis asks a yes-or-no question (a news trade), the answer is heard for this long without "Jarvis". */
        private const val ANSWER_MS = 60_000L

        /**
         * Says [text] (the news, good or bad, and the trade) and waits for the owner's yes or no to action [id]: yes
         * approves it, no rejects it. False when Jarvis is not listening (the pop-up and the Ira screen still ask).
         */
        fun askYesNo(id: Long, text: String): Boolean {
            val v = instance?.get() ?: return false
            v.main.post { v.asking = id; v.askingUntil = 0; v.say(text, "question") }
            return true
        }

        /** Pauses listening while the owner teaches Jarvis their voice (the microphone is needed for that). */
        fun hold(on: Boolean) {
            val v = instance?.get() ?: return
            v.main.post { v.held = on; if (on) { runCatching { v.rec?.cancel() }; v.endTap() } else v.again(300) }
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

        fun permitted(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** From the app on screen only (Android lets a microphone service start only then). */
        fun start(context: Context) {
            if (!available(context) || !permitted(context)) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, JarvisVoice::class.java)) }
                .onFailure { _state.value = VoiceState(problem = "Android did not let Jarvis start listening; try the switch again.") }
        }

        fun stop(context: Context) { context.stopService(Intent(context, JarvisVoice::class.java)) }

        /**
         * How Jarvis sounds. Android's voices are adult ones; a young girl's voice is the phone's voice pitched up and a
         * little quicker (the owner's choice, 2026-10-02).
         */
        var style: Style
            // A normal male voice by default (the owner's wish, 2026-10-02); a new key, so an earlier choice starts from it.
            get() = runCatching { Style.valueOf(com.optionslab.app.security.SecurePrefs.getString("jarvis.voice.style2") ?: "MAN") }.getOrDefault(Style.MAN)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.voice.style2", v.name) } }

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

        /** Sets [t] to the chosen offline voice and style; false when the phone has no offline English voice. */
        fun applyStyle(t: TextToSpeech): Boolean {
            val all = offlineVoices(t)
            val v = all.firstOrNull { it.name == voiceName } ?: (if (style == Style.MAN || style == Style.DEEP) maleVoice(all) else null) ?: all.firstOrNull() ?: return false
            t.voice = v
            t.setPitch(style.pitch); t.setSpeechRate(style.rate)
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
    @Volatile private var held = false
    /** This turn's own capture, shared with the recognizer (Android 13+ with a taught voice), or null. */
    private var tap: VoiceGuard.Tap? = null
    /** The phone's recognizer refused our audio: plain microphone from now on (voice cannot trade then). */
    private var tapFailed = false
    /** What the recognizer heard last turn (for the voice check), then dropped. */
    private var lastHeard: ShortArray? = null
    /** The action Jarvis asked a yes or no about, heard until [askingUntil]. */
    private var asking: Long? = null
    private var askingUntil = 0L
    private var lang = "en-IN"
    private var triedOtherLanguage = false
    private var errorsInRow = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { wanted = false; stopSelf(); return START_NOT_STICKY }
        val why = when {
            !com.optionslab.app.BuildConfig.JARVIS -> "Voice is in JarvisAlgo only."
            !permitted(this) -> "Jarvis needs the microphone permission to listen."
            !available(this) -> "This phone has no on-device speech recognizer (Android 12 or later), so Jarvis does not listen: it never sends your voice off the phone."
            else -> null
        }
        if (why != null) { _state.value = VoiceState(problem = why); stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (e: Exception) {
            _state.value = VoiceState(problem = "Android did not let Jarvis listen in the background; open JarvisAlgo to start it again.")
            stopSelf(); return START_NOT_STICKY
        }
        instance = java.lang.ref.WeakReference(this)
        if (rec == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
            tts = TextToSpeech(this) { status -> main.post { voiceReady = status == TextToSpeech.SUCCESS && pickOfflineVoice() } }
            listen()
        }
        return START_STICKY
    }

    /** An English voice that needs no network; without one Jarvis answers on screen only. */
    private fun pickOfflineVoice(): Boolean {
        val t = tts ?: return false
        if (!applyStyle(t)) return false
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { afterSpeech(id) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { afterSpeech(id) } }
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
        .addAction(0, "Stop", PendingIntent.getService(this, 1, Intent(this, JarvisVoice::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun awake() = SystemClock.elapsedRealtime() < awakeUntil

    private fun listen() {
        if (stopped || held) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        // With a taught voice (Android 13+): our own capture feeds the recognizer, so the words are also voice-checked.
        endTap()
        if (VoiceGuard.supported && VoiceGuard.enrolled && !tapFailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tap = runCatching { VoiceGuard.Tap() }.getOrNull()
            tap?.let { t ->
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, t.read)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, com.optionslab.ira.VoicePrint.RATE)
            }
        }
        runCatching { rec?.startListening(i) }.onFailure { endTap(); again(1_000) }
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
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            errorsInRow = 0
            endTap()
            heard(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
        }

        override fun onError(error: Int) {
            val shared = tap != null
            endTap(); lastHeard = null
            if (stopped) return
            // The recognizer would not take our audio: back to its own microphone (voice can ask, not trade).
            if (shared && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_AUDIO ||
                    (Build.VERSION.SDK_INT >= 33 && error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT))) { tapFailed = true; again(500); return }
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> giveUp("Jarvis lost the microphone permission.")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    if (!triedOtherLanguage) { triedOtherLanguage = true; lang = "en-US"; again() }
                    else giveUp("No on-device English speech model: add one in the phone's Settings (System → Languages → On-device speech recognition).")
                else -> {
                    // Silence and no-match are normal between sentences; a run of other errors backs off up to 5 s.
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) errorsInRow++
                    again(minOf(5_000L, 250L shl minOf(errorsInRow, 5)))
                }
            }
        }
    }

    private fun heard(alternatives: List<String>) {
        // The answer to Jarvis's yes-or-no question: only the recognizer's best reading, and anything unclear is not a yes.
        val id = asking
        if (id != null && SystemClock.elapsedRealtime() < askingUntil) {
            val yes = alternatives.firstOrNull()?.let { Wake.yesNo(it) }
            if (yes != null) {
                // Only Boss's voice approves a trade; a no from anyone is still a no.
                if (yes && !boss()) { say(VoiceGuard.blocked() ?: "Boss, that didn't sound like you, so I won't place it. Say yes again, or tap Approve.", "question"); return }
                asking = null
                _state.value = VoiceState(Mode.THINKING)
                scope.launch {
                    val r = if (yes) withContext(Dispatchers.Default) { IraHub.confirm(id) } ?: "That had already lapsed; nothing was placed."
                        else { IraHub.cancelAction(id); "Rejected. Nothing was placed." }
                    say(com.optionslab.ira.Address.boss(Wake.spoken(r)), "answer")
                }
                return
            }
        }
        val awake = awake()
        val h = alternatives.asSequence().map { Wake.heard(it, awake) }.firstOrNull { it !is Wake.Heard.Ignore } ?: Wake.Heard.Ignore
        when (h) {
            Wake.Heard.Ignore -> again()
            Wake.Heard.Awake -> { awakeUntil = SystemClock.elapsedRealtime() + AWAKE_MS; say("Yes, Boss?") }
            Wake.Heard.Stop -> { wanted = false; say("Going to sleep, Boss. Switch me on again in JarvisAlgo.", STOP_AFTER) }
            is Wake.Heard.Ask -> {
                awakeUntil = 0
                // A follow-up (no "Jarvis" in it) may ask, never act: trades and commands need the name, so talk nearby cannot trigger one.
                val named = alternatives.any { Regex("\\bj[ae]rv[ia]s").containsMatchIn(it.lowercase()) }
                val topics = com.optionslab.ira.Ask.parse(h.question).topics
                val acts = com.optionslab.ira.Topic.COMMAND in topics || com.optionslab.ira.Topic.ORDER in topics
                if (!named && acts) say("Boss, say Jarvis first for that.")
                // Orders and commands by voice: only Boss's voice.
                else if (acts && !boss()) say(VoiceGuard.blocked() ?: "Boss, that didn't sound like you, so I won't do it. Say it again, or use the Ira screen.")
                else answer(h.question)
            }
        }
    }

    private fun answer(q: String) {
        _state.value = VoiceState(Mode.THINKING)
        scope.launch {
            val st = IraHub.state.value
            val stale = st.snaps.isEmpty() || st.liveAt?.isBefore(java.time.Instant.now().minusSeconds(120)) != false
            if (stale) runCatching { withContext(Dispatchers.Default) { IraHub.refresh() } }
            IraHub.ask(q)
            val said = com.optionslab.ira.Secrets.redact(q.trim())
            // Some answers (your account, a backtest) arrive a moment later: wait for Ira's reply to THIS question.
            val a = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                IraHub.state.first { st -> st.messages.indexOfLast { !it.fromIra && it.text == said }.let { i -> i >= 0 && st.messages.drop(i + 1).any { it.fromIra } } }
                    .messages.let { ms -> ms.drop(ms.indexOfLast { !it.fromIra && it.text == said } + 1).first { it.fromIra } }
            }
            val o = a?.order
            // A suggested trade is asked aloud by itself (yes or no): nothing more to say here.
            if (a?.action != null && IraHub.asksYesNo(a.action)) return@launch
            say(when {
                a == null -> "I could not work that out."
                o != null && o.missing.isEmpty() && o.refusal == null -> "I have put that order on the Ira screen. Nothing is sent until you confirm it there."
                a.action != null -> "Tap Confirm on the Ira screen to do that."
                else -> com.optionslab.ira.Address.boss(Wake.spoken(a.text))
            }, "answer")
        }
    }

    /** Speaks (not listening meanwhile, so Jarvis never hears itself), then listens again - or stops after [id] STOP_AFTER. */
    private fun say(text: String, id: String = "say") {
        runCatching { rec?.cancel() }
        val t = tts
        if (!voiceReady || t == null) { afterSpeech(id); return }
        _state.value = VoiceState(Mode.SPEAKING)
        applyStyle(t)                                   // a style or voice changed on the Ira screen takes effect now
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) afterSpeech(id)
    }

    private fun afterSpeech(id: String?) {
        if (stopped) return
        if (id == STOP_AFTER) { stopSelf(); return }
        if (id == "answer") awakeUntil = SystemClock.elapsedRealtime() + FOLLOW_MS   // a follow-up needs no "Jarvis"
        if (id == "question" && askingUntil < SystemClock.elapsedRealtime()) askingUntil = SystemClock.elapsedRealtime() + ANSWER_MS
        again(150)
    }

    private fun giveUp(why: String) { _state.value = VoiceState(problem = why); stopSelf() }

    override fun onDestroy() {
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
