package com.optionslab.ira

/**
 * "More" / "tell me more" / "details" ([Command.Kind.MORE]): the last answer of Jarvis's, in full - with the lock
 * re-checked, as "go on" ([BargeIn.rest]) and "say that again" ([Again.reply]) do (review, 5 Oct: "more" on a locked phone
 * said the whole of the last answer aloud, the account or a note nobody asked for included).
 *
 * On a locked phone: a note Jarvis put in the chat by himself ([Last.unasked]) is never said in full; an answer that may
 * hold the account is said only in Boss's own voice - the same check an account question passes on a locked phone
 * ([LockRule.refuse]). Unlocked: as before. Words only: nothing is worked out again or done. Pure.
 */
object MoreAnswer {
    /** Said when there is no answer of Jarvis's to say more about. */
    const val NONE = "There is no answer of mine to say more about."

    /** The last message of Jarvis's ([text]), the question it answered ([question]; null: none), and whether nobody asked for it. */
    data class Last(val text: String, val question: String?, val unasked: Boolean)

    sealed class Reply {
        /** Said (and shown) in full; [account]: it may hold the account (a later "go on" re-checks the lock). */
        data class Say(val text: String, val account: Boolean) : Reply()
        /** Not said: the phone is locked. */
        data class Refused(val why: String) : Reply()
    }

    /** May [last] hold Boss's account: asked about it, no question at all, or an amount, a P&L or a contract in it. */
    fun account(last: Last): Boolean = runCatching {
        last.question.isNullOrBlank() || Topic.ACCOUNT in Ask.parse(last.question).topics || Overheard.holdsAccount(last.text)
    }.getOrDefault(true)

    /**
     * What "more" says from [last] when the phone is [locked]. [bossVoice] is asked only when needed (a locked phone and
     * an answer that may hold the account): is this "more" in Boss's own voice?
     */
    fun reply(last: Last?, locked: Boolean, bossVoice: () -> Boolean): Reply {
        if (last == null || last.text.isBlank()) return Reply.Say(NONE, account = false)
        val acct = account(last)
        if (!locked) return Reply.Say(last.text, acct)
        val refused = Reply.Refused(LockRule.refuse(true, false, true, false)!!)
        if (last.unasked) return refused
        if (acct && !runCatching(bossVoice).getOrDefault(false)) return refused
        return Reply.Say(last.text, acct)
    }
}
