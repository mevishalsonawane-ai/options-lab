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
         * it is shown as a pop-up instead. [urgent]: a safety warning (a loss near the guard, the feed stopped, the
         * square-off, overtrading...) is said at once, never held while Boss is speaking; market colour and briefings
         * wait for him to finish (up to 8 s). [full]: never shortened outside Boss's hours (the morning check, his own
         * reminders); otherwise still unasked (quiet hours, a locked phone). [whole]: never cut to its one line (an
         * action's result or the guard's report: a failure in it is never dropped - review, 5 Oct); still unasked.
         */
        fun announce(text: String, prompted: Boolean = false, urgent: Boolean = false, full: Boolean = false,
                     weight: com.optionslab.ira.SpeakChoice.Weight? = null, whole: Boolean = false): Boolean {
            val v = instance?.get() ?: return false
            // Boss's "Jarvis speaks" choice (a display preference only): a reply, a safety warning, his own reminder or the
            // morning check is always said; an important note unless he chose "only answers"; a minor one only with "everything".
            val w = when {
                prompted || full -> com.optionslab.ira.SpeakChoice.Weight.ANSWER
                urgent -> com.optionslab.ira.SpeakChoice.Weight.WARNING
                else -> weight ?: com.optionslab.ira.SpeakChoice.Weight.IMPORTANT
            }
            val choice = runCatching { IraTools.speakChoice }.getOrDefault(com.optionslab.ira.SpeakChoice.Choice.IMPORTANT)
            com.optionslab.ira.SpeakChoice.why(choice, w)?.let { why -> lastHeld = System.currentTimeMillis() to why; return false }
            // Short answers (the default): an unasked note is said as its one line too - never a safety warning, never a
            // reply (already short), never his reminder or the morning check ([com.optionslab.ira.ShortAnswer]).
            val shortText = if (!prompted && !urgent && !full && !whole && runCatching { IraTools.shortAnswers }.getOrDefault(true))
                runCatching { com.optionslab.ira.ShortAnswer.of(null, text).line }.getOrDefault(text) else text
            // Unasked on a locked phone (it may be overheard): never an amount, a P&L or a symbol - only that it is in
            // the chat (every caller has already put the full line there).
            val overheard = if (prompted) shortText else com.optionslab.ira.Overheard.said(shortText, runCatching { IraHub.locked() }.getOrDefault(true))
            if (!prompted && quietNow()) { runCatching { JarvisPopup.show(v, "Jarvis", overheard) }; return true }
            // A long unasked briefing outside the hours Boss talks to him: its first sentence aloud, the rest in the chat
            // ([com.optionslab.ira.TalkHours]). Never a safety warning (urgent), a reply, the morning check or a
            // reminder (full); the voice only, nothing acts.
            val mayShorten = com.optionslab.ira.TalkHours.mayShorten(prompted, urgent, full) && !whole
            val said = if (mayShorten) IraTools.talkAloud(overheard) else overheard
            // An unasked note may wait (up to 30 s) for Boss's own answer: when it is finally said, the lock and quiet hours
            // are checked again on the line as it was before either (review, 5 Oct: one was said after the phone locked).
            val recheck: (() -> String?)? = if (prompted) null else ({
                val now = com.optionslab.ira.Overheard.atSay(shortText, runCatching { IraHub.locked() }.getOrDefault(true), quietNow())
                if (now == null) { note("an unasked note reached quiet hours while it waited: not said"); null }
                else if (mayShorten) IraTools.talkAloud(now) else now
            })
            // Not a reply to Boss's words (never timed as one); unless urgent, not said over him while he is speaking.
            v.main.post { if (urgent) { if (!v.stopped) v.say(said, "answer", reply = false) } else v.sayWhenFree(said, "answer", recheck = recheck) }
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
            append("Listen for Jarvis: $wanted · running: ${instance?.get() != null} · started from the app on screen: ${instance?.get()?.visibleStart} · battery saver for listening: $listenSaver (resting now: ${restingNow()})\n")
            append("Ears: ${if (googleSpeech) "Google's speech service" else "on the phone only"} · language: ${instance?.get()?.lang} · voice taught: ${VoiceGuard.enrolled} · only my voice: $onlyBoss · muted: $muted\n")
            append("On-device recognition available: ${context?.let { c -> runCatching { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(c) }.getOrNull() }} · " +
                "any recognizer: ${context?.let { c -> runCatching { SpeechRecognizer.isRecognitionAvailable(c) }.getOrNull() }}\n")
            append(mutedByLine()).append('\n')
            append("Voice check: ${runCatching { diagnose(context) }.getOrElse { "could not run" }}\n")
            com.optionslab.ira.Latency.say(latencies, voiceLatencies)?.let { append(it).append('\n') }
            append(com.optionslab.ira.BossPace.say(paceGaps)).append('\n')
            append(runCatching { com.optionslab.ira.CutIn.say(cutInNow(context ?: instance?.get()), cutInChoice) }.getOrElse { "Cut-in: could not check" })
                .append(" · ").append(com.optionslab.ira.CutStops.say(cutStopTally)).append('\n')
            append(runCatching { com.optionslab.ira.Hearing.say(hearingDays, hearingDay()) }.getOrElse { "Hearing: could not read" }).append('\n')
            append(runCatching { com.optionslab.ira.ListenLanguage.say(langState, instance?.get()?.lang, hearingDay(), onDeviceLangs) }.getOrElse { "Language: could not read" }).append('\n')
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

        /** [hint]: Boss asked (the voice check): the battery saver's hint may be said, once ever ([saverHintOnce]). */
        fun diagnose(context: Context?, hint: Boolean = false): String {
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
            // The other English is not on this phone for on-device listening: said plainly, the working one kept (round 20).
            v?.let { s -> runCatching { com.optionslab.ira.ListenLanguage.missing(s.lang, onDeviceLangs, googleSpeech) }.getOrNull()?.let { out += it } }
            // The pattern of the last turns in plain words (Boss, 5 Oct: sound, no words, turn after turn).
            v?.let { s -> runCatching { com.optionslab.ira.EmptyTurns.say(s.recentTurns, s.emptyInRow, cutInNow(s).on) }.getOrNull()?.let { out += it } }
            traceLines().takeIf { it.isNotEmpty() }?.let { out += "Last turns: " + it.joinToString("; ") + "." }
            if (restingNow()) out += "Battery saver for listening has my ears resting a few seconds between quiet turns (screen off, market shut): say \"Jarvis\" again if I miss it, or switch the saver off in the Jarvis page."
            // The actual reason, first when it is the mute (Boss, 5 Oct: "why is it sending most things in chat?").
            val mutedNow = muted
            if (mutedNow) {
                val quietTill = mutedUntil
                val muteWhy = if (System.currentTimeMillis() < quietTill) "I'm quiet until %tR (you asked for a quiet while)".format(java.util.Locale.ENGLISH, quietTill)
                    else runCatching { com.optionslab.ira.VoiceMute.why(muteMark, IST_ZONE) }.getOrDefault("I'm muted")
                out.add(0, "$muteWhy: say \"Jarvis, unmute\" or switch Mute off in Settings, Voice and AI model.")
            }
            if (quietNow()) out.add(if (mutedNow) 1 else 0, "It's quiet hours (22:00 to 07:00): I only speak when you ask.")
            // Boss's "Jarvis speaks" choice, named when it kept a note on screen in the last 6 hours (or keeps every note there).
            val speaksNow = runCatching { IraTools.speakChoice }.getOrDefault(com.optionslab.ira.SpeakChoice.Choice.IMPORTANT)
            val heldRecently = lastHeld?.takeIf { System.currentTimeMillis() - it.first < 6 * 3600_000L }
            if (heldRecently != null || speaksNow == com.optionslab.ira.SpeakChoice.Choice.ANSWERS)
                out += "\"Jarvis speaks\" is set to ${speaksNow.label.lowercase()} (Settings, Voice): " +
                    (if (speaksNow == com.optionslab.ira.SpeakChoice.Choice.ANSWERS) "notes I post by myself stay on screen; " else "minor notes (news, records, paper tests, goals) stay on screen; ") +
                    "answers and safety warnings are always spoken."
            heldRecently?.let { held -> out += "The last note I kept on screen, at %tR: %s.".format(java.util.Locale.ENGLISH, held.first, held.second) }
            if (!JarvisSpeaker.speakTyped) out += "Speaking typed replies is off (Settings, Voice and AI model)."
            if (instance?.get()?.voiceReady == false) out += "This phone has no offline English voice ready: add one in Settings, Accessibility, Text-to-speech."
            context?.let { c -> runCatching {
                val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) out += "Your phone's media volume is at zero: turn it up."
            } }
            if (!wanted) out += "Listening is off, so I only speak replies to typed questions (switch on Jarvis voice to talk to me)."
            if (lastLatencyMs > 0) out += "My last spoken reply took %.1f seconds.".format(java.util.Locale.ENGLISH, lastLatencyMs / 1000.0)
            // Battery (round 4): words only, once - the switch stays Boss's.
            val saver = if (hint) runCatching { saverHintOnce() }.getOrNull() else null
            // Short answers are a choice, not a fault: named last, so a long answer said as one line is never a mystery.
            val shortNote = if (runCatching { IraTools.shortAnswers }.getOrDefault(true))
                " Answers are short by your choice: I say one line; say \"more\" for the rest, or choose Detailed answers in Settings, Voice." else ""
            return if (out.isEmpty()) "Boss, my voice looks fine: not muted, volume up, a voice ready. If you still hear nothing, tap Listen under a reply." + (saver?.let { " $it" } ?: "") + shortNote
                else "Boss, here's why you may not hear me: " + (out + listOfNotNull(saver)).joinToString(" ") + shortNote
        }

        /** Quiet hours: nothing said unasked from 22:00 to 07:00 (on by default; "Jarvis, quiet hours off"). */
        var quietHours: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.quiet", true) }.getOrDefault(true)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("jarvis.quiet" to v)) } }

        fun quietNow(): Boolean = quietHours && com.optionslab.ira.Quiet.now(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")))

        /** After Jarvis asks a yes-or-no question (a news trade), the answer is heard for this long without "Jarvis". */
        private const val ANSWER_MS = 60_000L

        /**
         * Says [text] (the news, good or bad, and the trade) and waits for the owner's yes or no to action [id]: yes
         * approves it, no rejects it. False when Jarvis is not listening (the pop-up and the Ira screen still ask).
         */
        fun askYesNo(id: Long, text: String): Boolean {
            val v = instance?.get() ?: return false
            v.main.post { v.asking = id; v.askingText = text; v.askingNeedsBoss = true; v.askingUntil = 0; v.sayWhenFree(text, "question") }
            return true
        }

        /** Pauses listening while the owner teaches Jarvis their voice (the microphone is needed for that). */
        fun hold(on: Boolean) {
            val v = instance?.get() ?: return
            v.main.post { v.held = on; v.readyAt = SystemClock.elapsedRealtime(); if (on) { runCatching { v.rec?.cancel() }; v.listening = false; v.endTap() } else v.again(300) }
        }

        /**
         * Read-only: Jarvis has a yes-or-no question of his own waiting for Boss's answer (asked or about to be asked, its
         * answer window not yet over). Nothing else may then invite a bare "yes" ([com.optionslab.ira.NextAsk]).
         */
        fun askingOpen(): Boolean {
            val v = instance?.get() ?: return false
            if (v.asking == null) return false
            val until = v.askingUntil
            return until == 0L || SystemClock.elapsedRealtime() < until
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

        /** Writes the durable settings to disk off the main thread. */
        private val keeper = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * A setting saved: readable at once, written to disk in the background. A [tightening] change (Google speech
         * off, only-Boss on, listening off, mute on) must survive the app being killed a moment later - the older,
         * looser value must never come back - so it is flushed to disk at once on a background thread, and written
         * again once if that write failed. A loosening change may wait for the next background write.
         */
        private fun keep(values: Map<String, Any?>, tightening: Boolean) {
            runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(values) }
            if (!tightening) return
            runCatching {
                keeper.launch {
                    val firstOk = runCatching { com.optionslab.app.security.SecurePrefs.flush() }.getOrDefault(false)
                    if (!firstOk) {
                        // Queue the whole current map again (it carries this value and any newer one) and wait for it.
                        val secondOk = runCatching {
                            com.optionslab.app.security.SecurePrefs.putAllSoon(emptyMap())
                            com.optionslab.app.security.SecurePrefs.flush()
                        }.getOrDefault(false)
                        if (!secondOk) runCatching { com.optionslab.app.data.Diag.record("jarvis", "a safety setting could not be written to disk; it holds until the app closes") }
                    }
                }
            }
        }

        /** The owner's switch, kept on the phone: listening starts again when the app is opened. */
        var wanted: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.listen", false) }.getOrDefault(false)
            set(v) { keep(mapOf("jarvis.listen" to v), tightening = !v) }

        /**
         * Battery saver for listening (Boss's switch, OFF by default: off, listening is exactly as before). On: with the
         * screen off, the market shut, outside his usual hours and a quiet room, the ears rest a few seconds between turns
         * ([com.optionslab.ira.ListenSaver]). Never changed by itself; no safety alert waits on listening.
         */
        var listenSaver: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.listen.saver", false) }.getOrDefault(false)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("jarvis.listen.saver" to v)) } }

        /** Listening runs now (the battery line). */
        fun listeningNow(): Boolean = instance?.get() != null
        /** Listening is resting between turns now ([listenSaver]). */
        fun restingNow(): Boolean = instance?.get()?.resting == true
        /** Listening's cost now (the battery answer): minutes since it started and the recognizer's turns in the last hour; null when not running. */
        fun listenCost(): Pair<Long, Int>? {
            val v = instance?.get() ?: return null
            val now = SystemClock.elapsedRealtime()
            val turns = synchronized(v.turnTimes) { v.turnTimes.count { now - it <= 3_600_000L } }
            return (now - v.startedAt) / 60_000L to turns
        }

        /** The battery saver's hint ([com.optionslab.ira.ListenSaver.HINT]) once, ever: null when not due; said marks it told. */
        fun saverHintOnce(): String? {
            val told = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean(SAVER_TOLD, false) }.getOrDefault(true)
            val h = com.optionslab.ira.ListenSaver.hint(listeningNow(), listenSaver, told) ?: return null
            runCatching { com.optionslab.app.security.SecurePrefs.put(SAVER_TOLD, true) }
            return h
        }
        private const val SAVER_TOLD = "jarvis.listen.saver.told"

        /** Can this phone listen on the device alone? (Android 12+ with an on-device recognizer.) */
        fun available(context: Context): Boolean = com.optionslab.app.BuildConfig.JARVIS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || (googleSpeech && SpeechRecognizer.isRecognitionAvailable(context)) }.getOrDefault(false)

        fun permitted(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Boss's choice (default off): Jarvis listens through the phone's speech service (Google), which may send speech to Google. */
        var googleSpeech: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.voice.google", false) }.getOrDefault(false)
            set(v) { keep(mapOf("jarvis.voice.google" to v), tightening = !v) }

        /**
         * Boss's choice (default off, Boss 4 Oct: "hear only my voice"): with his voice taught, words in any other voice -
         * the TV, people nearby - are ignored, not only for trades.
         */
        var onlyBoss: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.voice.onlyboss", false) }.getOrDefault(false)
            set(v) { keep(mapOf("jarvis.voice.onlyboss" to v), tightening = v) }

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
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("jarvis.voice.pace" to v.coerceIn(0.7f, 1.45f).toString())) } }

        var style: Style
            // A normal male voice by default (the owner's wish, 2026-10-02); a new key, so an earlier choice starts from it.
            get() = runCatching { Style.valueOf(com.optionslab.app.security.SecurePrefs.getString("jarvis.voice.style2") ?: "MAN") }.getOrDefault(Style.MAN)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("jarvis.voice.style2" to v.name)) } }

        /**
         * Cutting in while Jarvis talks: it listens during its own speech (the name or a stop word only,
         * [com.optionslab.ira.BargeIn]). Boss's own setting: on, off, or null - automatic, on only with a headset or
         * echo cancelling ([com.optionslab.ira.CutIn]). An "on" from before is kept. Choosing again clears a phone found
         * to go silent, so Boss can try once more.
         */
        var cutInChoice: Boolean?
            get() = runCatching { val sp = com.optionslab.app.security.SecurePrefs
                com.optionslab.ira.CutIn.load(sp.getString("jarvis.cutin2")) ?: if (sp.getBoolean("jarvis.cutin", false)) true else null }.getOrNull()
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("jarvis.cutin2" to com.optionslab.ira.CutIn.save(v),
                "jarvis.cutin" to null, "jarvis.cutin.silences" to null)) } }

        /** This phone was seen to stop speaking while it listened: the automatic choice stays off. */
        private var cutInSilences: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.cutin.silences", false) }.getOrDefault(false)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("jarvis.cutin.silences" to (if (v) true else null))) } }

        /** Outputs that put Jarvis's voice in Boss's ears, not the room (a Bluetooth headset plays as A2DP or SCO). */
        private val HEADSETS = setOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_HEARING_AID, 26 /* TYPE_BLE_HEADSET, Android 12 */)

        private fun headset(c: Context): Boolean = runCatching {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSETS }
        }.getOrDefault(false)

        /**
         * The echo canceller: applied when our own shared capture feeds the recognizer (we switch it on there); else only
         * the phone's own, which the recognizer may use.
         */
        private fun echo(): com.optionslab.ira.CutIn.Echo {
            val has = runCatching { android.media.audiofx.AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
            if (!has) return com.optionslab.ira.CutIn.Echo.NONE
            val v = instance?.get()
            val shared = v != null && VoiceGuard.supported && VoiceGuard.enrolled && !v.tapFailed
            // A shared capture running now whose canceller did not switch on: only the phone's own.
            val live = v?.tap
            return if (shared && (live == null || live.echoCancelled)) com.optionslab.ira.CutIn.Echo.APPLIED else com.optionslab.ira.CutIn.Echo.AVAILABLE
        }

        /** Is cut-in on now, why, and how soon into his speech listening starts (Boss's choice wins). */
        fun cutInNow(context: Context?): com.optionslab.ira.CutIn.Decision =
            com.optionslab.ira.CutIn.decide(cutInChoice, cutInSilences, context?.let { headset(it) } ?: false, echo())

        /**
         * Muted (the owner's wish, 2026-10-02: "Jarvis, mute"): nothing is spoken - replies, news and questions stay on
         * the screen and in pop-ups - while Jarvis still listens, so "Jarvis, unmute" brings the voice back.
         */
        var muted: Boolean
            get() = (runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.mute", false) }.getOrDefault(false) && muteHolds()) ||
                System.currentTimeMillis() < mutedUntil
            set(v) { keep(if (v) mapOf("jarvis.mute" to true, MUTE_BY to null) else mapOf("jarvis.mute" to false, "jarvis.mute.until" to null, MUTE_BY to null), tightening = v)
                if (v) { instance?.get()?.hush(); JarvisSpeaker.stop() } }

        /** Who muted Jarvis and when ([com.optionslab.ira.VoiceMute]): a voice mute lasts that day only. */
        private const val MUTE_BY = "jarvis.mute.by"
        private val IST_ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Kolkata")

        val muteMark: com.optionslab.ira.VoiceMute.Mark?
            get() = runCatching { com.optionslab.ira.VoiceMute.decode(com.optionslab.app.security.SecurePrefs.getString(MUTE_BY)) }.getOrNull()

        private fun muteHolds(): Boolean = runCatching {
            com.optionslab.ira.VoiceMute.holds(muteMark, java.time.LocalDate.now(IST_ZONE), IST_ZONE)
        }.getOrDefault(true)

        /** Muted by [by] ([heardAs]: a voice mute's own command words), kept with its source and time. */
        fun muteBy(by: com.optionslab.ira.VoiceMute.By, heardAs: String? = null, stopNow: Boolean = true) {
            val mark = com.optionslab.ira.VoiceMute.Mark(by, System.currentTimeMillis(), heardAs)
            keep(mapOf("jarvis.mute" to true, MUTE_BY to com.optionslab.ira.VoiceMute.encode(mark)), tightening = true)
            // ([stopNow] false: the voice mute's own "Muting, Boss..." line, already queued, is let finish.)
            if (stopNow) { instance?.get()?.hush(); JarvisSpeaker.stop() }
        }

        /** "Muted by: voice at 15:01 (heard as 'mute')" for the diagnostics. */
        fun mutedByLine(): String = runCatching { com.optionslab.ira.VoiceMute.diagLine(muted, muteMark, IST_ZONE) }.getOrDefault("Muted by: could not read")

        /**
         * The morning check: yesterday's voice mute ended (it lasts the day only) - cleared, and the line saying so, or
         * null. A typed or Settings mute is left as Boss set it.
         */
        fun morningUnmute(): String? {
            val line = runCatching { com.optionslab.ira.VoiceMute.morningLine(muteMark, java.time.LocalDate.now(IST_ZONE), IST_ZONE) }.getOrNull() ?: return null
            val wasOn = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.mute", false) }.getOrDefault(false)
            keep(mapOf("jarvis.mute" to false, MUTE_BY to null), tightening = false)
            return if (wasOn) line else null
        }

        /** The last line kept on screen instead of said, and why (for "why aren't you speaking?"; never its words). */
        @Volatile private var lastHeld: Pair<Long, String>? = null

        /**
         * A note Jarvis put in the chat by himself, said aloud as its one line when Boss's "Jarvis speaks" choice takes a
         * note of [weight] (important: his positions' alerts, a stop he must check, the order watch stopped; minor: records,
         * paper tests, goals, the plan). False: kept on screen (the reason is kept for the voice check). Words only.
         */
        fun offerNote(text: String, weight: com.optionslab.ira.SpeakChoice.Weight, whole: Boolean = false): Boolean =
            runCatching { announce(text, weight = weight, whole = whole) }.getOrDefault(false)

        /** Robolectric shares static state between tests: the voice's own memory reset. */
        internal fun resetForTest() { lastHeld = null; cutStopTally = com.optionslab.ira.CutStops.Tally(); resetLangAskForTest() }

        /** Boss's cuts over Jarvis: how many, and how soon the voice went silent (durations only - [com.optionslab.ira.CutStops]). */
        @Volatile internal var cutStopTally = com.optionslab.ira.CutStops.Tally()

        /** "Be quiet for 30 minutes": muted until this time (epoch ms), then speaking again by itself. */
        val mutedUntil: Long get() = runCatching { com.optionslab.app.security.SecurePrefs.getString("jarvis.mute.until")?.toLong() }.getOrNull() ?: 0L

        fun muteFor(minutes: Int) {
            // A timed quiet replaces a lasting mute: he speaks again by itself when it ends, as he says.
            keep(mapOf("jarvis.mute" to false,
                "jarvis.mute.until" to (System.currentTimeMillis() + minutes.coerceIn(1, 480) * 60_000L).toString()), tightening = true)
            instance?.get()?.hush(); JarvisSpeaker.stop()
        }

        /** Replies in Hindi (the AI model translates; figures are checked, and English is used when it cannot). */
        var hindi: Boolean
            get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.hindi", false) }.getOrDefault(false)
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("jarvis.hindi" to v)) } }

        /**
         * Boss's own pauses inside his questions (ms, numbers only - never words), for how long his words may stand still
         * before his turn is closed ([com.optionslab.ira.BossPace]). Kept on the phone across restarts.
         */
        @Volatile private var paceKept: List<Long>? = null
        var paceGaps: List<Long>
            get() = paceKept ?: runCatching { com.optionslab.ira.BossPace.load(com.optionslab.app.security.SecurePrefs.getString(PACE_KEY)) }
                .getOrDefault(emptyList()).also { paceKept = it }
            // Written behind (learnPace runs on the main thread at every answer): readable at once, on disk a moment later.
            set(v) { paceKept = v; runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(PACE_KEY to com.optionslab.ira.BossPace.save(v))) } }
        private const val PACE_KEY = "jarvis.voice.pausegaps"

        /**
         * How well the ears are hearing Boss, counts per day ([com.optionslab.ira.Hearing]): turns with and without words,
         * failures by code and hour, restarts, lost turns, the name caught, time to the first words - numbers only, never a
         * word heard. Kept on the phone (the last 8 days), written at most every 2 minutes and when listening stops.
         */
        private const val HEARING_KEY = "jarvis.voice.hearing"
        private val hearingLock = Any()
        private var hearingKept: List<com.optionslab.ira.Hearing.Day>? = null
        private var hearingSavedAt = 0L
        val hearingDays: List<com.optionslab.ira.Hearing.Day>
            get() = synchronized(hearingLock) {
                hearingKept ?: runCatching { com.optionslab.ira.Hearing.load(com.optionslab.app.security.SecurePrefs.getString(HEARING_KEY)) }
                    .getOrDefault(emptyList()).also { hearingKept = it }
            }
        /** Today's date in India, as the hearing counts are kept. */
        fun hearingDay(): String = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString()

        internal fun hearingCount(f: (List<com.optionslab.ira.Hearing.Day>, String) -> List<com.optionslab.ira.Hearing.Day>) {
            val save = synchronized(hearingLock) {
                val d = runCatching { f(hearingDays, hearingDay()) }.getOrNull() ?: return
                hearingKept = d
                val now = SystemClock.elapsedRealtime()
                if (hearingSavedAt == 0L || now - hearingSavedAt > 120_000) { hearingSavedAt = now; d } else null
            }
            save?.let { d -> runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(HEARING_KEY to com.optionslab.ira.Hearing.save(d))) } }
        }

        /**
         * Which English the ears listen in, kept steady ([com.optionslab.ira.ListenLanguage]): the one that last gave
         * words, at most one switch by itself a day - codes and counts only, kept on the phone.
         */
        private const val LANG_KEY = "jarvis.voice.lang"
        @Volatile private var langKept: com.optionslab.ira.ListenLanguage.State? = null
        val langState: com.optionslab.ira.ListenLanguage.State
            get() = langKept ?: runCatching { com.optionslab.ira.ListenLanguage.load(com.optionslab.app.security.SecurePrefs.getString(LANG_KEY)) }
                .getOrDefault(com.optionslab.ira.ListenLanguage.State()).also { langKept = it }
        internal fun langCount(f: (com.optionslab.ira.ListenLanguage.State) -> com.optionslab.ira.ListenLanguage.State) {
            val old = langState
            val new = runCatching { f(old) }.getOrNull() ?: return
            if (new == old) return
            langKept = new
            runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(LANG_KEY to com.optionslab.ira.ListenLanguage.save(new))) }
        }
        /** The time now in India, HH:mm, for the language switch record. */
        internal fun hhmm(): String = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")).let { "%02d:%02d".format(java.util.Locale.ENGLISH, it.hour, it.minute) }
        /** What the ears are listening in now and why (after the hearing answer). */
        fun languageSpoken(): String = runCatching {
            com.optionslab.ira.ListenLanguage.spoken(langState, instance?.get()?.lang, hearingDay(), onDeviceLangs, googleSpeech)
        }.getOrDefault("")
        /**
         * The languages the phone's on-device recognizer has installed (Android 13+ says; null when it cannot), so a
         * switch is never made to an English it lacks - which it may take silently and listen in another (round 20).
         */
        @Volatile internal var onDeviceLangs: List<String>? = null

        /**
         * Round 28: English (India) asked for once (aloud and in the chat) and taken by itself when the phone gets it
         * ([com.optionslab.ira.ListenLanguage.arrival]) - codes and dates only, kept on the phone.
         */
        private const val LANG_ASK_KEY = "jarvis.voice.lang.ask"
        @Volatile private var langAskKept: com.optionslab.ira.ListenLanguage.Ask? = null
        internal val langAsk: com.optionslab.ira.ListenLanguage.Ask
            get() = langAskKept ?: runCatching { com.optionslab.ira.ListenLanguage.loadAsk(com.optionslab.app.security.SecurePrefs.getString(LANG_ASK_KEY)) }
                .getOrDefault(com.optionslab.ira.ListenLanguage.Ask()).also { langAskKept = it }
        internal fun langAskSet(a: com.optionslab.ira.ListenLanguage.Ask) {
            if (a == langAsk) return
            langAskKept = a
            runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(LANG_ASK_KEY to com.optionslab.ira.ListenLanguage.saveAsk(a))) }
        }
        /** When the phone's on-device languages were last read for English (India) (elapsed ms; 0: not yet). */
        @Volatile internal var langCheckedAt = 0L
        /** Robolectric shares static state between tests: the language ask's memory reset. */
        internal fun resetLangAskForTest() { langAskKept = null; langCheckedAt = 0L }

        /** Writes the hearing counts now (listening stopping). */
        internal fun hearingSave() {
            val d = synchronized(hearingLock) { hearingKept } ?: return
            runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(HEARING_KEY to com.optionslab.ira.Hearing.save(d))) }
        }

        /** How long the last spoken reply took, from Boss's last word to Jarvis's first sound (ms), or 0. */
        @Volatile var lastLatencyMs = 0L
        /** This run's answer waits (for the diagnostics). */
        @Volatile var latencies: List<Long> = emptyList()
        /** For the newest of [latencies], in step: the part from the reply handed to the voice to its first sound (ms). */
        @Volatile var voiceLatencies: List<Long> = emptyList()

        /** Jarvis is speaking now. */
        val speakingNow: Boolean get() = instance?.get()?.speaking == true

        /** Times each spoken reply from Boss's words to its own first sound ([com.optionslab.ira.ReplyClock]). */
        private val replyClock = com.optionslab.ira.ReplyClock()
        /** The question whose stages are being timed ([IraHub.askStages]; 0: none). Speed, round 4. */
        @Volatile private var askTimed = 0L

        /**
         * The voice needs the phone's processor now - an answer being worked out or said, or Boss speaking - so the
         * model's rewrite (words for the screen only) does not run ([com.optionslab.ira.ModelYield]). False when Jarvis
         * is not running.
         */
        val busyForModel: Boolean get() {
            val v = instance?.get() ?: return false
            return com.optionslab.ira.ModelYield.voiceBusy(v.speaking, _state.value.mode == Mode.THINKING, v.bossSpeaking())
        }

        /**
         * Waits until the voice is free for the model ([busyForModel]); false when it stayed busy for
         * [com.optionslab.ira.ModelYield.MAX_WAIT_MS] (the rewrite is then dropped: the answer shown stands).
         */
        suspend fun freeForModel(): Boolean {
            var waited = 0L
            while (busyForModel) {
                if (waited >= com.optionslab.ira.ModelYield.MAX_WAIT_MS) return false
                kotlinx.coroutines.delay(250); waited += 250
            }
            return true
        }

        /** When the last sound started (elapsed ms): cut-in listening counts from it ([cutInListen]). */
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
            set(v) { runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("jarvis.voice.name2" to v)) } }

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
         *
         * Speed, round 4 (Boss's diagnostics, 5 Oct: 33.4 s from the answer handed to the voice to its first sound, the model
         * not loaded): the voice never waits for the model to load - only an already-loaded model translates, within
         * [com.optionslab.ira.ModelWait.HINDI_MS]; otherwise (or slower) the answer is said in English at once.
         */
        suspend fun inHindi(english: String): String? {
            if (!hindi || IraModel.state.value.status != IraModel.Status.READY) return null
            val wait = com.optionslab.ira.ModelWait.hindiWait(hindi, IraModel.state.value.loaded)
            if (wait <= 0L) { note("Hindi: the AI model is not loaded, so this answer is said in English (no wait)"); return null }
            val out = kotlinx.coroutines.withTimeoutOrNull(wait) {
                runCatching { IraModel.complete(com.optionslab.ira.Hindi.prompt(english), maxTokens = 200, timeoutMs = wait) }.getOrNull()
            }
            if (out == null) note("Hindi: no translation within %.1f s, said in English".format(java.util.Locale.ENGLISH, wait / 1000.0))
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
    /**
     * Boss's pauses inside this turn (ms between his words changing), learned from once the turn turns out to be his
     * question ([learnPace]): words stopped changing for his own usual pause and a margin, the turn ends (a breath inside
     * his sentence must not cut it, and a quick question is not kept waiting) - [com.optionslab.ira.BossPace].
     */
    private val turnGaps = ArrayList<Long>()
    /** When the pending [finish] is due (elapsed ms; 0: none this turn), so "speech ended" never puts it off. */
    private var finishAt = 0L
    /** Words unchanged this long: a plain question's answer is worked out ahead ([prepareAhead]). */
    private val AHEAD_AFTER_MS = 300L
    private var aheadJob: kotlinx.coroutines.Job? = null
    /**
     * The turn's partial words, a plain question ([com.optionslab.ira.Turn.ahead]): its answer's words worked out now, off
     * the main thread, while the turn closes and the recognizer's final reading is awaited ([IraHub.prepare]). Nothing is
     * said, shown or done from them: the final words go the usual way (the name, lock and voice checks), and the answer
     * worked out ahead is used only when they read as the same question.
     */
    private val prepareAhead = Runnable {
        if (!listening || speaking || turnInSpeech || asking != null) return@Runnable
        val words = turnPartial ?: return@Runnable
        val awake = awake()
        aheadJob?.cancel()
        aheadJob = scope.launch(Dispatchers.Default) { runCatching { com.optionslab.ira.Turn.ahead(words, awake)?.let { IraHub.prepare(it) } } }
    }
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
            // (Not when speech began: the recognizer then did get our audio - a slow reading is not a broken capture.)
            if (listening && tap != null && turnReadyAt > 0 && !turnHeardAny && !turnSpeech && now - turnReadyAt > 12_000 && !speaking) {
                tapFailed = true
                val n = silentShared + 1; silentShared = n
                if (n >= 3) runCatching { com.optionslab.app.security.SecurePrefs.put(TAP_BROKEN, true) }
                note("shared audio gave nothing ($n in a row): back to the phone's own microphone" + if (n >= 3) " for good" else " for now")
                IraActivity.add("Listening switched to the phone's own microphone (the shared one heard nothing).")
                main.removeCallbacks(finish); runCatching { rec?.cancel() }; listening = false; endTap(); turnReadyAt = 0; again(300)
            }
            // Never while Boss is speaking into the turn (a reset then lost his words), unless it is stuck (45 s).
            if (listening && com.optionslab.ira.ModelYield.mayResetTurn(listenedAt, bossSpeaking(), now)) {
                // (Partial words read before the reset still count, as a lost turn does.)
                val lost = com.optionslab.ira.Wake.lostTurn(7, turnPartial, awake())
                note("turn timed out" + if (turnPartial != null) " (read words mid-turn)" else " (nothing read)")
                hear(if (lost != null && !speaking) com.optionslab.ira.Hearing.Kind.HEARD else com.optionslab.ira.Hearing.Kind.LOST,
                    name = lost != null && WAKE.containsMatchIn(lost), saved = true)
                main.removeCallbacks(finish); runCatching { rec?.cancel() }; listening = false; endTap()
                if (lost != null && !speaking) { remember(lost); heard(listOf(lost), recovered = true) } else again()
            }
            if (speaking && now - spokeAt > 60_000) { speaking = false; again() }
            // The mic button's one question was asked and answered (or never came): listening stops again.
            if (oneShot && !speaking && !awake() && !lateWaiting && _state.value.mode != Mode.THINKING && now - talkAt > 14_000) { stopSelf(); return }
            // (Not inside a battery-saver rest: its own restart is already queued.)
            if (!listening && !speaking && !held && _state.value.mode != Mode.THINKING && now >= restUntil) again()
            // Self-healing: no listening turn for 3 minutes (the phone took the microphone, the recognizer died):
            // a new recognizer, noted in the activity log.
            if (Automations.on(Automations.Auto.SELFHEAL) && com.optionslab.ira.VoiceHealth.stuck(now, readyAt, speaking, !held && _state.value.mode != Mode.THINKING) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                readyAt = now
                hearingCount { d, day -> com.optionslab.ira.Hearing.restart(d, day) }
                runCatching { rec?.destroy() }
                rec = runCatching { newRecognizer().also { it.setRecognitionListener(listener) } }.getOrNull()
                listening = false; endTap()
                IraActivity.add("Restarted listening (the microphone had gone quiet).")
                Automations.acted(Automations.Auto.SELFHEAL, "Restarted listening.")
                again()
            }
            // English (India) awaited: the phone's list read again, at most hourly (round 28).
            runCatching { langCheck() }
            main.postDelayed(this, 5_000)
        }
    }
    private val WAKE = Regex("\\bj[ae]rv[ia]s+\\b|\\bjar vis\\b|\\b(jarvish|jarwis|jaarvis|jarviz|jarbis)\\b", RegexOption.IGNORE_CASE)
    @Volatile private var held = false
    /** When the recognizer last opened the microphone (for the self-healing check). */
    @Volatile private var readyAt = SystemClock.elapsedRealtime()
    /** This turn's own capture, shared with the recognizer (Android 13+ with a taught voice), or null. */
    private var tap: VoiceGuard.Tap? = null
    /** [tap] is the call microphone (a cut-in turn without a taught voice), the phone's full call processing on it. */
    private var tapCall = false
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
    /** This turn (opened while Jarvis talked) read only his own words heard back ([com.optionslab.ira.EmptyTurns.echo]). */
    @Volatile private var turnEcho = false
    /** Turns in a row with no words for Jarvis, and the kinds of the last few (for the restart pace and the voice check). */
    @Volatile private var emptyInRow = 0
    @Volatile private var recentTurns: List<com.optionslab.ira.EmptyTurns.Kind> = emptyList()
    /** Battery saver for listening: resting between turns now, and until when (the watchdog does not cut a rest short). */
    @Volatile private var resting = false
    @Volatile private var restUntil = 0L
    /** Battery (round 4): when listening started, and the recognizer's turns over the last hour (times only, never words). */
    private val startedAt = SystemClock.elapsedRealtime()
    private val turnTimes = java.util.ArrayDeque<Long>()
    /** The screen came on: a rest ends at once (registered while listening runs). */
    private var screenOn: android.content.BroadcastReceiver? = null
    /** When it said so (0: not yet), for the time to the first words read ([com.optionslab.ira.Hearing]). */
    @Volatile private var turnSpeechAt = 0L
    /** The current language has given words since listening began: it works, so it is never switched away from. */
    private var langWorks = false
    /** What the recognizer heard last turn (for the voice check), then dropped. */
    private var lastHeard: ShortArray? = null
    /** The action Jarvis asked a yes or no about, heard until [askingUntil]. */
    @Volatile private var asking: Long? = null
    /** A trade needs Boss's own voice for its yes; a command (start, stop...) needs only a yes. */
    private var askingNeedsBoss = true
    @Volatile private var askingUntil = 0L
    /** The words of the yes-or-no question about [asking], asked again when a yes is held ([com.optionslab.ira.AnswerWindow.hold]). */
    private var askingText: String? = null
    /** The request Jarvis last asked about again by name, with more than one waiting ([com.optionslab.ira.Requests.pick]). */
    private var requestFocus: Long? = null

    /**
     * Two or more requests waiting (Boss, 5 Oct): a bare yes or no picks none of them - Jarvis says how many and asks
     * which; a request named is asked about again by itself, and only its next plain yes or no answers it (through the
     * same confirm as ever). True when handled here; false leaves the yes or no to the usual handling.
     */
    private fun manyRequestsWaiting(id: Long, said: String?, yes: Boolean?): Boolean {
        val waiting = runCatching { IraHub.pendingRequests().value }.getOrDefault(emptyList())
        val pick = runCatching { com.optionslab.ira.Requests.pick(said.orEmpty(), yes, id, requestFocus, waiting) }
            .getOrDefault(com.optionslab.ira.Requests.Pick.Pass)
        when (pick) {
            is com.optionslab.ira.Requests.Pick.Pass -> return false
            is com.optionslab.ira.Requests.Pick.Ambiguous -> { note("a yes or no with ${pick.count} requests waiting: none picked"); say(pick.line, "question") }
            is com.optionslab.ira.Requests.Pick.Reask -> {
                // On a locked phone the request is not named aloud: how many wait, and the Requests panel after the unlock.
                if (locked()) { say(com.optionslab.ira.Requests.ambiguousLine(waiting.count { it.voiced }), "question"); return true }
                requestFocus = pick.id
                asking = pick.id; askingText = pick.line; askingNeedsBoss = true; askingUntil = 0
                say(pick.line, "question")
            }
        }
        return true
    }
    /**
     * What Jarvis last finished inviting an answer to ([com.optionslab.ira.AnswerWindow]): his own request's yes-or-no
     * question, or an offer of words (the morning check's "say yes for it"). A yes is only ever for the last one.
     */
    private var lastInvite: com.optionslab.ira.AnswerWindow.Invite? = null
    /** The English given to the recognizer each turn (EXTRA_LANGUAGE) - the one the diagnostics and the voice check name. */
    @Volatile private var lang = "en-US"
    private var triedOtherLanguage = false
    /** "The other English isn't on this phone" put in the activity once per listening, not every four turns. */
    private var missingNoted = false
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
            // The English that last gave words, not afresh each start (Boss, 5 Oct 08:53).
            lang = runCatching { com.optionslab.ira.ListenLanguage.start(langState, hearingDay()) }.getOrDefault("en-US")
            pickLanguage()
            tts = TextToSpeech(this) { status -> main.post { voiceReady = status == TextToSpeech.SUCCESS && pickOfflineVoice() } }
            listen()
            main.postDelayed(watchdog, 5_000)
            // Battery saver for listening: the screen coming on ends a rest at once (a system broadcast; nothing else read).
            if (screenOn == null) screenOn = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { main.post { endRest() } }
            }.also { r ->
                runCatching { ContextCompat.registerReceiver(this, r, android.content.IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED) }
            }
            // The question reader's first use builds all its patterns (the slowest reading of the day): done now, off the
            // main thread, not inside Boss's first question. A reading only: nothing is asked.
            // The same for the patterns the spoken words go through ([com.optionslab.ira.SpokenReply.warm]; words only, dropped),
            // and the voice's own records read once into memory (read only: nothing is noted, changed or acted on).
            scope.launch(Dispatchers.Default) {
                runCatching { com.optionslab.ira.Ask.parse("how is nifty today") }
                runCatching { com.optionslab.ira.SpokenReply.warm() }
                runCatching { IraTools.figureLeadingNow(); IraTools.lengthLearnedNow(); IraTools.clarityShorterNow() }
            }
            // While listening, the slow answers are kept ready so none waits: prices every minute in market hours (every 2
            // with the screen off and nothing held or armed - battery round 10), your
            // account and the trade check every 30 seconds (each every 2 minutes when nothing can use it - rounds 9 and 11).
            // (The model is NOT loaded here any more - root cause, 4 Oct: kept in memory the whole time Jarvis listened,
            // 1 GB and more on 4 cores starved the phone's on-device recognizer, which then heard nothing. It loads only
            // when an answer needs it, and leaves memory after 10 minutes unused.)
            scope.launch(Dispatchers.Default) {
                var n = 0
                var screenLast: Boolean? = null
                while (true) {
                    // Market hours on a trading day (battery round 1: an NSE holiday is not one - before, the clock alone was
                    // read, and a holiday weekday fetched prices every minute and the account every 30 s all session).
                    val open = runCatching { com.optionslab.app.data.Market.isOpen() }.getOrElse {
                        com.optionslab.ira.Market.NIFTY.trading(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))) }
                    val screen = runCatching { getSystemService(android.os.PowerManager::class.java)?.isInteractive }.getOrNull() ?: true
                    // Battery (round 10): a full read under 50 s old (the Ira page's, the feed check's) is shared, and with the
                    // screen off and the words lane quiet (nothing held or armed) one is read every 2 minutes, not every minute
                    // ([com.optionslab.ira.LiveReadPace]). Words only: stops and safety alerts read their own prices.
                    if (open && n % 2 == 0) runCatching {
                        val readsQuiet = !screen && com.optionslab.app.work.Tasks.wordsQuietNow() == true
                        IraHub.refreshIfDue(readsQuiet)
                    }
                    // The account and the trade check need the internet: not tried while offline. Outside market hours nothing
                    // in them moves: every 5 minutes while the screen is on, every 30 with it off (battery round 1; it was every
                    // 5 minutes all night). An answer asked meanwhile reads afresh, as on a low battery.
                    // Battery (round 11): with the screen off and the words lane quiet (nothing held or armed), the account is
                    // read ahead about every 2 minutes, not every 30 s ([com.optionslab.ira.AccountWarmPace]). Words only.
                    // Battery (round 17): market shut and the screen off, not at all (it was every 30 minutes all night and
                    // weekend); at once on the first pass after the screen comes on ([com.optionslab.ira.OffHoursWarmPace]).
                    // Learning (round 31): a quiet pass in market hours near a time Boss usually checks his P&L reads the account
                    // ahead all the same, so his answer then is as of now ([com.optionslab.ira.CheckTimes]). A read only.
                    if (com.optionslab.ira.OffHoursWarmPace.due(open, screen, n, screenLast) && IraHub.online()) runCatching {
                        val accountQuiet = !screen && com.optionslab.app.work.Tasks.wordsQuietNow() == true
                        IraHub.warm(IraTools.checkTimesQuiet(accountQuiet, open))
                    }
                    screenLast = screen
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
            // How far the voice has got (for "go on" after a cut): each piece's start, the word under way where the
            // engine says it, and each piece's end.
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                pieceAt[id ?: return]?.let { (b, _) -> reachedAt = b + start }
            }
            override fun onStart(id: String?) {
                id?.let { pieceAt[it] }?.let { (b, _) -> reachedAt = b }
                val now = SystemClock.elapsedRealtime()
                speechStartedAt = now
                // Only the first sound of the reply to the words just read counts ([com.optionslab.ira.ReplyClock]): an
                // announcement, a reply never said or one from an earlier turn is not timed (Boss, 5 Oct: "37.0 s" against
                // "answer ready 4.8 s" - a loose time taken by a later sound).
                val split = replyClock.startedSplit(id, now)
                val took = split?.total
                if (split != null) runCatching { IraHub.askStages.mark(askTimed, com.optionslab.ira.AskStages.Stage.SPOKEN, now) }
                if (split != null && took != null) { lastLatencyMs = took; latencies = com.optionslab.ira.Latency.add(latencies, took)
                    // Where the wait went (Voice, round 22): the voice's own part - the reply handed to it, to its first sound.
                    voiceLatencies = com.optionslab.ira.Latency.addVoice(voiceLatencies, latencies, took, split.voice)
                    note("first sound %.1f s after the words (voice %.1f s)".format(java.util.Locale.ENGLISH, took / 1000.0, split.voice / 1000.0))
                    // Counted for the day (times only, no words): his weekly goal of answering fast ([com.optionslab.ira.Improve]).
                    if (took in 1L until 60_000L) { IraTools.count(com.optionslab.ira.Improve.TIMED); if (took > com.optionslab.ira.Improve.FAST_MS) IraTools.count(com.optionslab.ira.Improve.SLOW) }
                    slowNudge() }
            }
            override fun onDone(id: String?) {
                id?.let { pieceAt[it] }?.let { (b, n) -> reachedAt = b + n }
                main.post { if (id == utterance) afterSpeech(id?.substringBefore('#'), endedInviting(id)) }
            }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { if (id == utterance) afterSpeech(id?.substringBefore('#'), endedInviting(id)) } }
            override fun onStop(id: String?, interrupted: Boolean) {
                main.post {
                    // Boss's cut: the voice is silent now (the time from his stop read, for the diagnostics).
                    cutSilent()
                    // An offer cut short or replaced was still (in part) said: a yes after it may be meant for it.
                    val cutInvited = endedInviting(id)
                    if (id != utterance) { if (cutInvited) offerEnded(); return@post }   // replaced by a newer sentence
                    if (stoppedByUs) { stoppedByUs = false; if (cutInvited) offerEnded(); return@post }  // Boss cut in
                    // The speech was cut off by something else - listening at the same time, on this phone.
                    if (turnInSpeech) {
                        // Off from now on: Boss's "on" goes back to automatic, which stays off on this phone.
                        if (cutInChoice == true) cutInChoice = null
                        cutInSilences = true; note("cut-in off: speech stopped while listening")
                        _state.value = VoiceState(Mode.LISTENING, problem = "Cutting in is off: this phone stops speaking while it listens.")
                    }
                    afterSpeech(id?.substringBefore('#'), cutInvited)
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
            // Written to disk at once, but on its own thread: this runs on the main thread at every listening turn (a vault
            // write there froze the screen), and a write left behind could be lost if the process died while muted.
            markMuted(st)
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
        markMuted(null)
    }

    /**
     * The muted stream's marker ([MUTED_KEY], read by [restoreMuted] when the service starts again) put on disk
     * synchronously - a full vault write, not a write left behind - on [muteWriter], one at a time in the order set, so
     * a process killed right after the mute still finds it and puts the sound back. Only the newest wish is written.
     */
    private fun markMuted(st: Int?) {
        muteWanted = st?.toString()
        runCatching { muteWriter.execute {
            val want = muteWanted
            if (!muteWrittenKnown || want != muteWritten) {
                if (runCatching { com.optionslab.app.security.SecurePrefs.put(MUTED_KEY, want) }.isSuccess) { muteWritten = want; muteWrittenKnown = true }
            }
        } }
    }
    /** The marker as last wished ([markMuted]); what [muteWriter] last put on disk (touched on that thread only). */
    @Volatile private var muteWanted: String? = null
    private var muteWritten: String? = null
    private var muteWrittenKnown = false
    private val muteWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-mute-marker").apply { isDaemon = true } }

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
        val asked = lang
        runCatching {
            r.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), ContextCompat.getMainExecutor(this),
                object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(s: android.speech.RecognitionSupport) {
                        val installed = s.installedOnDeviceLanguages.toList()
                        onDeviceLangs = installed
                        // The English that last gave words (or today's switch) when the phone has it; else English (US)
                        // first (what heard Boss when it worked, 4 Oct); English (India) when it is the only one.
                        val pick = com.optionslab.ira.ListenLanguage.pick(installed, langState, hearingDay(), googleSpeech)
                        pick?.let { p -> main.post {
                            // Only over the start-up choice (a switch made since is newer), and never silently (round 20:
                            // the activity said English (India) while the recognizer was put back to English (US)).
                            if (lang != asked || p == lang) return@post
                            val was = lang; lang = p
                            note("$was is not on this phone for on-device listening: now listening in $p")
                            langCount { com.optionslab.ira.ListenLanguage.forced(it, was, p, hearingDay(), hhmm(), com.optionslab.ira.ListenLanguage.Why.MISSING) }
                            langState.last?.takeIf { it.to == p && it.from == com.optionslab.ira.ListenLanguage.norm(was) }
                                ?.let { IraActivity.add(com.optionslab.ira.ListenLanguage.line(it)) }
                        } }
                        // English (India): asked for once, taken when it arrives (round 28) - after the pick above.
                        main.post { langArrival(installed) }
                    }
                    override fun onError(error: Int) {}
                })
            langCheckedAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * While English (India) is awaited (seen missing), the phone's on-device list is read again at most hourly
     * ([com.optionslab.ira.ListenLanguage.checkDue]) - on a recognizer of its own, made and dropped for the reading, so
     * the listening one is never disturbed. Called from the watchdog.
     */
    private fun langCheck() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || stopped) return
        val now = SystemClock.elapsedRealtime()
        if (!com.optionslab.ira.ListenLanguage.checkDue(langAsk, langCheckedAt, now, googleSpeech)) return
        langCheckedAt = now
        val probe = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) }.getOrNull() ?: return
        val done = runCatching {
            probe.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), ContextCompat.getMainExecutor(this),
                object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(s: android.speech.RecognitionSupport) {
                        val installed = s.installedOnDeviceLanguages.toList()
                        onDeviceLangs = installed
                        main.post { runCatching { probe.destroy() }; langArrival(installed) }
                    }
                    override fun onError(error: Int) { main.post { runCatching { probe.destroy() } } }
                })
        }.isSuccess
        if (!done) runCatching { probe.destroy() }
    }

    /**
     * The phone's on-device list read: [com.optionslab.ira.ListenLanguage.ASK_LINE] once (aloud and in the chat), or the
     * move to English (India) when it has arrived ([com.optionslab.ira.ListenLanguage.SWITCHED_LINE]). Only a language
     * code changes; never in a run whose English has given words ([langWorks]). Codes only in the trace.
     */
    private fun langArrival(installed: List<String>) {
        if (stopped) return
        val l = com.optionslab.ira.ListenLanguage
        val r = runCatching { l.arrival(langAsk, langState, lang, installed, googleSpeech, hearingDay(), hhmm(), langWorks) }.getOrNull() ?: return
        langAskSet(r.ask)
        r.to?.let { to ->
            val was = lang; lang = to; triedOtherLanguage = false
            langCount { r.state }
            note("$to now on this phone for on-device listening: $was -> $to")
            r.state.last?.let { IraActivity.add(l.line(it)) }
        }
        r.say?.let { text ->
            runCatching { IraHub.note(text) }
            runCatching { announce(text, full = true) }
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
        // Voice, round 27: lean the ears towards Jarvis's own trading words (Android 13+, RecognizerIntent.EXTRA_BIASING_STRINGS by its key; recognizers that ignore it are unchanged).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runCatching {
            i.putStringArrayListExtra("android.speech.extra.BIASING_STRINGS", ArrayList<String>(com.optionslab.ira.ListenBias.words()))
        }
        // With a taught voice (Android 13+): our own capture feeds the recognizer, so the words are also voice-checked.
        // A turn opened while Jarvis speaks (cut-in) also hears through our own capture, with the phone's echo canceller
        // on it (Boss, 5 Oct: the recognizer's own microphone heard his voice over Boss's "stop" and read nothing).
        endTap()
        val echoOk = runCatching { android.media.audiofx.AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
        val enrolledNow = VoiceGuard.enrolled
        if (com.optionslab.ira.CutIn.ownCapture(turnInSpeech, Build.VERSION.SDK_INT, echoOk, enrolledNow, tapFailed) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Without a taught voice no voice print is read from it: the call source, whose echo cancelling every phone applies.
            val src = if (enrolledNow) android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION else android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
            // Battery (round 13): its echo canceller on only while Jarvis talks ([com.optionslab.ira.CaptureEcho]) - a
            // taught voice's turns all night no longer run it with nothing playing.
            tap = runCatching { VoiceGuard.Tap(src, com.optionslab.ira.CaptureEcho.on(turnInSpeech)) }.getOrNull()
            tapCall = tap != null && !enrolledNow
            if (tap == null) tapFailed = true                // the microphone could not be shared: do not retry each turn
            tap?.let { t ->
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, t.read)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, com.optionslab.ira.VoicePrint.RATE)
            }
        }
        listening = true
        turnReadyAt = 0; turnHeardAny = false; turnLoudest = -100f; turnPartial = null; turnPartialAt = 0L; turnEndAt = 0L; turnSpeech = false; turnSpeechAt = 0L; answeredEarly = false; finishAt = 0L; turnEcho = false
        turnGaps.clear()
        hushBeep(1_500)                                   // the start beep (put back once the turn is ready, or in 1.5 s)
        runCatching { rec?.startListening(i) }.onFailure { listening = false; endTap(); again(1_000) }
        _state.value = VoiceState(if (awake()) Mode.AWAKE else Mode.LISTENING)
    }

    private fun again(delayMs: Long = 250) { if (!stopped) main.postDelayed({ listen() }, delayMs) }

    /**
     * Battery saver for listening ([listenSaver], off by default): [base] as before, or a rest of a few seconds between quiet
     * turns while the screen is off, the market is shut, it is not one of Boss's usual hours and the room has been quiet a
     * while ([com.optionslab.ira.ListenSaver]). Never while Boss is [expected] to speak. Kinds only in the trace, never words.
     */
    private fun restGap(base: Long, expected: Boolean): Long {
        val gap = if (!listenSaver || expected || emptyInRow < com.optionslab.ira.EmptyTurns.SLOW_AFTER) base
            else runCatching { com.optionslab.ira.ListenSaver.gap(base, saverNow(expected)) }.getOrDefault(base)
        val rest = gap > base
        if (rest != resting) note(if (rest) "battery saver: resting ${gap / 1000} s between quiet turns (screen off, market shut)" else "battery saver: listening as usual again")
        resting = rest
        restUntil = if (rest) SystemClock.elapsedRealtime() + gap else 0L
        return gap
    }

    private fun saverNow(expected: Boolean): com.optionslab.ira.ListenSaver.Now {
        val now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))
        // Unknown counts as "not resting": the screen as on, the market as open, the hour as one of Boss's.
        val screen = runCatching { getSystemService(android.os.PowerManager::class.java)?.isInteractive }.getOrNull() ?: true
        val open = runCatching { com.optionslab.app.data.Market.isOpen() }.getOrDefault(true)
        val talk = runCatching {
            val r = com.optionslab.ira.TalkHours.record(IraTools.talkLog(), now)
            r.learned && com.optionslab.ira.TalkHours.inHours(r.hours, now)
        }.getOrDefault(true)
        return com.optionslab.ira.ListenSaver.Now(on = true, screenOn = screen, marketOpen = open, talkHour = talk,
            night = com.optionslab.ira.Quiet.now(now.toLocalTime()), emptyInRow = emptyInRow, expected = expected,
            batteryLow = com.optionslab.app.work.Battery.saving(this))
    }

    /** The screen came on: a battery-saver rest ends at once, so Boss never waits on it when he picks the phone up. */
    private fun endRest() {
        if (restUntil == 0L) return
        val wasResting = SystemClock.elapsedRealtime() < restUntil
        restUntil = 0L; resting = false
        if (wasResting) { note("screen on: battery-saver rest ended"); if (!listening && !speaking && !held) again(0) }
    }

    /** Ends this turn's capture, keeping what it heard for the voice check. */
    private fun endTap() {
        tap?.let { t -> lastHeard = t.heard(); t.close() }
        tap = null; tapCall = false
    }

    /** Was the last thing heard said by the owner? (False without a taught voice or a shared capture.) */
    private fun boss(): Boolean = VoiceGuard.isBoss(lastHeard).also { lastHeard = null }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            errorsInRow = 0; clientErrors = 0; readyAt = SystemClock.elapsedRealtime(); turnReadyAt = readyAt; turnHeardAny = false
            if (mutedForBeep.isNotEmpty()) { main.removeCallbacks(unmuteBeep); main.postDelayed(unmuteBeep, 400) }
            note(if (tap != null) "ready (shared audio)" else "ready")
        }
        override fun onBeginningOfSpeech() {
            // Once per turn in the trace (a recognizer that says it again is not a second speaker).
            if (!turnSpeech) note("speech began" + if (speaking || turnInSpeech) " (while I spoke)" else "")
            turnSpeech = true; if (turnSpeechAt == 0L) turnSpeechAt = SystemClock.elapsedRealtime()
            // Boss is speaking: a rewrite under way stops, so the recognizer has the processor (5 Oct: 7-9 s of his speech
            // read as nothing while the model was writing).
            if (!speaking && !turnInSpeech) runCatching { IraModel.yieldToVoice() }
        }
        override fun onRmsChanged(rmsdB: Float) { if (rmsdB > turnLoudest) turnLoudest = rmsdB }
        override fun onBufferReceived(buffer: ByteArray?) {}
        // Boss, 4 Oct: "speech began", then nothing - the recognizer never closed the turn. Once he stops speaking, the
        // turn is closed for it 0.7 s later (was 1.5 s: Boss, 4 Oct, "late response") (stopListening makes it give its result), not left to a 25 s reset.
        // A close already due sooner (the words stood still) is kept, never put off (Boss, 5 Oct: speed).
        override fun onEndOfSpeech() {
            // Boss, 5 Oct 09:57:05: "speech ended" logged twice in one turn - the recognizer says it again when our
            // close (stopListening) reaches it after its own end of speech. Only the first counts: once in the trace, and
            // the close already set for it is not set again.
            if (turnEndAt != 0L) return
            note("speech ended")
            val now = SystemClock.elapsedRealtime()
            turnEndAt = now
            hushBeep(1_200)                               // the end beep
            if (!speaking) {
                val wait = com.optionslab.ira.Turn.closeIn(now, finishAt)
                main.removeCallbacks(finish); finishAt = now + wait; main.postDelayed(finish, wait)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            // Only words that changed restart the end-of-turn wait (a recognizer repeating the same reading pushed it back).
            var fresh = false
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }?.takeIf {
                // A turn opened while Jarvis talked (cut-in): his own words heard back are not the turn's words - never
                // "read mid-turn", never kept as a lost turn's question, never closing the turn (Boss, 5 Oct).
                val own = (speaking || turnInSpeech) && com.optionslab.ira.EmptyTurns.echo(it, sayingText)
                if (own) { turnEcho = true; turnHeardAny = true }
                !own
            }?.let {
                fresh = com.optionslab.ira.Turn.changed(turnPartial, it)
                if (fresh) {
                    val now = SystemClock.elapsedRealtime()
                    // A pause inside his speech (more words followed it); kept only if the turn proves to be his question.
                    if (turnPartialAt > 0 && !speaking && !turnInSpeech) turnGaps += now - turnPartialAt
                    turnPartialAt = now
                }
                // How soon the first words of Boss's speech are read (a time only, never the words).
                if (!turnHeardAny && turnSpeechAt > 0 && !speaking && !turnInSpeech) {
                    val ms = SystemClock.elapsedRealtime() - turnSpeechAt
                    hearingCount { d, day -> com.optionslab.ira.Hearing.firstWords(d, day, ms) }
                }
                turnHeardAny = true; turnPartial = it
                if (tap != null && silentShared != 0) silentShared = 0      // the shared capture does hear
            }
            // Boss talks over Jarvis (Boss, 5 Oct): read from the partial words, so the voice stops as they are read, not
            // at the end of his turn. "Jarvis" stops it and the question follows in this turn; a clear stop word ("stop",
            // "bas", "ok ok", "next") without the name stops it and listens again at once. Jarvis's own words heard back
            // never count ([com.optionslab.ira.BargeIn]): he never says his name, and a stop word he is saying is his.
            val words = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            // Every reading counts (not only the first), and a stop is read from the newest words, so a misread word of
            // his own before it no longer hides it (Boss, 5 Oct: "Jarvis stop" did not stop him).
            if (speaking) {
                val readNow = SystemClock.elapsedRealtime()
                val cut = if (words.any { WAKE.containsMatchIn(it) }) com.optionslab.ira.BargeIn.Cut.NAME
                    else com.optionslab.ira.BargeIn.cutAny(words, sayingText)
                when (cut) {
                    com.optionslab.ira.BargeIn.Cut.NAME -> { note("cut in by name"); cutHeard(readNow); interrupt() }
                    com.optionslab.ira.BargeIn.Cut.HUSH -> hushIfBoss(readNow)
                    null -> {}
                }
                return
            }
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
                val wait = if (nameOnly) 1_800L else com.optionslab.ira.BossPace.endAfter(paceGaps)
                finishAt = SystemClock.elapsedRealtime() + wait
                main.postDelayed(finish, wait)
                main.removeCallbacks(prepareAhead)
                if (!nameOnly && asking == null) main.postDelayed(prepareAhead, AHEAD_AFTER_MS)
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            main.removeCallbacks(finish)
            if (answeredEarly) { answeredEarly = false; return }      // already answered from its partial words
            listening = false
            loudNoMatch = 0
            if (results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.any { it.isNotBlank() } == true) {
                langWorks = true; val l = lang; langCount { com.optionslab.ira.ListenLanguage.gaveWords(it, l, hearingDay()) }
            }
            val readings = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            // How sure the recognizer was of its best reading (many on-device ones give no score: then null, as before).
            val sure = com.optionslab.ira.Sure.best(results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES), readings.size)
            note("heard " + (readings.firstOrNull()?.let { t ->
                if (WAKE.containsMatchIn(t)) "the name" else "${t.split(Regex("\\s+")).size} words, no name" } ?: "nothing") +
                ", " + com.optionslab.ira.Sure.say(sure))
            val first = readings.firstOrNull { it.isNotBlank() }
            when {
                first != null && !turnInSpeech -> hear(com.optionslab.ira.Hearing.Kind.HEARD, name = WAKE.containsMatchIn(first))
                first == null && turnSpeech && !turnInSpeech -> hear(com.optionslab.ira.Hearing.Kind.NO_MATCH, loud = turnLoudest >= 6f)
                else -> hear(com.optionslab.ira.Hearing.Kind.SILENT)
            }
            readings.firstOrNull()?.let { remember(it) }
            errorsInRow = 0
            // A final reading in a cut-in turn that is only his own words is no words for him (his own voice).
            val ownOnly = first != null && turnInSpeech && com.optionslab.ira.EmptyTurns.echo(first, sayingText)
            if (ownOnly) turnEcho = true
            countTurn(first != null && !ownOnly)
            endTap()
            heard(readings, sure = sure)
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
                hear(com.optionslab.ira.Hearing.Kind.HEARD, name = WAKE.containsMatchIn(partial), saved = true)
                errorsInRow = 0; loudNoMatch = 0
                countTurn(true)
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
                hear(com.optionslab.ira.Hearing.Kind.ERROR, code = error)
                if (error == 11 || error == SpeechRecognizer.ERROR_CLIENT) {
                    hearingCount { d, day -> com.optionslab.ira.Hearing.restart(d, day) }
                    runCatching { rec?.destroy() }
                    rec = runCatching { newRecognizer().also { it.setRecognitionListener(this) } }.getOrNull()
                }
                again(500); return
            }
            // The loudest sound says whether the microphone gave the recognizer anything (Boss, 4 Oct: error 7 each turn).
            note("error $error" + (if (turnLoudest > -100f) ", loudest %.0f dB".format(java.util.Locale.ENGLISH, turnLoudest) else ", no sound level") +
                (turnPartial?.let { ", read ${it.trim().split(Regex("\\s+")).size} word(s) mid-turn" } ?: if (turnEcho) ", read only my own voice" else ", read nothing") +
                (if (turnInSpeech) " (opened while I spoke)" else ""))
            // The kind of this empty turn (TV, his own voice, a quiet room) for the restart pace and the voice check.
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) countTurn(false)
            // Clear speech, no words found, four turns in a row: the language pack may be the trouble - the other English.
            // Only turns where speech began, and never away from a language that has given words (Boss, 4 Oct: a quiet
            // room at 6-8 dB switched a working en-US to the missing en-IN, and listening stopped).
            // Round 17 (Boss, 5 Oct 08:53): 4 in a row, at most one such switch a day, never away from the English that
            // last gave words (kept on the phone, so a fresh start no longer forgets it) - [com.optionslab.ira.ListenLanguage].
            // (Never a turn opened while Jarvis spoke: his own voice heard back says nothing about the language - 5 Oct.)
            if (error == SpeechRecognizer.ERROR_NO_MATCH && turnSpeech && !turnInSpeech && turnLoudest >= 6f && !langWorks) {
                // Never to an English the phone's on-device recognizer lacks (round 20): it may take the code and go on
                // listening in another English, so the activity would say one and the ears use the other.
                val d = runCatching { com.optionslab.ira.ListenLanguage.clearNoWords(langState, lang, hearingDay(), hhmm(), ++loudNoMatch, onDeviceLangs, googleSpeech) }.getOrNull()
                if (d != null && (d.to != null || d.held)) {
                    loudNoMatch = 0
                    langCount { d.state }
                    val to = d.to
                    if (to != null) {
                        lang = to
                        note("words not found in clear speech ${com.optionslab.ira.ListenLanguage.CLEAR_IN_ROW} times: now listening in $lang")
                        d.state.last?.let { IraActivity.add(com.optionslab.ira.ListenLanguage.line(it)) }
                    } else if (d.missing) {
                        note("words not found in clear speech: kept $lang (the other English is not on this phone)")
                        if (!missingNoted) { missingNoted = true; IraActivity.add(com.optionslab.ira.ListenLanguage.heldLine(lang)) }
                    } else note("words not found in clear speech: kept $lang (a steady English)")
                }
            } else if (error != SpeechRecognizer.ERROR_NO_MATCH) loudNoMatch = 0
            // His ears' own counts (numbers only): no words in Boss's speech, silence, or a failure of the speech service
            // (our own close racing the results is none of them).
            when {
                error == SpeechRecognizer.ERROR_CLIENT && SystemClock.elapsedRealtime() - stoppedAt < 3_000 -> {}
                error == SpeechRecognizer.ERROR_NO_MATCH && turnSpeech && !turnInSpeech -> hear(com.optionslab.ira.Hearing.Kind.NO_MATCH, loud = turnLoudest >= 6f)
                error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> hear(com.optionslab.ira.Hearing.Kind.SILENT)
                else -> hear(com.optionslab.ira.Hearing.Kind.ERROR, code = error)
            }
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> giveUp("Jarvis lost the microphone permission.")
                // Boss, 4 Oct ("why can't you hear me" -> "the speech service refused the request"): the on-device
                // recognizer is left in a bad state - it is made anew at once (not after 3 failures) and given a second
                // before the next turn; when the phone has no on-device recognition at all, he says what to install.
                // 11: the speech service crashed or restarted - the recognizer is dead; made anew at once (Boss, 4 Oct).
                11 -> {
                    errorsInRow++; lastError = error to SystemClock.elapsedRealtime()
                    hearingCount { d, day -> com.optionslab.ira.Hearing.restart(d, day) }
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
                    val was = lang
                    // (Never to an English the phone is known to lack - round 20.)
                    val otherLang = if (lang == "en-IN") "en-US" else "en-IN"
                    if (!triedOtherLanguage && !langWorks && com.optionslab.ira.ListenLanguage.canTry(otherLang, onDeviceLangs, googleSpeech)) {
                        triedOtherLanguage = true; lang = otherLang
                        IraActivity.add("Listening in $lang (the speech service refused the other English).")
                    } else if (lang == "en-IN" && !langWorks) {
                        lang = "en-US"; IraActivity.add("Listening in $lang (the speech service refused English (India)).")
                    }
                    if (lang != was) { val to = lang; langCount { com.optionslab.ira.ListenLanguage.forced(it, was, to, hearingDay(), hhmm(), com.optionslab.ira.ListenLanguage.Why.REFUSED) } }
                    if (clientErrors >= 6 && runCatching { !SpeechRecognizer.isOnDeviceRecognitionAvailable(this@JarvisVoice) }.getOrDefault(false)) {
                        giveUp("This phone has no on-device speech recognition ready: update \"Speech Services by Google\" in the Play Store, then add English under Settings, System, Languages, On-device speech recognition.")
                        return
                    }
                    hearingCount { d, day -> com.optionslab.ira.Hearing.restart(d, day) }
                    runCatching { rec?.destroy() }
                    rec = runCatching { newRecognizer().also { it.setRecognitionListener(this) } }.getOrNull()
                    if (clientErrors == 1) IraActivity.add("Restarted listening (the speech service refused a request).")
                    again(if (clientErrors <= 3) 1_000L else minOf(MAX_BACKOFF_MS, 1_000L shl minOf(clientErrors - 3, 5)))
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    // The other English is missing on this phone: back to English (US) at once, never deaf for it.
                    if (lang != "en-US") {
                        val was = lang; lang = "en-US"; triedOtherLanguage = true; note("$lang back: the other English is not on this phone")
                        langCount { com.optionslab.ira.ListenLanguage.forced(it, was, "en-US", hearingDay(), hhmm(), com.optionslab.ira.ListenLanguage.Why.MISSING) }
                        again()
                    }
                    else if (!triedOtherLanguage && com.optionslab.ira.ListenLanguage.canTry("en-IN", onDeviceLangs, googleSpeech)) {
                        triedOtherLanguage = true; lang = "en-IN"
                        langCount { com.optionslab.ira.ListenLanguage.forced(it, "en-US", "en-IN", hearingDay(), hhmm(), com.optionslab.ira.ListenLanguage.Why.MISSING) }
                        again()
                    }
                    else giveUp("No on-device English speech model: add one in the phone's Settings (System → Languages → On-device speech recognition).")
                else -> {
                    // Silence and no-match are normal between sentences; a run of other errors backs off up to 5 s.
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { errorsInRow++; lastError = error to SystemClock.elapsedRealtime() }
                    // A recognizer stuck failing (busy, client, server errors) beeped on every retry, once a second, until
                    // listening was switched off and on (Boss, 4 Oct): after 3 failures in a row it is made anew - what the
                    // switch did - and further failures wait longer, up to 30 s.
                    if (errorsInRow == 3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        hearingCount { d, day -> com.optionslab.ira.Hearing.restart(d, day) }
                        runCatching { rec?.destroy() }
                        rec = runCatching { newRecognizer().also { it.setRecognitionListener(this) } }.getOrNull()
                        IraActivity.add("Restarted listening (the speech recognizer kept failing).")
                    }
                    // A long run of empty turns (a quiet room, the TV): the next turn starts a little later - fewer restarts
                    // and start beeps - never while Boss is expected to speak; still listening for "Jarvis".
                    val expected = awake() || asking != null || speaking
                    again(if (errorsInRow == 0) restGap(com.optionslab.ira.EmptyTurns.restartIn(emptyInRow, expected), expected)
                        else minOf(MAX_BACKOFF_MS, 250L shl minOf(errorsInRow, 7)))
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
        countTurn(true)
        run { val l = lang; langCount { com.optionslab.ira.ListenLanguage.gaveWords(it, l, hearingDay()) } }
        note("heard " + (if (WAKE.containsMatchIn(words)) "the name" else "${words.trim().split(Regex("\\s+")).size} words, no name") +
            " (answered from the partial words; the final reading not waited for)")
        hear(com.optionslab.ira.Hearing.Kind.HEARD, name = WAKE.containsMatchIn(words))
        remember(words)
        endTap()
        heard(listOf(words))
    }

    /**
     * This turn counted for the restart pace and the voice check ([com.optionslab.ira.EmptyTurns]): [words] read for
     * Jarvis, else its own words only, speech with no words, or none. Kinds only, never a word.
     */
    private fun countTurn(words: Boolean) {
        val k = com.optionslab.ira.EmptyTurns.kind(words, turnEcho && turnPartial == null, turnSpeech)
        emptyInRow = com.optionslab.ira.EmptyTurns.inRow(emptyInRow, k)
        if (emptyInRow == 0) { resting = false; restUntil = 0L }      // words for Jarvis: no battery-saver rest
        recentTurns = com.optionslab.ira.EmptyTurns.add(recentTurns, k)
        val t = SystemClock.elapsedRealtime()
        synchronized(turnTimes) { turnTimes.addLast(t); while (turnTimes.isNotEmpty() && t - turnTimes.first() > 3_600_000L) turnTimes.removeFirst() }
        if (emptyInRow == com.optionslab.ira.EmptyTurns.CALM_AFTER || emptyInRow == com.optionslab.ira.EmptyTurns.SLOW_AFTER)
            note("$emptyInRow turns with no words in a row: listening restarts a little slower (still for \"Jarvis\")")
    }

    /** This turn was Boss's question: its pauses tell how long his words may stand still ([com.optionslab.ira.BossPace]). */
    private fun learnPace() {
        if (turnGaps.isEmpty()) return
        var g = paceGaps
        for (x in turnGaps) g = com.optionslab.ira.BossPace.add(g, x)
        turnGaps.clear()
        paceGaps = g
    }

    /**
     * One turn of the ears counted ([com.optionslab.ira.Hearing], numbers only - never the words); after a failure, a
     * clear problem against his usual days is put to Boss as a suggestion in the chat, once a day. It only ever SUGGESTS
     * (move closer, a headset, the phone's speech language, Google's speech in Settings): nothing is switched here.
     */
    private fun hear(kind: com.optionslab.ira.Hearing.Kind, code: Int? = null, loud: Boolean = false, name: Boolean = false, saved: Boolean = false) {
        val hour = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")).hour
        hearingCount { d, day -> com.optionslab.ira.Hearing.turn(d, day, hour, kind, code, loud, name, saved) }
        if (kind != com.optionslab.ira.Hearing.Kind.HEARD && kind != com.optionslab.ira.Hearing.Kind.SILENT) hearingNudge()
    }

    private fun hearingNudge() {
        val day = hearingDay()
        if (runCatching { com.optionslab.app.security.SecurePrefs.getString("jarvis.hearing.told") }.getOrNull() == day) return
        val text = runCatching { com.optionslab.ira.Hearing.nudge(hearingDays, day, googleSpeech) }.getOrNull() ?: return
        runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.hearing.told", day) }
        hearingSave()
        runCatching { IraHub.note(text) }
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

    /**
     * A stop word over Jarvis, without the name. With "only my voice" on and this turn's audio shared, the last two
     * seconds must be Boss's voice (checked off the main thread); with no shared audio there is nothing to check, as for
     * any words. Only the voice stops: nothing else is done.
     */
    private fun hushIfBoss(readAt: Long = SystemClock.elapsedRealtime()) {
        val t = tap
        if (t == null || !onlyBoss || !VoiceGuard.enrolled) { hushCut(readAt); return }
        if (hushChecking) return
        hushChecking = true
        val pcm = t.heard()
        scope.launch {
            val his = withContext(Dispatchers.Default) {
                runCatching { VoiceGuard.isBoss(pcm.copyOfRange(maxOf(0, pcm.size - com.optionslab.ira.VoicePrint.RATE * 2), pcm.size)) }.getOrDefault(false)
            }
            hushChecking = false
            if (!his) note("a stop word in another voice: not taken")
            else if (speaking) hushCut(readAt)
        }
    }
    @Volatile private var hushChecking = false

    /**
     * "Stop" / "bas" / "ok ok" over Jarvis (read at [readAt]): the voice stops, this turn is dropped, and listening starts
     * again at once. Only the voice: never an order, a strategy or anything else.
     */
    private fun hushCut(readAt: Long) {
        if (!speaking) return
        note("cut in by a stop word")
        cutHeard(readAt)
        runCatching { IraTools.noteHush() }
        interrupt()
        answerJob?.cancel(); replyClock.drop()
        main.removeCallbacks(finish); main.removeCallbacks(prepareAhead)
        stoppedAt = SystemClock.elapsedRealtime()
        runCatching { rec?.cancel() }; listening = false; endTap(); lastHeard = null
        // The follow-up window, as after an answer: "go on" or a next question without the name may only ask.
        awakeUntil = SystemClock.elapsedRealtime() + FOLLOW_MS; called = false
        _state.value = VoiceState(Mode.AWAKE)
        again(50)
    }

    /** Cut in on: Jarvis stops talking and waits for the owner's question. */
    private fun interrupt() {
        if (!speaking) return
        val now = SystemClock.elapsedRealtime()
        // An answer cut off: the rest is kept for "go on" (its words only - the chat already shows them).
        if (lastSpokenId == "answer") cutOff = com.optionslab.ira.BargeIn.cutOff(sayingFull, sayingText, reachedAt, sayingAccount, now)
        // Its own words heard back right after a cut are still its own (the echo check counts from here).
        lastSpokenEnd = now
        speaking = false
        // The utterance is forgotten: its onStop is then not ours to judge, and a reply still being prepared (Hindi) is not said.
        utterance = null
        // Every piece still queued goes with it (stop() flushes the speech engine's queue, not only the piece under way).
        runCatching { tts?.stop() }
        // A cut heard: if the engine never reports the stop ([cutSilent] from onStop), the time stop() took is kept instead.
        val readAt = cutReadAt
        if (readAt > 0L) {
            val returned = SystemClock.elapsedRealtime() - readAt
            main.postDelayed({ if (cutReadAt == readAt) { cutReadAt = 0L; cutStopTally = com.optionslab.ira.CutStops.silent(cutStopTally, returned) } }, 1_500)
        }
        awakeUntil = SystemClock.elapsedRealtime() + AWAKE_MS
        _state.value = VoiceState(Mode.AWAKE)
    }

    /** When Boss's cut being acted on was read (elapsed ms; 0: none waiting for the voice to fall silent). */
    @Volatile private var cutReadAt = 0L

    /** Boss's cut over Jarvis (the name or a stop word), read at [readAt]: counted, and timed until the voice is silent. */
    private fun cutHeard(readAt: Long) {
        cutStopTally = com.optionslab.ira.CutStops.heard(cutStopTally)
        cutReadAt = readAt
    }

    /** The voice fell silent after a cut ([cutHeard]): the time from the cut read, durations only. */
    private fun cutSilent() {
        val at = cutReadAt
        if (at <= 0L) return
        cutReadAt = 0L
        cutStopTally = com.optionslab.ira.CutStops.silent(cutStopTally, SystemClock.elapsedRealtime() - at)
    }

    /** When Boss's last words were heard (for the reply time). */
    @Volatile private var heardAt = 0L

    /**
     * [sure]: the recognizer's score for its best reading ([com.optionslab.ira.Sure]; null: none, or words read mid-turn).
     * A faint one only ever lets words go (a follow-up without the name, a soft misreading of the name, a yes).
     */
    private fun heard(alternatives: List<String>, recovered: Boolean = false, sure: Float? = null) {
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
            if (alternatives.none { WAKE.containsMatchIn(it) }) {
                // A stop word over him read only in the final reading (the partials missed it): the voice stops all the
                // same - and only the voice ([com.optionslab.ira.BargeIn]); anything else without the name is let go.
                if (speaking && com.optionslab.ira.BargeIn.cutAny(alternatives, sayingText) == com.optionslab.ira.BargeIn.Cut.HUSH) { hushIfBoss(); return }
                again(); return
            }
            if (speaking) cutHeard(SystemClock.elapsedRealtime())
            interrupt()
        }
        // The answer to Jarvis's yes-or-no question: only the recognizer's best reading, and anything unclear is not a yes.
        val id = asking
        if (id != null && SystemClock.elapsedRealtime() < askingUntil) {
            val yes = alternatives.firstOrNull()?.let { Wake.yesNo(it) }
            // A yes or no here is Boss's next words: the morning offer and the wait for a turn-down reason end (the "no"
            // below may start a new wait for its reason).
            if (yes != null) runCatching { IraTools.endWaits() }
            // An offer said after the question ("... say yes for it"): this yes or no may be for the offer, so it is not
            // taken for the request - never approved by a yes meant for something else ([com.optionslab.ira.AnswerWindow]).
            // Said so, with the request's own question asked again: the next yes or no is the request's.
            if (yes != null && com.optionslab.ira.AnswerWindow.yesFor(true, lastInvite) == com.optionslab.ira.AnswerWindow.For.HOLD) {
                note("a yes or no after another offer: not taken for the waiting request")
                // Its own question asked again, so the next yes or no answers that very question.
                say(com.optionslab.ira.AnswerWindow.hold(askingText), "question")
                return
            }
            if (manyRequestsWaiting(id, alternatives.firstOrNull(), yes)) return
            if (yes != null) {
                // A faint yes (the room, the TV) is not a yes: anything unclear is not a yes. The question stays open.
                if (yes && com.optionslab.ira.Sure.faintYes(sure)) { note("a faint yes (${com.optionslab.ira.Sure.say(sure)}): not taken"); again(); return }
                // A locked phone: a "no" still cancels, a "yes" never acts (trades and commands wait for the unlock).
                if (yes && locked()) { say(com.optionslab.ira.LockRule.refuse(true, true, false, false)!!, "question"); return }
                // Only Boss's voice approves a trade; a no from anyone is still a no.
                // "Answer only my voice": a yes in another voice never approves anything (a no from anyone still cancels).
                if (yes && onlyBoss && VoiceGuard.enrolled && lastHeard != null && !VoiceGuard.isBoss(lastHeard)) { note("a yes in another voice: ignored"); again(); return }
                if (yes && askingNeedsBoss && !boss()) { say(VoiceGuard.blocked() ?: "Boss, that didn't sound like you, so I won't place it. Say yes again, or tap Approve.", "question"); return }
                // Whether a "no"'s words may be read for Boss's reason: his voice when enrolled (judged now, on this turn's audio).
                val bossNo = !yes && (!VoiceGuard.enrolled || (lastHeard != null && VoiceGuard.isBoss(lastHeard)))
                asking = null
                _state.value = VoiceState(Mode.THINKING)
                scope.launch {
                    // The emergency exit takes Boss's own voice in place of the fingerprint (checked just above).
                    val r = if (yes) withContext(Dispatchers.Default) { IraHub.confirm(id, ownerVoice = askingNeedsBoss && IraHub.isExit(id)) } ?: IraHub.alreadyLine(id, "That had already lapsed; nothing was placed.")
                        // (His words for the no go with it: a reason in them, "no, too late in the day", is noted - its kind
                        // only, and only in Boss's own voice when it is enrolled - a no with no voice heard is not his reason; a
                        // no from anyone still cancels.)
                        else { IraHub.cancelAction(id, said = alternatives.firstOrNull()?.takeIf { bossNo }); "Rejected. Nothing was placed." }
                    // An order's result is read back as it is: its prices exact, never rounded for the ear.
                    say(com.optionslab.ira.Address.boss(com.optionslab.ira.Wake.spoken(r)), "answer")
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
            // A faint soft misreading of the name ("service" from the TV, no name in any reading) does not wake him.
            Wake.Heard.Awake -> if (com.optionslab.ira.Sure.faint(sure, named = alternatives.any { WAKE.containsMatchIn(it) }, called = false)) {
                note("a faint word like my name (${com.optionslab.ira.Sure.say(sure)}): let go"); again()
            } else { awakeUntil = SystemClock.elapsedRealtime() + AWAKE_MS; called = alternatives.any { WAKE.containsMatchIn(it) }; say("Yes, Boss?") }
            Wake.Heard.Stop -> if (alternatives.none { WAKE.containsMatchIn(it) }) again() else { wanted = false; say("Going to sleep, Boss. Switch me on again in the app.", STOP_AFTER) }
            // "Jarvis, stop" / "enough" / "quiet": it has stopped talking (the name cut in); nothing else is done.
            // Said over him (cut-in): the follow-up window stays open, so a bare "go on" says the rest, as after "stop" alone.
            Wake.Heard.Hush -> { if (speaking) runCatching { IraTools.noteHush() }; interrupt(); answerJob?.cancel(); replyClock.drop()
                awakeUntil = if (cutIn) SystemClock.elapsedRealtime() + FOLLOW_MS else 0L; called = false
                _state.value = VoiceState(if (cutIn) Mode.AWAKE else Mode.LISTENING); again() }
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
                // A follow-up without the name that the recognizer was barely sure of is the room (the TV, people nearby),
                // not Boss: let go, the follow-up window stays open. Not after "Yes, Boss?" or the mic button.
                if (com.optionslab.ira.Sure.faint(sure, named, called0 && awake)) { note("faint words without my name (${com.optionslab.ira.Sure.say(sure)}): let go"); again(); return }
                // "Go on" / "continue" / "aage bolo" after Boss cut an answer off: the rest of that answer, its words only
                // (the chat already shows them) - nothing new is worked out or done. A locked phone never says the
                // account aloud. With nothing cut off, it is asked as before ("go on" is "tell me more").
                if (com.optionslab.ira.BargeIn.goOn(h.question)) {
                    val c = cutOff
                    when (val r = com.optionslab.ira.BargeIn.rest(c, SystemClock.elapsedRealtime(), locked())) {
                        is com.optionslab.ira.BargeIn.Rest.Say -> {
                            awakeUntil = 0; cutOff = null
                            heardAt = com.optionslab.ira.Turn.spokeEnd(turnPartialAt, turnEndAt, SystemClock.elapsedRealtime()); replyClock.heard(heardAt)
                            say(com.optionslab.ira.Aloud.say(r.text, if (IraTools.brief) com.optionslab.ira.Aloud.Length.SHORT else com.optionslab.ira.Aloud.Length.USUAL),
                                "answer", full = r.text, account = c?.account == true)
                            return
                        }
                        is com.optionslab.ira.BargeIn.Rest.Refused -> { awakeUntil = 0; cutOff = null; say(r.why); return }
                        null -> {}
                    }
                }
                // "Say that again slowly" / "what was that number?" / "dobara dheere bolo" ([com.optionslab.ira.Again]): the
                // last answer's own words again, slower for that one answer, or only its figures - nothing is worked out
                // or done. An answer that may hold the account is not said again on a locked phone.
                com.optionslab.ira.Again.read(h.question)?.let { ask ->
                    awakeUntil = 0
                    heardAt = com.optionslab.ira.Turn.spokeEnd(turnPartialAt, turnEndAt, SystemClock.elapsedRealtime()); replyClock.heard(heardAt)
                    when (val r = com.optionslab.ira.Again.reply(ask, repeatable, SystemClock.elapsedRealtime(), locked())) {
                        is com.optionslab.ira.Again.Reply.Say -> {
                            note("said again (${ask.what.name.lowercase()})")
                            say(r.text, "answer", full = r.text, account = r.account, slow = r.slow, keep = false)
                        }
                        is com.optionslab.ira.Again.Reply.Refused -> say(r.why)
                        is com.optionslab.ira.Again.Reply.None -> say(r.why)
                    }
                    return
                }
                awakeUntil = 0
                // A command for later ("start all arms tomorrow at 9") is judged as the command itself.
                val laterRest = runCatching { com.optionslab.ira.Later.split(h.question, java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")))?.rest }.getOrNull()
                val parsedQ = com.optionslab.ira.Ask.parse(laterRest ?: h.question)
                val topics = parsedQ.topics
                // "More" on a locked phone (review, 5 Oct): the last answer in full only as "go on" and "say that again" allow -
                // never a note nobody asked for, and an answer that may hold the account only in Boss's own voice (the check
                // an account question passes on a locked phone). Words only; nothing is worked out again or done.
                if (parsedQ.command?.kind == com.optionslab.ira.Command.Kind.MORE && locked()) {
                    heardAt = com.optionslab.ira.Turn.spokeEnd(turnPartialAt, turnEndAt, SystemClock.elapsedRealtime()); replyClock.heard(heardAt)
                    when (val r = com.optionslab.ira.MoreAnswer.reply(IraHub.lastForMore(), true) { boss() }) {
                        is com.optionslab.ira.MoreAnswer.Reply.Say -> say(com.optionslab.ira.Aloud.say(r.text, com.optionslab.ira.Aloud.Length.FULL),
                            "answer", full = r.text, account = r.account)
                        is com.optionslab.ira.MoreAnswer.Reply.Refused -> say(r.why)
                    }
                    return
                }
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
                        // A heard mute must be clear (Boss, 5 Oct: one mis-heard "mute" kept him silent all day): the name in the
                        // same words or a clear phrase, said aloud first, and for today only. A typed mute is as before.
                        cmd?.kind == com.optionslab.ira.Command.Kind.MUTE -> heardMute(h.question, alternatives.firstOrNull().orEmpty())
                        else -> {
                            // Not sure it heard this question right (a low score, or its best readings differ in an index, a
                            // number, a side or a day): what it took it as is said first, then the answer - questions only,
                            // never an order or command (each has its own confirm); the trace keeps why, never the words.
                            // A mis-heard fragment ("bus") is not read back first: its "say it again" comes at once
                            // ([com.optionslab.ira.MisHeard]; voice, round 26).
                            val fragment = runCatching { com.optionslab.ira.MisHeard.fragment(h.question, voice = true) }.getOrDefault(false)
                            val echo = if (fragment) null else com.optionslab.ira.HeardBack.why(h.question, alternatives, sure,
                                isQuestion = parsedQ.order == null && parsedQ.command == null && laterRest == null &&
                                    com.optionslab.ira.Topic.ORDER !in topics && com.optionslab.ira.Topic.COMMAND !in topics,
                                lockedAccount = locked() && com.optionslab.ira.Topic.ACCOUNT in topics)?.let { why ->
                                note("read back first (${why.name.lowercase()}, ${com.optionslab.ira.Sure.say(sure)})")
                                com.optionslab.ira.HeardBack.line(h.question, com.optionslab.ira.Aloud.hindi(h.question))
                            }
                            answer(h.question, confirm = risky && !verified, named = named, echo = echo)
                        }
                    }
                }
            }
        }
    }

    /**
     * A mute heard as [question] (best reading [best]): only a clear one mutes ([com.optionslab.ira.VoiceMute.heardClear]) -
     * "Muting, Boss..." is said first, then the mute is kept as a voice mute (that day only). A bare "mute" is answered
     * with how to mute, and nothing changes. Only the mute's own command words are kept (the diagnostics' "heard as").
     */
    private fun heardMute(question: String, best: String) {
        if (!com.optionslab.ira.VoiceMute.heardClear(question, com.optionslab.ira.Wake.named(best))) {
            note("a bare mute heard: not taken")
            say(com.optionslab.ira.VoiceMute.UNCLEAR)
            return
        }
        val heardAs = com.optionslab.ira.VoiceMute.heardAs(question)
        say(com.optionslab.ira.VoiceMute.MUTING)
        // Kept now, after the line was handed to the voice (it finishes; nothing after it is said).
        muteBy(com.optionslab.ira.VoiceMute.By.VOICE, heardAs, stopNow = false)
        runCatching { IraTools.alertBoss(com.optionslab.ira.AlertSense.Boss.MUTED) }
        runCatching { IraTools.count(com.optionslab.ira.Improve.MUTED) }
        runCatching { IraActivity.add("Muted my voice (heard).") }
        runCatching { IraHub.note("Muted by voice (heard as '$heardAs'), for today only. Say \"Jarvis, unmute\" or tap Unmute to hear me.") }
    }

    /** Said without the name as a follow-up: unmute and the reply language (never mute: a stray word must not silence Jarvis). */
    private val VOICE_KINDS = setOf(com.optionslab.ira.Command.Kind.UNMUTE, com.optionslab.ira.Command.Kind.VOICE_CHECK,
        com.optionslab.ira.Command.Kind.HINDI, com.optionslab.ira.Command.Kind.ENGLISH,
        com.optionslab.ira.Command.Kind.QUIET_ON, com.optionslab.ira.Command.Kind.QUIET_OFF,
        com.optionslab.ira.Command.Kind.PACE_SLOWER, com.optionslab.ira.Command.Kind.PACE_FASTER, com.optionslab.ira.Command.Kind.PACE_NORMAL,
        // How much he says aloud, or saying more of it: preferences of his voice, not actions.
        com.optionslab.ira.Command.Kind.BRIEF_ON, com.optionslab.ira.Command.Kind.BRIEF_OFF, com.optionslab.ira.Command.Kind.MORE)

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

    /** The words being handed to the voice now, as said (for telling Jarvis's own words heard back from Boss's). */
    @Volatile private var sayingText: String? = null
    /**
     * Each utterance's own words end inviting an answer ([com.optionslab.ira.AnswerWindow.invites]; read before any Hindi),
     * by its id ("answer#12") - read when THAT utterance ends, said, cut short or replaced, never a later one's.
     */
    private val invitesOf = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** The answer being said, in full as in the chat (null: not an answer), and whether it is about the account. */
    @Volatile private var sayingFull: String? = null
    @Volatile private var sayingAccount = false
    /** Where in [sayingText] the voice has got to (characters; -1: not known yet). */
    @Volatile private var reachedAt = -1
    /** Each piece's utterance id: where it starts in [sayingText] and how long it is. */
    private val pieceAt = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    /** The last answer cut off by Boss, for "go on" ([com.optionslab.ira.BargeIn.rest]). */
    @Volatile private var cutOff: com.optionslab.ira.BargeIn.CutOff? = null
    /** The last answer said aloud, for "say that again slowly" / "what was that number?" ([com.optionslab.ira.Again]). */
    @Volatile private var repeatable: com.optionslab.ira.Again.Last? = null

    /** The last answer said aloud and when (a follow-up's same answer is not said again). */
    private data class Said(val text: String)
    @Volatile private var lastAnswer: Said? = null
    @Volatile private var lastAnswerAt = 0L

    /** The answer being worked out (cancelled by "Jarvis, stop"). */
    private var answerJob: kotlinx.coroutines.Job? = null
    /** A slow answer Boss was told is coming: the mic button's one-question listen is not ended before it. */
    @Volatile private var lateWaiting = false

    /** [echo]: what Jarvis took the question as, said before the answer when he was not sure ([com.optionslab.ira.HeardBack]). */
    private fun answer(q: String, confirm: Boolean = false, named: Boolean = true, echo: String? = null) {
        // From Boss's last word (Boss, 5 Oct): the turn's closing wait and the recognizer's final reading count too.
        heardAt = com.optionslab.ira.Turn.spokeEnd(turnPartialAt, turnEndAt, SystemClock.elapsedRealtime()); replyClock.heard(heardAt)
        learnPace()
        _state.value = VoiceState(Mode.THINKING)
        // Speed, round 4: this question's stages timed from Boss's last word ([com.optionslab.ira.AskStages]; durations only).
        val timed = IraHub.askStages.begin(heardAt, voice = true)
        askTimed = timed
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
            // Read off the main thread (this scope's); waited for, so the reply below is looked for after it as before.
            IraHub.askSoon(q, confirmed = confirm, timed = timed).join()
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
            if (a != null) IraHub.askStages.mark(timed, com.optionslab.ira.AskStages.Stage.ANSWERED, SystemClock.elapsedRealtime())
            else IraHub.askStages.finish(timed)
            // How long Boss waited, for the diagnostics (Boss, 4 Oct: "getting late response").
            if (a != null) note("answer ready %.1f s after the words".format(java.util.Locale.ENGLISH, (SystemClock.elapsedRealtime() - heardAt) / 1000.0))
            val o = a?.order
            // A suggested trade is asked aloud by itself (yes or no): nothing more to say here.
            if (a?.action != null && IraHub.asksYesNo(a.action)) return@launch
            // The answer's full text (as in the chat), kept with what is said so "go on" can say the rest after a cut.
            var full: String? = null
            // The words already shaped for the speech engine ([com.optionslab.ira.SpokenReply.Said.shaped]): not shaped again.
            var shaped = false
            // The question read once here, for "tell me more" and the account below (it used to be read for each).
            val asked by lazy { com.optionslab.ira.Ask.parse(q) }
            // The answer ends offering the question Boss usually asks next ([com.optionslab.ira.NextAsk]): an invitation (an
            // OFFER) whatever its words, even when the short line leaves the offer in the chat - so a yes after it is never
            // taken for an older request still waiting for his yes or no ([com.optionslab.ira.AnswerWindow]).
            val offered = a != null && runCatching { IraTools.nextAskOfferIn(a.text) }.getOrDefault(false)
            say(when {
                a == null -> if (late) "Boss, I could not finish what you asked earlier. Please ask me again." else "I could not work that out."
                o != null && o.missing.isEmpty() && o.refusal == null -> "I have put that order on the Ira screen. Nothing is sent until you confirm it there."
                a.action != null -> {
                    // Asked aloud instead of a button hidden in the chat: "Shall I stop ORB? Yes or no?"
                    val shall = IraHub.requestAsk(a.action) ?: com.optionslab.ira.Address.boss("Shall I " + a.text.removePrefix("Tap Confirm to ").trimEnd('.') + "? Yes or no?")
                    asking = a.action; askingText = shall; askingNeedsBoss = IraHub.isExit(a.action); askingUntil = 0
                    say(shall, "question")
                    return@launch
                }
                // Short answers (the owner's setting, or "shorter"): the first sentence; "tell me more" says the whole
                // answer. Said as a person says it (Boss once, figures rounded), the rest left in the chat.
                // Not sure of the words (echo): what he took them as goes first, one sentence, Boss named once. A kind of
                // answer Boss often asks "what?" after is said shorter (two sentences or one; [com.optionslab.ira.Clarity]); a topic
                // Boss keeps asking "in short" or "in detail" after, in one sentence or whole ([com.optionslab.ira.TopicLength]).
                // A market read Boss keeps asking again for its figure: that figure's sentence first aloud, the same words
                // ([com.optionslab.ira.FigureFirst]); kept as the full answer too, so "go on" finds the same sentences.
                // Worked out in one pass (Voice, round 22: [com.optionslab.ira.SpokenReply]): the question's kind read once for
                // all three, the same words as before; already shaped for the speech engine, so [say] does not shape it again.
                else -> {
                    val r = com.optionslab.ira.SpokenReply.said(q, a.text,
                        more = asked.command?.kind == com.optionslab.ira.Command.Kind.MORE,
                        // "Detail mein batao" said just now: his wish, said whole ([com.optionslab.ira.TopicLength]).
                        wishedLong = IraTools.lengthWished(q),
                        brief = IraTools.brief,
                        // A topic Boss keeps asking "in short" or "in detail" after: said that way aloud (never over his own setting above).
                        leading = { IraTools.figureLeadingNow() }, learned = { IraTools.lengthLearnedNow() }, shorter = { IraTools.clarityShorterNow() },
                        echo = echo, late = late,
                        // Short answers (Boss's choice, the default): one precise line; "go on" / "more" gives the rest.
                        // Never an action's, confirm's or command's result ([IraHub.Msg.whole]; review, 5 Oct).
                        short = !a.whole && runCatching { IraTools.shortAnswers }.getOrDefault(true),
                        // A kind Boss usually asks "more" after: said in full straight away, not the short line first
                        // ([com.optionslab.ira.MoreAfter]; never on a locked phone - none passed then). Length only.
                        fuller = { if (locked()) emptyList() else IraTools.moreAfterLearnedNow() })
                    // A question that named no index, read for the one Boss usually means ([com.optionslab.ira.UsualIndex]):
                    // "BankNifty, as usual:" before the answer, so he hears which index it is for (the chat's note says it
                    // in full). Speech wording only: never on a locked phone, never before a warning, never on a late answer.
                    val usualIdx = if (late) null else runCatching { IraTools.indexReadFor(said) }.getOrNull()
                    val usualLocked = usualIdx != null && locked()
                    // Led only when neither what is said nor the whole answer holds a warning (each is checked).
                    val usualSpoken = runCatching { com.optionslab.ira.UsualIndex.aloud(usualIdx, r.spoken, usualLocked) }.getOrDefault(r.spoken)
                    val usualFull = runCatching { com.optionslab.ira.UsualIndex.aloud(usualIdx, r.full, usualLocked) }.getOrDefault(r.full)
                    val usualLed = usualSpoken != r.spoken && usualFull != r.full
                    full = if (usualLed) usualFull else r.full
                    shaped = r.shaped && !usualLed
                    if (usualLed) usualSpoken else r.spoken
                }
            }.let { text ->
                // A follow-up (no "Jarvis") that brings back the very answer just given is not said again: the
                // follow-up window closes instead, so one answer can never repeat itself in a loop.
                val prev = lastAnswer
                if (!named && prev != null && prev.text == text && SystemClock.elapsedRealtime() - lastAnswerAt < 120_000) {
                    replyClock.drop(); awakeUntil = 0; again(); return@launch
                }
                lastAnswer = Said(text); lastAnswerAt = SystemClock.elapsedRealtime()
                text
            }, "answer", full = full,
                // Said on an unlocked phone, any answer may hold Boss's side (make the case, his reasons): "go on" says its
                // rest only while the phone is still unlocked (review, 5 Oct).
                account = !locked() || com.optionslab.ira.Topic.ACCOUNT in asked.topics, shaped = shaped, offer = offered)
        }
    }

    /** Stops any speech now (muted). */
    fun hush() { main.post {
        val hushed = utterance
        val id = hushed?.substringBefore('#')
        utterance = null                                 // nothing still being prepared is said, and its onStop is ignored
        runCatching { tts?.stop() }
        if (speaking) afterSpeech(id, endedInviting(hushed))
    } }

    /**
     * Speaks, still listening - but only for "Jarvis" while it talks, so the owner can cut in and it never answers
     * itself (its own name is said as "my name", so it does not hear it) - then listens again, or stops after
     * [id] STOP_AFTER.
     */
    /** [reply]: these words answer what Boss just said (timed from his words to their first sound); false for unasked ones. */
    private fun say(words: String, id: String = "say", full: String? = null, account: Boolean = false, slow: Boolean = false, keep: Boolean = true,
                    reply: Boolean = true, shaped: Boolean = false, offer: Boolean = false) {
        // The voice first: a model rewrite under way gives the processor back before the speech engine starts (5 Oct).
        runCatching { IraModel.yieldToVoice() }
        // Figures as a trader says them: a lakh or more in lakh / crore, option symbols as words ([com.optionslab.ira.SayAs]).
        // Then commas where a person would pause: brackets, spaced dashes, figures side by side ([com.optionslab.ira.Pauses]).
        // [shaped]: the words came straight from [com.optionslab.ira.Aloud.say], which ends with exactly these two - doing them
        // again would change nothing ([com.optionslab.ira.SpokenReply.Said.shaped]), so they are not done twice (Voice, round 22).
        val text = if (shaped) words else com.optionslab.ira.Pauses.shape(com.optionslab.ira.SayAs.figures(words, com.optionslab.ira.Aloud.hindi(words)))
        unmuteNow()                                      // Jarvis's own voice is never muted
        val t = tts
        lastSpokenId = id
        if (id == "answer" || id == "question") lastSpoken = text
        // A new answer replaces the one cut off; what is said is kept for "go on" if Boss cuts this one off too.
        if (id == "answer") cutOff = null
        // Kept for "say that again" (a repeat itself is not kept over the answer it repeats). Said on an unlocked phone,
        // any answer may hold Boss's side: its repeat re-checks the lock ([com.optionslab.ira.Again.reply]).
        if (id == "answer" && keep) repeatable = com.optionslab.ira.Again.Last(words, account || !locked(), SystemClock.elapsedRealtime())
        sayingFull = if (id == "answer") full else null; sayingAccount = account
        sayingReply = reply && (id == "answer" || id == "question")
        sayingText = text; reachedAt = -1
        // [offer]: the caller knows these words end with an offer (the next question, [com.optionslab.ira.NextAsk]) - an
        // invitation whatever the words say; else read from the words themselves.
        val invited = offer || com.optionslab.ira.AnswerWindow.invites(words)
        // Muted: the words go on screen as a pop-up instead (answers and questions only; "One moment" is dropped).
        if (muted && !text.startsWith("Voice on")) {
            if (id == "answer" || id == "question") runCatching { JarvisPopup.show(this, "Jarvis (muted)", "${full ?: words}\n\nSay \"Jarvis, unmute\" to hear me.") }
            afterSpeech(id, invited); return
        }
        if (!voiceReady || t == null) { afterSpeech(id, invited); return }
        _state.value = VoiceState(Mode.SPEAKING)
        applyStyle(t)                                   // a style or voice changed on the Ira screen takes effect now
        // A slow repeat: slower for this answer only (the next applyStyle sets Boss's own pace back).
        if (slow) { t.setSpeechRate(style.rate * com.optionslab.ira.Again.rate(pace, true)); synchronized(applied) { applied.remove(t) } }
        val ci = cutInNow(this)
        // Cut-in on: keep listening (for the name or a stop word only) once the sentence is under way - sooner when his
        // voice cannot reach the microphone loud (a headset, echo cancelling - [com.optionslab.ira.CutIn]) - counted from
        // the first SOUND ([cutInListen], started below), not from here.
        if (!ci.on) {
            // Take turns: not listening while speaking, so Jarvis never hears itself.
            main.removeCallbacks(finish)
            runCatching { rec?.cancel() }; listening = false; endTap(); lastHeard = null
        }
        speaking = true
        tap?.echo(com.optionslab.ira.CaptureEcho.on(true))   // a turn kept open over his speech: its echo canceller on now
        spokeAt = SystemClock.elapsedRealtime()
        val u = "$id#${++said}"
        utterance = u
        if (invitesOf.size > 64) invitesOf.clear()
        invitesOf[u] = invited
        if (reply && (id == "answer" || id == "question")) replyClock.queued(u, spokeAt)
        if (ci.on && id != STOP_AFTER) { val at = spokeAt; main.postDelayed({ cutInListen(u, at, ci.startMs) }, 100) }
        // Hindi replies: an answer is translated by the model (figures checked) and said in the phone's Hindi voice.
        if (hindi && id == "answer") {
            scope.launch {
                val h = withContext(Dispatchers.Default) { inHindi(text) }
                val hv = if (h != null) hindiVoice(t) else null
                if (utterance != u || !speaking || muted) return@launch
                if (h != null && hv != null) { t.voice = hv; synchronized(applied) { applied.remove(t) } }
                if (!speakPieces(t, spokenName(if (h != null && hv != null) h else text), u)) { speaking = false; invitesOf.remove(u); afterSpeech(id, invited) }
            }
            return
        }
        if (!speakPieces(t, spokenName(text), u)) { speaking = false; invitesOf.remove(u); afterSpeech(id, invited) }
    }

    /**
     * Cut-in listening for utterance [u] (queued at [queuedAt]): [startMs] after its first sound. Before, it started
     * [startMs] after the words were handed to the speech engine - with the engine slow to make the first sentence, the
     * recognizer opened before Jarvis made a sound, and Boss's own words in that turn were taken as Jarvis talking (only
     * the name counts then). Polled every 0.1 s until the sound comes; ends when that speech ends or is replaced.
     */
    private fun cutInListen(u: String, queuedAt: Long, startMs: Long) {
        val cur = utterance
        if (stopped || !speaking || cur == null || !(cur == u || cur.startsWith("$u."))) return
        val wait = com.optionslab.ira.ModelYield.cutInIn(queuedAt, speechStartedAt, startMs, SystemClock.elapsedRealtime())
        when {
            wait == null -> main.postDelayed({ cutInListen(u, queuedAt, startMs) }, 100)
            wait > 0 -> main.postDelayed({ cutInListen(u, queuedAt, startMs) }, wait)
            else -> listen()
        }
    }

    /** Boss is speaking into the listening turn now ([com.optionslab.ira.ModelYield.bossSpeaking]). */
    private fun bossSpeaking(): Boolean = com.optionslab.ira.ModelYield.bossSpeaking(listening, turnInSpeech, turnSpeechAt, turnEndAt,
        SystemClock.elapsedRealtime(), turnPartialAt)

    /**
     * Unasked words (an announcement, Jarvis's own yes-or-no question): not said over Boss while he is speaking into a turn
     * - that cancelled his turn (cut-in off) or made it a cut-in turn where only the name counts. Up to 8 s, then said.
     * [recheck]: an unasked note's words worked out again when finally said (null: not said) - the lock and quiet hours
     * as they are then (review, 5 Oct).
     */
    private fun sayWhenFree(text: String, id: String, tries: Int = 0, recheck: (() -> String?)? = null) {
        if (stopped) return
        if (tries < 32 && !speaking && bossSpeaking()) { main.postDelayed({ sayWhenFree(text, id, tries + 1, recheck) }, 250); return }
        // Speed, round 4: Boss's own answer first. A note nobody asked for just now (a coach line, a market note - never a
        // trade's own yes-or-no question) waits while his question is being answered or its reply is being said (30 s at
        // most): said over it, it flushed the reply waiting for its first sound. Urgent warnings never come here.
        if (id != "question" && tries < REPLY_FIRST_TRIES &&
            ((_state.value.mode == Mode.THINKING && answerJob?.isActive == true) || (speaking && sayingReply))) {
            main.postDelayed({ sayWhenFree(text, id, tries + 1, recheck) }, 250); return
        }
        val words = if (recheck == null) text else (runCatching { recheck() }.getOrDefault(com.optionslab.ira.Overheard.SAID) ?: return)
        say(words, id, reply = false)
    }

    /** The words being said now answer Boss ([say]'s reply, an "answer" or "question"); unasked notes wait for them. */
    @Volatile private var sayingReply = false
    /** [sayWhenFree] waits this many 250 ms turns at most for Boss's answer to be worked out and said. */
    private val REPLY_FIRST_TRIES = 120

    /**
     * Says [text] as utterance [u]: the first sentence alone, the rest queued behind it ([com.optionslab.ira.Wake.pieces]),
     * so the voice starts once the first sentence is made into sound, not the whole answer (Boss, 5 Oct: speed). Pieces
     * before the last are "[u].k" (an answer's first sound still starts with its id, for the reply time); [utterance] is
     * the last piece queued, so speech counts as over only when all of it is said. False: nothing could be said.
     */
    private fun speakPieces(t: TextToSpeech, text: String, u: String): Boolean {
        val parts = com.optionslab.ira.Wake.pieces(text)
        sayingText = parts.joinToString(" "); reachedAt = -1; pieceAt.clear()
        var base = 0
        for ((k, p) in parts.withIndex()) {
            val uid = if (k == parts.lastIndex) u else "$u.$k"
            pieceAt[uid] = base to p.length; base += p.length + 1
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

    /**
     * Did utterance [uid] (or one of its pieces, "answer#12.0") end inviting an answer as an OFFER? Read once: its entry is
     * dropped. A request's own yes-or-no question ("question#n") is never an offer.
     */
    private fun endedInviting(uid: String?): Boolean {
        if (uid == null) return false
        val inv = invitesOf.remove(uid.substringBefore('.')) == true
        val kind = uid.substringBefore('#')
        return inv && kind != "question" && kind != STOP_AFTER
    }

    /** An offer ended (said, cut short or replaced): a yes heard now may be for it, so it is never a waiting request's. */
    private fun offerEnded() {
        lastInvite = com.optionslab.ira.AnswerWindow.ended(false, true, lastInvite)
    }

    /**
     * A newer answer invited Boss's yes (an offer) while Jarvis's own yes-or-no question about [asking] was open: that
     * question's answer window ends now - no yes is taken for the request until it is asked again - and, while the
     * request still waits, its own question is asked again after the answer, so the next yes or no is that very
     * question's (the last thing invited). Lapsed or answered elsewhere: nothing waits, and it is dropped. Never approves
     * anything itself; every confirm and fingerprint step stays as it was.
     */
    private fun supersedeAsk() {
        val id = asking ?: return
        // Only a question still being asked (its window not yet begun: 0) or with its answer window still open is asked
        // again; one whose window closed long ago is stale - an offer said now does not bring it back.
        val windowEnd = askingUntil
        if (windowEnd != 0L && windowEnd < SystemClock.elapsedRealtime()) return
        askingUntil = 0L
        val text = askingText
        if (text != null && runCatching { IraHub.waitsFor(id) }.getOrDefault(false)) {
            note("an offer said over a waiting question: its window closed, the question asked again")
            // After this utterance's own end is done (posted), never from inside it; only while it is still the same ask.
            main.post { if (asking == id && askingUntil == 0L) sayWhenFree(text, "question") }
        } else asking = null
    }

    /** [invited]: the utterance that ended ([id] its kind) itself ended inviting an answer ([invitesOf]). */
    private fun afterSpeech(id: String?, invited: Boolean = false) {
        speaking = false
        lastSpokenEnd = SystemClock.elapsedRealtime()
        if (stopped) return
        if (id == STOP_AFTER) { stopSelf(); return }
        if (id == "answer") { awakeUntil = SystemClock.elapsedRealtime() + FOLLOW_MS; called = false }   // a follow-up needs no "Jarvis" (and may not act)
        // An answer said to its end with more of it unsaid (a short line, or the usual first sentences): "go on" says the rest.
        if (id == "answer" && cutOff == null) cutOff = runCatching { com.optionslab.ira.BargeIn.finished(sayingFull, sayingText, sayingAccount, lastSpokenEnd) }.getOrNull()
        if (id == "question" && askingUntil < SystemClock.elapsedRealtime()) askingUntil = SystemClock.elapsedRealtime() + ANSWER_MS
        // Jarvis's own words that end inviting an answer ("... say yes for it.", "Before you answer, Boss: ..."): Boss's
        // answer is heard without "Jarvis" for at least 20 s, as a follow-up (it may only ask, never act - a request's yes
        // is only its own question's). His yes-or-no question is the invitation to that request; any other is an offer.
        if (id == "question") lastInvite = com.optionslab.ira.AnswerWindow.ended(true, invited, lastInvite)
        else if (id != null && id != STOP_AFTER && invited) {
            offerEnded()
            // An answer to Boss that ended with an offer, said while Jarvis's own yes-or-no question was still open: that
            // earlier ask's window ends (a yes heard now may be for the offer), and it is asked again after while it waits.
            if (id == "answer") supersedeAsk()
            awakeUntil = maxOf(awakeUntil, SystemClock.elapsedRealtime() + com.optionslab.ira.AnswerWindow.WINDOW_MS); called = false
        }
        // Battery (round 13, [com.optionslab.ira.CaptureEcho]): nothing playing now, so no echo to cancel. A taught voice's
        // capture goes on with its canceller off; a cut-in turn on the call microphone in which nothing was heard gives way
        // to a plain turn on the recognizer's own microphone (before, it ran on until the recognizer ended it, up to 25 s).
        tap?.echo(com.optionslab.ira.CaptureEcho.on(false))
        if (com.optionslab.ira.CaptureEcho.plainAfter(listening, turnInSpeech, tapCall, turnSpeech, turnPartial != null)) {
            main.removeCallbacks(finish); stoppedAt = SystemClock.elapsedRealtime()
            runCatching { rec?.cancel() }; listening = false; endTap(); lastHeard = null
            note("cut-in turn ended with my speech (nothing heard): listening on the phone's own microphone")
        }
        again(150)
    }

    private fun giveUp(why: String) { _state.value = VoiceState(problem = why); stopSelf() }

    override fun onDestroy() {
        unmuteNow()
        runCatching { muteWriter.shutdown() }           // the marker's last write (above) still runs
        hearingSave()
        stopped = true
        if (instance?.get() === this) instance = null
        main.removeCallbacksAndMessages(null)
        screenOn?.let { r -> runCatching { unregisterReceiver(r) } }; screenOn = null
        endTap(); lastHeard = null
        runCatching { rec?.destroy() }; rec = null
        runCatching { tts?.stop(); tts?.shutdown() }; tts = null
        scope.cancel()
        _state.value = VoiceState(problem = _state.value.problem)
        super.onDestroy()
    }
}
