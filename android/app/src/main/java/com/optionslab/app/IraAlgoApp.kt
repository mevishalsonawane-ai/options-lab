package com.optionslab.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.optionslab.app.data.Alarms
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Ledger
import com.optionslab.app.data.Market
import com.optionslab.app.data.Store
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.SessionLock
import com.optionslab.app.work.Jobs
import com.optionslab.app.work.Notifier
import kotlinx.coroutines.launch

class IraAlgoApp : Application() {
    companion object {
        /** The last crash, sealed with [com.optionslab.app.security.Vault]; read it with Vault.readFile. */
        const val CRASH_FILE = "last_crash.vault"
        /** The old plaintext crash file (it held exception messages): deleted on start. */
        private const val OLD_CRASH_FILE = "last_crash.txt"

        /**
         * Exception class names and stack frames only - never an exception's message, which can
         * carry an API reply, a token, an amount or a symbol. Causes and suppressed ones included.
         */
        fun crashReport(t: Thread, e: Throwable): String = buildString {
            append("${java.time.ZonedDateTime.now()} · thread ${t.name} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            fun one(x: Throwable, prefix: String) {
                if (!seen.add(x) || length > 12_000) return
                append(prefix).append(x.javaClass.name).append('\n')
                x.stackTrace.take(60).forEach { append("\tat ").append(it.toString()).append('\n') }
                x.suppressed.forEach { one(it, "Suppressed: ") }
                x.cause?.let { one(it, "Caused by: ") }
            }
            one(e, "")
        }.take(12_000)
    }

