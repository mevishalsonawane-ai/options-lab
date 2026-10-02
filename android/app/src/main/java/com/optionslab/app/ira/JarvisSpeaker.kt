package com.optionslab.app.ira

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Jarvis's replies read aloud on the Ira screen (JarvisAlgo; the owner's wish, 2026-10-02): a typed question is
 * answered aloud too (the owner's switch, on by default), and every reply has a Listen button. The phone's offline
 * voice in the chosen style; when Jarvis is listening, through its own voice so it never hears itself.
 */
object JarvisSpeaker {
    var speakTyped: Boolean
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.speak.typed", true) }.getOrDefault(true)
        set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.speak.typed", v) } }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var waiting: String? = null

    /** How a reply is said: addressed to Boss, rupees read as rupees, the first few sentences. */
    fun words(text: String): String = com.optionslab.ira.Address.boss(com.optionslab.ira.Wake.spoken(text, 6))

    fun speak(context: Context, text: String) {
        if (!com.optionslab.app.BuildConfig.JARVIS || JarvisVoice.muted && !text.startsWith("Voice on")) return
        val said = words(text)
        if (JarvisVoice.announce(said)) return
        if (android.os.Build.FINGERPRINT == "robolectric") return
        synchronized(this) {
            val t = tts
            if (t != null && ready) { JarvisVoice.applyStyle(t); t.speak(said, TextToSpeech.QUEUE_FLUSH, null, "reply"); return }
            waiting = said
            if (t == null) tts = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    val x = tts ?: return@synchronized
                    ready = status == TextToSpeech.SUCCESS && JarvisVoice.applyStyle(x)
                    if (ready) waiting?.let { x.speak(it, TextToSpeech.QUEUE_FLUSH, null, "reply") }
                    waiting = null
                }
            }
        }
    }

    fun stop() { synchronized(this) { runCatching { tts?.stop() } } }

    /** After a typed [question]: says Jarvis's reply the moment it is there (never waiting for the model's rewrite). */
    suspend fun replyTo(context: Context, question: String) {
        if (!com.optionslab.app.BuildConfig.JARVIS || !speakTyped) return
        val said = com.optionslab.ira.Secrets.redact(question.trim())
        val reply = withTimeoutOrNull(20_000) {
            IraHub.state.first { st ->
                val i = st.messages.indexOfLast { !it.fromIra && it.text == said }
                i >= 0 && st.messages.drop(i + 1).any { it.fromIra }
            }.messages.let { ms -> ms.drop(ms.indexOfLast { !it.fromIra && it.text == said } + 1).first { it.fromIra } }
        } ?: return
        // A trade Jarvis asks about aloud by itself: nothing more to say.
        if (reply.action != null && IraHub.asksYesNo(reply.action)) return
        speak(context, reply.text)
    }
}
