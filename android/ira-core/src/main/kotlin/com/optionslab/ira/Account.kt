package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale

/**
 * Everything the app knows about the owner's own trading and itself, as the app read it - its orders (paper and
 * Zerodha), positions, P&L today and by day, every strategy and arm, risk limits, stops and targets, alarms, settings
 * and status. The app hands it here (rule 1: nothing outside the app); Ira only reads it out as facts, never advice.
 */
enum class Section(val title: String) {
    STATUS("App status"), SETTINGS("Settings"), FUNDS("Funds"), ORDERS("Orders"), POSITIONS("Positions"), PNL("P&L today"),
    HISTORY("P&L by day"), STRATEGIES("Strategies"), RISK("Risk limits"), PROTECTIONS("Stops and targets"), ALARMS("Alarms"),
    HOWTO("Where to find it"), EVENTS("Events"), CHAIN("Option chain"), FLOWS("Institutional flows"), REVIEW("Review"),
    STUDY("Jarvis's study"), ACTIVITY("What I did"), READY("Ready for live"), REGIME("Market regime"), LOSSES("Why trades lost"),
    WHATIF("What if"), CHANGES("Settings changes"), EXPLAIN_POS("Your positions explained"),
    SEARCH("Your trades found"), TIMEOFDAY("Your time of day"), REASONS("Your reasons"), MISTAKES("Mistakes noted"),
    MOVE("If the market moves"), RANK("Your positions ranked"), REPLAY("Your trades replayed"),
    MONTH("Your month reviewed"), CHARGES("Your charges"), HEALTH("Your positions' health"),
    BOTS("Your strategies' health"), TAX("Your tax-year records"), NEED("What has to be true for your position"),
    STREAKS("Your streaks"), NUMBERS("Your trading numbers"),
}

/** Fact lines per section, each a finished sentence; [mode] "Paper" or "Live". */
data class AppView(val mode: String, val lines: Map<Section, List<String>>)

/** Builders the app uses to turn its records into fact lines (pure, so they are tested here). */
object AppFacts {
    fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.2f".format(Locale.ENGLISH, kotlin.math.abs(x))
    fun amt(x: Double) = "Rs " + "%,.2f".format(Locale.ENGLISH, x)
    fun px(x: Double) = "%,.2f".format(Locale.ENGLISH, x)

    data class Held(val symbol: String, val qty: Int, val avg: Double, val ltp: Double, val pnl: Double)
    /** One of today's orders; [by] is who placed it ("Manual · Ira", a strategy's name...). */
    data class OrderLine(val time: String, val symbol: String, val action: String, val qty: Int, val status: String,
                         val avgPrice: Double, val by: String?, val reason: String? = null,
                         /** The order's id, said as "order #a1b2c3d4" (its last 8 characters). */ val id: String? = null)
    /** A strategy or arm: [kind] "Strategy", "Pine", "ORB"...; [todayPnl] in rupees when known; [holding] what it holds now. */
    data class ArmLine(val name: String, val kind: String, val on: Boolean, val detail: String, val todayPnl: Double?,
                       val holding: String?, val tradesToday: Int = 0)

    /** [from]: the number of this account's first open order in Jarvis's list ("cancel order 2"). */
    fun orders(account: String, o: List<OrderLine>, byWho: Boolean, from: Int = 1): List<String> {
        if (o.isEmpty()) return listOf("No orders on $account today.")
        val done = o.count { it.status.equals("COMPLETE", true) }
        val open = o.count { it.status.equals("OPEN", true) || it.status.contains("PENDING", true) || it.status.contains("TRIGGER", true) }
        val rej = o.count { it.status.equals("REJECTED", true) || it.status.equals("CANCELLED", true) }
        val out = ArrayList<String>()
        out += "$account: ${o.size} order${if (o.size > 1) "s" else ""} today, $done filled, $open open, $rej rejected or cancelled."
        o.takeLast(5).reversed().forEach { out += "$account order ${it.time}: ${it.action} ${it.qty} ${it.symbol}, ${it.status.lowercase()}" +
            (if (it.avgPrice > 0) " at ${px(it.avgPrice)}" else "") + (it.by?.let { b -> " ($b)" } ?: "") +
            (it.id?.trim()?.takeIf { s -> s.isNotEmpty() }?.let { s -> ", order #" + s.takeLast(8) } ?: "") + "." }
        val working = o.filter { isOpen(it.status) }
        if (working.isNotEmpty()) out += "$account open orders: " + working.mapIndexed { i, w -> "${from + i}. ${w.action} ${w.qty} ${w.symbol}" }.joinToString("; ") + "."
        o.lastOrNull { it.reason != null && it.status.equals("REJECTED", true) }?.let { out += "$account's last rejection: ${it.reason!!.trimEnd('.')}." }
        if (byWho) out += "$account orders by who placed them: " + o.groupBy { it.by ?: "unknown" }.entries.sortedByDescending { it.value.size }
            .joinToString(", ") { "${it.key} ${it.value.size}" } + "."
        return out
    }