    override fun onCreate() {
        super.onCreate()
        // Speed: how long this start takes on the screen's thread, and its slowest step (the diagnostics' "Speed:" line).
        val startAt = android.os.SystemClock.uptimeMillis()
        var slowest: Pair<String, Long>? = null
        fun timed(name: String, f: () -> Unit) {
            val a = android.os.SystemClock.uptimeMillis()
            try { f() } finally {
                val d = android.os.SystemClock.uptimeMillis() - a
                if (d > (slowest?.second ?: -1L)) slowest = name to d
            }
        }
        // If the app ever crashes, keep what went wrong - class names and stack frames only, no
        // messages, encrypted in the vault, on this phone only, never logged or sent - so the
        // next start can show it and the owner can pass it on.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // The diary's last few seconds of lines are written together (Diag): put them on disk before the process ends.
            runCatching { com.optionslab.app.data.Diag.saveNow() }
            runCatching {
                com.optionslab.app.security.Vault.writeFile(java.io.File(filesDir, CRASH_FILE), crashReport(t, e).toByteArray(Charsets.UTF_8))
            }
            if (BuildConfig.DEBUG) previous?.uncaughtException(t, e)
            else {
                // Release: the platform handler would print the whole exception, messages included,
                // to logcat. End the process here instead.
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        runCatching { java.io.File(filesDir, OLD_CRASH_FILE).delete() }
        SecurePrefs.init(this)
        // Speed, round 6: the settings decrypted first, before the pine scripts' and gold books' background reads queue
        // in the Keystore ahead of them (see SecurePrefs.warm). An unreadable vault is marked exactly as on a later read.
        timed("settings") { runCatching { SecurePrefs.warm() } }
        // The danger meter's half-size switch (paper arms, off by default) into the entry path's memory copy before any
        // arm can run: read from the settings just decrypted above (13 Oct; it no longer waits for the morning check or the card).
        runCatching { com.optionslab.app.data.AppSettings.syncDangerHalf() }
        // 18 Oct (Boss): the paper-only period into memory before any order can be asked for, from the settings just decrypted
        // above (as the half-size switch): the order door reads it from memory only and, until it is read, refuses new live
        // entries (never an exit). A failed read is tried again in the background ([PaperOnly.loadSoon]).
        runCatching { com.optionslab.app.data.PaperOnly.ensureLoaded() }
        com.optionslab.app.data.PaperOnly.loadSoon()
        // 22 Oct (the overnight kills, "excessive binder traffic during cached"): the start's heavy work that never trades waits
        // until the screen or the market watch starts (or a job asks for it) - an hourly night job starts the app for a second.
        // The open positions' steps below never wait for it.
        com.optionslab.app.data.StartGate.hold(this)
        Ledger.init(this)
        Alarms.init(this)
        Store.init(this)
        // 22 Oct: the time machine's forward log lives in the app's encrypted store.
        com.optionslab.app.data.TimeMachineHost.init(this)
        removeOldMarketDataStore()
        Market.init(this)
        com.optionslab.app.data.Holidays.init(this)
        com.optionslab.app.data.McxMarket.init(this)
        Broker.init(this)
        com.optionslab.app.data.StaticIp.init(this)
        com.optionslab.app.data.Paper.init(this)
        // 19 Oct: the same-direction cap's switches and record, read on a background thread (the order path reads memory only).
        com.optionslab.app.data.ExposureCapHost.init(this)
        // 20 Oct, enhancement round 1 (idea 4): the account stop rule (in shadow by default), read in the background.
        com.optionslab.app.data.AccountStopHost.init(this)
        // 25 Oct (Boss's decisions on the app audit): the new risk rules' switches and record, read in the background.
        com.optionslab.app.data.BossRiskHost.init(this)
        // 20 Oct, enhancement round 2 (idea 1): the exit coordinator's switch (in shadow by default), read in the background.
        com.optionslab.app.data.ExitCoordinator.init(this)
        // 21 Oct, enhancement round 4 (idea 1): stop and target as one GTT in the day too (off by default), read in the background.
        com.optionslab.app.data.DayOco.init(this)
        // 21 Oct, enhancement round 4 (idea 3): the payout-aware drawdown (in shadow by default), read in the background.
        com.optionslab.app.data.PayoutWatch.init(this)
        com.optionslab.app.data.History.init(this)
        com.optionslab.app.data.Strategies.init(this)
        // 18 Oct (Boss: every paper strategy present for live too): the paper strategies' live path, before the arms register with it.
        com.optionslab.app.data.ArmLive.init(this)
        com.optionslab.app.data.OrbArms.init(this)
        com.optionslab.app.data.NightArm.init(this)
        com.optionslab.app.data.VixDivArm.init(this)
        com.optionslab.app.data.HeroTicketArm.init(this)
        // 21 Oct (Boss: "Add FT3 to the app paper bot until live ..."): the R98 forward test FT3, on paper (its file only; no I/O here).
        com.optionslab.app.data.Ft3Arm.init(this)
        // 15 Oct (Boss: "Both"): the passed-research paper arms (R51, R52, Solo 2.0) and the shadow Watchlist (files only; no I/O here).
        com.optionslab.app.data.PassedArmsHost.init(this)
        com.optionslab.app.data.WatchlistHost.init(this)
        com.optionslab.app.data.TrendWatchHost.init(this)
        // 25 Oct: ntfy alerts (paper by default; its file only, no I/O here).
        com.optionslab.app.data.NtfyArm.init(this)
        com.optionslab.app.data.StockOptions.init(this)
        com.optionslab.app.data.McxPaperArms.init(this)
        com.optionslab.app.data.McxNr7Record.init(this)          // 21 Oct: NR7 crude, record only (no orders)
        com.optionslab.app.data.GrammarArm.init(this)
        com.optionslab.app.data.ShadowArms.init(this)
        com.optionslab.app.data.MarketRecorder.init(this)
        com.optionslab.app.data.PineScripts.init(this)
        com.optionslab.app.data.PineAuto.init(this)
        com.optionslab.app.data.Protections.init(this)
        com.optionslab.app.data.TradeBook.init(this)
        com.optionslab.app.data.FillSlippage.init(this)
        // OPEN POSITIONS AND ORDERS COME FIRST (14 Oct, memory audit): every position book is read now on a thread of its own
        // at the top priority, the watch started when anything is open, then the stream - before the rest of the start's
        // background work (the shared knowledge waits for both). Every position store's init above only names its file.
        // The diary and the stream's context first (each only names its file or keeps the context): the positions' lines are kept.
        com.optionslab.app.data.Diag.init(this)
        com.optionslab.app.data.KiteStream.init(this)
        com.optionslab.app.data.PositionsFirst.start(this)
        // 26 Oct: ntfy alerts' reader supervised on its own (started at 09:00, restarted within seconds); after the positions step above (it waits for it).
        if (!com.optionslab.app.BuildConfig.GOLD) com.optionslab.app.data.NtfyReader.init(this)
        // Boss's 15 Oct arms cleanup: its small file read on its own worker; its first pass waits for the positions step above.
        com.optionslab.app.data.HiddenArms.init(this)
        // 25 Oct: the removed Telegram feature's data deleted once, in the background, after the positions step above.
        if (!com.optionslab.app.BuildConfig.GOLD) com.optionslab.app.data.RemovedFeatureCleanup.init(this)
        com.optionslab.app.data.LiveRecorder.init(this)
        // The OI-wall shadow log (14 Oct, Boss approved; R26): names its encrypted file only (a record, never an order).
        com.optionslab.app.data.OiWallShadowHost.init(this)
        // The tick zone (12 Oct): the watch-list's order flow every second, from the stream's memory, written in the background.
        com.optionslab.app.data.TickZoneRecorder.init(this)
        // GitHub sync (13 Oct, TODO G1/G2): the context only; its worker uploads after the MCX evening close (15:45 without
        // MCX; G4) on Wi-Fi while charging.
        com.optionslab.app.data.GitHubSync.init(this)
        com.optionslab.app.data.LiquidityTest.init(this)
        com.optionslab.app.data.Journal.init(this)
        // Battery (14 Oct): the minutes each background loop ran today, for the diagnostics and the Health page.
        com.optionslab.app.work.BatteryMeter.init()
        // The live order flow (shown, logged beside each signal; it can only ever skip an entry where Boss set CONFIRM).
        com.optionslab.app.data.FlowGate.init(this)
        com.optionslab.app.data.SmartWorkers.init(this)
        // The market context (TODO I6, I10-I13, 13 Oct): event warnings, the expiry card, days like today, the level map.
        com.optionslab.app.data.MarketContext.init(this)
        // Jarvis's lessons from the agents (11 Oct): his small file read on a background thread; learned once a day after the close.
        com.optionslab.app.ira.JarvisLearning.init(this)
        // The shared knowledge (13 Oct): research + Jarvis's lessons + recent findings, built once in the background for every agent.
        com.optionslab.app.data.SharedKnowledgeHost.init(this)
        com.optionslab.app.data.OrderReviewHost.init(this)
        // 12 Oct: the open problems any agent found (the Automation card shows them).
        com.optionslab.app.data.ProblemsHost.init(this)
        // Autopilot learning (11 Oct): its change log, knobs and setting read on a background thread; it runs after his pass.
        com.optionslab.app.ira.Autopilot.init(this)
        // The app's own health (11 Oct): its small file and the previous runs' ANRs and crashes read on a background thread.
        com.optionslab.app.data.AppHealth.init(this)
        // What Jarvis tries to learn next, the weekly consolidation and self-report: their small files read in the background.
        com.optionslab.app.ira.SelfGrowth.init(this)
        // The strategy lab (11 Oct): its two small files read on a background thread; its worker settles after the close and
        // runs the forge at night (charging and idle, or after 18:00). Virtual trades only.
        com.optionslab.app.data.VariantRunner.init(this)
        // The trade manager (10 Oct): its modes and record read on its own thread; it only lowers risk.
        com.optionslab.app.data.TradeManagerHost.init(this)
        // The history vault (TODO S5, 11 Oct): nothing read now; its folder is opened by its own thread after the close.
        com.optionslab.app.data.HistoryKeeper.init(this)
        com.optionslab.app.data.OrderFlowLive.init(this)
        // The roadmap's trust, learning-from-Boss and study pieces (S10-S21): the learning freeze, Boss's own trades, the
        // scorecard, the battery budget; small files read on background threads.
        com.optionslab.app.ira.SelfJobs.init(this)
        // The gold books and Jarvis's memory are decrypted on a background thread (Speed, round 2); every change to them
        // waits until they are read, so nothing is ever saved over them empty.
        timed("gold books") { com.optionslab.app.data.GoldBooks.init(this) }
        timed("Jarvis's memory") { com.optionslab.app.ira.IraHub.init(this) }
        Notifier.createChannels(this)      // before the disarm below, which may post a notice
        // First start after a restore: everything restored comes back disarmed and paper only.
        // Done HERE, synchronously, before onCreate returns - so before any receiver, alarm,
        // WatchService or worker in this process can run a tick and trade on restored state.
        // It costs a few Keystore operations, and only on that one start. If it fails the flag
        // stays set, the stores disarm on load, and the next start tries again.
        if (SecurePrefs.getBoolean(com.optionslab.app.data.Backup.DISARM, false)) runCatching {
            kotlinx.coroutines.runBlocking {
                com.optionslab.app.data.Strategies.disarmAll()
                com.optionslab.app.data.OrbArms.disarmAll()
                com.optionslab.app.data.PineScripts.disarmAll()
            }
            SecurePrefs.put(com.optionslab.app.data.Backup.DISARM, null)
        }
        // The restore-disarm above is done: a watch started by the positions-first thread may tick now.
        com.optionslab.app.data.PositionsFirst.appReady()
        ProcessLifecycleOwner.get().lifecycle.addObserver(SessionLock)
        // 22 Oct: the screen starting opens the start's waiting heavy work and arms every alarm in full once (StartGate).
        ProcessLifecycleOwner.get().lifecycle.addObserver(com.optionslab.app.data.StartGate)
        // 21 Oct, enhancement round 3 (idea 7): the phone unlocked - a Sunday review not said at 18:00 is said now (Sunday
        // evening only, once; IraSundayReview.catchUp). A receiver of this process only (Android delivers the unlock to running apps).
        if (com.optionslab.app.BuildConfig.JARVIS) runCatching {
            androidx.core.content.ContextCompat.registerReceiver(this, UnlockReceiver,
                android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        // Keystore work stays off the main thread. 22 Oct: only the alarms whose time changed (an hourly night job starts the app;
        // each alarm armed again cost four calls into Android); the dead-man check is armed as always, and the screen's first
        // start arms everything in full (StartGate).
        com.optionslab.app.data.StartGate.startArm = Thread { runCatching { Jobs.scheduleAll(this, onlyChanged = true) } }.apply { start() }
        com.optionslab.app.data.Speed.startMs = android.os.SystemClock.uptimeMillis() - startAt
        com.optionslab.app.data.Speed.startSlowest = slowest?.let { (n, ms) -> "$n $ms ms" }
    }

    /** 21 Oct, enhancement round 3 (idea 7): the phone was unlocked; the Sunday review's catch-up, off the main thread. */
    internal object UnlockReceiver : android.content.BroadcastReceiver() {
        private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action != android.content.Intent.ACTION_USER_PRESENT) return
            val app = context.applicationContext
            scope.launch { runCatching { com.optionslab.app.ira.IraSundayReview.catchUp(app) } }
        }
    }

    /** Android asks for memory back: counted in the app's health, and the caches that are safe to drop are dropped. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Memory audit (14 Oct): counted, and what [com.optionslab.ira.MemoryPlan] lists for the level freed on a background
        // thread - never the position watch, its prices, its checks or their cadence.
        runCatching { com.optionslab.app.data.AppHealth.trim(level) }
        // 18 Oct, review: the settings still in memory go to disk first, on a background thread (a low-memory kill may follow).
        runCatching { SecurePrefs.flushSoon() }
    }

    /**
     * Older versions could download market data from Dhan (and build a learned graph from it); the app now uses Zerodha only.
     * What they left on this phone goes, off the main thread: the stored data and its import/pack folders, the learned graph,
     * the Dhan client ID, access token and choices in the settings vault, any queued download/import/graph work, and the
     * read grants kept on picked pack files (nothing else in the app keeps such grants). Each step is harmless when there is
     * nothing to remove. 22 Oct (the overnight kills): once per installed app, not at every start (an hourly night job starts
     * the app; six WorkManager cancels and a settings scan each time) - nothing makes these again once they are gone.
     */
    private fun removeOldMarketDataStore() {
        val c = applicationContext
        Thread { runCatching { OldStore.removeOnce(c) } }.start()
    }

    /** The old Dhan store's clean-up (22 Oct: once per installed app). Internal: the tests run it. */
    internal object OldStore {
        private const val DONE = "cleanup.dhan"

        /** The clean-up, unless it was done for this installed app; true when it ran. Off the main thread only. */
        fun removeOnce(c: android.content.Context): Boolean {
            val installed = com.optionslab.app.work.NightMarks.installed(c).toString()
            if (com.optionslab.app.work.NightMarks.get(c, DONE) == installed) return false
            // Marked done only once the old Dhan settings are really gone from the disk (an unreadable vault or a failed write:
            // tried again at the next start).
            if (removeNow(c)) com.optionslab.app.work.NightMarks.put(c, DONE, installed)
            return true
        }

        /** True when no old Dhan setting is left. */
        private fun removeNow(c: android.content.Context): Boolean {
            runCatching { java.io.File(c.filesDir, "dhan").deleteRecursively() }
            runCatching { java.io.File(c.filesDir, "dhan.import").deleteRecursively() }
            runCatching { java.io.File(c.filesDir, "neuro").deleteRecursively() }
            runCatching { java.io.File(c.cacheDir, "dhan-pack").deleteRecursively() }
            runCatching {
                val old = SecurePrefs.keys("dhan.")
                if (old.isNotEmpty()) SecurePrefs.putAll(old.associateWith { null })
            }
            runCatching {
                val wm = androidx.work.WorkManager.getInstance(c)
                listOf("dhan.download.now", "dhan.download.auto", "dhan.import", "neuro.after", "neuro.full", "neuro.now")
                    .forEach { runCatching { wm.cancelUniqueWork(it) } }
            }
            // Done only when the settings were read (an unreadable vault shows no keys at all) and the removal is on disk.
            return runCatching { !SecurePrefs.unreadable && SecurePrefs.keys("dhan.").isEmpty() && SecurePrefs.flush() && !SecurePrefs.unreadable }
                .getOrDefault(false)
        }
    }
}
