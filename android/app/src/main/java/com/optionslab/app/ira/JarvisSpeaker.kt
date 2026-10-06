package com.optionslab.app.ira

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Jarvis's replies read aloud on the Ira screen (Jarvis; the owner's wish, 2026-10-02): a typed question is
 * answered aloud too (the owner's switch, on by default), and every reply has a Listen button. The phone's offline
 * voice in the chosen style; when Jarvis is listening, through its own voice so it never hears itself.
 */
object JarvisSpeaker {
    var speakTyped: Boolean
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.speak.typed", true) }.getOrDefault(true)
        set(v) { runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("jarvis.speak.typed" to v)) } }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var waiting: String? = null

    /**
     * Battery (round 4): the engine (a bound service in the speech engine's own process) is let go after [IDLE_MS] with
     * nothing said, and started again on the next reply - which then waits the second or so the engine takes to start,
     * as the very first reply always did; opening the Ira page warms it again. Only this voice: listening's own voice
     * (and every safety alert said through it) is not touched.
     */
    private const val IDLE_MS = 10 * 60_000L
    private val idle by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val release = Runnable {
        synchronized(this) {
            val t = tts ?: return@synchronized
            // Still talking: later. (An engine that never started after ten minutes is let go too, and started afresh.)
            if (ready && runCatching { t.isSpeaking }.getOrDefault(true)) { idleLater(); return@synchronized }
            runCatching { t.stop(); t.shutdown() }
            tts = null; ready = false; waiting = null
        }
    }

    private fun idleLater() {
        runCatching { idle.removeCallbacks(release); idle.postDelayed(release, IDLE_MS) }
    }

    /** How a reply is said ([com.optionslab.ira.Aloud]): Boss once, figures as a person says them, the first few sentences and "the rest is in the chat". */
    fun words(text: String, sentences: Int = (if (IraTools.brief) com.optionslab.ira.Aloud.Length.SHORT else com.optionslab.ira.Aloud.Length.USUAL).sentences): String =
        com.optionslab.ira.Aloud.say(text, sentences)

    /**
     * The 09:00 morning check said aloud (Boss, 4 Oct: "will Jarvis greet me by voice at 9?"): through the listening voice
     * when it is on, else through this one when "Morning check aloud" is on - never when muted or in quiet hours.
     */
    fun morning(context: Context, text: String) {
        // In full at any hour: a kill switch on or Zerodha not logged in is never cut to "the rest is in the chat".
        if (JarvisVoice.announce(text, full = true)) return
        if (!com.optionslab.app.BuildConfig.JARVIS || JarvisVoice.muted || JarvisVoice.quietNow() || !Automations.on(Automations.Auto.MORNING_VOICE)) return
        speak(context, text, 8)
    }

    /**
     * The speech engine started ahead (the Ira page opened, typed replies spoken, listening off): the first reply is said
     * at once instead of after the second or so the engine takes to start (Boss, 4 Oct: replies felt slow).
     */
    fun warm(context: Context) {
        if (!com.optionslab.app.BuildConfig.JARVIS || !speakTyped || JarvisVoice.muted || JarvisVoice.listenOn) return
        if (android.os.Build.FINGERPRINT == "robolectric") return
        synchronized(this) {
            idleLater()
            if (tts != null) return
            tts = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    val x = tts ?: return@synchronized
                    ready = status == TextToSpeech.SUCCESS && JarvisVoice.applyStyle(x)
                    if (ready) waiting?.let { sayNow(x, it) }
                    waiting = null
                }
            }
        }
    }

    /** [sentences]: how much to say (null: the usual, one in short-answer mode); "tell me more" says it in full. */
    fun speak(context: Context, text: String, sentences: Int? = null) {
        if (!com.optionslab.app.BuildConfig.JARVIS || JarvisVoice.muted && !text.startsWith("Voice on")) return
        val said = if (sentences != null) words(text, sentences) else words(text)
        if (JarvisVoice.announce(said, prompted = true)) return
        aloud(context, said, flush = true)
    }

    /**
     * [said] through this voice as it is (already shortened and checked by the caller): Jarvis's own notes and alerts while
     * "Don't listen" has the listening voice stopped ([JarvisVoice.announce]). [flush]: over what is being said (a reply,
     * a warning); else after it.
     */
    fun aloud(context: Context, said: String, flush: Boolean) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        if (android.os.Build.FINGERPRINT == "robolectric") { lastAloud = said; return }
        synchronized(this) {
            idleLater()
            val t = tts
            if (t != null && ready) { JarvisVoice.applyStyle(t); sayNow(t, said, flush); return }
            waiting = said
            if (t == null) tts = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    val x = tts ?: return@synchronized
                    ready = status == TextToSpeech.SUCCESS && JarvisVoice.applyStyle(x)
                    if (ready) waiting?.let { sayNow(x, it) }
                    waiting = null
                }
            }
        }
    }

    /** The first sentence alone, the rest queued behind it: the voice starts before the whole reply is made into sound. */
    private fun sayNow(t: TextToSpeech, text: String, flush: Boolean = true) {
        com.optionslab.ira.Wake.pieces(text).forEachIndexed { k, p ->
            t.speak(p, if (k == 0 && flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "reply.$k")
        }
    }

    fun stop() { synchronized(this) { runCatching { tts?.stop() } } }

    /** The last words handed to [aloud] under Robolectric (no speech engine there): what tests check. */
    @Volatile internal var lastAloud: String? = null

    /** After a typed [question]: says Jarvis's reply the moment it is there (never waiting for the model's rewrite). */
    suspend fun replyTo(context: Context, question: String) {
        if (!com.optionslab.app.BuildConfig.JARVIS || !speakTyped) return
        val said = com.optionslab.ira.Secrets.redact(question.trim())
        val reply = withTimeoutOrNull(20_000) {
            IraHub.state.first { st -> IraHub.replyAfter(st.messages, said) != null }.let { st -> IraHub.replyAfter(st.messages, said)!! }
        } ?: return
        // A trade Jarvis asks about aloud by itself: nothing more to say.
        if (reply.action != null && IraHub.asksYesNo(reply.action)) return
        // "Tell me more" is said in full, short answers or not.
        val more = runCatching { com.optionslab.ira.Commands.parse(said)?.kind == com.optionslab.ira.Command.Kind.MORE }.getOrDefault(false)
        // A question that named no index, read for the one Boss usually means: "BankNifty, as usual:" first (speech wording
        // only; never on a locked phone, never before a warning - [com.optionslab.ira.UsualIndex.aloud]).
        val usualIdx = runCatching { IraTools.indexReadFor(said) }.getOrNull()
        val usualLocked = usualIdx != null && runCatching { IraHub.locked() }.getOrDefault(true)
        // Short answers (Boss's choice, the default): the one line the chat shows is what is said, whole (its safety notes kept).
        // A kind Boss usually asks "more" after ([com.optionslab.ira.MoreAfter]): said in full straight away, not the short
        // line first - never on a locked phone, never with his own "shorter" on. Length only.
        val fullFirst = !more && !reply.whole && runCatching { IraTools.shortAnswers && IraTools.moreAfterFull(said, IraHub.locked()) }.getOrDefault(false)
        val shortOn = !more && !reply.whole && !fullFirst && runCatching { IraTools.shortAnswers }.getOrDefault(true)
        val answerText = if (shortOn) runCatching { com.optionslab.ira.ShortAnswer.of(said, reply.text).line }.getOrDefault(reply.text) else reply.text
        val spokenText = runCatching { com.optionslab.ira.UsualIndex.aloud(usualIdx, answerText, usualLocked) }.getOrDefault(answerText)
        speak(context, spokenText, if (more || shortOn || fullFirst) 8 else null)
    }
}
