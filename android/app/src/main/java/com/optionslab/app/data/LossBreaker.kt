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

    /**
     * The day it tripped, kept in memory (review R1, 11 Oct: the entry door asks [trippedToday] for every bot entry and reads
     * nothing from the settings store on the order path): read again only when the vault or the loss limit's day changed
     * ([SecurePrefs.generationHint], [SecurePrefs.guardHint]); written through when it trips.
     */
    private class Kept(val gen: Int, val guard: Int, val day: String?)
    @Volatile private var kept: Kept? = null

    private fun trippedDay(): String? {
        val g = SecurePrefs.generationHint
        val q = SecurePrefs.guardHint
        kept?.let { if (it.gen == g && it.guard == q) return it.day }
        return SecurePrefs.getString(K_DAY).also { kept = Kept(g, q, it) }
    }

    /** Tripped today already. Memory only once read. */
    fun trippedToday(): Boolean = trippedDay() == Market.today().toString()

    suspend fun check(context: Context) {
        val today = Market.today().toString()
        if (trippedToday()) {
            // Tripped: keep asking until every running strategy has actually stopped (a stop that
            // could not be requested, e.g. contracts not loaded, is retried on the next pass).
            // The day's stop is put back too if it was lifted (plans only lower risk: the limit holds until tomorrow).
            if (Strategies.anyRunning() || !Strategies.stoppedToday())
                runCatching { Strategies.stopForToday(stopRunning = true, compromised = false, why = com.optionslab.ira.DayStop.Why.LOSS) }
            return
        }
        val s = AppSettings.load()
        // Both accounts are watched whatever the badge shows: a live position is real money in Paper mode too.
        // The paper day AFTER charges (Paper.Snapshot.dayPnl), never the before-charges figure the screens show: the limit
        // keeps the safer, net figure (Boss, 5 Oct). Zerodha's is its own m2m, as it always was.
        val paper = runCatching { Paper.snapshot() }.getOrNull()?.dayPnl
        // 16 Oct: waited 5 s at most - this runs before the arms' exits and entries in each pass, and one hung read of
        // Zerodha's positions held all of them for up to 105 s. Not read in time: this pass decides on paper's figure; the
        // next pass reads again.
        val live = if (Broker.loggedIn) Broker.within(Broker.READ_BUDGET_MS) { Broker.positionBook().m2m } else null
        // 20 Oct, enhancement round 1 (idea 4): the same read is today's figure for the account stop rule (memory; written later).
        if (live != null) runCatching { AccountStopHost.dayRead(live) }
        // The day lock on the same figures (08 Oct): an account up by the Bot settings amount makes no new automatic entry today.
        runCatching { DayLockGuard.update(context, paper, live) }
        val hit = when {
            live != null && s.guardDailyLoss > 0 && live <= -s.guardDailyLoss -> Triple("Live", live, s.guardDailyLoss)
            paper != null && s.guardPaperDailyLoss > 0 && paper <= -s.guardPaperDailyLoss -> Triple("Paper", paper, s.guardPaperDailyLoss)
            else -> null
        } ?: return
        // Kept as the loss limit's stop (never "by you"): "start all" and Start bot do not lift it today.
        val msg = runCatching { Strategies.stopForToday(stopRunning = true, compromised = false, why = com.optionslab.ira.DayStop.Why.LOSS) }.getOrElse { it.message ?: "" }
        SecurePrefs.put(K_DAY, today)
        kept = Kept(SecurePrefs.generationHint, SecurePrefs.guardHint, today)
        val text = "Today's %s P&L Rs %,.0f reached the Rs %,.0f daily loss limit. The bot sold what it held and stopped for today. %s"
            .format(hit.first, hit.second, hit.third, msg)
        // Also the in-app banner (Notifier.post drops it in): one call, one banner.
        Notifier.post(context, 2040, Notifier.RISK, "Daily loss limit reached", text, "almanac", setting = "risk.guards")
    }
}
