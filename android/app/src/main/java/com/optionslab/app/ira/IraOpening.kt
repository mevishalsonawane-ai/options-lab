package com.optionslab.app.ira

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.Candle
import com.optionslab.ira.LiquidityMap
import com.optionslab.ira.OpeningRead
import java.time.LocalDate
import java.time.LocalDateTime
import com.optionslab.ira.Market as IraMarket

/**
 * The opening read ([OpeningRead], Boss, 06 Oct 2026): just after the first 5-minute candle closes - 09:20-09:25 on a
 * trading day, once, in the words lane ([watch], [Automations.Auto.OPENING], its own switch under Market alerts) - one
 * short note in the chat on how the market opened; and the same when he asks ("how did the market open", [answer]).
 * Read from what the app already holds, each with a time limit: the index candles Jarvis last read (else one read of
 * them now), the minutes Liquidity 15+5 read this minute (else one read of them), the arm's switch and size (its book's
 * cheap reads, a short wait on its lock, never held across a slow read), the market recorder's last GIFT Nifty reading
 * (never fetched here) and the big-move read on the same candles. Words only: nothing here arms, places, closes or
 * changes anything; no figure of the account is in it. In the chat only (never spoken); the first two lines while
 * saving battery or on short answers; never in quiet hours. Not in IraGoldAlgo.
 */
internal object IraOpening {
    /** The day the note was last put in the chat (remembered across a restart). */
    private const val KEY_DONE = "jarvis.opening.done"
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun doneOn(): LocalDate? = runCatching {
        com.optionslab.app.security.SecurePrefs.getString(KEY_DONE)?.let { LocalDate.parse(it) }
    }.getOrNull()

    private fun candles(bars: List<Bar>): List<Candle> = bars.map { Candle(it.start, it.open, it.high, it.low, it.close) }

    /** An index's 1-minute bars as Liquidity 15+5 reads them: the arm's own read of the minute, else one read now (bounded). */
    private suspend fun minutes(u: String, now: LocalDateTime): List<Bar>? =
        com.optionslab.app.data.OrbArms.liquidityMinutesKept(u, now)
            ?: runCatching { kotlinx.coroutines.withTimeoutOrNull(15_000) { com.optionslab.app.data.OrbArms.liquidityMinutesFor(u, now) } }.getOrNull()

    /**
     * [m]'s 1-minute candles holding today's opening: those Jarvis last read, else the arm's minutes ([ones]), else one read
     * of the feed now ([IraHub.freshBars], bounded); whichever is there when none holds it.
     */
    private suspend fun barsOf(m: IraMarket, ones: List<Bar>?, today: LocalDate, now: LocalDateTime): List<Candle> {
        val recent = runCatching { IraHub.recentBars(m) }.getOrDefault(emptyList())
        if (OpeningRead.hasOpening(recent, today, now)) return recent
        val arm = ones?.let { candles(it) }
        if (arm != null && OpeningRead.hasOpening(arm, today, now)) return arm
        val fresh = runCatching { IraHub.freshBars(m) }.getOrDefault(emptyList())
        return listOf(fresh, arm.orEmpty(), recent).firstOrNull { OpeningRead.hasOpening(it, today, now) } ?: fresh.ifEmpty { recent }
    }