    fun isOpen(status: String) = status.equals("OPEN", true) || status.contains("PENDING", true) || status.contains("TRIGGER", true)

    /** [from]: the number of this account's first position in Jarvis's list ("close position 2"). */
    fun positions(account: String, p: List<Held>, from: Int = 1): List<String> =
        if (p.isEmpty()) listOf("No open positions on $account.")
        else listOf("$account: ${p.size} open position${if (p.size > 1) "s" else ""}.") +
            p.mapIndexed { i, it -> "${from + i}. $account position ${it.symbol}: ${it.qty} at ${px(it.avg)}, now ${px(it.ltp)}, ${rs(it.pnl)}." }

    /**
     * Today's P&L line. [day] is BEFORE charges (Boss, 5 Oct: as Zerodha shows its P&L), [realized] + [unrealized] its
     * parts; [charges] the day's charges (null or 0: none said), [estimate] when they are estimated from the trades
     * (Zerodha) rather than paid (paper). With charges the line says both: before and after them.
     */
    fun pnl(account: String, day: Double?, realized: Double?, unrealized: Double?, charges: Double? = null, estimate: Boolean = false): String {
        if (day == null) return "No P&L on $account today."
        val parts = if (realized != null && unrealized != null) "${rs(realized)} booked, ${rs(unrealized)} open" else null
        if (!PnlCharges.shown(charges)) return "$account P&L today ${rs(day)}" + (parts?.let { ": $it." } ?: ".")
        val c = charges!!
        val about = if (estimate) "about " else ""
        return "$account P&L today ${rs(day)} before charges" + (parts?.let { ": $it" } ?: "") +
            "; charges $about${amt(c)}${if (estimate) " (an estimate from today's trades)" else ""}, so $about${rs(PnlCharges.net(day, c))} after charges."
    }

    fun arms(a: List<ArmLine>, rank: Boolean): List<String> {
        if (a.isEmpty()) return listOf("No strategies or arms are set up.")
        val on = a.filter { it.on }
        val out = ArrayList<String>()
        out += "${on.size} of ${a.size} strategies and arms are switched on."
        // Numbered in the app's order, so "stop strategy 2" means the second one listed here.
        a.forEachIndexed { i, x -> out += "${i + 1}. ${x.name} (${x.kind}, ${x.detail}): ${if (x.on) "on" else "off"}" +
            (x.todayPnl?.let { " today ${rs(it)}" } ?: "") + (if (x.tradesToday > 0) ", ${x.tradesToday} trade${if (x.tradesToday > 1) "s" else ""} today" else "") +
            (x.holding?.let { h -> ", holding $h" } ?: "") + "." }
        val ranked = a.filter { it.todayPnl != null }.sortedByDescending { it.todayPnl }
        if (rank && ranked.size >= 2) out += "Best today: ${ranked.first().name} (${rs(ranked.first().todayPnl!!)}); weakest: ${ranked.last().name} (${rs(ranked.last().todayPnl!!)})."
        return out
    }

    /** P&L by day ([days]: day -> rupees, trades): yesterday, this week, this month, best and worst day, winning days. */
    fun history(account: String, days: Map<LocalDate, Pair<Double, Int>>, today: LocalDate): List<String> {
        val past = days.filterKeys { !it.isAfter(today) }
        if (past.isEmpty()) return listOf("No P&L days recorded on $account yet.")
        val out = ArrayList<String>()
        past.keys.filter { it.isBefore(today) }.maxOrNull()?.let { d -> out += "$account last session before today ($d): ${rs(past.getValue(d).first)}." }
        val week = past.filterKeys { !it.isBefore(today.minusDays((today.dayOfWeek.value - 1).toLong())) }
        if (week.isNotEmpty()) out += "$account this week: ${rs(week.values.sumOf { it.first })} over ${week.size} day${if (week.size > 1) "s" else ""}."
        val month = past.filterKeys { it.year == today.year && it.month == today.month }
        if (month.isNotEmpty()) out += "$account this month: ${rs(month.values.sumOf { it.first })} over ${month.size} day${if (month.size > 1) "s" else ""}, " +
            "${month.values.count { it.first > 0 }} of them up."
        val best = past.maxByOrNull { it.value.first }!!; val worst = past.minByOrNull { it.value.first }!!
        out += "$account best day ${best.key} ${rs(best.value.first)}; worst day ${worst.key} ${rs(worst.value.first)}; ${past.size} days recorded in all, total ${rs(past.values.sumOf { it.first })}."
        return out
    }
}

/** Answers about the app, the owner's trading, and Ira itself. Pure. */
object AppAnswers {
    private val WORDS: List<Pair<Section, Regex>> = listOf(
        Section.RISK to Regex(" (kill switch|daily loss|loss limit|drawdown|guard|limits?|risk|breaker|max lots|max trades|cut ?off|can i (still )?lose|left to lose) "),
        Section.PROTECTIONS to Regex(" (stop|stops|stop loss|stoploss|sl|target|targets|trail|trailing|protection|protections|bracket|gtt|gtts) "),
        Section.ALARMS to Regex(" (alarm|alarms|alert|alerts) "),
        Section.FUNDS to Regex(" (funds|fund|margin|margins|balance|cash|capital|money|buying power|trade with) "),
        Section.HISTORY to Regex(" (yesterday|week|weekly|month|monthly|history|calendar|journal|last \\d+ days|best day|worst day|so far|this year|all time) "),
        Section.PNL to Regex(" (p l|pnl|profit|profits|made|lost|earned|returns?|loss|losses|mtm|m2m|(did|have) i (make|earn|lose)) "),
        Section.ORDERS to Regex(" (order|orders|trades|fills|filled|rejected|rejection|rejections) "),
        // ("Is Nifty holding up", "how's the market holding above 25000": the index holding a level, never Boss's holdings - round 21.)
        Section.POSITIONS to Regex(" (position|positions|holding(?! (up|on|above|below|its|at|steady|firm|strong) )|holdings|open trades|exposure) "),
        // His leg's own price (routing round 13: "what is the average price of my put" got the market): the position's line
        // holds its quantity and average price - "my average on the 24500 put", "meri call ka avg price".
        Section.POSITIONS to Regex(" (average|avg|entry|buy|buying|purchase|cost) (price|cost|rate|level) (of |on |for )?(my|mine|meri|mera|mere) |" +
            " (my|meri|mera|mere) ((\\d+|nifty|banknifty|bank nifty|finnifty|sensex) ){0,2}(put|call|ce|pe|puts|calls|straddle|strangle)( ka| ki| ke| s)? (average|avg|entry) (price|cost|rate)? ?|" +
            " (my|meri|mera|mere) (average|avg|entry|buy) (price|cost) (of|on|for) (the |my )?((\\d+|nifty|banknifty|bank nifty|finnifty|sensex) ){0,2}(put|call|ce|pe|straddle|strangle) "),
        Section.STRATEGIES to Regex(" (strategy|strategies|arm|arms|bot|bots|algo|algos|pine|orb|script|scripts|studies|study|running) "),
        Section.SETTINGS to Regex(" (settings|setting|mode|paper mode|live mode|paper or live|live or paper|product|nrml|mis|preferences|one tap|biometric|pin) "),
        Section.STATUS to Regex(" (status|market open|is the market|open today|holiday|holidays|expiry|expiries|harvest|data|zerodha|kite|login|logged|connected|static ip|relay|app) "),
        Section.HOWTO to Regex(" (where|how do i|how can i|how to|find|which tab|which page|switch to) "),
        Section.REVIEW to Regex(" (review|weekly review|insight|insights|mistake|mistakes|habits|patterns in my|what am i doing wrong|how did my week|my week|my hafta|how (was|did|s|is) my weak|my weak (go|went|been)|news trades?|(my|our) (win rate|winning rate|hit rate|success rate|strike rate|accuracy)) "),
        Section.FLOWS to Regex(" (fii|fiis|dii|diis|fpi|fpis|institutional|institutions|flows|foreign funds|mutual funds) "),
        Section.CHAIN to Regex(" (oi|open interest|pcr|put call|put-call|max pain|option chain|chain|iv|implied volatility|skew|call writing|put writing|writers) "),
        Section.STUDY to Regex(" (what did you study|your study|you studied|you learn|you learned|history say|history says|history shows|what usually happens|usually happens|overnight|last night|night news|how will the market|how the market will|will the market|market will|how markets? works?|edge|edges) "),
        Section.MISTAKES to Regex(" (mistakes? (list|noted)|what did you get wrong|your mistakes|wrong answers) "),
        Section.TIMEOFDAY to Regex(" (best time|worst time|time of day|which hour|what time do i (lose|make|win)|when do i (lose|make|win)) "),
        Section.REASONS to Regex(" (my (trade )?notes|which (of my )?reasons|reasons (work|worked)|why i (took|take) (my )?trades) "),
        Section.EXPLAIN_POS to Regex(" (explain|walk me through|break down|tell me about|how is) (my |the )?([a-z]+ )?(position|positions|trade|trades)(?! check)( now)? "),
        // One leg of his named by its kind (routing round 11: "talk me through my put", "explain my put", "how did my put do",
        // a bare "my put" were market answers): that position explained. Only whole, so "how did my calls do this week" stays his history.
        Section.EXPLAIN_POS to Regex("^ (jarvis |boss |please )?((explain|walk me through|talk me through|break down|tell me about) (my|mine) |how (did|has) my |(my|mera|meri) )" +
            "(\\d+ )?(nifty |banknifty |bank nifty |finnifty |sensex )?(\\d+ )?(put|call|ce|pe|straddle|strangle|iron condor|spread)( position| trade| leg)?( (do|done|go|gone|doing))?( please| boss| jarvis)? $"),
        // ...and in Hinglish, the verb last (routing round 13: "meri put explain karo", the first step of "... phir isko band karo").
        Section.EXPLAIN_POS to Regex("^ (jarvis |boss )?(my|mera|meri|mere) (\\d+ )?(nifty |banknifty |bank nifty |finnifty |sensex )?(\\d+ )?" +
            "(put|call|ce|pe|straddle|strangle|iron condor|spread)( position| trade)?( ko| ke baare mein)? (explain|samjhao|samjha do|explain karo|explain kar do|explain kardo|batao)( please| boss| jarvis)? $"),
        Section.WHATIF to Regex(" (what if (i|we) (had )?(taken|took|take|bought|approved)|would (i|it) have (made|lost)|if i had (taken|approved|bought)) "),
        Section.CHANGES to Regex(" (what did i change|what have i changed|settings? (history|changes)|changes? to (my )?(settings|limits)|limit changes|who changed|changed my (limits|settings)) "),
        Section.ACTIVITY to Regex(" (what did you do|what have you done|what you did|your activity|activity log|what did jarvis do|did you do anything) "),
        Section.READY to Regex(" (ready (to|for) (go )?live|ready for live trading|can i go live|should i go live|go live checklist|live checklist|am i ready) "),
        Section.REGIME to Regex(" (regime|market mood|market phase|trending or sideways|is the market trending|sideways or trending|which arms suit|arms suit|which strateg(y|ies) suits?|suits? (this|the) market) "),
        Section.LOSSES to Regex(" (why did (that|the|my|it|this)( last)? trade lose|why did (i|we|it|you) lose|why (it|that|the trade) lost|why trades? lost|loss reasons?|reason (for|of) (the )?loss|why the loss) "),
        Section.EVENTS to Regex(" (event|events|calendar|fed|fomc|rbi|budget|policy|cpi|news events|this week|expiry day|expiries) "),
    )

