package com.optionslab.app.testing

import android.content.Context
import com.optionslab.app.data.ExpirySquareOff
import com.optionslab.app.data.Market
import com.optionslab.app.data.OrbArms
import com.optionslab.app.data.PineAuto
import com.optionslab.app.data.PineScripts
import com.optionslab.app.security.Integrity
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Alerts
import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * TEST ONLY helpers for the strategies and automation tests (Strategies, ORB arms, Pine auto-trade,
 * expiry square-off, loss breaker). Nothing here reaches the network or an APK.
 *
 * Reflection is used only on test state the app keeps in memory for a whole process (a cache, a
 * "told once" set, the security report cache), so each test starts on a fresh phone without a seam.
 */
object AutomationSupport {

    /** Today (IST, as the app sees it) at [h]:[m]. */
    fun todayAt(h: Int, m: Int = 0): ZonedDateTime = Market.today().atTime(LocalTime.of(h, m)).atZone(IST)

    /** The first NSE trading day on or after today, at [h]:[m] (Pine only decides while the market is open). */
    fun tradingDayAt(h: Int, m: Int = 0): ZonedDateTime {
        var d = Market.today()
        while (!Market.isTradingDay(d)) d = d.plusDays(1)
        return d.atTime(LocalTime.of(h, m)).atZone(IST)
    }

    /** Now, as a time today before 15:00 and never later than the real clock (Zerodha's order times come from it). */
    fun earlierToday(): ZonedDateTime {
        val real = ZonedDateTime.now(IST).minusSeconds(5)
        return if (real.hour * 60 + real.minute >= 15 * 60) real.with(LocalTime.of(15, 0)) else real
    }

    /** Live mode with real orders on, no entry cut-off (tests run at any hour). */
    fun liveSettings(live: Boolean = true) {
        SecurePrefs.putAll(mapOf("k.mode" to if (live) "live" else "sandbox", "k.allow" to live, "g.cutoff" to -1))
    }

    /** The security check as the app last saw it: clean, or a device that failed it. */
    fun security(compromised: Boolean) {
        val f = if (compromised) listOf(Integrity.Finding("Root", Integrity.Severity.DANGER, "su binaries, Magisk or test-keys present"))
        else listOf(Integrity.Finding("Root", Integrity.Severity.OK, "no root indicators found"))
        static(Integrity::class.java, "cached", f)
        static(Integrity::class.java, "cachedAt", android.os.SystemClock.elapsedRealtime())
    }

    /** Forget the expiry square-off's "already told today" set (it lives for the whole process). */
    fun resetExpiryTold() {
        @Suppress("UNCHECKED_CAST")
        (field(ExpirySquareOff::class.java, "told").get(ExpirySquareOff) as MutableSet<String>).let { synchronized(it) { it.clear() } }
    }

    /** Drop a store's in-memory copy so the next read comes from its encrypted file, as after a restart. */
    fun reloadFromDisk(store: Any) = static(store.javaClass, "cache", null)

    /** Every test clock back to the real one. */
    fun realClocks() {
        OrbArms.testNow = null; OrbArms.testIndexBars = null; OrbArms.testHistoryBars = null; OrbArms.testOtherIndexBars = null; OrbArms.testHeroBars = null; ExpirySquareOff.testNow = null; PineAuto.testNow = null; PineAuto.testBars = null
    }

    fun clearAlerts() = Alerts.queue.value.forEach { Alerts.dismiss(it.id) }
    fun alerts(): List<String> = Alerts.queue.value.map { (it.title?.let { t -> "$t: " } ?: "") + it.text }

    /** PineScripts is not started by TestApp (it has a loader thread): start it on this test's empty directory. */
    fun freshPine(context: Context) {
        PineScripts.init(context)
        PineScripts.wipe()
    }

