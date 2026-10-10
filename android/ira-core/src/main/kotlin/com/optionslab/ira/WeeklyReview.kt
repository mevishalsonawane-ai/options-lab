package com.optionslab.ira

import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.LiquidityShadow
import com.optionslab.engine.orb.ShadowRules
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Jarvis's weekly review (Boss, 06 Oct 2026): once every trading week, after its last session (15:45 on the week's last
 * trading day by the exchange calendar), one review of the whole week - kept for [KEEP_WEEKS] weeks to browse on the Ira
 * page, and said in three sentences when asked ("weekly review", "is hafte ka review", "how did this week go").
 *
 *  1. Money       the week's paper P&L by strategy - Liquidity 15+5 per book and in total, Solo (midday), Hero (when armed
 *                 or it traded), the Pine scripts, Jarvis's own trades, the manual ones: trades, net, win %, charges, and
 *                 last week's beside it ([money]).
 *  2. Live vs     each running strategy's [ForwardCheck] verdict, with its CUSUM warning when it alarmed ([forward]).
 *     backtest
 *  3. Shadows     the best and the worst shadow of the week and every shadow's standing toward the 60-trade bar
 *                 ([ShadowRules.PROMOTE_TRADES]) ([shadows]).
 *  4. Candidates  Liquidity 15+5's pre-registered candidates (a)-(f) ([LiquidityShadow]): which helped so far ([candidates]).
 *  5. Discipline  this week's overtrading warnings, cooling-offs, answers marked wrong and Boss's costly habits
 *                 ([WeekReview.findings]) - counts against last week ([discipline]).
 *  6. Market      NIFTY / BANKNIFTY / SENSEX over the week, its biggest day, India VIX's change, the FIIs' index-futures
 *                 positioning over the week (the recorder's NSE participant OI) and the sessions recorded ([market]).
 *  7. Next week   its sessions, expiries, holidays and events from the calendar, and Hero's expiry days ([nextWeek]).
 *  8. Watch       one honest line from the numbers ([watch]): what to keep an eye on - never to trade more or bigger, never
 *                 a switch to flip.
 *
 * Facts only, assembled from plain inputs the app gathers: nothing here reads a clock, a file or the network, and nothing
 * places, changes, arms or stops anything. Pure.
 */
object WeeklyReview {
    /** The reviews kept to browse. */
    const val KEEP_WEEKS = 12
    /** When the week's review is made: after the last session's close, with the day's own report (15:45). */
    val READY_AT: LocalTime = LocalTime.of(15, 45)
    /** Indices whose week is recapped, in this order. */
    val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.SENSEX)

    // ---- formatting --------------------------------------------------------------------------------------------------

    internal fun rs(x: Double): String = (if (x < -0.5) "−₹" else if (x > 0.5) "+₹" else "₹") +
        String.format(Locale.ENGLISH, "%,.0f", abs(round(x)))
    private fun amt(x: Double): String = "₹" + String.format(Locale.ENGLISH, "%,.0f", abs(round(x)))
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    internal fun dd(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.dayOfMonth + " " +
        d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    private fun dm(d: LocalDate) = "${d.dayOfMonth} " + d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    private fun pct(x: Double) = String.format(Locale.ENGLISH, "%+.1f%%", x)
    private fun num(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x)

    /** "Week of 5 Oct". */
    fun title(monday: LocalDate): String = "Week of ${dm(monday)}"

    // ---- the week and when it is due -----------------------------------------------------------------------------------

    fun mondayOf(d: LocalDate): LocalDate = d.with(DayOfWeek.MONDAY)

    /** The week's last session (Monday [monday] to Sunday, by [tradingDay]), or null when it has none. */
    fun lastSession(monday: LocalDate, tradingDay: (LocalDate) -> Boolean): LocalDate? =
        (6L downTo 0L).map { monday.plusDays(it) }.firstOrNull(tradingDay)

    /** [day] is a trading day and the week's last one (the next session falls in a later week). */
    fun isLastSession(day: LocalDate, tradingDay: (LocalDate) -> Boolean): Boolean =
        tradingDay(day) && lastSession(mondayOf(day), tradingDay) == day

    /**
     * The week whose review is due at [now] and not yet [made] (their Mondays), or null: the newest week whose last
     * session ended by [READY_AT] - never an older one (a phone off for a fortnight does not make a pile of reviews).
     */
    fun due(now: LocalDateTime, made: Collection<LocalDate>, tradingDay: (LocalDate) -> Boolean): LocalDate? {
        var monday = mondayOf(now.toLocalDate())
        val last = lastSession(monday, tradingDay)
        if (last == null || now.isBefore(last.atTime(READY_AT))) {
            // This week is not over: the one before, when it had a session.
            monday = monday.minusWeeks(1)
            if (lastSession(monday, tradingDay) == null) return null
        }
        return monday.takeIf { it !in made }
    }

    // ---- 1. money ------------------------------------------------------------------------------------------------------

    enum class Group(val label: String) {
        LIQUIDITY("Liquidity 15+5"), SOLO("Solo (midday)"), HERO("Hero (expiry)"), PINE("Pine scripts"),
        JARVIS("Jarvis's own trades"), MANUAL("Manual"), OTHER("Other arms"),
    }

    /**
     * One closed paper round trip: its [group], [name] (a Liquidity book's, a Pine script's or the owner as labelled), the
     * day it [closed], its [net] rupees after [charges].
     */
    data class Trade(val group: Group, val name: String, val closed: LocalDate, val net: Double, val charges: Double,
                     /** A Liquidity 15+5 trade's index ("BANKNIFTY"), judged on its own ([LiquiditySplit]); null otherwise or unknown. */
                     val index: String? = null)

    /**
     * The group of a paper trade whose orders the app labelled [owner] (as [Charges.owner] cuts it: "Liquidity 5m",
     * "Hero", "Jarvis solo", "Jarvis news", "Manual", a Pine script's name...); [pine] the Pine scripts' names.
     */
    fun groupOf(owner: String, pine: Set<String>): Group {
        val o = owner.trim()
        return when {
            ArmOwners.arm(o) == ArmOwners.LIQUIDITY -> Group.LIQUIDITY
            o == HeroRules.OWNER || o == HeroRules.ARM.label -> Group.HERO
            o.startsWith("Jarvis solo") -> Group.SOLO
            o.startsWith("Jarvis") -> Group.JARVIS
            o.startsWith("Manual") || o.startsWith("Outside") -> Group.MANUAL
            o in pine -> Group.PINE
            else -> Group.OTHER
        }
    }

    /** One row: this week's [trades], [won], [net] and [charges], and last week's [prevTrades] / [prevNet]. */
    data class Row(val name: String, val trades: Int, val won: Int, val net: Double, val charges: Double, val prevTrades: Int, val prevNet: Double) {
        val winPct: Int? get() = if (trades == 0) null else Math.round(100.0 * won / trades).toInt()
    }

    /** The money: each group's row in [Group] order, the Liquidity books and Pine scripts one by one ([detail]), the [total]. */
    data class Money(val rows: List<Row>, val detail: Map<Group, List<Row>>, val total: Row,
                     /** Liquidity 15+5 per index, BANKNIFTY first ([LiquiditySplit]): each judged on its own record. */
                     val liquidityByIndex: List<Row> = emptyList())

    private fun row(name: String, now: List<Trade>, prev: List<Trade>) =
        Row(name, now.size, now.count { it.net > 0 }, now.sumOf { it.net }, now.sumOf { it.charges }, prev.size, prev.sumOf { it.net })

    /**
     * The week from [monday] (to Sunday) against the week before. Liquidity 15+5 always has its row (it is the running
     * arm); Hero when [heroArmed] or it traded either week; the others when they traded either week.
     */
    fun money(trades: List<Trade>, monday: LocalDate, heroArmed: Boolean): Money {
        val now = trades.filter { mondayOf(it.closed) == monday }
        val prev = trades.filter { mondayOf(it.closed) == monday.minusWeeks(1) }
        val rows = Group.entries.mapNotNull { g ->
            val n = now.filter { it.group == g }; val p = prev.filter { it.group == g }
            val show = g == Group.LIQUIDITY || n.isNotEmpty() || p.isNotEmpty() || (g == Group.HERO && heroArmed)
            if (show) row(g.label, n, p) else null
        }
        val detail = listOf(Group.LIQUIDITY, Group.PINE).associateWith { g ->
            (now + prev).filter { it.group == g }.map { it.name }.distinct().sorted()
                .map { name -> row(name, now.filter { it.group == g && it.name == name }, prev.filter { it.group == g && it.name == name }) }
        }.filterValues { it.isNotEmpty() }
        // 08 Oct (research X1): BANKNIFTY's Liquidity on its own, apart from FINNIFTY and MIDCPNIFTY.
        val liq = (now + prev).filter { it.group == Group.LIQUIDITY && it.index != null }
        val byIndex = LiquiditySplit.INDICES.filter { i -> liq.any { it.index == i } }.map { i ->
            row(LiquiditySplit.name(i), now.filter { it.group == Group.LIQUIDITY && it.index == i }, prev.filter { it.group == Group.LIQUIDITY && it.index == i })
        }
        return Money(rows, detail, row("All paper trading", now, prev), byIndex)
    }

    private fun rowLine(r: Row): String {
        val last = if (r.prevTrades == 0) "last week: none" else "last week: ${plural(r.prevTrades, "trade")}, ${rs(r.prevNet)}"
        if (r.trades == 0) return "${r.name}: no trades ($last)."
        return "${r.name}: ${plural(r.trades, "trade")}, net ${rs(r.net)}, ${r.winPct}% won, charges ${amt(r.charges)} ($last)."
    }

    fun moneyLines(m: Money): List<String> {
        val out = ArrayList<String>()
        for (r in m.rows) {
            out += rowLine(r)
            val g = Group.entries.firstOrNull { it.label == r.name }
            m.detail[g]?.takeIf { it.size > 1 || (it.size == 1 && it[0].name != r.name) }?.forEach { out += "  · " + rowLine(it) }
            // Each index judged on its own record (research X1): BANKNIFTY apart from FINNIFTY and MIDCPNIFTY.
            if (g == Group.LIQUIDITY) m.liquidityByIndex.forEach { out += "  = " + rowLine(it.copy(name = it.name + " on its own")) }
        }
        val t = m.total
        out += "Total: " + (if (t.trades == 0) "no paper trades closed this week" else
            "${plural(t.trades, "trade")}, net ${rs(t.net)}, ${t.winPct}% won, charges ${amt(t.charges)}") +
            (if (t.prevTrades == 0) "; none last week." else "; last week ${rs(t.prevNet)} on ${plural(t.prevTrades, "trade")}.")
        return out
    }

    // ---- 2. live vs backtest -------------------------------------------------------------------------------------------

    /** A running strategy's forward check (shadows are section 3's). */
    data class Forward(val title: String, val result: ForwardCheck.Result)

    fun forwardLines(fs: List<Forward>): List<String> {
        if (fs.isEmpty()) return listOf("No running strategy to compare with its backtest this week.")
        val out = fs.map { f -> "${f.title}: " + ForwardCheck.line(f.result).removePrefix("Live vs backtest: ") }.toMutableList()
        fs.filter { it.result.alarm && !it.result.expectation.lottery }.forEach { f ->
            out += "CUSUM warning - ${f.title}: a sustained run below its backtest since trade ${f.result.alarmAt}, not just one bad trade."
        }
        return out
    }

    // ---- 3. shadows ----------------------------------------------------------------------------------------------------

    /**
     * A shadow ([name] its own, [arm] the arm it shadows): this week's closed [weekTrades] and [weekNet], and its whole
     * record [summary]; [promoted]: re-armed on paper on Boss's yes.
     */
    data class Shadow(val name: String, val arm: String, val weekTrades: Int, val weekNet: Double, val summary: ShadowRules.Summary,
                      val promoted: Boolean = false)

    fun shadowLines(ss: List<Shadow>): List<String> {
        if (ss.isEmpty()) return listOf("No shadow has a record yet.")
        val out = ArrayList<String>()
        val traded = ss.filter { it.weekTrades > 0 }
        if (traded.isEmpty()) out += "No shadow closed a trade this week."
        else {
            val best = traded.maxWith(compareBy<Shadow> { it.weekNet }.thenBy { it.name })
            out += "Best shadow this week: ${best.name} (${best.arm}), ${plural(best.weekTrades, "trade")}, ${rs(best.weekNet)}."
            val worst = traded.minWith(compareBy<Shadow> { it.weekNet }.thenBy { it.name })
            if (worst != best) out += "Worst: ${worst.name} (${worst.arm}), ${plural(worst.weekTrades, "trade")}, ${rs(worst.weekNet)}."
        }
        val bar = ShadowRules.PROMOTE_TRADES
        out += "Toward the $bar-trade bar: " + ss.sortedWith(compareByDescending<Shadow> { it.summary.trades }.thenBy { it.name }).joinToString("; ") { s ->
            "${s.name} ${minOf(s.summary.trades, bar)}/$bar, ${rs(s.summary.net)}" + when {
                s.promoted -> " (re-armed on paper on your yes)"
                ShadowRules.promotionDue(s.summary) -> " (met the bar)"
                s.summary.trades >= bar -> " (enough trades, not the net or profit factor)"
                else -> ""
            }
        } + "."
        return out
    }

    // ---- 4. Liquidity 15+5's candidates --------------------------------------------------------------------------------

    /** How one candidate stands so far: [helped] (null: no trades to compare), its trades and net a trade against the base. */
    data class Candidate(val key: String, val what: String, val helped: Boolean?, val trades: Int, val per: Double?, val basePer: Double?)

    fun candidates(s: LiquidityShadow.Summary): List<Candidate> {
        fun cut(key: String, what: String, base: LiquidityShadow.Cut, with: LiquidityShadow.Cut, same: Boolean) =
            Candidate(key, what, if (base.trades == 0 || with.trades == 0) null else if (same) LiquidityShadow.better(base, with) else LiquidityShadow.helped(base, with),
                with.trades, with.perTrade, base.perTrade)
        return listOf(
            cut("a", "skip a level within one index stop", s.all, s.withoutNear, false),
            cut("b", "no FINNIFTY 30-min", s.all, s.withoutFin30, false),
            cut("c", "skip in high volatility", s.volAll, s.withoutVol, false),
            cut("d", "all out by 14:30", s.exitAll, s.at1430, true),
            cut("e", "2 strikes in the money", s.itm1, s.itm2, true),
            cut("f", "only a strong close with momentum", s.strongAll, s.withStrong, false),
        )
    }

    fun candidateLines(s: LiquidityShadow.Summary?): List<String> {
        if (s == null) return listOf("Liquidity 15+5's record could not be read this time.")
        val cs = candidates(s)
        val out = ArrayList<String>()
        out += "Liquidity 15+5 has ${s.all.trades} of ${LiquidityShadow.MIN_TRADES} paper trades since 06 Oct; each candidate is judged at " +
            "${LiquidityShadow.MIN_TRADES} of its own - until then this is \"so far\", not a verdict."
        val helped = cs.filter { it.helped == true }.map { "(${it.key})" }
        out += if (helped.isEmpty()) "Helped so far: none." else "Helped so far: ${helped.joinToString(", ")}."
        cs.forEach { c ->
            out += "(${c.key}) ${c.what}: " + when (c.helped) {
                null -> "no trades to compare yet."
                else -> "${if (c.helped) "helped" else "did not help"} so far - ${c.per?.let { rs(it) } ?: "-"} a trade over ${plural(c.trades, "trade")} against " +
                    "${c.basePer?.let { rs(it) } ?: "-"}."
            }
        }
        out += "Nothing changes by itself: adopting one is yours."
        return out
    }

    // ---- 5. discipline -------------------------------------------------------------------------------------------------

    /** This week's counts and last week's: overtrading warnings, cooling-offs, answers marked wrong; Boss's own habits. */
    data class Discipline(val overtrade: Int, val coolOffs: Int, val markedWrong: Int, val prevOvertrade: Int, val prevCoolOffs: Int,
                          val prevMarkedWrong: Int, val habits: List<WeekReview.Finding>)

    /** An overtrading warning in Jarvis's log ([Overtrade], "Warned: 5 trades in 30 minutes."). */
    internal fun isOvertrade(what: String) = what.startsWith("Warned:") && what.contains("trades in ${Overtrade.WINDOW_MINUTES} minutes")
    /** A cooling-off in Jarvis's log ([CoolOff.say]). */
    internal fun isCoolOff(what: String) = what.startsWith(CoolOff.say(LocalDateTime.of(2000, 1, 1, 0, 0)).substringBefore(":"))

    /**
     * The week of [monday] from Jarvis's log ([activity]), the answers Boss marked wrong ([mistakes]) and his own round
     * trips ([own], not the bots'), each against the week before.
     */
    fun discipline(activity: List<Activity.Entry>, mistakes: List<Mistakes.Entry>, own: List<Insights.Trip>, monday: LocalDate): Discipline {
        fun inWeek(at: LocalDateTime, m: LocalDate) = mondayOf(at.toLocalDate()) == m
        val prevMon = monday.minusWeeks(1)
        fun count(m: LocalDate, f: (String) -> Boolean) = activity.count { inWeek(it.at, m) && f(it.what) }
        val w = own.filter { inWeek(it.closedAt, monday) }; val p = own.filter { inWeek(it.closedAt, prevMon) }
        return Discipline(count(monday, ::isOvertrade), count(monday, ::isCoolOff), mistakes.count { inWeek(it.at, monday) },
            count(prevMon, ::isOvertrade), count(prevMon, ::isCoolOff), mistakes.count { inWeek(it.at, prevMon) }, WeekReview.findings(w, p))
    }

    fun disciplineLines(d: Discipline): List<String> {
        fun c(n: Int, before: Int, what: String) = "${plural(n, what)} (last week $before)"
        val out = ArrayList<String>()
        out += "${c(d.overtrade, d.prevOvertrade, "overtrading warning")}, ${c(d.coolOffs, d.prevCoolOffs, "cooling-off")}, " +
            "${c(d.markedWrong, d.prevMarkedWrong, "answer")} you marked wrong."
        out += if (d.habits.isEmpty()) "Your own trades: no costly habit showed up this week."
            else "Your own trades: " + d.habits.joinToString("; ") { "${it.kind.spoken} (${plural(it.count, "trade")}, last week ${it.before})" } + "."
        return out
    }

    // ---- 6. market recap -----------------------------------------------------------------------------------------------

    /** Each session's close from [bars] (any candles, in time order or not): the last candle of each day. */
    fun closes(bars: List<Candle>): List<Pair<LocalDate, Double>> =
        bars.groupBy { it.t.toLocalDate() }.map { (d, cs) -> d to cs.maxBy { it.t }.c }.sortedBy { it.first }

    /** The market's week: [closes] per index (and [Market.VIX]), the FIIs at the week's end and before it, the sessions recorded. */
    data class MarketInput(val closes: Map<Market, List<Pair<LocalDate, Double>>>, val fiiNow: MorningCues.Fii?, val fiiBefore: MorningCues.Fii?,
                           val recordedDays: Int, val sessions: Int)

    fun marketLines(m: MarketInput, monday: LocalDate): List<String> {
        val out = ArrayList<String>()
        val sunday = monday.plusDays(6)
        fun weekOf(x: List<Pair<LocalDate, Double>>) = x.filter { !it.first.isBefore(monday) && !it.first.isAfter(sunday) }
        fun before(x: List<Pair<LocalDate, Double>>) = x.lastOrNull { it.first.isBefore(monday) }
        val moves = ArrayList<String>()
        var biggest: Triple<Market, LocalDate, Double>? = null
        for (idx in INDICES) {
            val all = m.closes[idx].orEmpty().sortedBy { it.first }
            val w = weekOf(all)
            if (w.isEmpty()) continue
            val base = before(all)
            val from = base ?: w.first()
            val last = w.last()
            moves += if (base == null && w.size == 1) "${idx.label} ${num(last.second)}"
                else "${idx.label} ${pct(100 * (last.second - from.second) / from.second)} to ${num(last.second)}" + if (base == null) " (from ${dd(from.first)})" else ""
            // The biggest day: a session's close against the session before it, over the three indices.
            val seq = listOfNotNull(base) + w
            for (i in 1 until seq.size) {
                val ch = 100 * (seq[i].second - seq[i - 1].second) / seq[i - 1].second
                if (biggest == null || abs(ch) > abs(biggest.third)) biggest = Triple(idx, seq[i].first, ch)
            }
        }
        out += if (moves.isEmpty()) "No index closes on the phone for this week." else "The week: ${moves.joinToString(", ")}."
        biggest?.let { (idx, d, ch) -> out += "Biggest day: ${dd(d)}, ${idx.label} ${pct(ch)}." }
        val vix = m.closes[Market.VIX].orEmpty().sortedBy { it.first }
        val vw = weekOf(vix)
        if (vw.isNotEmpty()) {
            val from = before(vix) ?: vw.first()
            val d = vw.last().second - from.second
            out += "India VIX ${num(from.second)} to ${num(vw.last().second)} (${String.format(Locale.ENGLISH, "%+.2f", d)})."
        }
        out += fiiLine(m.fiiNow, m.fiiBefore)
        out += "Market recorder: ${m.recordedDays} of ${plural(m.sessions, "session")} recorded."
        return out
    }

    private fun fiiLine(now: MorningCues.Fii?, before: MorningCues.Fii?): String {
        val lp = now?.longPct ?: return "FII positioning: no NSE participant OI recorded this week."
        val p0 = String.format(Locale.ENGLISH, "%.0f", lp)
        val bp = before?.takeIf { it.date.isBefore(now.date) }?.longPct
            ?: return "FIIs' index futures: $p0% long on ${dd(now.date)} (no file from before the week to compare)."
        val d = lp - bp
        val word = when {
            abs(d) < MorningCues.SAME_PTS -> "about the same"
            d > 0 -> "more long"
            else -> "more short"
        }
        return "FIIs' index futures: ${String.format(Locale.ENGLISH, "%.0f", bp)}% long on ${dd(before.date)} to $p0% on ${dd(now.date)} - $word " +
            "(${String.format(Locale.ENGLISH, "%+.0f", d)} points). Positions, not a forecast."
    }

    // ---- 7. next week --------------------------------------------------------------------------------------------------

    /**
     * The week after [monday]: its sessions, each index's expiries ([expiries]), holidays ([holiday] names a weekday that is
     * shut), [events], and Hero's expiry days (the NIFTY expiries, [HeroRules.isExpiryDay]); [heroArmed] said beside them.
     */
    fun nextWeek(monday: LocalDate, tradingDay: (LocalDate) -> Boolean, holiday: (LocalDate) -> String?, expiries: Map<Market, List<LocalDate>>,
                 events: List<Events.Event>, heroArmed: Boolean): List<String> {
        val next = monday.plusWeeks(1)
        val days = (0L until 7L).map { next.plusDays(it) }
        val sessions = days.filter(tradingDay)
        val out = ArrayList<String>()
        out += "Next week (${dd(next)} to ${dd(next.plusDays(4))}): ${plural(sessions.size, "session")}."
        val exp = WeekAhead.INDICES.flatMap { m -> expiries[m].orEmpty().distinct().filter { it in days }.map { it to m.label } }.sortedBy { it.first }
        val anyExpiry = expiries.values.any { it.isNotEmpty() }
        out += when {
            exp.isNotEmpty() -> "Expiries: " + exp.groupBy({ it.first }, { it.second }).entries.joinToString("; ") { (d, ms) -> "${dd(d)} ${ms.joinToString(", ")}" } + "."
            anyExpiry -> "Expiries: none that week."
            else -> "Expiries: not loaded yet (the contracts load in the morning)."
        }
        val shut = days.filter { it.dayOfWeek.value <= 5 && !tradingDay(it) }
        out += if (shut.isEmpty()) "Holidays: none." else "Holidays: " + shut.joinToString("; ") { d -> dd(d) + (holiday(d)?.let { " ($it)" } ?: "") } + "."
        val ev = events.filter { it.day in days }.sortedBy { it.day }
        if (ev.isNotEmpty()) out += "Events: " + ev.joinToString("; ") { "${dd(it.day)} ${it.name}" } + "."
        val hero = expiries[Market.NIFTY].orEmpty().distinct().filter { it in days && tradingDay(it) }.sorted()
        out += when {
            !anyExpiry -> "Hero's expiry days: not known until the contracts load."
            hero.isEmpty() -> "Hero's expiry days: none (no NIFTY expiry that week)."
            else -> "Hero's expiry days: ${hero.joinToString(", ") { dd(it) }}" + if (heroArmed) " (it is armed, paper only)." else " (it is not armed)."
        }
        return out
    }

    // ---- 8. the one line to watch --------------------------------------------------------------------------------------

    /**
     * One honest line from the numbers, the most important first: a running strategy flagged below its backtest (the
     * CUSUM, then the band), one still short of the trades it is judged on, a shadow nearing its bar, Boss's pace, and
     * otherwise that nothing stands out. Rupees are never in it (it may be said aloud); it never says to trade more or
     * bigger, and never to switch anything on or off.
     */
    fun watch(fs: List<Forward>, ss: List<Shadow>, d: Discipline?, liq: LiquidityShadow.Summary?): String {
        val running = fs.filter { !it.result.expectation.lottery }
        running.firstOrNull { it.result.alarm }?.let { f ->
            return "${f.title} has kept coming in below its backtest since trade ${f.result.alarmAt} - a sustained shortfall, worth a careful " +
                "look at its trades before reading more into it."
        }
        fs.firstOrNull { it.result.verdict == ForwardCheck.Verdict.BELOW }?.let { f ->
            return "${f.title} is below expectation after ${plural(f.result.trades, "trade")} - one more week of trades before it's worth reviewing."
        }
        if (d != null && d.overtrade >= 2) return "${plural(d.overtrade, "overtrading warning")} this week - the pace of your own trades is the thing to watch."
        fs.firstOrNull { it.result.verdict == ForwardCheck.Verdict.TOO_FEW }?.let { f ->
            return "${f.title} has ${f.result.trades} of ${ForwardCheck.MIN_TRADES} trades - too few to judge yet; the verdict needs more weeks of trades."
        }
        ss.filter { !it.promoted && it.summary.trades in (ShadowRules.PROMOTE_TRADES - 10) until ShadowRules.PROMOTE_TRADES && it.summary.net > 0 }
            .maxByOrNull { it.summary.trades }?.let { s ->
                return "${s.name} is ${ShadowRules.PROMOTE_TRADES - s.summary.trades} trades from the ${ShadowRules.PROMOTE_TRADES}-trade bar and ahead so far - " +
                    "it is put to you only if it still meets the bar there."
            }
        if (liq != null && !liq.enough && liq.all.trades > 0)
            return "Liquidity 15+5's candidates have ${liq.all.trades} of ${LiquidityShadow.MIN_TRADES} trades - nothing to judge about them yet."
        if (running.any { it.result.verdict == ForwardCheck.Verdict.ABOVE })
            return "A running strategy is above its backtest so far - good luck counts too; the band narrows only with more trades."
        return "Nothing stands out: the running strategies are in line with their backtests so far."
    }

    // ---- the review ----------------------------------------------------------------------------------------------------

    data class Section(val title: String, val lines: List<String>)

    /**
     * One week's review: [monday] its week, [lastSession] its last session, [made] when it was put together; [summary] the
     * three sentences (with the numbers), [plain] the same with no rupee figure (for a locked phone, a notification or a
     * voice heard by others), [watch] the one line, [sections] the eight parts. [soFar]: put together before the week ended.
     */
    data class Review(val monday: LocalDate, val lastSession: LocalDate, val made: LocalDateTime, val summary: String, val plain: String,
                      val watch: String, val sections: List<Section>, val soFar: Boolean = false) {
        val title: String get() = WeeklyReview.title(monday) + if (soFar) " (so far)" else ""
    }

    /** Everything the review is made of, gathered by the app (any part it could not read is empty or null). */
    data class Input(
        val monday: LocalDate, val lastSession: LocalDate, val made: LocalDateTime,
        val trades: List<Trade>, val heroArmed: Boolean,
        val forward: List<Forward>, val shadows: List<Shadow>, val liquidity: LiquidityShadow.Summary?,
        val discipline: Discipline?, val market: MarketInput?, val nextWeek: List<String>, val soFar: Boolean = false,
    )

    /** The verdicts in a few words: "Liquidity 15+5 in line, Solo (midday) too few trades (<20)". */
    private fun verdicts(fs: List<Forward>) = fs.joinToString(", ") { "${it.title} ${ForwardCheck.words(it.result)}" }

    fun build(i: Input): Review {
        val money = money(i.trades, i.monday, i.heroArmed)
        val w = watch(i.forward, i.shadows, i.discipline, i.liquidity)
        val t = money.total
        val week = if (i.soFar) "This week so far" else "The week of ${dm(i.monday)}"
        val first = if (t.trades == 0) "$week: no paper trades closed" + (if (t.prevTrades > 0) " (last week ${rs(t.prevNet)} on ${plural(t.prevTrades, "trade")})." else ".")
            else "$week: paper net ${rs(t.net)} on ${plural(t.trades, "trade")}, ${t.winPct}% won" +
                (if (t.prevTrades > 0) " (last week ${rs(t.prevNet)})." else " (none last week).")
        val plainFirst = when {
            t.trades == 0 -> "$week: no paper trades closed."
            else -> "$week: ${plural(t.trades, "paper trade")}, ${t.winPct}% won, " + when {
                t.net > 0.5 -> "ending up"
                t.net < -0.5 -> "ending down"
                else -> "ending flat"
            } + when {
                t.prevTrades == 0 -> "."
                t.net > t.prevNet + 0.5 -> ", better than last week."
                t.net < t.prevNet - 0.5 -> ", weaker than last week."
                else -> ", about as last week."
            }
        }
        val second = if (i.forward.isEmpty()) "No running strategy to set against its backtest." else "Live vs backtest: ${verdicts(i.forward)}."
        val summary = "$first $second $w"
        val plain = "$plainFirst $second $w"
        val sections = listOf(
            Section("Money (paper)", moneyLines(money)),
            Section("Live vs backtest", forwardLines(i.forward)),
            Section("Shadows", shadowLines(i.shadows)),
            Section("Liquidity candidates (a)-(f)", candidateLines(i.liquidity)),
            Section("Discipline", i.discipline?.let { disciplineLines(it) } ?: listOf("Jarvis's log could not be read this time.")),
            Section("Market", i.market?.let { marketLines(it, i.monday) } ?: listOf("No market data on the phone for this week.")),
            Section("Next week", i.nextWeek.ifEmpty { listOf("The calendar could not be read this time.") }),
            Section("What I'd watch", listOf(w)),
        )
        return Review(i.monday, i.lastSession, i.made, summary, plain, w, sections, i.soFar)
    }

    /** What the notification says (no rupee figure: it may show on a locked screen). */
    fun notice(r: Review): Pair<String, String> = "Weekly review ready" to r.plain

    // ---- kept reviews --------------------------------------------------------------------------------------------------

    /** [all] with [r] in (a review of the same week replaced), newest week first, at most [KEEP_WEEKS]. */
    fun keep(all: List<Review>, r: Review): List<Review> =
        (listOf(r) + all.filter { it.monday != r.monday }).sortedByDescending { it.monday }.take(KEEP_WEEKS)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\u001E", "")
    private fun unesc(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i + 1] }); i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }

    /** One review as text (version 1): a header line, the three texts, then "S" section and "L" line rows. */
    fun encode(r: Review): String = buildString {
        append("W1\t").append(r.monday).append('\t').append(r.lastSession).append('\t').append(r.made).append('\t').append(if (r.soFar) "1" else "0").append('\n')
        append(esc(r.summary)).append('\n').append(esc(r.plain)).append('\n').append(esc(r.watch))
        r.sections.forEach { s -> append("\nS\t").append(esc(s.title)); s.lines.forEach { append("\nL\t").append(esc(it)) } }
    }

    /** A review [encode] wrote, or null when [s] is not one. */
    fun decode(s: String): Review? = runCatching {
        val ls = s.split('\n')
        val h = ls[0].split('\t')
        require(h[0] == "W1" && h.size >= 5)
        val sections = ArrayList<Section>()
        for (l in ls.drop(4)) {
            when {
                l.startsWith("S\t") -> sections += Section(unesc(l.substring(2)), emptyList())
                l.startsWith("L\t") -> { val last = sections.removeAt(sections.size - 1); sections += last.copy(lines = last.lines + unesc(l.substring(2))) }
                else -> throw IllegalArgumentException("bad row")
            }
        }
        Review(LocalDate.parse(h[1]), LocalDate.parse(h[2]), LocalDateTime.parse(h[3]), unesc(ls[1]), unesc(ls[2]), unesc(ls[3]), sections, h[4] == "1")
    }.getOrNull()

    /** All kept reviews as one text; [decodeAll] skips any that does not read. */
    fun encodeAll(rs: List<Review>): String = rs.joinToString("\u001E") { encode(it) }
    fun decodeAll(s: String?): List<Review> = s?.takeIf { it.isNotBlank() }?.split('\u001E')?.mapNotNull { decode(it) }?.sortedByDescending { it.monday }.orEmpty()

    // ---- the question --------------------------------------------------------------------------------------------------

    /** Which review is asked: the newest (this week's, or the last made), or the week before this one's. */
    enum class Which { LATEST, PREVIOUS }

    private fun norm(text: String) = Spaced.joined(text)

    private val ASK = Regex(
        " (weekly|week s|weeks) (review|report|recap|summary|wrap|wrap up|wrapup|round up|roundup|report card) |" +
            " (review|recap|summary|wrap up|round up) (of|for) (the|this|last|previous|past) week | week in review |" +
            " how did (this|the|last|previous|past) week (go|end|turn out) | how (was|has been) (this|the|last|previous|past) week( been)? |" +
            " (is|iss|pichle|pichhle|pichla|pichhla|guzre|is poore|poore) (hafte|hafta|week) (ka|ki|ke) (review|hisaab|hisab|summary|report|recap) |" +
            " (hafte|hafta|week) (ka|ki) (review|hisaab|hisab|summary|report|recap) |" +
            " (hafta|week|hafte) kaisa (raha|gaya|tha) | (is|iss|pichla|pichhla|pichle) (hafta|week|hafte) kaisa (raha|gaya|tha) ")
    /**
     * Boss's own week ("my weekly review", "how did my week go": his own trades' review), an index's week, a strategy's or
     * the bots' own record, the month's, or the week ahead: their own answers.
     */
    private val NOT = Regex(" (my|mine|mera|meri|mere|i|our|nifty|banknifty|bank|sensex|finnifty|fin|vix|gold|midcap|bot|bots|strategy|strategies|arm|arms|" +
        "orb|liquidity|solo|hero|shadow|shadows|pine|script|scripts|month|monthly|mahina|mahine|next|coming|upcoming|agle|agla|aane|ahead|" +
        "look like|looking|plan|calendar|expiry|expiries|holiday|holidays|news|stock|stocks|market) ")
    private val PREV = Regex(" (last|previous|past|pichle|pichhle|pichla|pichhla|guzre) (week|weeks|hafte|hafta) ")

    /** Is the weekly review asked ("weekly review", "is hafte ka review", "how did this week go")? Null when not. */
    fun asked(text: String): Which? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Which?>(64)

    private fun askedFresh(text: String): Which? {
        val t = norm(text)
        if (!ASK.containsMatchIn(t) || NOT.containsMatchIn(t)) return null
        return if (PREV.containsMatchIn(t)) Which.PREVIOUS else Which.LATEST
    }

    /**
     * The kept review [which] names at [today]: PREVIOUS the week before this one; LATEST this week's when made, else the
     * newest kept (on a Monday morning, last week's) - null when [all] has none.
     */
    fun pick(which: Which, today: LocalDate, all: List<Review>): Review? {
        val mon = mondayOf(today)
        return when (which) {
            Which.PREVIOUS -> all.firstOrNull { it.monday == mon.minusWeeks(1) }
            Which.LATEST -> all.firstOrNull { it.monday == mon } ?: all.maxByOrNull { it.monday }
        }
    }

    /**
     * The answer: the review's three sentences ([Review.plain] when [locked]: no rupee figure), and where the whole of it is.
     * [review] null: none kept yet.
     */
    fun say(review: Review?, which: Which, locked: Boolean): String {
        if (review == null) return if (which == Which.PREVIOUS) "I have no review kept for last week, Boss - the first one is made after a week's last session (15:45)."
            else "No weekly review yet, Boss - I make it after the week's last session (15:45 on its last trading day)."
        val text = if (locked) review.plain else review.summary
        return "Boss, ${text.replaceFirstChar { it.lowercase() }} " +
            if (review.soFar) "The whole review comes after the week's last session." else "The whole review is on the Ira page."
    }
}
