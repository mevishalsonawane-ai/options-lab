package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * What Jarvis has learned, in one honest view (learning round 9, 2026-10-05): every learning store read together -
 * the wordings Boss taught him ([Corrections]), the routines kept on his yes ([Routine]), the alerts said less often
 * ([AlertSense]), the conditions his own ideas and Solo sit out ([SelfCalibration], [SoloCalibration]), the answer kinds
 * he flags ([SelfDoubt]), the pattern kinds he no longer brings up unasked ([PatternCalls]), his data-freshness record
 * ([DataAge]), how Nifty moved after each news theme's headlines ([NewsMoves]), the market reads Boss asked again within
 * minutes ([AskedAgain], a record only), the questions he answered with the wrong thing ([WrongThing], a record only), what Boss
 * does with his bots after losing days ([ArmHabits], a record only), the morning-check items said in a few words aloud
 * ([MorningSense]), the confidence scores said aloud with their record ([HonestStars]), the long unasked briefings said in a
 * sentence aloud outside the hours Boss talks to him ([TalkHours]), the question he asks every morning offered in one line
 * at the end of the morning check ([MorningAsks]), the reasons he turns Jarvis's trade ideas down for said up front
 * before the next idea they fit ([TurnDowns]), the topics said in a sentence or in full aloud as Boss asks for them
 * ([TopicLength]), the index he means when he names none ([UsualIndex]), the nicknames he uses for his arms and positions
 * ([Nicknames]) and his own goals for the week ([Improve]) - each with when and why it changed and, where one exists, the
 * words that undo it by voice.
 *
 * "What have you learned this week?" ([Ask.WEEK]), "what changed in how you work?" ([Ask.CHANGED]) and "show me
 * everything you've learned about me" / "what did you learn about me?" / "tumne mere baare mein kya seekha" ([Ask.ALL]). Boss's own words, routines and records ([Item.personal]) are said
 * on an unlocked phone only.
 *
 * "Undo everything you learned this week" ([undoAsked], [undo]) is put to Boss first and resets learned behaviour only:
 * the wordings and routines kept in the last [DAYS] days, the alert count (every alert aloud again), the answers said
 * shorter aloud ([Clarity]: every answer as usual again), the market reads said figure first ([FigureFirst]: the usual
 * order again), the morning-check items named in a few words ([MorningSense]: read out in full again), the confidence scores
 * said with their record ([HonestStars]: said plainly again), the briefings said shorter outside his hours ([TalkHours]:
 * in full at any hour again), the morning question offered ([MorningAsks]: no longer offered), his reasons said up front ([TurnDowns]: no longer
 * said), the topics said shorter or in full ([TopicLength]: the usual length again), the index taken when he names none
 * ([UsualIndex]: Nifty again), the nicknames learned this week ([Nicknames]: forgotten) and his own goals. (His confidence words set to fit the
 * numbers beside them ([WordFit]) are listed with their own undo, but not reset here: that is a check on his own words
 * against his own record, not a habit learned from Boss.)
 * for this week. Never a setting, the PIN, Live, the AI's live trading, a guard or the Google speech choice - and never
 * a record: the answers Boss marked wrong, the trades and the patterns' outcomes stay, as they are facts, not habits.
 * Nothing here acts. Pure: the stores live in the app.
 */
object Learnings {
    /** "This week": the last this-many days, today among them. */
    const val DAYS = 7L
    /** At most this many lines an area. */
    const val SHOW = 3

