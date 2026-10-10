package com.optionslab.app.ira

import com.optionslab.engine.orb.HeroRules

/**
 * Jarvis on the expiry-day Hero arm's day ([com.optionslab.ira.HeroDay]): "what did hero do today", "why no hero trade", "is
 * today a hero day", "when is the next hero day" - answered from the arm's own records. Read only: nothing here arms,
 * places, closes or changes anything. Not in IraGoldAlgo (no Hero arm there).
 */
internal object IraHero {
    /** One Hero position as [com.optionslab.ira.HeroDay] reads it; [mark] its last price while open. */
    private fun tradeOf(p: com.optionslab.app.data.OrbArms.Position, mark: Double?): com.optionslab.ira.HeroDay.Trade =
        com.optionslab.ira.HeroDay.Trade(p.symbol, p.right == "CE", p.qty, p.entry, p.entryTime, p.signalBar, p.sold, p.soldAt, p.soldTime,
            p.exit, p.exitTime, p.why, if (p.exit != null) (p.grossPnl ?: 0.0) - p.charges else null, p.charges,
            if (p.exit == null) mark else null, p.seen)

    /**
     * NIFTY's listed expiries on or after [today], from the instrument master already on the phone (the list the arm decides
     * by; never a download), else from the calendar's list. Empty: no list on the phone.
     */
    private fun expiries(today: java.time.LocalDate): List<java.time.LocalDate> = runCatching {
        com.optionslab.app.data.Market.cachedContracts()?.second.orEmpty().filter { it.underlying == HeroRules.UNDERLYING && !it.expiry.isBefore(today) }
            .map { it.expiry }.distinct().sorted()
    }.getOrDefault(emptyList()).ifEmpty {
        runCatching { com.optionslab.app.data.Market.upcomingExpiries(HeroRules.UNDERLYING) }.getOrDefault(emptyList())
    }

    /**
     * "What did hero do today", "why no hero trade", "is today a hero day", "when is the next hero day": its switch, status
     * and today's trades ([com.optionslab.app.data.OrbArms.view], a short wait on the arms' lock, else said as not read), the
     * decisions it kept in memory ([com.optionslab.app.data.OrbArms.heroDecisions], no lock), NIFTY's expiries, and its closed
     * paper trades ([com.optionslab.app.data.OrbArms.closedPaper]) against its research. Read only.
     */
    suspend fun day(q: com.optionslab.ira.HeroDay.Q): String {
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val today = now.toLocalDate()
        val tradingDay = runCatching { com.optionslab.app.data.Market.isTradingDay(today) }.getOrDefault(true)
        val arms = com.optionslab.app.data.OrbArms
        // The arms' lock may be held by their pass across a network read: a short wait, else the switch is said as not read.
        val hero = runCatching { kotlinx.coroutines.withTimeoutOrNull(3_000) { arms.view() } }.getOrNull()
            ?.arms?.firstOrNull { it.arm.source == HeroRules.ARM.source }
        val closed = runCatching { kotlinx.coroutines.withTimeoutOrNull(3_000) { arms.closedPaper() } }.getOrNull()
            ?.filter { it.arm == HeroRules.ARM.source && !it.live }
        val record = closed?.let { c -> runCatching { com.optionslab.ira.ForwardCheck.check(com.optionslab.ira.ForwardCheck.HERO,
            com.optionslab.app.data.ForwardRecords.hero(c)) }.getOrNull() }
        val facts = com.optionslab.ira.HeroDay.Facts(
            now = now, tradingDay = tradingDay, expiries = expiries(today),
            armed = hero?.armed, status = hero?.status,
            decisions = runCatching { arms.heroDecisions(today) }.getOrDefault(emptyList()), since = arms.liquidityRecordSince,
            today = hero?.today.orEmpty().filter { !it.live }.map { tradeOf(it, hero?.mark) },
            record = record, allTime = closed?.let { c -> c.size to c.sumOf { (it.grossPnl ?: 0.0) - it.charges } })
        return com.optionslab.ira.HeroDay.answer(q, facts)
    }
}
