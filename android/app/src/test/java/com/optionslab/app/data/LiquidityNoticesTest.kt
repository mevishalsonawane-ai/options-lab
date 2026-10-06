package com.optionslab.app.data

import android.app.Notification
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.app.work.LiquidityNotices
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.ira.LiquidityNotice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Liquidity 15+5's own trade notifications on the PAPER account (LiquidityArmTest's failed-break day: the 5-minute book
 * buys the 54,000 CE at 13:05 and sells it at 13:10): one entry notice and one exit notice, never twice, in words Boss can
 * read alone - and with "hide figures on the lock screen" on, no rupee amount and no option price.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiquidityNoticesTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|LIQCE"

    private fun bar(k: Int): DoubleArray = when (k) {
        20 -> doubleArrayOf(54_050.0, 54_100.0, 54_040.0, 54_060.0)
        26 -> doubleArrayOf(54_040.0, 54_070.0, 54_020.0, 54_030.0)
        45 -> doubleArrayOf(54_020.0, 54_150.0, 54_015.0, 54_140.0)
        46 -> doubleArrayOf(54_140.0, 54_145.0, 54_080.0, 54_090.0)      // closes back under 54,100: the failed break
        else -> doubleArrayOf(54_000.0, 54_010.0, 53_990.0, 54_000.0)
    }

    private fun feed(t: LocalDateTime): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }
        .filter { !it.plusMinutes(1).isAfter(t) }
        .map { start ->
            val b = bar((start.hour * 60 + start.minute - (9 * 60 + 15)) / 5)
            Upstox.Bar(start.atZone(IST).toEpochSecond(), b[0], b[1], b[2], b[3], 1000, 0)
        }

    @Before fun up() {
        upstox = FakeUpstox()
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        Background.grantNotifications(context)
        LiquidityNotices.resetForTest()
        day = AutomationSupport.tradingDayAt(12, 50).toLocalDate()
        at(LocalTime.of(12, 50))
        val expiry = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.CE, 30, ceKey, "BANKNIFTY-LIQ-54000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.PE, 30, "NSE_FO|LIQPE0", "BANKNIFTY-LIQ-54000PE")))
        upstox.price(ceKey, 300.0)
        OrbArms.testIndexBars = { t -> feed(t) }
        OrbArms.testHistoryBars = { emptyList() }
        runBlocking { OrbArms.setLiquidityLots(1, "test") }
    }

    @After fun down() {
        AutomationSupport.realClocks()
        Market.testClock = null
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(t: LocalTime) {
        val z = day.atTime(t).atZone(IST)
        OrbArms.testNow = z
        Market.testClock = Clock.fixed(z.toInstant(), IST)
    }

    private fun passes(from: LocalTime, until: LocalTime) {
        var t = from
        while (!t.isAfter(until)) { at(t); runBlocking { OrbArms.tick() }; t = t.plusMinutes(1) }
    }

    private fun shown(prefix: String): List<Notification> =
        Background.notifications(context).allNotifications.filter { Background.title(it)?.startsWith(prefix) == true }

    private fun words(n: Notification): String =
        (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)).toString()

    private fun trade() {
        val msg = runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }
        assertTrue(msg, msg.startsWith("Liquidity 15+5 armed on paper"))
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
    }

    @Test fun anEntryAndAnExitEachPostExactlyOneNotification() {
        AppSettings.save(AppSettings.load().copy(hideAmountsOnLockScreen = false))
        trade()
        val entries = shown("Liquidity 15+5 bought")
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("Liquidity 15+5 bought BANKNIFTY 54,000 CE · Paper", Background.title(e))
        val ew = words(e)
        assertTrue(ew, ew.startsWith("BANKNIFTY 5-min · 1 lot (30) @ 30"))
        assertTrue(ew, ew.contains("Broke 54,100 · no level ahead (no target)"))
        assertTrue(ew, ew.contains("index 54,070 (30 pts back below)"))
        assertTrue(ew, ew.contains("Time stop: out at 13:25 unless up 5%"))
        assertEquals(0, shown("Liquidity 15+5 sold").size)
        passes(LocalTime.of(13, 6), LocalTime.of(13, 10))
        val exits = shown("Liquidity 15+5 sold")
        assertEquals(1, exits.size)
        val x = exits.single()
        assertEquals("Liquidity 15+5 sold BANKNIFTY 54,000 CE · failed break", Background.title(x))
        val xw = words(x)
        assertTrue(xw, xw.startsWith("Failed break: a bar closed back below 54,100"))
        assertTrue(xw, xw.contains("held 5 min"))
        assertTrue(xw, xw.contains("P&L ") && xw.contains("₹") && xw.contains("a lot)"))
        assertTrue(xw, xw.contains("Today's Liquidity: 1 trade, "))
        // More passes, and a restart: each event is told once only.
        passes(LocalTime.of(13, 11), LocalTime.of(13, 15))
        AutomationSupport.reloadFromDisk(OrbArms)
        passes(LocalTime.of(13, 16), LocalTime.of(13, 18))
        assertEquals(1, shown("Liquidity 15+5 bought").size)
        assertEquals(1, shown("Liquidity 15+5 sold").size)
    }

    @Test fun hideFiguresTakesTheRupeesAndPricesOut() {
        assertTrue("hidden by default", AppSettings.load().hideAmountsOnLockScreen)
        trade()
        val e = shown("Liquidity 15+5 bought").single()
        assertEquals(Notification.VISIBILITY_PRIVATE, e.visibility)
        val ew = words(e)
        assertFalse(ew, ew.contains("@") || ew.contains("₹") || ew.contains("300."))
        assertTrue("the levels stay: $ew", ew.contains("Broke 54,100"))
        passes(LocalTime.of(13, 6), LocalTime.of(13, 10))
        val x = shown("Liquidity 15+5 sold").single()
        val xw = words(x)
        assertFalse(xw, xw.contains("₹") || xw.contains("→"))
        assertTrue(xw, xw.contains("after charges"))
        assertEquals("Unlock to read", Background.text(x.publicVersion!!))
    }

    @Test fun aSkippedBreakIsToldOnlyWithItsSwitchOnAndOnce() {
        val skip = LiquidityNotice.Skip(LiquidityNotice.Book("BANKNIFTY", 5), 54_180.0, 22.0, 30.0)
        val bar = day.atTime(11, 0)
        LiquidityNotices.skip(context, "liquidity5", bar, skip, bar.plusMinutes(5))
        assertEquals("off by default", 0, shown("Liquidity 15+5 skipped").size)
        com.optionslab.app.ira.Automations.set(com.optionslab.app.ira.Automations.Auto.LIQSKIP, true)
        assertTrue(com.optionslab.app.ira.Automations.on(com.optionslab.app.ira.Automations.Auto.LIQSKIP))
        LiquidityNotices.skip(context, "liquidity5", bar, skip, bar.plusMinutes(5))
        LiquidityNotices.skip(context, "liquidity5", bar, skip, bar.plusMinutes(20))
        val n = shown("Liquidity 15+5 skipped").single()
        assertEquals("Skipped: BankNifty 5-min break of 54,180 — only 22 pts to the next level (needs 30)",
            n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        // Market alerts off: quiet, whatever the switch beneath it.
        com.optionslab.app.ira.Automations.set(com.optionslab.app.ira.Automations.Group.MARKET, false)
        assertFalse(com.optionslab.app.ira.Automations.on(com.optionslab.app.ira.Automations.Auto.LIQSKIP))
        assertTrue(com.optionslab.app.ira.Automations.ownSwitch(com.optionslab.app.ira.Automations.Auto.LIQSKIP))
        com.optionslab.app.ira.Automations.set(com.optionslab.app.ira.Automations.Group.MARKET, true)
    }
}
