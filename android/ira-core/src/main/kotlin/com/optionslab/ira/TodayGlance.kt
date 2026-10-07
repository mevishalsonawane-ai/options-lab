package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Home's "Today at a glance" card (Boss, 06 Oct 2026), in words: the market's state, the morning cues before the open, the
 * big-move risk per index in the session, the running strategies (Liquidity 15+5, Solo (midday), Hero, Jarvis's own
 * trades), their live-vs-backtest verdicts and the next events of today and tomorrow. Every figure comes in as [Facts] the
 * app has already read; this only words them. Information only: nothing here suggests, places or changes a trade. Pure.
 */
object TodayGlance {
    enum class Phase { PRE_OPEN, OPEN, CLOSED }

    /** Where a tap on a row goes (Home's own shortcuts). */
    const val TO_STRATEGIES = "strategy"
    const val TO_SOLO = "jarvis"
    const val TO_JARVIS = "ira"
    const val TO_PNL = "pnl"

    /** Solo (midday) decides at this minute of the day (12:00). */
    const val SOLO_DECIDES = 12 * 60
    /** Events shown: at most this many, of today and tomorrow. */
    const val EVENTS = 3
    /** A holiday this many days ahead or fewer is said. */
    const val HOLIDAY_DAYS = 7L

    const val RISK_NOTE = "These badges read how big the next 5-minute moves may be, not their side - ${BigMoveRisk.CAVEAT}. Not a reason to enter or exit."
    const val NO_EVENTS = "No events today or tomorrow."

    private val OPEN: LocalTime = LocalTime.of(9, 15)
    private val CLOSE: LocalTime = LocalTime.of(15, 30)

    // ---- the facts in, the card out --------------------------------------------------------------------------------

    /** Liquidity 15+5: [armed], [lots] a new entry buys, today's [trades] and their [net] after charges, the [open] one. */
    data class Liquidity(val armed: Boolean, val lots: Int?, val trades: Int, val net: Double?, val open: Open?)

    /** An open position: what was bought, at [entry], its resting [stop] (null: none yet). */
    data class Open(val symbol: String, val entry: Double, val stop: Double?)

    /**
     * Solo (midday): [on], today's trade (null: none), its forward test's closed trades ([closed] of [target]), their [net]
     * after charges (null: not given), and [switchedOff] when Solo switched itself off (its forward test's bar).
     */
    data class Solo(val on: Boolean, val today: SoloTrade?, val closed: Int, val target: Int = 60, val net: Double? = null,
                    val switchedOff: Boolean = false)

    /** Solo's trade today: [symbol], still [open], its [net] once closed. */
    data class SoloTrade(val symbol: String, val open: Boolean, val net: Double?)

    /** Hero: [armed]; [expiryToday] NIFTY's nearest expiry is today (null: the contract list is not on the phone). */
    data class Hero(val armed: Boolean, val expiryToday: Boolean?)

    /**
     * What the card is made from, read by the app off the main thread: [now] (IST), [tradingToday], [nextSession] (the next
     * trading day after today; null: not known), [expiriesToday] (index names), [holidays] (upcoming, from today), the morning
     * cue lines [gift] / [fii] (already worded, null: none), the big-move [risks] (Nifty, BankNifty), the strategies (null:
     * not read), the live-vs-backtest [forward] results and the [events] ahead.
     */
    data class Facts(
        val now: LocalDateTime, val tradingToday: Boolean, val nextSession: LocalDate? = null,
        val expiriesToday: List<String> = emptyList(), val holidays: List<Pair<LocalDate, String>> = emptyList(),
        val gift: String? = null, val fii: String? = null, val risks: List<BigMoveRisk.Read> = emptyList(),
        val liquidity: Liquidity? = null, val solo: Solo? = null, val hero: Hero? = null, val jarvisOpen: Int? = null,
        val forward: List<ForwardCheck.Result> = emptyList(), val events: List<Events.Event> = emptyList(),
    )

    /** One strategy row: [title], its [state] ("armed · 2 lots"), the lines under it, and where a tap goes ([to]). */
    data class Row(val title: String, val state: String, val lines: List<String>, val to: String, val on: Boolean)

    /** A big-move badge: the index, the [word] shown ("high · 2.5x"), its [level] (null: not known). */
    data class Badge(val market: String, val word: String, val level: BigMoveRisk.Level?)

    /** The card: everything worded. [asOf] "Updated 10:42". */
    data class Card(
        val phase: Phase, val market: String, val notices: List<String>, val cues: List<String>, val risks: List<Badge>,
        val rows: List<Row>, val verdicts: List<String>, val events: List<String>, val asOf: String,
    )