    enum class Area(val title: String, val personal: Boolean) {
        WORDS("Wordings you taught me", true),
        ROUTINES("Routines kept", true),
        ALERTS("Alerts I say less often", false),
        CLARITY("Answers I keep shorter aloud", false),
        WORD_FIT("Confidence words I set to fit my numbers", false),
        AGAIN("Market reads you asked again within minutes", false),
        WRONG_THING("Questions I answered with the wrong thing", false),
        FIGURE_FIRST("Market reads I start with the figure aloud", false),
        MORNING("Morning-check items I name in a few words aloud", false),
        STARS("Confidence scores I say with their record aloud", true),
        HOURS("Briefings I keep short aloud outside your usual hours", false),
        MORNING_ASKS("Your usual morning question, offered at the end of the morning check", false),
        TURN_DOWNS("Your reasons for turning down my ideas, said up front", true),
        LENGTHS("Topics I say in a sentence or in full aloud, as you ask for them", false),
        USUAL_INDEX("The index I take when you name none", true),
        NICKNAMES("Nicknames you use for your arms and positions", true),
        ARM_HABITS("Your bots after losing days", true),
        SIT_OUT("Conditions I sit out", true),
        ANSWERS("Answer kinds I flag", true),
        PATTERNS("Patterns I no longer bring up", false),
        DATA("My data-freshness record", false),
        NEWS("News and the index on this phone", false),
        GOALS("My own goals", true),
    }

    /** One thing learned: what it is, the day it changed ([on]; null: before days were noted), why, and its undo (null: none by voice). */
    data class Item(val area: Area, val what: String, val on: LocalDate?, val why: String, val undo: String?) {
        val personal: Boolean get() = area.personal
        fun text(): String = what + " - " + (on?.let { "${day(it)}, " } ?: "") + why + (undo?.let { ". Undo: \"$it\"" } ?: "")
    }

    /** Everything the stores hold now (the app reads each with its own accessor). */
    data class Inputs(
        val words: List<Corrections.Learned> = emptyList(),
        val routines: List<Routine.Kept> = emptyList(),
        val alerts: AlertSense.Log = AlertSense.Log(),
        val paper: List<SelfCalibration.Outcome> = emptyList(),
        val solo: List<SelfCalibration.Outcome> = emptyList(),
        val mistakes: List<Mistakes.Entry> = emptyList(),
        val tally: DoubtTally = emptyMap(),
        val patterns: List<PatternCalls.Call> = emptyList(),
        val data: DataAge.Log = DataAge.Log(),
        val news: List<NewsMoves.Note> = emptyList(),
        val plan: Improve.Plan? = null,
        val clarity: Clarity.Log = Clarity.Log(),
        val wordFit: WordFit.Log = WordFit.Log(),
        val again: AskedAgain.Log = AskedAgain.Log(),
        val wrong: WrongThing.Log = WrongThing.Log(),
        val figure: FigureFirst.Log = FigureFirst.Log(),
        val arms: ArmHabits.Log = ArmHabits.Log(),
        val morning: MorningSense.Log = MorningSense.Log(),
        /** Jarvis's scored ideas with the confidence they were put with, and when Boss last asked for it plainly. */
        val stars: List<HonestStars.Scored> = emptyList(),
        val starsReset: LocalDateTime? = null,
        val hours: TalkHours.Log = TalkHours.Log(),
        val asks: MorningAsks.Log = MorningAsks.Log(),
        val turnDowns: TurnDowns.Log = TurnDowns.Log(),
        val lengths: TopicLength.Log = TopicLength.Log(),
        val usualIndex: UsualIndex.Log = UsualIndex.Log(),
        val nicknames: Nicknames.Log = Nicknames.Log(),
    )

    fun day(d: LocalDate): String = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    /** Within the last [DAYS] days up to [today]. */
    fun thisWeek(d: LocalDate?, today: LocalDate): Boolean = d != null && !d.isBefore(today.minusDays(DAYS - 1)) && !d.isAfter(today)

    private fun chart(minutes: Int) = if (minutes == 60) "1-hour" else "$minutes-minute"

    // ---- the ledger ----------------------------------------------------------------------------------------------

