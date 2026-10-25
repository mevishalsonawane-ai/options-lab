package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.work.Notifier

/**
 * One daily loss limit over everything the app trades by itself. The account guard already
 * refuses new entries once the day's P&L is past the Bot settings limit; this also sells
 * what the bots hold: when today's P&L (the paper account, or Zerodha in Live) reaches
 * minus the limit, the bot is stopped for the day with its running positions closed,
 * so ORB, the Pine auto-traders and every running strategy sell and stand still until
 * tomorrow. Positions you opened by hand are left to you.
 */
object LossBreaker {
    private const val K_DAY = "breaker.day"
    /** 21 Oct: whose limit made today's [K_DAY] stop ("2026-10-21|paper" or "|live"; [com.optionslab.ira.SegmentLock.Trip]). */
    private const val K_KIND = "breaker.day.kind"
    /** 21 Oct: the MCX paper arms' own loss stop today ([com.optionslab.engine.risk.DayLock.encode]'s shape, a negative P&L). */
    private const val K_MCX = "breaker.mcx.day"
    /** 21 Oct: the whole paper day past the paper limit while commodities keep their own (the live door still refuses on it). */
    private const val K_WHOLE = "breaker.paper.whole"
    /** 21 Oct: the day Boss (or the tile) stopped the bot, kept apart so a loss stop made first never hides it from MCX. */
    internal const val K_BOSS = "breaker.bossstop"

    /**
     * The day it tripped, kept in memory (review R1, 11 Oct: the entry door asks [trippedToday] for every bot entry and reads
     * nothing from the settings store on the order path): read again only when the vault or the loss limit's day changed
     * ([SecurePrefs.generationHint], [SecurePrefs.guardHint]); written through when it trips. 21 Oct: with the segment keys.
     */
    private class Kept(val gen: Int, val guard: Int, val day: String?, val kind: String? = null, val mcx: String? = null,
                       val whole: String? = null, val boss: String? = null)
    @Volatile private var kept: Kept? = null

    private fun kept(): Kept {
        val g = SecurePrefs.generationHint
        val q = SecurePrefs.guardHint
        kept?.let { if (it.gen == g && it.guard == q) return it }
        return Kept(g, q, SecurePrefs.getString(K_DAY), SecurePrefs.getString(K_KIND), SecurePrefs.getString(K_MCX),
            SecurePrefs.getString(K_WHOLE), SecurePrefs.getString(K_BOSS)).also { kept = it }
    }

    private fun trippedDay(): String? = kept().day

    /** Tripped today already (the NSE side, or everything with the split off; Zerodha's limit always). Memory only once read. */
    fun trippedToday(): Boolean = trippedDay() == Market.today().toString()

    /** Whose limit tripped today's stop, when known (21 Oct). Memory only once read. */
    fun tripToday(): com.optionslab.ira.SegmentLock.Trip? = com.optionslab.ira.SegmentLock.decodeTrip(kept().kind, Market.today())

    /** The MCX paper arms' own loss stop today (21 Oct): when, at what P&L, against which limit; null when none. Memory only. */
    fun mcxReached(): com.optionslab.engine.risk.DayLock.Reached? =
        com.optionslab.engine.risk.DayLock.decode(kept().mcx)?.takeIf { it.day == Market.today() }

    /** The MCX paper arms' own loss stop holds today. Memory only once read. */
    fun mcxTrippedToday(): Boolean = mcxReached() != null

    /** Boss (or the tile) stopped the bot today (21 Oct: read by the MCX arms beside the day stop). Memory only once read. */
    fun bossStoppedToday(): Boolean = kept().boss == Market.today().toString()

    /**
     * The live entry door's loss stop (21 Oct): never weaker than before the split - the NSE side's or Zerodha's stop, the MCX
     * paper stop, or the whole paper day past the paper limit. Memory only once read.
     */
    fun liveDoorTripped(): Boolean = com.optionslab.ira.SegmentLock.liveDoorTripped(trippedToday(), mcxTrippedToday(),
        kept().whole == Market.today().toString())

    /**
     * Whether the MCX paper arms make no new entry today and sell what they hold (21 Oct, [com.optionslab.ira.SegmentLock.mcxStopped]):
     * Boss's stop, the tile, Zerodha's loss limit and the commodities' own limit stop them; the NSE paper limit does not while
     * the split is on. [stoppedWhy]: the bot's day stop as [Strategies.stoppedWhy] read it.
     */
    fun mcxStopped(stoppedWhy: com.optionslab.ira.DayStop.Why?): Boolean = com.optionslab.ira.SegmentLock.mcxStopped(
        DayLockGuard.splitOn(), stoppedWhy, bossStoppedToday(), trippedToday(), tripToday(), mcxTrippedToday())

