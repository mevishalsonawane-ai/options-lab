package com.optionslab.ira

/**
 * The answer window after Jarvis's own invitation to answer (voice round 21, 2026-10-05). A spoken line that ENDS with
 * an explicit invitation - the morning check's offer ("... say "yes" for it."), a yes-or-no question ("... Yes or no?"),
 * a "Before you answer, Boss: ..." line - keeps the ears open for Boss's answer for at least [WINDOW_MS] without
 * "Jarvis", as the yes-or-no question does. Only how long he is listened to changes: the words then go the usual way,
 * with every rule (a follow-up without the name may only ask, never act; a turn that began while Jarvis spoke needs the
 * name, so his own "yes" heard back is never taken).
 *
 * One bare "yes" can only ever be for ONE thing ([yesFor]). While a request of Jarvis's own waits for Boss's yes or no
 * (a trade idea, "Shall I stop ORB?"), a yes approves it only when that question was the last thing Jarvis invited an
 * answer to: when an offer was said after it (the 09:00 offer said over a waiting trade question), the yes (or no) is
 * not taken for either - the request is never approved by a yes meant for the offer - and Jarvis says so ([HOLD]). Pure.
 */
object AnswerWindow {
    /** How long an invitation's answer is heard without the name (at least). */
    const val WINDOW_MS = 20_000L

    /** The last sentence of [line] invites an answer: "say yes (for it)", "yes or no?", "before you answer". */
    fun invites(line: String?): Boolean {
        val t = line?.trim().orEmpty()
        if (t.isEmpty()) return false
        val last = rx("(?<=[.!?])\\s+").split(t).lastOrNull { it.isNotBlank() } ?: return false
        val n = " " + last.lowercase().replace(rx("[^a-z ]"), " ").replace(rx("\\s+"), " ").trim() + " "
        return rx(" (say yes|yes or no|before you answer) ").containsMatchIn(n)
    }

    /** What Jarvis last invited an answer to: his own request's yes-or-no question, or an offer of words only. */
    enum class Invite { QUESTION, OFFER }

    /** What Boss's bare yes (or no) is for. */
    enum class For {
        /** The request waiting for his yes or no (its question was the last invitation). */
        REQUEST,
        /** Not taken for the request: an offer was said after its question - [HOLD] is said, nothing is done. */
        HOLD,
        /** No request waits: the words go on as usual (a follow-up that may only ask; an offer is checked again there). */
        WORDS,
    }

    /**
     * [requestOpen]: a request of Jarvis's own is waiting for Boss's spoken yes or no now; [last]: the last invitation
     * Jarvis finished saying (null: none known).
     */
    fun yesFor(requestOpen: Boolean, last: Invite?): For = when {
        !requestOpen -> For.WORDS
        last == Invite.OFFER -> For.HOLD
        else -> For.REQUEST
    }

    /** Said when a yes or no is not taken for the waiting request ([For.HOLD]): nothing was done. */
    const val HOLD = "Boss, I didn't take that answer: a request is still waiting for your approval, and I had just offered " +
        "you something else, so nothing was done. For the waiting request, say yes or no now, or use the Ira screen."
}
