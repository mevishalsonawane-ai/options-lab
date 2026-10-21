package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.work.Notifier
import com.optionslab.engine.risk.DayLock

/**
 * The account day lock on the phone (08 Oct, research/PROFIT_LOCK_8OCT.md fix 5; the rule is [DayLock]): once the paper
 * account's day P&L after charges - or, separately, Zerodha's own day P&L - reaches the Bot settings amount (+Rs 8,000 at
 * first; 0 = off), no NEW automatic entry is made in that account for the rest of the day: every ORB arm, Liquidity 15+5,
 * the Pine scripts, Solo, Jarvis's trades and the scheduled strategies ([AutoExposure.check] asks [refusal]). Positions
 * already open keep their own exits; nothing is sold here, and Boss's own orders are never refused by it.
 *
 * Checked beside the daily loss limit ([LossBreaker.check], which reads both accounts' day P&L once a pass). The day it was
 * reached is kept under "breaker." (this phone's alone, never in a backup), so a restart keeps the lock for the day.
 */
object DayLockGuard {
    private const val K_PAPER = "breaker.daylock.paper"
    private const val K_LIVE = "breaker.daylock.live"
    /** 21 Oct (Boss: "give commodities their own day lock"): the MCX paper arms' own lock, on the MCX paper day alone. */
    private const val K_MCX = "breaker.daylock.mcx"

    private fun account(live: Boolean) = if (live) "Zerodha" else "paper"

    /**
     * Both accounts' lock days and the lock's amount, kept in memory (review R1, 11 Oct: the entry door asks [refusal] for
     * every bot entry and reads nothing from the settings store on the order path): read again only when the vault, the lock
     * days or the amount changed ([SecurePrefs.generationHint], [SecurePrefs.guardHint]); refreshed by [update].
     */
    private class Kept(val gen: Int, val guard: Int, val paper: DayLock.Reached?, val live: DayLock.Reached?, val limit: Double,
                       val mcx: DayLock.Reached? = null, val mcxLimit: Double = 0.0, val split: Boolean = false)
    @Volatile private var kept: Kept? = null

    /** TEST SEAM: the kept copy forgotten, as a new process starts (21 Oct). */
    internal fun forgetForTest() { kept = null }

    private fun kept(): Kept {
        val g = SecurePrefs.generationHint
        val q = SecurePrefs.guardHint
        kept?.let { if (it.gen == g && it.guard == q) return it }
        val s = AppSettings.load()
        return Kept(g, q, DayLock.decode(SecurePrefs.getString(K_PAPER)), DayLock.decode(SecurePrefs.getString(K_LIVE)),
            s.dayLock, DayLock.decode(SecurePrefs.getString(K_MCX)), s.mcxDayLock, s.mcxOwnLimits).also { kept = it }
    }

    /** The day [live]'s account reached its lock, when it did today and the lock is on; null otherwise. Memory only once read. */
    fun active(live: Boolean): DayLock.Reached? = runCatching {
        val k = kept()
        (if (live) k.live else k.paper)?.takeIf { DayLock.active(it, Market.today(), k.limit) }
    }.getOrNull()

    /** Whether commodities keep their own paper lock and loss limit (21 Oct; memory only once read). False when unreadable. */
    fun splitOn(): Boolean = runCatching { kept().split }.getOrDefault(false)

    /**
     * Commodities on their own paper limits [on] or back on the shared paper figure (21 Oct): the Autopilot timeline's Undo
     * (Bot settings' switch writes the setting itself). Paper only. The words said back.
     */
    fun setSplit(on: Boolean): String {
        val was = AppSettings.load()
        if (was.mcxOwnLimits != on) AppSettings.saveChanged(was, was.copy(mcxOwnLimits = on))
        kept = null
        runCatching { kept() }      // warmed here, off the order path
        return if (on) "Commodities keep their own paper day lock and loss limit." else
            "Commodities share the paper day lock and loss limit with NSE again (one paper figure)."
    }

    /** The day the MCX paper arms reached their own lock, when they did today, the split is on and the lock is on; null otherwise. */
    fun mcxActive(): DayLock.Reached? = runCatching {
        val k = kept()
        // Reached today, it holds whatever the switch says since (review: switching the split off never lifts it).
        k.mcx?.takeIf { DayLock.active(it, Market.today(), k.mcxLimit) }
    }.getOrNull()

    /**
     * Why an MCX paper arm's new entry may not go now: with the split on, the commodities' own lock only (NSE's never stops
     * it); with it off, the shared paper lock as before. Null when it may. Memory only once read.
     */
    fun mcxRefusal(): String? {
        val own = mcxActive()?.let { com.optionslab.ira.SegmentLock.mcxLockRefusal(it) }
        // The split off: the shared paper lock as before, and a commodities lock already reached today still holds.
        return if (!splitOn()) refusal(false) ?: own else own
    }

