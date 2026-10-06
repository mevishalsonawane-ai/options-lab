package com.optionslab.app.ira

import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.ira.LiquidityMap
import java.time.LocalDateTime

/**
 * Jarvis on Liquidity 15+5's map of the market ([LiquidityMap]): "where are the liquidity levels" answered from the
 * candles the arm reads ([com.optionslab.app.data.OrbArms.liquidityMinutesFor]), and the heads-up before a likely entry
 * ([watch], [Automations.Auto.LIQUIDITY]) from the arm's own read of the minute. Read only: nothing here places, changes
 * or closes an order, and the arm decides on its own. Not in IraGoldAlgo (no Liquidity arm).
 */
internal object IraLiquidity {
    /** Each book's map at [now]: BANKNIFTY 15 and 5, FINNIFTY 30 and 5; [kept] true: only the arm's own read (no network). */
    private suspend fun reads(now: LocalDateTime, unds: List<String>, kept: Boolean): List<LiquidityMap.Read> {
        val ones = unds.associateWith { u ->
            if (kept) com.optionslab.app.data.OrbArms.liquidityMinutesKept(u, now)
            else runCatching { com.optionslab.app.data.OrbArms.liquidityMinutesFor(u, now) }.getOrNull()
        }
        return LiquidityRules.BOOKS.filter { LiquidityRules.underlyingOf(it) in unds }.mapNotNull { arm ->
            val u = LiquidityRules.underlyingOf(arm)
            val o = ones[u] ?: if (kept) return@mapNotNull null else emptyList()
            LiquidityMap.read(o, u, LiquidityRules.minutesOf(arm), now)
        }
    }

    /**
     * Liquidity 15+5 armed (either book), or null when its book cannot be read: the book's armed flags only
     * ([com.optionslab.app.data.OrbArms.liquidityArmed]) - not the whole view (marks, shadows, rows), read each round.
     */
    private suspend fun armed(): Boolean? = runCatching {
        kotlinx.coroutines.withTimeoutOrNull(3_000) { com.optionslab.app.data.OrbArms.liquidityArmed() }
    }.getOrNull()

    /** Jarvis's answer to [q]: each book's levels, the arm's state and its entry hours. */
    suspend fun answer(q: LiquidityMap.Q): String {
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        if (q.underlyings.isEmpty()) return LiquidityMap.NOT_HERE
        return LiquidityMap.answer(q, reads(now, q.underlyings, kept = false), armed(), now)
    }

    /**
     * "Why no liquidity trade today?", "what is liquidity waiting for" ([com.optionslab.ira.LiquidityWhyNot]): from the arm's
     * own records of today - each book's switch, verdict and bars decided and today's trades ([com.optionslab.app.data.OrbArms.liquidityDay],
     * a short wait on the arms' lock, else said as not read), the decisions it kept in memory
     * ([com.optionslab.app.data.OrbArms.liquidityDecisions], no lock), the day's stop, and each book's levels (the minutes the
     * arm read this minute, else one read of them, bounded). Read only: nothing is armed, placed, closed or changed.
     */
    suspend fun whyNot(q: com.optionslab.ira.LiquidityWhyNot.Q): String {
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val day = now.toLocalDate()
        val tradingDay = runCatching { com.optionslab.app.data.Market.isTradingDay(day) }.getOrDefault(true)
        val arms = com.optionslab.app.data.OrbArms
        // The arms' lock may be held by their tick across a network read: a short wait, else the book is said as not read.
        val held = runCatching { kotlinx.coroutines.withTimeoutOrNull(3_000) { arms.liquidityDay(day) } }.getOrNull()
        val stopped = runCatching { kotlinx.coroutines.withTimeoutOrNull(3_000) { com.optionslab.app.data.Strategies.stoppedWhy() } }.getOrNull()
        val decisions = runCatching { arms.liquidityDecisions(day) }.getOrDefault(emptyList())
        val reads = if (!tradingDay) emptyList() else runCatching {
            keptElseFetched(q.underlyings, { reads(now, it, kept = true) }) {
                kotlinx.coroutines.withTimeoutOrNull(8_000) { reads(now, it, kept = false) }.orEmpty()
            }
        }.getOrDefault(emptyList())
        val facts = com.optionslab.ira.LiquidityWhyNot.Facts(
            now = now, tradingDay = tradingDay, armed = held?.first?.any { it.armed }, books = held?.first.orEmpty(), stopped = stopped,
            trades = held?.second.orEmpty().map { IraBots.tradeOf(it) }, decisions = decisions, since = arms.liquidityRecordSince, reads = reads)
        return com.optionslab.ira.LiquidityWhyNot.answer(q, facts)
    }

    /**
     * Each of [unds]' levels from the arm's own read of the minute ([kept]), and only for an index it has none of, one
     * read of its candles ([fetch]): one index missing never leaves the other unread, nor reads again the one kept.
     */
    internal suspend fun keptElseFetched(unds: List<String>, kept: suspend (List<String>) -> List<LiquidityMap.Read>,
                                         fetch: suspend (List<String>) -> List<LiquidityMap.Read>): List<LiquidityMap.Read> {
        val have = kept(unds)
        val missing = unds.filter { u -> have.none { it.underlying == u } }
        return if (missing.isEmpty()) have else have + fetch(missing)
    }

    /** What was told today (kept in memory; a restart may tell a level again). */
    private var told = LiquidityMap.Told()

    /**
     * The heads-up (each round of the words lane, market hours, [Automations.Auto.LIQUIDITY] on, Liquidity 15+5 armed):
     * the price within 15 points (FinNifty 8) of a level whose close-through would be the arm's entry with room - one line,
     * once per level a day, at most once in 10 minutes per index. A pop-up and a line in the chat; in the chat only while
     * saving battery or on short answers. Words only.
     */
    suspend fun watch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.LIQUIDITY) ||
            !com.optionslab.app.data.Market.isOpen()) return
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        if (now.toLocalTime().isBefore(LiquidityRules.FIRST_ENTRY) || now.toLocalTime().isAfter(LiquidityRules.LAST_ENTRY)) return
        if (armed() != true) return
        val rs = reads(now, LiquidityRules.UNDERLYINGS, kept = true)
        if (rs.isEmpty()) return
        val cues = synchronized(this) { val (c, t) = LiquidityMap.cues(rs, now, told); told = t; c }
        if (cues.isEmpty()) return
        val ctx = IraHub.appContext()
        val silent = IraTools.brief || runCatching { com.optionslab.app.work.Battery.saving(ctx) }.getOrDefault(false)
        for (c in cues) {
            IraHub.note(c.text, from = Automations.Auto.LIQUIDITY); Automations.acted(Automations.Auto.LIQUIDITY, c.text)
            if (!silent && ctx != null) runCatching { JarvisPopup.show(ctx, "${LiquidityMap.indexName(c.underlying)}: near a Liquidity level", c.text) }
        }
    }
}
