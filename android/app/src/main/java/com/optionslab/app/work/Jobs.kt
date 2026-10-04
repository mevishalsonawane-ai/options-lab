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
import androidx.work.ForegroundInfo
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
import kotlinx.coroutines.async
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
 * may start the foreground service a six-hour watch or a long harvest needs:
 * the scheduled harvest runs in that service too. Without that permission the
 * one-shot jobs fall back to WorkManager, which is reliable but may run late
 * (a harvest there may be cut at ten minutes and resumes on its retry) and
 * cannot keep a watch alive all session. "Harvest now" from the app runs in
 * WorkManager, which may take the foreground while the app is open.
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
    /** The session a harvest collects (ISO date), fixed when it is requested: a retry after midnight keeps it. */
    const val EXTRA_SESSION = "session"

    /**
     * The market watch runs on every market day (paper bots need no Zerodha account); the other
     * jobs need a linked Zerodha account.
     */
    // Jarvis keeps the day's data (Upstox's public candles, no login) whether or not Zerodha is linked.
    fun enabled(k: Kind, s: AppSettings) = (k == Kind.LIVE || com.optionslab.app.data.Broker.linked ||
        k == Kind.HARVEST && com.optionslab.app.BuildConfig.JARVIS) && when (k) {
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
        // IraGoldAlgo has one clock of its own and none of IraAlgo's NSE jobs.
        if (com.optionslab.app.BuildConfig.GOLD) { GoldAlarm.schedule(context); return }
        val s = AppSettings.load()
        Kind.entries.forEach { schedule(context, it, s) }
        Heartbeat.schedule(context)
        DailyReports.scheduleAll(context)
        com.optionslab.app.ira.StudyWorker.schedule(context)
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

    /** The harvest in WorkManager: a silent low-importance notice while it runs, no "complete" notice. */
    fun enqueueHarvest(context: Context, session: java.time.LocalDate, manual: Boolean) {
        val req = OneTimeWorkRequestBuilder<FallbackWorker>()
            .setInputData(workDataOf(EXTRA_KIND to Kind.HARVEST.name, "manual" to manual, EXTRA_SESSION to session.toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("job.${Kind.HARVEST.name}", ExistingWorkPolicy.KEEP, req)
    }

    /** Start work in the foreground service; from the UI this is always allowed. */
    fun start(context: Context, k: Kind, manual: Boolean = true) {
        if (k != Kind.LIVE && !com.optionslab.app.data.Broker.linked) return
        val session = Market.today()
        // "Harvest now" runs in WorkManager (the app is open, so it may take the foreground). The scheduled
        // one runs in the service below: an exact alarm may start it, where WorkManager's foreground is refused.
        if (k == Kind.HARVEST && manual) { enqueueHarvest(context, session, manual); return }
        val i = Intent(context, WatchService::class.java).putExtra(EXTRA_KIND, k.name).putExtra("manual", manual)
            .putExtra(EXTRA_SESSION, session.toString())
        try {
            ContextCompat.startForegroundService(context, i)
        } catch (_: Exception) {
            // Not permitted from here (Android 12+ background start). Hand
            // the one-shot jobs to WorkManager; the watch cannot run that way.
            if (k == Kind.LIVE) {
                Notifier.post(context, 2010, Notifier.APPROVAL, "Order watch is off", "Tap to start watching your orders and strategies.", "almanac")
            } else if (k == Kind.HARVEST) {
                enqueueHarvest(context, session, manual)
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
    fun ensureWatch(context: Context) {
        // IraGoldAlgo has no NSE watch: its own always-on service instead.
        if (com.optionslab.app.BuildConfig.GOLD) { GoldService.ensure(context); return }
        if (watchDue()) start(context, Kind.LIVE, manual = false)
    }

    fun stopLive(context: Context) {
        context.startService(Intent(context, WatchService::class.java).setAction(WatchService.STOP))
    }
}

/** Fires at each scheduled instant: re-arm tomorrow's, then run today's. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Off the main thread: settings are a Keystore decrypt and the expiry check parses the instrument list.
        // The service is still started inside the alarm's window, which is what lets it start from the background.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { handle(context, intent) } finally { pending.finish() }
        }
    }

    private fun handle(context: Context, intent: Intent) {
        if (intent.action == Heartbeat.ACTION) { Heartbeat.check(context); return }
        // A command Boss set for this time (any day: it was confirmed when set).
        if (intent.action == com.optionslab.app.ira.IraLater.ACTION) { kotlinx.coroutines.runBlocking { com.optionslab.app.ira.IraLater.fire(context) }; return }
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
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                Jobs.scheduleAll(context)
                // Commands Boss set for a time (Jarvis): their alarm too.
                if (com.optionslab.app.BuildConfig.JARVIS) runCatching { com.optionslab.app.ira.IraLater.schedule(context) }
            }
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
                    val open = if (!com.optionslab.app.data.Market.acceptsOrders()) {
                        Alerts.post(com.optionslab.app.data.Market.CLOSED_FOR_ORDERS, Alerts.Kind.ERROR); emptyList()
                    } else com.optionslab.app.data.Paper.state.positions.filter { it.symbol == symbol && it.quantity != 0 }
                    if (open.isEmpty() && com.optionslab.app.data.Market.acceptsOrders()) Alerts.post("No open paper position in $symbol.", Alerts.Kind.ERROR)
                    for (p in open) {
                        val r = com.optionslab.app.data.Paper.close(p.symbol, p.product)
                        r.orderId?.let { runCatching { com.optionslab.app.data.Strategies.tagOwner("paper:$it", com.optionslab.app.data.Origins.manual("Notification close")) } }
                        Alerts.post(r.message, if (r.ok) Alerts.Kind.SUCCESS else Alerts.Kind.ERROR, if (r.ok) "Paper position closed" else "Could not close")
                    }
                    // An ORB position closed this way is booked and its resting stop taken out now, not on the next pass.
                    runCatching { com.optionslab.app.data.OrbArms.priceCheckOnly() }
                    runCatching { com.optionslab.app.data.Paper.tick() }
                    runCatching { PositionCards.refresh(context) }
                    PositionCards.closedFromShade.value++
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

    // The harvest's progress (the Harvest card reads it) and the watch's, apart: one never shows the other's stage.
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state
    private val _watchState = MutableStateFlow(LiveState())
    val watchState: StateFlow<LiveState> = _watchState

    fun publish(s: LiveState) { _state.value = s }
    fun publishWatch(s: LiveState) { _watchState.value = s }

    /**
     * What a failure notice or the harvest line may say about [e]: never its message, which can carry
     * a response body or an instrument; the app's own network failures are generic sentences already.
     */
    fun reason(e: Throwable): String = when (e) {
        is com.optionslab.app.data.Net.HttpFailure, is com.optionslab.app.data.Net.Offline -> e.message ?: e.javaClass.simpleName
        else -> e.javaClass.simpleName
    }

    /** Real harvest failures for [session] (system stops are not counted), persisted: the retry budget. */
    fun harvestFailed(session: java.time.LocalDate): Int {
        val prior = SecurePrefs.getString("harvest.failures")?.split(":")?.takeIf { it.size == 2 && it[0] == session.toString() }
            ?.get(1)?.toIntOrNull() ?: 0
        SecurePrefs.put("harvest.failures", "$session:${prior + 1}")
        return prior + 1
    }

    /** Tries a harvest gets for one session before it gives up: its first run and two retries. */
    const val HARVEST_TRIES = 3

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
            "Review today's Zerodha order", "SELL ${t.underlying} ${fmtG(t.strike)} PE x${t.lots} is ready. Open Options → Expiry Put, review it and swipe to send - nothing goes until you do.", "ticket")
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

    suspend fun harvest(context: Context, s: AppSettings, session: java.time.LocalDate, onProgress: (String, Float) -> Unit) {
        if (Holidays.stale(Market.today())) runCatching { Holidays.refresh() }
        // Jarvis: FINNIFTY's chain too, and the FINNIFTY and SENSEX index candles (1-minute OHLC, volume and OI for every contract).
        val r = if (com.optionslab.app.BuildConfig.JARVIS) Harvester.run(underlyings = listOf("NIFTY", "BANKNIFTY", "FINNIFTY"), session = session,
            extraIndices = linkedMapOf("FINNIFTY" to "NSE_INDEX|Nifty Fin Service", "SENSEX" to "BSE_INDEX|SENSEX"),
            onProgress = { p -> onProgress(p.stage, if (p.total > 0) p.done.toFloat() / p.total else -1f) })
        else Harvester.run(session = session, onProgress = { p -> onProgress(p.stage, if (p.total > 0) p.done.toFloat() / p.total else -1f) })
        // No notification: the result is shown in More → Data and harvest.
        SecurePrefs.put("harvest.last", "$session: ${r.summary()}")
        if (s.healthAlerts) healthCheck(context, s)
        // The ORB evening replay (TODO A8): the day's bars, beside what the paper arms did. No orders.
        runCatching { com.optionslab.app.data.OrbArms.replayIfDue() }
        // Jarvis: Ira reads the day's candles, learns them and reviews how its patterns did. No orders.
        runCatching { com.optionslab.app.ira.IraHub.evening() }
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

    /** [dest]: where a tap on the card opens - the page that shows what the card is about. */
    data class Tick(val title: String, val lines: List<String>, val progress: Int, val dest: String = "almanac")

    /** The ongoing watch notice: no index levels or other market data, only the owner's orders and positions. */
    const val WATCH_TITLE = "Order watch"
    const val WATCH_IDLE = "Watching your orders and strategies"

    /**
     * The steps that protect money: fills, the daily loss limit, bot exits, stops and targets,
     * the expiry square-off and strategy risk. Run first in every pass, so a slow quote or
     * chart fetch never holds them up.
     */
    private suspend fun riskSteps(context: Context, s: AppSettings) {
        // Zerodha's live price stream (Live mode, logged in, market hours).
        runCatching { com.optionslab.app.data.KiteStream.ensure() }
        // The static-IP relay: connected ahead of the first order, kept alive during market hours.
        if (com.optionslab.app.data.Market.isOpen()) runCatching { com.optionslab.app.data.Relay.warm() }
        // A bot's entry or exit then finds a pooled connection through the relay and a fresh static-IP reading, not handshakes.
        if (s.live && com.optionslab.app.data.Market.isOpen() && com.optionslab.app.data.Broker.loggedIn)
            runCatching { com.optionslab.app.data.Broker.warmOrderRoute() }
        // Paper account (also used by paper strategy runs in LIVE mode): resting orders fill, MIS squares off, expiries settle.
        runCatching { com.optionslab.app.data.Paper.tick() }.getOrNull()?.let { paperEvents(context, it) }
        // One daily loss limit over every bot: past it, all of them sell and stop for the day.
        runCatching { com.optionslab.app.data.LossBreaker.check(context) }
        // The ORB paper arms: manage open positions, then decide on the last completed 5-minute bar.
        runCatching { com.optionslab.app.data.OrbArms.tick() }
        // Pine scripts set to auto-trade: decide on each completed candle, sell at 15:15.
        runCatching { com.optionslab.app.data.PineAuto.tick() }
        // Jarvis: every 15 minutes in market hours, Jarvis looks for a pattern worth a strategy and notifies it.
        runCatching { com.optionslab.app.ira.IraHub.backgroundCheck() }
        // Jarvis: the news every 5 minutes, judged for your arms and positions.
        runCatching { com.optionslab.app.ira.IraHub.newsWatch() }
        // Jarvis: the candle-pattern expert at each 5- and 15-minute close; a qualifying pattern becomes a trade to approve.
        runCatching { com.optionslab.app.ira.IraHub.expertWatch() }
        // Jarvis: approved news trades - the best price seen, the profit-lock stop moved up, the result recorded.
        if (com.optionslab.app.BuildConfig.JARVIS) runCatching { com.optionslab.app.ira.IraNewsTrades.tick() }
        // Solo (paper only, Boss's switch): its open trade managed, or the next one looked for.
        if (com.optionslab.app.BuildConfig.JARVIS) runCatching { com.optionslab.app.ira.IraSolo.tick() }
        // Jarvis: a position with no stop is offered one; the 14:55 expiry heads-up; live prices that stopped.
        runCatching { com.optionslab.app.ira.IraHub.rescueWatch() }
        runCatching { com.optionslab.app.ira.IraHub.expiryPreview() }
        runCatching { com.optionslab.app.ira.IraHub.feedWatch() }
        runCatching { com.optionslab.app.ira.IraCoach.orbWatch() }
        runCatching { com.optionslab.app.ira.IraCoach.vixWatch() }
        // Jarvis: your own stops trailed up automatically; too many trades too fast; the opening gap plan.
        runCatching { com.optionslab.app.ira.IraCoach.trailWatch() }
        runCatching { com.optionslab.app.ira.IraCoach.overtradeWatch() }
        runCatching { com.optionslab.app.ira.IraCoach.gapWatch() }
        // Jarvis: the static-IP relay server not answering is told before a login or an order fails on it.
        runCatching { com.optionslab.app.ira.IraCoach.relayWatch() }
        // Jarvis: not logged in to Zerodha a few minutes before the open while something needs it - said once.
        runCatching { com.optionslab.app.ira.IraCoach.loginWatch() }
        // Jarvis: the morning plan - the paper arms fitted to the market's regime.
        runCatching { com.optionslab.app.ira.IraCoach.dayPlanWatch() }
        // Jarvis: Boss's goals over days - close, broken or met is told once a day.
        runCatching { com.optionslab.app.ira.IraGoals.watch() }
        // Jarvis: the paper tests - what held up is brought to Boss, what failed is offered off (asked first).
        runCatching { com.optionslab.app.ira.IraExpert.watch() }
        // Jarvis: the day's target reached; a trade of yours going nowhere is offered a close (asked first).
        runCatching { com.optionslab.app.ira.IraJournal.targetWatch() }
        runCatching { com.optionslab.app.ira.IraJournal.staleWatch() }
        // Stops, trailing stops and targets: one exit filled cancels the other; trails move up.
        runCatching { com.optionslab.app.data.Protections.tick() }
        // Expiry day, 15:05: close every option position expiring today (paper and live, all products).
        runCatching { com.optionslab.app.data.ExpirySquareOff.maybeRun(context, s) }
        // Strategy Module: schedules, prices, per-leg and basket risk, exits.
        runCatching {
            val bad = com.optionslab.app.security.Integrity.compromised(com.optionslab.app.security.Integrity.reportWithin(context, 60_000))
            com.optionslab.app.data.Strategies.tickAll(bad)
        }
        // Every open position's notification, with its live P&L and a Close button.
        runCatching { PositionCards.refresh(context) }
    }


    /**
     * One pass of the live watch: index levels, the open ticket's live mark and
     * cushion to breakeven, and every price alarm. Risk alerts fire once each.
     */
    suspend fun watchTick(context: Context, s: AppSettings, fired: MutableSet<String>): Tick {
        riskSteps(context, s)
        // The three index quotes at once, each given at most 20 s: a hung feed cannot stall the watch. The
        // Upstox read uses short timeouts and is cancellable mid-read, so the deadline really ends it.
        val q = kotlinx.coroutines.coroutineScope {
            listOf("NIFTY", "BANKNIFTY", "INDIAVIX").map { sym ->
                async(kotlinx.coroutines.Dispatchers.IO) { runCatching { kotlinx.coroutines.withTimeoutOrNull(20_000) { Market.quote(sym, quick = true) } }.getOrNull() }
            }.mapNotNull { it.await() }.associateBy { it.symbol }
        }
        // The index quotes feed the alarms, the ticket's risk checks and the widget; the ongoing notice itself
        // carries no market data, only the owner's positions and orders.
        val lines = ArrayList<String>()
        var title = WATCH_TITLE
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
            if (keys.isNotEmpty()) runCatching { kotlinx.coroutines.withTimeoutOrNull(20_000) { b.quotes(keys) } }.getOrNull()?.forEach { (k, v) -> prices[k] = v.last }
            // The account's P&L: recorded for the day's curve, and alerted on the owner's levels.
            runCatching { kotlinx.coroutines.withTimeoutOrNull(20_000) { b.positionBook() } }.getOrNull()?.takeIf { it.net.isNotEmpty() }?.let { book ->
                com.optionslab.app.data.PnlTracker.record(book.pnl)
                runCatching { com.optionslab.app.data.DailyPnl.record(true, book.m2m, -1) }
                accountPnl = book.pnl
                lines.add(0, "Positions %s".format(if (s.hideAmountsOnLockScreen) "open: ${book.net.count { it.open }}" else "Rs %+,.0f".format(book.pnl)))
                pnlAlerts(context, s, book.pnl)
            }
        }
        runCatching { com.optionslab.app.data.Paper.state.positions.count { it.quantity != 0 } }.getOrDefault(0).takeIf { it > 0 }?.let { n ->
            runCatching { com.optionslab.app.data.Paper.snapshot() }.getOrNull()?.let { snap ->
                val pnl = snap.dayPnl
                runCatching { com.optionslab.app.data.DailyPnl.record(false, pnl, snap.trades.size) }
                // Orders on the same contract net into one position (two arms buying it = one position of 2 lots),
                // so the line says positions and the quantity they hold, not orders.
                val qty = snap.positions.positions.sumOf { kotlin.math.abs(it.quantity) }
                val held = "$n position${if (n == 1) "" else "s"} · qty $qty"
                lines.add(0, "Paper %s".format(if (s.hideAmountsOnLockScreen) "open: $held" else "P&L Rs %+,.0f · $held".format(pnl)))
            }
        }
        // Alarms set from the chart, priced from the chart's own feed (the last 1-minute close): all at once,
        // each given at most 20 s, on the watch's own quick path (never queued behind a chart or backtest load).
        val chartKeys = Alarms.all().filter { it.enabled && it.symbol.startsWith(com.optionslab.app.data.PriceAlarm.CHART) }.map { it.symbol }.distinct()
        kotlinx.coroutines.coroutineScope {
            chartKeys.map { key ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    val sym = key.removePrefix(com.optionslab.app.data.PriceAlarm.CHART)
                    val now = System.currentTimeMillis() / 1000
                    key to runCatching {
                        kotlinx.coroutines.withTimeoutOrNull(20_000) { com.optionslab.app.data.ChartFeed.bars(sym, "1m", now - 3 * 3600, now, quick = true).lastOrNull()?.close }
                    }.getOrNull()
                }
            }.map { it.await() }
        }.forEach { (key, price) -> if (price != null) prices[key] = price }
        checkAlarms(context, prices, fired)
        runCatching {
            com.optionslab.app.widget.IraWidget.publish(context, q["NIFTY"]?.let { it.last to it.changePct },
                q["BANKNIFTY"]?.let { it.last to it.changePct }, accountPnl)
        }
        // A tap opens what the card is about: the expiry ticket, else the positions it lists, else Home.
        val dest = when {
            open != null -> "ticket"
            accountPnl != null || lines.any { it.startsWith("Paper ") } -> "trade"
            else -> "almanac"
        }
        return Tick(title, lines, progress, dest)
    }

    fun paperEventsPublic(context: Context, events: List<com.optionslab.engine.sandbox.SandboxEvent>) = paperEvents(context, events)

    private fun paperEvents(context: Context, events: List<com.optionslab.engine.sandbox.SandboxEvent>) {
        for (e in events) when (e) {
            is com.optionslab.engine.sandbox.SandboxEvent.Fill -> Notifier.orderFilled(context, e.action, e.quantity, e.symbol, e.price, "Paper",
                kotlinx.coroutines.runBlocking { runCatching { com.optionslab.app.data.Strategies.owners()["paper:${e.orderId}"] }.getOrNull() }, e.orderId)
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
                Alarms.markFired(a.id, System.currentTimeMillis())
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

    /** False when Android refused the foreground (the service is then stopping: start nothing). */
    private fun show(title: String, text: String, progress: Int = -1, dest: String = "almanac"): Boolean {
        val n = Notifier.builder(this, Notifier.LIVE, title, text, dest)
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
            return true
        } catch (_: Exception) {
            // Not allowed now (e.g. the dataSync budget is spent): say so and stop cleanly - a service
            // started in the foreground that never calls startForeground is killed by the system.
            Notifier.post(this, 2011, Notifier.APPROVAL, "IraAlgo could not run in the background", "Open the app to continue: $title", "almanac")
            running.values.forEach { it.cancel() }
            running.clear()
            watching = false
            stopSelf()
            return false
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
        // Refused: show() stopped the service, so no job is launched to run on after stopSelf.
        if (!show("IraAlgo", "Starting…")) return START_NOT_STICKY
        if (intent?.action == STOP) { stopEverything(); return START_NOT_STICKY }
        // Paper trading needs no Zerodha account: only the Zerodha-only jobs stop when none is linked.
        if (!com.optionslab.app.data.Broker.linked && k != null && k != Jobs.Kind.LIVE) { stopEverything(); return START_NOT_STICKY }
        if (k == null) { maybeStop(); return START_NOT_STICKY }
        if (running[k]?.isActive == true) return START_NOT_STICKY
        val session = intent?.getStringExtra(Jobs.EXTRA_SESSION)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: Market.today()
        running[k] = scope.launch {
            val s = AppSettings.load()
            try {
                when (k) {
                    Jobs.Kind.LIVE -> watch(s)
                    Jobs.Kind.TICKET -> Tasks.ticket(this@WatchService, s)
                    Jobs.Kind.SETTLE -> Tasks.settle(this@WatchService, s)
                    Jobs.Kind.HARVEST -> Tasks.harvest(this@WatchService, s, session) { stage, p ->
                        Tasks.publish(Tasks.LiveState(true, stage, p, System.currentTimeMillis()))
                        show("Harvesting · $stage", if (p >= 0) "${(100 * p).toInt()}%" else "", if (p >= 0) (100 * p).toInt() else -1)
                    }
                    Jobs.Kind.REMIND -> Tasks.remind(this@WatchService, s)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (k == Jobs.Kind.HARVEST) {
                    // No notice, as before: the harvest line says so, and WorkManager retries it (it resumes
                    // where this stopped) until the session's retry budget is spent.
                    SecurePrefs.put("harvest.last", "$session: did not complete (${Tasks.reason(e)})")
                    if (Tasks.harvestFailed(session) < Tasks.HARVEST_TRIES) runCatching { Jobs.enqueueHarvest(this@WatchService, session, manual = false) }
                } else Notifier.post(this@WatchService, 2900 + k.ordinal, Notifier.SCHEDULE,
                    "${k.name.lowercase().replaceFirstChar { it.uppercase() }} did not complete",
                    "It stopped with ${Tasks.reason(e)}. Open IraAlgo to check.", "almanac")
            } finally {
                if (k == Jobs.Kind.LIVE) Tasks.publishWatch(Tasks.LiveState(false)) else if (k == Jobs.Kind.HARVEST) Tasks.publish(Tasks.LiveState(false))
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
            "Yesterday's session ended at 06:00. Open ${com.optionslab.app.ui.Tab.CABINET.label} → Zerodha and log in before the 11:00 entry.", "broker")
        // Market hours, and a quarter-hour past the close while a strategy run is still open,
        // so its exit-time square-off and any retried exits are seen through.
        while (Market.isTradingDay() && (Market.minuteNow() <= Market.CLOSE ||
                (Market.minuteNow() <= Market.CLOSE + 15 && com.optionslab.app.data.Strategies.anyRunning()))) {
            try {
                watchPass(fired)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // One bad pass (a Keystore or disk hiccup) must not end the watch: note it and go on.
                // Its own id: 2014 is Heartbeat's "Market watch stopped", which every beat cancels.
                Notifier.post(this, 2018, Notifier.SCHEDULE, "Order watch hiccup", "One pass failed (${e.javaClass.simpleName}); the watch goes on.")
                delay(15_000)
            }
        }
        // The watch is over for the day: the live price stream closes with it.
        runCatching { com.optionslab.app.data.KiteStream.stop() }
    }

    /** One pass of the watch loop and the wait until the next one. */
    private suspend fun watchPass(fired: HashSet<String>) {
        run {
            Heartbeat.beat(this)
            if (Market.minuteNow() < Market.OPEN) {
                show(Tasks.WATCH_TITLE, Tasks.WATCH_IDLE)
                delay(30_000)
                return
            }
            // Settings read fresh every pass: the kill switch, Paper / Live and limits changed mid-session take effect at once.
            val t = Tasks.watchTick(this, AppSettings.load(), fired)
            Tasks.publishWatch(Tasks.LiveState(true, t.title, t.progress / 100f, System.currentTimeMillis()))
            show(t.title, t.lines.joinToString("\n").ifEmpty { Tasks.WATCH_IDLE }, t.progress, t.dest)
            // While an ORB position is open its stop, target and 15:10 exit are checked every 15 s, not once a minute.
            val next = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < next) {
                val holding = PositionCards.anyOpen || runCatching { com.optionslab.app.data.OrbArms.holding() }.getOrDefault(false)
                // With the live stream up, Zerodha cards move every 3 s from ticks alone (no network).
                val streaming = com.optionslab.app.data.KiteStream.status.value == com.optionslab.app.data.KiteStream.Status.LIVE
                if (holding && streaming) {
                    val until = System.currentTimeMillis() + 15_000
                    // The cards are for the eye: on a low battery (not charging) they move less often (stops are not affected).
                    val step = Battery.gap(this, 3_000)
                    while (System.currentTimeMillis() < until) { delay(step); runCatching { PositionCards.tickLive(this) } }
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

    /** Runs on the main thread (a STOP intent, Android's timeout): nothing here may block. */
    private fun stopEverything() {
        val harvesting = running.containsKey(Jobs.Kind.HARVEST)
        running.values.forEach { it.cancel() }
        running.clear()
        if (harvesting) Tasks.publish(Tasks.LiveState(false))   // a WorkManager harvest may still be running: its card stays
        Tasks.publishWatch(Tasks.LiveState(false))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        // The non-blocking hints, never the locks: a strategy pass may hold its lock across network calls.
        if (com.optionslab.app.data.Strategies.runningHint || com.optionslab.app.data.OrbArms.holdingHint)
            Notifier.post(this, 2013, Notifier.APPROVAL, "Strategies are no longer being watched",
                "A strategy run is open. Its stops and targets are only checked while the watch or the Strategies page is running.", "strategy")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * The harvest, always; the other one-shot jobs only when the service may not be started (no
 * exact-alarm permission).
 */
class FallbackWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private fun kind() = runCatching { Jobs.Kind.valueOf(inputData.getString(Jobs.EXTRA_KIND) ?: "") }.getOrNull()

    /**
     * The notice shown while this runs in the foreground: the harvest asks for it (a plain worker is
     * stopped at ten minutes), and expedited work shows it on Android 11 and below.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val harvest = kind() == Jobs.Kind.HARVEST
        val n = Notifier.builder(applicationContext, Notifier.LIVE,
            if (harvest) "Harvesting market data" else "IraAlgo", if (harvest) "Collecting today's option chains" else "Running a scheduled job", "almanac")
            .setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false).setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(if (harvest) Notifier.ID_HARVEST else Notifier.ID_HARVEST + 1, n, type)
    }

    override suspend fun doWork(): Result {
        val k = kind() ?: return Result.failure()
        val s = AppSettings.load()
        val session = inputData.getString(Jobs.EXTRA_SESSION)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: Market.today()
        if (k == Jobs.Kind.HARVEST) {
            // A fresh master and ~1500 contracts outlast the ten minutes a plain worker gets. If the
            // foreground is refused (background start not allowed on Android 12+, dataSync budget spent),
            // run anyway: the harvest stores as it goes, so a stopped attempt's rerun resumes where it was.
            try {
                setForeground(getForegroundInfo())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return try {
            when (k) {
                Jobs.Kind.TICKET -> Tasks.ticket(applicationContext, s)
                Jobs.Kind.SETTLE -> Tasks.settle(applicationContext, s)
                Jobs.Kind.HARVEST -> try {
                    Tasks.harvest(applicationContext, s, session) { stage, p -> Tasks.publish(Tasks.LiveState(true, stage, p, System.currentTimeMillis())) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e      // stopped by the system: WorkManager reschedules it, and nothing failed
                } catch (e: Exception) {
                    if (isStopped) throw kotlinx.coroutines.CancellationException("stopped")   // a read cut by the stop: not a failure
                    SecurePrefs.put("harvest.last", "$session: did not complete (${Tasks.reason(e)})")
                    // System stops count toward runAttemptCount; the budget is the session's own count of real failures.
                    return if (Tasks.harvestFailed(session) < Tasks.HARVEST_TRIES) Result.retry() else Result.failure()
                } finally { Tasks.publish(Tasks.LiveState(false)) }
                Jobs.Kind.REMIND -> Tasks.remind(applicationContext, s)
                Jobs.Kind.LIVE -> Unit
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
