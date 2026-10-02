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
                         val avgPrice: Double, val by: String?, val reason: String? = null)
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
            (if (it.avgPrice > 0) " at ${px(it.avgPrice)}" else "") + (it.by?.let { b -> " ($b)" } ?: "") + "." }
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

    fun pnl(account: String, day: Double?, realized: Double?, unrealized: Double?): String =
        if (day == null) "No P&L on $account today."
        else "$account P&L today ${rs(day)} after charges" + (if (realized != null && unrealized != null) ": ${rs(realized)} booked, ${rs(unrealized)} open." else ".")

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
        Section.RISK to Regex(" (kill switch|daily loss|loss limit|drawdown|guard|limits?|risk|breaker|max lots|max trades|cut ?off) "),
        Section.PROTECTIONS to Regex(" (stop|stops|stop loss|stoploss|sl|target|targets|trail|trailing|protection|protections|bracket|gtt|gtts) "),
        Section.ALARMS to Regex(" (alarm|alarms|alert|alerts) "),
        Section.FUNDS to Regex(" (funds|fund|margin|margins|balance|cash|capital|money) "),
        Section.HISTORY to Regex(" (yesterday|week|weekly|month|monthly|history|calendar|journal|last \\d+ days|best day|worst day|so far|this year|all time) "),
        Section.PNL to Regex(" (p l|pnl|profit|profits|made|lost|earned|returns?|loss|losses) "),
        Section.ORDERS to Regex(" (order|orders|trades|fills|filled|rejected|rejection|rejections) "),
        Section.POSITIONS to Regex(" (position|positions|holding|holdings|open trades|exposure) "),
        Section.STRATEGIES to Regex(" (strategy|strategies|arm|arms|bot|bots|algo|algos|pine|orb|script|scripts|studies|study|running) "),
        Section.SETTINGS to Regex(" (settings|setting|mode|paper mode|live mode|product|nrml|mis|preferences|one tap|biometric|pin) "),
        Section.STATUS to Regex(" (status|market open|is the market|open today|holiday|holidays|expiry|expiries|harvest|data|zerodha|kite|login|logged|connected|static ip|relay|app) "),
        Section.HOWTO to Regex(" (where|how do i|how can i|how to|find|which tab|which page|switch to) "),
        Section.REVIEW to Regex(" (review|weekly review|insight|insights|mistake|mistakes|habits|patterns in my|what am i doing wrong|how did my week|my week|news trades?) "),
        Section.FLOWS to Regex(" (fii|fiis|dii|diis|fpi|fpis|institutional|institutions|flows|foreign funds|mutual funds) "),
        Section.CHAIN to Regex(" (oi|open interest|pcr|put call|put-call|max pain|option chain|chain|iv|implied volatility|skew|call writing|put writing|writers) "),
        Section.EVENTS to Regex(" (event|events|calendar|fed|fomc|rbi|budget|policy|cpi|news events|this week|expiry day|expiries) "),
    )

    /** Does [t] (lower-case, spaced) ask about the app or the owner's trading? */
    fun about(t: String): Boolean = WORDS.any { (s, r) -> s != Section.HOWTO && s != Section.STATUS && r.containsMatchIn(t) } ||
        Regex(" (my|mine|our|i|me) ").containsMatchIn(t) && WORDS.any { it.second.containsMatchIn(t) } ||
        Regex(" (how am i doing|how did i do|app status|this app|kill switch|zerodha|login|logged in|holiday|holidays|harvest|static ip|where is|where do i|how do i|how can i) ").containsMatchIn(t)

    fun sections(text: String): Set<Section> {
        val t = " " + text.lowercase().replace("p&l", "p l").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        val out = LinkedHashSet<Section>()
        for ((s, r) in WORDS) if (r.containsMatchIn(t)) out += s
        if (Section.RISK in out && Regex(" (daily loss|loss limit) ").containsMatchIn(t)) out.remove(Section.PNL)
        if (Section.HISTORY in out && Regex(" (made|lost|earned|p l|pnl|profit|loss) ").containsMatchIn(t)) out.remove(Section.PNL)
        if (Section.EVENTS in out && Regex(" (event|events|fed|fomc|rbi|budget|cpi) ").containsMatchIn(t)) out.remove(Section.HISTORY)
        if (Section.FLOWS in out || Section.CHAIN in out) { out.remove(Section.HISTORY); out.remove(Section.STATUS) }
        if (Section.REVIEW in out) { out.remove(Section.HISTORY); out.remove(Section.ORDERS); out.remove(Section.PNL) }
        if (out.isEmpty() || out == setOf(Section.STATUS) && Regex(" (how am i doing|how did i do|my account|account) ").containsMatchIn(t))
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
        if (Regex("\\b(analy[sz]e|analysis|review|should)", RegexOption.IGNORE_CASE).containsMatchIn(q.text)) parts += "These are facts from the app, not advice."
        return Answer(parts.joinToString(" "), facts)
    }

    /** Where things are in the app. */
    fun howto(text: String): List<String> {
        val t = " " + text.lowercase().replace("p&l", "p l").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ") + " "
        val map = listOf(
            Regex(" (kill switch|daily loss|drawdown|limit|limits|bot settings|risk) ") to "Kill switch, daily loss, drawdown and order limits: More, then Bot settings.",
            Regex(" (zerodha|kite|login|log in|live|real orders|api|mode|paper) ") to "Zerodha login, Paper or Live mode and order limits: More, then Zerodha. The PAPER TRADING badge at the top switches the mode.",
            Regex(" (alarm|alarms|alert|alerts) ") to "Price alarms and P&L alerts: More, then Alerts.",
            Regex(" (pine|script|scripts|indicator) ") to "Pine scripts (write, backtest, put on the chart, auto-trade): Research, then Pine.",
            Regex(" (strategy|strategies|arm|arms|orb) ") to "Strategies and the ORB arms: Trade, then Strategies. Pine arms (and the strategies Jarvis made): Research, then Pine.",
            Regex(" (order|orders|position|positions|funds|account) ") to "Orders, positions and funds: Trade, then Account.",
            Regex(" (p l|pnl|calendar|journal|history) ") to "P&L by day, the calendar and the journal: the P&L tab.",
            Regex(" (option chain|chain|options|straddle|oi|iv|greeks|builder) ") to "Option chain, OI, IV, straddle and the strategy builder: the Options tab.",
            Regex(" (chart|charts) ") to "Charts: the Chart tab; tap Options on a chart for option prices and buy or sell.",
            Regex(" (pin|fingerprint|biometric|security|lock) ") to "PIN, fingerprint and device checks: More, then Security.",
            Regex(" (schedule|schedules|holiday|holidays|notification|notifications) ") to "Daily jobs, notifications and market holidays: More, then Schedules.",
            Regex(" (data|harvest|backup) ") to "The data record and the nightly harvest: More, then Data and harvest.",
            Regex(" (backtest|portfolio|sip|replay|health) ") to "Backtests, portfolio, SIP, replay and strategy health: the Research tab.",
            Regex(" (voice|jarvis|listen|model|ai) ") to "Jarvis's voice and AI model: Home, Ira, the Voice and AI model cards at the top.",
        ).filter { it.first.containsMatchIn(t) }.map { it.second }
        return map.ifEmpty { listOf("The tabs: Home (Ira and the dashboard), Chart, Trade (account and strategies), P&L, Options, Research, More (Zerodha, Alerts, Security, Schedules, Bot settings, Data).") }
    }

    /** "Can you listen to me?", "what can you do?" - about Ira (Jarvis) itself. */
    fun help(q: Question, voice: Boolean): Answer {
        val t = q.text.lowercase()
        val aboutVoice = Regex("\\b(listen|hear|voice|speak|talk|mic|microphone)").containsMatchIn(t)
        val voiceLine = if (voice) "Voice: switch on \"Listen for Jarvis\" in the Voice card at the top of this page and allow the microphone. " +
            "Then say \"Jarvis, how is Nifty?\" - or \"Jarvis\", wait for \"Yes?\", and ask. Say \"Jarvis, stop listening\" to switch it off. " +
            "It hears you on this phone only."
        else "Voice is in JarvisAlgo only; here you type."
        if (aboutVoice) return Answer((if (voice) "Yes. " else "") + voiceLine, emptyList())
        return Answer("I can tell you about Nifty, BankNifty, FinNifty, Sensex, India VIX and gold: prices, trend, levels, " +
            "patterns, volatility and the news. I know the app: your orders (paper and Zerodha), positions, P&L today and by day, " +
            "every strategy and arm, risk limits, stops and targets, alarms, settings, and where things are (try \"analyze my orders\" " +
            "or \"where is the kill switch\"). I can backtest a pattern as a strategy (\"backtest the breakout on BankNifty 15m\") and " +
            "prepare an order for you to confirm (\"buy 1 lot Nifty ATM CE\"). I don't give buy or sell advice. " + voiceLine, emptyList())
    }
}