    /** Every learned thing in force at [now], area by area, newest first within each. */
    fun items(i: Inputs, now: LocalDateTime): List<Item> {
        val today = now.toLocalDate()
        val out = ArrayList<Item>()
        // The wordings Boss taught him (each kept only on his yes).
        out += Corrections.fresh(i.words, today).sortedByDescending { it.since ?: LocalDate.MIN }.map { l ->
            Item(Area.WORDS, "\"${l.wrong}\" means \"${l.right}\"", l.since,
                "kept on your yes after you said it another way" + (l.used?.let { "; last used ${day(it)}" } ?: "") + " - a question only",
                "forget the word ${l.wrong}")
        }
        // The routines kept on his yes.
        out += Routine.live(i.routines, today).sortedByDescending { it.since ?: LocalDate.MIN }.map { k ->
            Item(Area.ROUTINES, "${Routine.about(k.key) ?: "your usual question"} ${Routine.whenSaid(k.kind, k.minute, k.day)}", k.since,
                "kept on your yes; said at its time in words only, until ${day(k.until)}", "forget my routine")
        }
        // The alerts said less often: from when the first was held back (since the count last started), and how many this week.
        val window = now.minusDays(AlertSense.WINDOW_DAYS)
        val from = i.alerts.resetAt?.takeIf { it.isAfter(window) } ?: window
        out += AlertSense.quieter(i.alerts, now).map { r ->
            val held = i.alerts.held.filter { it.first == r.kind && it.second.isAfter(from) && !it.second.isAfter(now) }
            val week = held.count { thisWeek(it.second.toLocalDate(), today) }
            val how = if (r.level == AlertSense.Level.HOLD) "only one in ${AlertSense.HOLD_EVERY} aloud" else "every other one aloud"
            Item(Area.ALERTS, "${r.phrase}: $how", held.minOfOrNull { it.second }?.toLocalDate(),
                "you followed up ${r.followed} of my last ${r.said}" + (if (r.muted > 0) " and muted me after ${r.muted}" else "") +
                    (if (week > 0) "; $week kept to the chat this week" else "") + " - safety warnings never", "say everything again")
        }
        // The answer kinds said shorter aloud: Boss kept asking "what?" after them ([Clarity]; kinds only, never words).
        Clarity.shorter(i.clarity, i.tally, now).forEach { r ->
            out += Item(Area.CLARITY, "${r.phrase}: ${r.how()}", Clarity.newest(i.clarity, r.kind)?.toLocalDate(),
                "${r.say()} in the last ${Clarity.WINDOW_DAYS} days; only my voice is shorter, the chat keeps it all", Clarity.UNDO)
        }
        // His confidence words the numbers beside them did not bear out ([WordFit]; his own words only, never Boss's).
        WordFit.misfits(i.wordFit, now).forEach { r ->
            out += Item(Area.WORD_FIT, WordFit.ledgerWhat(r, i.wordFit.off), r.newest.toLocalDate(), WordFit.ledgerWhy(r, i.wordFit.off),
                if (i.wordFit.off) WordFit.REDO else WordFit.UNDO)
        }
        // The market reads Boss asked again within minutes ([AskedAgain]; kinds only): a record, it changes nothing he does.
        AskedAgain.shown(i.again, i.tally, now).forEach { r ->
            out += Item(Area.AGAIN, AskedAgain.ledgerWhat(r), r.newest.toLocalDate(), AskedAgain.ledgerWhy(r), null)
        }
        // The questions he answered with the wrong thing ([WrongThing]; kinds and the way taken only): a record to fix the
        // routing by - it re-routes nothing and has no undo.
        WrongThing.shown(i.wrong, now).forEach { r ->
            out += Item(Area.WRONG_THING, WrongThing.ledgerWhat(r), r.newest.toLocalDate(), WrongThing.ledgerWhy(r), null)
        }
        // The market reads whose figure he says first aloud, Boss having asked them again for it ([FigureFirst]; kinds only).
        FigureFirst.leading(i.figure, now).forEach { r ->
            out += Item(Area.FIGURE_FIRST, FigureFirst.ledgerWhat(r), FigureFirst.newest(i.figure, r.kind)?.toLocalDate(), FigureFirst.ledgerWhy(r), FigureFirst.UNDO)
        }
        // The morning-check items Boss usually leaves as they are, named in a few words aloud ([MorningSense]; keys only).
        MorningSense.usuallyLeft(i.morning, today).forEach { r ->
            out += Item(Area.MORNING, MorningSense.ledgerWhat(r), r.newest, MorningSense.ledgerWhy(r), MorningSense.UNDO)
        }
        // The confidence scores said aloud with their record ([HonestStars]; counts only): the score itself is unchanged.
        HonestStars.honest(i.stars, now, i.starsReset).forEach { r ->
            out += Item(Area.STARS, HonestStars.ledgerWhat(r), r.newest.toLocalDate(), HonestStars.ledgerWhy(r), HonestStars.UNDO)
        }
        // The long unasked briefings said in a sentence aloud outside the hours Boss talks to him ([TalkHours]; days and hours only).
        TalkHours.record(i.hours, now).takeIf { it.learned }?.let { r ->
            out += Item(Area.HOURS, TalkHours.ledgerWhat(r), r.newest, TalkHours.ledgerWhy(r), TalkHours.UNDO)
        }
        // The question Boss asks every morning, offered in one line at the end of the morning check ([MorningAsks]; kinds and days only).
        MorningAsks.usual(i.asks, today).forEach { u ->
            out += Item(Area.MORNING_ASKS, MorningAsks.ledgerWhat(u), u.newest, MorningAsks.ledgerWhy(u), MorningAsks.UNDO)
        }
        // The reasons Boss turns Jarvis's trade ideas down for, said up front before the next idea they fit ([TurnDowns];
        // reason kinds and times only). Speech only: the idea and his answer are unchanged.
        TurnDowns.learned(i.turnDowns, now).forEach { r ->
            out += Item(Area.TURN_DOWNS, TurnDowns.ledgerWhat(r), r.newest.toLocalDate(), TurnDowns.ledgerWhy(r), TurnDowns.UNDO)
        }
        // The topics said in one sentence or in full aloud, as Boss keeps asking for them ([TopicLength]; kinds only).
        TopicLength.learned(i.lengths, now).forEach { r ->
            out += Item(Area.LENGTHS, TopicLength.ledgerWhat(r), r.newest.toLocalDate(), TopicLength.ledgerWhy(r), TopicLength.UNDO)
        }
        // The index Boss means when he names none, from his corrections ([UsualIndex]; indices and times only). Understanding
        // only: a named index always wins and nothing learned acts.
        UsualIndex.learned(i.usualIndex, now)?.let { r ->
            out += Item(Area.USUAL_INDEX, UsualIndex.ledgerWhat(r), r.newest.toLocalDate(), UsualIndex.ledgerWhy(r), UsualIndex.UNDO)
        }
        // The nicknames Boss uses for his arms and positions, each kept on his own pick after Jarvis asked which one
        // ([Nicknames]). Understanding only: each means exactly one, and every action still waits for his confirm.
        i.nicknames.notes.sortedByDescending { it.at }.forEach { n ->
            out += Item(Area.NICKNAMES, Nicknames.ledgerWhat(n), n.at.toLocalDate(), Nicknames.ledgerWhy(n), Nicknames.UNDO)
        }
        // What Boss does with his bots after losing days ([ArmHabits]; switches and signs only): his record - it arms or
        // disarms nothing, changes nothing Jarvis does, and so has no undo.
        ArmHabits.shown(i.arms).forEach { h ->
            out += Item(Area.ARM_HABITS, ArmHabits.ledgerWhat(h), h.newest, ArmHabits.ledgerWhy(h), null)
        }
        // The conditions his own ideas and Solo sit out: the record decides, so it lifts only as the record does.
        fun sitOut(outcomes: List<SelfCalibration.Outcome>, solo: Boolean) {
            val recent = SelfCalibration.recent(outcomes, today)
            SelfCalibration.weakest(outcomes, today).forEach { s ->
                val newest = s.members.mapNotNull { recent.getOrNull(it)?.c?.at?.toLocalDate() }.maxOrNull()
                val what = if (solo) "Solo sits out ${s.what(SoloCalibration.WHO)}" else "I don't take ${s.what()} by myself"
                Item(Area.SIT_OUT, what, newest, (if (solo) s.record("trade") else s.record()) + "; it lifts when the record does", null).also { out += it }
            }
        }
        sitOut(i.paper, false)
        sitOut(i.solo, true)
        // The answer kinds he flags: from the answers Boss marked wrong.
        SelfDoubt.weak(i.mistakes, i.tally, today).forEach { r ->
            val newest = i.mistakes.filter { m -> SelfDoubt.tags(m.said).any { it.key == r.tag.key } }.maxOfOrNull { it.at }?.toLocalDate()
            val how = if (r.level == SelfDoubt.Level.ASK) "I say how I read the question first and ask you to check me" else "I end them with \"check me on this\""
            out += Item(Area.ANSWERS, "${r.tag.phrase}: $how", newest, "${r.say()} in the last ${SelfDoubt.WINDOW_DAYS} days; it lifts as fewer are marked wrong", null)
        }
        // The pattern kinds no longer brought up unasked (still told, with their record, when patterns are asked).
        i.patterns.map { Triple(it.market, it.minutes, it.kind) }.distinct()
            .filter { (m, min, k) -> PatternCalls.quiet(i.patterns, m, min, k, today) }
            .forEach { (m, min, k) ->
                val r = PatternCalls.record(i.patterns, m, min, k, today)
                val newest = i.patterns.filter { it.market == m && it.minutes == min && it.kind == k && it.went[PatternCalls.QUOTE] != null }
                    .maxOfOrNull { it.at }?.toLocalDate()
                out += Item(Area.PATTERNS, "${k.label} on ${m.label} ${chart(min)}: not brought up unasked", newest,
                    "${r.way} of the last ${r.n} went their way within ${PatternCalls.QUOTE} minutes; still told when you ask about patterns", null)
            }
        // His freshness record this week (a record, not a habit: it never changes what he does, only what he tells).
        val oldDays = i.data.days.filter { thisWeek(it.day, today) && (it.spells.isNotEmpty() || it.warned > 0) }
        if (oldDays.isNotEmpty()) {
            val answers = oldDays.sumOf { it.answers }; val warned = oldDays.sumOf { it.warned }; val withheld = oldDays.sumOf { it.withheld }
            out += Item(Area.DATA, "my data was old on ${oldDays.size} of the last $DAYS days", oldDays.maxOf { it.day },
                "$warned of my $answers market answers on those days carried an age warning" + (if (withheld > 0) " and $withheld I would not quote" else "") +
                    "; I say how old it is, never pass it off as live", null)
        }
        // How Nifty moved after each news theme's headlines (a record of timing, never a cause; it only changes what he says).
        NewsMoves.TAGS.map { NewsMoves.record(i.news, it, Market.NIFTY, today) }.filter { it.n > 0 }
            .sortedByDescending { it.newest ?: LocalDate.MIN }
            .forEach { r ->
                out += Item(Area.NEWS, NewsMoves.fact(r)!! + if (r.n < NewsMoves.FEW) " (too few to go by)" else "", r.newest,
                    "timed on the phone's own candles from each story's first headline - timing only, never a cause; it only adds a line when such news comes up", null)
            }
        // His own goals for the week (they only speak, study, ask Boss to teach him or work on paper).
        i.plan?.takeIf { it.goals.isNotEmpty() && !it.week.isBefore(Improve.weekOf(today)) }?.let { p ->
            out += Item(Area.GOALS, "for the week of ${day(p.week)}: " + p.goals.joinToString("; ") { it.text() }, p.week.minusDays(2).takeIf { !it.isAfter(today) } ?: today,
                "set at the weekend review from my own record; they only change how I speak, study or ask you", null)
        }
        return out
    }

