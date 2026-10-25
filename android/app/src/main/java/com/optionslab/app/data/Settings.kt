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
    /**
     * Live orders without PIN: one tap sends a real order (or a whole basket) with no PIN and no review (and a cancel, square-off
     * or protect). The owner's choice, off by default; turning it on asks the PIN. Reset to off once on 18 Oct ([ONE_TAP_KEY]).
     */
    val oneTapOrders: Boolean = false,
    /**
     * 22 Oct (the three gates on a real order): "yes" typed for each real order of Boss's own before the PIN. On by default;
     * turning it off asks the PIN (it loosens a gate). The PIN gate stays whatever this says. Exits never ask it.
     */
    val typedYes: Boolean = true,
    /**
     * "Keep a stop-loss order at the exchange for live positions" (Settings → Zerodha; off unless the owner chooses it):
     * a live arm position whose stop is not resting at Zerodha (it could not be placed, or was refused or cancelled) gets
     * it placed again at the arm's own level, so Zerodha triggers it with no phone in the loop. Only lowers risk; live only.
     */
    val exchangeStops: Boolean = false,
    /**
     * The danger meter's one action (TODO I4, research/HUNT_R17_PRETRADE.md; off unless Boss chooses it): on a careful or
     * paper-only day, PAPER arms buy half their lots (rounded down, at least 1). Only lowers size; never live, never Liquidity.
     */
    val dangerHalfSize: Boolean = false,
    val maxOrdersPerDay: Int = 4,
    val maxLotsPerOrder: Int = 2,
    val maxOrderValue: Double = 500_000.0,
    // Account-wide guard (every order, paper and live, strategy and manual; exits only stop at the kill switch)
    val guardKill: Boolean = false,
    val guardDailyLoss: Double = 2_000.0,
    val guardDrawdownPct: Double = 10.0,
    val guardMaxOpen: Int = 3,
    val guardMaxTrades: Int = 10,
    val guardMaxValue: Double = 200_000.0,
    val guardMaxLots: Int = 2,
    /** Rupees held in one instrument after an order; 0 = off (desktop ACCOUNT_MAX_SYMBOL_EXPOSURE). */
    val guardMaxExposure: Double = 200_000.0,
    /** Minute of day; -1 = no cutoff. 14:55 = 20 minutes before the 15:15 square-off, as the desktop. */
    val guardCutoff: Int = 14 * 60 + 55,
    // The paper account's own loss, drawdown and order limits: the desktop raised these for its paper
    // forward test only (two ORB arms count entry, resting stop and exit orders). Live keeps the ones above.
    val guardPaperTrades: Int = 60,
    /** The paper bots' daily loss limit (NSE paper; MCX keeps its own when split). 25 Oct (Boss: "Make it 10k"): ₹10,000, was ₹6,000. */
    val guardPaperDailyLoss: Double = 10_000.0,
    val guardPaperDrawdownPct: Double = 30.0,
    val guardNakedShort: Boolean = true,
    /** TODO A5: close positions expiring today at 15:05 on expiry day. */
    val expirySquareOff: Boolean = true,
    /** ...except the Expiry Put ticket, which is meant to be held to the 15:30 settlement. */
    val keepExpiryPut: Boolean = true,
    /**
     * MCX expiry exit (9 Oct, [McxGuard]): MCX options closed by [mcxOptionExitMinute] on the trading day before expiry (they
     * turn into futures otherwise), delivery futures [mcxFutureExitDays] trading days before expiry. On by default.
     * [mcxFutureExitDays] was 2 until 9 Oct, now 5 (research/MCX_TREND.md: roll about 5 days before expiry); kept under a new
     * key ("g.mcxFutDays5") so the old default saved on 9 Oct does not hold it at 2.
     */
    val mcxExpiryExit: Boolean = true,
    val mcxOptionExitMinute: Int = 23 * 60,
    val mcxFutureExitDays: Int = com.optionslab.engine.mcx.McxExpiry.DEFAULT_FUTURE_EXIT_DAYS,
    val prepareRealOrder: Boolean = true,
    // appearance
    val theme: String = "system",          // system | light | dark
    val reduceMotion: Boolean = false,
    /**
     * The day lock (08 Oct, research/PROFIT_LOCK_8OCT.md fix 5), rupees; 0 = off: once an account's day P&L reaches it, no new
     * automatic entry in that account for the rest of the day ([com.optionslab.engine.risk.DayLock], [DayLockGuard]). It only
     * ever stops entries. Set in Bot settings only: never by voice, never from a backup (a "g." setting stays on the phone).
     */
    val dayLock: Double = com.optionslab.engine.risk.DayLock.DEFAULT_RUPEES,
    /**
     * 21 Oct (Boss: "Yes, give commodities their own day lock"): the MCX paper arms keep their own PAPER day lock and daily loss
     * limit, on the MCX paper day alone, and the NSE paper ones read the rest ([com.optionslab.ira.SegmentLock]). On by Boss's
     * choice, logged in the Autopilot change timeline (one tap back to the shared lock). Paper only: Zerodha stays one account.
     */
    val mcxOwnLimits: Boolean = true,
    /** The commodities' own paper day lock, rupees (0 = off); unset, the paper day lock's amount. Bot settings only. */
    val mcxDayLock: Double = com.optionslab.engine.risk.DayLock.DEFAULT_RUPEES,
    /** The commodities' own paper daily loss limit, rupees (0 = off); unset, the paper daily loss limit. Bot settings only. */
    val mcxPaperDailyLoss: Double = 10_000.0,           // 25 Oct: with the NSE paper limit (Boss: "Make it 10k")
    /** Home's paper running total counts from this day (ISO date; Boss's 08 Oct wish, [com.optionslab.engine.risk.PaperSince]). Words only. */
    val paperSince: String = com.optionslab.engine.risk.PaperSince.DEFAULT_START.toString(),
) {
    val entryMinute: Int get() = runCatching { hhmm(entry) }.getOrDefault(ExpiryPut.DEFAULT_ENTRY)

    val live: Boolean get() = mode == "live"

    /**
     * TODO A6: one set of limits. The Zerodha order checks use the Bot settings values ("off" = no cap),
     * so the same numbers apply to paper and live and there is one place to change them.
     */
    fun limits() = com.optionslab.engine.Kite.Limits(
        guardMaxTrades.takeIf { it > 0 } ?: Int.MAX_VALUE,
        guardMaxLots.takeIf { it > 0 } ?: 1_000,
        guardMaxValue.takeIf { it > 0 } ?: Double.MAX_VALUE)
    fun guardLimits(paper: Boolean = false) = com.optionslab.engine.risk.AccountGuard.Limits(guardKill,
        if (paper) guardPaperDailyLoss else guardDailyLoss, if (paper) guardPaperDrawdownPct else guardDrawdownPct, guardMaxOpen,
        if (paper) guardPaperTrades else guardMaxTrades, guardMaxValue, guardMaxLots, guardCutoff.takeIf { it >= 0 }, guardNakedShort,
        maxSymbolExposure = guardMaxExposure)

    fun params(): ExpiryPut.Params = ExpiryPut.Params(
        otmPct = otmPct, entryMinute = entryMinute, regime = regime, wingPct = wingPct,
        lot = if (datedLot) ExpiryPut.LotChoice.Dated else ExpiryPut.LotChoice.Pinned(pinnedLot),
    )

    companion object {
        /** The kill switch's key in the settings vault: read alone by the entry door ([Broker.placeOrder]). */
        const val KILL_KEY = "g.kill"
        /**
         * "Live orders without PIN" (18 Oct review: its second key - the first, "k.oneTap", was set when one tap still opened a
         * review card; it is never read again, so the setting starts off once with this update).
         */
        const val ONE_TAP_KEY = "k.oneTap2"
        /** 22 Oct: "yes" typed before a real order (on by default). */
        const val TYPED_YES_KEY = "k.typedYes"
        /** 25 Oct: set once the paper daily loss limit moved to Boss's ₹10,000 ([load]); from then the saved value stands. */
        const val PAPER_LOSS_VERSION_KEY = "g.pLossV"
        /** The paper daily loss limit's default before 25 Oct. */
        const val OLD_PAPER_LOSS = 6_000.0
        /** The danger meter's half-size switch (paper arms; off by default). */
        const val DANGER_HALF_KEY = "r.dangerHalf"

        fun load(): AppSettings {
            val d = AppSettings()
            val p = SecurePrefs
            // Saved before the guard matched the desktop (v2): the old untouched defaults move to the desktop's.
            val old = p.getInt("g.v", 1) < 2
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
                // 18 Oct (review): with the direct send, one tap now sends a real order with no PIN and no review - a stronger
                // meaning than Boss switched on. Reset to off once with this update (a new key: the old "k.oneTap" is never read
                // again), so it is on only after he turns it on again knowingly, with his PIN.
                oneTapOrders = p.getBoolean(ONE_TAP_KEY, d.oneTapOrders),
                typedYes = p.getBoolean(TYPED_YES_KEY, d.typedYes),
                exchangeStops = p.getBoolean("k.exStop", d.exchangeStops),
                dangerHalfSize = p.getBoolean(DANGER_HALF_KEY, d.dangerHalfSize),
                maxOrdersPerDay = p.getInt("k.maxOrders", d.maxOrdersPerDay),
                maxLotsPerOrder = p.getInt("k.maxLots", d.maxLotsPerOrder),
                maxOrderValue = p.getDouble("k.maxValue", d.maxOrderValue),
                guardKill = p.getBoolean("g.kill", d.guardKill), guardDailyLoss = p.getDouble("g.loss", d.guardDailyLoss),
                guardDrawdownPct = p.getDouble("g.dd", d.guardDrawdownPct), guardMaxOpen = p.getInt("g.open", d.guardMaxOpen),
                guardMaxTrades = p.getInt("g.trades", d.guardMaxTrades), guardMaxValue = p.getDouble("g.value", d.guardMaxValue).let { if (old && it == 500_000.0) d.guardMaxValue else it },
                guardMaxLots = p.getInt("g.lots", d.guardMaxLots), guardCutoff = p.getInt("g.cutoff", d.guardCutoff).let { if (old && it == 14 * 60 + 30) d.guardCutoff else it },
                guardNakedShort = p.getBoolean("g.naked", d.guardNakedShort),
                guardMaxExposure = p.getDouble("g.expo", d.guardMaxExposure), guardPaperTrades = p.getInt("g.pTrades", d.guardPaperTrades),
                // 25 Oct (Boss: "Make it 10k"): the old ₹6,000 default saved before that day becomes ₹10,000 once; a limit Boss or
                // Jarvis had set to anything else stays. From the first save on ("g.pLossV") the saved value always stands.
                guardPaperDailyLoss = p.getDouble("g.pLoss", d.guardPaperDailyLoss).let { if (p.getInt(PAPER_LOSS_VERSION_KEY, 0) < 1 && it == OLD_PAPER_LOSS) d.guardPaperDailyLoss else it }, guardPaperDrawdownPct = p.getDouble("g.pDd", d.guardPaperDrawdownPct),
                expirySquareOff = p.getBoolean("g.expSq", d.expirySquareOff), keepExpiryPut = p.getBoolean("g.keepPut", d.keepExpiryPut),
                mcxExpiryExit = p.getBoolean("g.mcxExit", d.mcxExpiryExit),
                mcxOptionExitMinute = p.getInt("g.mcxOptAt", d.mcxOptionExitMinute).coerceIn(9 * 60, 23 * 60 + 40),
                mcxFutureExitDays = p.getInt("g.mcxFutDays5", d.mcxFutureExitDays).coerceIn(1, 10),
                prepareRealOrder = p.getBoolean("k.prepare", d.prepareRealOrder),
                theme = p.getString("ui.theme", d.theme)!!,
                reduceMotion = p.getBoolean("ui.calm", d.reduceMotion),
                dayLock = com.optionslab.engine.risk.DayLock.clean(p.getDouble("g.dayLock", d.dayLock)),
                mcxOwnLimits = p.getBoolean("g.mcxOwn", d.mcxOwnLimits),
                // Unset: the same amounts as the paper ones (Boss, 21 Oct).
                mcxDayLock = com.optionslab.engine.risk.DayLock.clean(p.getDouble("g.mcxDayLock", p.getDouble("g.dayLock", d.dayLock))),
                mcxPaperDailyLoss = p.getDouble("g.mcxPLoss", p.getDouble("g.pLoss", d.guardPaperDailyLoss)).let { if (it.isFinite() && it >= 0) it else d.mcxPaperDailyLoss }
                    .let { if (p.getInt(PAPER_LOSS_VERSION_KEY, 0) < 1 && it == OLD_PAPER_LOSS) d.mcxPaperDailyLoss else it },
                paperSince = com.optionslab.engine.risk.PaperSince.startOf(p.getString("ui.paperSince", d.paperSince)).toString(),
            )
        }

        /**
         * Bumped after every [save] (18 Oct, review): the screen's copy ([com.optionslab.app.ui.AppModel.settings]) reads the
         * vault again, so a kill switch the guard turned on shows at once and is never written back off. Memory only.
         */
        private val saveCount = java.util.concurrent.atomic.AtomicLong()
        private val savesFlow = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val saves: kotlinx.coroutines.flow.StateFlow<Long> = savesFlow

        /**
         * The kill switch on, as the guard turns it on by itself (18 Oct, review): readable at once by every reader (the entry
         * door, the arms, the screen through [saves]) and written to disk by the vault's background writer, so the order path
         * and the screen's thread never wait on a vault write. Only that one key: nothing else the vault holds is touched.
         */
        fun killSoon() {
            SecurePrefs.putAllSoon(mapOf(KILL_KEY to true))
            savesFlow.value = saveCount.incrementAndGet()
            // In the diary, on its own thread (the screen's own switch writes the same line through AppModel.update).
            runCatching { Diag.record("mode", "kill switch ON (the guard: a live drawdown)") }
            // 18 Oct, review: made sure it reaches disk, as PinLock does with its failure count - on a thread of its own (the
            // caller is the order path, which never waits on a vault write): the background write is waited for, and if it
            // failed the key is written directly, tried again a few times. A process death or a failed write no longer loses it.
            runCatching { killWriter.execute { persistKill() } }
        }

        /** The kill switch's own disk writer (one daemon thread; never the order path's or the screen's). */
        private val killWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "kill-writer").apply { isDaemon = true } }

        /** Tests: wait until the kill switch's writer has nothing queued. */
        internal fun awaitKillWriter() { runCatching { killWriter.submit(Runnable {}).get(30, java.util.concurrent.TimeUnit.SECONDS) } }

        /** How the kill switch is waited for and written (tests swap them to make a write fail). */
        @Volatile internal var killFlush: () -> Boolean = { SecurePrefs.flush() }
        // The settings as they are in memory now, written on this thread: the kill switch with them, or Boss's clear if they
        // cleared it meanwhile (never a stale "on" written over it).
        @Volatile internal var killPut: () -> Unit = { check(SecurePrefs.writeNow()) { "the settings could not be written" } }
        @Volatile internal var killRetryMs: Long = 2_000L

        /**
         * The kill switch on disk: true once [killFlush] says the background write landed, or a direct [killPut] did not throw.
         * Tried [attempts] times, [killRetryMs] apart. What is written is what memory holds then, so a kill switch Boss
         * cleared meanwhile stays cleared.
         */
        internal fun persistKill(attempts: Int = 4): Boolean {
            repeat(attempts) { n ->
                if (runCatching { killFlush() }.getOrDefault(false)) return true
                if (runCatching { killPut() }.isSuccess) return true
                if (n < attempts - 1) runCatching { Thread.sleep(killRetryMs) }
            }
            runCatching { Diag.record("mode", "kill switch: the vault write failed ${attempts}x; it is on in memory, written again at the next save") }
            return false
        }

        /** Every setting's vault key and value. */
        private fun keys(s: AppSettings): Map<String, Any?> = mapOf(
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
                "k.mode" to s.mode, "k.allow" to s.allowRealOrders, "k.product" to s.orderProduct, ONE_TAP_KEY to s.oneTapOrders, TYPED_YES_KEY to s.typedYes, "k.exStop" to s.exchangeStops, DANGER_HALF_KEY to s.dangerHalfSize, "k.maxOrders" to s.maxOrdersPerDay,
                "k.maxLots" to s.maxLotsPerOrder, "k.maxValue" to s.maxOrderValue,
                "g.kill" to s.guardKill, "g.loss" to s.guardDailyLoss, "g.dd" to s.guardDrawdownPct, "g.open" to s.guardMaxOpen,
                "g.trades" to s.guardMaxTrades, "g.value" to s.guardMaxValue, "g.lots" to s.guardMaxLots, "g.cutoff" to s.guardCutoff,
                "g.naked" to s.guardNakedShort, "g.expo" to s.guardMaxExposure, "g.pTrades" to s.guardPaperTrades,
                "g.pLoss" to s.guardPaperDailyLoss, PAPER_LOSS_VERSION_KEY to 1, "g.pDd" to s.guardPaperDrawdownPct, "g.v" to 2, "g.expSq" to s.expirySquareOff, "g.keepPut" to s.keepExpiryPut, "k.prepare" to s.prepareRealOrder,
                "g.mcxExit" to s.mcxExpiryExit, "g.mcxOptAt" to s.mcxOptionExitMinute, "g.mcxFutDays5" to s.mcxFutureExitDays,
                "g.dayLock" to com.optionslab.engine.risk.DayLock.clean(s.dayLock),
                "g.mcxOwn" to s.mcxOwnLimits, "g.mcxDayLock" to com.optionslab.engine.risk.DayLock.clean(s.mcxDayLock), "g.mcxPLoss" to s.mcxPaperDailyLoss,
                "ui.paperSince" to com.optionslab.engine.risk.PaperSince.startOf(s.paperSince).toString(),
            )

        /**
         * 18 Oct, review: only the settings [to] changes from [from] are written (the screen's change, applied to what was
         * stored), so a value written meanwhile by someone else - the guard's kill switch - is never written back over.
         */
        fun saveChanged(from: AppSettings, to: AppSettings) {
            // Settings from before the guard's v2 defaults are read through a migration (see [load]): written whole once.
            // 18 Oct, review: a kill switch the guard turned on since [from] was read (this change did not touch it) is kept in that
            // whole write too - CI's SettingsUpdateTest caught the first change on a fresh vault writing it back off.
            // 18 Oct (CI, again): a kill switch this change did not touch is left out of that whole write altogether. Checked and
            // then written whole, the guard's write could still land in between (the save's own history read sits there) and be
            // written back off; and a switch Boss cleared since [from] was read was written back on.
            if (SecurePrefs.getInt("g.v", 1) < 2) { save(to, keepKill = from.guardKill == to.guardKill); return }
            runCatching { SettingsLog.diff(from, to) }
            val was = keys(from)
            val changed = keys(to).filter { (k, v) -> was[k] != v }
            if (changed.isNotEmpty()) SecurePrefs.putAll(changed)
            DangerToday.halfSize = to.dangerHalfSize
            savesFlow.value = saveCount.incrementAndGet()
        }

        /** Every setting written ([keepKill]: all but the kill switch, which keeps what the vault holds). */
        fun save(s: AppSettings, keepKill: Boolean = false) {
            // The limits' history ("what did I change this week", "Jarvis, undo"); never stops a save.
            // A kill switch left as the vault holds it ([keepKill]) is logged as it is, never as a change that was not written.
            runCatching { val was = load(); SettingsLog.diff(was, if (keepKill) s.copy(guardKill = was.guardKill) else s) }
            SecurePrefs.putAll(if (keepKill) keys(s) - KILL_KEY else keys(s))
            // The entry path reads the danger meter's switch from memory only: kept in step with every save (13 Oct).
            DangerToday.halfSize = s.dangerHalfSize
            savesFlow.value = saveCount.incrementAndGet()
        }

        /** The danger meter's switch from the vault into [DangerToday]'s memory copy: the app's start, after a restore. */
        fun syncDangerHalf() { DangerToday.halfSize = SecurePrefs.getBoolean(DANGER_HALF_KEY, false) }
    }
}
