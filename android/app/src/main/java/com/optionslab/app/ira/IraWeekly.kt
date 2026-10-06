package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.Market
import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.WeeklyReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import com.optionslab.ira.Market as IraMarket

/**
 * Jarvis's weekly review ([WeeklyReview]): the app's side. [prepare] runs from the day report's worker at 15:45 on the week's
 * last trading day (and, as a catch-up, from the morning check's worker - a phone that was off on Friday gets it on
 * Saturday or Monday morning), gathers the week's inputs OFF the main thread, keeps the review (the last
 * [WeeklyReview.KEEP_WEEKS], encrypted with the app's other preferences), notes it in the chat and notifies "Weekly review
 * ready". [answer] says it in three sentences when asked. Reads only: nothing is placed, changed, armed or stopped. Not in
 * IraGoldAlgo (it only talks of gold).
 */
internal object IraWeekly {
    private const val KEY = "jarvis.weekly.reviews"
    private const val NOTIFY_ID = 2035
    private val lock = Mutex()

    private val _state = MutableStateFlow<List<WeeklyReview.Review>?>(null)
    /** The kept reviews, newest first (null until read once: [refresh]). */
    val state: StateFlow<List<WeeklyReview.Review>?> = _state

    private fun on() = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    /** The kept reviews, read from the vault (off the main thread). */
    fun kept(): List<WeeklyReview.Review> =
        runCatching { WeeklyReview.decodeAll(com.optionslab.app.security.SecurePrefs.getString(KEY)) }.getOrDefault(emptyList())

    /** Reads the kept reviews into [state] (the Ira page's card). */
    suspend fun refresh() = withContext(Dispatchers.IO) { _state.value = kept() }

    private fun tradingDay(d: LocalDate) = runCatching { Market.isTradingDay(d) }.getOrDefault(d.dayOfWeek.value <= 5)