    enum class Ask { WEEK, CHANGED, ALL }

    private fun norm(text: String) = " " + spacedWords(text.lowercase().replace("'", "").replace("’", "")) + " "

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?"
    private const val TAIL = "( please| boss| jarvis)* $"
    private const val YOU_LEARNED = "(you (have )?|youve )(learned|learnt)"

    private val ALL = Regex(LEAD + "((show|tell|give|list|read)( me)? )?(everything|all|all the things|what all) (that )?$YOU_LEARNED( about me| from me| so far)*" + TAIL + "|" +
        LEAD + "what all (have you|did you) (learned|learnt|learn)( about me| from me)?" + TAIL + "|" +
        LEAD + "(show me |read me )?(your |the )?(learning|learnings|what ive learned) (ledger|log|list)" + TAIL + "|" +
        LEAD + "(tumne|aapne) (mere baare (mein|me) )?(kya kya|sab kuch) (seekha|sikha)( hai)?" + TAIL + "|" +
        // "What did you learn about me?" (the overview; "what have you learned about me" stays [AboutBoss]'s, what Boss told him).
        LEAD + "(tell me )?what (did you|you) (learned|learnt|learn) (about me|from me)( so far| till now| until now)?" + TAIL + "|" +
        LEAD + "(tumne|aapne|tune|apne) (mere baare (mein|me|main|men) |mujhse |mujhe dekh kar )(kya|kya kya) (seekha|sikha|seekhe|sikhe|jaana|jana)( hai| hain)?" + TAIL + "|" +
        LEAD + "(mere baare (mein|me|main|men) )(tumne|aapne|tune|apne) (kya|kya kya) (seekha|sikha|seekhe|sikhe)( hai| hain)?" + TAIL)
    private val WEEK = Regex(LEAD + "(tell me )?what (have you|did you|you have|youve) (learned|learnt|learn) (this week|lately|recently|in the last (7|seven) days|over the (last )?week|since monday)" + TAIL + "|" +
        LEAD + "what (is|s) new in what $YOU_LEARNED" + TAIL + "|" +
        LEAD + "(is|iss) (week|hafte) (tumne |aapne )?kya (seekha|sikha)( hai)?" + TAIL)
    private val CHANGED = Regex(LEAD + "what (has |s )?changed (in|about) (how|the way) you (work|behave|answer|talk|speak|do things)( this week| lately| recently)?" + TAIL + "|" +
        LEAD + "how (have you|has your (behaviou?r|way of working)|has the way you work) changed( this week| lately| recently)?" + TAIL + "|" +
        LEAD + "what (have you|did you) change(d)? (in|about) (yourself|how you work)( this week| lately| recently)?" + TAIL)

