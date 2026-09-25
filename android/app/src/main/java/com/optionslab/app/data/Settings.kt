package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.ExpiryPut
import com.optionslab.engine.Sizing
import com.optionslab.engine.hhmm

/**
 * Every tunable the PC exposes as a CLI flag, plus the phone's own
 * (notifications, locking, appearance). Defaults are the PC's defaults.
 */
data class AppSettings(
    // expiry-put / regime / live-ticket flags
    val otmPct: Double = ExpiryPut.DEFAULT_OTM_PCT,
    val entry: String = "11:00",
    val regime: String = "quoted",
    val datedLot: Boolean = false,
    val pinnedLot: Int = 65,
    val wingPct: Double? = null,
    val capital: Double = 400_000.0,
    val survive: Double = Sizing.DEFAULT_SURVIVE_MOVE_PCT,
    val holdout: Int = ExpiryPut.DEFAULT_HOLDOUT_SESSIONS,
    val healthLast: Int = 30,
    val ticketUnderlying: String = "NIFTY",
    val ticketLots: Int = 1,
    val includeDeviceSessions: Boolean = true,
    // schedules and notifications
    val entryReminder: Boolean = true,
    val autoTicket: Boolean = false,
    val autoSettle: Boolean = true,
    val nightlyHarvest: Boolean = true,
    val liveWatch: Boolean = true,
    val riskAlertPct: Double = 0.0025,
    val healthAlerts: Boolean = true,
    /** Notifications beyond buy / sell / approval (risk, alarms, P&L, reminders, health). Off by default. */
    val otherAlerts: Boolean = false,
    // account P&L alerts from the background watch (rupees; 0 = off)
    val pnlLossAlert: Double = 0.0,
    val pnlProfitAlert: Double = 0.0,
    // home-screen widget: index levels always; the account P&L only if the owner opts in
    val widgetPnl: Boolean = false,
    // security
    val biometric: Boolean = false,
    val allowWeakFace: Boolean = false,
    /** Lock after this long without use (in the app or away from it). */
    val idleSeconds: Int = 300,
    val refuseCompromised: Boolean = false,
    val wipeOnExhaustion: Boolean = false,
    val hideAmountsOnLockScreen: Boolean = true,
    // "live": every live figure comes from Zerodha and nothing else.
    // "sandbox": public Upstox data and paper tickets. Analysis is the same in both.
    val mode: String = "sandbox",
    // Zerodha: real orders are OFF until the owner turns them on
    val allowRealOrders: Boolean = false,
    val orderProduct: String = "NRML",
    val maxOrdersPerDay: Int = 4,
    val maxLotsPerOrder: Int = 2,
    val maxOrderValue: Double = 500_000.0,
    // Account-wide guard (every order, paper and live, strategy and manual; exits only stop at the kill switch)
    val guardKill: Boolean = false,
    val guardDailyLoss: Double = 2_000.0,
    val guardDrawdownPct: Double = 10.0,
    val guardMaxOpen: Int = 3,
    val guardMaxTrades: Int = 10,
    val guardMaxValue: Double = 500_000.0,
    val guardMaxLots: Int = 2,
    /** Minute of day; -1 = no cutoff. */
    val guardCutoff: Int = 14 * 60 + 30,
    val guardNakedShort: Boolean = true,
    val prepareRealOrder: Boolean = true,
    // appearance
    val theme: String = "system",          // system | light | dark
    val reduceMotion: Boolean = false,
) {
    val entryMinute: Int get() = runCatching { hhmm(entry) }.getOrDefault(ExpiryPut.DEFAULT_ENTRY)

    val live: Boolean get() = mode == "live"

    fun limits() = com.optionslab.engine.Kite.Limits(maxOrdersPerDay, maxLotsPerOrder, maxOrderValue)
    fun guardLimits() = com.optionslab.engine.risk.AccountGuard.Limits(guardKill, guardDailyLoss, guardDrawdownPct, guardMaxOpen,
        guardMaxTrades, guardMaxValue, guardMaxLots, guardCutoff.takeIf { it >= 0 }, guardNakedShort)

    fun params(): ExpiryPut.Params = ExpiryPut.Params(
        otmPct = otmPct, entryMinute = entryMinute, regime = regime, wingPct = wingPct,
        lot = if (datedLot) ExpiryPut.LotChoice.Dated else ExpiryPut.LotChoice.Pinned(pinnedLot),
    )

    companion object {
        fun load(): AppSettings {
            val d = AppSettings()
            val p = SecurePrefs
            return AppSettings(
                otmPct = p.getDouble("s.otm", d.otmPct),
                entry = p.getString("s.entry", d.entry)!!,
                regime = p.getString("s.regime", d.regime)!!,
                datedLot = p.getBoolean("s.dated", d.datedLot),
                pinnedLot = p.getInt("s.lot", d.pinnedLot),
                wingPct = p.getDouble("s.wing", -1.0).takeIf { it > 0 },
                capital = p.getDouble("s.capital", d.capital),
                survive = p.getDouble("s.survive", d.survive),
                holdout = p.getInt("s.holdout", d.holdout),
                healthLast = p.getInt("s.last", d.healthLast),
                ticketUnderlying = p.getString("s.tkU", d.ticketUnderlying)!!,
                ticketLots = p.getInt("s.tkLots", d.ticketLots),
                includeDeviceSessions = p.getBoolean("s.devSess", d.includeDeviceSessions),
                entryReminder = p.getBoolean("n.entry", d.entryReminder),
                autoTicket = p.getBoolean("n.autoTicket", d.autoTicket),
                autoSettle = p.getBoolean("n.autoSettle", d.autoSettle),
                nightlyHarvest = p.getBoolean("n.harvest", d.nightlyHarvest),
                liveWatch = p.getBoolean("n.live", d.liveWatch),
                riskAlertPct = p.getDouble("n.risk", d.riskAlertPct),
                healthAlerts = p.getBoolean("n.health", d.healthAlerts),
                otherAlerts = p.getBoolean("n.other", d.otherAlerts),
                pnlLossAlert = p.getDouble("n.pnlLoss", d.pnlLossAlert),
                pnlProfitAlert = p.getDouble("n.pnlProfit", d.pnlProfitAlert),
                widgetPnl = p.getBoolean("ui.widgetPnl", d.widgetPnl),
                biometric = p.getBoolean("sec.bio", d.biometric),
                allowWeakFace = p.getBoolean("sec.face", d.allowWeakFace),
                idleSeconds = p.getInt("lock.idleSeconds", d.idleSeconds),
                refuseCompromised = p.getBoolean("sec.refuse", d.refuseCompromised),
                wipeOnExhaustion = p.getBoolean("sec.wipe", d.wipeOnExhaustion),
                hideAmountsOnLockScreen = p.getBoolean("sec.hideAmounts", d.hideAmountsOnLockScreen),
                mode = p.getString("k.mode", d.mode)!!,
                // Live trading means real orders; the separate switch is gone, so Live always allows them.
                allowRealOrders = p.getBoolean("k.allow", d.allowRealOrders) || p.getString("k.mode", d.mode) == "live",
                orderProduct = p.getString("k.product", d.orderProduct)!!,
                maxOrdersPerDay = p.getInt("k.maxOrders", d.maxOrdersPerDay),
                maxLotsPerOrder = p.getInt("k.maxLots", d.maxLotsPerOrder),
                maxOrderValue = p.getDouble("k.maxValue", d.maxOrderValue),
                guardKill = p.getBoolean("g.kill", d.guardKill), guardDailyLoss = p.getDouble("g.loss", d.guardDailyLoss),
                guardDrawdownPct = p.getDouble("g.dd", d.guardDrawdownPct), guardMaxOpen = p.getInt("g.open", d.guardMaxOpen),
                guardMaxTrades = p.getInt("g.trades", d.guardMaxTrades), guardMaxValue = p.getDouble("g.value", d.guardMaxValue),
                guardMaxLots = p.getInt("g.lots", d.guardMaxLots), guardCutoff = p.getInt("g.cutoff", d.guardCutoff),
                guardNakedShort = p.getBoolean("g.naked", d.guardNakedShort),
                prepareRealOrder = p.getBoolean("k.prepare", d.prepareRealOrder),
                theme = p.getString("ui.theme", d.theme)!!,
                reduceMotion = p.getBoolean("ui.calm", d.reduceMotion),
            )
        }

        fun save(s: AppSettings) {
            SecurePrefs.putAll(mapOf(
                "s.otm" to s.otmPct, "s.entry" to s.entry, "s.regime" to s.regime, "s.dated" to s.datedLot,
                "s.lot" to s.pinnedLot, "s.wing" to (s.wingPct ?: -1.0), "s.capital" to s.capital,
                "s.survive" to s.survive, "s.holdout" to s.holdout, "s.last" to s.healthLast,
                "s.tkU" to s.ticketUnderlying, "s.tkLots" to s.ticketLots, "s.devSess" to s.includeDeviceSessions,
                "n.entry" to s.entryReminder, "n.autoTicket" to s.autoTicket, "n.autoSettle" to s.autoSettle,
                "n.harvest" to s.nightlyHarvest, "n.live" to s.liveWatch, "n.risk" to s.riskAlertPct,
                "n.health" to s.healthAlerts, "n.other" to s.otherAlerts, "sec.bio" to s.biometric, "sec.face" to s.allowWeakFace,
                "lock.idleSeconds" to s.idleSeconds, "sec.refuse" to s.refuseCompromised,
                "sec.wipe" to s.wipeOnExhaustion, "sec.hideAmounts" to s.hideAmountsOnLockScreen,
                "ui.theme" to s.theme, "ui.calm" to s.reduceMotion, "ui.widgetPnl" to s.widgetPnl,
                "n.pnlLoss" to s.pnlLossAlert, "n.pnlProfit" to s.pnlProfitAlert,
                "k.mode" to s.mode, "k.allow" to s.allowRealOrders, "k.product" to s.orderProduct, "k.maxOrders" to s.maxOrdersPerDay,
                "k.maxLots" to s.maxLotsPerOrder, "k.maxValue" to s.maxOrderValue,
                "g.kill" to s.guardKill, "g.loss" to s.guardDailyLoss, "g.dd" to s.guardDrawdownPct, "g.open" to s.guardMaxOpen,
                "g.trades" to s.guardMaxTrades, "g.value" to s.guardMaxValue, "g.lots" to s.guardMaxLots, "g.cutoff" to s.guardCutoff,
                "g.naked" to s.guardNakedShort, "k.prepare" to s.prepareRealOrder,
            ))
        }
    }
}
