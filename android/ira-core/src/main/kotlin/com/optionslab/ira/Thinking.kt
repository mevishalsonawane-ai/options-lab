package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * "Explain your thinking" (reasoning, round 5): for each decision Jarvis makes on his own - a paper idea taken, taken at
 * half size or sat out ([SelfCalibration], [ActAlone]); a Solo trade taken, shadowed or skipped ([SoloCalibration]); an
 * unasked market alert said, merged or kept to the chat ([Airtime], [AlertSense]); an item of his plan worked or skipped
 * ([Agenda]); a "check me on this" added to an answer ([SelfDoubt]) - a short trail is written AT THE MOMENT he decides:
 * the facts he had (built by the code from his records, never words from the model) and the fixed rule he applied.
 *
 * "Why didn't you take that trade?", "why were you quiet at 11?", "what made you say check me?", "why did you do that?"
 * walk that chain - the facts, then the rule, then what he did. Nothing is worked out afterwards: with no step written
 * for it, he says so ([nothing]) instead of finding a reason.
 *
 * Bounded: [MAX_A_DAY] steps a day, [KEEP_DAYS] days, [MAX_FACTS] short facts a step. A fact never holds a rupee amount
 * (cut when written, [clean]); a step or fact about Boss's account or his own words is marked private and is not said on
 * a locked phone. Words only: the trail never decides, changes or starts anything. Pure.
 */
object Thinking {
    const val MAX_A_DAY = 80
    const val KEEP_DAYS = 2L
    const val MAX_FACTS = 6
    const val FACT_LEN = 160
    private const val SUBJECT_LEN = 100
    /** The same decision about the same thing within this many minutes is written once (a watcher passes every minute). */
    const val SAME_MIN = 30L
    /** "At 11:20": steps this many minutes either side. */
    const val NEAR_MIN = 10L

    enum class Area { PAPER, SOLO, ALERT, AGENDA, CAUTION }

