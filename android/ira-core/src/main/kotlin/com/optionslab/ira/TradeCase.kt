package com.optionslab.ira

import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * "Make the case", "pros and cons of trading now", "talk me through it" (autonomy and reasoning, round 4, 2026-10-05):
 * the trade check reasoned out across everything Jarvis already knows, instead of one verdict. He gathers the facts
 * that bear on trading now - the session and the app's own guards ([TradeCheck.Now]), today's risks (events, expiry,
 * India VIX, gaps, sudden moves), what stands out about today against the recent sessions ([MarketStory]), what is
 * coming in the next days ([Events]), the arms' tested records, his own record in conditions like now's
 * ([SelfCalibration]) - and, on an unlocked phone only, Boss's own side: his day against his loss limit and his goals
 * ([Goals]), the rules he asked Jarvis to keep ([BossRules]), what he told Jarvis about himself ([AboutBoss]) and his
 * own habits around trades ([PreTrade]). They are laid out as facts on the steady side, facts on the careful side,
 * what stands out and what Jarvis would watch, with the facts that pull apart said plainly and his own numbers checked
 * against each other ([Consistency]) - never a buy or sell, never a direction, never a verdict on whether to
 * trade: it always ends that the decision is Boss's. Words only: building the case changes nothing. Pure.
 */
object TradeCase {
    /** At most this many facts on each side, things standing out, and things to watch. */
    const val MAX_STEADY = 4
    const val MAX_CAREFUL = 7
    const val MAX_STANDOUT = 2
    const val MAX_WATCH = 4
    /** Events this many days ahead are told as coming up. */
    const val AHEAD_DAYS = 3L
    /** A rule of Boss's starting within this many minutes is something to watch. */
    const val RULE_SOON = 90

    private const val OPEN = 9 * 60 + 15
    private const val CUTOFF = 14 * 60 + 55

    /** The closing words: the decision is always Boss's. */
    const val YOURS = "Those are the facts as I have them, not advice: whether to trade, and how, is your call, Boss."
    const val LOCKED_NOTE = "Your own day, goals and rules are left out while the phone is locked."

    /**
     * Boss's own side, read only on an unlocked phone (null when locked): [notes] what he asked Jarvis to remember (his
     * rules and facts about himself), [goals] his goals as they stand, [own] his own closed trades (not the bots'),
     * [maxTrades] his "no more than N trades a day" goal.
     */
    data class Mine(
        val notes: List<String> = emptyList(),
        val goals: List<Goals.Status> = emptyList(),
        val own: List<Insights.Trip> = emptyList(),
        val maxTrades: Int? = null,
    )

    /**
     * Everything the case is built from: [now] what the trade check reads ([TradeCheck.Now]), [at] the time now,
     * [bars] the indices' and India VIX's 1-minute candles on the phone, [upcoming] the calendar ahead, [calibration]
     * Jarvis's own scored ideas, [regime] the market's regime now and [ivRank] how dear options are (null: not known),
     * [mine] Boss's own side (null on a locked phone), [locked] the phone is locked (Boss's day is then never said),
     * [chain] the option chain's facts ([ChainIntel.caseFacts]: the straddle's implied move, the biggest OI, the skew,
     * with the chain's time; market data, so said on a locked phone too).
     */
    data class Input(
        val now: TradeCheck.Now,
        val at: LocalDateTime,
        val bars: Map<Market, List<Candle>> = emptyMap(),
        val upcoming: List<Events.Event> = emptyList(),
        val calibration: List<SelfCalibration.Outcome> = emptyList(),
        val regime: Regime.Kind? = null,
        val ivRank: Double? = null,
        val mine: Mine? = null,
        val locked: Boolean = false,
        val chain: List<String> = emptyList(),
        /**
         * The Nifty chain read the [chain] facts came from (null: none today): its call and put writing are set against
         * the day's structure ([Consistency.tensions]) and its spot against the candle of the same minute ([Consistency.priceCheck]).
         */
        val chainRead: ChainIntel.Read? = null,
    )

    /** At most this many option-chain facts. */
    const val MAX_CHAIN = 3