    /** The daily loss limit in other words: "daily stop loss", "day's SL", "stop loss for the day", "max loss per day". */
    val DAY_STOP = Regex(" ((daily|day s|days|today s|per day|intraday) (stop ?loss|sl|max loss|loss cap)|(stop ?loss|sl|max loss|maximum loss) (for|of|per|in) (the |a )?day|daily max(imum)? loss) ")

    /** Does [t] (lower-case, spaced) ask about the app or the owner's trading? */
    fun about(t: String): Boolean = WORDS.any { (s, r) -> s != Section.HOWTO && s != Section.STATUS && r.containsMatchIn(t) } ||
        rx(" (my|mine|our|i|me) ").containsMatchIn(t) && WORDS.any { it.second.containsMatchIn(t) } ||
        rx(" (how am i doing|how did i do|app status|this app|kill switch|zerodha|login|logged in|holiday|holidays|harvest|static ip|where is|where do i|how do i|how can i|when is (the )?(next )?expiry|next expiry|expiry (day|date|today)|is (the )?market open|market open today) ").containsMatchIn(t) ||
        // "Is Kite connected", "is the app working" (audit, 5 Oct): the app's own status.
        rx(" is (kite|zerodha|the data|data|the app|app|the relay|relay|the feed|my broker) (connected|working|running|ok|okay|down|up) ").containsMatchIn(t) ||
        // The home-screen widgets (round 23): "what does the Open widget show", "how to add widget", "widget kaise lagaye" - never
        // with a trading word, so an order or a command reads exactly as before. (Review: "purchase", "khareedo", "lelo" and
        // "le lo" too. Ask's own parse is not used here: it reads this clause, so it would read itself.)
        WIDGET.containsMatchIn(t) && !rx(" (buy|sell|bought|sold|purchase|purchased|kharido|khareedo|kharid|khareed|lelo|le lo|becho|bech|bechna|exit|square|squareoff|cancel|close|place|short|long|lot|lots|stop|kill|approve|reject) ").containsMatchIn(t)