    /** What was decided: its area, whether he did the thing ([did] false: sat out, held back, skipped), how it is said, and the rule. */
    enum class Kind(val area: Area, val did: Boolean, val verb: String, val rule: String) {
        PAPER_TOOK(Area.PAPER, true, "took it on paper by myself",
            "I take an idea on paper by myself only when every gate passes - the switch on, paper not Zerodha, confidence at my bar or above, no losing hour or kind, and my record in its conditions not losing"),
        PAPER_HALF(Area.PAPER, true, "took it on paper by myself, at half size",
            "where my record in an idea's conditions is losing but not clearly bad, I take it at half size (one lot at least) - what I learn only ever lowers my own paper risk"),
        PAPER_ASKED(Area.PAPER, false, "did not take it by myself - I asked you instead",
            "any one gate failing and I don't take an idea by myself: I ask you, and it is still scored"),
        PAPER_NOT_PLACED(Area.PAPER, false, "meant to take it on paper, but the paper order did not go through",
            "a paper order that does not fill is not retried or left waiting"),
        SOLO_TOOK(Area.SOLO, true, "let Solo take it on paper",
            "Solo takes a trade by itself only where its own record in the trade's conditions is not losing"),
        SOLO_ONE(Area.SOLO, true, "let Solo take it on paper - its one of those for the day",
            "where Solo's record in the conditions is losing but not clearly bad, it takes one of those a day and no more"),
        SOLO_SHADOWED(Area.SOLO, false, "had Solo sit it out, followed on paper as if taken (no order)",
            "where Solo's record in the conditions is clearly bad - or its one a day there is used - it sits out and the trade is followed as if taken, so the condition can earn its way back"),
        SOLO_THIN(Area.SOLO, false, "had Solo skip it",
            "Solo never buys a strike with a wide gap between buyers and sellers or hardly any trading today: the fill and the exit would be poor"),
        ALERT_SAID(Area.ALERT, true, "said it aloud",
            "a market alert is said aloud unless it repeats a move just told, the hour already had ${Airtime.PER_HOUR} lines, or it is a kind you keep letting pass"),
        ALERT_MERGED(Area.ALERT, true, "said them aloud as one line",
            "alerts about the same move found in one pass are said as one line, and other moves are added to it"),
        ALERT_SAME_MOVE(Area.ALERT, false, "kept it to the chat",
            "a move is told once: an alert about a move I told aloud in the last ${Airtime.SAME_MOVE_MIN} minutes goes to the chat only"),
        ALERT_OVER_LIMIT(Area.ALERT, false, "kept it to the chat",
            "at most ${Airtime.PER_HOUR} market lines aloud in any hour; past that, the chat only (a safety warning is never held)"),
        ALERT_LEARNED(Area.ALERT, false, "kept it to the chat",
            "market colour of a kind you keep letting pass is said aloud less often - never a safety warning, and it is always in the chat"),
        AGENDA_DONE(Area.AGENDA, true, "worked through it",
            "each item on my plan is worked at its own time, the most important first, two at most a pass"),
        AGENDA_MISSED(Area.AGENDA, false, "skipped it",
            "a word meant for its moment is skipped once it is over ${Agenda.LATE_MIN} minutes late: said hours late it would mislead"),
        CHECK_ME(Area.CAUTION, true, "ended my answer with \"check me on this\"",
            "when at least ${SelfDoubt.MIN_WRONG} of a kind of my answers, and ${(SelfDoubt.CHECK_RATE * 100).toInt()} percent or more of them, were marked wrong in the last ${SelfDoubt.WINDOW_DAYS} days, I end those with \"check me on this\""),
        ASK_FIRST(Area.CAUTION, true, "said first how I read your question, and asked you to check me",
            "when at least ${SelfDoubt.ASK_WRONG} of a kind of my answers, and ${(SelfDoubt.ASK_RATE * 100).toInt()} percent or more of them, were marked wrong lately, I first say how I read the question so a misreading is caught at once"),
    }

    /** One fact as known when deciding. [private]: about Boss's account or his own words - not said on a locked phone. */
    data class Fact(val text: String, val private: Boolean = false)

    /**
     * One decision: when, what kind, what about ([subject], made by the code: "my Nifty call idea"), the facts then, and
     * the [market] it was about (null: none). [private]: the whole step is about Boss's account or his words.
     */
    data class Step(val at: LocalDateTime, val kind: Kind, val subject: String, val facts: List<Fact> = emptyList(),
                    val market: Market? = null, val private: Boolean = false)

    // ---- writing a step ------------------------------------------------------------------------------------------------

    private val MONEY = Regex("(?i)\\s*[+-]?\\s*(rs\\.?|inr|₹)\\s*[+-]?\\d[\\d,]*(\\.\\d+)?")

    /** A fact as kept: no rupee amount (cut), no secret, one line, short. */
    fun clean(text: String): String {
        var t = Secrets.redact(text).replace(rx("\\s+"), " ").trim()
        t = MONEY.replace(t, "").replace(rx("\\s+([,;:.)])"), "$1").replace(rx("[,;:]\\s*([).]|$)"), "$1").trim()
        return t.trimEnd('.', ' ').take(FACT_LEN)
    }

    private fun step(at: LocalDateTime, kind: Kind, subject: String, facts: List<Fact?>, market: Market? = null, private: Boolean = false) =
        Step(at.withSecond(0).withNano(0), kind, clean(subject).take(SUBJECT_LEN),
            facts.filterNotNull().map { it.copy(text = clean(it.text)) }.filter { it.text.isNotEmpty() }.distinctBy { it.text }.take(MAX_FACTS),
            market, private)