    /** The day's Upstox option list the paper account and the auto-traders pick contracts from (no download). */
    fun contracts(context: Context, list: List<Upstox.Contract>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONArray().put(it.underlying).put(it.expiry.toString()).put(it.strike).put(it.right.name).put(it.lotSize).put(it.instrumentKey).put(it.tradingSymbol)) }
        File(context.filesDir, "contracts.json").writeText(JSONObject().put("day", Market.today().toString()).put("c", arr).toString())
    }

    /**
     * Write the ORB arms' encrypted state as the app would have saved it, and make the arms read it. Without a "migrated"
     * list it is a book saved after every one-time change (06 Oct's switch-off and retirement), so loading it changes
     * nothing; a test of a change passes its own list (an empty one: saved before both).
     */
    fun orbState(context: Context, o: JSONObject) {
        if (!o.has("migrated")) o.put("migrated", JSONArray().put(OrbArms.OFF_LOSERS).put(com.optionslab.engine.orb.RetiredArms.MIGRATION))
        OrbArms.wipe()
        Vault.writeFile(File(context.filesDir, "orb.vault"), o.toString().toByteArray(Charsets.UTF_8))
    }

    /** One 5-minute candle starting at [start]. */
    fun bar(start: ZonedDateTime, close: Double) = Upstox.Bar(start.toEpochSecond(), close, close + 5, close - 5, close, 1000, 0)

    /** [n] completed 5-minute candles ending with the one that closed just before [now], the last one at [lastClose]. */
    fun bars(now: ZonedDateTime, closes: List<Double>): List<Upstox.Bar> {
        val lastStart = now.withSecond(0).withNano(0).let { it.minusMinutes((it.minute % 5).toLong()) }.minusMinutes(5)
        return closes.mapIndexed { i, c -> bar(lastStart.minusMinutes(5L * (closes.size - 1 - i)), c) }
    }

    fun expiryAfter(days: Long): LocalDate = Market.today().plusDays(days)

    /** NIFTY 24500 CE, [days] out, as Zerodha lists it (lot 75), and the leg that names it by strike. */
    const val NIFTY_CE = "NIFTY26OCT24500CE"
    const val NIFTY_PE = "NIFTY26OCT24500PE"
    fun niftyOptions(kite: FakeKite, expiry: LocalDate = expiryAfter(9)) {
        kite.instruments += FakeKite.Ins(12_345_678, NIFTY_CE, "NIFTY", expiry, 24_500.0, "CE", 75)
        kite.instruments += FakeKite.Ins(12_345_679, NIFTY_PE, "NIFTY", expiry, 24_500.0, "PE", 75)
    }

    /** A saved-strategy definition: one leg per (position, CE/PE) at the 24500 strike, weekly expiry, a 30-point stop. */
    fun strategy(name: String = "Test basket", vararg legs: Pair<com.optionslab.engine.strategy.Position, com.optionslab.engine.strategy.OptionType>,
                 lots: Int = 1, sl: Double? = 30.0) = com.optionslab.engine.strategy.StrategyDef(
        name = name, underlying = "NIFTY", underlyingExchange = "NSE_INDEX",
        entryTime = LocalTime.of(9, 20), exitTime = LocalTime.of(15, 15),
        legs = legs.mapIndexed { i, (pos, t) ->
            com.optionslab.engine.strategy.LegDef(i + 1, com.optionslab.engine.strategy.Segment.OPTIONS, pos, lots, t,
                com.optionslab.engine.strategy.StrikeMode.STRIKE, strike = 24_500.0, expiry = "weekly", slPts = sl)
        },
    )

    private fun field(c: Class<*>, name: String) = c.getDeclaredField(name).apply { isAccessible = true }
    private fun static(c: Class<*>, name: String, v: Any?) { field(c, name).set(null, v) }

}

/**
 * A test of this area still running after [seconds] prints its stack and is interrupted, so a stuck wait fails with
 * its place instead of holding the whole CI job (Robolectric runs every test on one thread).
 */
class AutomationWatchdog(private val seconds: Long = 240) : org.junit.rules.TestRule {
    override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) =
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val thread = Thread.currentThread()
                val timer = java.util.Timer("automation-watchdog ${description.methodName}", true)
                var dumped = false
                timer.schedule(object : java.util.TimerTask() {
                    override fun run() {
                        if (!dumped) {
                            dumped = true
                            System.err.println("WATCHDOG ${description.displayName} still running after $seconds s; test thread:")
                            thread.stackTrace.forEach { System.err.println("    at $it") }
                        }
                        thread.interrupt()
                    }
                }, seconds * 1000, 5_000)
                try { base.evaluate() } finally { timer.cancel(); Thread.interrupted() }
            }
        }
}
