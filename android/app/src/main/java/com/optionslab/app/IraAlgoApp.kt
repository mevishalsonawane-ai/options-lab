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
    companion object { const val CRASH_FILE = "last_crash.txt" }

    override fun onCreate() {
        super.onCreate()
        // If the app ever crashes, keep what went wrong (on this phone only, never logged or sent)
        // so the next start can show it and the owner can pass it on.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "${java.time.ZonedDateTime.now()} · thread ${t.name} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        e.stackTraceToString().take(12_000))
            }
            previous?.uncaughtException(t, e)
        }
        SecurePrefs.init(this)
        Ledger.init(this)
        Alarms.init(this)
        Store.init(this)
        Market.init(this)
        com.optionslab.app.data.Holidays.init(this)
        Broker.init(this)
        com.optionslab.app.data.Paper.init(this)
        com.optionslab.app.data.History.init(this)
        com.optionslab.app.data.Strategies.init(this)
        com.optionslab.app.data.OrbArms.init(this)
        com.optionslab.app.data.Protections.init(this)
        com.optionslab.app.data.TradeBook.init(this)
        com.optionslab.app.data.Journal.init(this)
        Notifier.createChannels(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(SessionLock)
        // Keystore work stays off the main thread.
        Thread {
            // First start after a restore: everything restored comes back disarmed and paper only.
            if (SecurePrefs.getBoolean(com.optionslab.app.data.Backup.DISARM, false)) runCatching {
                kotlinx.coroutines.runBlocking {
                    com.optionslab.app.data.Strategies.disarmAll()
                    com.optionslab.app.data.OrbArms.disarmAll()
                }
                SecurePrefs.put(com.optionslab.app.data.Backup.DISARM, null)
            }
            runCatching { Jobs.scheduleAll(this) }
        }.start()
    }
}
