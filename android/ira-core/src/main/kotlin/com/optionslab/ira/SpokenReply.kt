package com.optionslab.ira

/**
 * The words Jarvis says for an answer, worked out in one pass (Voice, round 22: the wait from Boss's last word to the
 * first word said). Before, between the answer arriving and the voice starting, the question was read again for each
 * of the voice's own ways - its figure first ([FigureFirst]), a topic Boss likes short or whole ([TopicLength]), a kind he
 * often asks "what?" after ([Clarity]) - each reading it in full ([SelfDoubt.tags]), and the whole of the question once
 * more for "tell me more" and once more for the account; and the spoken words, already shaped by [Aloud.say] (figures
 * as said - [SayAs] - and pauses - [Pauses]), were shaped a second time on the way to the speech engine.
 *
 * Now the question's kind is read once and shared, and [Said.shaped] tells the voice that the words already are as the
 * speech engine would get them: a second [SayAs.figures] and [Pauses.shape] would change nothing (see [Said.shaped]),
 * so it is skipped. The words said are exactly as before - same choice of length, same order, same text.
 *
 * Choice of length, in this order (as before): "tell me more" said now - whole; "in detail" just answered - whole; Boss's
 * own "short answers" - one sentence; in short-answer mode the short line, or in full for a kind Boss usually asks "more"
 * after ([MoreAfter], learning round 28); a topic learned short or whole; a kind said shorter after "what?"; else as usual.
 * Pure.
 */
object SpokenReply {
    /**
     * What is said ([spoken]) and the full answer kept with it for "go on" ([full]). [shaped]: [spoken] is a fixed point of
     * the speech engine's own shaping ([SayAs.figures], then [Pauses.shape]) - it came straight out of [Aloud.say], which
     * ends with exactly those (its figures already said as a trader says them, then its pauses; [Pauses] only ever puts a
     * comma in place of a bracket, dash, separator or line break, or between figures, which can only stop a figure pattern
     * from matching, never start one; and [Pauses] applied twice changes nothing). False with a read-back or the
     * "About what you asked earlier" lead in front: those words were not through [Aloud.say], so they are shaped as before.
     */
    data class Said(val spoken: String, val full: String, val shaped: Boolean)

    /**
     * The spoken answer to [question] with answer [text]. [more]: the question was "tell me more"; [wishedLong]: Boss's
     * "in detail" was answered just now; [brief]: his "short answers" setting. [leading], [learned], [shorter]: the voice's
     * learnings, read only when needed (in the order above). [echo]: the read-back said first when not sure of the words
     * ([HeardBack]); [late]: a slow answer said after "Still working on it".
     */
    fun said(question: String, text: String, more: Boolean, wishedLong: Boolean, brief: Boolean,
             leading: () -> List<FigureFirst.Record>, learned: () -> List<TopicLength.Record>, shorter: () -> List<Clarity.Record>,
             echo: String? = null, late: Boolean = false, short: Boolean = false,
             fuller: () -> List<MoreAfter.Record> = { emptyList() }): Said {
        // A kind Boss usually asks "more" after ([MoreAfter]; the app passes none on a locked phone): said in full straight
        // away, as "tell me more" says it, instead of the short line first - never over his own "shorter" ([brief]), never
        // for a command or an order. Read only in short-answer mode.
        val fullFirst = short && !more && !wishedLong && !brief && runCatching {
            val f = fuller()
            f.isNotEmpty() && MoreAfter.detailed(question, f)
        }.getOrDefault(false)
        // Short answers (Boss, 5 Oct - the default): the answer's one line ([ShortAnswer]), said whole - its safety
        // notes kept - and the rest kept for "go on". "Tell me more" and "in detail" are said as before. The learned
        // lengths below (topic, clarity, figure first) shape only the "detailed" choice: never the short line.
        if (short && !more && !wishedLong && !fullFirst) {
            val sa = ShortAnswer.of(question, text)
            if (sa.details != null) {
                val spoken = Aloud.say(sa.line, Aloud.Length.FULL.sentences)
                val full = sa.line + (sa.rest?.let { " $it" } ?: "")
                return if (late) Said("About what you asked earlier: $spoken", full, shaped = false)
                    else Said(HeardBack.lead(echo, spoken), HeardBack.full(echo, full), shaped = echo == null && settled(spoken))
            }
        }
        // The question's kind, read once for all three (each used to read it in full by itself).
        val tags by lazy { runCatching { SelfDoubt.tags(question) }.getOrNull() }
        val kind by lazy { tags?.firstOrNull { it.dim == SelfDoubt.Dim.TOPIC }?.key }
        val ordered = runCatching {
            val lead = leading()
            if (lead.isEmpty()) text
            else {
                val r = AskedAgain.read(tags ?: emptyList()) as? AskedAgain.Read.Reading
                if (r != null && lead.any { it.kind == r.kind }) FigureFirst.reorder(text) else text
            }
        }.getOrDefault(text)
        val n = when {
            more -> Aloud.Length.FULL.sentences
            wishedLong -> Aloud.Length.FULL.sentences
            brief -> Aloud.Length.SHORT.sentences
            // As "tell me more" says it: Boss usually asks for more after this kind's short line ([MoreAfter]).
            fullFirst -> Aloud.Length.FULL.sentences
            // A topic learned short is never one sentence for a question that also asks the trade check, the account or
            // what to do ([TopicLength.shortGuarded]); and no length drops a verdict or warning ([Aloud.keep]).
            else -> runCatching { TopicLength.sentencesOf(kind, learned())?.let { TopicLength.shortGuarded(question, it) } }.getOrNull()
                ?: runCatching { Clarity.sentencesOf(kind, shorter()) }.getOrNull()
                ?: Aloud.Length.USUAL.sentences
        }
        val spoken = Aloud.say(ordered, n)
        return if (late) Said("About what you asked earlier: $spoken", ordered, shaped = false)
            else Said(HeardBack.lead(echo, spoken), HeardBack.full(echo, ordered), shaped = echo == null && settled(spoken))
    }

