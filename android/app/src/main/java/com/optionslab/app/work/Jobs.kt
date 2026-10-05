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
import kotlinx.coroutines.job
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
                Notifier.post(context, 2010, Notifier.APPROVAL, "Order watch is off", "Tap to start watching your orders and strategies.", "almanac", setting = "schedule.permissions")
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
     * One named step of the watch: what it is doing is shown to [Heartbeat] (so a stuck watch can say what it waits on),
     * and a failure is kept, never thrown (one bad step must not end a pass). Names are the app's own words, never data.
     * A cancellation is passed on only when the pass itself was cancelled; one from inside the step (a timeout that
     * escaped it) is kept like any failure, so the steps after it still run.
     */
    private suspend inline fun step(name: String, block: () -> Unit) {
        Heartbeat.stepBegin(name)
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!passActive()) throw e
            stepFailed(name, e)
        } catch (e: Throwable) {
            stepFailed(name, e)
        } finally {
            Heartbeat.stepEnd()
        }
    }

    /** Is the coroutine running this pass still active (false: it was cancelled, and a cancellation must go up)? */
    private suspend fun passActive(): Boolean = kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive ?: true

    /** When each step last failed: the diary gets one line a minute per step at most. */
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** A step that threw: the diary gets where and the exception's class only, never its message (it can carry a URL or a token). */
    fun stepFailed(name: String?, e: Throwable) {
        val now = System.currentTimeMillis()
        val key = name ?: "pass"
        val prev = failedAt[key]
        if (prev != null && now - prev < 60_000) return
        failedAt[key] = now
        Heartbeat.diary(com.optionslab.ira.WatchHealth.failure(name, e.javaClass.name))
    }

    // ---- network warm-ups, off the watch's path ----------------------------------------------------------------------

    /**
     * The relay's SSH connect (15 s when the server does not answer, as on 5 Oct: every connect timing out at port 22)
     * and the order route's warm-up ran INSIDE every pass, before the stops, in Paper mode too: with the relay down each
     * pass waited on them. They run here instead, one at a time, never awaited by the watch, and a relay that fails is
     * tried again after 2, 4, 8, then every 10 minutes (not every minute). An order still connects the relay itself.
     */
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var warmJob: Job? = null
    @Volatile private var relayFails = 0
    @Volatile private var relayTriedAt = 0L

    private fun warmRoutes(s: AppSettings) {
        if (warmJob?.isActive == true) return
        warmJob = warmScope.launch {
            val m = com.optionslab.app.data.Market
            if (m.isOpen() && com.optionslab.app.data.Relay.enabled) {
                val now = System.currentTimeMillis()
                if (now - relayTriedAt >= com.optionslab.ira.WatchHealth.warmGapMs(relayFails)) {
                    relayTriedAt = now
                    val ok = runCatching { com.optionslab.app.data.Relay.warm() }.getOrDefault(false)
                    relayFails = if (ok) 0 else relayFails + 1
                }
            }
            // A bot's entry or exit then finds a pooled connection through the relay and a fresh static-IP reading, not handshakes.
            if (s.live && m.isOpen() && com.optionslab.app.data.Broker.loggedIn) runCatching { com.optionslab.app.data.Broker.warmOrderRoute() }
        }
    }

    // ---- the words-only lane -----------------------------------------------------------------------------------------

    /**
     * Jarvis's words-only checks (news, patterns, coaching, reviews: they say things or ask first; none places, changes
     * or closes an order by itself) read the news, candles and Zerodha over the network. They ran inside the pass, in
     * front of the stops; now they run in a lane of their own, one round at a time, never awaited by the watch: a slow
     * feed delays a remark, never a stop or the dead-man's beat.
     */
    @Volatile private var wordsJob: Job? = null
    @Volatile private var wordsSince = 0L
    @Volatile private var wordsStuckTold = 0L
    @Volatile private var wordsStep: String? = null
    /** When the slow group of words last ran ([com.optionslab.ira.WordsPace]). */
    @Volatile private var wordsSlowAt = 0L
    /** The words lane's pace at its last round (true = quiet) and when that was - for the diagnostics' "Battery:" line. */
    @Volatile private var wordsQuietLast = false
    @Volatile private var wordsPaceAt = 0L

    /** The words lane's pace now: true quiet, false every round, null when it has not run in the last 5 minutes. */
    fun wordsQuietNow(): Boolean? =
        if (wordsPaceAt > 0L && System.currentTimeMillis() - wordsPaceAt in 0L..5 * 60_000L) wordsQuietLast else null

    /**
     * Battery (round 2): the screen off and nothing held or armed - the words lane's slow group and the news go slower
     * ([com.optionslab.ira.WordsPace]). Anything unknown counts as not quiet: the pace stays as before.
     */
    private suspend fun wordsQuiet(): Boolean {
        val ctx = com.optionslab.app.ira.IraHub.appContext() ?: return false
        val screen = runCatching { ctx.getSystemService(android.os.PowerManager::class.java)?.isInteractive }.getOrNull() ?: true
        if (screen) return false
        val held = PositionCards.anyOpen || com.optionslab.app.data.OrbArms.holdingHint || com.optionslab.app.data.Strategies.runningHint ||
            runCatching { Ledger.openTicket() != null }.getOrDefault(true) ||
            runCatching { com.optionslab.app.data.Paper.state.positions.any { it.quantity != 0 } }.getOrDefault(true)
        if (held) return false
        val pine = runCatching { com.optionslab.app.data.PineScripts.items.value.any { it.auto.on } }.getOrDefault(true)
        // The arms' lock may be held by their tick across a network read: a short wait, else "unknown" (not quiet).
        val orb = if (pine) true else kotlinx.coroutines.withTimeoutOrNull(2_000) {
            runCatching { com.optionslab.app.data.OrbArms.anyArmed() }.getOrNull()
        }
        return com.optionslab.ira.WordsPace.quiet(screenOn = false, held = false, armed = orb)
    }

    private fun wordsLane(scope: CoroutineScope) {
        if (wordsJob?.isActive == true) {
            // One round stuck for 5 minutes is worth a line in the diary (once per round), not a stop.
            if (System.currentTimeMillis() - wordsSince > 5 * 60_000 && wordsStuckTold != wordsSince) {
                wordsStuckTold = wordsSince
                Heartbeat.diary("Jarvis's words-only checks have waited over 5 min on ${wordsStep ?: "a network call"} (stops are not affected)")
            }
            return
        }
        wordsSince = System.currentTimeMillis()
        wordsJob = scope.launch(Dispatchers.IO) {
            com.optionslab.app.data.Broker.passBegin()
            try { wordsSteps() } finally { com.optionslab.app.data.Broker.passEnd(); wordsStep = null }
        }
    }

    private suspend inline fun word(name: String, block: () -> Unit) {
        wordsStep = name
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Passed on only when the lane itself was cancelled; one from inside the check is kept, the round goes on.
            if (!passActive()) throw e
            stepFailed("Jarvis $name", e)
        } catch (e: Throwable) {
            stepFailed("Jarvis $name", e)
        }
    }

    private suspend fun wordsSteps() {
        // The safety words first in each round (a position eating the loss limit, MIS before 15:10, the 14:45 health
        // check, the expiry heads-up, the feed or the relay stopped, overtrading, a bot misbehaving): never behind a slow
        // pattern, news or candle read.
        // Jarvis: one position losing a big share of Boss's daily loss limit, or a sold option mostly decayed (words only).
        word("heads-up") { com.optionslab.app.ira.IraCoach.headsUpWatch() }
        // Jarvis: open Zerodha MIS positions named at 15:10, before Zerodha's own square-off (words only).
        word("MIS word") { com.optionslab.app.ira.IraCoach.misWatch() }
        // Jarvis: 14:45 on a day a position expires, the position health check in the chat and a few words (counts only).
        word("position health") { com.optionslab.app.ira.IraCoach.healthWatch() }
        // Jarvis: the 14:55 expiry heads-up; live prices that stopped.
        word("expiry preview") { com.optionslab.app.ira.IraHub.expiryPreview() }
        word("feed watch") { com.optionslab.app.ira.IraHub.feedWatch() }
        // Jarvis: the static-IP relay server not answering is told before a login or an order fails on it.
        word("relay watch") { com.optionslab.app.ira.IraCoach.relayWatch() }
        // Jarvis: too many trades too fast.
        word("overtrading") { com.optionslab.app.ira.IraCoach.overtradeWatch() }
        // Battery (round 2): with the screen off and nothing held or armed, the slow group below runs every 3 minutes and
        // the news every 10 (every 20 until Boss asks about the news that day, round 6); the safety words above and the time-bound checks run every round as before.
        val quiet = wordsQuiet()
        val nowMs = System.currentTimeMillis()
        wordsQuietLast = quiet; wordsPaceAt = nowMs
        val slow = com.optionslab.ira.WordsPace.slowDue(quiet, nowMs, wordsSlowAt)
        if (slow) wordsSlowAt = nowMs
        // Battery (round 6): quiet and no news question today, every 20 minutes (anything held: every round, as before).
        val newsDue = com.optionslab.ira.WordsPace.newsDueUnasked(quiet, com.optionslab.app.ira.IraHub.newsAskedToday(), nowMs,
            com.optionslab.app.ira.IraHub.state.value.newsAt?.toEpochMilli())
        // Jarvis: a strategy of Boss's behaving unusually against its tested record - told once a day without amounts,
        // stopping it asked first (reads the app's own books, every five minutes at most).
        word("bot review") { com.optionslab.app.ira.IraBots.watch() }
        // Jarvis: every 15 minutes in market hours, Jarvis looks for a pattern worth a strategy and notifies it.
        word("pattern check") { com.optionslab.app.ira.IraHub.backgroundCheck() }
        // Jarvis: the news every 5 minutes, judged for your arms and positions.
        if (newsDue) word("news") { com.optionslab.app.ira.IraHub.newsWatch() }
        // Jarvis: the candle-pattern expert at each 5- and 15-minute close; a qualifying pattern becomes a trade to approve.
        word("candle expert") { com.optionslab.app.ira.IraHub.expertWatch() }
        word("ORB coach") { com.optionslab.app.ira.IraCoach.orbWatch() }
        word("moments") { com.optionslab.app.ira.IraCoach.momentsWatch() }
        // Jarvis: on an index's expiry day, the straddle's decay, spot against max pain and the last hour, at set times.
        word("expiry watch") { com.optionslab.app.ira.IraCoach.expiryWatch() }
        word("VIX") { com.optionslab.app.ira.IraCoach.vixWatch() }
        // Jarvis: a sharp move within minutes - what coincided with it (headlines, VIX, the other indices), timing only.
        word("sharp move") { com.optionslab.app.ira.IraCoach.sharpMoveWatch() }
        // Jarvis: the opening gap plan.
        word("gap") { com.optionslab.app.ira.IraCoach.gapWatch() }
        // Jarvis: not logged in to Zerodha a few minutes before the open while something needs it - said once.
        word("login watch") { com.optionslab.app.ira.IraCoach.loginWatch() }
        // Jarvis: Boss's usual market question at its usual hour, answered unasked (once a day each).
        word("usual question") { com.optionslab.app.ira.IraCoach.usualWatch() }
        // Jarvis: the morning plan - the paper arms fitted to the market's regime.
        word("day plan") { com.optionslab.app.ira.IraCoach.dayPlanWatch() }
        // Jarvis: his own plan for the day - made each morning, worked through at each item's time (words, study, paper only).
        word("agenda") { com.optionslab.app.ira.IraAgenda.watch() }
        // Jarvis: Boss's goals over days - close, broken or met is told once a day.
        if (slow) word("goals") { com.optionslab.app.ira.IraGoals.watch() }
        // Jarvis: Boss's own words (a rule he asked me to remember, his trade goal) against today's trades - pointed out
        // once each, gently, on an unlocked phone only (words only).
        if (slow) word("Boss's rules") { com.optionslab.app.ira.IraCoach.wordsWatch() }
        // Jarvis: the paper tests - what held up is brought to Boss, what failed is offered off (asked first).
        if (slow) word("paper tests") { com.optionslab.app.ira.IraExpert.watch() }
        // Jarvis: the day's target reached; a trade of yours going nowhere is offered a close (asked first).
        if (slow) word("day target") { com.optionslab.app.ira.IraJournal.targetWatch() }
        if (slow) word("stale trades") { com.optionslab.app.ira.IraJournal.staleWatch() }
        // Jarvis: each bot's switch and whether it ended the day down or up (names and signs only) - a record only.
        if (slow) word("bot switches") { com.optionslab.app.ira.IraBots.noteSwitches() }
        // Jarvis: the market alerts found in this round said as one line - one per move, a few an hour (last, so it
        // carries this round's alerts).
        word("airtime") { com.optionslab.app.ira.IraAirtime.flush() }
    }

    /**
     * The steps that protect money: fills, the daily loss limit, bot exits, stops and targets,
     * the expiry square-off and strategy risk. Run first in every pass, so a slow quote or
     * chart fetch never holds them up - and since 5 Oct nothing here waits on the relay or on Jarvis's words.
     */
    private suspend fun riskSteps(context: Context, s: AppSettings, lanes: CoroutineScope?) {
        // Zerodha's live price stream (Live mode, logged in, market hours): starts its own loop, never waits on it.
        step("live price stream") { com.optionslab.app.data.KiteStream.ensure() }
        // The static-IP relay and the order route: connected ahead of the first order, in the background ([warmRoutes]).
        warmRoutes(s)
        // Paper account (also used by paper strategy runs in LIVE mode): resting orders fill, MIS squares off, expiries settle.
        step("paper orders") { paperEvents(context, com.optionslab.app.data.Paper.tick()) }
        // One daily loss limit over every bot: past it, all of them sell and stop for the day.
        step("daily loss limit") { com.optionslab.app.data.LossBreaker.check(context) }
        // The ORB paper arms: manage open positions, then decide on the last completed 5-minute bar.
        step("ORB arms") { com.optionslab.app.data.OrbArms.tick() }
        // Pine scripts set to auto-trade: decide on each completed candle, sell at 15:15.
        step("Pine scripts") { com.optionslab.app.data.PineAuto.tick() }
        // Stops, trailing stops and targets: one exit filled cancels the other; trails move up.
        step("stops and targets") { com.optionslab.app.data.Protections.tick() }
        // Expiry day, 15:05: close every option position expiring today (paper and live, all products).
        step("expiry square-off") { com.optionslab.app.data.ExpirySquareOff.maybeRun(context, s) }
        // Strategy Module: schedules, prices, per-leg and basket risk, exits.
        step("strategies") {
            val bad = com.optionslab.app.security.Integrity.compromised(com.optionslab.app.security.Integrity.reportWithin(context, 60_000))
            com.optionslab.app.data.Strategies.tickAll(bad)
        }
        // Jarvis's own trade management after the stops, the square-off and the strategies (their Zerodha reads are
        // bounded, but they never go first).
        // Jarvis: approved news trades - the best price seen, the profit-lock stop moved up, the result recorded.
        if (com.optionslab.app.BuildConfig.JARVIS) step("Jarvis's trades") { com.optionslab.app.ira.IraNewsTrades.tick() }
        // Solo (paper only, Boss's switch): its open trade managed, or the next one looked for.
        if (com.optionslab.app.BuildConfig.JARVIS) step("Solo") { com.optionslab.app.ira.IraSolo.tick() }
        // Jarvis: a position with no stop is offered one (or given one, when Boss switched that on).
        step("stop rescue") { com.optionslab.app.ira.IraHub.rescueWatch() }
        // Jarvis: your own stops trailed up automatically (Boss's switch).
        step("trailing stops") { com.optionslab.app.ira.IraCoach.trailWatch() }
        // Every open position's notification, with its live P&L and a Close button.
        step("position cards") { PositionCards.refresh(context) }
        // The money steps are done: that is a check, whatever the quotes and Jarvis's words below wait on.
        runCatching { Heartbeat.beat(context) }
        // Jarvis's words-only checks in their own lane (outside the service - tests - they run here, in order).
        if (lanes != null) wordsLane(lanes) else wordsSteps()
    }


    /**
     * One pass of the live watch: index levels, the open ticket's live mark and
     * cushion to breakeven, and every price alarm. Risk alerts fire once each.
     */
    suspend fun watchTick(context: Context, s: AppSettings, fired: MutableSet<String>, lanes: CoroutineScope? = null): Tick {
        // One pass: the words-only checks and the notice's P&L line share one Zerodha positions read (dropped on any
        // order, change or cancel); everything that orders still reads fresh.
        com.optionslab.app.data.Broker.passBegin()
        try {
            return watchTickOnce(context, s, fired, lanes)
        } finally {
            com.optionslab.app.data.Broker.passEnd()
            Heartbeat.stepEnd()
        }
    }

    private suspend fun watchTickOnce(context: Context, s: AppSettings, fired: MutableSet<String>, lanes: CoroutineScope?): Tick {
        riskSteps(context, s, lanes)
        Heartbeat.stepBegin("index quotes")
        val open = Ledger.openTicket()
        // The three index quotes at once, each given at most 20 s: a hung feed cannot stall the watch. The
        // Upstox read uses short timeouts and is cancellable mid-read, so the deadline really ends it.
        // Battery (round 2): only the ones something reads this pass - the open ticket's underlying, an enabled alarm
        // on the index, a widget on the home screen. None of them: no read at all (stops and targets never used these).
        val wanted = indexQuotesWanted(context, open?.row?.ticket?.underlying)
        val q = kotlinx.coroutines.coroutineScope {
            wanted.map { sym ->
                async(kotlinx.coroutines.Dispatchers.IO) { runCatching { kotlinx.coroutines.withTimeoutOrNull(20_000) { Market.quote(sym, quick = true) } }.getOrNull() }
            }.mapNotNull { it.await() }.associateBy { it.symbol }
        }
        // The index quotes feed the alarms, the ticket's risk checks and the widget; the ongoing notice itself
        // carries no market data, only the owner's positions and orders.
        val lines = ArrayList<String>()
        var title = WATCH_TITLE
        var progress = -1
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
            Heartbeat.stepBegin("Zerodha quotes and positions")
            val keys = Alarms.all().filter { it.enabled && ':' in it.symbol }.map { it.symbol }.distinct()
            if (keys.isNotEmpty()) runCatching { b.within(20_000) { b.quotes(keys) } }.getOrNull()?.forEach { (k, v) -> prices[k] = v.last }
            // The account's P&L: recorded for the day's curve, and alerted on the owner's levels.
            runCatching { b.within(20_000) { b.passPositionBook() } }.getOrNull()?.takeIf { it.net.isNotEmpty() }?.let { book ->
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
        Heartbeat.stepBegin("chart alarms")
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

    /** The index quotes this watch pass has a reader for (an empty list = none fetched). */
    internal fun indexQuotesWanted(context: Context, ticketUnderlying: String?): List<String> {
        val all = listOf("NIFTY", "BANKNIFTY", "INDIAVIX")
        val alarmed = runCatching { Alarms.all().filter { it.enabled }.map { it.symbol }.toSet() }.getOrDefault(all.toSet())
        val widget = runCatching { com.optionslab.app.widget.IraWidget.placed(context) }.getOrDefault(true)
        return all.filter { sym -> sym == ticketUnderlying || sym in alarmed || (widget && sym != "INDIAVIX") }
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
 *
 * While the watch runs (2026-10-05): it is START_STICKY, so if Android ends the app's process Android starts the
 * service again and the watch picks up (in market hours) without waiting for the five-minute dead-man check; its own
 * pulse ([Heartbeat.pulse]) tells a watch that is alive but waiting from one that is gone; and every end - the close,
 * Boss's stop, Android's time limit, a refused foreground, a destroyed service, a throw, a process that died under
 * it - goes to the diagnostics diary (class names and the app's own words only).
 */
class WatchService : Service() {
    companion object {
        const val STOP = "com.optionslab.app.WATCH_STOP"
        /** The day a watch was last started and has not been seen to end: still set at the next start = the process died. */
        private const val RUN_DAY = "watch.run.day"
        /**
         * Held while [RUN_DAY] is set at a watch's start and cleared at its end, so the two never interleave: an old run's
         * end landing after a new run's start would clear the new run's day (and its process death would go untold).
         * Process-wide (not per service): an old service's run can still be ending when Android starts a new one.
         */
        private val runDayLock = Any()
        /** The watch run that set [RUN_DAY] last (written under [runDayLock]); only that run's end clears it. */
        @Volatile private var watchRun = 0L
        /**
         * The last run seen to end (Boss's stop, Android's time limit, a destroyed service) before its own end has cleared
         * [RUN_DAY]: a start meanwhile does not take that day for a process that died.
         */
        @Volatile private var endSeenRun = 0L
        /** Tests run many services in one process: the run count starts again as a new process would. */
        internal fun resetRunsForTest() { synchronized(runDayLock) { watchRun = 0L; endSeenRun = 0L } }
        /** The watch's check pace now in seconds (30 before the open, 15 with a position open, 60 otherwise; 0 = not running), for the battery line. */
        @Volatile var stepSec: Int = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = java.util.concurrent.ConcurrentHashMap<Jobs.Kind, Job>()
    @Volatile private var watching = false
    /** Why the watch is being ended from outside its loop (Boss's stop, Android's time limit, a refused foreground). */
    @Volatile private var endWhy: Pair<com.optionslab.ira.WatchHealth.End, String?>? = null

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
        } catch (e: Exception) {
            // Not allowed now (e.g. the dataSync budget is spent, or a start from the background while Android
            // battery-optimizes the app): say so and stop cleanly - a service started in the foreground that never
            // calls startForeground is killed by the system.
            val battery = Heartbeat.batteryRestricted(this) == true
            Notifier.post(this, 2011, Notifier.APPROVAL, "IraAlgo could not run in the background",
                "Open the app to continue: $title" + if (battery) ". " + com.optionslab.ira.WatchHealth.BATTERY_ASK else "", "almanac", setting = "schedule.permissions")
            if (watching) {
                endWhy = com.optionslab.ira.WatchHealth.End.FOREGROUND_REFUSED to null
                Heartbeat.diary("Android refused the foreground (${com.optionslab.ira.WatchHealth.cleanClass(e.javaClass.name)})" +
                    if (battery) " · battery OPTIMIZED" else "")
            }
            running.values.forEach { it.cancel() }
            running.clear()
            watching = false
            stopSelf()
            return false
        }
    }

    /** Android 15: a time-limited foreground service must stop when told, or the app is killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val type = when (fgsType) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC -> "dataSync"
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE -> "specialUse"
            else -> "type $fgsType"
        }
        Heartbeat.diary("Android's time limit reached for the $type foreground service")
        if (endWhy == null) endWhy = com.optionslab.ira.WatchHealth.End.ANDROID_TIME_LIMIT to type
        Notifier.post(this, 2012, Notifier.APPROVAL, "Background watch stopped by Android",
            "The system's time limit for background work was reached. Open IraAlgo to keep strategies and alerts checked.", "almanac",
            setting = "schedule.permissions")
        stopEverything()
    }

    /** Sticky while the watch runs: Android brings the service back if it ends the app's process. */
    private fun stickiness(): Int = if (watching) START_STICKY else START_NOT_STICKY

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, always: the system requires it within seconds.
        // No intent: Android restarted this sticky service after ending the app's process. In market hours that is the
        // watch coming back; otherwise there is nothing to resume (one-shot jobs are not sticky).
        val byAndroid = intent == null
        val k = if (byAndroid && !com.optionslab.app.BuildConfig.GOLD && Jobs.watchDue()) Jobs.Kind.LIVE
            else runCatching { Jobs.Kind.valueOf(intent?.getStringExtra(Jobs.EXTRA_KIND) ?: "") }.getOrNull()
        if (byAndroid && k == null && running.isEmpty()) {
            // Past the watch's hours: nothing to resume. Not a foreground start (it would be refused from the background
            // and post a needless notice): simply end.
            Heartbeat.diary("Android restarted the watch service after the market hours: nothing to resume")
            stopSelf(); return START_NOT_STICKY
        }
        if (k == Jobs.Kind.LIVE) watching = true   // show() then declares the specialUse type
        // Refused: show() stopped the service, so no job is launched to run on after stopSelf.
        if (!show("IraAlgo", "Starting…")) return START_NOT_STICKY
        if (intent?.action == STOP) {
            if (endWhy == null) endWhy = com.optionslab.ira.WatchHealth.End.STOPPED_BY_BOSS to null
            stopEverything(); return START_NOT_STICKY
        }
        // Paper trading needs no Zerodha account: only the Zerodha-only jobs stop when none is linked.
        if (!com.optionslab.app.data.Broker.linked && k != null && k != Jobs.Kind.LIVE) { stopEverything(); return START_NOT_STICKY }
        if (k == null) { maybeStop(); return stickiness() }
        if (running[k]?.isActive == true) return stickiness()
        val session = intent?.getStringExtra(Jobs.EXTRA_SESSION)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: Market.today()
        val run = if (k == Jobs.Kind.LIVE) noteStart(byAndroid) else 0L
        // Registered before it runs (LAZY, started below): a maybeStop() in between never sees the service idle and stops it.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val s = AppSettings.load()
            var end: Pair<com.optionslab.ira.WatchHealth.End, String?>? = null
            try {
                when (k) {
                    Jobs.Kind.LIVE -> { watch(s, run); end = com.optionslab.ira.WatchHealth.End.MARKET_CLOSED to null }
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
            } catch (e: Throwable) {
                // Throwable, not only Exception: an Error left here would reach the app's crash handler, which ends the
                // whole process - and the watch with it - with no word of why.
                if (k == Jobs.Kind.LIVE) end = com.optionslab.ira.WatchHealth.End.CRASHED to e.javaClass.name
                if (k == Jobs.Kind.HARVEST) {
                    // No notice, as before: the harvest line says so, and WorkManager retries it (it resumes
                    // where this stopped) until the session's retry budget is spent.
                    SecurePrefs.put("harvest.last", "$session: did not complete (${Tasks.reason(e)})")
                    if (Tasks.harvestFailed(session) < Tasks.HARVEST_TRIES) runCatching { Jobs.enqueueHarvest(this@WatchService, session, manual = false) }
                } else runCatching {
                    Notifier.post(this@WatchService, 2900 + k.ordinal, Notifier.SCHEDULE,
                        "${k.name.lowercase().replaceFirstChar { it.uppercase() }} did not complete",
                        "It stopped with ${Tasks.reason(e)}. Open IraAlgo to check.", "almanac")
                }
            } finally {
                if (k == Jobs.Kind.LIVE) {
                    // Cancelled with no reason given: Android destroyed the service under the watch.
                    val why = end ?: endWhy ?: (com.optionslab.ira.WatchHealth.End.SERVICE_DESTROYED to null)
                    // Seen to end: not a process death. Cleared - on disk, synchronously - BEFORE the "ended" line, so
                    // whoever sees the end in the diary (the owner, the next start) also sees the day cleared; and only
                    // by the run that set it, never over a newer run's day.
                    val current = synchronized(runDayLock) { run == watchRun }
                    endRun(run)
                    Heartbeat.diary(com.optionslab.ira.WatchHealth.ended(why.first, why.second))
                    // A newer watch started meanwhile: its pulse and its "running" state are left alone.
                    if (current) {
                        Heartbeat.pulseStopped()
                        Tasks.publishWatch(Tasks.LiveState(false))
                    }
                } else if (k == Jobs.Kind.HARVEST) Tasks.publish(Tasks.LiveState(false))
                // Only this run's own entry: a new start after a stop may already have put its job here.
                running.remove(k, coroutineContext.job)
                if (k == Jobs.Kind.LIVE && running[k]?.isActive != true) watching = false
                maybeStop()
            }
        }
        running[k] = job
        job.start()
        return stickiness()
    }

    /**
     * The watch starts: a start after a watch that was never seen to end means the app's process ended under it.
     * Returns this run's number, which its end hands to [endRun].
     */
    private fun noteStart(byAndroid: Boolean): Long {
        endWhy = null
        val run = synchronized(runDayLock) { ++watchRun }
        runCatching {
            val today = Market.today().toString()
            val crash = java.io.File(filesDir, com.optionslab.app.IraAlgoApp.CRASH_FILE).exists()
            synchronized(runDayLock) {
                // The run before this one was seen to end (its own end may not have cleared the day yet): not a process death.
                val runDay = if (run > 1 && endSeenRun == run - 1) null else SecurePrefs.getString(RUN_DAY)
                com.optionslab.ira.WatchHealth.restart(runDay, today, crash, byAndroid)?.let { Heartbeat.diary(it) }
                if (run == watchRun) SecurePrefs.put(RUN_DAY, today)
            }
            val battery = Heartbeat.batteryRestricted(this) == true
            Heartbeat.diary("started" + if (battery) " · battery OPTIMIZED: Android may stop the watch (set IraAlgo's battery to Unrestricted)" else "")
        }
        return run
    }

    /** The newest watch run is ending, seen here (its own end clears [RUN_DAY] a moment later). */
    private fun markEndSeen() { endSeenRun = watchRun }

    /**
     * The watch run [run] was seen to end: [RUN_DAY] is cleared at once (every read sees it) and written to the vault
     * before this returns. A later run's day is left alone. Should the vault write fail, the cleared map stays in memory
     * and goes to disk with the next write.
     */
    private fun endRun(run: Long) {
        synchronized(runDayLock) { if (run == watchRun) runCatching { SecurePrefs.put(RUN_DAY, null) } }
    }

    private suspend fun watch(s: AppSettings, run: Long) = kotlinx.coroutines.coroutineScope {
        // The service's own pulse: no network, no lock, every 30 s - "alive", whatever a check is waiting on.
        val pulse = launch {
            while (true) { Heartbeat.pulse(); delay(com.optionslab.ira.WatchHealth.PULSE_MS) }
        }
        try {
            watchLoop(s)
        } finally {
            pulse.cancel()
            // Only the newest run's end stops the pulse (an old run ending after a new start leaves the new one's).
            if (synchronized(runDayLock) { run == watchRun }) {
                Heartbeat.pulseStopped()
                stepSec = 0
            }
        }
    }

    private suspend fun watchLoop(s: AppSettings) {
        val fired = HashSet<String>()
        if (Holidays.stale(Market.today())) runCatching { Holidays.refresh() }
        val b = com.optionslab.app.data.Broker
        if (b.configured && !b.loggedIn) Notifier.post(this, 2005, Notifier.SCHEDULE, "Log in to Zerodha for today",
            "Yesterday's session ended at 06:00. Open ${com.optionslab.app.ui.Tab.CABINET.label} → Zerodha and log in before the 11:00 entry.", "broker",
            setting = "broker.login")
        // Market hours, and a quarter-hour past the close while a strategy run is still open,
        // so its exit-time square-off and any retried exits are seen through.
        while (Market.isTradingDay() && (Market.minuteNow() <= Market.CLOSE ||
                (Market.minuteNow() <= Market.CLOSE + 15 && com.optionslab.app.data.Strategies.anyRunning()))) {
            try {
                watchPass(fired)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // One bad pass (a Keystore or disk hiccup, even an Error) must not end the watch: note it and go on.
                // Its own id: 2014 is Heartbeat's "Market watch stopped", which every beat cancels.
                Tasks.stepFailed(Heartbeat.busy, e)
                Heartbeat.stepEnd()
                runCatching { Notifier.post(this, 2018, Notifier.SCHEDULE, "Order watch hiccup", "One pass failed (${e.javaClass.simpleName}); the watch goes on.") }
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
                stepSec = 30
                show(Tasks.WATCH_TITLE, Tasks.WATCH_IDLE)
                delay(30_000)
                return
            }
            // Settings read fresh every pass: the kill switch, Paper / Live and limits changed mid-session take effect at once.
            // Jarvis's words-only checks run beside it in the service's scope, never awaited ([Tasks.watchTick]).
            val t = Tasks.watchTick(this, AppSettings.load(), fired, scope)
            Tasks.publishWatch(Tasks.LiveState(true, t.title, t.progress / 100f, System.currentTimeMillis()))
            show(t.title, t.lines.joinToString("\n").ifEmpty { Tasks.WATCH_IDLE }, t.progress, t.dest)
            // While an ORB position is open its stop, target and 15:10 exit are checked every 15 s, not once a minute.
            val next = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < next) {
                val holding = PositionCards.anyOpen || runCatching { com.optionslab.app.data.OrbArms.holding() }.getOrDefault(false)
                stepSec = if (holding) 15 else 60
                // With the live stream up, Zerodha cards move every 3 s from ticks alone (no network).
                val streaming = com.optionslab.app.data.KiteStream.status.value == com.optionslab.app.data.KiteStream.Status.LIVE
                if (holding && streaming) {
                    val until = System.currentTimeMillis() + 15_000
                    // The cards are for the eye: on a low battery (not charging) they move less often (stops are not affected).
                    val step = Battery.gap(this, 3_000)
                    while (System.currentTimeMillis() < until) { delay(step); runCatching { PositionCards.tickLive(this) } }
                } else delay(if (holding) 15_000 else next - System.currentTimeMillis())
                if (holding) {
                    Heartbeat.stepBegin("15-second stop check")
                    // Each in its own try: a paper or ORB failure never skips the stops and targets.
                    try {
                        try {
                            com.optionslab.app.data.Paper.tick().let { Tasks.paperEventsPublic(this, it) }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Tasks.stepFailed("15-second stop check: paper orders", e)
                        }
                        try {
                            com.optionslab.app.data.OrbArms.priceCheckOnly()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Tasks.stepFailed("15-second stop check: ORB arms", e)
                        }
                        try {
                            com.optionslab.app.data.Protections.tick()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Tasks.stepFailed("15-second stop check: stops and targets", e)
                        }
                    } finally { Heartbeat.stepEnd() }
                    runCatching { PositionCards.refresh(this) }
                }
                Heartbeat.beat(this)
            }
        }
    }

    @Synchronized
    private fun maybeStop() {
        // A job registered but not started yet (LAZY, a moment) counts as running: only finished or cancelled ones do not.
        if (running.values.all { it.isCompleted || it.isCancelled }) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Runs on the main thread (a STOP intent, Android's timeout): nothing here may block. */
    private fun stopEverything() {
        // Seen to end (no lock, no disk: this is the main thread): a start before the run's own end clears the day
        // does not report a process death.
        markEndSeen()
        val harvesting = running.containsKey(Jobs.Kind.HARVEST)
        running.values.forEach { it.cancel() }
        running.clear()
        watching = false
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
        // The watch's job is cancelled below; its own finally writes the diary line (SERVICE_DESTROYED unless a reason was set).
        if (running[Jobs.Kind.LIVE]?.isActive == true) {
            if (endWhy == null) endWhy = com.optionslab.ira.WatchHealth.End.SERVICE_DESTROYED to null
            markEndSeen()
        }
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
