package com.optionslab.app.testing

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.optionslab.app.data.Broker
import com.optionslab.app.data.StaticIp
import com.optionslab.app.security.PinLock
import com.optionslab.app.ui.AppModel
import com.optionslab.app.work.Alerts
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/**
 * TEST ONLY (area B: the Zerodha pages and the live money path). Shared set-up for the tests in
 * ui/screens/BrokerScreensTest, ui/OrderFlowLive2Test, data/LiveSelfTestTest and data/GttLiveTest.
 * Every value here is made up; nothing reaches the internet ([FakeKite] answers on localhost).
 */
object BrokerArea {
    const val PIN = "246813"
    const val WRONG_PIN = "135790"
    const val KEY = "testkey"
    /** A made-up API secret (letters and digits, as Kite's are). It must never appear on screen or on the wire. */
    const val SECRET = "sekr1tNotReal9"

    /** A real [AppModel] owned by [store] (the test clears the store, which ends its coroutines). */
    fun model(app: Application, store: ViewModelStore): AppModel =
        ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[AppModel::class.java]

    /** The app PIN and the Kite keys saved as the app saves them (the secret sealed with the PIN). Before [FakeKite.login]. */
    fun saveKeys(pin: String = PIN) {
        PinLock.setPin(pin.toCharArray())
        Broker.saveCredentials(KEY, SECRET, pin.toCharArray())
    }

    /**
     * The phone's public IP as [StaticIp] last read it (its one-minute cache), so the static-IP checks
     * run without asking api.ipify.org; null clears it (then a read would go to the network guard).
     */
    fun phoneIp(ip: String?) {
        val f = StaticIp::class.java.getDeclaredField("cached")
        f.isAccessible = true
        f.set(null, ip?.let { System.currentTimeMillis() to it })
    }

    /** Alerts are an app-wide queue: a banner left by an earlier test must not satisfy this one's check. */
    fun clearAlerts() = Alerts.queue.value.forEach { Alerts.dismiss(it.id) }

    fun alerted(text: String, substring: Boolean = false) =
        Alerts.queue.value.any { if (substring) it.text.contains(text) else it.text == text }

    /** Poll [get] (running the main looper, where the model's coroutines resume) until it answers. */
    fun <T> await(what: String, timeoutMs: Long = 20_000, get: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            get()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** Let background work settle (the main looper run) for [ms]. */
    fun settle(ms: Long = 300) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
    }

    /** NIFTY options listed at the fake Kite: [strikes] x CE/PE for each expiry, lot 75. Returns the symbols by (expiry, strike, right). */
    fun listNifty(kite: FakeKite, expiries: List<LocalDate>, strikes: List<Double> = listOf(24_400.0, 24_500.0, 24_600.0), lot: Int = 75, name: String = "NIFTY") {
        var token = if (name == "NIFTY") 10_000_000L else 20_000_000L
        for (e in expiries) for (k in strikes) for (r in listOf("CE", "PE"))
            kite.instruments += FakeKite.Ins(token++, symbol(name, e, k, r), name, e, k, r, lot)
    }

    /** A Kite-style monthly-looking symbol (the fake only needs it unique and starting with the underlying and digits). */
    fun symbol(name: String, e: LocalDate, strike: Double, right: String) =
        "$name${e.year % 100}${"%02d".format(e.monthValue)}${"%02d".format(e.dayOfMonth)}${strike.toInt()}$right"
}
