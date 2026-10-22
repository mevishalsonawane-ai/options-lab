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
 * and, for Jarvis, one on Sunday evening (18:00): the spoken Sunday review ([com.optionslab.app.ira.IraSundayReview], paper and
 * live apart; a locked phone gets only a "ready" notice) and the paper arms' week ([com.optionslab.ira.ArmWeek]) - said once,
 * kept in the chat; nothing acts - and one after each week's last session (15:47): Jarvis's weekly review
 * ([com.optionslab.ira.WeeklyReview]), kept for the Ira page's card with a "Weekly review ready" notice; nothing acts.
 *
 * They run in a worker (the contract list can take a while to download), from
 * their own exact alarms, re-armed for the next trading day each time.
 */
object DailyReports {
    enum class Kind(val action: String, val at: LocalTime, val id: Int) {
        MORNING("ol.report.morning", LocalTime.of(9, 0), 2020),
        LOGIN("ol.report.login", LocalTime.of(9, 10), 2021),
        EVENING("ol.report.evening", LocalTime.of(15, 45), 2022),
        /** Sundays only, Jarvis only (not IraGoldAlgo): the paper arms' week. */
        WEEK("ol.report.week", LocalTime.of(18, 0), 2023),
        /**
         * Jarvis only (not IraGoldAlgo): after the week's last session by the exchange calendar (Friday, or the last trading
         * day of a week with a holiday), Jarvis's weekly review ([com.optionslab.app.ira.IraWeekly]). One alarm a week.
         */
        WEEKLY("ol.report.weekly", LocalTime.of(15, 47), 2024),
        /**
         * Jarvis only (not IraGoldAlgo), trading days: the danger meter read again once BANKNIFTY's open is in
         * ([dangerAfterOpen]; review of TODO I4, 13 Oct). A chat note only: no notification, nothing acts.
         */
        DANGER("ol.report.danger", LocalTime.of(9, 16), 2025),
    }

    /** The Sunday report runs in Jarvis (not IraGoldAlgo) whether or not Zerodha is set up: the arms trade on paper. */
    private fun weekOn() = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    /** The danger meter's 09:16 re-read: where the morning check reads it and the Pre-trade checks card shows it. */
    internal fun dangerOn() = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    fun of(action: String?): Kind? = Kind.entries.firstOrNull { it.action == action }