    /**
     * Is [s] (out of [Aloud.say]) beyond any change by a second [SayAs.figures] and [Pauses.shape]? [Aloud.say] ends with
     * exactly those two, and they settle almost every answer in one go - but not all: a separator left beside another
     * ("· ·", "| |", a bullet), a spaced dash left after one turned into a comma, a pause next to a stop (", ,", "?,"), a
     * line break, a bullet at the start, and a figure the first pass could not read (a comma glued before it, since
     * dropped: a lakh-sized one anywhere, any one starting the words) each change on a second pass. Any of them in [s]: false, and the voice shapes the words again as
     * before. Checked over every line the voice bench knows and thousands made of figures, symbols and punctuation
     * (SpokenReplyTest): whenever this says true, the second pass gives the very same words.
     */
    fun settled(s: String): Boolean {
        // A figure starting the words may have had a comma glued before it that the pauses' tidying then dropped.
        if (s.isEmpty() || s[0] in '0'..'9') return false
        for (c in s) if (c == '·' || c == '|' || c == '•' || c == '\n' || c == '\r' || c == '*') return false
        if (UNSETTLED.containsMatchIn(s)) return false
        return true
    }

    /**
     * What a second pass could still change: a spaced dash before a word (or at the end), a comma after a stop or another
     * comma, a bullet starting the words, a figure of a lakh or more still as written (Indian or Western grouping, or six
     * digits in a row).
     */
    private val UNSETTLED = Regex("\\s[-\\u2013\\u2014](?:\\s|$)|[.!?:;,\\u0964]\\s*,|^\\s*[-\\u2013\\u2014]|" +
        "\\d{6}|\\d,\\d{2},\\d{3}|\\d{3},\\d{3}|,\\d{3},\\d{3}|\\d\\s?\\+")

    /**
     * Builds the patterns the voice's words go through (their first use compiles them: tens of milliseconds, more on a
     * phone) so the first answer after Jarvis starts does not wait on it. Words only, worked out and dropped: nothing is
     * said, shown, kept or learned.
     */
    fun warm() {
        val q = "what is nifty doing"
        val a = "Nifty is at 24,612.40, up 0.4% today (Rs 1,23,456 margin). Bank Nifty is flat - NIFTY25O0724500CE · 24,500 24,600. Boss, it is calm.\nDone."
        runCatching { said(q, a, more = false, wishedLong = false, brief = false, { emptyList() }, { emptyList() }, { emptyList() }) }
        runCatching { AskedAgain.read(q) }
        runCatching { val s = Aloud.say(a, Aloud.Length.FULL); Pauses.shape(SayAs.figures(s, Aloud.hindi(s))); Wake.pieces(s) }
        runCatching { AnswerWindow.invites(a) }
        runCatching { HeardBack.why(q, listOf(q, "what is bank nifty doing"), 0.6f, isQuestion = true) }
        runCatching { HeardBack.lead(HeardBack.line(q), a) }
    }
}