    fun card(f: Facts): Card {
        val ph = phase(f.now, f.tradingToday)
        val today = f.now.toLocalDate()
        val notices = listOfNotNull(expiryLine(f.expiriesToday, f.tradingToday), holidayLine(today, f.holidays))
        val cues = if (ph == Phase.PRE_OPEN) listOfNotNull(f.gift, f.fii) else emptyList()
        val risks = if (ph == Phase.OPEN) f.risks.map { badge(it, f.now) } else emptyList()
        val rows = listOfNotNull(
            f.liquidity?.let { liquidity(it) },
            f.solo?.let { solo(it, f.now, f.tradingToday) },
            f.hero?.let { hero(it, f.tradingToday) },
            f.jarvisOpen?.let { jarvis(it) },
        )
        return Card(ph, marketLine(f.now, f.tradingToday, f.nextSession), notices, cues, risks, rows,
            f.forward.map { verdict(it) }, events(f.events, today), "Updated ${hm(f.now.toLocalTime())}")
    }

    // ---- the market ----------------------------------------------------------------------------------------------

    fun phase(now: LocalDateTime, tradingToday: Boolean): Phase {
        val t = now.toLocalTime()
        return when {
            !tradingToday -> Phase.CLOSED
            t.isBefore(OPEN) -> Phase.PRE_OPEN
            t.isBefore(CLOSE) -> Phase.OPEN
            else -> Phase.CLOSED
        }
    }

    /** "Pre-open · opens at 09:15", "Market open · closes at 15:30 (2h 10m)", "Market closed · next session tomorrow, 09:15". */
    fun marketLine(now: LocalDateTime, tradingToday: Boolean, nextSession: LocalDate?): String = when (phase(now, tradingToday)) {
        Phase.PRE_OPEN -> "Pre-open · opens at 09:15 (in ${span(java.time.Duration.between(now.toLocalTime(), OPEN).toMinutes())})"
        Phase.OPEN -> "Market open · closes at 15:30 (${span(java.time.Duration.between(now.toLocalTime(), CLOSE).toMinutes())} left)"
        Phase.CLOSED -> "Market closed" + (nextSession?.let { " · next session ${dayWord(it, now.toLocalDate())}, 09:15" } ?: "")
    }

    /** "Expiry today: NIFTY, SENSEX"; null on a day without one (or without a session). */
    fun expiryLine(indices: List<String>, tradingToday: Boolean): String? =
        indices.distinct().takeIf { tradingToday && it.isNotEmpty() }?.let { "Expiry today: ${it.joinToString(", ")}" }

    /** "Holiday today: Dussehra", or the next within [HOLIDAY_DAYS] days ("Holiday Tue 20 Oct: Dussehra - market shut"). */
    fun holidayLine(today: LocalDate, holidays: List<Pair<LocalDate, String>>): String? {
        val next = holidays.filter { !it.first.isBefore(today) && !it.first.isAfter(today.plusDays(HOLIDAY_DAYS)) }.minByOrNull { it.first } ?: return null
        return if (next.first == today) "Holiday today: ${next.second}" else "Holiday ${dayWord(next.first, today)}: ${next.second} - market shut"
    }

    // ---- the morning cues ----------------------------------------------------------------------------------------

    /** The GIFT Nifty line: [MorningCues.gift]'s lead, or that there is no reading yet. */
    fun giftLine(g: RecorderFeeds.Gift?, readAt: LocalDateTime?, prev: MorningCues.Close?, now: LocalDateTime): String =
        if (g == null || readAt == null) "GIFT Nifty: no reading on the phone yet (the market recorder reads it from 06:30)."
        else MorningCues.gift(g, readAt, prev, now).first()

    /** The FIIs' line: [MorningCues.fiiBrief], or the newest file's date when it is old, or that there is none. */
    fun fiiLine(cur: MorningCues.Fii?, prev: MorningCues.Fii?, today: LocalDate): String =
        MorningCues.fiiBrief(cur, prev, today)?.let { "$it." }
            ?: cur?.let { "FII positioning: the newest NSE participant file on the phone is from ${dm(it.date)} - too old to read as today's." }
            ?: "FII positioning: no NSE participant file on the phone yet (NSE publishes it after 18:30)."

    // ---- the big-move risk ---------------------------------------------------------------------------------------

    /** "high · 2.5x", "low · 0.4x", "not known yet"; "(as of 10:05)" when the last candle read is old. */
    fun badge(r: BigMoveRisk.Read, now: LocalDateTime): Badge {
        val lvl = r.level
        val x = r.times
        val word = if (lvl == null || x == null) "not known yet" else {
            val stale = r.asOf?.takeIf { it.plusMinutes(1 + BigMoveRisk.STALE_MIN).isBefore(now) }?.let { " (as of ${hm(it.toLocalTime())})" } ?: ""
            "${lvl.word} · ${"%.1f".format(Locale.ENGLISH, x)}x$stale"
        }
        return Badge(r.market.label, word, lvl)
    }