    /** What the read is made of, read now. Each part read on its own: one that fails is left out, never guessed. */
    suspend fun facts(): OpeningRead.Facts {
        val mk = com.optionslab.app.data.Market
        val now = mk.now().toLocalDateTime()
        val today = now.toLocalDate()
        val trading = runCatching { mk.isTradingDay(today) }.getOrDefault(true)
        if (!trading || now.toLocalTime().isBefore(LiquidityRules.SESSION_OPEN)) return OpeningRead.Facts(now = now, tradingDay = trading)
        // Liquidity 15+5's indices: the minutes the arm reads (the levels and the first trigger are read from them).
        val ones = LiquidityRules.UNDERLYINGS.associateWith { u -> runCatching { minutes(u, now) }.getOrNull() }
        val bars = OpeningRead.MARKETS.associateWith { m -> runCatching { barsOf(m, ones[m.name], today, now) }.getOrDefault(emptyList()) }
        val indices = OpeningRead.MARKETS.map { m -> runCatching { OpeningRead.index(m, bars[m].orEmpty(), now) }.getOrElse { OpeningRead.Index(m, null, null) } }
        // GIFT Nifty: the recorder's last reading, as the 09:00 check reads it (nothing fetched).
        val gift = runCatching {
            com.optionslab.app.data.MarketRecorder.lastGift()?.let { (read, g) -> OpeningRead.Gift(g.last, g.at ?: read) }
        }.getOrNull()
        // Where each Liquidity index opened against its books' levels as they stood at the open.
        val places = LiquidityRules.UNDERLYINGS.mapNotNull { u ->
            val o = ones[u] ?: return@mapNotNull null
            val gap = indices.firstOrNull { it.market.name == u }?.gap ?: return@mapNotNull null
            runCatching {
                val levels = LiquidityRules.BOOKS.filter { LiquidityRules.underlyingOf(it) == u }
                    .mapNotNull { arm -> OpeningRead.levelsAtOpen(o, u, LiquidityRules.minutesOf(arm), today) }.flatten()
                OpeningRead.place(u, gap.open, gap.prevClose, levels)
            }.getOrNull()
        }
        // The arm: its switch and size from its book (a short wait on its lock; said as not read otherwise), and its first
        // trigger from each book's levels now (the nearest break with room still ahead).
        val armed = runCatching { kotlinx.coroutines.withTimeoutOrNull(3_000) { com.optionslab.app.data.OrbArms.liquidityArmed() } }.getOrNull()
        val lots = runCatching { kotlinx.coroutines.withTimeoutOrNull(3_000) { com.optionslab.app.data.OrbArms.liquidityLots() } }.getOrNull()
        val reads = runCatching {
            LiquidityRules.BOOKS.mapNotNull { arm ->
                val u = LiquidityRules.underlyingOf(arm)
                ones[u]?.let { LiquidityMap.read(it, u, LiquidityRules.minutesOf(arm), now) }
            }
        }.getOrDefault(emptyList())
        val trigger = runCatching { com.optionslab.ira.LiquidityWhyNot.nearestWords(reads) }.getOrNull()
        val arm = OpeningRead.Arm(armed, lots, trigger, levelsRead = reads.any { it.state == LiquidityMap.State.OK })
        // Nifty's big-move read now, on the same candles (India VIX's as Jarvis last read them).
        val bigMove = runCatching {
            com.optionslab.ira.BigMoveRisk.read(IraMarket.NIFTY, bars[IraMarket.NIFTY].orEmpty(), IraHub.recentBars(IraMarket.VIX), now, true)
        }.getOrNull()
        return OpeningRead.Facts(now = now, tradingDay = true, indices = indices, gift = gift, places = places, arm = arm, bigMove = bigMove)
    }

    /** The answer to "how did the market open" (the whole read). */
    suspend fun answer(): String = OpeningRead.say(facts())

    /**
     * Each round of the words lane: 09:20-09:25 on a trading day, once, the read in the chat ([OpeningRead.due]). Never in
     * quiet hours; while saving battery or on short answers, the first two lines. When the opening candles are not on the
     * phone yet, the next round tries again (within the window). Words only.
     */
    suspend fun watch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.OPENING)) return
        if (runCatching { JarvisVoice.quietNow() }.getOrDefault(true)) return
        val mk = com.optionslab.app.data.Market
        val now = mk.now().toLocalDateTime()
        if (!OpeningRead.due(now, mk.isTradingDay(now.toLocalDate()), doneOn())) return
        val ctx = IraHub.appContext()
        val brief = IraTools.brief || runCatching { com.optionslab.app.work.Battery.saving(ctx) }.getOrDefault(false)
        val made = postOnce(now.toLocalDate(), {
            // Bounded as a whole (the words lane runs its checks one after another): too slow, the next round tries again.
            val f: OpeningRead.Facts = kotlinx.coroutines.withTimeoutOrNull(45_000) { facts() } ?: error("the open could not be read in time")
            check(OpeningRead.ready(f)) { "the opening candles are not read yet" }
            OpeningRead.say(f, brief)
        }) { IraHub.note(it) }
        if (made) Automations.acted(Automations.Auto.OPENING, "Put the opening read in the chat.")
    }

    /**
     * Puts [make]'s text with [post] once on [day]. One at a time. The text is made first: should that fail or be cancelled,
     * the day is not kept as done and the next round tries again. Then the day is kept as done before the note is put, so
     * a restart midway never puts it twice. True when it was put.
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
