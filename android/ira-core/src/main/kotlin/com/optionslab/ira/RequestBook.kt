package com.optionslab.ira

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Requests panel asked about (usefulness round 33, 2026-10-05): "what requests are waiting?", "anything waiting for my
 * approval?", "what needs my approval?", "koi request hai?", and "what did I approve today?", "what did I decline?", "what
 * happened to my requests?", "maine aaj kya approve kiya".
 *
 * The panel ([Requests]) lists what waits for Boss's yes and what became of the last few, but Jarvis could not say it:
 * here, from the panel's own read-only lists, the requests waiting now (soonest lapse first: what each would do, where it
 * would act, when it lapses, whether a yes needs his fingerprint), or the ones answered or ended (approved, declined, lapsed,
 * failed - the kind he names, today when he says so). The lead sentence carries the count and the names.
 *
 * Words only: nothing here approves, declines or changes a request - answering stays the panel's and the hub's own confirm
 * with every gate. Boss's account, so on a locked phone only how many wait ([locked]). A list that could not be read is said
 * as such ([READ_FAILED]), never as none. Pure.
 */
object RequestBook {
    const val READ_FAILED = "I could not read your requests just now, Boss - that is not the same as none waiting."

    /** What he asked: those waiting now, or those already answered or ended ([outcome] the kind named, or null for all; [today]: today's only). */
    data class Asked(val done: Boolean, val outcome: Requests.Outcome? = null, val today: Boolean = false)

    private fun norm(text: String) = " " + spacedWords(text.lowercase(Locale.ENGLISH).replace("’", "").replace("'", "")) + " "

    private const val REQ = "(requests?|approvals?)"

    /** The wake word and "can you tell me", said first or last. */
    private val POLITE = rx("^ ((hey|ok|okay|hi) )?(jarvis|ira|boss) | (can|could|would|will) you (please )?(tell|show|let) me( know)? | please | (jarvis|ira|boss) $")

    /** Said to answer one, to act, or about the app itself (where the panel is, what a request means), or advice. */
    private val NOT = rx("^ (approve|decline|reject|accept|deny|cancel|clear|dismiss|yes|no|haan|nahi|go ahead|send|place|confirm) " +
        "| (how do i|how to|how can i|where is|where are|where do|what is a|what does|meaning|explain|panel|screen|tab|button|badge|" +
        "should i|shall i|why|feature request|pull request|make a request|send a request|request a|approve it|approve that|approve this|approve all|reject all|decline all) ")

    private val DONE = rx(
        " (did|have|had) i (\\w+ ){0,1}(approve|approved|decline|declined|reject|rejected|accept|accepted|say yes to|said yes to|say no to|said no to|turn down|turned down) " +
        "| (approved|declined|rejected|lapsed|expired|failed|answered) $REQ " +
        "| $REQ (that |which )?(i |were |have |got |have been )?(approved|declined|rejected|lapsed|expired|failed|answered) " +
        "| (what|kya) happened (to|with) (my |the |those |these )?$REQ " +
        "| (recent|past|earlier|answered|old) $REQ " +
        "| (maine|mene|main ne) (aaj )?(kya|kaun sa|kaunsa|kaun se|kitne|kitni) (\\w+ ){0,2}(approve|reject|decline|mana) (kiya|kiye|ki|kari|kar diya) " +
        "| aaj (kya|kaun sa|kaunsa) (approve|reject|decline) (kiya|hua|kiye) ")

    private val WAITING = rx(
        " (any|koi|kitni|kitne|how many|what|whats|which|my|mere|meri|show|list|open|pending|waiting) (\\w+ ){0,3}$REQ " +
        "| $REQ (are |is )?(waiting|pending|open|hai|hain|baaki|baki|left) " +
        "| (waiting|pending|waits) (for|on) (my|me|boss) ?(approval|yes|ok|okay|answer|reply|decision|sign off|nod) " +
        "| (needs?|requires?) (my|bosss|boss) (approval|yes|ok|okay|sign off|answer) " +
        "| (do|must) i (need|have) to (approve|answer) " +
        "| (anything|something|kuch|what|whats) (\\w+ ){0,2}(for me to|i need to|i have to|to) approve " +
        "| (are|is) (you|jarvis) waiting (on|for) (me|my|boss) " +
        "| (kya|kuch) (approve|approval) (karna|karne) ")

    /** Does [text] ask about his requests? What he asked, or null. */
    fun asked(text: String): Asked? {
        for (s in listOf(text, Ask.reading(text))) {
            var t = norm(s)
            while (true) { val u = " " + POLITE.replace(t, " ").trim() + " "; if (u == t) break; t = u }
            if (t.isBlank() || NOT.containsMatchIn(t)) continue
            val today = rx(" (today|todays|aaj) ").containsMatchIn(t)
            if (DONE.containsMatchIn(t)) return Asked(true, outcomeIn(t), today)
            if (WAITING.containsMatchIn(t)) return Asked(false, null, today)
        }
        return null
    }

    private fun outcomeIn(t: String): Requests.Outcome? = when {
        rx(" (approv\\w*|accept\\w*|say yes|said yes) ").containsMatchIn(t) && !rx(" (declin\\w*|reject\\w*|mana) ").containsMatchIn(t) -> Requests.Outcome.APPROVED
        rx(" (declin\\w*|reject\\w*|turn down|turned down|say no|said no|mana) ").containsMatchIn(t) -> Requests.Outcome.DECLINED
        rx(" (lapsed|expired) ").containsMatchIn(t) -> Requests.Outcome.LAPSED
        rx(" failed ").containsMatchIn(t) -> Requests.Outcome.FAILED
        else -> null
    }