    /** "What have you learned this week?", "what changed in how you work?", "show me everything you've learned about me", else null. */
    fun asked(text: String): Ask? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Ask?>(64)

    private fun askedFresh(text: String): Ask? {
        val t = norm(text)
        return when {
            ALL.containsMatchIn(t) -> Ask.ALL
            WEEK.containsMatchIn(t) -> Ask.WEEK
            CHANGED.containsMatchIn(t) -> Ask.CHANGED
            else -> null
        }
    }

    const val NEVER_ACTS = "Nothing I learn ever places, changes or closes a trade, or touches a setting."
    const val UNLOCK = "Unlock the phone for your wordings, routines, my records and my goals, Boss."

    /** The answer to [ask] from [items] ([locked]: Boss's own words and records left out). */
    fun say(items: List<Item>, ask: Ask, today: LocalDate, locked: Boolean): String {
        val shown = items.filter { !locked || !it.personal }
            .filter { ask == Ask.ALL || thisWeek(it.on, today) }
            .filter { ask != Ask.CHANGED || (it.area != Area.DATA && it.area != Area.NEWS && it.area != Area.AGAIN && it.area != Area.WRONG_THING && it.area != Area.ARM_HABITS) }
        val hidden = locked && items.any { it.personal }
        val tail = listOfNotNull(if (hidden) UNLOCK else null, NEVER_ACTS).joinToString(" ")
        if (shown.isEmpty()) {
            val older = items.count { !locked || !it.personal }
            val none = when (ask) {
                Ask.ALL -> "I haven't learned anything about you or my work yet, Boss: I keep a wording or a routine only on your yes, and the rest comes from my own record."
                Ask.WEEK -> "Nothing new in the last $DAYS days, Boss" + (if (older > 0) " - $older older thing${if (older == 1) "" else "s"} I learned still hold${if (older == 1) "s" else ""}; ask \"show me everything you've learned\"." else ".")
                Ask.CHANGED -> "Nothing changed in how I work in the last $DAYS days, Boss" + (if (older > 0) " - what I learned before still holds; ask \"show me everything you've learned\"." else ".")
            }
            return "$none $tail"
        }
        val head = when (ask) {
            Ask.ALL -> "Everything I've learned, Boss:"
            Ask.WEEK -> "What I learned in the last $DAYS days, Boss:"
            Ask.CHANGED -> "What changed in how I work in the last $DAYS days, Boss:"
        }
        val body = Area.entries.mapNotNull { a ->
            val xs = shown.filter { it.area == a }
            if (xs.isEmpty()) null
            else "${a.title}: " + xs.take(SHOW).joinToString("; ") { it.text() } + (if (xs.size > SHOW) "; and ${xs.size - SHOW} more" else "") + "."
        }
        val undo = if (locked) null else "Say \"undo everything you learned this week\" to reset this week's learning - I'll ask you first."
        return listOfNotNull(head, body.joinToString(" "), tail, undo).joinToString(" ")
    }

