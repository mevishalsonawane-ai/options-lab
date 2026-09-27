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
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

/**
 * TEST ONLY (area B: the Zerodha pages and the live money path). Shared set-up for the tests in
 * ui/screens/BrokerScreensTest, ui/OrderFlowLive2Test, data/LiveSelfTestTest and data/GttLiveTest.
 * Every value here is made up; nothing reaches the internet ([FakeKite] answers on localhost).
 */
object BrokerArea {
    init { StallDump.start() }

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
    fun clearAlerts() { Alerts.queue.value.forEach { Alerts.dismiss(it.id) }; Alerts.forgetPosted() }

    fun alerted(text: String, substring: Boolean = false) =
        (Alerts.queue.value + Alerts.posted).any { if (substring) it.text.contains(text) else it.text == text }

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

/**
 * Screen-matrix base for area B. [lintKnown] is [ScreenTest.lint] with known layout bugs matched per
 * finding (a regex over the finding's text), so a screen with one known bug still fails on any other
 * error; when every error is a known one the test is skipped with "LAYOUT BUG (...)". [snap] saves every
 * window (a dialog is a window of its own).
 */
abstract class BrokerScreenBase(device: DeviceConfig) : ScreenTest(device) {
    @get:org.junit.Rule val watchdog = Watchdog()
    data class Known(val pattern: Regex, val bug: String)

    /** Set to true to list every finding as a skip (a first look at a new screen), never committed as true. */
    protected open val discovery: Boolean = false

    protected fun lintKnown(name: String, known: List<Known> = emptyList(), options: LayoutLint.Options = LayoutLint.Options()) {
        compose.waitForIdle()
        val findings = compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { LayoutLint.check(it, compose.density, options) }
        findings.forEach { println("LAYOUT $name ${device.name}: $it") }
        val errors = findings.filter { it.level == LayoutLint.Level.ERROR }
        if (errors.isEmpty()) return
        if (discovery) org.junit.Assume.assumeTrue("LAYOUT BUG ($name, ${device.name}): TRIAGE\n" + errors.joinToString("\n"), false)
        val matched = errors.associateWith { e -> known.firstOrNull { it.pattern.containsMatchIn(e.toString()) } }
        val other = matched.filterValues { it == null }.keys
        if (other.isNotEmpty()) org.junit.Assert.fail("Layout problems on $name (${device.name}):\n" + other.joinToString("\n"))
        val bugs = matched.values.filterNotNull().map { it.bug }.distinct()
        org.junit.Assume.assumeTrue("LAYOUT BUG ($name, ${device.name}): ${bugs.joinToString(" | ")}\n" + errors.joinToString("\n"), false)
    }

    protected fun snap(name: String, known: List<Known> = emptyList(), options: LayoutLint.Options = LayoutLint.Options()) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot())
        val nodes = roots.fetchSemanticsNodes()
        if (nodes.size <= 1) capture(name)
        else nodes.forEachIndexed { i, n ->
            if (n.size.width > 0 && n.size.height > 0)
                roots[i].captureRoboImage("build/outputs/roborazzi/${name}${if (i == 0) "" else "-layer$i"}_${device.name}.png")
        }
        lintKnown(name, known, options)
    }

    protected fun frames(n: Int = 12) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    /** The click-everything smoke runs on one set-up only (it is about behaviour, not layout), to keep CI time in bounds. */
    protected fun smokeOnce(skip: Set<String> = emptySet()) { if (device.name == "phone-font1.0-light") smokeEveryAction(skip) }

}

/**
 * A test that has not finished after [seconds] is interrupted (again every 5 s), so a wait that would
 * never end fails with the place it was stuck in its stack trace instead of stalling the whole CI job.
 */
class Watchdog(private val seconds: Long = 240) : org.junit.rules.TestRule {
    override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) =
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val thread = Thread.currentThread()
                val timer = java.util.Timer("watchdog ${description.methodName}", true)
                timer.schedule(object : java.util.TimerTask() { override fun run() { thread.interrupt() } }, seconds * 1000, 5_000)
                try { base.evaluate() } finally { timer.cancel(); Thread.interrupted() }
            }
        }
}

/** CI diagnostics for a job that stops making progress (see [start]). */
object StallDump {
    @Volatile private var started = false

    /**
     * Every 4 minutes, the stacks of the threads that run tests (the test worker and Robolectric's main thread)
     * go to the process's own stderr (System.err is captured per test), which shows in the CI log: a job that
     * stops making progress then shows where it sits, whether it is blocked or spinning.
     */
    @Synchronized fun start() {
        if (started) return
        started = true
        Thread({
            while (true) {
                try { Thread.sleep(240_000) } catch (_: InterruptedException) { return@Thread }
                runCatching {
                    val text = "AREA-B STACKS\n" + Thread.getAllStackTraces().entries
                        .filter { (t, _) -> t.name.contains("Main", true) || t.name.startsWith("Test worker") }
                        .joinToString("\n") { (t, st) -> "${t.name} (${t.state})\n" + st.take(40).joinToString("\n") { "    at $it" } } + "\n"
                    java.io.FileOutputStream(java.io.FileDescriptor.err).apply { write(text.toByteArray()); flush() }
                }
            }
        }, "area-b-stall-dump").apply { isDaemon = true }.start()
    }
}
