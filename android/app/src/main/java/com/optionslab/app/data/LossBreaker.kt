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

    /** Tripped today already. */
    fun trippedToday(): Boolean = SecurePrefs.getString(K_DAY) == Market.today().toString()

    suspend fun check(context: Context) {
        if (trippedToday()) return
        val s = AppSettings.load()
        val live = s.live && s.allowRealOrders
        val limit = if (live) s.guardDailyLoss else s.guardPaperDailyLoss
        if (!(limit > 0)) return
        val dayPnl = if (live) {
            if (!Broker.loggedIn) return
            runCatching { Broker.positionBook().m2m }.getOrNull() ?: return
        } else {
            val f = runCatching { Paper.snapshot().funds }.getOrNull() ?: return
            f.todayRealizedPnl + f.m2mUnrealized
        }
        if (dayPnl > -limit) return
        SecurePrefs.put(K_DAY, Market.today().toString())
        val msg = runCatching { Strategies.stopForToday(stopRunning = true, compromised = false) }.getOrElse { it.message ?: "" }
        val text = "Today's P&L Rs %,.0f reached the Rs %,.0f daily loss limit (%s). The bot sold what it held and stopped for today. %s"
            .format(dayPnl, limit, if (live) "Live" else "Paper", msg)
        Notifier.post(context, 2040, Notifier.RISK, "Daily loss limit reached", text, "strategy")
        com.optionslab.app.work.Alerts.error(text, "Daily loss limit")
    }
}
