package com.optionslab.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.optionslab.app.data.Alarms
import com.optionslab.app.data.Ledger
import com.optionslab.app.data.Market
import com.optionslab.app.data.Store
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.SessionLock
import com.optionslab.app.work.Jobs
import com.optionslab.app.work.Notifier

class OptionsLabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SecurePrefs.init(this)
        Ledger.init(this)
        Alarms.init(this)
        Store.init(this)
        Market.init(this)
        Notifier.createChannels(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(SessionLock)
        // Keystore work stays off the main thread.
        Thread { runCatching { Jobs.scheduleAll(this) } }.start()
    }
}
