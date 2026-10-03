package com.optionslab.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Holidays
import com.optionslab.app.data.Market
import com.optionslab.app.data.OrbArms
import com.optionslab.app.data.Paper
import com.optionslab.app.data.Strategies
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Three notices on every trading day, always shown (the owner's choice, like approvals):
 *
 *   09:00  morning check   Zerodha session, holidays, today's contracts, what is armed, the kill switch
 *   09:10  login reminder  only if Zerodha is still not logged in
 *   15:45  day report      paper and Zerodha P&L, trades, the ORB arms, anything that went wrong
 *
 * They run in a worker (the contract list can take a while to download), from
 * their own exact alarms, re-armed for the next trading day each time.
 */
object DailyReports {
    enum class Kind(val action: String, val at: LocalTime, val id: Int) {
        MORNING("ol.report.morning", LocalTime.of(9, 0), 2020),
        LOGIN("ol.report.login", LocalTime.of(9, 10), 2021),
        EVENING("ol.report.evening", LocalTime.of(15, 45), 2022),
    }

    fun of(action: String?): Kind? = Kind.entries.firstOrNull { it.action == action }

    private fun intent(context: Context, k: Kind): PendingIntent = PendingIntent.getBroadcast(
        context, 180 + k.ordinal, Intent(context, AlarmReceiver::class.java).setAction(k.action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun scheduleAll(context: Context) = Kind.entries.forEach { schedule(context, it) }

    fun schedule(context: Context, k: Kind) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = intent(context, k)
        am.cancel(pi)
        // JarvisAlgo's morning check runs whether or not Zerodha is set up (it says so); the rest need Zerodha.
        if (!Broker.linked && !(com.optionslab.app.BuildConfig.JARVIS && k == Kind.MORNING)) return
        val now = Market.now()
        var d = now.toLocalDate()
        if (!now.toLocalTime().isBefore(k.at)) d = d.plusDays(1)
        // JarvisAlgo's morning comes every day (a closed day gets its own short morning); the rest only on trading days.
        if (!(com.optionslab.app.BuildConfig.JARVIS && k == Kind.MORNING)) while (!Market.isTradingDay(d)) d = d.plusDays(1)
        val at = d.atTime(k.at).atZone(now.zone).toInstant().toEpochMilli()
        try {
            if (Jobs.canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** The alarm fired: re-arm, then hand the work to a worker. */
    fun fired(context: Context, k: Kind) {
        schedule(context, k)
        if (!Market.isTradingDay() && !(com.optionslab.app.BuildConfig.JARVIS && k == Kind.MORNING)) return
        val req = OneTimeWorkRequestBuilder<ReportWorker>()
            .setInputData(workDataOf("kind" to k.name))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("report.${k.name}", ExistingWorkPolicy.REPLACE, req)
    }

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /**
     * JarvisAlgo's morning on a day with no session (a weekend, a holiday): why it is closed and when it opens, the
     * night's study and overnight news, the coming events, Saturday's report card - shown, noted and said.
     */
    suspend fun closedMorning(context: Context): Pair<String, List<String>> {
        val today = Market.today()
        val holiday = runCatching { Holidays.book().upcoming(today).firstOrNull { it.first == today }?.second }.getOrNull()
        val why = holiday ?: if (today.dayOfWeek.value >= 6) "weekend" else "a market holiday"
        var next = today.plusDays(1)
        while (!Market.isTradingDay(next)) next = next.plusDays(1)
        val lines = ArrayList<String>()
        lines += "• The market is closed today ($why). It opens again ${next.format(DAY)} at 9:15."
        val brief = runCatching { com.optionslab.app.ira.IraStudy.brief() }.getOrDefault(emptyList())
        brief.forEach { lines += "• Study: $it" }
        runCatching { com.optionslab.app.ira.IraEvents.upcoming(4) }.getOrDefault(emptyList()).take(3)
            .forEach { e -> lines += "• " + com.optionslab.ira.Events.line(e, today).removeSuffix(".") }
        if (com.optionslab.app.ira.Automations.on(com.optionslab.app.ira.Automations.Auto.BACKUP)) {
            val last = runCatching { com.optionslab.app.security.SecurePrefs.getString("backup.last")?.let(java.time.LocalDate::parse) }.getOrNull()
            if (com.optionslab.ira.BackupNudge.due(last, today)) lines += "• " + com.optionslab.ira.BackupNudge.say(last)
        }
        val title = "Good morning Boss · market closed today ($why)"
        runCatching { com.optionslab.app.ira.JarvisPopup.show(context, title, lines.take(2).joinToString(" ")) }
        runCatching { com.optionslab.app.ira.JarvisVoice.announce("Good morning, Boss. The market is closed today, $why. It opens again ${next.dayOfWeek.name.lowercase()}." +
            (if (brief.isEmpty()) "" else " From my night's study: " + brief.take(2).joinToString(" ") { com.optionslab.ira.Wake.spoken(it, 1) })) }
        com.optionslab.app.ira.IraHub.note(com.optionslab.ira.Address.boss("Good morning. " + lines.joinToString(" ") { it.removePrefix("• ").trimEnd('.') + "." }))
        // Saturday: the week's report card comes with the morning (not left to the hourly study worker).
        runCatching { com.optionslab.app.ira.IraStudy.reportCardIfDue() }
        return title to lines
    }

    /** 09:00: is everything in place for the day? */
    suspend fun morning(context: Context): Pair<String, List<String>> {
        if (com.optionslab.app.BuildConfig.JARVIS && !Market.isTradingDay()) return closedMorning(context)
        val s = AppSettings.load()
        val lines = ArrayList<String>()
        var bad = 0
        fun ok(yes: Boolean, text: String) { if (!yes) bad++; lines += (if (yes) "✓ " else "✗ ") + text }
        ok(Broker.loggedIn, if (Broker.loggedIn) "Zerodha logged in for today" else "Zerodha not logged in: log in before 09:15 (${com.optionslab.app.ui.Tab.CABINET.label} → Zerodha)")
        if (Holidays.stale(Market.today())) runCatching { Holidays.refresh() }
        ok(!Holidays.stale(Market.today()), "NSE holiday list up to date")
        val contracts = runCatching { Market.contracts().size }.getOrDefault(0)
        ok(contracts > 0, if (contracts > 0) "Today's option contracts loaded ($contracts)" else "Option contracts could not be loaded")
        // Zerodha's own instrument list, read now so the first live order after 09:15 does not wait for it.
        if (Broker.loggedIn) {
            val kite = runCatching { Broker.instruments().size }.getOrDefault(0)
            ok(kite > 0, if (kite > 0) "Zerodha instrument list ready ($kite)" else "Zerodha instrument list could not be read")
        }
        val orb = runCatching { OrbArms.view() }.getOrNull()
        val armed = orb?.arms?.filter { it.armed }?.map { it.arm.label }.orEmpty()
        lines += "• ORB arms: " + if (armed.isEmpty()) "none armed" else armed.joinToString() + if (s.live && s.allowRealOrders) " (LIVE, automatic)" else " (paper)"
        val strat = runCatching { Strategies.all().count { it.def.scheduler?.enabled == true } }.getOrDefault(0)
        lines += "• Strategies armed: $strat" + if (runCatching { Strategies.stoppedToday() }.getOrDefault(false)) " · bot stopped for today" else ""
        ok(!s.guardKill, if (s.guardKill) "Kill switch is ON: every Zerodha order is refused (paper still trades)" else "Kill switch off")
        val carried = Paper.state.positions.count { it.quantity != 0 }
        if (carried > 0) lines += "• Paper positions carried overnight: $carried"
        lines += "• Mode: " + if (s.live) "LIVE (Zerodha)" else "Paper"
        if (com.optionslab.app.BuildConfig.JARVIS) {
            // Jarvis's own checks: the Zerodha connection itself, the static IP, the live prices, the data, its model and voice.
            if (Broker.loggedIn) {
                val f = kotlinx.coroutines.withTimeoutOrNull(10_000) { runCatching { Broker.funds() }.getOrNull() }
                ok(f != null, if (f != null) "Zerodha connection tested: ${rs(f.available).removePrefix("+")} available to trade" else "Zerodha did not answer the connection test")
            } else if (!Broker.configured) lines += "• Zerodha not set up: Paper only"
            com.optionslab.app.data.StaticIp.registered?.let { reg ->
                val st = runCatching { com.optionslab.app.data.StaticIp.status(force = true) }.getOrNull()
                ok(st?.matches == true, if (st?.matches == true) "On your registered static IP $reg" else "Not on your registered static IP $reg: new live positions will be refused")
            }
            runCatching { com.optionslab.app.ira.IraHub.refresh() }
            val ira = com.optionslab.app.ira.IraHub.state.value
            ok(ira.liveMissing.size < 3, if (ira.liveMissing.isEmpty()) "Live prices reaching the app" else "No live prices yet from ${ira.liveMissing.joinToString { it.label }}")
            com.optionslab.app.security.SecurePrefs.getString("harvest.last")?.let { lines += "• Last data harvest: $it" }
            val pine = com.optionslab.app.data.PineScripts.items.value.count { it.auto.on }
            if (pine > 0) lines += "• Pine scripts switched on: $pine"
            lines += "• Jarvis: AI model ${if (com.optionslab.app.ira.IraModel.state.value.status == com.optionslab.app.ira.IraModel.Status.READY) "ready" else "not on the phone"}, " +
                "voice ${if (com.optionslab.app.ira.JarvisVoice.wanted) "on" else "off"}"
            runCatching { com.optionslab.app.ira.IraHub.tradeCheck() }.getOrNull()?.let { v ->
                lines += "• Trade check: " + when (v.level) { com.optionslab.ira.TradeCheck.Level.GO -> "normal"; com.optionslab.ira.TradeCheck.Level.CAREFUL -> "careful"; else -> "don't trade yet" } +
                    v.reasons.filter { it.level != com.optionslab.ira.TradeCheck.Level.GO && !it.text.startsWith("The market opens") }.take(2).joinToString("") { " · " + it.text.removeSuffix(".") }
            }
            runCatching { kotlinx.coroutines.withTimeoutOrNull(15_000) { com.optionslab.app.ira.IraHub.flows() } }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { lines += "• " + com.optionslab.ira.Flows.lines(it).first() }
            com.optionslab.app.ira.IraEvents.upcoming(1).forEach { e -> lines += "• " + com.optionslab.ira.Events.line(e, Market.today()).removeSuffix(".") }
            // Jarvis's own self-check: each part it needs, working or not (a part switched off is not counted).
            run {
                val vs = com.optionslab.app.ira.JarvisVoice.state.value
                val voiceOn = com.optionslab.app.ira.JarvisVoice.wanted
                val mic = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val model = com.optionslab.app.ira.IraModel.state.value.status
                val studied = com.optionslab.app.ira.IraStudy.state.value.at?.isAfter(java.time.Instant.now().minusSeconds(36 * 3600)) == true
                val (battery, charging) = Battery.state(context)
                val parts = listOf(
                    "Voice" to (if (voiceOn) vs.problem == null else null),
                    "Microphone permission" to (if (voiceOn) mic else null),
                    "Spoken replies" to (if (com.optionslab.app.ira.JarvisVoice.muted) null else true),
                    "AI model" to (if (model == com.optionslab.app.ira.IraModel.Status.READY) true else null),
                    "Night study" to studied,
                )
                val sc = com.optionslab.ira.SelfCheck.lines(parts)
                ok(parts.none { it.second == false }, sc.first().removeSuffix("."))
                sc.drop(1).filter { it.endsWith("not working.") }.forEach { lines += "• $it" }
                if (com.optionslab.app.ira.JarvisVoice.muted) lines += "• Jarvis is muted (say \"Jarvis, unmute\" to hear me)"
                // A low battery is not a fault: a note, and the battery saver slows Jarvis's own refreshes.
                if (battery != null && battery <= com.optionslab.ira.BatterySaver.LOW && !charging) lines += "• Battery at $battery%: charge the phone for the trading day"
                // The backup reminder: the records live only on this phone.
                if (com.optionslab.app.ira.Automations.on(com.optionslab.app.ira.Automations.Auto.BACKUP)) {
                    val last = runCatching { com.optionslab.app.security.SecurePrefs.getString("backup.last")?.let(java.time.LocalDate::parse) }.getOrNull()
                    if (com.optionslab.ira.BackupNudge.due(last, Market.today())) {
                        lines += "• " + com.optionslab.ira.BackupNudge.say(last)
                        com.optionslab.app.ira.Automations.acted(com.optionslab.app.ira.Automations.Auto.BACKUP, com.optionslab.ira.BackupNudge.say(last))
                    }
                }
            }
            // After everything is checked: what the night's study and the overnight news say about today.
            val brief = runCatching { com.optionslab.app.ira.IraStudy.brief() }.getOrDefault(emptyList())
            brief.forEach { lines += "• Study: $it" }
            val title = "Good morning Boss · " + if (bad == 0) "we are set for today's trading" else "$bad thing${if (bad > 1) "s" else ""} need you"
            runCatching { com.optionslab.app.ira.JarvisPopup.show(context, title, lines.take(3).joinToString(" · ")) }
            // Listening now: Jarvis says it too.
            runCatching { com.optionslab.app.ira.JarvisVoice.announce("Good morning, Boss. " + if (bad == 0) "We are set for today's trading." else
                "$bad thing${if (bad > 1) "s" else ""} need you: " + lines.filter { it.startsWith("✗") }.joinToString(". ") { it.removePrefix("✗ ") } + "." +
                (if (brief.isEmpty()) "" else " Now my analysis. " + brief.joinToString(" ") { com.optionslab.ira.Wake.spoken(it, 2) } +
                    " That is history, not a promise.")) }
            com.optionslab.app.ira.IraHub.note(com.optionslab.ira.Address.boss("Good morning. " +
                (if (bad == 0) "We are set for today's trading. " else "$bad thing${if (bad > 1) "s" else ""} need you before 09:15. ") +
                lines.joinToString(" ") { it.removePrefix("✓ ").removePrefix("✗ ").removePrefix("• ").trimEnd('.') + "." }))
            return title to lines
        }
        val title = "Morning check · ${Market.today().format(DAY)}" + if (bad == 0) " · all set" else " · $bad to fix"
        return title to lines
    }

    /** 15:45: how the day went. */
    suspend fun evening(context: Context): Pair<String, List<String>> {
        val lines = ArrayList<String>()
        var total = 0.0
        runCatching { Paper.snapshot() }.getOrNull()?.let { sn ->
            val pnl = sn.dayPnl
            if (sn.trades.isNotEmpty() || pnl != 0.0) {
                total += pnl
                lines += "Paper: ${rs(pnl)} · ${sn.trades.size} trade${if (sn.trades.size == 1) "" else "s"}"
                runCatching { com.optionslab.app.data.DailyPnl.record(false, pnl, sn.trades.size) }
            }
            val open = sn.positions.positions.count { it.quantity != 0 }
            if (open > 0) lines += "⚠ Paper positions still open: $open"
        }
        if (Broker.loggedIn) runCatching {
            val book = Broker.positionBook()
            val all = runCatching { Broker.trades() }.getOrDefault(emptyList())
            runCatching { com.optionslab.app.data.TradeBook.recordLive(all) }
            val trades = all.size
            if (book.net.isNotEmpty() || trades > 0) {
                total += book.m2m
                lines += "Zerodha: ${rs(book.m2m)} · $trades trade${if (trades == 1) "" else "s"}"
                runCatching { com.optionslab.app.data.DailyPnl.record(true, book.m2m, trades) }
            }
            val open = book.net.count { it.qty != 0 }
            if (open > 0) lines += "⚠ Zerodha positions carried: $open (NRML)"
        }
        runCatching { OrbArms.view() }.getOrNull()?.arms?.forEach { a ->
            val closed = a.today.filter { !it.open }
            if (closed.isNotEmpty()) lines += "${a.arm.label}: ${closed.size} trade${if (closed.size == 1) "" else "s"} · ${rs(closed.sumOf { (it.grossPnl ?: 0.0) - it.charges })}"
        }
        if (Heartbeat.stalledToday()) lines += "⚠ The market watch stopped at least once today"
        // JarvisAlgo: after the week's last session, the weekly review.
        if (com.optionslab.app.BuildConfig.JARVIS) {
            var d = Market.today().plusDays(1)
            while (!Market.isTradingDay(d)) d = d.plusDays(1)
            if (d.toEpochDay() - Market.today().toEpochDay() > 1 || d.dayOfWeek == java.time.DayOfWeek.MONDAY) runCatching { com.optionslab.app.ira.IraHub.weeklyReview() }
        }
        if (AppSettings.load().guardKill) lines += "⚠ The kill switch is on"
        if (lines.isEmpty()) lines += "No trades today."
        val title = "Day report · ${Market.today().format(DAY)}" + if (lines.first() != "No trades today.") " · ${rs(total)}" else ""
        return title to lines
    }

    fun post(context: Context, k: Kind, title: String, lines: List<String>) =
        Notifier.post(context, k.id, Notifier.APPROVAL, title, lines.joinToString("\n"),
            // Not logged in: the notification opens the Zerodha page, one login and done.
            if (k == Kind.EVENING) "pnl" else if (!Broker.loggedIn && Broker.configured) "broker" else "almanac")
}

class ReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val k = runCatching { DailyReports.Kind.valueOf(inputData.getString("kind") ?: "") }.getOrNull() ?: return Result.failure()
        return try {
            when (k) {
                DailyReports.Kind.MORNING -> DailyReports.morning(applicationContext).let { (t, l) -> DailyReports.post(applicationContext, k, t, l) }
                // The reminder speaks only if the session is still missing.
                DailyReports.Kind.LOGIN -> if (!Broker.loggedIn) DailyReports.post(applicationContext, k, "Log in to Zerodha now",
                    listOf("The market opens at 09:15 and there is no Zerodha session today. Open the app → ${com.optionslab.app.ui.Tab.CABINET.label} → Zerodha."))
                DailyReports.Kind.EVENING -> DailyReports.evening(applicationContext).let { (t, l) -> DailyReports.post(applicationContext, k, t, l) }
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