    /**
     * 25 Oct (app audit): whether the MCX paper arms SELL what they hold for today's stop ([com.optionslab.ira.SegmentLock.mcxExitStop]):
     * only a stop known to apply to MCX; an unknown split setting or an unknown loss account never sells. Memory only once read.
     */
    fun mcxExitStop(stoppedWhy: com.optionslab.ira.DayStop.Why?): Boolean = com.optionslab.ira.SegmentLock.mcxExitStop(
        DayLockGuard.splitKnown(), stoppedWhy, bossStoppedToday(), trippedToday(), tripToday(), mcxTrippedToday())

    /** Boss's or the tile's stop for today, kept (21 Oct); [on] false: lifted by "start all" or Start bot. */
    internal fun noteBossStop(on: Boolean) {
        val today = Market.today().toString()
        if (on) { if (kept().boss != today) SecurePrefs.put(K_BOSS, today) }
        else if (kept().boss != null) SecurePrefs.put(K_BOSS, "")
        kept = null
        runCatching { kept() }      // warmed here (Boss's stop or start, off the order path): the door reads memory only
    }

    /** The paper day as the limit reads it: after charges, with each position row's and trade's exchange (21 Oct). */
    internal data class PaperDay(val total: Double, val rows: List<Pair<String, Double>>, val charges: List<Pair<String, Double>>)

    /** TEST SEAM: the paper day instead of the paper book's (null in the app). */
    @Volatile internal var testPaperDay: (() -> PaperDay)? = null

    /** TEST SEAM: the kept copy forgotten, as a new process starts (the vault keeps the day's stops). */
    internal fun forgetForTest() { kept = null }

    /**
     * One look at the day's P&L (the market watch's pass, off the order path). The kept copy is read again at its end, so the
     * entry door's next look reads memory only after any stop written here (as the day lock's pass does).
     */
    suspend fun check(context: Context) {
        try { checkOnce(context) } finally { runCatching { kept() } }
    }

