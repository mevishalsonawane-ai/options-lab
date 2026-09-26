package com.optionslab.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.optionslab.app.data.Alarms
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Harvester
import com.optionslab.app.data.Holidays
import com.optionslab.app.data.Ledger
import com.optionslab.app.data.Market
import com.optionslab.app.data.Store
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.IST
import com.optionslab.engine.Monitor
import com.optionslab.engine.fmtG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The PC ran one scheduled task (harvest_nightly.ps1 after the close). The
 * phone runs the strategy's whole day on its own clock:
 *
 *   09:14  live watch starts   (market days; ongoing notification, risk alerts, price alarms)
 *   10:55  entry reminder      (expiry days)
 *   11:01  paper ticket        (expiry days, if auto-ticket is on)
 *   15:35  settlement          (expiry days, if a ticket is open)
 *   15:45  harvest             (market days), then the health check
 *
 * Exact alarms, because since Android 12 only an exact alarm (or the user)
 * may start the foreground service a six-hour watch or a ten-minute harvest
 * needs. Without that permission the jobs fall back to WorkManager, which is
 * reliable but may run late and cannot keep a watch alive all session.
 */
object Jobs {
    enum class Kind(val at: LocalTime, val expiryOnly: Boolean) {
        LIVE(LocalTime.of(9, 14), false),
        REMIND(LocalTime.of(10, 55), true),
        TICKET(LocalTime.of(11, 1), true),
        SETTLE(LocalTime.of(15, 35), true),
        HARVEST(LocalTime.of(15, 45), false),
    }

    const val EXTRA_KIND = "kind"

    /** Nothing runs in the background until a Zerodha account is linked. */
    fun enabled(k: Kind, s: AppSettings) = com.optionslab.app.data.Broker.linked && when (k) {
        Kind.LIVE -> true   // the market watch always runs on market days; it has no off switch
        Kind.REMIND -> s.entryReminder
        Kind.TICKET -> s.autoTicket || (s.prepareRealOrder && com.optionslab.app.data.Broker.configured)
        Kind.SETTLE -> s.autoSettle
        Kind.HARVEST -> s.nightlyHarvest
    }

