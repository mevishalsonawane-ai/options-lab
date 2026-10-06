package com.optionslab.app.ira

import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.LiquidityMap
import com.optionslab.ira.TomorrowPlan
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import com.optionslab.ira.Market as IraMarket

/**
 * Tomorrow's plan ([TomorrowPlan], Boss, 06 Oct 2026): after the close - from 15:45 on a trading day, once, in the words
 * lane and after the evening harvest ([watch], [Automations.Auto.TOMORROW], in Coach me) - one note in the chat that prepares
 * Boss for the next session; and the same when he asks ("what's the plan for tomorrow", [answer]). Read from what the app
 * already holds: the exchange calendar, the contract list's expiries, Liquidity 15+5's own book and candles, Solo's record,
 * the events, the market recorder's files, the candles Jarvis last read. Words only: nothing here arms, places, closes or
 * changes anything. In the chat only (never spoken); shorter while saving battery or on short answers; never in quiet
 * hours. Not in IraGoldAlgo.
 */
internal object IraTomorrow {
    /** The day the note was last put in the chat (remembered across a restart). */
    private const val KEY_DONE = "jarvis.tomorrow.done"
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The FII/DII rows the recorder kept: decrypted at most every 30 minutes (they change once a day). */
    private const val FLOWS_EVERY_MS = 30 * 60_000L
    @Volatile private var flowsKept: Triple<Long, LocalDate, List<com.optionslab.ira.Flows.Flow>>? = null

    private fun doneOn(): LocalDate? = runCatching {
        com.optionslab.app.security.SecurePrefs.getString(KEY_DONE)?.let { LocalDate.parse(it) }
    }.getOrNull()

    /** Each Liquidity book's map at [now] from the arm's own read of the minute, else the same candles read now (no order). */
    private suspend fun reads(now: LocalDateTime): List<LiquidityMap.Read> = LiquidityRules.UNDERLYINGS.flatMap { u ->
        val ones = com.optionslab.app.data.OrbArms.liquidityMinutesKept(u, now)
            ?: runCatching { kotlinx.coroutines.withTimeoutOrNull(20_000) { com.optionslab.app.data.OrbArms.liquidityMinutesFor(u, now) } }.getOrNull()
            ?: return@flatMap emptyList<LiquidityMap.Read>()
        LiquidityRules.BOOKS.filter { LiquidityRules.underlyingOf(it) == u }.map { arm -> LiquidityMap.read(ones, u, LiquidityRules.minutesOf(arm), now) }
    }

    /** The FII/DII cash figures in the newest of the recorder's last three day files that holds them (read only). */
    private fun flows(today: LocalDate, nowMs: Long = System.currentTimeMillis()): List<com.optionslab.ira.Flows.Flow> {
        flowsKept?.takeIf { it.second == today && nowMs - it.first < FLOWS_EVERY_MS }?.let { return it.third }
        val rec = com.optionslab.app.data.MarketRecorder
        val f = runCatching {
            rec.days().map { it.first }.filter { !it.isAfter(today) }.sortedDescending().take(3)
                .firstNotNullOfOrNull { d -> TomorrowPlan.flowsOf(rec.dayRecords(d).lines).takeIf { it.isNotEmpty() } }
        }.getOrNull().orEmpty()
        flowsKept = Triple(nowMs, today, f)
        return f
    }

