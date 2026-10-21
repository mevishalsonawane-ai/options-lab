package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.risk.AccountGuard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 21 Oct, enhancement round 4 (idea 3): the payout-aware drawdown ([PayoutWatch]) with the account guard ([Guard]). In shadow (the
 * default) nothing the guard says or does changes; on, the refusal says the payouts explain it and Bot settings offers to lower the
 * peak by them, only with the PIN. Either way the refusal stands and the kill switch goes on as before.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PayoutWatchTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val peak = 500_000.0

    @Before fun up() {
        PayoutWatch.resetForTest()
        kite = FakeKite()
        kite.login()
        AppSettings.save(AppSettings.load().copy(guardKill = false, guardDrawdownPct = 10.0))
        Guard.setPeak(live = true, peak)
    }

    @After fun down() { PayoutWatch.resetForTest(); kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    private val yesterday get() = Market.today().minusDays(1)
    /** Rs 1 lakh taken out yesterday: today's opening balance is 4 lakh against the 5 lakh peak (20% "down", limit 10%). */
    private fun account(equity: Double = 400_000.0) = AccountGuard.Account(capital = equity, equity = equity, peakEquity = peak, dayPnl = 0.0,
        holdings = emptyList(), ordersToday = 0, minuteOfDay = 600)
    private val entry = AccountGuard.Order("NIFTY26OCT24500CE", "BUY", 75, 75, 100.0, "NIFTY", Market.today().plusDays(7), "CE")
    private fun peakNow() = SecurePrefs.getDouble("guard.peak.live", Double.NaN)

    @Test fun inShadowTheRefusalAndTheKillSwitchAreAsBeforeAndItIsOnlyLogged() {
        PayoutWatch.keepForTest(yesterday, 100_000.0, peak)
        val out = Guard.check(entry, account())
        assertTrue(out.toString(), out.any { it.startsWith("Drawdown limit") && it.contains("its peak") })
        assertTrue("no payout words in shadow", out.none { it.contains("Payouts of") })
        assertTrue("the kill switch goes on as before", AppSettings.load().guardKill)
        assertTrue(PayoutWatch.line(), PayoutWatch.line().contains("Shadow: logged only"))
        assertNull("nothing offered in shadow", PayoutWatch.view.value.offer)
        assertEquals("the peak untouched", peak, peakNow(), 0.0)
    }

    @Test fun onTheRefusalSaysSoTheKillSwitchStillGoesOnAndThePeakIsOffered() {
        PayoutWatch.resetForTest(on = true)
        PayoutWatch.keepForTest(yesterday, 100_000.0, peak)
        val out = Guard.check(entry, account())
        assertTrue(out.any { it.startsWith("Drawdown limit") })
        assertTrue(out.toString(), out.any { it.startsWith("Payouts of Rs 1,00,000") || it.startsWith("Payouts of Rs 100,000") })
        assertTrue("never kept off by itself", AppSettings.load().guardKill)
        assertEquals(100_000.0, PayoutWatch.view.value.offer!!, 0.0)
        assertEquals("nothing lowered until Boss confirms", peak, peakNow(), 0.0)
    }

    @Test fun loweringThePeakTakesThePinAndNeverClearsTheKillSwitch() {
        PayoutWatch.resetForTest(on = true)
        PayoutWatch.keepForTest(yesterday, 100_000.0, peak)
        Guard.check(entry, account())
        assertTrue(PayoutWatch.lowerPeak(pinConfirmed = false).contains("PIN"))
        assertEquals(peak, peakNow(), 0.0)
        val said = PayoutWatch.lowerPeak(pinConfirmed = true)
        assertTrue(said, said.contains("clear it yourself"))
        assertEquals(400_000.0, peakNow(), 0.0)
        assertTrue("the kill switch is left as it was", AppSettings.load().guardKill)
        assertTrue("the payouts are used", PayoutWatch.payoutsForTest().isEmpty())
        assertNull(PayoutWatch.view.value.offer)
        // Asked again: nothing more to lower.
        assertTrue(PayoutWatch.lowerPeak(pinConfirmed = true).startsWith("No drawdown"))
        assertEquals(400_000.0, peakNow(), 0.0)
        // The next check against the lowered peak: no drawdown refusal.
        val out = Guard.check(entry, account().copy(peakEquity = peakNow()))
        assertTrue(out.toString(), out.none { it.startsWith("Drawdown limit") })
    }

    @Test fun aRealLossBesideAPayoutIsNeverOffered() {
        PayoutWatch.resetForTest(on = true)
        PayoutWatch.keepForTest(yesterday, 50_000.0, peak)
        val out = Guard.check(entry, account(equity = 390_000.0))
        assertTrue(out.any { it.startsWith("Drawdown limit") })
        assertTrue(out.none { it.contains("Payouts of") })
        assertNull(PayoutWatch.view.value.offer)
        assertTrue(PayoutWatch.lowerPeak(pinConfirmed = true).startsWith("No drawdown"))
    }

    @Test fun todaysPayoutAndOneUnderAnOlderPeakDoNotCount() {
        PayoutWatch.resetForTest(on = true)
        PayoutWatch.keepForTest(Market.today(), 100_000.0, peak)
        PayoutWatch.keepForTest(yesterday.minusDays(1), 100_000.0, 450_000.0)
        val out = Guard.check(entry, account())
        assertTrue(out.none { it.contains("Payouts of") })
        assertNull(PayoutWatch.view.value.offer)
    }

    @Test fun thePeakMovedSinceIsNeverLowered() {
        PayoutWatch.resetForTest(on = true)
        PayoutWatch.keepForTest(yesterday, 100_000.0, peak)
        Guard.check(entry, account())
        Guard.setPeak(live = true, 520_000.0)
        assertTrue(PayoutWatch.lowerPeak(pinConfirmed = true).contains("changed"))
        assertEquals(520_000.0, peakNow(), 0.0)
    }

    @Test fun offLoweringIsRefused() {
        PayoutWatch.keepForTest(yesterday, 100_000.0, peak)
        Guard.check(entry, account())
        assertTrue(PayoutWatch.lowerPeak(pinConfirmed = true).contains("on first"))
        assertEquals(peak, peakNow(), 0.0)
    }

    @Test fun aMarginsReadKeepsTheDaysPayoutWithThePeak() {
        kite.payoutToday = 25_000.0
        runBlocking { Broker.funds() }
        val deadline = System.currentTimeMillis() + 5_000
        while (PayoutWatch.payoutsForTest().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val p = PayoutWatch.payoutsForTest().single()
        assertEquals(Market.today(), p.day)
        assertEquals(25_000.0, p.amount, 0.0)
        assertEquals(peak, p.peak, 0.0)
    }

    /** Review: a payout requested and then cancelled the same day reads back as 0 and is no longer counted. */
    @Test fun aPayoutCancelledTheSameDayIsDropped() {
        kite.payoutToday = 25_000.0
        runBlocking { Broker.funds() }
        val deadline = System.currentTimeMillis() + 5_000
        while (PayoutWatch.payoutsForTest().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, PayoutWatch.payoutsForTest().size)
        kite.payoutToday = 0.0
        Thread.sleep(Broker.SHARE_MS + 300)                         // past the shared margins read
        runBlocking { Broker.funds() }
        val end = System.currentTimeMillis() + 5_000
        while (PayoutWatch.payoutsForTest().isNotEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(PayoutWatch.payoutsForTest().isEmpty())
    }

    @Test fun noPayoutKeepsNothing() {
        kite.payoutToday = 0.0
        runBlocking { Broker.funds() }
        Thread.sleep(200)
        assertTrue(PayoutWatch.payoutsForTest().isEmpty())
    }

    @Test fun theGuardsCheckReadsNothingFromTheSettingsVault() {
        PayoutWatch.resetForTest(on = true)
        PayoutWatch.keepForTest(yesterday, 100_000.0, peak)
        val a = account()
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        PayoutWatch.drawdown(a, 10.0)
        SecurePrefs.countedThread = null
        assertEquals(0L, SecurePrefs.readsForTest.get())
    }

    @Test fun theSwitchIsOffByDefaultSavedAndSaidPlainly() {
        PayoutWatch.load()
        assertFalse(PayoutWatch.isOn())
        assertTrue(PayoutWatch.setOn(true).startsWith("On:"))
        PayoutWatch.resetForTest()
        PayoutWatch.load()
        assertTrue("read back from the settings", PayoutWatch.isOn())
        assertTrue(PayoutWatch.setOn(false).startsWith("Shadow"))
        PayoutWatch.load()
        assertFalse(PayoutWatch.isOn())
    }

    @Test fun paperNeverConsultsIt() {
        PayoutWatch.resetForTest(on = true)
        PayoutWatch.keepForTest(yesterday, 100_000.0, peak)
        assertTrue(Guard.check(entry, account(), paper = true).isEmpty())
        assertNull(PayoutWatch.view.value.offer)
    }
}
