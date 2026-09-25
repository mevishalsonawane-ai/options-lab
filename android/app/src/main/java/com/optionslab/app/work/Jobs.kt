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

    fun enabled(k: Kind, s: AppSettings) = when (k) {
        Kind.LIVE -> s.liveWatch
        Kind.REMIND -> s.entryReminder
        Kind.TICKET -> s.autoTicket || (s.prepareRealOrder && com.optionslab.app.data.Broker.configured)
        Kind.SETTLE -> s.autoSettle
        Kind.HARVEST -> s.nightlyHarvest
    }

    /** The next weekday instant for this job, in IST. */
    fun nextRun(k: Kind, from: ZonedDateTime = Market.now()): ZonedDateTime {
        var d = from.toLocalDate()
        if (!from.toLocalTime().isBefore(k.at)) d = d.plusDays(1)
        while (!Market.isWeekday(d)) d = d.plusDays(1)
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
        val i = Intent(context, WatchService::class.java).putExtra(EXTRA_KIND, k.name).putExtra("manual", manual)
        try {
            ContextCompat.startForegroundService(context, i)
        } catch (_: Exception) {
            // Not permitted from here (Android 12+ background start). Hand
            // the one-shot jobs to WorkManager; the watch cannot run that way.
            if (k == Kind.LIVE) {
                Notifier.post(context, 2010, Notifier.SCHEDULE, "Market is open", "Tap to start the live watch.", "almanac")
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

    fun stopLive(context: Context) {
        context.startService(Intent(context, WatchService::class.java).setAction(WatchService.STOP))
    }
}

/** Fires at each scheduled instant: re-arm tomorrow's, then run today's. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val k = runCatching { Jobs.Kind.valueOf(intent.getStringExtra(Jobs.EXTRA_KIND) ?: return) }.getOrNull() ?: return
        Jobs.schedule(context, k)
        val s = AppSettings.load()
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
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    companion object { const val STOP_LIVE = "com.optionslab.app.STOP_LIVE" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == STOP_LIVE) Jobs.stopLive(context)
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
        if (s.prepareRealOrder && com.optionslab.app.data.Broker.loggedIn) Notifier.post(context, 2004, Notifier.RISK,
            "Review today's Zerodha order", "SELL ${t.underlying} ${fmtG(t.strike)} PE x${t.lots} is ready. Open the Ticket page, review it and hold to send - nothing goes until you do.", "ticket")
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
        val r = Harvester.run(onProgress = { p -> onProgress(p.stage, if (p.total > 0) p.done.toFloat() / p.total else -1f) })
        SecurePrefs.put("harvest.last", "${Market.today()}: ${r.summary()}")
        Notifier.post(context, Notifier.ID_HARVEST, Notifier.SCHEDULE, "Harvest complete", r.summary(), "cabinet")
        if (s.healthAlerts) healthCheck(context, s)
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
        checkAlarms(context, q.mapValues { it.value.last }, fired)
        // Sandbox paper account: resting orders fill, MIS squares off at 15:15, expiries settle.
        // Paper account (also used by paper strategy runs in LIVE mode): resting orders fill, MIS squares off, expiries settle.
        runCatching { com.optionslab.app.data.Paper.tick() }.getOrNull()?.let { paperEvents(context, it) }
        // Strategy Module: schedules, prices, per-leg and basket risk, exits.
        runCatching {
            val bad = com.optionslab.app.security.Integrity.compromised(com.optionslab.app.security.Integrity.reportWithin(context, 60_000))
            com.optionslab.app.data.Strategies.tickAll(bad)
        }
        return Tick(title, lines, progress)
    }

    private fun paperEvents(context: Context, events: List<com.optionslab.engine.sandbox.SandboxEvent>) {
        for (e in events) when (e) {
            is com.optionslab.engine.sandbox.SandboxEvent.Fill -> Notifier.post(context, 7000 + (e.orderId.hashCode() and 0x3ff), Notifier.LIVE,
                "Paper ${e.action} filled", "${e.quantity} ${e.symbol} @ %.2f".format(e.price), "trade")
            is com.optionslab.engine.sandbox.SandboxEvent.ExpirySettled -> Notifier.post(context, 7000 + (e.symbol.hashCode() and 0x3ff), Notifier.LIVE,
                "Paper contract settled", "${e.symbol} at %.2f · P&L Rs %+,.0f".format(e.price.toDouble(), e.pnl.toDouble()), "trade")
            is com.optionslab.engine.sandbox.SandboxEvent.SquareOff -> Notifier.post(context, 7000 + (e.symbol.hashCode() and 0x3ff), Notifier.LIVE,
                "Paper MIS squared off", e.symbol, "trade")
            else -> Unit
        }
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
            .addAction(0, "Stop", PendingIntent.getBroadcast(this, 7,
                Intent(this, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.STOP_LIVE),
                PendingIntent.FLAG_IMMUTABLE))
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
            Notifier.post(this, 2011, Notifier.SCHEDULE, "IraAlgo could not run in the background", "Open the app to continue: $title", "almanac")
        }
    }

    /** Android 15: a time-limited foreground service must stop when told, or the app is killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Notifier.post(this, 2012, Notifier.RISK, "Background watch stopped by Android",
            "The system's time limit for background work was reached. Open IraAlgo to keep strategies and alerts checked.", "almanac")
        stopEverything()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, always: the system requires it within seconds.
        val k = runCatching { Jobs.Kind.valueOf(intent?.getStringExtra(Jobs.EXTRA_KIND) ?: "") }.getOrNull()
        if (k == Jobs.Kind.LIVE) watching = true   // show() then declares the specialUse type
        show("IraAlgo", "Starting…")
        if (intent?.action == STOP) { stopEverything(); return START_NOT_STICKY }
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
        val b = com.optionslab.app.data.Broker
        if (b.configured && !b.loggedIn) Notifier.post(this, 2005, Notifier.SCHEDULE, "Log in to Zerodha for today",
            "Yesterday's session ended at 06:00. Open Cabinet → Zerodha and log in before the 11:00 entry.", "broker")
        // Market hours, and a quarter-hour past the close while a strategy run is still open,
        // so its exit-time square-off and any retried exits are seen through.
        while (Market.isWeekday() && (Market.minuteNow() <= Market.CLOSE ||
                (Market.minuteNow() <= Market.CLOSE + 15 && com.optionslab.app.data.Strategies.anyRunning()))) {
            if (Market.minuteNow() < Market.OPEN) {
                show("Market watch", "Waiting for the 09:15 open")
                delay(30_000)
                continue
            }
            val t = Tasks.watchTick(this, s, fired)
            Tasks.publish(Tasks.LiveState(true, t.title, t.progress / 100f, System.currentTimeMillis()))
            show(t.title, t.lines.joinToString("\n").ifEmpty { "Waiting for prints" }, t.progress)
            delay(60_000)
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
        if (kotlinx.coroutines.runBlocking { runCatching { com.optionslab.app.data.Strategies.anyRunning() }.getOrDefault(false) })
            Notifier.post(this, 2013, Notifier.RISK, "Strategies are no longer being watched",
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
                Jobs.Kind.HARVEST -> Tasks.harvest(applicationContext, s) { _, _ -> }
                Jobs.Kind.REMIND -> Tasks.remind(applicationContext, s)
                Jobs.Kind.LIVE -> Unit
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