    /** A home-screen widget named, with the recognizer's slips ("vidget", "widjet"). */
    internal val WIDGET = Regex(" (widget|widgets|vidget|vidgets|widjet|widjets|wiget|wigets) ")

    fun sections(text: String): Set<Section> = sectioned.of(text) { sectionsFresh(text) }

    /** The last questions' sections (speed round 10: read ahead and again when answered; [Kept], every reader in it pure). */
    private val sectioned = Kept<Set<Section>>(64)

    private fun sectionsFresh(text: String): Set<Section> {
        // (Read as a question: "aaj kitna kamaya" and a misheard "p and l" ask the P&L too - only what to read, never an action.)
        val t = " " + spacedWords(Ask.reading(text).lowercase().replace("p&l", "p l")) + " "
        val out = LinkedHashSet<Section>()
        for ((s, r) in WORDS) if (r.containsMatchIn(t)) out += s
        // "My daily stop loss", "stop loss for the day", "max loss per day": the daily loss limit (Boss's words, 3 Oct),
        // not the stops on positions nor the P&L.
        if (DAY_STOP.containsMatchIn(t)) { out += Section.RISK; out.remove(Section.PROTECTIONS); out.remove(Section.PNL) }   // (paper's too: RISK lists both)
        if (Section.RISK in out && rx(" (daily loss|loss limit) ").containsMatchIn(t)) out.remove(Section.PNL)
        // Boss's book said as "we" or in traders' words (round 21): "did we make money today" is his P&L, not his funds; "where do I
        // stand today", "how much are we down", "am I bleeding" too - not where things are in the app.
        if (rx(" (did|have|are|am|were) (i|we) (make|made|making|earn|earned|earning|lose|lost|losing) (any )?money ").containsMatchIn(t) &&
            !rx(" (funds|fund|margin|margins|balance|cash|capital|buying power) ").containsMatchIn(t)) { out.remove(Section.FUNDS); out += Section.PNL }
        if (rx(" where do (i|we) stand | how much (are|were) we (up|down) | (did|have) we (make|made|earn|earned|lose|lost) | how did we do | (am i|are we) bleeding | are we in the (green|red) ").containsMatchIn(t) &&
            out.none { it != Section.HOWTO && it != Section.PNL }) { out.remove(Section.HOWTO); out += Section.PNL }
        if (Section.HISTORY in out && rx(" (made|lost|earned|p l|pnl|profit|loss|make|earn|lose) ").containsMatchIn(t)) out.remove(Section.PNL)
        if (Section.EVENTS in out && rx(" (event|events|fed|fomc|rbi|budget|cpi) ").containsMatchIn(t)) out.remove(Section.HISTORY)
        // "How did I do this week": his own week, not the calendar's events (routing audit, 5 Oct).
        else if (Section.EVENTS in out && Section.HISTORY in out && rx(" (i|my|me|mine) ").containsMatchIn(t) &&
            !rx(" (calendar|policy|expiry day|expiries|news events) ").containsMatchIn(t)) out.remove(Section.EVENTS)
        if (Section.FLOWS in out || Section.CHAIN in out) { out.remove(Section.HISTORY); out.remove(Section.STATUS) }
        // "Where is max pain", "where is resistance from the option chain": the chain's own answer (round 10), not where to find
        // it in the app - unless the app is named ("where can I see max pain", "which tab shows the chain").
        if (Section.CHAIN in out && Section.HOWTO in out && !rx(" (how do i|how can i|how to|find|which tab|which page|switch to|see|screen|tab|page|app|show|shown) ").containsMatchIn(t))
            out.remove(Section.HOWTO)
        if (Section.STUDY in out) { out.remove(Section.STRATEGIES); out.remove(Section.HISTORY); out.remove(Section.STATUS) }
        // "My last P&L", "previous day's profit": the last session, from the record.
        if (rx(" (last|previous|yesterday|yesterday s|last session s|last day s|last trading day s) (p l|pnl|profit|loss|day|session|result)").containsMatchIn(t) &&
            rx(" (p l|pnl|profit|loss|made|lost|result) ").containsMatchIn(t)) { out.remove(Section.PNL); out += Section.HISTORY }
        // "How did my Thursday expiry trades do this month?": a search of the owner's own trades.
        if (TradeSearch.asked(text) && out.none { it == Section.TIMEOFDAY || it == Section.REASONS }) { out.clear(); out += Section.SEARCH }
        // "What happens to my P&L if Nifty moves 100 points", "which of my positions is losing most": asked on their own.
        if (Exposure.moveAsked(text) != null) { out.clear(); out += Section.MOVE }
        // "How was my last trade?", "go over my trades today": the trades laid against their own candles.
        else if (TradeReplay.asked(text) != null) { out.clear(); out += Section.REPLAY }
        else if (Exposure.rankAsked(text)) { out.clear(); out += Section.RANK }
        // "How was my month?", "review last month": the month's review of Boss's own trades.
        if (MonthReview.asked(text)) { out.clear(); out += Section.MONTH }
        // "How much did I pay in charges this week?": what the trading paid in charges, and which kind paid most.
        if (Charges.asked(text)) { out.clear(); out += Section.CHARGES }
        // "Check my positions", "position health", "kya meri positions theek hain": each open position's health, on its own.
        if (PositionHealth.asked(text)) { out.clear(); out += Section.HEALTH }
        // "How are my bots doing?", "is the ORB arm behaving?", "which strategy is losing?": the strategies' health, on its own.
        if (BotHealth.asked(text)) { out.clear(); out += Section.BOTS }
        // "What's my F&O turnover this year?", "my tax summary": the financial year's facts, on their own (not tax advice).
        if (TaxRecords.asked(text)) { out.clear(); out += Section.TAX }
        // "For my 24500 put to work, what needs to happen?", "where is my breakeven?": worked from the facts, on its own.
        if (NeedsTrue.asked(text)) { out.clear(); out += Section.NEED }
        // "Am I on a winning streak?", "what's my best weekday?": Boss's own runs of days and trades, on their own.
        if (MyStreaks.asked(text)) { out.clear(); out += Section.STREAKS }
        // "What's my average win and loss?", "my profit factor", "do I hold my losers longer?": Boss's own numbers, on their own.
        if (MyNumbers.asked(text)) { out.clear(); out += Section.NUMBERS }
        // "What does the Open widget show", "how do I show my P&L on the widget", "widget pe P&L kaise dikhaye": the widgets'
        // how-to (round 23), never the P&L, the orders or the positions themselves - unless something else is asked with it.
        if (WIDGET.containsMatchIn(t) && !rx(" and | also | plus ").containsMatchIn(t)) { out.clear(); out += Section.HOWTO }
        // The new sections are asked on their own: drop the broad matches their words also hit.
        if (!rx(" and | also | plus ").containsMatchIn(t) && out.any { it == Section.ACTIVITY || it == Section.READY || it == Section.REGIME || it == Section.LOSSES || it == Section.WHATIF || it == Section.CHANGES || it == Section.EXPLAIN_POS ||
                it == Section.SEARCH || it == Section.TIMEOFDAY || it == Section.REASONS || it == Section.MISTAKES || it == Section.MOVE || it == Section.RANK || it == Section.REPLAY || it == Section.MONTH || it == Section.CHARGES || it == Section.HEALTH || it == Section.BOTS || it == Section.TAX || it == Section.NEED || it == Section.STREAKS || it == Section.NUMBERS })
            out.removeAll(setOf(Section.EVENTS, Section.POSITIONS, Section.STATUS, Section.STRATEGIES, Section.ORDERS, Section.PNL, Section.SETTINGS, Section.HISTORY, Section.HOWTO, Section.STUDY, Section.REVIEW))
        if (Section.REVIEW in out) { out.remove(Section.HISTORY); out.remove(Section.ORDERS); out.remove(Section.PNL) }
        if (out.isEmpty() || out == setOf(Section.STATUS) && rx(" (how am i doing|how did i do|my account|account) ").containsMatchIn(t))
            out += listOf(Section.PNL, Section.POSITIONS, Section.ORDERS, Section.STRATEGIES)
        return out
    }