    /** [trail] with [s] added: older days dropped, a repeat of the same decision within [SAME_MIN] minutes skipped, [MAX_A_DAY] a day. */
    fun add(trail: List<Step>, s: Step): List<Step> {
        val day = s.at.toLocalDate()
        val kept = trail.filter { !it.at.toLocalDate().isBefore(day.minusDays(KEEP_DAYS - 1)) && !it.at.toLocalDate().isAfter(day) }
        if (kept.any { it.kind == s.kind && it.subject == s.subject && it.facts == s.facts && Math.abs(Duration.between(it.at, s.at).toMinutes()) <= SAME_MIN }) return kept
        val today = kept.filter { it.at.toLocalDate() == day } + s
        return (kept.filter { it.at.toLocalDate() != day } + today.takeLast(MAX_A_DAY)).sortedBy { it.at }
    }

    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun hm(m: Int) = "%02d:%02d".format(Locale.ENGLISH, m / 60, m % 60)

    /** The conditions an idea came in, in words ("on Nifty, in the late morning, against the trend, buying calls"). */
    private fun conditions(c: SelfCalibration.Conditions): String =
        SelfCalibration.tags(c).filter { it.dim != SelfCalibration.Dim.KIND }.joinToString(", ") { it.phrase }

    private fun idea(c: SelfCalibration.Conditions, who: String) =
        "$who ${c.market.label} ${if (c.call) "call" else "put"} idea" + SelfCalibration.tags(c).firstOrNull { it.dim == SelfCalibration.Dim.KIND }
            ?.takeIf { c.kind != SoloCalibration.KIND && c.kind.isNotBlank() }?.let { " (${it.phrase})" }.orEmpty()

    enum class Paper { TOOK, HALF, ASKED, NOT_PLACED }

    /**
     * Jarvis's own idea, decided ([ActAlone] and [SelfCalibration]'s gates as they stood): [stars] his confidence, [bar]
     * his bar to act alone, [switchOn] acting on paper by himself allowed, [goesLive] it would reach Zerodha, [badHour] /
     * [badKind] the losing hour or kind in words, [calib] his record in its conditions, [solo] Solo's setup offered to
     * Boss, [noPrice] no live price for it then, [failed] why the paper order did not go through (code's words).
     */
    fun paper(at: LocalDateTime, c: SelfCalibration.Conditions, outcome: Paper, calib: SelfCalibration.Judgment, stars: Int, bar: Int,
              switchOn: Boolean, goesLive: Boolean, badHour: String? = null, badKind: String? = null, solo: Boolean = false,
              noPrice: Boolean = false, failed: String? = null): Step {
        val kind = when (outcome) { Paper.TOOK -> Kind.PAPER_TOOK; Paper.HALF -> Kind.PAPER_HALF; Paper.ASKED -> Kind.PAPER_ASKED; Paper.NOT_PLACED -> Kind.PAPER_NOT_PLACED }
        val record = calib.text() ?: if (calib.action == SelfCalibration.Action.SIT_OUT) "my record could not be read, so I took the careful side" else null
        return step(at, kind, idea(c, if (solo) "Solo's" else "my"), listOf(
            Fact("it came in " + conditions(c)),
            Fact("confidence $stars/5, " + if (bar >= ActAlone.NONE) "and every level had lost, so my bar was none" else "against my bar of $bar/5"),
            if (solo) Fact("it was Solo's setup, which is always put to you") else null,
            if (!switchOn) Fact("acting on paper by myself was switched off") else null,
            if (goesLive) Fact("it would have gone to Zerodha with real money, which is always asked, with your fingerprint") else null,
            if (noPrice) Fact("I had no live ${c.market.label} price then") else null,
            badHour?.let { Fact("in this hour, $it") },
            badKind?.let { Fact(it) },
            record?.let { Fact(it) },
            failed?.let { Fact("the paper order: $it") },
        ), c.market)
    }