    private suspend fun checkOnce(context: Context) {
        val today = Market.today().toString()
        val s = AppSettings.load()
        val split = s.mcxOwnLimits
        if (trippedToday()) {
            // Tripped: keep asking until every running strategy has actually stopped (a stop that
            // could not be requested, e.g. contracts not loaded, is retried on the next pass).
            // The day's stop is put back too if it was lifted (plans only lower risk: the limit holds until tomorrow).
            if (Strategies.anyRunning() || !Strategies.stoppedToday())
                runCatching { Strategies.stopForToday(stopRunning = true, compromised = false, why = com.optionslab.ira.DayStop.Why.LOSS) }
            // 21 Oct: with commodities on their own limits, the MCX side is still watched, and a later Zerodha loss still stops them.
            if (!split) return
        }
        // Both accounts are watched whatever the badge shows: a live position is real money in Paper mode too.
        // The paper day AFTER charges (Paper.Snapshot.dayPnl), never the before-charges figure the screens show: the limit
        // keeps the safer, net figure (Boss, 5 Oct). Zerodha's is its own m2m, as it always was.
        val day = testPaperDay?.invoke() ?: runCatching { Paper.snapshot() }.getOrNull()?.let { snap ->
            PaperDay(snap.dayPnl, snap.positions.positions.map { it.exchange to it.totalPnlToday }, snap.trades.map { it.exchange to it.charges })
        }
        val paper = day?.total
        // 21 Oct: the MCX positions' day after charges, apart (memory: the snapshot just read).
        val parts = if (!split || day == null) null else runCatching { com.optionslab.ira.SegmentLock.split(day.total, day.rows, day.charges) }.getOrNull()
        // 16 Oct: waited 5 s at most - this runs before the arms' exits and entries in each pass, and one hung read of
        // Zerodha's positions held all of them for up to 105 s. Not read in time: this pass decides on paper's figure; the
        // next pass reads again.
        val book = if (Broker.loggedIn) Broker.within(Broker.READ_BUDGET_MS) { Broker.positionBook() } else null
        val live = book?.m2m
        // 21 Oct (merge review): a live position still open (Paper mode or real orders off included) keeps the one shared paper
        // limit, so its loss stop still sells it as before; a book not read in time counts as open (the safer side).
        val liveOpen = if (book != null) book.net.any { it.open } else Broker.loggedIn
        // 20 Oct, enhancement round 1 (idea 4): the same read is today's figure for the account stop rule (memory; written later).
        // 20 Oct, enhancement round 2 (idea 6): less today's charges (Zerodha's exact figure when kept, else the estimate from the
        // day's kept trades; both from memory or the vault, never a network read here), so it reads the arms' net figure.
        if (live != null) runCatching {
            val d = Market.today()
            val charges = runCatching { TradeBook.exactChargesOn(d) ?: TradeBook.liveChargesOn(d) }.getOrNull()
            AccountStopHost.dayRead(live, charges = charges)
        }
        // The day lock on the same figures (08 Oct): an account up by the Bot settings amount makes no new automatic entry today.
        // 21 Oct: with the split on, the paper lock reads the NSE paper day and the MCX lock the MCX one.
        val (lockPaper, lockMcx) = com.optionslab.ira.SegmentLock.lockFigures(split, paper, parts)
        runCatching { DayLockGuard.update(context, lockPaper, live, lockMcx) }
        // 21 Oct (review): the loss limits split only while nothing can trade live; otherwise the one paper figure, as before.
        val liveActive = liveOpen || (s.live && s.allowRealOrders) || runCatching { Strategies.anyLiveRunning() }.getOrDefault(true)
        val lossSplit = com.optionslab.ira.SegmentLock.lossSplit(split, liveActive)
        val l = com.optionslab.ira.SegmentLock.losses(lossSplit, live, s.guardDailyLoss, paper, s.guardPaperDailyLoss, parts, s.mcxPaperDailyLoss)
        if (l.wholePaper && kept().whole != today) { SecurePrefs.put(K_WHOLE, today); kept = null }
        l.mcx?.let { if (!mcxTrippedToday()) tripMcx(context, it) }
        val hit = l.nse ?: return
        if (trippedToday()) {
            // Already stopped by the NSE paper limit: a Zerodha loss (or the one paper figure, once anything trades live) now stops
            // the commodities too - never lifted by either side.
            if (com.optionslab.ira.SegmentLock.upgrade(tripToday(), hit.trip)) {
                SecurePrefs.put(K_KIND, com.optionslab.ira.SegmentLock.encodeTrip(Market.today(), hit.trip)); kept = null
                runCatching { Diag.record("risk", "${hit.who}'s daily loss limit reached after the NSE paper one: the MCX paper arms stop too") }
            }
            return
        }
        // Whose limit it was and the day, written FIRST (review): the arms on other lanes read the stop at once, and the MCX arms
        // never take an NSE paper stop for one that stops them (a day read without its kind - an older build's - stops both).
        SecurePrefs.put(K_KIND, com.optionslab.ira.SegmentLock.encodeTrip(Market.today(), hit.trip))
        SecurePrefs.put(K_DAY, today)
        kept = null
        // Kept as the loss limit's stop (never "by you"): "start all" and Start bot do not lift it today.
        val msg = runCatching { Strategies.stopForToday(stopRunning = true, compromised = false, why = com.optionslab.ira.DayStop.Why.LOSS) }.getOrElse { it.message ?: "" }
        val text = "Today's %s P&L Rs %,.0f reached the Rs %,.0f daily loss limit. The bot sold what it held and stopped for today. %s"
            .format(hit.who, hit.pnl, hit.limit, msg) +
            if (hit.trip == com.optionslab.ira.SegmentLock.Trip.PAPER) " The MCX paper arms keep their own limit." else ""
        // Also the in-app banner (Notifier.post drops it in): one call, one banner.
        Notifier.post(context, 2040, Notifier.RISK, "Daily loss limit reached", text, "almanac", setting = "risk.guards")
    }

    /** The commodities' own loss limit reached (21 Oct): kept for the day; the MCX arms sell what they hold on their next pass. */
    private fun tripMcx(context: Context, hit: com.optionslab.ira.SegmentLock.Hit) {
        val now = Market.now()
        val r = com.optionslab.ira.SegmentLock.lossReached(now.toLocalDate(), now.toLocalTime(), hit.pnl, hit.limit)
        SecurePrefs.put(K_MCX, com.optionslab.engine.risk.DayLock.encode(r))
        kept = null
        val line = com.optionslab.ira.SegmentLock.mcxLossLine(r)
        runCatching { Diag.record("risk", line + " (MCX paper day Rs %,.0f)".format(java.util.Locale.ENGLISH, hit.pnl)) }
        runCatching {
            Notifier.post(context, 2047, Notifier.RISK, "Commodities daily loss limit reached",
                "$line. NSE trading is not affected. Change it in Bot settings.", "almanac", setting = "risk.guards")
        }
    }
}