    /** On a locked phone: how many wait, never what. */
    fun locked(count: Int): String = when {
        count <= 0 -> "No requests waiting, Boss."
        count == 1 -> "1 request waiting, Boss - unlock the phone for what it is."
        else -> "$count requests waiting, Boss - unlock the phone for what they are."
    }

    private fun plain(s: String) = s.trim().trimEnd('.').trim()

    private fun names(titles: List<String>, max: Int = 3): String {
        val shown = titles.take(max).map { plain(it) }
        val more = titles.size - shown.size
        val head = if (more > 0) shown + "$more more" else shown
        return when (head.size) {
            0 -> ""
            1 -> head[0]
            else -> head.dropLast(1).joinToString("; ") + " and " + head.last()
        }
    }

    /** The answer for those waiting now ([list] as the panel reads it; lapsed ones are left out). */
    fun waiting(list: List<Requests.RequestView>, now: Long): String {
        val open = Requests.shown(list, now, gold = false)
        if (open.isEmpty()) return Requests.EMPTY + " Nothing waits for your yes."
        val out = ArrayList<String>()
        out += "${open.size} request${if (open.size == 1) "" else "s"} waiting, Boss: ${names(open.map { it.title })}."
        open.forEachIndexed { i, v ->
            val parts = ArrayList<String>()
            parts += "${i + 1}. ${v.kind.label} - ${plain(v.what)}"
            parts += v.venue.label
            parts += Requests.askedText(v.askedAt, now)
            parts += Requests.lapseText(v.lapsesAt, now)
            var line = parts.joinToString(". ") + "."
            if (v.venue.real) line += " A yes on it needs your fingerprint."
            if (!v.voiced) line += " Approved on the Requests screen only."
            out += line
        }
        out += "Open Requests to answer them - asking about them here approves nothing."
        return out.joinToString("\n")
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    private fun outcomeWords(o: Requests.Outcome): String = when (o) {
        Requests.Outcome.APPROVED -> "approved"
        Requests.Outcome.DECLINED -> "declined"
        Requests.Outcome.LAPSED -> "lapsed"
        Requests.Outcome.FAILED -> "approved, but it failed"
    }

    private fun wanted(o: Requests.Outcome, want: Requests.Outcome?): Boolean = when (want) {
        null -> true
        // An approval whose action failed was still approved.
        Requests.Outcome.APPROVED -> o == Requests.Outcome.APPROVED || o == Requests.Outcome.FAILED
        else -> o == want
    }

    /**
     * The answer for those answered or ended: [recent] as the panel keeps it (newest first, the last [Requests.RECENT_KEEP]
     * while the app runs), filtered by [asked]; [waitingCount] still waiting.
     */
    fun done(asked: Asked, recent: List<Requests.Recent>, waitingCount: Int, now: Long, zone: ZoneId): String {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day: (Long) -> LocalDate = { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val picked = recent.filter { wanted(it.outcome, asked.outcome) && (!asked.today || day(it.at) == today) }
        val what = when (asked.outcome) {
            Requests.Outcome.APPROVED -> "approved"
            Requests.Outcome.DECLINED -> "declined"
            Requests.Outcome.LAPSED -> "lapsed"
            Requests.Outcome.FAILED -> "failed"
            null -> "answered or ended"
        }
        val whenSaid = if (asked.today) " today" else ""
        val out = ArrayList<String>()
        if (picked.isEmpty()) {
            out += "No request $what$whenSaid, Boss - of the last ${Requests.RECENT_KEEP} I keep while the app runs."
        } else {
            val n = picked.size
            val lead = when (asked.outcome) {
                null -> {
                    val by = Requests.Outcome.entries.mapNotNull { o -> picked.count { it.outcome == o }.takeIf { it > 0 }?.let { "$it ${o.label.lowercase(Locale.ENGLISH)}" } }
                    "$n request${if (n == 1) "" else "s"} $what$whenSaid, Boss: ${by.joinToString(", ")}."
                }
                else -> "You $what $n request${if (n == 1) "" else "s"}$whenSaid, Boss: ${names(picked.map { it.view.title })}."
            }
            out += lead
            for (r in picked) {
                val at = Instant.ofEpochMilli(r.at).atZone(zone)
                val stamp = if (at.toLocalDate() == today) HM.format(at) else at.toLocalDate().toString() + " " + HM.format(at)
                out += "$stamp ${outcomeWords(r.outcome).replaceFirstChar { it.uppercase() }} - ${plain(r.view.title)} (${r.view.kind.label}, ${r.view.venue.label})."
            }
            out += "That's the last ${Requests.RECENT_KEEP} I keep while the app runs; the chat and the order book hold the rest."
        }
        if (waitingCount > 0) out += "$waitingCount still waiting - ask \"what requests are waiting\" for them."
        return out.joinToString("\n")
    }

    /** The whole answer for [asked] on an unlocked phone. */
    fun answer(asked: Asked, waiting: List<Requests.RequestView>, recent: List<Requests.Recent>, now: Long, zone: ZoneId): String =
        if (asked.done) done(asked, recent, Requests.shown(waiting, now, gold = false).size, now, zone) else waiting(waiting, now)
}