    /** The next weekday instant for this job, in IST. */
    fun nextRun(k: Kind, from: ZonedDateTime = Market.now()): ZonedDateTime {
        var d = from.toLocalDate()
        if (!from.toLocalTime().isBefore(k.at)) d = d.plusDays(1)
        while (!Market.isTradingDay(d)) d = d.plusDays(1)
        return d.atTime(k.at).atZone(IST)
    }

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    private fun alarmIntent(context: Context, k: Kind): PendingIntent = PendingIntent.getBroadcast(
        context, 100 + k.ordinal,
        Intent(context, AlarmReceiver::class.java).setAction("ol.job.${k.name}").putExtra(EXTRA_KIND, k.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun scheduleAll(context: Context) {
        val s = AppSettings.load()
        Kind.entries.forEach { schedule(context, it, s) }
        Heartbeat.schedule(context)
        DailyReports.scheduleAll(context)
    }

    fun schedule(context: Context, k: Kind, s: AppSettings = AppSettings.load()) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context, k)
        am.cancel(pi)
        if (!enabled(k, s)) return
        val at = nextRun(k).toInstant().toEpochMilli()
        try {
            if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Start work in the foreground service; from the UI this is always allowed. */
    fun start(context: Context, k: Kind, manual: Boolean = true) {
        if (!com.optionslab.app.data.Broker.linked) return
        // The harvest runs quietly in WorkManager: no foreground notification, no "complete" notice.
        if (k == Kind.HARVEST) {
            val req = OneTimeWorkRequestBuilder<FallbackWorker>()
                .setInputData(workDataOf(EXTRA_KIND to k.name, "manual" to manual))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("job.${k.name}", ExistingWorkPolicy.KEEP, req)
            return
        }
        val i = Intent(context, WatchService::class.java).putExtra(EXTRA_KIND, k.name).putExtra("manual", manual)
        try {
            ContextCompat.startForegroundService(context, i)
        } catch (_: Exception) {
            // Not permitted from here (Android 12+ background start). Hand
            // the one-shot jobs to WorkManager; the watch cannot run that way.
            if (k == Kind.LIVE) {
                Notifier.post(context, 2010, Notifier.APPROVAL, "Market is open", "Tap to start the market watch.", "almanac")
            } else {
                val req = OneTimeWorkRequestBuilder<FallbackWorker>()
                    .setInputData(workDataOf(EXTRA_KIND to k.name, "manual" to manual))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork("job.${k.name}", ExistingWorkPolicy.KEEP, req)
            }
        }
    }

    /** Market hours today (from 09:14 to the close): the watch should be running now. */
    fun watchDue(): Boolean = Market.isTradingDay() && Market.minuteNow() in (Market.OPEN - 1)..Market.CLOSE

    /** Start the watch now if it should be running; it is a no-op when it already is. */
    fun ensureWatch(context: Context) { if (watchDue()) start(context, Kind.LIVE, manual = false) }

    fun stopLive(context: Context) {
        context.startService(Intent(context, WatchService::class.java).setAction(WatchService.STOP))
    }
}

/** Fires at each scheduled instant: re-arm tomorrow's, then run today's. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Heartbeat.ACTION) { Heartbeat.check(context); return }
        DailyReports.of(intent.action)?.let { DailyReports.fired(context, it); return }
        val k = runCatching { Jobs.Kind.valueOf(intent.getStringExtra(Jobs.EXTRA_KIND) ?: return) }.getOrNull() ?: return
        Jobs.schedule(context, k)
        val s = AppSettings.load()
        if (!Market.isTradingDay()) return   // an NSE holiday: nothing trades, nothing to do
        if (k.expiryOnly && !Market.isExpiryDay(s.ticketUnderlying)) return
        if (k == Jobs.Kind.REMIND) {
            Tasks.remind(context, s)
            return
        }
        Jobs.start(context, k, manual = false)
    }
}

/** Alarms do not survive a reboot or an app update; re-arm them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> Jobs.scheduleAll(context)
        }
        // Rebooted or updated during market hours: pick the watch straight back up.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Jobs.ensureWatch(context)
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    companion object { const val STOP_LIVE = "com.optionslab.app.STOP_LIVE" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == STOP_LIVE) Jobs.stopLive(context)
        // "Close position" on a paper position's card: close it now (paper only; a Zerodha card opens the app instead).
        if (intent.action == PositionCards.ACTION_CLOSE_PAPER) {
            val symbol = intent.getStringExtra(PositionCards.EXTRA_SYMBOL) ?: return
            val done = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val open = com.optionslab.app.data.Paper.state.positions.filter { it.symbol == symbol && it.quantity != 0 }
                    if (open.isEmpty()) Alerts.post("No open paper position in $symbol.", Alerts.Kind.ERROR)
                    for (p in open) {
                        val r = com.optionslab.app.data.Paper.close(p.symbol, p.product)
                        Alerts.post(r.message, if (r.ok) Alerts.Kind.SUCCESS else Alerts.Kind.ERROR, if (r.ok) "Paper position closed" else "Could not close")
                    }
                    // An ORB position closed this way is booked and its resting stop taken out now, not on the next pass.
                    runCatching { com.optionslab.app.data.OrbArms.priceCheckOnly() }
                    runCatching { com.optionslab.app.data.Paper.tick() }
                    runCatching { PositionCards.refresh(context) }
                } finally { done.finish() }
            }
        }
    }
}

/** What each job actually does; shared by the service and the fallback worker. */
object Tasks {
    data class LiveState(
        val running: Boolean = false,
        val stage: String = "",
        val progress: Float = -1f,
        val lastUpdate: Long = 0L,
    )

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    fun publish(s: LiveState) { _state.value = s }

    fun remind(context: Context, s: AppSettings) = Notifier.post(context, 2001, Notifier.SCHEDULE,
        "Expiry day - entry at ${s.entry}",
        "Today is a ${s.ticketUnderlying} expiry. The strategy sells its put at ${s.entry} IST, ${"%.2f".format(100 * s.otmPct)}% below the parity forward. Hold to settlement; never buy it back.",
        "ticket")

    suspend fun ticket(context: Context, s: AppSettings) {
        if (Ledger.all().any { it.row.ticket.session == Market.today() }) return
        val d = Market.draftTicket(s)
        Ledger.record(d.ticket, d.shortKey, d.wingKey)
        val t = d.ticket
        Notifier.post(context, 2002, Notifier.SCHEDULE, "Paper ticket: SELL ${t.underlying} ${fmtG(t.strike)} PE",
            "Credit Rs %.2f/unit (Rs %,.0f), breakeven %,.1f. Recorded as paper - nothing was sent."
                .format(t.credit, t.credit * t.qty, t.breakeven), "ticket")
        // The real order is PREPARED, never sent: it waits for your review.
        if (s.prepareRealOrder && com.optionslab.app.data.Broker.loggedIn) Notifier.post(context, 2004, Notifier.APPROVAL,
            "Review today's Zerodha order", "SELL ${t.underlying} ${fmtG(t.strike)} PE x${t.lots} is ready. Open Options → Expiry Put, review it and hold to send - nothing goes until you do.", "ticket")
    }

    suspend fun settle(context: Context, s: AppSettings) {
        val open = Ledger.openTicket() ?: return
        val tk = open.row.ticket
        if (tk.session != Market.today()) return
        val settlement = Market.settlement(tk.underlying)
        val tr = Ledger.settle(tk.session, settlement, s.regime).row.trade!!
        Notifier.post(context, 2003, Notifier.SCHEDULE,
            "Settled ${if (tr.won) "WIN" else "LOSS"}: Rs %+,.0f".format(tr.netPnl),
            "${tk.underlying} settled at %,.1f against the ${fmtG(tk.strike)} strike.".format(settlement), "ticket")
    }

    suspend fun harvest(context: Context, s: AppSettings, onProgress: (String, Float) -> Unit) {
        if (Holidays.stale(Market.today())) runCatching { Holidays.refresh() }
        val r = Harvester.run(onProgress = { p -> onProgress(p.stage, if (p.total > 0) p.done.toFloat() / p.total else -1f) })
        // No notification: the result is shown in More → Data and harvest.
        SecurePrefs.put("harvest.last", "${Market.today()}: ${r.summary()}")
        if (s.healthAlerts) healthCheck(context, s)
        // The ORB evening replay (TODO A8): the day's bars, beside what the paper arms did. No orders.
        runCatching { com.optionslab.app.data.OrbArms.replayIfDue() }
    }

    fun healthCheck(context: Context, s: AppSettings) {
        val report = Store.backtest(s)
        val rows = Monitor.rows(report.all)
        if (rows.isEmpty()) return
        val (_, checks) = Monitor.healthOf(rows, s.healthLast, s.otmPct, if (s.datedLot) null else s.pinnedLot)
        val verdict = Monitor.verdict(checks).name
        val prev = SecurePrefs.getString("health.verdict")
        SecurePrefs.put("health.verdict", verdict)
        if (prev != null && prev != verdict) {
            val worst = checks.filter { it.status.name == verdict }.joinToString { it.name }
            Notifier.post(context, 5001, Notifier.HEALTH, "Strategy health: $prev → $verdict", "Now $verdict on: $worst.", "health")
        }
    }

    data class Tick(val title: String, val lines: List<String>, val progress: Int)

    /**
     * One pass of the live watch: index levels, the open ticket's live mark and
     * cushion to breakeven, and every price alarm. Risk alerts fire once each.
     */
    suspend fun watchTick(context: Context, s: AppSettings, fired: MutableSet<String>): Tick {
        val q = listOf("NIFTY", "BANKNIFTY", "INDIAVIX").mapNotNull { runCatching { Market.quote(it) }.getOrNull() }.associateBy { it.symbol }
        val lines = ArrayList<String>()
        q["NIFTY"]?.let { lines += "NIFTY %,.1f (%+.2f%%)".format(it.last, 100 * it.changePct) }
        q["BANKNIFTY"]?.let { lines += "BANKNIFTY %,.1f (%+.2f%%)".format(it.last, 100 * it.changePct) }
        q["INDIAVIX"]?.let { lines += "VIX %.2f".format(it.last) }
        var title = "Market watch"
        var progress = -1
        val open = Ledger.openTicket()
        if (open != null) {
            val tk = open.row.ticket
            val spot = q[tk.underlying]?.last
            val mark = runCatching { Market.markOpenTicket(open) }.getOrNull()
            title = "Paper ${tk.underlying} ${fmtG(tk.strike)} PE" + (mark?.let { " · Rs %+,.0f".format(it) } ?: "")
            if (spot != null) {
                val cushion = (spot - tk.breakeven) / spot
                lines.add(0, "Cushion to breakeven %,.1f pts (%.2f%%)".format(spot - tk.breakeven, 100 * cushion))
                // 0 at the breakeven, 100 at the forward the ticket was priced off.
                progress = (100 * ((spot - tk.breakeven) / (tk.forward - tk.breakeven))).toInt().coerceIn(0, 100)
                if (cushion <= s.riskAlertPct && fired.add("risk.near")) Notifier.post(context, 3001, Notifier.RISK,
                    "Index nearing your breakeven",
                    "${tk.underlying} %,.1f is %.2f%% above the %,.1f breakeven of the ${fmtG(tk.strike)} put.".format(spot, 100 * cushion, tk.breakeven), "ticket")
                if (spot < tk.strike && fired.add("risk.itm")) Notifier.post(context, 3002, Notifier.RISK,
                    "Short put is in the money",
                    "${tk.underlying} %,.1f is below the ${fmtG(tk.strike)} strike. The loss is unbounded below here.".format(spot), "ticket")
            }
        }
        // Alarms on any instrument ("NSE:INFY", "NFO:NIFTY26SEP24500PE") are priced from Zerodha.
        val prices = HashMap(q.mapValues { it.value.last })
        val b = com.optionslab.app.data.Broker
        var accountPnl: Double? = null
        if (s.live && b.loggedIn) {
            val keys = Alarms.all().filter { it.enabled && ':' in it.symbol }.map { it.symbol }.distinct()
            if (keys.isNotEmpty()) runCatching { b.quotes(keys) }.getOrNull()?.forEach { (k, v) -> prices[k] = v.last }
            // The account's P&L: recorded for the day's curve, and alerted on the owner's levels.
            runCatching { b.positionBook() }.getOrNull()?.takeIf { it.net.isNotEmpty() }?.let { book ->
                com.optionslab.app.data.PnlTracker.record(book.pnl)
                runCatching { com.optionslab.app.data.DailyPnl.record(true, book.m2m, -1) }
                accountPnl = book.pnl
                lines.add(0, "Positions %s".format(if (s.hideAmountsOnLockScreen) "open: ${book.net.count { it.open }}" else "Rs %+,.0f".format(book.pnl)))
                pnlAlerts(context, s, book.pnl)
            }
        }
        runCatching { com.optionslab.app.data.Paper.state.positions.count { it.quantity != 0 } }.getOrDefault(0).takeIf { it > 0 }?.let { n ->
            runCatching { com.optionslab.app.data.Paper.snapshot() }.getOrNull()?.let { snap ->
                val pnl = snap.funds.todayRealizedPnl + snap.funds.m2mUnrealized
                runCatching { com.optionslab.app.data.DailyPnl.record(false, pnl, snap.trades.size) }
                lines.add(0, "Paper %s".format(if (s.hideAmountsOnLockScreen) "open: $n" else "P&L Rs %+,.0f · $n open".format(pnl)))
            }
        }
        // Alarms set from the chart, priced from the chart's own feed (the last 1-minute close).
        Alarms.all().filter { it.enabled && it.symbol.startsWith(com.optionslab.app.data.PriceAlarm.CHART) }.map { it.symbol }.distinct().forEach { key ->
            val sym = key.removePrefix(com.optionslab.app.data.PriceAlarm.CHART)
            val now = System.currentTimeMillis() / 1000
            runCatching { com.optionslab.app.data.ChartFeed.bars(sym, "1m", now - 3 * 3600, now).lastOrNull()?.close }.getOrNull()?.let { prices[key] = it }
        }
        checkAlarms(context, prices, fired)
        runCatching {
            com.optionslab.app.widget.IraWidget.publish(context, q["NIFTY"]?.let { it.last to it.changePct },
                q["BANKNIFTY"]?.let { it.last to it.changePct }, accountPnl)
        }
        // Sandbox paper account: resting orders fill, MIS squares off at 15:15, expiries settle.
        // Paper account (also used by paper strategy runs in LIVE mode): resting orders fill, MIS squares off, expiries settle.
        runCatching { com.optionslab.app.data.Paper.tick() }.getOrNull()?.let { paperEvents(context, it) }
        // The ORB paper arms: manage open positions, then decide on the last completed 5-minute bar.
        runCatching { com.optionslab.app.data.OrbArms.tick() }
        // Pine scripts set to auto-trade: decide on each completed candle, sell at 15:15.
        runCatching { com.optionslab.app.data.PineAuto.tick() }
        // Zerodha's live price stream (Live mode, logged in, market hours).
        runCatching { com.optionslab.app.data.KiteStream.ensure() }
        // The static-IP relay: connected ahead of the first order, kept alive during market hours.
        if (com.optionslab.app.data.Market.isOpen()) runCatching { com.optionslab.app.data.Relay.warm() }
        // Stops, trailing stops and targets: one exit filled cancels the other; trails move up.
        runCatching { com.optionslab.app.data.Protections.tick() }
        // Every open position's notification, with its live P&L and a Close button.
        runCatching { PositionCards.refresh(context) }
        // Expiry day, 15:05: close every option position expiring today (paper and live, all products).
        runCatching { com.optionslab.app.data.ExpirySquareOff.maybeRun(context, s) }
        // Strategy Module: schedules, prices, per-leg and basket risk, exits.
        runCatching {
            val bad = com.optionslab.app.security.Integrity.compromised(com.optionslab.app.security.Integrity.reportWithin(context, 60_000))
            com.optionslab.app.data.Strategies.tickAll(bad)
        }
        return Tick(title, lines, progress)
    }

    fun paperEventsPublic(context: Context, events: List<com.optionslab.engine.sandbox.SandboxEvent>) = paperEvents(context, events)

    private fun paperEvents(context: Context, events: List<com.optionslab.engine.sandbox.SandboxEvent>) {
        for (e in events) when (e) {
            is com.optionslab.engine.sandbox.SandboxEvent.Fill -> Notifier.orderFilled(context, e.action, e.quantity, e.symbol, e.price, "Paper",
                kotlinx.coroutines.runBlocking { runCatching { com.optionslab.app.data.Strategies.owners()["paper:${e.orderId}"] }.getOrNull() })
            is com.optionslab.engine.sandbox.SandboxEvent.ExpirySettled -> Notifier.post(context, 7000 + (e.symbol.hashCode() and 0x3ff), Notifier.LIVE,
                "Paper contract settled", "${e.symbol} at %.2f · P&L Rs %+,.0f".format(e.price.toDouble(), e.pnl.toDouble()), "trade")
            is com.optionslab.engine.sandbox.SandboxEvent.SquareOff -> Notifier.post(context, 7000 + (e.symbol.hashCode() and 0x3ff), Notifier.LIVE,
                "Paper MIS squared off", e.symbol, "trade")
            else -> Unit
        }
    }

    /** Once a day per kind: the account's P&L crossed the owner's loss or profit level. */
    private fun pnlAlerts(context: Context, s: AppSettings, pnl: Double) {
        val day = Market.today().toString()
        fun once(kind: String, title: String, text: String) {
            if (SecurePrefs.getString("pnl.alerted.$kind") == day) return
            SecurePrefs.put("pnl.alerted.$kind", day)
            Notifier.post(context, if (kind == "loss") 3101 else 3102, Notifier.RISK, title, text, "trade")
        }
        if (s.pnlLossAlert > 0 && pnl <= -s.pnlLossAlert) once("loss", "Account loss beyond your level", "Today's P&L is Rs %,.0f (your alert: -Rs %,.0f).".format(pnl, s.pnlLossAlert))
        if (s.pnlProfitAlert > 0 && pnl >= s.pnlProfitAlert) once("profit", "Account profit reached your level", "Today's P&L is Rs %+,.0f (your alert: Rs %,.0f).".format(pnl, s.pnlProfitAlert))
    }

    fun checkAlarms(context: Context, prices: Map<String, Double>, fired: MutableSet<String>) {
        for (a in Alarms.all().filter { it.enabled }) {
            val price = prices[a.symbol] ?: continue
            val tag = "alarm.${a.id}"
            if (a.hit(price) && tag !in fired && System.currentTimeMillis() - a.firedAtMillis > 30 * 60_000) {
                fired += tag
                Alarms.upsert(a.copy(firedAtMillis = System.currentTimeMillis()))
                Notifier.post(context, 4000 + (a.id % 1000).toInt(), Notifier.RISK, "Alarm: ${a.describe()}",
                    "${a.symbol} is at %,.2f.${if (a.note.isNotBlank()) " ${a.note}" else ""}".format(price), "alarms")
            }
        }
    }
}

/**
 * The foreground service behind the live watch and the long jobs. It shows one
 * ongoing notification that rewrites itself each minute - the live update -
 * and stops itself when nothing is left to do.
 */
class WatchService : Service() {
    companion object { const val STOP = "com.optionslab.app.WATCH_STOP" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = java.util.concurrent.ConcurrentHashMap<Jobs.Kind, Job>()
    @Volatile private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun show(title: String, text: String, progress: Int = -1) {
        val n = Notifier.builder(this, Notifier.LIVE, title, text, "almanac")
            .setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false).setSilent(true)
            .apply { if (progress >= 0) setProgress(100, progress, false) }
            .build()
        // The watch runs longer than Android 15 allows a dataSync service (6h a day); it is
        // declared specialUse, which has no daily cap. One-shot jobs stay dataSync.
        val type = when {
            Build.VERSION.SDK_INT >= 34 && watching -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, Notifier.ID_LIVE, n, type)
        } catch (_: Exception) {
            // Not allowed now (e.g. the dataSync budget is spent): say so rather than crash.
            Notifier.post(this, 2011, Notifier.APPROVAL, "IraAlgo could not run in the background", "Open the app to continue: $title", "almanac")
        }
    }

    /** Android 15: a time-limited foreground service must stop when told, or the app is killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Notifier.post(this, 2012, Notifier.APPROVAL, "Background watch stopped by Android",
            "The system's time limit for background work was reached. Open IraAlgo to keep strategies and alerts checked.", "almanac")
        stopEverything()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, always: the system requires it within seconds.
        val k = runCatching { Jobs.Kind.valueOf(intent?.getStringExtra(Jobs.EXTRA_KIND) ?: "") }.getOrNull()
        if (k == Jobs.Kind.LIVE) watching = true   // show() then declares the specialUse type
        show("IraAlgo", "Starting…")
        if (intent?.action == STOP) { stopEverything(); return START_NOT_STICKY }
        if (!com.optionslab.app.data.Broker.linked) { stopEverything(); return START_NOT_STICKY }
        if (k == null) { maybeStop(); return START_NOT_STICKY }
        if (running[k]?.isActive == true) return START_NOT_STICKY
        running[k] = scope.launch {
            val s = AppSettings.load()
            try {
                when (k) {
                    Jobs.Kind.LIVE -> watch(s)
                    Jobs.Kind.TICKET -> Tasks.ticket(this@WatchService, s)
                    Jobs.Kind.SETTLE -> Tasks.settle(this@WatchService, s)
                    Jobs.Kind.HARVEST -> Tasks.harvest(this@WatchService, s) { stage, p ->
                        Tasks.publish(Tasks.LiveState(true, stage, p, System.currentTimeMillis()))
                        show("Harvesting · $stage", if (p >= 0) "${(100 * p).toInt()}%" else "", if (p >= 0) (100 * p).toInt() else -1)
                    }
                    Jobs.Kind.REMIND -> Tasks.remind(this@WatchService, s)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Notifier.post(this@WatchService, 2900 + k.ordinal, Notifier.SCHEDULE,
                    "${k.name.lowercase().replaceFirstChar { it.uppercase() }} did not complete", e.message ?: "unknown failure")
            } finally {
                Tasks.publish(Tasks.LiveState(false))
                running.remove(k)
                if (k == Jobs.Kind.LIVE) watching = false
                maybeStop()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun watch(s: AppSettings) {
        val fired = HashSet<String>()
        if (Holidays.stale(Market.today())) runCatching { Holidays.refresh() }
        val b = com.optionslab.app.data.Broker
        if (b.configured && !b.loggedIn) Notifier.post(this, 2005, Notifier.SCHEDULE, "Log in to Zerodha for today",
            "Yesterday's session ended at 06:00. Open More → Zerodha and log in before the 11:00 entry.", "broker")
        // Market hours, and a quarter-hour past the close while a strategy run is still open,
        // so its exit-time square-off and any retried exits are seen through.
        while (Market.isTradingDay() && (Market.minuteNow() <= Market.CLOSE ||
                (Market.minuteNow() <= Market.CLOSE + 15 && com.optionslab.app.data.Strategies.anyRunning()))) {
            Heartbeat.beat(this)
            if (Market.minuteNow() < Market.OPEN) {
                show("Market watch", "Waiting for the 09:15 open")
                delay(30_000)
                continue
            }
            val t = Tasks.watchTick(this, s, fired)
            Tasks.publish(Tasks.LiveState(true, t.title, t.progress / 100f, System.currentTimeMillis()))
            show(t.title, t.lines.joinToString("\n").ifEmpty { "Waiting for prints" }, t.progress)
            // While an ORB position is open its stop, target and 15:10 exit are checked every 15 s, not once a minute.
            val next = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < next) {
                val holding = PositionCards.anyOpen || runCatching { com.optionslab.app.data.OrbArms.holding() }.getOrDefault(false)
                // With the live stream up, Zerodha cards move every 3 s from ticks alone (no network).
                val streaming = com.optionslab.app.data.KiteStream.status.value == com.optionslab.app.data.KiteStream.Status.LIVE
                if (holding && streaming) {
                    val until = System.currentTimeMillis() + 15_000
                    while (System.currentTimeMillis() < until) { delay(3_000); runCatching { PositionCards.tickLive(this) } }
                } else delay(if (holding) 15_000 else next - System.currentTimeMillis())
                if (holding) {
                    runCatching {
                        com.optionslab.app.data.Paper.tick().let { Tasks.paperEventsPublic(this, it) }
                        com.optionslab.app.data.OrbArms.priceCheckOnly()
                        com.optionslab.app.data.Protections.tick()
                    }
                    runCatching { PositionCards.refresh(this) }
                }
                Heartbeat.beat(this)
            }
        }
    }

    @Synchronized
    private fun maybeStop() {
        if (running.values.none { it.isActive }) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopEverything() {
        if (kotlinx.coroutines.runBlocking { runCatching { com.optionslab.app.data.Strategies.anyRunning() || com.optionslab.app.data.OrbArms.holding() }.getOrDefault(false) })
            Notifier.post(this, 2013, Notifier.APPROVAL, "Strategies are no longer being watched",
                "A strategy run is open. Its stops and targets are only checked while the watch or the Strategies page is running.", "strategy")
        running.values.forEach { it.cancel() }
        running.clear()
        Tasks.publish(Tasks.LiveState(false))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Used only when the service may not be started (no exact-alarm permission). */
class FallbackWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val k = runCatching { Jobs.Kind.valueOf(inputData.getString(Jobs.EXTRA_KIND) ?: "") }.getOrNull() ?: return Result.failure()
        val s = AppSettings.load()
        return try {
            when (k) {
                Jobs.Kind.TICKET -> Tasks.ticket(applicationContext, s)
                Jobs.Kind.SETTLE -> Tasks.settle(applicationContext, s)
                Jobs.Kind.HARVEST -> try {
                    Tasks.harvest(applicationContext, s) { stage, p -> Tasks.publish(Tasks.LiveState(true, stage, p, System.currentTimeMillis())) }
                } catch (e: Exception) {
                    SecurePrefs.put("harvest.last", "${Market.today()}: did not complete (${e.message})")
                    throw e
                } finally { Tasks.publish(Tasks.LiveState(false)) }
                Jobs.Kind.REMIND -> Tasks.remind(applicationContext, s)
                Jobs.Kind.LIVE -> Unit
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