    /** At most this many lines of a section are read out (all are kept as facts). */
    const val SHOWN = 7

    fun answer(q: Question, v: AppView?): Answer {
        if (v == null) return Answer("I could not read the app just now. Try again in a moment.", emptyList())
        val want = sections(q.text)
        val facts = ArrayList<String>()
        val parts = ArrayList<String>()
        for (s in Section.entries) {
            if (s !in want) continue
            val lines = if (s == Section.HOWTO) howto(q.text) else v.lines[s].orEmpty()
            if (lines.isEmpty()) { parts += "Nothing to show for ${s.title.lowercase()}."; continue }
            facts += lines
            parts += lines.take(SHOWN).joinToString(" ") + if (lines.size > SHOWN) " (and ${lines.size - SHOWN} more on screen)" else ""
        }
        if (rx("\\b(analy[sz]e|analysis|review|should)", RegexOption.IGNORE_CASE).containsMatchIn(q.text)) parts += "These are facts from the app, not advice."
        return Answer(parts.joinToString(" "), facts)
    }

    /** Where things are in the app. */
    fun howto(text: String): List<String> {
        val t = " " + text.lowercase().replace("p&l", "p l").replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ") + " "
        // The home-screen widgets on their own (round 23): adding them, what each shows, and the switch the P&L waits for.
        if (WIDGET.containsMatchIn(t)) return listOf(WIDGETS)
        val map = listOf(
            rx(" (kill switch|daily loss|drawdown|limit|limits|bot settings|risk) ") to "Kill switch, daily loss, drawdown and order limits: More, then Bot settings.",
            rx(" (zerodha|kite|login|log in|live|real orders|api|mode|paper) ") to "Zerodha login, Paper or Live mode and order limits: More, then Zerodha. The PAPER TRADING badge at the top switches the mode.",
            rx(" (alarm|alarms|alert|alerts) ") to "Price alarms and P&L alerts: More, then Alerts.",
            rx(" (pine|script|scripts|indicator) ") to "Pine scripts (write, backtest, put on the chart, auto-trade): Research, then Pine.",
            rx(" (strategy|strategies|arm|arms|orb) ") to "Strategies and the ORB arms: Trade, then Strategies. Pine arms (and the strategies Jarvis made): Research, then Pine.",
            rx(" (order|orders|position|positions|funds|account) ") to "Orders, positions and funds: Trade, then Account.",
            rx(" (p l|pnl|calendar|journal|history) ") to "P&L by day, the calendar and the journal: the P&L tab.",
            rx(" (option chain|chain|options|straddle|oi|iv|greeks|builder) ") to "Option chain, OI, IV, straddle and the strategy builder: the Options tab.",
            rx(" (chart|charts) ") to "Charts: the Chart tab; tap Options on a chart for option prices and buy or sell.",
            rx(" (pin|fingerprint|biometric|security|lock) ") to "PIN, fingerprint and device checks: More, then Security.",
            rx(" (schedule|schedules|holiday|holidays|notification|notifications) ") to "Daily jobs, notifications and market holidays: More, then Schedules.",
            rx(" (data|harvest|backup) ") to "The data record and the nightly harvest: More, then Data and harvest.",
            rx(" (backtest|portfolio|sip|replay|health) ") to "Backtests, portfolio, SIP, replay and strategy health: the Research tab.",
            rx(" (voice|jarvis|listen|model|ai) ") to "Jarvis's voice and AI model: Home, Ira, the Voice and AI model cards at the top.",
        ).filter { it.first.containsMatchIn(t) }.map { it.second }
        return map.ifEmpty { listOf("The tabs: Home (Ira and the dashboard), Chart, Trade (account and strategies), P&L, Options, Research, More (Zerodha, Alerts, Security, Schedules, Bot settings, Data).") }
    }