    private fun intent(context: Context, k: Kind): PendingIntent = PendingIntent.getBroadcast(
        context, 180 + k.ordinal, Intent(context, AlarmReceiver::class.java).setAction(k.action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** [onlyChanged] (22 Oct, the app's start in the background): only the reports whose time changed ([Jobs.scheduleAll]). */
    fun scheduleAll(context: Context, onlyChanged: Boolean = false) = Kind.entries.forEach { schedule(context, it, onlyChanged) }

    /** [intent] only when Android still holds it (null: never made, or dropped by a force stop or a reboot). */
    private fun intentIfAny(context: Context, k: Kind): PendingIntent? = PendingIntent.getBroadcast(
        context, 180 + k.ordinal, Intent(context, AlarmReceiver::class.java).setAction(k.action),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * When [k]'s alarm is next due (epoch ms), or [com.optionslab.ira.NightStart.OFF] when it is not armed. 22 Oct: the times
     * exactly as [schedule] armed them before, worked out first so an unchanged one can be left as it is.
     */
    internal fun nextAt(k: Kind): Long {
        val off = com.optionslab.ira.NightStart.OFF
        if (k == Kind.WEEKLY) {
            if (!weekOn()) return off
            val now = Market.now()
            var d = now.toLocalDate()
            if (!now.toLocalTime().isBefore(k.at)) d = d.plusDays(1)
            // The week's last session (at most three weeks ahead: a calendar with no session at all arms nothing).
            var n = 0
            while (!com.optionslab.ira.WeeklyReview.isLastSession(d) { Market.isTradingDay(it) } && n < 21) { d = d.plusDays(1); n++ }
            if (n >= 21) return off
            return d.atTime(k.at).atZone(now.zone).toInstant().toEpochMilli()
        }
        if (k == Kind.WEEK) {
            if (!weekOn()) return off
            val now = Market.now()
            var d = now.toLocalDate()
            if (!now.toLocalTime().isBefore(k.at)) d = d.plusDays(1)
            while (d.dayOfWeek != java.time.DayOfWeek.SUNDAY) d = d.plusDays(1)
            return d.atTime(k.at).atZone(now.zone).toInstant().toEpochMilli()
        }
        if (k == Kind.DANGER) {
            // The next trading day's 09:16 (at most three weeks ahead), whether or not Zerodha is set up: the arms trade on paper.
            if (!dangerOn()) return off
            val now = Market.now()
            var d = now.toLocalDate()
            if (!now.toLocalTime().isBefore(k.at)) d = d.plusDays(1)
            var n = 0
            while (!Market.isTradingDay(d) && n < 21) { d = d.plusDays(1); n++ }
            if (n >= 21) return off
            return d.atTime(k.at).atZone(now.zone).toInstant().toEpochMilli()
        }
        // Jarvis's morning check runs whether or not Zerodha is set up (it says so); the rest need Zerodha.
        if (!Broker.linked && !(com.optionslab.app.BuildConfig.JARVIS && k == Kind.MORNING)) return off
        val now = Market.now()
        var d = now.toLocalDate()
        if (!now.toLocalTime().isBefore(k.at)) d = d.plusDays(1)
        // Jarvis's morning comes every day (a closed day gets its own short morning); the rest only on trading days.
        if (!(com.optionslab.app.BuildConfig.JARVIS && k == Kind.MORNING)) while (!Market.isTradingDay(d)) d = d.plusDays(1)
        return d.atTime(k.at).atZone(now.zone).toInstant().toEpochMilli()
    }

    fun schedule(context: Context, k: Kind, onlyChanged: Boolean = false) {
        val at = nextAt(k)
        val mark = "report.${k.name}"
        // 22 Oct: a start in the background leaves a report's alarm armed for the same time as it is (while Android holds it).
        if (onlyChanged && !AlarmMarks.needsArm(context, mark, at) { intentIfAny(context, k) != null }) return
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = intent(context, k)
        am.cancel(pi)
        if (at == com.optionslab.ira.NightStart.OFF) { AlarmMarks.armed(context, mark, at); return }
        try {
            if (Jobs.canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        AlarmMarks.armed(context, mark, at)
    }

    /** The alarm fired: re-arm, then hand the work to a worker. */
    fun fired(context: Context, k: Kind) {
        schedule(context, k)
        if (k == Kind.WEEK) { if (!weekOn() || Market.today().dayOfWeek != java.time.DayOfWeek.SUNDAY) return }
        else if (k == Kind.WEEKLY) { if (!weekOn() || !com.optionslab.ira.WeeklyReview.isLastSession(Market.today()) { Market.isTradingDay(it) }) return }
        else if (k == Kind.DANGER) { if (!dangerOn() || !Market.isTradingDay()) return }
        else if (!Market.isTradingDay() && !(com.optionslab.app.BuildConfig.JARVIS && k == Kind.MORNING)) return
        val req = OneTimeWorkRequestBuilder<ReportWorker>()
            .setInputData(workDataOf("kind" to k.name))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("report.${k.name}", ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * 09:16 on a trading day (review of TODO I4, 13 Oct): the danger meter read again once BANKNIFTY's open is in. The gap is
     * its strongest sign, and the 09:00 check runs before the open. Up to four tries a minute apart while today's first
     * bar has not reached the phone. The new reading replaces the morning's (the Pre-trade checks card shows it) and is
     * noted in the chat as the morning check's follow-up: no notification, nothing spoken, nothing acts. A worker's
     * thread, never the main one or the order path. Nothing to do when today's reading already has the gap.
     */
    suspend fun dangerAfterOpen() {
        val today = Market.today()
        if (com.optionslab.app.data.DangerToday.today(today)?.provisional == false) return
        var r: com.optionslab.ira.DangerMeter.Reading? = null
        for (attempt in 0 until 4) {
            if (attempt > 0) kotlinx.coroutines.delay(60_000)
            runCatching { com.optionslab.app.ira.IraHub.refresh() }
            r = runCatching { com.optionslab.app.data.DangerToday.readFrom(com.optionslab.app.ira.IraHub.state.value.snaps, today) }.getOrNull()
            if (r != null && !r.provisional) break
        }
        val reading = r ?: return
        runCatching {
            com.optionslab.app.ira.IraHub.note(com.optionslab.ira.Address.boss("Good morning check, after the open: " + reading.line()),
                from = null, kind = com.optionslab.ira.TodayNotes.Category.PLANS)
        }
    }

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /**
     * Jarvis's morning on a day with no session (a weekend, a holiday): why it is closed and when it opens, the
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
        // The notification is the worker's post; the voice and the chat note are once a day (a retried run repeats nothing).
        val onceKey = "jarvis.closedMorning.$today"
        if (runCatching { com.optionslab.app.security.SecurePrefs.getString(onceKey) != null }.getOrDefault(false)) return title to lines
        // One key a closed day: the earlier days' are dropped with it (only today's is ever read).
        runCatching {
            val old = com.optionslab.ira.Upkeep.staleDayKeys(com.optionslab.app.security.SecurePrefs.keys("jarvis.closedMorning."), "jarvis.closedMorning.", today, 1)
            com.optionslab.app.security.SecurePrefs.putAll(old.associateWith<String, Any?> { null } + (onceKey to "1"))
        }
        runCatching { com.optionslab.app.ira.JarvisSpeaker.morning(context, "Good morning, Boss. The market is closed today, $why. It opens again ${next.dayOfWeek.name.lowercase()}." +
            (if (brief.isEmpty()) "" else " From my night's study: " + brief.take(2).joinToString(" ") { com.optionslab.ira.Wake.spoken(it, 1) })) }
        com.optionslab.app.ira.IraHub.note(com.optionslab.ira.Address.boss("Good morning. " + lines.joinToString(" ") { it.removePrefix("• ").trimEnd('.') + "." }), from = null, kind = com.optionslab.ira.TodayNotes.Category.PLANS)
        // Saturday: the week's report card comes with the morning (not left to the hourly study worker).
        runCatching { com.optionslab.app.ira.IraStudy.reportCardIfDue() }
        // Saturday's morning (or the first run after Friday's close): Jarvis's weekly self-report and the weekly consolidation.
        runCatching { com.optionslab.app.ira.SelfGrowth.tick(context) }
        // Saturday: Liquidity 15+5's weekly patterns too, when enough paper trades closed since the last look (words only).
        runCatching { com.optionslab.app.ira.IraLiquidityInsight.watch() }
        return title to lines
    }

    /** 09:00: is everything in place for the day? */
    suspend fun morning(context: Context): Pair<String, List<String>> {
        // 18 Oct (review): the paper-only period read now if the start could not (a worker thread): until it is read the order
        // door refuses every new live entry, so a failed read at the start no longer lasts the day.
        runCatching { com.optionslab.app.data.PaperOnly.ensureLoaded() }
        if (com.optionslab.app.BuildConfig.JARVIS && !Market.isTradingDay()) return closedMorning(context)
        val s = AppSettings.load()
        val lines = ArrayList<String>()
        var bad = 0
        // Margin and carried positions are account figures: shown, never said aloud - the phone may lock while the
        // morning's slower checks run, and the spoken line has no lock check of its own (review, 5 Oct).
        fun privateLine(l: String) = l.removePrefix("✗ ").let { t ->
            t.startsWith(com.optionslab.ira.PreMarket.Part.MARGIN.label + ":") || t.startsWith(com.optionslab.ira.PreMarket.Part.CARRIED.label + ":") }
        fun ok(yes: Boolean, text: String) { if (!yes) bad++; lines += (if (yes) "✓ " else "✗ ") + text }
        ok(Broker.loggedIn, if (Broker.loggedIn) "Zerodha logged in for today" else "Zerodha not logged in: log in before 09:15 (${com.optionslab.app.ui.Tab.CABINET.label} → Zerodha)")
        // Bounded, and off every order path (18 Oct): the morning check waits for the background refresh, never longer than its limit.
        if (Holidays.stale(Market.today())) Holidays.refreshInBackground().join()
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
        lines += "• Strategies armed: $strat" + (runCatching { Strategies.stoppedWhy() }.getOrNull()?.let { " · bot stopped for today ${com.optionslab.ira.DayStop.by(it)}" } ?: "")
        // 12 Oct: anything armed against a standing decision (SENSEX on BSE, FINNIFTY by research X1, a strategy that lost in both
        // tested years) is a failing line that counts, not a bullet among twenty; also on the Automation card's problems.
        runCatching { com.optionslab.app.data.ProblemsHost.policySweep(s.live && s.allowRealOrders, force = true) }.getOrDefault(emptyList())
            .forEach { ok(false, it.line()) }
        ok(!s.guardKill, if (s.guardKill) "Kill switch is ON: every Zerodha order is refused (paper still trades)" else "Kill switch off")
        // Battery-optimized, Android may stop the order watch (and its stops and targets) in the background: said plainly,
        // and the notification's tap opens the Battery row. Only read: the setting is Boss's to change.
        Heartbeat.batteryRestricted(context)?.let { restricted ->
            ok(!restricted, if (restricted) com.optionslab.ira.WatchHealth.BATTERY_FIX else com.optionslab.ira.WatchHealth.BATTERY_OK)
        }
        val carried = Paper.state.positions.count { it.quantity != 0 }
        if (carried > 0) lines += "• Paper positions carried overnight: $carried"
        lines += "• Mode: " + if (s.live) "LIVE (Zerodha)" else "Paper"
        // Boss's paper-only period (12 Oct), when one is set: its end date and reason.
        runCatching { com.optionslab.app.data.PaperOnly.ensureLoaded(); com.optionslab.app.data.PaperOnly.activeNow() }.getOrNull()
            ?.let { lines += "• " + com.optionslab.ira.PaperOnlyPeriod.status(it, Market.today()) }
        if (com.optionslab.app.BuildConfig.JARVIS) {
            // Jarvis's own checks: the Zerodha connection itself, the static IP, the live prices, the data, its model and voice.
            if (Broker.loggedIn) {
                val f = Broker.within(10_000) { Broker.funds() }
                // 12 Oct: said as Zerodha's REAL money (Boss read this morning's figure against the evening's paper result).
                ok(f != null, if (f != null) "Zerodha (LIVE, real money) connection tested: ${rs(f.available).removePrefix("+")} available to trade; paper trades never use it" +
                    (com.optionslab.ira.MoneyFacts.negativeWhy(com.optionslab.ira.MoneyFacts.Funds(f.available, f.used, f.m2m, f.realised, f.span, f.exposure,
                        f.optionPremium), 0, null)?.let { ". $it" } ?: "")
                    else "Zerodha did not answer the connection test")
            } else if (!Broker.configured) lines += "• Zerodha not set up: Paper only"
            com.optionslab.app.data.StaticIp.registered?.let { reg ->
                val st = runCatching { com.optionslab.app.data.StaticIp.status(force = true) }.getOrNull()
                val said = if (st?.matches == true) "On your registered static IP $reg" else "Not on your registered static IP $reg: new live positions will be refused"
                ok(st?.matches == true, said)
                // Kept in the diary too, so Jarvis can answer "is my static IP working?" from it (RelayHealth); the IP is the one shown.
                com.optionslab.app.data.Diag.record("static-ip", "Morning check: $said")
            }
            runCatching { com.optionslab.app.ira.IraHub.refresh() }
            val ira = com.optionslab.app.ira.IraHub.state.value
            // The pre-market checklist's checks the morning did not have ([com.optionslab.ira.PreMarket]): the relay, the
            // guards, the bots against the ones he usually arms and - on an unlocked phone only - his margin against his
            // usual position size and the positions carried overnight. A fail says the step he takes; nothing is changed.
            runCatching {
                val locked = com.optionslab.app.ira.IraHub.locked()
                val setup = com.optionslab.app.ira.IraPreMarket.setup(context, locked)
                val acct = if (locked) null else com.optionslab.app.ira.IraPreMarket.account()
                val parts = setOf(com.optionslab.ira.PreMarket.Part.RELAY, com.optionslab.ira.PreMarket.Part.GUARDS, com.optionslab.ira.PreMarket.Part.BOTS,
                    com.optionslab.ira.PreMarket.Part.MARGIN, com.optionslab.ira.PreMarket.Part.CARRIED)
                com.optionslab.ira.PreMarket.checks(setup, acct, locked || acct == null).filter { it.part in parts }.forEach { c ->
                    when (val v = c.ok) {
                        null -> lines += "• ${c.part.label}: ${c.text}"
                        else -> ok(v, "${c.part.label}: ${c.text.removeSuffix(".")}" + (c.fix?.let { ". $it" } ?: ""))
                    }
                }
            }
            ok(ira.liveMissing.size < 3, if (ira.liveMissing.isEmpty()) "Live prices reaching the app" else "No live prices yet from ${ira.liveMissing.joinToString { it.label }}")
            // The danger meter (TODO I4, research R17): today's day type with its sample size. Advice only: it failed its holdout
            // test; it acts only through Boss's half-size switch (paper arms, off by default). At 09:00 the gap is not known
            // yet, so this reading is provisional; the DANGER run reads it again at 09:16 ([dangerAfterOpen]). VIX counts only
            // from a snapshot of today or the previous session (review, 13 Oct).
            runCatching { lines += "• " + com.optionslab.app.data.DangerToday.readFrom(ira.snaps, Market.today()).line() }
            // The day's expected move, from India VIX.
            runCatching {
                val n = ira.snaps[com.optionslab.ira.Market.NIFTY]; val v = ira.snaps[com.optionslab.ira.Market.VIX]?.price
                if (n != null && v != null) com.optionslab.ira.ExpectedRange.say(n, v, java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")))?.let { lines += "• $it" }
            }
            com.optionslab.app.security.SecurePrefs.getString("harvest.last")?.let { lines += "• Last data harvest: $it" }
            val pine = com.optionslab.app.data.PineScripts.items.value.count { it.auto.on }
            if (pine > 0) lines += "• Pine scripts switched on: $pine"
            // Boss's goals and where they stand (part 6).
            if (com.optionslab.app.BuildConfig.JARVIS) runCatching { com.optionslab.app.ira.IraGoals.statuses() }.getOrNull()?.forEach { lines += "• Goal: ${it.text}" }
            // The strongest lesson in the results (part 5), when one stands out.
            if (com.optionslab.app.BuildConfig.JARVIS) runCatching { com.optionslab.app.ira.IraAccount.lessons().first.firstOrNull() }.getOrNull()?.let { lines += "• Lesson: ${it.text}" }
            lines += "• Jarvis: AI model ${com.optionslab.app.ira.IraModel.choice.name} ${if (com.optionslab.app.ira.IraModel.state.value.status == com.optionslab.app.ira.IraModel.Status.READY) "ready" else "not on the phone"}, " +
                "voice ${if (com.optionslab.app.ira.JarvisVoice.listenOn) "on" else "off"}"
            runCatching { com.optionslab.app.ira.IraHub.tradeCheck() }.getOrNull()?.let { v ->
                lines += "• Trade check: " + when (v.level) { com.optionslab.ira.TradeCheck.Level.GO -> "normal"; com.optionslab.ira.TradeCheck.Level.CAREFUL -> "careful"; else -> "don't trade yet" } +
                    v.reasons.filter { it.level != com.optionslab.ira.TradeCheck.Level.GO && !it.text.startsWith("The market opens") }.take(2).joinToString("") { " · " + it.text.removeSuffix(".") }
            }
            runCatching { kotlinx.coroutines.withTimeoutOrNull(15_000) { com.optionslab.app.ira.IraHub.flows() } }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { lines += "• " + com.optionslab.ira.Flows.lines(it).first() }
            // The market recorder's morning cues ([com.optionslab.ira.MorningCues]): GIFT Nifty's gap from Nifty's previous close
            // and the FIIs' index positioning from NSE's participant OI - each only when a recent one is held. Facts only.
            runCatching { com.optionslab.app.ira.IraHub.morningCues() }.getOrDefault(emptyList()).forEach { lines += "• $it" }
            com.optionslab.app.ira.IraEvents.upcoming(1).forEach { e -> lines += "• " + com.optionslab.ira.Events.line(e, Market.today()).removeSuffix(".") }
            // The market context (TODO I6, I10, I11): today's event warning, the expiry card, when "days like today" comes.
            runCatching { com.optionslab.app.data.MarketContext.morningLines() }.getOrDefault(emptyList()).forEach { lines += "• $it" }
            // A mute said by voice lasts that day only (Boss, 5 Oct): yesterday's ends here, and the check says so.
            val voiceBack = runCatching { com.optionslab.app.ira.JarvisVoice.morningUnmute() }.getOrNull()
            voiceBack?.let { lines += "• $it" }
            // Jarvis's own self-check: each part it needs, working or not (a part switched off is not counted).
            run {
                val vs = com.optionslab.app.ira.JarvisVoice.state.value
                val voiceOn = com.optionslab.app.ira.JarvisVoice.listenOn
                val mic = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val model = com.optionslab.app.ira.IraModel.state.value.status
                // 12 Oct: since the last close (a Monday morning's check read Saturday's run as "not working").
                val studyNow = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))
                val studyAt = com.optionslab.app.ira.IraStudy.state.value.at?.atZone(java.time.ZoneId.of("Asia/Kolkata"))?.toLocalDateTime()
                val studied = com.optionslab.ira.StudyPace.studiedSinceClose(studyAt, studyNow) { Market.isTradingDay(it) }
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
                sc.drop(1).filter { it.endsWith("not working.") && !it.startsWith("Night study") }.forEach { lines += "• $it" }
                // What to do about it, not only "not working".
                if (!studied) lines += "• " + com.optionslab.ira.StudyPace.notStudiedLine(studyAt)
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
            // Each index's day in one line: its trend, the range a usual day spans, the pivot.
            com.optionslab.app.ira.IraHub.morningOutlook().forEach { lines += "• Outlook: $it" }
            // Its numbers noted, to be set against the close at the 15:45 wrap-up ([com.optionslab.ira.OutlookCheck]).
            com.optionslab.app.ira.IraHub.outlookNoted()
            val title = "Good morning Boss · " + if (bad == 0) "we are set for today's trading" else "$bad thing${if (bad > 1) "s" else ""} need you"
            runCatching { com.optionslab.app.ira.JarvisPopup.show(context, title, lines.take(3).joinToString(" · ")) }
            // Listening now: Jarvis says it too. The minor items Boss usually leaves as they are (still failing from the
            // morning before) are named in a few words aloud ([com.optionslab.ira.MorningSense]); a safety item is always
            // read out, and the chat note below keeps every item. Today's failing items are noted (keys only).
            val failing = lines.filter { it.startsWith("✗") }
            val needYou = com.optionslab.app.ira.IraTools.morningAloud(bad, failing.filter { !privateLine(it) }.map { it.removePrefix("✗ ") }, failing)
            // The question Boss asks on most mornings and has not asked yet today: offered in one last line ("say 'yes'
            // for it"), never answered unasked ([com.optionslab.ira.MorningAsks]; a market question only - words, nothing acts).
            val usual = runCatching { com.optionslab.app.ira.IraTools.morningAsksLine() }.getOrNull()
            // Battery (round 4): listening on with its battery saver off - said once, ever, in words; never switched here.
            val saver = runCatching { com.optionslab.app.ira.JarvisVoice.saverHintOnce() }.getOrNull()
            runCatching { com.optionslab.app.ira.JarvisSpeaker.morning(context, "Good morning, Boss. " + (voiceBack?.let { "$it " } ?: "") + (if (bad == 0) "We are set for today's trading." else
                needYou +
                (if (brief.isEmpty()) "" else " Now my analysis. " + brief.joinToString(" ") { com.optionslab.ira.Wake.spoken(it, 2) } +
                    " That is history, not a promise.")) + (saver?.let { " $it" } ?: "") + (usual?.let { " $it" } ?: "")) }
            com.optionslab.app.ira.IraHub.note(com.optionslab.ira.Address.boss("Good morning. " +
                (if (bad == 0) "We are set for today's trading. " else "$bad thing${if (bad > 1) "s" else ""} need you before 09:15. ") +
                lines.joinToString(" ") { it.removePrefix("✓ ").removePrefix("✗ ").removePrefix("• ").trimEnd('.') + "." } + (saver?.let { " $it" } ?: "") +
                (usual?.let { " $it" } ?: "")), from = null, kind = com.optionslab.ira.TodayNotes.Category.PLANS)
            return title to lines
        }
        val title = "Morning check · ${Market.today().format(DAY)}" + if (bad == 0) " · all set" else " · $bad to fix"
        return title to lines
    }

    /** 15:45: how the day went. */
    suspend fun evening(context: Context): Pair<String, List<String>> {
        val lines = ArrayList<String>()
        // 12 Oct: each account's day apart, never one total of paper and Zerodha (the title said "−₹1,374" for a paper day).
        var paperDay: Double? = null
        var liveDay: Double? = null
        runCatching { Paper.snapshot() }.getOrNull()?.let { sn ->
            val pnl = sn.dayPnl
            if (sn.trades.isNotEmpty() || pnl != 0.0) {
                // Shown before charges, as Zerodha shows its own (Boss, 5 Oct), the day's charges beside it; kept after them.
                paperDay = sn.dayGross
                lines += "Paper (practice, not your Zerodha money): ${rs(sn.dayGross)} · ${sn.trades.size} trade${if (sn.trades.size == 1) "" else "s"}" +
                    (com.optionslab.ira.PnlCharges.line(sn.dayCharges, estimate = false)?.let { " · ${it.lowercase()}" } ?: "")
                // Honest paper (08 Oct): the figure above already paid the bid/ask spread; said so, with how much.
                lines += "Paper now includes the bid/ask spread: Rs " + String.format(java.util.Locale.ENGLISH, "%,.2f", sn.daySpread) + " today"
                runCatching { com.optionslab.app.data.DailyPnl.record(false, pnl, sn.trades.size, sn.dayCharges) }
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
                liveDay = book.m2m
                // Zerodha's exact figure when the app kept it for every order of the day's trades (never asked from here;
                // usefulness, round 35), else the estimate from them.
                val zExact = runCatching { com.optionslab.app.data.TradeBook.exactChargesOn(Market.today()) }.getOrNull()
                val zCharges = zExact ?: com.optionslab.app.data.TradeBook.liveCharges(all)
                lines += "Zerodha (LIVE, real money): ${rs(book.m2m)} · $trades trade${if (trades == 1) "" else "s"}" +
                    (com.optionslab.ira.PnlCharges.line(zCharges, estimate = zExact == null)?.let { " · ${it.lowercase()}" } ?: "")
                runCatching { com.optionslab.app.data.DailyPnl.record(true, book.m2m, trades, zCharges, exact = zExact != null) }
            }
            val open = book.net.count { it.qty != 0 }
            if (open > 0) lines += "⚠ Zerodha positions carried: $open (NRML)"
        }
        runCatching { OrbArms.view() }.getOrNull()?.arms?.forEach { a ->
            val closed = a.today.filter { !it.open }
            if (closed.isNotEmpty()) lines += "${a.arm.label}: ${closed.size} trade${if (closed.size == 1) "" else "s"} · ${rs(closed.sumOf { (it.grossPnl ?: 0.0) - it.charges })}"
        }
        if (Heartbeat.stalledToday()) lines += "⚠ The market watch stopped at least once today"
        // Jarvis: after the week's last session, the weekly review.
        if (com.optionslab.app.BuildConfig.JARVIS) {
            var d = Market.today().plusDays(1)
            while (!Market.isTradingDay(d)) d = d.plusDays(1)
            if (d.toEpochDay() - Market.today().toEpochDay() > 1 || d.dayOfWeek == java.time.DayOfWeek.MONDAY) runCatching { com.optionslab.app.ira.IraHub.weeklyReview() }
            // ... and after the month's last session, the monthly review.
            if (com.optionslab.ira.MonthReview.lastTradingDay(Market.today()) { Market.isTradingDay(it) }) runCatching { com.optionslab.app.ira.IraHub.monthlyReview() }
        }
        // What was armed today, kept (names only) so the morning checklist knows the bots he usually arms.
        if (com.optionslab.app.BuildConfig.JARVIS) runCatching { com.optionslab.app.ira.IraPreMarket.remember(com.optionslab.app.ira.IraPreMarket.armedNow()) }
        if (AppSettings.load().guardKill) lines += "⚠ The kill switch is on"
        if (lines.isEmpty()) lines += "No trades today."
        // The event shield (TODO I6): the next session's event warning, the evening before.
        runCatching { com.optionslab.app.data.MarketContext.eveningLines() }.getOrDefault(emptyList()).forEach { lines += it }
        // Jarvis: what he learned from the agents today (his pass runs now if the brain's worker has not run it yet).
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD) runCatching {
            com.optionslab.app.ira.JarvisLearning.learnIfDue(context)
            // The shared knowledge and the lessons bundle right after his pass (13 Oct; its own once-a-day check).
            runCatching { com.optionslab.app.data.SharedKnowledgeHost.nightly(context) }
            // Autopilot learning right after his pass (its own once-a-day check; it tells its own changes).
            runCatching { com.optionslab.app.ira.Autopilot.runIfDue(context) }
            com.optionslab.app.ira.JarvisLearning.eveningLine()
        }.getOrNull()?.let { lines += it }
        val title = com.optionslab.ira.MoneyFacts.dayTitle("Day report · ${Market.today().format(DAY)}", paperDay, liveDay)
        // Review: DailyPnl.record writes in the background (SecurePrefs.putAllSoon) and this worker is short-lived, so the
        // day's final figures are put on disk before it ends - off the main thread (flush waits on the vault's writer).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.optionslab.app.security.SecurePrefs.flush() } }
        return title to lines
    }

    /**
     * Sunday 18:00: the paper arms' week ([com.optionslab.ira.ArmWeek]) - each arm's trades, wins and net beside its two-year
     * test, and which is on track to pass its paper test. Once a week (a retried run repeats nothing), under the weekly
     * review's own switch; the numbers in the chat, aloud plain words only (a locked phone hears only that it is in the chat).
     * Reads only: nothing is armed, stopped, placed or closed. [aloud] false (20 Oct, enhancement round 2): the spoken Sunday
     * review ([com.optionslab.app.ira.IraSundayReview]) was made, so this goes to the chat only - one voice at a time.
     */
    suspend fun week(aloud: Boolean = true) {
        if (!weekOn() || !com.optionslab.app.ira.Automations.on(com.optionslab.app.ira.Automations.Auto.WEEK)) return
        val today = Market.today()
        val onceKey = "jarvis.armWeek.last"
        if (runCatching { com.optionslab.app.security.SecurePrefs.getString(onceKey) == today.toString() }.getOrDefault(false)) return
        val said = com.optionslab.app.ira.IraBots.armWeek(today) ?: return
        runCatching { com.optionslab.app.security.SecurePrefs.put(onceKey, today.toString()) }
        com.optionslab.app.ira.IraHub.note(said.chat, from = com.optionslab.app.ira.Automations.Auto.WEEK)
        if (aloud) runCatching { com.optionslab.app.ira.JarvisVoice.announce(said.aloud) }
        runCatching { com.optionslab.app.ira.Automations.acted(com.optionslab.app.ira.Automations.Auto.WEEK, said.aloud) }
    }

    fun post(context: Context, k: Kind, title: String, lines: List<String>) =
        Notifier.post(context, k.id, Notifier.APPROVAL, title, lines.joinToString("\n"),
            // Not logged in: the notification opens the Zerodha page, one login and done.
            if (k == Kind.EVENING) "pnl" else if (!Broker.loggedIn && Broker.configured) "broker" else "almanac",
            // A morning that needs something set (the login, the microphone, the model, a backup): the tap opens that row.
            setting = if (k == Kind.EVENING) null else NoticeCards.morningSetting(lines, !Broker.loggedIn && Broker.configured))
}

class ReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val k = runCatching { DailyReports.Kind.valueOf(inputData.getString("kind") ?: "") }.getOrNull() ?: return Result.failure()
        return try {
            when (k) {
                DailyReports.Kind.MORNING -> {
                    DailyReports.morning(applicationContext).let { (t, l) -> DailyReports.post(applicationContext, k, t, l) }
                    // Jarvis: a weekly review missed (the phone was off after the week's last session) is made now, once.
                    if (com.optionslab.app.BuildConfig.JARVIS) runCatching { com.optionslab.app.ira.IraWeekly.prepare(applicationContext) }
                }
                // The reminder speaks only if the session is still missing.
                DailyReports.Kind.LOGIN -> if (!Broker.loggedIn) DailyReports.post(applicationContext, k, "Log in to Zerodha now",
                    listOf("The market opens at 09:15 and there is no Zerodha session today. Open the app → ${com.optionslab.app.ui.Tab.CABINET.label} → Zerodha."))
                DailyReports.Kind.EVENING -> {
                    // The daily self-review after F&O's close (10 Oct): each strategy against its backtest; a drifting live one
                    // parks itself to paper and asks (once a day; the watch's brain worker does it too when it is still running).
                    if (!com.optionslab.app.BuildConfig.GOLD) runCatching { com.optionslab.app.data.SmartWorkers.reviewIfDue(applicationContext) }
                    // The history vault (TODO S5): the day's 1-minute history kept after the close (its own thread; resumes).
                    if (!com.optionslab.app.BuildConfig.GOLD) runCatching { com.optionslab.app.data.HistoryKeeper.kick() }
                    DailyReports.evening(applicationContext).let { (t, l) -> DailyReports.post(applicationContext, k, t, l) }
                }
                // Said and kept in the chat only: no notification. 20 Oct, enhancement round 2 (TODO I15): the spoken Sunday
                // review first (a locked phone gets only a "ready" notice); when it was made, the arms' week is not said too.
                DailyReports.Kind.WEEK -> {
                    val reviewed = runCatching { com.optionslab.app.ira.IraSundayReview.sunday(applicationContext) }.getOrDefault(false)
                    DailyReports.week(aloud = !reviewed)
                }
                // Kept for the Ira page, noted in the chat, "Weekly review ready" notified (no rupee figure in it).
                DailyReports.Kind.WEEKLY -> com.optionslab.app.ira.IraWeekly.prepare(applicationContext)
                // The danger meter again once BANKNIFTY's open is in: a chat note and the card, no notification.
                DailyReports.Kind.DANGER -> DailyReports.dangerAfterOpen()
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
