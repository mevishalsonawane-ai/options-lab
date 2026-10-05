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
        // If the app ever crashes, keep what went wrong - class names and stack frames only, no
        // messages, encrypted in the vault, on this phone only, never logged or sent - so the
        // next start can show it and the owner can pass it on.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
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
        Ledger.init(this)
        Alarms.init(this)
        Store.init(this)
        Market.init(this)
        com.optionslab.app.data.Holidays.init(this)
        Broker.init(this)
        com.optionslab.app.data.StaticIp.init(this)
        com.optionslab.app.data.Paper.init(this)
        com.optionslab.app.data.History.init(this)
        com.optionslab.app.data.Strategies.init(this)
        com.optionslab.app.data.OrbArms.init(this)
        com.optionslab.app.data.PineScripts.init(this)
        com.optionslab.app.data.PineAuto.init(this)
        com.optionslab.app.data.Protections.init(this)
        com.optionslab.app.data.TradeBook.init(this)
        com.optionslab.app.data.Journal.init(this)
        com.optionslab.app.data.Diag.init(this)
        com.optionslab.app.data.KiteStream.init(this)
        com.optionslab.app.data.GoldPaper.init(this)
        com.optionslab.app.data.GoldTrendPaper.init(this)
        com.optionslab.app.data.GoldDipPaper.init(this)
        com.optionslab.app.data.GoldTasPaper.init(this)
        com.optionslab.app.ira.IraHub.init(this)
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
    }
}