    // ---- undoing the week ------------------------------------------------------------------------------------------

    private val UNDO = Regex(LEAD + "(undo|forget|reset|unlearn|clear|drop|wipe|erase|reverse) (everything |all |all of |what )?(that )?(you (have )?|youve )?(learned|learnt|learn|picked up) (this week|in the last (7|seven) days|over the (last )?week|since monday)" + TAIL + "|" +
        LEAD + "(undo|reset|unlearn|reverse|wipe) (this weeks|the weeks|your) (learning|learnings)( this week)?" + TAIL + "|" +
        LEAD + "unlearn (this|the) week" + TAIL)

    /** "Undo everything you learned this week". */
    fun undoAsked(text: String): Boolean = UNDO.containsMatchIn(norm(text))

    /** What undoing the week resets: wordings and routines kept in the last [DAYS] days, the alert count, this week's own goals. */
    data class Undo(val words: List<Corrections.Learned>, val routines: List<Routine.Kept>, val alerts: List<AlertSense.Record>, val goals: Int,
                    val clarity: List<Clarity.Record> = emptyList(), val figure: List<FigureFirst.Record> = emptyList(),
                    val morning: List<MorningSense.Record> = emptyList(), val stars: List<HonestStars.Record> = emptyList(),
                    val hours: List<Int> = emptyList(), val asks: List<MorningAsks.Usual> = emptyList(),
                    val turnDowns: List<TurnDowns.Record> = emptyList(), val lengths: List<TopicLength.Record> = emptyList(),
                    val usualIndex: List<UsualIndex.Record> = emptyList(), val nicknames: List<Nicknames.Note> = emptyList()) {
        val empty: Boolean get() = words.isEmpty() && routines.isEmpty() && alerts.isEmpty() && goals == 0 && clarity.isEmpty() && figure.isEmpty() && morning.isEmpty() &&
            stars.isEmpty() && hours.isEmpty() && asks.isEmpty() && turnDowns.isEmpty() && lengths.isEmpty() && usualIndex.isEmpty() && nicknames.isEmpty()
    }