    /**
     * Why an automatic entry may not go now: the lock of the account it goes to ([live]: Zerodha, false: paper; null: not
     * known - either account's lock refuses it); null when it may.
     */
    fun refusal(live: Boolean?): String? {
        val accounts = if (live == null) listOf(false, true) else listOf(live)
        for (a in accounts) active(a)?.let { return DayLock.refusal(it, account(a)) }
        return null
    }

    /**
     * Home's, Bot settings' and the diagnostics' lines, one per side reached today (21 Oct): "Day lock reached (paper NSE) +Rs
     * 8,000 at 11:00: no new NSE entries today", "Commodities day lock reached +Rs 8,000 at 18:05: no new MCX entries today",
     * and the commodities' own loss stop; null while none is reached.
     */
    fun statusLine(): String? {
        val seg = com.optionslab.ira.SegmentLock
        val split = splitOn()
        val lines = ArrayList<String>()
        active(false)?.let { lines += seg.lockLine(it, if (split) com.optionslab.ira.SegmentLock.Side.PAPER_NSE else com.optionslab.ira.SegmentLock.Side.PAPER) }
        active(true)?.let { lines += seg.lockLine(it, com.optionslab.ira.SegmentLock.Side.ZERODHA) }
        mcxActive()?.let { lines += seg.lockLine(it, com.optionslab.ira.SegmentLock.Side.MCX) }
        runCatching { LossBreaker.mcxReached() }.getOrNull()?.let { lines += seg.mcxLossLine(it) }
        if (split && runCatching { LossBreaker.trippedToday() && LossBreaker.tripToday() == com.optionslab.ira.SegmentLock.Trip.PAPER }.getOrDefault(false))
            lines += seg.NSE_LOSS_ONLY
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * The MCX group's own line on Home (21 Oct): its own day lock or loss stop while commodities keep their own, else the shared
     * paper lock that also stops them; null while nothing stops their new entries.
     */
    fun mcxLine(): String? {
        val seg = com.optionslab.ira.SegmentLock
        runCatching { LossBreaker.mcxReached() }.getOrNull()?.let { return seg.mcxLossLine(it) }
        mcxActive()?.let { return seg.lockLine(it, com.optionslab.ira.SegmentLock.Side.MCX) }
        if (splitOn()) return null
        return active(false)?.let { seg.lockLine(it, com.optionslab.ira.SegmentLock.Side.PAPER) + " (shared with MCX)" }
    }

    /** The diagnostics' line: what is reached today, else how the paper locks are set. Its own function (the report is long). */
    fun diagLine(): String = statusLine()?.replace("\n", " · ") ?: runCatching {
        val s = AppSettings.load()
        "Day lock: none reached today (${DayLock.describe(s.dayLock)}). " + com.optionslab.ira.SegmentLock.idleLine(s.mcxOwnLimits, s.mcxDayLock, s.mcxPaperDailyLoss)
    }.getOrElse { "Day lock: could not read" }

    /**
     * One look at both accounts' day P&L ([paper]: the paper account's after charges; [live]: Zerodha's, null when not read):
     * an account that reaches its lock now is locked for the day, said once (a notice and a diagnostics line).
     */
    fun update(context: Context, paper: Double?, live: Double?, mcx: Double? = null) {
        val s = AppSettings.load()
        val now = Market.now()
        val seg = com.optionslab.ira.SegmentLock
        // [mcx]: the MCX paper day alone, given only while commodities keep their own lock (21 Oct); [paper] is then NSE's alone.
        val sides = listOf(Triple(K_PAPER, paper, s.dayLock), Triple(K_LIVE, live, s.dayLock)) +
            (if (s.mcxOwnLimits && mcx != null) listOf(Triple(K_MCX, mcx, s.mcxDayLock)) else emptyList())
        for ((k, pnl, limit) in sides) {
            val prev = DayLock.decode(SecurePrefs.getString(k))
            val next = DayLock.next(prev, now.toLocalDate(), now.toLocalTime(), limit, pnl) ?: continue
            if (next == prev) continue
            SecurePrefs.put(k, DayLock.encode(next))
            kept = null      // read again now, off the order path (below), never by the door
            val line = when (k) {
                K_MCX -> seg.lockLine(next, com.optionslab.ira.SegmentLock.Side.MCX)
                K_LIVE -> seg.lockLine(next, com.optionslab.ira.SegmentLock.Side.ZERODHA)
                else -> seg.lockLine(next, if (s.mcxOwnLimits) com.optionslab.ira.SegmentLock.Side.PAPER_NSE else com.optionslab.ira.SegmentLock.Side.PAPER)
            }
            runCatching { Diag.record("risk", line + " (day P&L Rs %,.0f)".format(java.util.Locale.ENGLISH, next.pnl)) }
            runCatching {
                Notifier.post(context, if (k == K_MCX) 2046 else 2041, Notifier.RISK, if (k == K_MCX) "Commodities day lock reached" else "Day lock reached",
                    "$line. Open positions keep their own exits; your own orders are not affected. Change it in Bot settings.",
                    "strategy", setting = "risk.guards")
            }
        }
        // Warm the door's kept copy here (the loss limit's pass), so the next entry reads memory only.
        runCatching { kept() }
    }
}