    // ---- the strategies ------------------------------------------------------------------------------------------

    fun liquidity(l: Liquidity): Row {
        val state = if (l.armed) "armed" + (l.lots?.let { " · $it lot${if (it == 1) "" else "s"}" } ?: "") else "off"
        val today = when (l.trades) {
            0 -> "Today: no trades"
            else -> "Today: ${l.trades} trade${if (l.trades == 1) "" else "s"}" + (l.net?.let { ", ${signed(it)}" } ?: "")
        }
        val open = l.open?.let { o -> "Open ${o.symbol} · bought ${px(o.entry)} · " + (o.stop?.let { "stop ${px(it)}" } ?: "no stop yet") }
        return Row("Liquidity 15+5", state, listOfNotNull(today, open), TO_STRATEGIES, l.armed)
    }

    fun solo(s: Solo, now: LocalDateTime, tradingToday: Boolean): Row {
        val state = (if (s.on) "on" else if (s.switchedOff) "switched itself off" else "off") + " · ${s.closed} of ${s.target}" +
            (s.net?.takeIf { s.closed > 0 }?.let { " · net ${signed(it)}" } ?: "")
        val t = s.today
        val minute = now.hour * 60 + now.minute
        val line = when {
            t != null && t.open -> "Today: ${t.symbol} · open"
            t != null -> "Today: ${t.symbol}" + (t.net?.let { " · ${signed(it)}" } ?: " · closed")
            !s.on && s.switchedOff -> "Switching it back on is Boss's switch"
            !s.on -> null
            !tradingToday -> "No session today"
            minute < SOLO_DECIDES -> "Decides at 12:00"
            else -> "No trade today"
        }
        return Row("Solo (midday)", state, listOfNotNull(line), TO_SOLO, s.on)
    }

    fun hero(h: Hero, tradingToday: Boolean): Row {
        val state = if (h.armed) "armed" else "off"
        val line = when {
            !tradingToday -> null
            h.expiryToday == null -> "Expiry not known (no contract list on the phone)"
            h.expiryToday -> "NIFTY expiry today" + if (h.armed) " · may trade 13:30-14:45" else ""
            else -> "Not a NIFTY expiry day" + if (h.armed) " · stays out" else ""
        }
        return Row("Hero (expiry)", state, listOfNotNull(line), TO_STRATEGIES, h.armed)
    }

    fun jarvis(open: Int): Row = Row("Jarvis's own trades", if (open == 0) "none open" else "$open open", emptyList(), TO_JARVIS, open > 0)

    // ---- live vs backtest ----------------------------------------------------------------------------------------

    /** "Liquidity 15+5: in line · 34 trades", "Solo: too few trades (<20) · 3 so far". */
    fun verdict(r: ForwardCheck.Result): String {
        val name = r.expectation.name
        val n = r.trades
        val count = when {
            r.verdict == ForwardCheck.Verdict.BELOW -> ""
            r.verdict == ForwardCheck.Verdict.TOO_FEW -> " · $n so far"
            else -> " · $n trade${if (n == 1) "" else "s"}"
        }
        return "$name: ${ForwardCheck.words(r)}$count"
    }

    // ---- events --------------------------------------------------------------------------------------------------

    /** The next [EVENTS] events of today and tomorrow: "Today: NIFTY expiry", "Tomorrow: US Fed decision overnight (FOMC)". */
    fun events(all: List<Events.Event>, today: LocalDate): List<String> =
        all.filter { it.day == today || it.day == today.plusDays(1) }.sortedWith(compareBy({ it.day }, { it.name })).distinctBy { it.day to it.name }
            .take(EVENTS).map { e -> (if (e.day == today) "Today" else "Tomorrow") + ": ${e.name}" + if (e.owner) " (added by you)" else "" }

    // ---- words ---------------------------------------------------------------------------------------------------

    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun dm(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun dayWord(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${dm(d)}"
    }

    /** "45m", "2h 10m", "3h". */
    internal fun span(minutes: Long): String {
        val m = minutes.coerceAtLeast(0)
        return when {
            m < 60 -> "${m}m"
            m % 60 == 0L -> "${m / 60}h"
            else -> "${m / 60}h ${m % 60}m"
        }
    }

    internal fun px(x: Double) = "%.2f".format(Locale.ENGLISH, x)

    /** "+₹1,240", "−₹300", "₹0". */
    internal fun signed(x: Double): String {
        val r = round(x)
        val body = "₹" + "%,.0f".format(Locale.ENGLISH, abs(r))
        return when { r > 0 -> "+$body"; r < 0 -> "−$body"; else -> body }
    }
}