    /**
     * Makes the week's review when one is due ([WeeklyReview.due]: after the newest week's last session, not made yet);
     * otherwise nothing. Once per week: a retried or repeated run finds it made.
     */
    suspend fun prepare(context: Context?) {
        if (!on()) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                val now = Market.now().toLocalDateTime()
                val all = kept()
                val monday = WeeklyReview.due(now, all.filter { !it.soFar }.map { it.monday }, ::tradingDay) ?: return@withLock
                val r = build(monday, now, soFar = false)
                val keep = WeeklyReview.keep(all, r)
                runCatching { com.optionslab.app.security.SecurePrefs.put(KEY, WeeklyReview.encodeAll(keep)) }
                _state.value = keep
                IraHub.note("Your weekly review is ready, Boss (${r.title.lowercase()}). ${r.summary} The whole review is on the Ira page.")
                IraActivity.add("Made the weekly review (${r.title.lowercase()}).")
                // No rupee figure in the notification: it may show on a locked screen.
                val (title, text) = WeeklyReview.notice(r)
                context?.let { c -> runCatching { com.optionslab.app.work.Notifier.post(c, NOTIFY_ID, com.optionslab.app.work.Notifier.IRA, title, text, tab = "almanac") } }
            }
        }
    }

    /**
     * Jarvis's answer to "weekly review" / "how did this week go" ([which]); [locked]: the plain words only (no rupee
     * figure). This week's review once made; before that, from the week's first session on, the week so far (said, not
     * kept); otherwise the newest kept.
     */
    suspend fun answer(which: WeeklyReview.Which, locked: Boolean): String = withContext(Dispatchers.IO) {
        val now = Market.now().toLocalDateTime()
        val today = now.toLocalDate()
        val kept = kept().also { _state.value = it }
        val picked = WeeklyReview.pick(which, today, kept)
        val mon = WeeklyReview.mondayOf(today)
        if (which == WeeklyReview.Which.LATEST && picked?.monday != mon) {
            val begun = (0L..6L).map { mon.plusDays(it) }.any { d ->
                tradingDay(d) && (d.isBefore(today) || d == today && now.toLocalTime() >= java.time.LocalTime.of(9, 15))
            }
            if (begun) return@withContext WeeklyReview.say(build(mon, now, soFar = true), which, locked)
        }
        WeeklyReview.say(picked, which, locked)
    }

    // ---- the inputs (each read alone: one that fails is left empty, never the whole review) ----------------------------

    private suspend fun build(monday: LocalDate, now: LocalDateTime, soFar: Boolean): WeeklyReview.Review {
        val sunday = monday.plusDays(6)
        val last = WeeklyReview.lastSession(monday, ::tradingDay) ?: monday
        val heroArmed = runCatching {
            withTimeoutOrNull(5_000) { com.optionslab.app.data.OrbArms.view().arms.any { it.arm.source == HeroRules.ARM.source && it.armed } }
        }.getOrNull() ?: false
        val shadowRows = runCatching { com.optionslab.app.data.ShadowArms.rows() }.getOrDefault(emptyList())
        val input = WeeklyReview.Input(
            monday = monday, lastSession = last, made = now.withNano(0),
            trades = runCatching { trades(monday) }.getOrDefault(emptyList()), heroArmed = heroArmed,
            forward = runCatching { com.optionslab.app.data.ForwardRecords.rows().filter { !it.shadow }.map { WeeklyReview.Forward(it.title, it.result) } }
                .getOrDefault(emptyList()),
            shadows = shadowRows.map { r ->
                val week = r.trades.filter { t -> !t.open && t.exitTime?.toLocalDate()?.let { !it.isBefore(monday) && !it.isAfter(sunday) } == true }
                WeeklyReview.Shadow(r.variant.name, r.variant.label, week.size, week.sumOf { it.net ?: 0.0 }, r.summary, r.promoted)
            },
            liquidity = runCatching { com.optionslab.app.data.OrbArms.liquidityShadow() }.getOrNull(),
            discipline = runCatching { discipline(monday) }.getOrNull(),
            market = runCatching { market(monday, last) }.getOrNull(),
            nextWeek = runCatching { nextWeek(monday, heroArmed) }.getOrDefault(emptyList()),
            soFar = soFar,
        )
        return WeeklyReview.build(input)
    }

    /**
     * The paper round trips closed this week and last: Liquidity 15+5 per book and Hero from the arms' own book ([OrbArms]),
     * everything else from the paper trade book by who placed it ([com.optionslab.app.data.TradeBook.ownerOf]).
     */
    private suspend fun trades(monday: LocalDate): List<WeeklyReview.Trade> {
        val from = monday.minusWeeks(1)
        val out = ArrayList<WeeklyReview.Trade>()
        val books = LiquidityRules.BOOKS.associateBy { it.source }
        runCatching { com.optionslab.app.data.OrbArms.closedPaper() }.getOrDefault(emptyList()).forEach { p ->
            val exit = p.exitTime?.toLocalDate() ?: return@forEach
            if (exit.isBefore(from)) return@forEach
            val net = (p.grossPnl ?: 0.0) - p.charges
            val book = books[p.arm]
            when {
                book != null -> out += WeeklyReview.Trade(WeeklyReview.Group.LIQUIDITY,
                    "${com.optionslab.ira.LiquidityMap.indexName(LiquidityRules.underlyingOf(book))} ${LiquidityRules.minutesOf(book)}-min", exit, net, p.charges)
                p.arm == HeroRules.ARM.source -> out += WeeklyReview.Trade(WeeklyReview.Group.HERO, HeroRules.ARM.label, exit, net, p.charges)
            }
        }
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val pine = runCatching { com.optionslab.app.data.PineScripts.items.value.map { it.name }.toSet() }.getOrDefault(emptySet())
        runCatching { com.optionslab.app.data.TradeBook.trips(false) }.getOrDefault(emptyList()).forEach { t ->
            if (t.day.isBefore(from)) return@forEach
            val owner = com.optionslab.app.data.TradeBook.ownerOf(t, owners)
            val g = WeeklyReview.groupOf(owner, pine)
            // Liquidity 15+5 and Hero are counted from their own book (per book, with its charges), never twice.
            if (g == WeeklyReview.Group.LIQUIDITY || g == WeeklyReview.Group.HERO) return@forEach
            out += WeeklyReview.Trade(g, if (g == WeeklyReview.Group.SOLO) g.label else owner, t.day, t.net, t.charges)
        }
        return out
    }

    /** Overtrading warnings and cooling-offs from Jarvis's log, the answers marked wrong, Boss's own trades' habits. */
    private suspend fun discipline(monday: LocalDate): WeeklyReview.Discipline {
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val live = runCatching { com.optionslab.app.data.AppSettings.load().live }.getOrDefault(false)
        val own = runCatching { IraAccount.trips(live, owners).filter { it.owner.startsWith("Manual") } }.getOrDefault(emptyList())
        return WeeklyReview.discipline(IraActivity.entries(), IraTools.mistakes(), own, monday)
    }

    /** The indices' closes from the candles on the phone, the FIIs' positioning the recorder kept, the sessions recorded. */
    private fun market(monday: LocalDate, last: LocalDate): WeeklyReview.MarketInput {
        val sunday = monday.plusDays(6)
        val closes = (WeeklyReview.INDICES + IraMarket.VIX).associateWith { m -> WeeklyReview.closes(IraHub.recentBars(m)) }.filterValues { it.isNotEmpty() }
        val days = runCatching { com.optionslab.app.data.MarketRecorder.days().map { it.first } }.getOrDefault(emptyList())
        val recorded = days.count { !it.isBefore(monday) && !it.isAfter(sunday) && tradingDay(it) }
        val sessions = (0L..6L).map { monday.plusDays(it) }.count { !it.isAfter(last) && tradingDay(it) }
        // The FIIs at the week's end (the newest participant file dated in it) and before it (the newest dated earlier). Day
        // files read newest first, from ten days before the week, stopping once both are found (a weekly read, off the main thread).
        var now: com.optionslab.ira.MorningCues.Fii? = null
        var before: com.optionslab.ira.MorningCues.Fii? = null
        for (d in days.filter { !it.isBefore(monday.minusDays(10)) && !it.isAfter(sunday.plusDays(1)) }.sortedDescending()) {
            val ps = runCatching { com.optionslab.ira.MorningCues.participants(com.optionslab.app.data.MarketRecorder.readDay(d).lines) }.getOrDefault(emptyList())
            for (p in ps) {
                val f = com.optionslab.ira.MorningCues.fii(p) ?: continue
                if (!p.date.isBefore(monday) && !p.date.isAfter(sunday)) { if (now == null || f.date.isAfter(now.date)) now = f }
                else if (p.date.isBefore(monday)) { if (before == null || f.date.isAfter(before.date)) before = f }
            }
            if (now != null && before != null) break
        }
        return WeeklyReview.MarketInput(closes, now, before, recorded, sessions)
    }

    /** Next week from the exchange calendar, the loaded contracts' expiries and the events (Boss's own included: an in-app card). */
    private fun nextWeek(monday: LocalDate, heroArmed: Boolean): List<String> {
        val next = monday.plusWeeks(1)
        val book = com.optionslab.app.data.Holidays.book()
        val exp = com.optionslab.ira.WeekAhead.INDICES.associateWith { m -> runCatching { Market.upcomingExpiries(m.name) }.getOrDefault(emptyList()) }
        val events = com.optionslab.ira.Events.builtIn(next, next.plusDays(6)) + runCatching { IraEvents.owner() }.getOrDefault(emptyList())
        return WeeklyReview.nextWeek(monday, ::tradingDay,
            { d -> if (book.holiday(d)) book.upcoming(d).firstOrNull { it.first == d }?.second ?: "a market holiday" else null },
            exp, events, heroArmed)
    }
}
