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

    private fun key(live: Boolean) = if (live) K_LIVE else K_PAPER
    private fun account(live: Boolean) = if (live) "Zerodha" else "paper"

    /**
     * Both accounts' lock days and the lock's amount, kept in memory (review R1, 11 Oct: the entry door asks [refusal] for
     * every bot entry and reads nothing from the settings store on the order path): read again only when the vault, the lock
     * days or the amount changed ([SecurePrefs.generationHint], [SecurePrefs.guardHint]); refreshed by [update].
     */
    private class Kept(val gen: Int, val guard: Int, val paper: DayLock.Reached?, val live: DayLock.Reached?, val limit: Double)
    @Volatile private var kept: Kept? = null

    private fun kept(): Kept {
        val g = SecurePrefs.generationHint
        val q = SecurePrefs.guardHint
        kept?.let { if (it.gen == g && it.guard == q) return it }
        return Kept(g, q, DayLock.decode(SecurePrefs.getString(K_PAPER)), DayLock.decode(SecurePrefs.getString(K_LIVE)),
            AppSettings.load().dayLock).also { kept = it }
    }

    /** The day [live]'s account reached its lock, when it did today and the lock is on; null otherwise. Memory only once read. */
    fun active(live: Boolean): DayLock.Reached? = runCatching {
        val k = kept()
        (if (live) k.live else k.paper)?.takeIf { DayLock.active(it, Market.today(), k.limit) }
    }.getOrNull()

    /**
     * Why an automatic entry may not go now: the lock of the account it goes to ([live]: Zerodha, false: paper; null: not
     * known - either account's lock refuses it); null when it may.
     */
    fun refusal(live: Boolean?): String? {
        val accounts = if (live == null) listOf(false, true) else listOf(live)
        for (a in accounts) active(a)?.let { return DayLock.refusal(it, account(a)) }
        return null
    }

    /** Home's and Bot settings' line: "Day lock (paper): reached +Rs 8,000 at 14:11, no new entries"; null while none is reached. */
    fun statusLine(): String? = listOf(false, true).mapNotNull { a -> active(a)?.let { DayLock.line(it, account(a)) } }
        .takeIf { it.isNotEmpty() }?.joinToString("\n")

    /**
     * One look at both accounts' day P&L ([paper]: the paper account's after charges; [live]: Zerodha's, null when not read):
     * an account that reaches its lock now is locked for the day, said once (a notice and a diagnostics line).
     */
    fun update(context: Context, paper: Double?, live: Double?) {
        val limit = AppSettings.load().dayLock
        val now = Market.now()
        for ((isLive, pnl) in listOf(false to paper, true to live)) {
            val prev = DayLock.decode(SecurePrefs.getString(key(isLive)))
            val next = DayLock.next(prev, now.toLocalDate(), now.toLocalTime(), limit, pnl) ?: continue
            if (next == prev) continue
            SecurePrefs.put(key(isLive), DayLock.encode(next))
            kept = null      // read again now, off the order path (below), never by the door
            val line = DayLock.line(next, account(isLive))
            runCatching { Diag.record("risk", line + " (day P&L Rs %,.0f)".format(java.util.Locale.ENGLISH, next.pnl)) }
            runCatching {
                Notifier.post(context, 2041, Notifier.RISK, "Day lock reached",
                    "$line. Open positions keep their own exits; your own orders are not affected. Change it in Bot settings.",
                    "strategy", setting = "risk.guards")
            }
        }
        // Warm the door's kept copy here (the loss limit's pass), so the next entry reads memory only.
        runCatching { kept() }
    }
}