    /** Solo's own trade, decided by its record ([SoloCalibration.decide]). */
    fun solo(at: LocalDateTime, c: SelfCalibration.Conditions, d: SoloCalibration.Decision): Step {
        val kind = when {
            !d.take -> Kind.SOLO_SHADOWED
            d.action == SelfCalibration.Action.SHRINK -> Kind.SOLO_ONE
            else -> Kind.SOLO_TOOK
        }
        val record = d.why?.let { "Solo's record on ${it.what(SoloCalibration.WHO)}: ${it.record("trade")}" }
            ?: if (!d.take) "Solo's record could not be read, so it took the careful side" else "nothing in Solo's record in these conditions said to be careful"
        return step(at, kind, idea(c, "Solo's"), listOf(Fact("it came in " + conditions(c)), Fact(record)), c.market)
    }

    /** Solo's trade skipped for a thin strike ([problem]: [StrikeLiquidity.problem]'s words). */
    fun soloThin(at: LocalDateTime, market: Market, call: Boolean, problem: String): Step =
        step(at, Kind.SOLO_THIN, "Solo's ${market.label} ${if (call) "call" else "put"} idea", listOf(Fact(problem)), market)

    /**
     * One [Airtime] pass's decisions ([Airtime.Out.decided]) as steps, one per way it went. [learned]: for a kind held
     * back as one Boss lets pass, its record then ([AlertSense.Record.say]), by source.
     */
    fun alerts(at: LocalDateTime, decided: List<Airtime.Entry>, learned: Map<Airtime.Source, String> = emptyMap()): List<Step> {
        if (decided.isEmpty()) return emptyList()
        val out = ArrayList<Step>()
        fun subject(es: List<Airtime.Entry>) = es.map { it.brief.trim().trimEnd('.') }.distinct().let { b ->
            b.take(2).joinToString("; ") + if (b.size > 2) " and ${b.size - 2} more" else "" }
        fun market(es: List<Airtime.Entry>) = es.mapNotNull { it.subject }.distinct().singleOrNull()
        val spoken = decided.filter { it.how == Airtime.How.SPOKEN }
        if (spoken.isNotEmpty()) out += step(at, if (spoken.size > 1) Kind.ALERT_MERGED else Kind.ALERT_SAID, subject(spoken),
            listOf(spoken.first().because?.let { Fact(it) }) + spoken.map { Fact("${it.brief.trim().trimEnd('.')} (found ${hm(it.at)})") }, market(spoken))
        for ((how, kind) in listOf(Airtime.How.SAME_MOVE to Kind.ALERT_SAME_MOVE, Airtime.How.OVER_LIMIT to Kind.ALERT_OVER_LIMIT, Airtime.How.LEARNED to Kind.ALERT_LEARNED)) {
            val es = decided.filter { it.how == how }
            if (es.isEmpty()) continue
            val facts = es.map { e ->
                val why = e.because ?: e.source?.let { learned[it] }?.let { "of the kind you let pass: $it" }
                Fact(e.brief.trim().trimEnd('.') + " (found ${hm(e.at)})" + (why?.let { " - $it" } ?: ""))
            }
            out += step(at, kind, subject(es), facts, market(es))
        }
        return out
    }

    private fun agendaNoun(i: Agenda.Item): String = when (i.kind) {
        Agenda.Kind.GOAL -> "goal check"; Agenda.Kind.RULE -> "reminder of your rule"; Agenda.Kind.ABOUT -> "reminder of what you told me"
        Agenda.Kind.EXPIRY -> "expiry check of what you hold"; Agenda.Kind.POSITIONS -> "check of your positions"
        Agenda.Kind.EVENT -> "word on today's event"; Agenda.Kind.WEAK_HOUR -> "weak-hour watch"; Agenda.Kind.PAPER_TEST -> "paper test read"
        Agenda.Kind.SELF -> "check of my own goals"; Agenda.Kind.TEACH -> "question from my study"
    }