    /** The case: how the market is moving ([reads]), facts each way, what stands out, the option chain's facts, what to watch. */
    data class Case(
        val reads: List<String>,
        val steady: List<String>,
        val careful: List<String>,
        val standout: List<String>,
        val watch: List<String>,
        val locked: Boolean,
        val chain: List<String> = emptyList(),
        /** Nifty's intraday structure so far in one line ([Structure.caseLine]), a fact; null without enough of today's candles. */
        val structure: String? = null,
        /** Facts above that point different ways, said plainly ([Consistency.tensions]). */
        val tensions: List<String> = emptyList(),
        /** Jarvis's own numbers disagreeing (the chain's spot against the candles), with the source he goes by; null when they agree. */
        val mismatch: String? = null,
    ) {
        fun say(): String {
            val parts = ArrayList<String>()
            parts += "Here's the case as I see it, Boss - the facts both ways."
            if (reads.isNotEmpty()) parts += reads.joinToString(" ") + " That is how the market is moving now, not a forecast."
            if (careful.isNotEmpty()) parts += "On the careful side: " + careful.joinToString(" ")
            if (steady.isNotEmpty()) parts += "On the steady side: " + steady.joinToString(" ")
            if (standout.isNotEmpty()) parts += "What stands out about today: " + standout.joinToString(" ")
            structure?.let { parts += it }
            if (chain.isNotEmpty()) parts += "From the option chain: " + chain.joinToString(" ")
            if (tensions.isNotEmpty()) parts += "Where the facts pull apart: " + tensions.joinToString(" ")
            mismatch?.let { parts += it }
            if (watch.isNotEmpty()) parts += "What I'd watch: " + watch.joinToString("; ") { it.trimEnd('.') } + "."
            if (locked) parts += LOCKED_NOTE
            parts += YOURS
            return parts.joinToString(" ")
        }
    }

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |boss |so |and |please |ok |okay )*"
    private const val NOW = "( now| today| right now| at the moment| here)?"
    private const val TRADING = "(trading|a trade|trades|taking a trade|taking trades|trade)"
    private val ASKED = Regex(
        "^ $LEAD(make|give me|build|lay out|put) (me )?(the |a |your )?case( for and against| either way| both ways)?( (for |on |about )?$TRADING$NOW)?( boss| jarvis| please)? $|" +
        "^ $LEAD(what are )?(the )?(pros and cons|for and against|case for and against|arguments for and against|plus and minus|plusses and minuses)( of| for| on)? $TRADING$NOW( boss| jarvis| please)? $|" +
        // "Case for and against", "pros and cons?" with nothing after it: trading now (round 10).
        "^ $LEAD(what are )?(the )?(pros and cons|case for and against|arguments for and against)( boss| jarvis| please)? $|" +
        "^ $LEAD(think|reason|walk me|talk me|take me)( it)? (through|out)( it)?( whether| if)?( i should)?( be)?( $TRADING)?$NOW( boss| jarvis| please)? $|" +
        "^ $LEAD(explain|break down|unpack) (the |your |that )?trade check( in detail| for me)?( boss| jarvis| please)? $|" +
        "^ $LEAD(full|detailed|the full|a full|a detailed|the detailed) trade check( please)?( boss| jarvis)? $|" +
        "^ $LEAD(should|shall|can) i (trade|be trading)$NOW (and )?(why|why or why not|why not|explain|tell me why) $|" +
        "^ $LEAD(why|why or why not) (should|shouldn t|should not) i (trade|be trading)$NOW( boss| jarvis)? $|" +
        "^ $LEAD(what would you|what do you|what should we) (watch|keep an eye on|keep an eye out for)$NOW( before (trading|a trade))?( boss| jarvis)? $|" +
        // ("Aaj trade karu ya nahi aur kyun", routing audit 5 Oct: it went to the market's why.)
        "^ $LEAD(aaj |abhi )?(trade (karu|karun|karna|karoon|lu|loon|lun) (ya nahi|ya nahin|kya)|kya trade (karu|karun|karna)) (aur )?(samjhao|samjha do|kyun|kyu|kyon|explain karo|pura batao) $"
    )

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "

    /** "Make the case", "pros and cons of trading now", "talk me through it", "should I trade now and why?". Whole questions only. */
    fun asked(text: String): Boolean = ASKED.containsMatchIn(norm(text))

    private fun hm(minute: Int) = "%02d:%02d".format(Locale.ENGLISH, minute / 60, minute % 60)
    private fun pct(x: Double) = "%+.1f%%".format(Locale.ENGLISH, x)
    private fun one(x: Double) = "%.1f".format(Locale.ENGLISH, x)
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun amt(x: Double) = "Rs " + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun span(minutes: Int) = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes minutes"

    /** Builds the case from [i]. Nothing here acts. */
    fun build(i: Input): Case {
        val n = i.now
        // (The plain "all is working" facts go last on the steady side, after what says more.)
        val steady = ArrayList<String>(); val basics = ArrayList<String>(); val careful = ArrayList<String>(); val standout = ArrayList<String>(); val watch = ArrayList<String>()
        val m = n.minute
        val today = i.at.toLocalDate()
        val open = n.tradingDay && n.marketOpen
        val mine = if (i.locked) null else i.mine

        // The session: can anything happen at all, and how much of it is left.
        when {
            !n.tradingDay -> careful += "The market is closed today."
            !n.marketOpen -> careful += if (m < OPEN) "The market opens at 09:15." else "The market is closed for the day."
            else -> {
                if (m < OPEN + 5) { careful += "It's the first five minutes of the session, when prices swing hardest."; watch += "09:20, when the first five minutes are over." }
                when {
                    m >= CUTOFF -> careful += "It's past 14:55: the app takes no new positions this late."
                    m >= 14 * 60 + 30 -> careful += "Only ${span(CUTOFF - m)} are left before 14:55, when the app stops taking new positions."
                    m >= OPEN + 5 -> basics += "There is time in the session: ${span(CUTOFF - m)} until 14:55, when the app stops taking new positions."
                }
            }
        }
        // The plumbing and the app's own guards.
        if (open) { if (n.pricesFresh) basics += "Live prices are coming in." else careful += "Live prices are not coming in, so I'd be reading stale numbers." }
        if (n.liveMode && !n.zerodhaLoggedIn) careful += "Zerodha is not logged in today."
        if (n.liveMode && n.staticIpOk == false) careful += "This phone is not on your registered static IP, so Zerodha would refuse new positions."
        if (n.killSwitch) careful += "The kill switch is on, so the app places no orders."
        if (n.breakerTripped) careful += "The daily loss limit was hit today, so the app's loss breaker is tripped."
        if (!n.killSwitch && !n.breakerTripped && n.tradingDay) basics += "The app's guards are clear: kill switch off, loss breaker not tripped."
        if (n.botsStopped) careful += "The bots are stopped for today."

        // Boss's day against his limit (his account: never on a locked phone).
        if (!i.locked) n.dayPnl?.let { p ->
            val limit = n.dayLossLimit
            if (limit > 0) {
                val used = (-p / limit * 100).toInt()
                if (p <= -0.5 * limit) careful += "You are down ${amt(p)} today, $used% of your ${amt(limit)} daily loss limit."
                else steady += "Your day so far is ${rs(p)}, well inside your ${amt(limit)} daily loss limit."
                if (open) watch += "Room to the daily loss limit: ${amt(maxOf(0.0, limit + p))} before the app's breaker trips."
            } else steady += "Your day so far is ${rs(p)}."
        }
        // His goals, rules, what he told me about himself, his own habits around trades.
        if (mine != null) caseOfMine(mine, i, open, steady, careful, watch)

        // Today's risks.
        if (n.tradingDay) {
            if (n.eventsToday.isEmpty()) basics += "No events on the calendar today."
            n.eventsToday.forEach { careful += "Event today: $it." }
            if (n.expiryToday) {
                careful += "It's an expiry day: option prices decay and jump fast."
                if (open && m < 14 * 60 + 30) watch += "the last hour of expiry, when decay is fastest"
            }
        }
        n.vix?.let { v ->
            if (v >= 22) careful += "India VIX is high at ${one(v)}: options are dear and moves are wide."
            else steady += "India VIX is ${one(v)}, not high."
        }
        n.vixChangePct?.let { c -> if (c >= 8) { careful += "India VIX is ${pct(c)} today: fear is rising."; watch += "India VIX, already ${pct(c)} today" } }
        n.gapPct?.let { g ->
            if (abs(g) >= 1.0 && open && m < 9 * 60 + 45) { careful += "The market opened with a ${pct(g)} gap."; watch += "09:45, when the opening gap has had 30 minutes to settle" }
        }
        n.indexChangePct?.let { c -> if (abs(c) >= 1.5) careful += "BankNifty is already ${pct(c)} on the day: much of a move has run before a late entry." }
        val b = n.burst30Pct; val u = n.usual30Pct
        if (b != null && u != null && u > 0) {
            if (abs(b) >= 3 * u && abs(b) >= 0.4) {
                careful += "A sudden ${pct(b)} move in the last 30 minutes, ${one(abs(b) / u)} times the usual."
                watch += "whether the last 30 minutes' move settles"
            } else if (open) steady += "No sudden move in the last 30 minutes."
        }
        n.openingRangeRatio?.let { r ->
            val orb = n.armsOn.any { it.startsWith("ORB") }
            if (r < 0.7) careful += "Today's opening range (09:15-10:00) is narrow, ${one(r)} times its 20-day average" +
                (if (orb) ": the kind of day ORB breakouts failed most in testing." else ".")
            else if (r > 1.3) careful += "Today's opening range (09:15-10:00) is wide, ${one(r)} times its 20-day average."
        }

        // What stands out about today against the recent sessions on the phone.
        if (n.tradingDay && i.bars.isNotEmpty()) {
            val reads = MarketStory.INDICES.mapNotNull { mk -> i.bars[mk]?.let { MarketStory.read(mk, it, today) } }
            if (reads.any { it.avgRange != null }) {
                val vix = i.bars[Market.VIX]?.let { MarketStory.read(Market.VIX, it, today) }
                val facts = MarketStory.differences(reads, vix, !open && m >= OPEN)
                    // (India VIX's move is already said above when it is big.)
                    .filterNot { it.kind == "vix" && (n.vixChangePct ?: 0.0) >= 8 }
                if (facts.isEmpty()) steady += "Nothing stands out about today: ranges, moves and gaps are within their usual sizes."
                else standout += facts.take(MAX_STANDOUT).map { it.text }
            }
        }

        // My own record in conditions like now's (the side and the kind of idea are not known here, so not judged).
        calibration(i).let { (bad, good) -> careful += bad; steady += good }

        // The arms' tested records.
        for (a in n.armsOn) {
            val rec = TradeCheck.RECORD[a] ?: continue
            val years = "(${rs(rec.first)}, ${rs(rec.second)} a lot)"
            when {
                rec.first > 0 && rec.second > 0 -> steady += "Tested: $a made money in both years $years."
                rec.first > 0 || rec.second > 0 -> careful += "Tested: $a made money in one year and lost in the other $years."
                else -> careful += "Tested: $a lost in both years $years."
            }
        }

        // What is coming in the next days.
        i.upcoming.filter { it.day.isAfter(today) && !it.day.isAfter(today.plusDays(AHEAD_DAYS)) }.take(2)
            .forEach { watch += "coming up: " + Events.line(it, today).removePrefix("Event ").trimEnd('.') }

        // Facts pulling different ways, and my own numbers disagreeing: said plainly, not smoothed over (market data only).
        val niftyBars = if (n.tradingDay) i.bars[Market.NIFTY] else null
        val chainToday = i.chainRead?.takeIf { it.at.toLocalDate() == today && it.underlying.equals(Market.NIFTY.name, true) }
        val structureRead = niftyBars?.let { runCatching { Structure.read(Market.NIFTY, it, today) }.getOrNull() }
        val tensions = runCatching { Consistency.tensions(Consistency.leans(structureRead, chainToday, n.vixChangePct)) }.getOrDefault(emptyList())
        val mismatch = if (niftyBars != null && chainToday != null)
            runCatching { Consistency.priceCheck(Market.NIFTY, niftyBars, Consistency.chainQuote(chainToday))?.text }.getOrNull() else null

        return Case(n.reads, (steady + basics).distinct().take(MAX_STEADY), careful.distinct().take(MAX_CAREFUL),
            standout.take(MAX_STANDOUT), watch.distinct().take(MAX_WATCH), i.locked, i.chain.distinct().take(MAX_CHAIN),
            // Nifty's structure so far from today's candles (market data, so said on a locked phone too): a fact, not a call.
            niftyBars?.let { runCatching { Structure.caseLine(Market.NIFTY, it, today) }.getOrNull() },
            tensions, mismatch)
    }

    /** Boss's own side: his goals, his rules, what he told about himself and his habits around a trade. */
    private fun caseOfMine(me: Mine, i: Input, open: Boolean, steady: MutableList<String>, careful: MutableList<String>, watch: MutableList<String>) {
        val n = i.now; val m = n.minute
        // His goals.
        val off = me.goals.filter { it.broken || it.near || it.met }
        off.forEach { st ->
            val g = st.goal
            careful += when {
                g.kind == Goals.Kind.TARGET && st.met -> "Your own goal to ${g.text()} is reached."
                g.kind == Goals.Kind.MAX_TRADES -> "Your own goal of ${g.text()} is reached."
                st.broken -> "Your own goal to ${g.text()} is already broken."
                else -> "Your own goal to ${g.text()} is close: most of it is used."
            }
        }
        if (me.goals.isNotEmpty() && off.isEmpty()) steady += "Your own goals are inside their limits so far."
        me.goals.firstOrNull { it.goal.kind == Goals.Kind.TARGET && !it.met }?.let { watch += it.text.trimEnd('.') }

        // What he told me about himself (said in his own words).
        val about = AboutBoss.recall(me.notes, AboutBoss.Moment(i.at.toLocalDate(), AboutBoss.At.CHECK, n.dayPnl, n.expiryToday))
        about?.let { careful += it }

        // His rules: in force now, or starting soon.
        for (r in me.notes.mapNotNull { BossRules.of(it) }.distinctBy { it.note }) {
            if (about?.contains(r.note) == true) continue
            when (r.kind) {
                BossRules.Kind.NOT_BEFORE -> if (m < r.minute!!) {
                    careful += "You asked me to remember \"${r.note}\", and it's ${hm(m)}."
                    watch += "${hm(r.minute)}, when your rule \"${r.note}\" no longer holds"
                }
                BossRules.Kind.NOT_AFTER -> if (m >= r.minute!!) careful += "You asked me to remember \"${r.note}\", and it's ${hm(m)}."
                    else if (open && r.minute - m <= RULE_SOON) watch += "${hm(r.minute)}, when your rule \"${r.note}\" starts (in ${span(r.minute - m)})"
                BossRules.Kind.AVOID_EXPIRY -> if (n.expiryToday) careful += "It's an expiry day, and you asked me to remember \"${r.note}\"."
                BossRules.Kind.AVOID_MARKET -> watch += "your own rule \"${r.note}\""
            }
        }

        // His habits around a trade (just after a loss, past his usual day, the first five minutes).
        if (open) careful += PreTrade.reminders(me.own, i.at, emptyList(), null, n.expiryToday, me.maxTrades)
            .filterNot { it.startsWith("It's the first five minutes") }
    }

    /**
     * Jarvis's own record in the conditions now - the time of day, a sideways or volatile market, how dear options are
     * (the side and kind of idea are left out: no idea is on the table) - as (clearly bad or losing, clearly good). Said
     * only; it never changes what Jarvis or Boss may do.
     */
    internal fun calibration(i: Input): Pair<List<String>, List<String>> {
        if (i.calibration.isEmpty()) return emptyList<String>() to emptyList()
        // The tags now (no side or kind: a trend "with" or "against" needs a side, so only sideways or volatile is judged).
        val now = SelfCalibration.tags(SelfCalibration.Conditions(i.at, Market.NIFTY, true, "", i.regime, i.ivRank))
            .filter { it.dim == SelfCalibration.Dim.TIME || it.dim == SelfCalibration.Dim.OPTIONS ||
                (it.dim == SelfCalibration.Dim.TREND && (it.key == "SIDEWAYS" || it.key == "VOLATILE")) }
        val here = SelfCalibration.slices(i.calibration, i.at.toLocalDate()).filter { s -> s.tags.isNotEmpty() && now.containsAll(s.tags) }
        val worst = compareBy<SelfCalibration.Slice>({ it.rate }, { it.net })
        val bad = (here.filter { it.poor }.sortedWith(worst).firstOrNull()?.let { "My own ideas ${it.what("").trim()} have a clearly poor record (${it.record()}), so I sit those out." }
            ?: here.filter { it.weak }.sortedWith(worst).firstOrNull()?.let { "My own ideas ${it.what("").trim()} have been losing (${it.record()}), so I take those at half size." })
        val good = here.filter { it.strong }.sortedWith(compareByDescending<SelfCalibration.Slice> { it.rate }.thenByDescending { it.net }).firstOrNull()
            ?.let { "My own ideas ${it.what("").trim()} have held up (${it.record()}) - that never makes me take more." }
        return listOfNotNull(bad) to listOfNotNull(good)
    }
}