    /** The home-screen widgets: how to add one, what each shows, and the switch their P&L waits for. */
    const val WIDGETS = "Home-screen widgets: long-press an empty spot on the home screen, tap Widgets and pick IraAlgo. There are three: " +
        "the index widget (Nifty and BankNifty, your P&L only if you allow it), Open (today's P&L on top, then only what is open - " +
        "your open positions and pending orders) and Jarvis (his last message, with Approve or Reject for a trade waiting for you). " +
        "The Open widget and the P&L stay blank until you turn on \"Show my P&L on the widget\" in More, then Security - off by default, " +
        "as anyone holding the unlocked phone sees the home screen."

    /** "Can you listen to me?", "what can you do?" - about Ira (Jarvis) itself. */
    fun help(q: Question, voice: Boolean): Answer {
        val t = q.text.lowercase()
        val aboutVoice = rx("\\b(listen|hear|voice|speak|talk|mic|microphone)").containsMatchIn(t)
        val voiceLine = if (voice) "Voice: switch on \"Listen for Jarvis\" in the Voice card at the top of this page and allow the microphone. " +
            "Then say \"Jarvis, how is Nifty?\" - or \"Jarvis\", wait for \"Yes?\", and ask. Say \"Jarvis, stop listening\" to switch it off. " +
            "It hears you on this phone only."
        else "Voice is in IraAlgo only; here you type."
        if (aboutVoice) return Answer((if (voice) "Yes. " else "") + voiceLine, emptyList())
        return Answer("I can tell you about Nifty, BankNifty, FinNifty, Sensex, India VIX and gold: prices, trend, levels, " +
            "patterns, volatility and the news. I know the app: your orders (paper and Zerodha), positions, P&L today and by day, " +
            "every strategy and arm, risk limits, stops and targets, alarms, settings, and where things are (try \"analyze my orders\" " +
            "or \"where is the kill switch\"). I can backtest a pattern as a strategy (\"backtest the breakout on BankNifty 15m\") and " +
            "prepare an order for you to confirm (\"buy 1 lot Nifty ATM CE\"). I can also reason over the data: \"why is Nifty down\", " +
            "\"how much did BankNifty move in the last hour\", \"which index is strongest\", \"expected range today\", \"chances Nifty closes " +
            "above 25000\", \"pivots\", \"is Nifty overbought\", \"recap the day\", \"how did Nifty do this week\", \"brief me\", " +
            "\"what is theta\", and \"the usual\". Quick ones: \"price of BankNifty 52000 PE\", \"which strike is ATM\", \"how many lots " +
            "can I buy with 20000\", \"how far is Nifty from 25000\", \"when is the next expiry\", \"is tomorrow a holiday\", \"remind me at 3 pm " +
            "to check Nifty\", \"wrap up my day\", \"how was my last trade\", \"how was my month\", \"how much did I pay in charges this week\", \"what did I miss\", \"run a self check\", \"speak slower\" - Hinglish too. " +
            "I don't give buy or sell advice. " + Toolbox.say() + " " + voiceLine, emptyList())
    }
}
