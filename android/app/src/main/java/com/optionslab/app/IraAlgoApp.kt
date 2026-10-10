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
        Ledger.init(this)
        Alarms.init(this)
        Store.init(this)
        removeOldMarketDataStore()
        Market.init(this)
        com.optionslab.app.data.Holidays.init(this)
        com.optionslab.app.data.McxMarket.init(this)
        Broker.init(this)
        com.optionslab.app.data.StaticIp.init(this)
        com.optionslab.app.data.Paper.init(this)
        com.optionslab.app.data.History.init(this)
        com.optionslab.app.data.Strategies.init(this)
        com.optionslab.app.data.OrbArms.init(this)
        com.optionslab.app.data.NightArm.init(this)
        com.optionslab.app.data.VixDivArm.init(this)
        com.optionslab.app.data.McxPaperArms.init(this)
        com.optionslab.app.data.ShadowArms.init(this)
        com.optionslab.app.data.MarketRecorder.init(this)
        com.optionslab.app.data.PineScripts.init(this)
        com.optionslab.app.data.PineAuto.init(this)
        com.optionslab.app.data.Protections.init(this)
        com.optionslab.app.data.TradeBook.init(this)
        com.optionslab.app.data.Journal.init(this)
        com.optionslab.app.data.Diag.init(this)
        com.optionslab.app.data.KiteStream.init(this)
        // The live order flow (shown, logged beside each signal; it can only ever skip an entry where Boss set CONFIRM).
        com.optionslab.app.data.FlowGate.init(this)
        com.optionslab.app.data.SmartWorkers.init(this)
        com.optionslab.app.data.OrderFlowLive.init(this)
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
        ProcessLifecycleOwner.get().lifecycle.addObserver(SessionLock)
        // Keystore work stays off the main thread.
        Thread { runCatching { Jobs.scheduleAll(this) } }.start()
        com.optionslab.app.data.Speed.startMs = android.os.SystemClock.uptimeMillis() - startAt
        com.optionslab.app.data.Speed.startSlowest = slowest?.let { (n, ms) -> "$n $ms ms" }
    }

    /**
     * Older versions could download market data from Dhan (and build a learned graph from it); the app now uses Zerodha only.
     * What they left on this phone goes, off the main thread: the stored data and its import/pack folders, the learned graph,
     * the Dhan client ID, access token and choices in the settings vault, any queued download/import/graph work, and the
     * read grants kept on picked pack files (nothing else in the app keeps such grants). Each step is harmless when there is
     * nothing to remove.
     */
    private fun removeOldMarketDataStore() {
        val c = applicationContext
        Thread {
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
        }.start()
    }
}