    /** An item of his plan worked ([done] true) or skipped, at [minute] of day. Private when it holds Boss's account or words. */
    fun agenda(at: LocalDateTime, i: Agenda.Item, done: Boolean): Step {
        val minute = at.hour * 60 + at.minute
        val subject = "my ${hm(i.at)} ${agendaNoun(i)}" + if (i.kind == Agenda.Kind.EVENT) i.words.firstOrNull()?.let { " ($it)" }.orEmpty() else ""
        return step(at, if (done) Kind.AGENDA_DONE else Kind.AGENDA_MISSED, subject, listOf(
            Fact("it was set for ${hm(i.at)}, priority ${i.kind.rank} of 5, and it only ${i.kind.means.label.lowercase(Locale.ENGLISH).removePrefix("i ")
                .let { if (i.kind.means == Agenda.Means.PAPER) "works on paper" else it }}"),
            if (done) Fact("its time had come (${hm(minute)})") else Fact("by ${hm(minute)} it was ${minute - i.at} minutes past its time"),
            if (done) Fact("what came of it: ${Agenda.summary(i)}", private = !i.kind.open) else null,
        ), private = !i.kind.open)
    }

    /** A caution added to an answer ([SelfDoubt.Caution]); null when there was none. */
    fun caution(at: LocalDateTime, c: SelfDoubt.Caution): Step? {
        val r = c.record ?: return null
        if (c.level == SelfDoubt.Level.NORMAL) return null
        val about = c.reading ?: r.tag.phrase.removePrefix("answers ")
        val private = r.tag.key == "topic:ACCOUNT"
        return step(at, if (c.level == SelfDoubt.Level.ASK) Kind.ASK_FIRST else Kind.CHECK_ME, "your question on $about", listOf(
            Fact("your question fell among my ${r.tag.phrase}"),
            Fact("${r.say()} in the last ${SelfDoubt.WINDOW_DAYS} days"),
            Fact("that is " + "%.0f".format(Locale.ENGLISH, r.rate * 100) + " percent, allowing for few answers"),
        ), private = private)
    }

    // ---- asking ------------------------------------------------------------------------------------------------------

