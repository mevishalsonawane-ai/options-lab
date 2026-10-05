package com.optionslab.app.ira

import com.optionslab.ira.Airtime
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Jarvis's airtime for unasked market alerts ([Airtime], round 6): the market watchers put each alert's full words in
 * the chat themselves and offer it here; a pop-up is shown unless one about the same move just was, and at the end of
 * the market pass ([flush]) what is left is said as ONE line - alerts about one move merged, a move already told not
 * told again, at most [Airtime.PER_HOUR] lines an hour, and each kind asked of the learned say first
 * ([IraTools.alertAloud], [com.optionslab.ira.AlertSense]). Safety warnings never come here. Words only. Kept in memory
 * (a restart starts the hour afresh); no prices, amounts or account data are stored beyond today's brief lines.
 */
internal object IraAirtime {
    private val IST = ZoneId.of("Asia/Kolkata")
    private var state = Airtime.State()

    /** The automation an alert's source belongs to (the learned say's kind); the watch's pop-ups have none. */
    private fun auto(s: Airtime.Source): Automations.Auto? = when (s) {
        Airtime.Source.SHARPMOVE -> Automations.Auto.SHARPMOVE
        Airtime.Source.VIX -> Automations.Auto.VIX
        Airtime.Source.MOMENTS -> Automations.Auto.MOMENTS
        Airtime.Source.ORB -> Automations.Auto.ORB
        Airtime.Source.GAP -> Automations.Auto.GAP
        Airtime.Source.EXPIRYDAY -> Automations.Auto.EXPIRYDAY
        Airtime.Source.OI -> Automations.Auto.OI
        Airtime.Source.WATCH -> null
    }

    /**
     * An unasked market alert, already in the chat: [title] / [popText] its pop-up (null title: none, as before),
     * [spoken] what is said on its own, [brief] its clause in a merged line; [voice] false: shown only, never spoken.
     */
    fun offer(source: Airtime.Source, subject: com.optionslab.ira.Market?, up: Boolean?, title: String?, popText: String,
              spoken: String, brief: String, voice: Boolean = true) {
        val a = Airtime.Alert(source, LocalDateTime.now(IST), subject, up, spoken, brief, voice)
        val pop = synchronized(this) { val (next, p) = Airtime.offer(state, a); state = next; p }
        if (pop && title != null) runCatching { IraHub.appContext()?.let { JarvisPopup.show(it, title, popText) } }
    }

    /** The end of a market pass: the waiting alerts said as one line (or nothing). */
    fun flush() {
        val now = LocalDateTime.now(IST)
        var before: Airtime.State? = null
        val out = synchronized(this) {
            if (state.pending.isEmpty()) return
            before = state
            val o = Airtime.flush(state, now) { a -> auto(a.source)?.let { IraTools.alertAloud(it) } ?: true }
            state = o.state
            o
        }
        val line = out.say ?: return
        val said = JarvisVoice.announce(line)
        // Not heard (listening off): not counted as said - no move marked told, no hour's line used (the chat has them).
        if (!said) synchronized(this) { before?.let { b -> if (state === out.state) state = b.copy(pending = emptyList()) } }
        // Only a line Boss could hear is judged by what he did next.
        if (said) IraTools.alertSaid(out.sources.mapNotNull { auto(it) })
    }

    /** "Why so quiet?": what was said aloud today and what went to the chat only, and why. */
    fun answer(): String = synchronized(this) { Airtime.say(state, LocalDateTime.now(IST)) }

    /** Alerts kept to the chat today for any reason (0: nothing to add to "which alerts do you hold back?"). */
    fun heldToday(): Int = synchronized(this) { Airtime.heldToday(state, LocalDateTime.now(IST)) }
}
