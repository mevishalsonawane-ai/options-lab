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
    // account P&L alerts from the background watch (rupees; 0 = off)
    val pnlLossAlert: Double = 0.0,
    val pnlProfitAlert: Double = 0.0,
    // home-screen widget: index levels always; the account P&L only if the owner opts in
    val widgetPnl: Boolean = false,
    // security
    val biometric: Boolean = false,
    val allowWeakFace: Boolean = false,
    val graceSeconds: Int = 0,
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
    val prepareRealOrder: Boolean = true,
    // appearance
    val theme: String = "system",          // system | light | dark
    val reduceMotion: Boolean = false,
) {
    val entryMinute: Int get() = runCatching { hhmm(entry) }.getOrDefault(ExpiryPut.DEFAULT_ENTRY)

    val live: Boolean get() = mode == "live"

    fun limits() = com.optionslab.engine.Kite.Limits(maxOrdersPerDay, maxLotsPerOrder, maxOrderValue)

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
                pnlLossAlert = p.getDouble("n.pnlLoss", d.pnlLossAlert),
                pnlProfitAlert = p.getDouble("n.pnlProfit", d.pnlProfitAlert),
                widgetPnl = p.getBoolean("ui.widgetPnl", d.widgetPnl),
                biometric = p.getBoolean("sec.bio", d.biometric),
                allowWeakFace = p.getBoolean("sec.face", d.allowWeakFace),
                graceSeconds = p.getInt("lock.graceSeconds", d.graceSeconds),
                refuseCompromised = p.getBoolean("sec.refuse", d.refuseCompromised),
                wipeOnExhaustion = p.getBoolean("sec.wipe", d.wipeOnExhaustion),
                hideAmountsOnLockScreen = p.getBoolean("sec.hideAmounts", d.hideAmountsOnLockScreen),
                mode = p.getString("k.mode", d.mode)!!,
                allowRealOrders = p.getBoolean("k.allow", d.allowRealOrders),
                orderProduct = p.getString("k.product", d.orderProduct)!!,
                maxOrdersPerDay = p.getInt("k.maxOrders", d.maxOrdersPerDay),
                maxLotsPerOrder = p.getInt("k.maxLots", d.maxLotsPerOrder),
                maxOrderValue = p.getDouble("k.maxValue", d.maxOrderValue),
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
                "n.health" to s.healthAlerts, "sec.bio" to s.biometric, "sec.face" to s.allowWeakFace,
                "lock.graceSeconds" to s.graceSeconds, "sec.refuse" to s.refuseCompromised,
                "sec.wipe" to s.wipeOnExhaustion, "sec.hideAmounts" to s.hideAmountsOnLockScreen,
                "ui.theme" to s.theme, "ui.calm" to s.reduceMotion, "ui.widgetPnl" to s.widgetPnl,
                "n.pnlLoss" to s.pnlLossAlert, "n.pnlProfit" to s.pnlProfitAlert,
                "k.mode" to s.mode, "k.allow" to s.allowRealOrders, "k.product" to s.orderProduct, "k.maxOrders" to s.maxOrdersPerDay,
                "k.maxLots" to s.maxLotsPerOrder, "k.maxValue" to s.maxOrderValue, "k.prepare" to s.prepareRealOrder,
            ))
        }
    }
}
