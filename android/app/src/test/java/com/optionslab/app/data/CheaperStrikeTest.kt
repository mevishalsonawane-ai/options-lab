package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime

/**
 * 20 Nov 2026 (Boss's exception: "if its says insufficient balance for any trade, then try cheaper options increment it 1 by 1"): the
 * cheaper strike on its own - the strike grid (a put lower, a call higher), a missing strike or one with no live quote skipped and counted,
 * at most three steps then a give-up, the free cash unknown, the arms it covers (by source), Zerodha's funds words, the chase bound, the
 * square-off time on expiry day and after the close, SENSEX on BFO; its switch (on by default, off one tap, on with the PIN), its record
 * across a restart and an erase, and no rupees in what it shows.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class CheaperStrikeTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val expiry = AutomationSupport.expiryAfter(7)
    private lateinit var now: LocalDateTime

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        CheaperStrike.resetForTest(on = true)
        now = AutomationSupport.earlierToday().toLocalDateTime().withNano(0)
    }

    @After fun down() {
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    // ---- the strike grid (pure) -------------------------------------------------------------------------------------------------

    /** Boss, 20 Nov: "100-point steps" - NIFTY 25000 PE -> 24900 -> 24800 -> 24700, CE -> 25100 -> 25200 -> 25300, on a 50-point list. */
    @Test fun aPutStepsLowerAndACallHigherA100PointsAStep() {
        val nifty = (240..260).map { it * 100.0 } + (240..259).map { it * 100.0 + 50 }          // NIFTY: 50 apart near the money
        assertEquals(listOf(24900.0, 24800.0, 24700.0), CheaperStrike.ladder(nifty, 25000.0, Right.PE))
        assertEquals(listOf(25100.0, 25200.0, 25300.0), CheaperStrike.ladder(nifty, 25000.0, Right.CE))
        // A 50-point list with the 100-point mark missing: the listed strike at or beyond the mark, before the next one.
        assertEquals(listOf(24850.0, 24800.0, 24700.0), CheaperStrike.ladder(nifty.filter { it != 24900.0 }, 25000.0, Right.PE))
        assertEquals(listOf(25150.0, 25200.0, 25300.0), CheaperStrike.ladder(nifty.filter { it != 25100.0 }, 25000.0, Right.CE))
        // An index whose own gap is wider than 100 steps by its gap.
        val wide = (100..140).map { it * 200.0 }
        assertEquals(listOf(23800.0, 23600.0, 23400.0), CheaperStrike.ladder(wide, 24000.0, Right.PE))
        assertEquals(listOf(24200.0, 24400.0, 24600.0), CheaperStrike.ladder(wide, 24000.0, Right.CE))
        val bank = (500..540).map { it * 100.0 }                                                     // BANKNIFTY: 100 apart
        assertEquals(listOf(51900.0, 51800.0, 51700.0), CheaperStrike.ladder(bank, 52000.0, Right.PE))
        assertEquals(listOf(52100.0, 52200.0, 52300.0), CheaperStrike.ladder(bank, 52000.0, Right.CE))
        // Not an option, too few strikes, no strike: nothing.
        assertTrue(CheaperStrike.ladder(bank, 52000.0, Right.IX).isEmpty())
        assertTrue(CheaperStrike.ladder(listOf(52000.0), 52000.0, Right.CE).isEmpty())
        assertTrue(CheaperStrike.ladder(emptyList(), 52000.0, Right.CE).isEmpty())
        assertTrue(CheaperStrike.ladder(bank, Double.NaN, Right.CE).isEmpty())
        // A strike missing from the list still takes its step (the grid is the commonest gap).
        val holed = bank.filter { it != 51900.0 }
        assertEquals(listOf(51900.0, 51800.0, 51700.0), CheaperStrike.ladder(holed, 52000.0, Right.PE))
        // Never below zero.
        assertEquals(listOf(100.0), CheaperStrike.ladder(listOf(100.0, 200.0, 300.0), 200.0, Right.PE))
    }

    @Test fun theFirstStrikeOneLotFitsIsChosenAMissingOrUnquotedOneCountsAsAStep() {
        val tries = listOf(CheaperStrike.Try(52100.0, "A", 6_500.0), CheaperStrike.Try(52200.0, null, null), CheaperStrike.Try(52300.0, "C", 3_000.0))
        assertEquals(CheaperStrike.Outcome.Picked(0, 1), CheaperStrike.choose(tries, 7_000.0))
        // The first still short: the second not listed (skipped, counted), the third fits - three strikes out.
        assertEquals(CheaperStrike.Outcome.Picked(2, 3), CheaperStrike.choose(tries, 5_000.0))
        // No live quote: skipped and counted too.
        val noQuote = listOf(CheaperStrike.Try(52100.0, "A", null), CheaperStrike.Try(52200.0, "B", 3_000.0))
        assertEquals(CheaperStrike.Outcome.Picked(1, 2), CheaperStrike.choose(noQuote, 5_000.0))
        // Exactly three tried, then a give-up - a fourth is never looked at.
        val four = (1..4).map { CheaperStrike.Try(52000.0 + 100 * it, "S$it", if (it == 4) 100.0 else 9_000.0) }
        val gave = CheaperStrike.choose(four, 5_000.0)
        assertTrue(gave is CheaperStrike.Outcome.GaveUp)
        assertEquals(3, (gave as CheaperStrike.Outcome.GaveUp).steps)
        assertTrue(gave.why, gave.why.contains("none of the 3 strikes further out fits one lot"))
        // The free cash unknown: never a buy on a guess.
        assertEquals(CheaperStrike.Outcome.GaveUp("the free cash is not known", 0), CheaperStrike.choose(tries, null))
        assertEquals(CheaperStrike.Outcome.GaveUp("the free cash is not known", 0), CheaperStrike.choose(tries, Double.NaN))
        assertTrue(CheaperStrike.choose(emptyList(), 5_000.0) is CheaperStrike.Outcome.GaveUp)
    }

    /** Only Zerodha's definite refusal for funds steps - never an unsure DataException / GeneralException, a lost reply or the app's own door. */
    @Test fun onlyADefiniteZerodhaFundsRefusalCounts() {
        assertTrue(CheaperStrike.definiteRefusal(Broker.KiteError("InputException", "Insufficient funds. Required margin is 6000.00")))
        assertTrue(CheaperStrike.definiteRefusal(Broker.KiteError("MarginException", "RMS:Margin Exceeds, Required:6,000.00")))
        assertFalse(CheaperStrike.definiteRefusal(Broker.KiteError("GeneralException", "Insufficient funds.")))
        assertFalse(CheaperStrike.definiteRefusal(Broker.KiteError("DataException", "Insufficient funds.")))
        assertFalse(CheaperStrike.definiteRefusal(Broker.KiteError("NetworkException", "Insufficient funds.")))
        assertFalse(CheaperStrike.definiteRefusal(Broker.KiteError("entry_gate", "refused: insufficient funds")))
        assertFalse(CheaperStrike.definiteRefusal(Broker.KiteError("InputException", "Quantity exceeds the freeze limit.")))
        assertFalse(CheaperStrike.definiteRefusal(java.io.IOException("Insufficient funds")))
    }

    /**
     * The update's "switched on for Boss" timeline entry: decided once per phone (the mark), logged only on a phone that ran an earlier build,
     * never re-logged after the timeline prunes it, never on a fresh install, never once Boss set the switch.
     */
    @Test fun theOneTimeTimelineEntryIsDecidedOncePerPhone() {
        assertTrue("a phone that ran an earlier build: logged", CheaperStrike.onceDue(never = true, ranBefore = true))
        SecurePrefs.flush()
        assertFalse("never again, even after the timeline pruned it", CheaperStrike.onceDue(never = true, ranBefore = true))
        SecurePrefs.put(CheaperStrike.K_ONCE, null)
        assertFalse("a fresh install: not logged", CheaperStrike.onceDue(never = true, ranBefore = false))
        assertFalse("and decided for good", CheaperStrike.onceDue(never = true, ranBefore = true))
        SecurePrefs.put(CheaperStrike.K_ONCE, null)
        assertFalse("Boss set it already", CheaperStrike.onceDue(never = false, ranBefore = true))
        // Never carried by a backup (a restore cannot switch it on nor bring the mark back).
        assertFalse(com.optionslab.ira.Upkeep.carried(CheaperStrike.K_ON)); assertFalse(com.optionslab.ira.Upkeep.carried(CheaperStrike.K_ONCE))
    }

    @Test fun itCoversR51R72AndEveryLiquidityBookBySourceOnly() {
        assertTrue(CheaperStrike.covers(com.optionslab.engine.orb.DonchianArm.SOURCE))
        assertTrue(CheaperStrike.covers(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE))
        assertTrue(CheaperStrike.covers(com.optionslab.engine.orb.LiquidityRules.ARM.source))
        for (b in com.optionslab.engine.orb.LiquidityRules.BOOKS) assertTrue(b.source, CheaperStrike.covers(b.source))
        // Every other arm, and the display names, are not.
        assertFalse(CheaperStrike.covers(com.optionslab.engine.orb.OovArm.SOURCE))
        assertFalse(CheaperStrike.covers(com.optionslab.engine.orb.HeroTicketRules.SOURCE))
        assertFalse(CheaperStrike.covers(com.optionslab.engine.orb.BreadthDonchianArm.LABEL))
        assertFalse(CheaperStrike.covers("Liquidity 15+5"))
    }

    @Test fun onlyZerodhasFundsWordsCount() {
        assertTrue(CheaperStrike.fundsRefused("Insufficient funds. Required margin is 6000.00 but available margin is 120.00."))
        assertTrue(CheaperStrike.fundsRefused("RMS:Margin Exceeds, Required:1,20,000.00, Available:40,000.00"))
        assertFalse(CheaperStrike.fundsRefused("RMS:Trigger price is outside the circuit limit"))
        assertFalse(CheaperStrike.fundsRefused("Read timed out"))
        assertFalse(CheaperStrike.fundsRefused(null))
    }

    // ---- the entry's look (Zerodha's list and quotes) -----------------------------------------------------------------------------

    private fun list(und: String = "BANKNIFTY", right: String = "PE", strikes: List<Double> = listOf(51700.0, 51800.0, 51900.0, 52000.0, 52100.0),
                     ex: String = "NFO", lot: Int = 30) {
        for ((i, k) in strikes.withIndex()) {
            val s = "$und${expiry.dayOfMonth}X${k.toInt()}$right"
            kite.instruments += FakeKite.Ins(30_000L + i + (if (right == "CE") 100 else 0), s, und, expiry, k, right, lot, exchange = ex)
        }
    }
    private fun sym(k: Double, und: String = "BANKNIFTY", right: String = "PE") = "$und${expiry.dayOfMonth}X${k.toInt()}$right"
    private fun req(free: Double?, now: LocalDateTime = this.now, t0: Long = System.nanoTime(), right: Right = Right.PE, ex: String = "NFO",
                    und: String = "BANKNIFTY", from: Double = 52000.0, expiry: java.time.LocalDate = this.expiry) =
        CheaperStrike.Req("R72 Breadth Donchian", com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, und, expiry, right, from,
            sym(from, und, right.name), ex, "MIS", free, CheaperStrike.Trigger.PRE_SEND, t0, now)

    @Test fun itStepsDownForAPutSkippingAMissingStrikeAndOneWithNoQuote() {
        list(strikes = listOf(51600.0, 51700.0, 52000.0, 52100.0))                 // 51900 and 51800 missing
        kite.quote("NFO:${sym(51700.0)}", 100.0, 99.95, 100.05)
        runBlocking { Broker.instruments() }
        // 51900 not present, 51800 not present, 51700 fits (Rs 5,000 free, one lot of 30 at about 100.6).
        val p = runBlocking { CheaperStrike.find(req(5_000.0)) }
        assertNotNull(p); p!!
        assertEquals(51700.0, p.strike, 0.0); assertEquals(3, p.steps); assertEquals(sym(51700.0), p.symbol)
        assertEquals("NFO", p.exchange); assertEquals(30, p.lot)
        assertEquals("BANKNIFTY 52000 PE", p.fromWords); assertEquals("51700 PE", p.toWords)
        assertEquals("R72 Breadth Donchian: funds short for BANKNIFTY 52000 PE, bought 51700 PE (1 lot)", CheaperStrike.boughtWords(p, 1))
        assertTrue("nothing recorded until it is bought", CheaperStrike.recsOf().isEmpty())
    }

    @Test fun aCallStepsUpAndAStrikeWithNoLiveQuoteCountsAsAStep() {
        list(right = "CE", strikes = listOf(51900.0, 52000.0, 52100.0, 52200.0, 52300.0))
        kite.quote("NFO:${sym(52200.0, right = "CE")}", 120.0, 119.9, 120.1)            // 52100 has no quote at all
        runBlocking { Broker.instruments() }
        val p = runBlocking { CheaperStrike.find(req(5_000.0, right = Right.CE)) }!!
        assertEquals(52200.0, p.strike, 0.0); assertEquals(2, p.steps)
    }

    @Test fun afterThreeStrikesItGivesUpAndLogsIt() {
        list()
        for (k in listOf(51700.0, 51800.0, 51900.0)) kite.quote("NFO:${sym(k)}", 150.0, 149.9, 150.1)
        runBlocking { Broker.instruments() }
        assertNull(runBlocking { CheaperStrike.find(req(1_000.0)) })
        val r = CheaperStrike.recsOf().single()
        assertFalse(r.bought); assertEquals(3, r.steps); assertEquals("BANKNIFTY 52000 PE", r.from)
        assertTrue(r.why, r.why.contains("none of the 3 strikes further out fits one lot"))
        assertTrue(CheaperStrike.line(), CheaperStrike.line().contains("Today: 0 bought at a cheaper strike, 1 gave up."))
    }

    @Test fun theSwitchOffOrAnotherArmNeverSteps() {
        list(); kite.quote("NFO:${sym(51900.0)}", 50.0, 49.9, 50.1); runBlocking { Broker.instruments() }
        CheaperStrike.set(false, log = false)
        assertNull(runBlocking { CheaperStrike.find(req(5_000.0)) })
        assertTrue("off: not even a give-up", CheaperStrike.recsOf().isEmpty())
        CheaperStrike.set(true, pinConfirmed = true, log = false)
        assertNull(runBlocking { CheaperStrike.find(req(5_000.0).copy(source = com.optionslab.engine.orb.OovArm.SOURCE)) })
        assertNotNull(runBlocking { CheaperStrike.find(req(5_000.0)) })
    }

    @Test fun itNeverChasesNorGoesAfterTheSquareOffTimeOnExpiryDayOrAfterTheClose() {
        list(); kite.quote("NFO:${sym(51900.0)}", 50.0, 49.9, 50.1); runBlocking { Broker.instruments() }
        // More than 60 s after the entry began.
        assertNull(runBlocking { CheaperStrike.find(req(5_000.0, t0 = System.nanoTime() - 61_000_000_000L)) })
        assertTrue(CheaperStrike.recsOf().last().why.contains("more than 60 s after the signal"))
        // Expiry day at 15:06 (the 15:05 expiry square-off).
        val day = now.toLocalDate()
        assertNull(runBlocking { CheaperStrike.find(req(5_000.0, now = day.atTime(15, 6), expiry = day)) })
        assertTrue(CheaperStrike.recsOf().last().why, CheaperStrike.recsOf().last().why.contains("too late"))
        // The market closed for new intraday entries (15:15 square-off and later).
        assertNull(runBlocking { CheaperStrike.find(req(5_000.0, now = day.atTime(15, 20))) })
        assertTrue(CheaperStrike.recsOf().last().why.contains("too late"))
        // The free cash unknown (a stale figure is never kept: [Broker.keptFreeCash]).
        assertNull(runBlocking { CheaperStrike.find(req(null)) })
        assertTrue(CheaperStrike.recsOf().last().why.contains("free cash is not known"))
        assertEquals(4, CheaperStrike.recsOf().count { !it.bought })
    }

    @Test fun aStrikeWithAZeroPriceIsNoLiveQuote() {
        list(); kite.quote("NFO:${sym(51900.0)}", 0.0, null, null); kite.quote("NFO:${sym(51800.0)}", 60.0, 59.9, 60.1)
        runBlocking { Broker.instruments() }
        assertEquals(51800.0, runBlocking { CheaperStrike.find(req(5_000.0)) }!!.strike, 0.0)
    }

    @Test fun sensexStepsOnBfo() {
        list(und = "SENSEX", right = "CE", strikes = listOf(80900.0, 81000.0, 81100.0, 81200.0), ex = "BFO", lot = 20)
        kite.quote("BFO:${sym(81100.0, "SENSEX", "CE")}", 90.0, 89.9, 90.1)
        Broker.resetBfoForTest()
        Broker.loadBfoOptionsInBackground()
        val until = System.currentTimeMillis() + 15_000
        while (Broker.cachedBfoOptions() == null && System.currentTimeMillis() < until) Thread.sleep(20)
        assertNotNull("the SENSEX list is in", Broker.cachedBfoOptions())
        val p = runBlocking { CheaperStrike.find(req(5_000.0, right = Right.CE, ex = "BFO", und = "SENSEX", from = 81000.0)) }!!
        assertEquals("BFO", p.exchange); assertEquals(sym(81100.0, "SENSEX", "CE"), p.symbol); assertEquals(20, p.lot)
        assertEquals(1, p.steps)
    }

    // ---- the switch and the record ------------------------------------------------------------------------------------------------

    @Test fun offIsOneTapOnTakesThePinAndBothAreKeptAcrossARestart() {
        assertTrue(CheaperStrike.isOn())
        assertEquals("Off: such buys are no longer moved to a cheaper strike; they go (or are skipped) as before.", CheaperStrike.set(false, log = false))
        assertFalse(CheaperStrike.isOn())
        assertEquals(CheaperStrike.PIN_ON, CheaperStrike.set(true, log = false))
        assertFalse("never on without the PIN", CheaperStrike.isOn())
        SecurePrefs.flush()
        CheaperStrike.resetForTest(on = true, loaded = false)
        CheaperStrike.load()
        assertFalse("off read back from the settings", CheaperStrike.isOn())
        assertTrue(CheaperStrike.set(true, pinConfirmed = true, log = false).startsWith("On:"))
        assertTrue(CheaperStrike.set(true, pinConfirmed = true, log = false).endsWith("Nothing changed."))
        SecurePrefs.flush()
        CheaperStrike.resetForTest(on = false, loaded = false)
        CheaperStrike.load()
        assertTrue(CheaperStrike.isOn())
    }

    /** The change timeline's Undo: off at once (one tap), on only with the PIN (as the Undo is given it), never logged again. */
    @Test fun theTimelinesUndoTurnsItOffFreelyAndOnOnlyWithThePin() {
        val off = com.optionslab.app.ira.Autopilot.apply(com.optionslab.ira.AutoChange.cheaperStrike(false), pinConfirmed = false, ownUndo = true)
        assertNotNull(off); assertFalse(CheaperStrike.isOn())
        assertNull("no PIN: nothing changed", com.optionslab.app.ira.Autopilot.apply(com.optionslab.ira.AutoChange.cheaperStrike(true), pinConfirmed = false, ownUndo = true))
        assertFalse(CheaperStrike.isOn())
        assertNotNull(com.optionslab.app.ira.Autopilot.apply(com.optionslab.ira.AutoChange.cheaperStrike(true), pinConfirmed = true, ownUndo = true))
        assertTrue(CheaperStrike.isOn())
    }

    @Test fun aFreshPhoneHasItOnAndAnEraseGoesBackToThat() {
        CheaperStrike.resetForTest(on = false, loaded = false)
        assertTrue("never set on this phone", CheaperStrike.load())
        assertTrue("on by default (Boss's exception)", CheaperStrike.isOn())
        CheaperStrike.set(false, log = false)
        assertTrue(CheaperStrike.holdsForErase())
        CheaperStrike.wipe()
        assertTrue(CheaperStrike.isOn()); assertFalse(CheaperStrike.holdsForErase())
        val before = CheaperStrike.genForTest()
        CheaperStrike.wipe()
        assertFalse("a save from before the erase is skipped", CheaperStrike.persist(before))
    }

    @Test fun aBuyIsSaidAndCountedWithNoRupees() {
        list(); kite.quote("NFO:${sym(51900.0)}", 50.0, 49.9, 50.1); runBlocking { Broker.instruments() }
        val p = runBlocking { CheaperStrike.find(req(5_000.0)) }!!
        CheaperStrike.bought(p, 1)
        val r = CheaperStrike.recsOf().single()
        assertTrue(r.bought); assertEquals("51900 PE", r.to); assertEquals(1, r.steps)
        assertTrue(CheaperStrike.line(), CheaperStrike.line().contains("Today: 1 bought at a cheaper strike, 0 gave up."))
        assertEquals(1, CheaperStrike.view.value.boughtToday)
        val end = System.currentTimeMillis() + 10_000
        while (Diag.lines().none { it.contains("funds short for BANKNIFTY 52000 PE, bought 51900 PE (1 lot)") } && System.currentTimeMillis() < end) Thread.sleep(20)
        val lines = Diag.lines().filter { it.contains("[cheaper]") } + CheaperStrike.line()
        assertTrue(lines.toString(), lines.any { it.contains("bought 51900 PE (1 lot)") })
        assertTrue(lines.toString(), lines.none { it.contains("Rs") || it.contains("₹") || it.contains("5,000") })
        // Kept across a restart.
        SecurePrefs.flush()
        val end2 = System.currentTimeMillis() + 5_000
        while (SecurePrefs.getString("cheaperStrike.log").isNullOrEmpty() && System.currentTimeMillis() < end2) { Thread.sleep(50); SecurePrefs.flush() }
        CheaperStrike.resetForTest(on = true, loaded = false)
        CheaperStrike.load()
        assertEquals(1, CheaperStrike.recsOf().count { it.bought })
    }
}