    /** What the plan is made of, read now. Each part read on its own: one that fails is left out, never guessed. */
    suspend fun facts(): TomorrowPlan.Facts {
        val mk = com.optionslab.app.data.Market
        val now = mk.now().toLocalDateTime()
        val today = mk.today()
        val next = com.optionslab.ira.ExpiryEve.nextTradingDay(today) { mk.isTradingDay(it) }
        val holidays = if (next == null) emptyList() else runCatching {
            val book = com.optionslab.app.data.Holidays.book()
            generateSequence(today.plusDays(1)) { it.plusDays(1) }.takeWhile { it.isBefore(next) }
                .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY && book.holiday(it) }
                .map { d -> d to (book.upcoming(d).firstOrNull { it.first == d }?.second ?: "a market holiday") }.toList()
        }.getOrDefault(emptyList())
        val known = runCatching { mk.upcomingExpiries("NIFTY").isNotEmpty() }.getOrDefault(false)
        val expiries = if (next == null || !known) emptyList() else
            listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY, IraMarket.FINNIFTY, IraMarket.SENSEX)
                .filter { m -> runCatching { mk.isExpiryDay(m.name, next) }.getOrDefault(false) }
        // The arms' own book: Liquidity 15+5's day, switch and size; Hero's switch (reads only).
        val view = runCatching { kotlinx.coroutines.withTimeoutOrNull(10_000) { com.optionslab.app.data.OrbArms.view() } }.getOrNull()
        val liq = view?.arms?.firstOrNull { it.arm.liquidity }
        val hero = view?.arms?.firstOrNull { it.arm.hero }
        val day = liq?.let { a -> runCatching { TomorrowPlan.dayOf(com.optionslab.ira.LiquidityRecord.rows(a.today.map { IraBots.tradeOf(it) }), today) }.getOrNull() }
        val levels = runCatching { TomorrowPlan.levels(reads(now)) }.getOrDefault(emptyList())
        // Solo (midday): its switch and its forward test's count.
        val soloOn = runCatching { IraSolo.on }.getOrNull()
        val soloTrades = runCatching { IraSolo.forward().trades }.getOrNull()
        // The events on the days up to the next session: the built-in ones and Boss's own (the expiries are their own line).
        val events = if (next == null) emptyList() else runCatching {
            com.optionslab.ira.Events.builtIn(today.plusDays(1), next) + IraEvents.owner()
        }.getOrDefault(emptyList())
        // The FIIs' latest positioning (NSE's participant OI the recorder read) and the FII/DII cash figures it kept.
        val fii = runCatching {
            val ps = com.optionslab.app.data.MarketRecorder.participantsRecorded()
            com.optionslab.ira.MorningCues.fiiBrief(com.optionslab.ira.MorningCues.fii(ps.getOrNull(0)), com.optionslab.ira.MorningCues.fii(ps.getOrNull(1)), today)
        }.getOrNull()
        val flows = runCatching { flows(today) }.getOrDefault(emptyList())
        // Today's big-move read at the close (Nifty, from the candles Jarvis last read): only once the session is over.
        val tradingToday = runCatching { mk.isTradingDay(today) }.getOrDefault(false)
        val bigMove = if (tradingToday && !now.toLocalTime().isBefore(java.time.LocalTime.of(15, 30))) runCatching {
            GlanceReads.bigMove(IraMarket.NIFTY, today.atTime(15, 29, 59), true)
        }.getOrNull() else null
        val lesson = runCatching { IraTradeLessons.wrapLine() }.getOrNull()
        return TomorrowPlan.Facts(
            today = today, next = next, holidays = holidays, expiries = expiries, expiriesKnown = known,
            liquidity = day, levels = levels, armed = liq?.armed, lots = liq?.lots,
            soloOn = soloOn, soloTrades = soloTrades, heroArmed = hero?.let { it.armed && it.retired == null },
            events = events, fii = fii, flows = flows, bigMove = bigMove, lesson = lesson,
        )
    }

    /** The answer to "what's the plan for tomorrow" (the whole plan; the hub keeps it off a locked phone). */
    suspend fun answer(): String = TomorrowPlan.say(facts())

    /**
     * Each round of the words lane, and after the evening harvest: from 15:45 on a trading day, once, the plan in the chat
     * ([TomorrowPlan.due]). Never in quiet hours; while saving battery or on short answers, the short plan. Words only.
     */
    suspend fun watch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.TOMORROW)) return
        if (runCatching { JarvisVoice.quietNow() }.getOrDefault(true)) return
        val mk = com.optionslab.app.data.Market
        val now = mk.now().toLocalDateTime()
        if (!TomorrowPlan.due(now, mk.isTradingDay(now.toLocalDate()), doneOn())) return
        val ctx = IraHub.appContext()
        val brief = IraTools.brief || runCatching { com.optionslab.app.work.Battery.saving(ctx) }.getOrDefault(false)
        if (postOnce(now.toLocalDate(), { TomorrowPlan.say(facts(), brief) }) { IraHub.note(it) })
            Automations.acted(Automations.Auto.TOMORROW, "Put tomorrow's plan in the chat.")
    }

    /**
     * Puts [make]'s text with [post] once on [day]. One at a time (the words lane and the harvest may both call). The text
     * is made first: should that fail or be cancelled, the day is not kept as done and the next round tries again. Then the
     * day is kept as done before the note is put, so a restart midway never puts it twice. True when it was put.
     */
    internal suspend fun postOnce(day: LocalDate, make: suspend () -> String, post: (String) -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        try {
            if (doneOn() == day) return false
            val text = runCatching { make() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull() ?: return false
            runCatching { com.optionslab.app.security.SecurePrefs.put(KEY_DONE, day.toString()) }
            post(text)
            return true
        } finally {
            busy.set(false)
        }
    }
}