    fun undo(i: Inputs, now: LocalDateTime): Undo {
        val today = now.toLocalDate()
        return Undo(
            Corrections.fresh(i.words, today).filter { thisWeek(it.since, today) },
            Routine.live(i.routines, today).filter { thisWeek(it.since, today) },
            AlertSense.quieter(i.alerts, now),
            i.plan?.takeIf { it.week == Improve.weekOf(today) }?.goals?.size ?: 0,
            Clarity.shorter(i.clarity, i.tally, now),
            FigureFirst.leading(i.figure, now),
            MorningSense.usuallyLeft(i.morning, today),
            HonestStars.honest(i.stars, now, i.starsReset),
            TalkHours.record(i.hours, now).hours,
            MorningAsks.usual(i.asks, today),
            TurnDowns.learned(i.turnDowns, now),
            TopicLength.learned(i.lengths, now),
            listOfNotNull(UsualIndex.learned(i.usualIndex, now)),
            Nicknames.week(i.nicknames, today))
    }

    /** [words] without those kept in the last [DAYS] days (the rest, and undated ones, stay). */
    fun keepWords(words: List<Corrections.Learned>, today: LocalDate): List<Corrections.Learned> = words.filter { !thisWeek(it.since, today) }

    /** [kept] without the routines kept in the last [DAYS] days. */
    fun keepRoutines(kept: List<Routine.Kept>, today: LocalDate): List<Routine.Kept> = kept.filter { !thisWeek(it.since, today) }

