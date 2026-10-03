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
            if (!prompted && quietNow()) { runCatching { JarvisPopup.show(v, "Jarvis", text) }; return true }
            v.main.post { v.say(text, "answer") }
            return true
        }

        /**
         * "Why can't I hear you?": everything that stops the voice, said plainly (muted, quiet hours, typed replies not
         * spoken, no offline voice, the phone's media volume at zero), or that all looks right.
         */
        fun diagnose(context: Context?): String {
            val out = ArrayList<String>()
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

        const val ACTION_TALK = "com.optionslab.app.ira.JarvisVoice.TALK"

        /**
         * The mic button: Jarvis says "Yes, Boss?" and takes the next sentence as the question, no "Jarvis" needed.
         * With listening off it listens just for that one question, then stops again. False when it cannot listen.
         */
        fun talk(context: Context): Boolean {
            if (!available(context) || !permitted(context)) return false
            return runCatching { ContextCompat.startForegroundService(context, Intent(context, JarvisVoice::class.java).setAction(ACTION_TALK)) }
                .onFailure { _state.value = VoiceState(problem = "Android did not let Jarvis listen; try again with the app open.") }.isSuccess
        }

        /**
         * How Jarvis sounds. Android's voices are adult ones; a young girl's voice is the phone's voice pitched up and a
         * little quicker (the owner's choice, 2026-10-02).
         */
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
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.mute", false) }.getOrDefault(false)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.mute", v) }; if (v) { instance?.get()?.hush(); JarvisSpeaker.stop() } }

        /** Replies in Hindi (the AI model translates; figures are checked, and English is used when it cannot). */
        var hindi: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.hindi", false) }.getOrDefault(false)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.hindi", v) } }

        /** How long the last spoken reply took, from Boss's last word to Jarvis's first sound (ms), or 0. */
        @Volatile var lastLatencyMs = 0L

        /** Jarvis is speaking now. */
        val speakingNow: Boolean get() = instance?.get()?.speaking == true

        /** When the last sound started (elapsed ms): the model starts once the voice is under way. */
        @Volatile var speechStartedAt = 0L

        /** What the recognizer heard last, for the Settings check. */
        @Volatile var heardText: String? = null; private set

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
            val key = "${style.name}|${voiceName}"
            if (synchronized(applied) { applied[t] } == key) return true
            val all = offlineVoices(t)
            val v = all.firstOrNull { it.name == voiceName } ?: (if (style == Style.MAN || style == Style.DEEP) maleVoice(all) else null) ?: all.firstOrNull() ?: return false
            t.voice = v
            t.setPitch(style.pitch); t.setSpeechRate(style.rate)
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
    private val finish = Runnable { if (listening && !speaking) runCatching { rec?.stopListening() } }
    /** Words stopped changing this long: the turn ends (a short pause inside a sentence must not cut it). */
    private val END_AFTER_MS = 900L
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
            if (listening && now - listenedAt > 25_000) { runCatching { rec?.cancel() }; listening = false; endTap(); again() }
            if (speaking && now - spokeAt > 60_000) { speaking = false; again() }
            // The mic button's one question was asked and answered (or never came): listening stops again.
            if (oneShot && !speaking && !awake() && _state.value.mode != Mode.THINKING && now - talkAt > 14_000) { stopSelf(); return }
            if (!listening && !speaking && !held && _state.value.mode != Mode.THINKING) again()
            // Self-healing: no listening turn for 3 minutes (the phone took the microphone, the recognizer died):
            // a new recognizer, noted in the activity log.
            if (Automations.on(Automations.Auto.SELFHEAL) && com.optionslab.ira.VoiceHealth.stuck(now, readyAt, speaking, !held && _state.value.mode != Mode.THINKING) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                readyAt = now
                runCatching { rec?.destroy() }
                rec = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this@JarvisVoice).also { it.setRecognitionListener(listener) } }.getOrNull()
                listening = false; endTap()
                IraActivity.add("Restarted listening (the microphone had gone quiet).")
                Automations.acted(Automations.Auto.SELFHEAL, "Restarted listening.")
                again()
            }
            main.postDelayed(this, 5_000)
        }
    }
    private val WAKE = Regex("\\bj[ae]rv[ia]s+\\b|\\bjar vis\\b", RegexOption.IGNORE_CASE)
    @Volatile private var held = false
    /** When the recognizer last opened the microphone (for the self-healing check). */
    @Volatile private var readyAt = SystemClock.elapsedRealtime()
    /** This turn's own capture, shared with the recognizer (Android 13+ with a taught voice), or null. */
    private var tap: VoiceGuard.Tap? = null
    /** The phone's recognizer refused our audio: plain microphone from now on (voice cannot trade then). */
    private var tapFailed = false
    /** What the recognizer heard last turn (for the voice check), then dropped. */
    private var lastHeard: ShortArray? = null
    /** The action Jarvis asked a yes or no about, heard until [askingUntil]. */
    private var asking: Long? = null
    /** A trade needs Boss's own voice for its yes; a command (start, stop...) needs only a yes. */
    private var askingNeedsBoss = true
    private var askingUntil = 0L
    private var lang = "en-IN"
    private var triedOtherLanguage = false
    private var errorsInRow = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { wanted = false; stopSelf(); return START_NOT_STICKY }
        // The mic button with listening off: this one question only.
        val talkNow = intent?.action == ACTION_TALK
        if (talkNow && rec == null) oneShot = !wanted
        if (!talkNow) oneShot = false                    // the switch turned on: listening stays on
        // Restarted by the system after a one-question listen with the switch off: do not listen.
        if (intent == null && !wanted) { stopSelf(); return START_NOT_STICKY }
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
            main.postDelayed(watchdog, 5_000)
            // While listening, the slow answers are kept ready so none waits: prices every minute in market hours, your
            // account and the trade check every 30 seconds.
            // The model is loaded while Jarvis listens: a spoken question never waits the seconds loading takes.
            runCatching { IraModel.preload() }
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
            main.postDelayed({ awakeUntil = SystemClock.elapsedRealtime() + AWAKE_MS + 4_000; say("Yes, Boss?") }, if (voiceReady) 0L else 800L)
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
                if (h > 0 && (id?.startsWith("answer") == true || id?.startsWith("question") == true)) { if (took < 60_000) lastLatencyMs = took; heardAt = 0L }
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

    private fun listen() {
        if (stopped || held || listening) return
        lastHeard = null                                 // a voice check only ever uses this turn's own audio
        turnInSpeech = speaking
        listenedAt = SystemClock.elapsedRealtime()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
        override fun onReadyForSpeech(params: Bundle?) { errorsInRow = 0; readyAt = SystemClock.elapsedRealtime() }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {
            // The owner says "Jarvis" while Jarvis is talking: stop at once and listen (the question follows).
            val words = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (speaking) { if (words.any { WAKE.containsMatchIn(it) }) interrupt(); return }
            // Boss is speaking to Jarvis: once the words stop changing for a moment, end the turn now instead of
            // waiting for the recognizer's own long silence.
            val first = words.firstOrNull()?.trim().orEmpty()
            if (first.isNotEmpty() && (WAKE.containsMatchIn(first) || awake() || asking != null)) {
                main.removeCallbacks(finish)
                // Only the name so far ("Jarvis..." and a breath before the question): never cut there, or the question
                // is lost and only "Yes, Boss?" is said. The recognizer's own silence ends that turn.
                val nameOnly = asking == null && com.optionslab.ira.Wake.heard(first, awake()) is com.optionslab.ira.Wake.Heard.Awake
                if (!nameOnly) main.postDelayed(finish, END_AFTER_MS)
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            main.removeCallbacks(finish)
            listening = false
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heardText = com.optionslab.ira.Secrets.redact(it) }
            errorsInRow = 0
            endTap()
            heard(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
        }

        override fun onError(error: Int) {
            main.removeCallbacks(finish)
            listening = false
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
                    again(minOf(1_000L, 200L shl minOf(errorsInRow, 3)))
                }
            }
        }
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

    private fun heard(alternatives: List<String>) {
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
        when (h) {
            Wake.Heard.Ignore -> again()
            Wake.Heard.Awake -> { awakeUntil = SystemClock.elapsedRealtime() + AWAKE_MS; say("Yes, Boss?") }
            Wake.Heard.Stop -> { wanted = false; say("Going to sleep, Boss. Switch me on again in JarvisAlgo.", STOP_AFTER) }
            is Wake.Heard.Ask -> {
                // "Jarvis, stop talking" said over Jarvis: it has already stopped; that is not a lasting mute.
                if (cutIn && Regex("^(stop|please stop|ok stop) (talking|speaking)$").matches(h.question.lowercase().trim())) { again(); return }
                // A follow-up (no "Jarvis" in it) may ask, never act: trades and commands need the name, so talk nearby cannot trigger one.
                val named = alternatives.any { Regex("\\bj[ae]rv[ia]s").containsMatchIn(it.lowercase()) }
                // Its own last words heard back without the name are not a question (the follow-up window stays open).
                if (!named && Wake.echo(h.question, lastSpoken?.takeIf { SystemClock.elapsedRealtime() - lastSpokenEnd < 15_000 })) { again(); return }
                awakeUntil = 0
                val topics = com.optionslab.ira.Ask.parse(h.question).topics
                // Muting, unmuting and the reply language are not actions: a follow-up "mute" works without the name.
                val voiceOnly = com.optionslab.ira.Ask.parse(h.question).command?.kind in VOICE_KINDS
                val acts = !voiceOnly && (com.optionslab.ira.Topic.COMMAND in topics || com.optionslab.ira.Topic.ORDER in topics)
                // Locked phone: questions only; the account needs Boss's own voice.
                // Mute, unmute and the voice check are not actions: they work on a locked phone too.
                val lockedNo = if (locked()) com.optionslab.ira.LockRule.refuse(true, acts || com.optionslab.ira.Topic.COMMAND in topics && !voiceOnly && com.optionslab.ira.Ask.parse(h.question).command?.kind != com.optionslab.ira.Command.Kind.VOICE_CHECK,
                    com.optionslab.ira.Topic.ACCOUNT in topics, com.optionslab.ira.Topic.ACCOUNT in topics && boss()) else null
                if (lockedNo != null) say(lockedNo)
                else if (!named && acts) { IraTools.count("nameFirst"); say("Boss, say Jarvis first for that.") }
                else {
                    // Trades, and commands that add risk (live mode, kill switch off, autopilot, starting arms), need
                    // Boss's own voice; without it a command is asked as a yes or no instead of done at once, and
                    // the riskiest ones are refused. Stopping, closing and questions need only the name.
                    val cmd = com.optionslab.ira.Ask.parse(h.question).command
                    // Loosening one of the app's limits (more lots, a bigger loss limit, a limit off) is Boss's alone.
                    val loosens = cmd != null && runCatching { IraActions.loosens(cmd) }.getOrDefault(true)
                    val risky = cmd != null && (!cmd.kind.reduces || loosens)
                    val verified = (com.optionslab.ira.Topic.ORDER in topics || risky) && boss()
                    when {
                        com.optionslab.ira.Topic.ORDER in topics && !verified ->
                            say(VoiceGuard.blocked() ?: "Boss, that didn't sound like you, so I won't place it. Say it again, or use the Ira screen.")
                        risky && !verified && (cmd!!.kind in HIGH_RISK || loosens) ->
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
        com.optionslab.ira.Command.Kind.QUIET_ON, com.optionslab.ira.Command.Kind.QUIET_OFF)

    /** Commands that are never done on an unrecognised voice, even with a yes. */
    private val HIGH_RISK = setOf(com.optionslab.ira.Command.Kind.MODE_LIVE, com.optionslab.ira.Command.Kind.KILL_OFF,
        com.optionslab.ira.Command.Kind.JTRADES_LIVE, com.optionslab.ira.Command.Kind.AUTOPILOT_ON)

    /** What was said last (its id, and its words for an answer or question) and when that speech ended. */
    @Volatile private var lastSpokenId: String? = null
    @Volatile private var lastSpoken: String? = null
    @Volatile private var lastSpokenEnd = 0L

    /** The last answer said aloud and when (a follow-up's same answer is not said again). */
    private data class Said(val text: String)
    @Volatile private var lastAnswer: Said? = null
    @Volatile private var lastAnswerAt = 0L

    private fun answer(q: String, confirm: Boolean = false, named: Boolean = true) {
        heardAt = SystemClock.elapsedRealtime()
        _state.value = VoiceState(Mode.THINKING)
        scope.launch {
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
            val hold = launch { kotlinx.coroutines.delay(1_000); say("One moment, Boss.", "wait") }
            val said = com.optionslab.ira.Secrets.redact(q.trim())
            // Some answers (your account, a backtest) arrive a moment later: wait for Ira's reply to THIS question.
            val a = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                IraHub.state.first { st -> IraHub.replyAfter(st.messages, said) != null }.let { st -> IraHub.replyAfter(st.messages, said)!! }
            }
            hold.cancel()
            val o = a?.order
            // A suggested trade is asked aloud by itself (yes or no): nothing more to say here.
            if (a?.action != null && IraHub.asksYesNo(a.action)) return@launch
            say(when {
                a == null -> "I could not work that out."
                o != null && o.missing.isEmpty() && o.refusal == null -> "I have put that order on the Ira screen. Nothing is sent until you confirm it there."
                a.action != null -> {
                    // Asked aloud instead of a button hidden in the chat: "Shall I stop ORB? Yes or no?"
                    asking = a.action; askingNeedsBoss = IraHub.isExit(a.action); askingUntil = 0
                    say(com.optionslab.ira.Address.boss("Shall I " + a.text.removePrefix("Tap Confirm to ").trimEnd('.') + "? Yes or no?"), "question")
                    return@launch
                }
                // Short answers (the owner's setting): the first sentence; "tell me more" says the whole answer.
                else -> com.optionslab.ira.Address.boss(Wake.spoken(a.text, when {
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
     * itself (its own name is spelled out, J.A.R.V.I.S., so it does not hear it) - then listens again, or stops after
     * [id] STOP_AFTER.
     */
    private fun say(text: String, id: String = "say") {
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
        utterance = "$id#${++said}"
        // Hindi replies: an answer is translated by the model (figures checked) and said in the phone's Hindi voice.
        if (hindi && id == "answer") {
            val u = utterance
            scope.launch {
                val h = withContext(Dispatchers.Default) { inHindi(text) }
                val hv = if (h != null) hindiVoice(t) else null
                if (utterance != u || !speaking || muted) return@launch
                if (h != null && hv != null) { t.voice = hv; synchronized(applied) { applied.remove(t) } }
                if (t.speak(spokenName(if (h != null && hv != null) h else text), TextToSpeech.QUEUE_FLUSH, null, u) != TextToSpeech.SUCCESS) { speaking = false; afterSpeech(id) }
            }
            return
        }
        if (t.speak(spokenName(text), TextToSpeech.QUEUE_FLUSH, null, utterance) != TextToSpeech.SUCCESS) { speaking = false; afterSpeech(id) }
    }

    private fun spokenName(text: String) = WAKE.replace(text, "J.A.R.V.I.S.")

    private fun afterSpeech(id: String?) {
        speaking = false
        lastSpokenEnd = SystemClock.elapsedRealtime()
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