    /** What Boss asked about: an [area] (null: any), sat out or done ([did], null: either), a minute of day or an hour, a day. */
    data class Query(val area: Area?, val did: Boolean?, val minute: Int? = null, val hour: Int? = null,
                     val yesterday: Boolean = false, val market: Market? = null, val latest: Boolean = false, val day: Boolean = false)

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |boss |so |and |but |please |tell me |explain )*"
    private const val YOU = "(you|u|jarvis|solo)"
    private const val NOT = "(didn t|did not|didnt|haven t|have not|wouldn t|would not|don t|do not)"
    private const val TRADE = "(trade|trades|idea|ideas|call|put|one|it|position|setup|signal)"
    private val CAUTION = Regex("^ $LEAD(why|what made you|how come)( did you| do you| have you)? (say|said|add|added|end with|ended with|tell me to|ask me to|asked me to)?( me)? ?(to )?(check me|double check|check you|check that|did you mean)")
    private val CAUTION2 = Regex("^ $LEAD(why|what made you) (the |a |that )?(check me|check me on this)( on that| boss)? ")
    private val ALERT = Regex("^ $LEAD(why|how come)( were| was)? $YOU( were| was)? (so )?(quiet|silent)|" +
        "^ $LEAD(why|how come) $NOT $YOU (say|tell me|speak|mention|alert me|tell|warn me)( about)?( that| it| this| the)?( alert| move)?|" +
        "^ $LEAD(why|how come)( did| have)? $YOU (hold|held|keep|kept|merge|merged|combine|combined|skip|skipped) (back )?(that|the|this|those|these|my|an|any)? ?(alerts?|moves?|lines?)|" +
        "^ $LEAD(why|how come) (was|were) (that|the|this|those|these) (alerts?|moves?) (held|kept|merged|combined|skipped|not said)")
    private val TRADE_NOT = Regex("^ $LEAD(why|how come) $NOT $YOU (take|took|buy|bought|trade|enter|go for|act on|place)( on)?( that| the| this| my| your| a| an)?( nifty| banknifty| bank nifty| finnifty| sensex)? ?$TRADE?|" +
        // (Routing audit, 5 Oct: "why did you not take that trade" went to the activity log; Hinglish "tumne woh trade
        // kyun nahi liya" to the market's why.)
        "^ $LEAD(how come $YOU $NOT|(why|how come) (did|do|have) $YOU not) (take|taken|buy|bought|trade|enter|go for|act on|place)( on)?( that| the| this| a| an)?( nifty| banknifty| bank nifty| finnifty| sensex)? ?$TRADE?|" +
        "^ $LEAD(tumne |tum ne |aapne |aap ne |jarvis ne |solo ne )?(wo |woh |vo |voh |ye |yeh |us |is )?(nifty |banknifty |bank nifty |finnifty |sensex )?(trade|position|setup|signal) (kyun|kyu|kyon) (nahi|nahin) (liya|lia|li|kiya|kia|lagaya|uthaya)( tumne| aapne)?( boss| jarvis)? $|" +
        "^ $LEAD(why|how come)( did| have)? $YOU (sit|sat) (that |it |this )?(one )?out|" +
        "^ $LEAD(why|how come)( did| have)? $YOU (skip|skipped|pass on|passed on|pass|shadow|shadowed)( on)? (that|the|this|my|your|a|an)?( nifty| banknifty| bank nifty| finnifty| sensex)? ?$TRADE")
    private val TRADE_DID = Regex("^ $LEAD(why|how come)( did| have)? $YOU (take|took|buy|bought|trade|traded|enter|entered|go for|went for)( on)?( that| the| this| a| an)?( nifty| banknifty| bank nifty| finnifty| sensex)? ?$TRADE|" +
        "^ $LEAD(why|how come)( did| have)? $YOU (go|went|take|took|trade|traded)( it)?( at)? (half|smaller|small|one lot|1 lot)|" +
        "^ $LEAD(why|how come) (only )?(half size|one lot|1 lot|half)( boss)? $")
    private val AGENDA = Regex("^ $LEAD(why|how come)( did| have)? $YOU (skip|skipped|miss|missed|drop|dropped|do|did|work on|worked on)( on)? (that|the|this|your|an|one)? ?(plan|agenda|check|item|goal check|plan item|agenda item)|" +
        "^ $LEAD(why|how come) $NOT $YOU (do|get to|work on)( that| the| your| this)? ?(plan|agenda|check|item)")
    private val GENERIC = Regex("^ $LEAD(why did you do that|why did you do it|why that|explain your (thinking|reasoning|decision)|walk me through your (thinking|reasoning|decision)|" +
        "what were you thinking|what was your (thinking|reasoning)|why did you decide that|how did you decide that)( boss| jarvis| just now| then)? ")

    private val DAY = Regex("^ $LEAD((what|which) (did you decide|decisions did you make|calls did you make)( on your own| by yourself)?|walk me through your (day|decisions)|explain your (decisions|thinking) (today|so far|yesterday))( today| so far| yesterday)?( boss| jarvis)? $")

    private val AT = Regex(" (at|around|near|about|by) (\\d{1,2})(?:[ :.](\\d{2}))?( ?(am|pm|baje))? ")

    private fun norm(text: String) = " " + Spoken.digits(text).lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9: ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    /** What [text] asks of his thinking, or null when it is not such a question. */
    fun asked(text: String): Query? {
        val s = runCatching { norm(text) }.getOrElse { return null }
        val area: Area?; val did: Boolean?
        var latest = false
        when {
            DAY.containsMatchIn(s) -> return Query(null, null, yesterday = rx(" (yesterday|kal) ").containsMatchIn(s), day = true)
            CAUTION.containsMatchIn(s) || CAUTION2.containsMatchIn(s) -> { area = Area.CAUTION; did = null }
            // "Why so quiet today?" is the day's airtime ([Airtime.say]); a time or a move named is a decision of its own.
            ALERT.containsMatchIn(s) && !runCatching { Airtime.asked(text) }.getOrDefault(false) -> { area = Area.ALERT; did = if (rx(" (merge|merged|combine|combined) ").containsMatchIn(s)) true else false }
            AGENDA.containsMatchIn(s) -> { area = Area.AGENDA; did = if (rx(" (skip|skipped|miss|missed|drop|dropped) | $NOT ").containsMatchIn(s)) false else null }
            TRADE_NOT.containsMatchIn(s) -> { area = if (s.contains(" solo ")) Area.SOLO else null; did = false }
            TRADE_DID.containsMatchIn(s) -> { area = if (s.contains(" solo ")) Area.SOLO else null; did = true }
            GENERIC.containsMatchIn(s) -> { area = null; did = null; latest = true }
            else -> return null
        }
        var minute: Int? = null; var hour: Int? = null
        AT.find(s)?.let { m ->
            var h = m.groupValues[2].toInt()
            val mm = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            if (m.groupValues[5] == "pm" && h < 12) h += 12 else if (m.groupValues[5] != "am" && h in 1..7) h += 12
            if (h in 0..23 && (mm == null || mm in 0..59)) { if (mm != null) minute = h * 60 + mm else hour = h }
        }
        val market = Market.mentioned(text).firstOrNull()
        return Query(area, did, minute, hour, rx(" (yesterday|kal) ").containsMatchIn(s), market, latest && minute == null && hour == null)
    }

    private fun areaOk(q: Query, k: Kind) = when (q.area) {
        null -> q.latest || k.area == Area.PAPER || k.area == Area.SOLO
        else -> k.area == q.area
    }

    /** The step [q] is about: the latest that fits ([q]'s area, done or not, its time and index), or null. */
    fun find(q: Query, trail: List<Step>, now: LocalDateTime): Step? {
        val day = if (q.yesterday) now.toLocalDate().minusDays(1) else now.toLocalDate()
        val fit = trail.filter { it.at.toLocalDate() == day && !it.at.isAfter(now) && areaOk(q, it.kind) }
            .filter { q.did == null || it.kind.did == q.did }
            .filter { q.market == null || it.market == null || it.market == q.market }
        val timed = when {
            q.minute != null -> fit.filter { Math.abs(it.at.hour * 60 + it.at.minute - q.minute) <= NEAR_MIN }
            q.hour != null -> fit.filter { it.at.hour == q.hour }
            else -> fit
        }
        return timed.maxByOrNull { it.at }
    }

    /**
     * The answer: the step [q] is about walked through - what he decided and when, the facts he had then, the rule he
     * applied - or null when nothing written fits (the caller then says [nothing], or lets the activity log answer).
     * [locked]: a private step or fact is not said. [newerElsewhere]: for "why did you do that", something he did after
     * the latest step (in the activity log) - the question is then about that, not this.
     */
    fun answer(q: Query, trail: List<Step>, now: LocalDateTime, locked: Boolean, newerElsewhere: LocalDateTime? = null): String? {
        if (q.day) return summary(trail, if (q.yesterday) now.toLocalDate().minusDays(1) else now.toLocalDate(), now.toLocalDate())
        val s = find(q, trail, now) ?: return null
        if (q.latest && newerElsewhere != null && newerElsewhere.isAfter(s.at.plusMinutes(1))) return null
        val day = if (s.at.toLocalDate() == now.toLocalDate()) "" else "Yesterday "
        val head = "${day}At ${hm(s.at)} ".replaceFirst("Yesterday At", "Yesterday at")
        if (locked && s.private) return "${head}I decided about ${s.subject}; it was about your account or your own words, so unlock the phone for my reasons, Boss."
        val facts = s.facts.filter { !(locked && it.private) }
        val hidden = s.facts.size - facts.size
        val sb = StringBuilder("$head${what(s)}, Boss.")
        if (facts.isNotEmpty()) sb.append(" What I had then: ").append(facts.joinToString("; ") { it.text }).append('.')
        sb.append(" The rule: ").append(s.kind.rule).append('.')
        sb.append(" So I ").append(s.kind.verb).append('.')
        if (hidden > 0) sb.append(" One more fact is about your account: unlock the phone for it.")
        sb.append(" That is as I wrote it down when I decided - nothing added since.")
        return sb.toString()
    }

    private fun what(s: Step): String = when (s.kind.area) {
        Area.PAPER, Area.SOLO -> "I looked at ${s.subject}"
        Area.ALERT -> if (s.kind == Kind.ALERT_MERGED) "I had alerts: ${s.subject}" else "I had an alert: ${s.subject}"
        Area.AGENDA -> "${s.subject} was due"
        Area.CAUTION -> "I answered ${s.subject}"
    }

    /** Nothing written fits [q]: said plainly, never a reason found afterwards. */
    fun nothing(q: Query): String {
        val what = when (q.area) {
            Area.ALERT -> "a market alert"; Area.AGENDA -> "an item of my plan"; Area.CAUTION -> "a \"check me\""
            Area.SOLO -> "a Solo trade"; Area.PAPER, null -> if (q.latest) "a decision" else "a trade idea"
        }
        val time = q.minute?.let { " around ${hm(it)}" } ?: q.hour?.let { " between ${hm(it * 60)} and ${hm(it * 60 + 60)}" } ?: ""
        val day = if (q.yesterday) " yesterday" else " today"
        return "I have no reason written down for $what$time$day, Boss, and I don't make one up afterwards. " +
            "I keep my reasons for the trade ideas I take or sit out, the alerts I say or hold, my plan and my \"check me\"s, for $KEEP_DAYS days."
    }

    /** "What did you decide today?": how many of each, in a line (counts only: nothing private). */
    fun summary(trail: List<Step>, day: LocalDate, today: LocalDate = day): String {
        val t = trail.filter { it.at.toLocalDate() == day }
        val name = when (day) { today -> "today"; today.minusDays(1) -> "yesterday"; else -> day.toString() }
        if (t.isEmpty()) return "No decisions of my own written down for $name, Boss."
        fun n(vararg k: Kind) = t.count { it.kind in k }
        val parts = listOfNotNull(
            (n(Kind.PAPER_TOOK, Kind.PAPER_HALF) to n(Kind.PAPER_ASKED, Kind.PAPER_NOT_PLACED)).takeIf { it.first + it.second > 0 }?.let { "my ideas: ${it.first} taken, ${it.second} not" },
            (n(Kind.SOLO_TOOK, Kind.SOLO_ONE) to n(Kind.SOLO_SHADOWED, Kind.SOLO_THIN)).takeIf { it.first + it.second > 0 }?.let { "Solo: ${it.first} taken, ${it.second} sat out" },
            (n(Kind.ALERT_SAID, Kind.ALERT_MERGED) to n(Kind.ALERT_SAME_MOVE, Kind.ALERT_OVER_LIMIT, Kind.ALERT_LEARNED)).takeIf { it.first + it.second > 0 }?.let { "alerts: ${it.first} lines said, ${it.second} times kept to the chat" },
            (n(Kind.AGENDA_DONE) to n(Kind.AGENDA_MISSED)).takeIf { it.first + it.second > 0 }?.let { "my plan: ${it.first} done, ${it.second} skipped" },
            n(Kind.CHECK_ME, Kind.ASK_FIRST).takeIf { it > 0 }?.let { "\"check me\" added $it time${if (it == 1) "" else "s"}" },
        )
        return name.replaceFirstChar { it.uppercase() } + ", Boss - " + parts.joinToString("; ") + ". Ask \"why\" about any one and I'll walk you through what I had then."
    }
}