    /** [plan] with this week's own goals dropped (graded weeks and the last grade kept), or null when there are none. */
    fun dropGoals(plan: Improve.Plan?, today: LocalDate): Improve.Plan? =
        plan?.takeIf { it.week == Improve.weekOf(today) && it.goals.isNotEmpty() }?.copy(goals = emptyList())

    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"

    private fun parts(u: Undo): List<String> = listOfNotNull(
        if (u.words.isEmpty()) null else plural(u.words.size, "wording") + " (" + u.words.take(SHOW).joinToString(", ") { "\"${it.wrong}\"" } + (if (u.words.size > SHOW) ", ..." else "") + ")",
        if (u.routines.isEmpty()) null else plural(u.routines.size, "routine") + " kept on your yes",
        if (u.alerts.isEmpty()) null else "the alerts I say less often (" + u.alerts.joinToString(", ") { it.phrase } + ") - aloud every time again",
        if (u.clarity.isEmpty()) null else "the answers I say shorter aloud (" + u.clarity.joinToString(", ") { it.phrase } + ") - said as usual again",
        if (u.figure.isEmpty()) null else "the market reads I start with the figure aloud (" + u.figure.joinToString(", ") { it.phrase } + ") - in the usual order again",
        if (u.morning.isEmpty()) null else "the morning-check items I name in a few words aloud (" + u.morning.joinToString(", ") { it.phrase } + ") - read out in full again",
        if (u.stars.isEmpty()) null else "the confidence scores I say with their record aloud (" + u.stars.joinToString(", ") { it.phrase } + ") - said plainly again",
        if (u.hours.isEmpty()) null else "the briefings I say shorter aloud outside your usual hours (" + TalkHours.spans(u.hours) + ") - in full at any hour again",
        if (u.asks.isEmpty()) null else "the morning question I offer at the end of the morning check (" + u.asks.joinToString(", ") { it.phrase } + ") - no longer offered",
        if (u.turnDowns.isEmpty()) null else "your reasons for turning down my ideas, said up front (" + u.turnDowns.joinToString(", ") { it.phrase } + ") - no longer said",
        if (u.lengths.isEmpty()) null else "the topics I say in a sentence or in full aloud (" + u.lengths.joinToString(", ") { it.phrase } + ") - at the usual length again",
        if (u.usualIndex.isEmpty()) null else "the index I take when you name none (" + u.usualIndex.joinToString(", ") { it.phrase } + ") - Nifty again",
        if (u.nicknames.isEmpty()) null else "the nicknames you use for your arms and positions (" + u.nicknames.take(SHOW).joinToString(", ") { "\"${it.words}\"" } +
            (if (u.nicknames.size > SHOW) ", ..." else "") + ") - forgotten",
        if (u.goals == 0) null else "my ${plural(u.goals, "goal")} for this week")

    const val ONLY = "Only learned behaviour: never a setting, your PIN, Live, AI trading, a guard or the Google speech choice. " +
        "The answers you marked wrong, the trades and the patterns' outcomes stay - they're records, not habits."

    const val NOTHING = "There's nothing I learned in the last $DAYS days to undo, Boss: no wording or routine kept, no alert held back, no answer said shorter or with its figure first and no goals of mine this week."

    /** Put to Boss before anything is reset. */
    fun offer(u: Undo): String = "Boss, shall I undo what I learned in the last $DAYS days? That resets " + parts(u).joinToString("; ") + ". $ONLY"

    /** Said once it is done. */
    fun done(u: Undo): String = if (u.empty) NOTHING else "Done, Boss: undone - " + parts(u).joinToString("; ") + ". $ONLY"
}
