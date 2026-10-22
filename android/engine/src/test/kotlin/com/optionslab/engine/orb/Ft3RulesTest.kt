package com.optionslab.engine.orb

import com.optionslab.engine.options.OptionMath
import com.optionslab.engine.options.OptionType
import com.optionslab.engine.orb.Ft3Rules.Chain
import com.optionslab.engine.orb.Ft3Rules.Leg
import com.optionslab.engine.orb.Ft3Rules.Phase
import com.optionslab.engine.orb.Ft3Rules.Pick
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "FT3 near-ATM morning" (21 Oct, Boss's choice; a forward test that did not pass research R98e): its pure rules on synthetic
 * chains priced by Black-76 (so every IV ratio is known exactly), the marks and their order windows, the strike step and the
 * ATM, sizing in whole lots, 15:05 - and five real expiry days from the research lake (one per index), where the app's rules must
 * pick the same mark, strike and side as the research replay (research/hunt/r98/ft3_app.py).
 */
class Ft3RulesTest {
    private fun t(h: Int, m: Int): LocalTime = LocalTime.of(h, m)
    private val mark = t(11, 0)
    private val years = Ft3Rules.yearsToClose(mark)

    /** A Black-76 price at [sigma] (forward = spot, r = 0) to the cent, as the feed shows it. */
    private fun px(call: Boolean, spot: Double, strike: Double, sigma: Double): Double =
        Math.round(OptionMath.price(if (call) OptionType.CE else OptionType.PE, spot, strike, years, 0.0, sigma) * 100) / 100.0

    @Test fun itIsPaperOnlyItsOwnArmAndLabelledBossChoice() {
        val a = Ft3Rules.ARM
        assertTrue(a.paperOnly)
        assertFalse(a.hero || a.sweep || a.fade || a.liquidity || a.freshOnly)
        assertTrue(a !in OrbRules.ARMS && a != HeroTicketRules.ARM && a.source != HeroTicketRules.SOURCE)
        assertEquals("ft3", Ft3Rules.SOURCE); assertEquals(Ft3Rules.SOURCE, a.source)
        assertEquals("FT3 near-ATM morning", Ft3Rules.LABEL); assertEquals(Ft3Rules.LABEL, a.label)
        assertTrue(Ft3Rules.BOSS_CHOICE.startsWith("Boss's choice") && "did not pass research" in Ft3Rules.BOSS_CHOICE)
        for (w in listOf("8,280 variants", "SPA p 0.96", "forward test")) assertTrue(w in Ft3Rules.RECORD, w)
        for (w in listOf("478 tickets", "16.3%", "+Rs 1,805", "+Rs 293 without the 5 best days", "4 of 6 years", "+Rs 1,579 (+Rs 62)"))
            assertTrue(w in Ft3Rules.MEASURED, w)
        assertTrue("no chart pattern" in Ft3Rules.SIGNAL_SOURCE && "option chain" in Ft3Rules.SIGNAL_SOURCE)
        assertTrue("labelled: an option-price signal" in Ft3Rules.SIGNAL_SOURCE, "CLAUDE.md: an option-price signal is labelled")
        for (w in listOf("Rs 6-10", "1.0333", "1.5466", "Rs 5,000", "15:05", "no stop")) assertTrue(w in Ft3Rules.RULES, w)
        for (w in listOf("12 months", "100 tickets", "Holm")) assertTrue(w in Ft3Rules.JUDGED, w)
        assertTrue("own record" in Ft3Rules.SEPARATE)
        assertEquals(listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"), Ft3Rules.UNDERLYINGS)
        // The frozen numbers (FORWARD_TEST.md, 21 Oct amendment): never changed without a new test.
        assertEquals(6.0, Ft3Rules.PX_LO); assertEquals(10.0, Ft3Rules.PX_HI)
        assertEquals(1.0333, Ft3Rules.IV_REL_MAX); assertEquals(1.5466, Ft3Rules.DIST_EM_MAX)
        assertEquals(5_000.0, Ft3Rules.BUDGET); assertEquals(t(15, 5), Ft3Rules.EXIT_AT)
        assertEquals("ft3_1505", Ft3Rules.EXIT_WHY)
    }

    @Test fun expiryDaysComeFromTheListedExpiriesNeverTheWeekday() {
        val tue = LocalDate.of(2026, 10, 27)
        assertTrue(Ft3Rules.tradesToday(tue, true, listOf(tue, tue.plusDays(7))))
        assertFalse(Ft3Rules.tradesToday(tue, false, listOf(tue)), "a holiday is never a ticket day")
        assertFalse(Ft3Rules.tradesToday(tue, true, listOf(tue.plusDays(1))), "a moved expiry")
        assertFalse(Ft3Rules.isExpiryDay(tue, emptyList()), "no list: never")
        assertTrue(Ft3Rules.isExpiryDay(tue, listOf(tue.minusDays(7), tue)), "a past expiry in the list is ignored")
    }

    @Test fun twentyFourMarksEachJudgedOnTheBarThatStartsThere() {
        assertEquals(24, Ft3Rules.MARKS.size)
        assertEquals(t(11, 0), Ft3Rules.MARKS.first()); assertEquals(t(12, 55), Ft3Rules.MARKS.last())
        assertEquals(t(11, 5), Ft3Rules.MARKS[1])
        assertEquals(t(11, 1), Ft3Rules.barClose(t(11, 0)))
        assertEquals(emptyList(), Ft3Rules.marksClosedBy(t(11, 0)), "the 11:00 bar is still forming")
        assertEquals(listOf(t(11, 0)), Ft3Rules.marksClosedBy(t(11, 1)))
        assertEquals(listOf(t(11, 0), t(11, 5)), Ft3Rules.marksClosedBy(t(11, 9)))
        assertEquals(24, Ft3Rules.marksClosedBy(t(15, 0)).size)
        // The order is worked for 3 minutes after the bar closes: 11:01, 11:02, 11:03 - never 11:04.
        assertFalse(Ft3Rules.entryOpen(t(11, 0), t(11, 0)))
        assertTrue(Ft3Rules.entryOpen(t(11, 0), t(11, 1)))
        assertTrue(Ft3Rules.entryOpen(t(11, 0), LocalTime.of(11, 3, 59)))
        assertFalse(Ft3Rules.entryOpen(t(11, 0), t(11, 4)))
        assertEquals(Phase.BEFORE, Ft3Rules.phase(t(9, 15)))
        assertEquals(Phase.BEFORE, Ft3Rules.phase(LocalTime.of(11, 0, 59)))
        assertEquals(Phase.SCAN, Ft3Rules.phase(t(11, 1)))
        assertEquals(Phase.SCAN, Ft3Rules.phase(LocalTime.of(12, 58, 59)))
        assertEquals(Phase.LATE, Ft3Rules.phase(t(12, 59)))
        assertEquals(Phase.LATE, Ft3Rules.phase(t(15, 30)))
    }

    @Test fun theIvClockRunsFromTheMarkTo1530() {
        assertEquals(270 / (365.0 * 24 * 60), Ft3Rules.yearsToClose(t(11, 0)), 1e-15)
        assertEquals(155 / (365.0 * 24 * 60), Ft3Rules.yearsToClose(t(12, 55)), 1e-15)
        assertEquals(1 / (365.0 * 24 * 60), Ft3Rules.yearsToClose(t(15, 30)), 1e-15, "at least a minute")
        assertEquals(1 / (365.0 * 24 * 60), Ft3Rules.yearsToClose(t(15, 45)), 1e-15)
    }

    @Test fun theAtmIsSpotRoundedToTheIndexStepTiesToEven() {
        assertEquals(50.0, Ft3Rules.strikeStep("NIFTY")); assertEquals(50.0, Ft3Rules.strikeStep("FINNIFTY"))
        assertEquals(100.0, Ft3Rules.strikeStep("BANKNIFTY")); assertEquals(100.0, Ft3Rules.strikeStep("SENSEX"))
        assertEquals(25.0, Ft3Rules.strikeStep("MIDCPNIFTY"))
        assertEquals(22500.0, Ft3Rules.atmStrike(22524.9, "NIFTY")); assertEquals(22550.0, Ft3Rules.atmStrike(22525.1, "NIFTY"))
        assertEquals(22500.0, Ft3Rules.atmStrike(22525.0, "NIFTY"), "a tie goes to the even step, as Python's round")
        assertEquals(22600.0, Ft3Rules.atmStrike(22575.0, "NIFTY"))
        assertEquals(66800.0, Ft3Rules.atmStrike(66826.16, "SENSEX")); assertEquals(11325.0, Ft3Rules.atmStrike(11330.0, "MIDCPNIFTY"))
    }

    @Test fun theStraddleNeedsBothLegs() {
        assertEquals(160.0, Ft3Rules.straddle(90.0, 70.0)!!, 1e-9)
        assertNull(Ft3Rules.straddle(null, 70.0)); assertNull(Ft3Rules.straddle(90.0, null)); assertNull(Ft3Rules.straddle(0.0, 70.0))
    }

    @Test fun ivIsBlack76WithSpotAsForwardAndNoneWithoutTimeValue() {
        val p = px(true, 22500.0, 22600.0, 0.15)
        assertEquals(0.15, Ft3Rules.iv(p, true, 22500.0, 22600.0, years)!!, 1e-3)
        assertNull(Ft3Rules.iv(0.0, true, 22500.0, 22600.0, years))
        assertNull(Ft3Rules.iv(5.0, true, 0.0, 22600.0, years))
        assertNull(Ft3Rules.iv(5.0, true, 22500.0, 22600.0, 0.0))
        assertNull(Ft3Rules.iv(100.0, true, 22600.0, 22500.0, years), "an ITM price at intrinsic has no time value")
        assertNull(Ft3Rules.iv(Double.NaN, false, 22500.0, 22400.0, years))
        // The ATM IV is the mean of the legs that solve; neither: none.
        val c = px(true, 22500.0, 22500.0, 0.12); val pu = px(false, 22500.0, 22500.0, 0.14)
        assertEquals(0.13, Ft3Rules.atmIv(22500.0, 22500.0, c, pu, years)!!, 2e-3)
        assertEquals(0.12, Ft3Rules.atmIv(22500.0, 22500.0, c, null, years)!!, 2e-3)
        assertNull(Ft3Rules.atmIv(22500.0, 22500.0, null, null, years))
        assertNull(Ft3Rules.ivRatio(c, true, 22500.0, 22500.0, null, years))
        assertNull(Ft3Rules.ivRatio(c, true, 22500.0, 22500.0, 0.0, years))
        assertEquals(1.0, Ft3Rules.ivRatio(c, true, 22500.0, 22500.0, 0.12, years)!!, 2e-2)
    }

    // A NIFTY chain at the 11:00 mark, spot 22510, every option priced by Black-76 at 20% vol unless said: the ATM 22500 call
    // 45.89 and put 35.89 (straddle 81.78, so the band is 126.5 points); the 22400 put 7.26 (1.345 straddles); the 22600 call
    // 10.64 (over Rs 10); the 22450 put 17.50 (over Rs 10); the 22650 call 4.03 (under Rs 6).
    private val spot = 22510.0
    private fun leg(k: Double, call: Boolean, sigma: Double = 0.2, traded: Boolean = true) = Leg(k, call, px(call, spot, k, sigma), traded)
    private fun nifty(vararg legs: Leg, atm: Double = 0.2) =
        Chain("NIFTY", spot, px(true, spot, 22500.0, atm), px(false, spot, 22500.0, atm), legs.toList())
    private val base = listOf(leg(22600.0, true), leg(22650.0, true), leg(22450.0, false), leg(22400.0, false))

    @Test fun theChainIsPricedAsTheCommentSays() {
        val ch = nifty(*base.toTypedArray())
        assertEquals(45.89, ch.atmCall!!, 1e-9); assertEquals(35.89, ch.atmPut!!, 1e-9); assertEquals(22500.0, ch.atmStrike)
        assertEquals(81.78, ch.straddle!!, 1e-9)
        assertEquals(listOf(10.64, 4.03, 17.5, 7.26), ch.legs.map { it.close })
    }

    @Test fun thePickIsTheOneOptionInsideEveryFilter() {
        val p = Ft3Rules.pick(mark, nifty(*base.toTypedArray()))!!
        assertEquals(22400.0, p.strike); assertFalse(p.call); assertEquals("PE", p.right)
        assertEquals(7.26, p.price, 1e-9)
        assertEquals(110.0 / 81.78, p.distEm, 1e-12)
        assertEquals(1.0, p.ivRatio, 3e-3, "the same vol as the ATM (to the cent)")
    }

    @Test fun anIvRatioOver1Point0333IsOut() {
        // The 22400 put at 21% (5% over the ATM's 20%): no ticket; at 20.4% (2% over): its ticket.
        val rich = base.dropLast(1) + leg(22400.0, false, 0.21)
        assertNull(Ft3Rules.pick(mark, nifty(*rich.toTypedArray())))
        val mild = base.dropLast(1) + leg(22400.0, false, 0.204)
        assertEquals(1.02, Ft3Rules.pick(mark, nifty(*mild.toTypedArray()))!!.ivRatio, 3e-3)
        // The same option against a dearer ATM (21%): its ratio falls below 1.
        assertTrue(Ft3Rules.pick(mark, nifty(*rich.toTypedArray(), atm = 0.21))!!.ivRatio < 1.0)
    }

    @Test fun eachOtherFilterOnItsOwn() {
        val put = Leg(22400.0, false, 7.26, true)
        fun one(l: Leg) = Ft3Rules.pick(mark, nifty(l))
        assertNotNull(one(put))
        assertNull(one(put.copy(traded = false)), "not traded in the last 3 bars")
        assertNull(one(put.copy(close = null)), "no bar at the mark")
        assertNull(one(put.copy(close = 5.95)), "under Rs 6"); assertNull(one(put.copy(close = 10.05)), "over Rs 10")
        // Rs 6 and Rs 10 are in (the 22450 put at Rs 10 has a lower IV than the ATM's, so its ratio passes).
        assertEquals(6.0, one(put.copy(close = 6.0))!!.price); assertEquals(10.0, one(Leg(22450.0, false, 10.0, true))!!.price)
        assertNull(one(Leg(22450.0, true, 8.0, true)), "a call below spot is not OTM")
        assertNull(one(Leg(22600.0, false, 8.0, true)), "a put above spot is not OTM")
        assertNull(one(Leg(spot, false, 8.0, true)), "at spot is not OTM")
        // 22380: 130 points = 1.59 straddles, past 1.5466 (priced at the ATM's vol it would be in the band's price).
        assertNull(one(Leg(22380.0, false, 6.5, true)), "beyond 1.5466 straddles")
        assertNull(Ft3Rules.pick(mark, Chain("NIFTY", spot, null, 35.89, listOf(put))), "no straddle")
        assertNull(Ft3Rules.pick(mark, Chain("NIFTY", spot, 45.89, 0.0, listOf(put))), "no straddle")
        assertNull(Ft3Rules.pick(mark, Chain("NIFTY", 0.0, 45.89, 35.89, listOf(put))), "no spot")
        assertNull(Ft3Rules.pick(mark, nifty()), "nothing read")
    }

    @Test fun theNearerOneWinsThenTheLowerIvRatio() {
        // The 22600 call at Rs 9.90 (1.10 straddles, under the ATM's vol) is nearer than the 22400 put (1.35): it wins.
        val near = Ft3Rules.pick(mark, nifty(*(base + Leg(22600.0, true, 9.9, true)).toTypedArray()))!!
        assertEquals(22600.0, near.strike); assertTrue(near.call); assertEquals(9.9, near.price)
        // Spot on the ATM: the 22600 call and the 22400 put sit the same distance away; at the same price the one with the
        // lower IV ratio wins.
        val s = 22500.0
        val c = Math.round(OptionMath.price(OptionType.CE, s, s, years, 0.0, 0.2) * 100) / 100.0
        val p = Math.round(OptionMath.price(OptionType.PE, s, s, years, 0.0, 0.2) * 100) / 100.0
        val legs = listOf(Leg(22600.0, true, 8.0, true), Leg(22400.0, false, 8.0, true))
        val pick = Ft3Rules.pick(mark, Chain("NIFTY", s, c, p, legs))!!
        val atm = Ft3Rules.atmIv(s, s, c, p, years)
        val rc = Ft3Rules.ivRatio(8.0, true, s, 22600.0, atm, years)!!
        val rp = Ft3Rules.ivRatio(8.0, false, s, 22400.0, atm, years)!!
        assertTrue(kotlin.math.abs(rc - rp) > 1e-9)
        assertEquals(rc < rp, pick.call)
        assertEquals(minOf(rc, rp), pick.ivRatio, 1e-12)
    }

    @Test fun theBandIsTheStrikesWithin1Point5466StraddlesNearestFirst() {
        assertEquals(listOf(22550.0, 22450.0, 22600.0), Ft3Rules.band(22510.0, 100.0, listOf(22300.0, 22450.0, 22510.0, 22550.0, 22600.0, 22700.0, 22550.0)))
        assertEquals(emptyList(), Ft3Rules.band(22510.0, 10.0, listOf(22600.0)))
    }

    @Test fun theFirstQualifyingMarkIsTheDaysTicket() {
        val a = Pick(22600.0, true, 8.0, 1.0, 1.0); val b = Pick(22400.0, false, 7.0, 1.2, 0.9)
        assertNull(Ft3Rules.firstQualifying(emptyMap()))
        assertNull(Ft3Rules.firstQualifying(mapOf(t(11, 0) to null, t(11, 5) to null)))
        assertEquals(t(11, 5) to a, Ft3Rules.firstQualifying(mapOf(t(11, 10) to b, t(11, 0) to null, t(11, 5) to a)))
    }

    @Test fun rs5000OfWholeLotsNeverRoundedUp() {
        assertEquals(10, Ft3Rules.lots(7.0, 65))          // 455 a lot
        assertEquals(7, Ft3Rules.lots(10.0, 65))          // 650 a lot: 4,550
        assertEquals(4, Ft3Rules.lots(10.0, 120))         // MIDCPNIFTY: 1,200 a lot
        assertEquals(25, Ft3Rules.lots(10.0, 20))         // SENSEX: exactly Rs 5,000
        assertEquals(0, Ft3Rules.lots(10.0, 600), "a lot over Rs 5,000: skipped")
        assertEquals(0, Ft3Rules.lots(8.0, 0)); assertEquals(0, Ft3Rules.lots(0.0, 65))
        assertEquals(1, Ft3Rules.lots(10.0, 400, budget = 4_000.0))
    }

    @Test fun theLimitIsAskPlusATickCappedAndTheExitIs1505() {
        assertEquals(8.05, Ft3Rules.limitPrice(8.0, 8.0), 1e-9)
        assertEquals(9.7, Ft3Rules.limitPrice(12.0, 8.0), 1e-9, "never above the mark's price x 1.2 + 0.10")
        assertFalse(Ft3Rules.exitDue(t(15, 4)))
        assertTrue(Ft3Rules.exitDue(t(15, 5))); assertTrue(Ft3Rules.exitDue(t(15, 20)))
        assertTrue(Ft3Rules.exitDue(t(9, 20), overnight = true), "a ticket from an earlier day goes at once")
    }

    // ---- five real expiry days (research/hunt/r98: the lake's 1-minute closes, ft3_app.py's replay) ---------------------------

    private data class Golden(val und: String, val day: String, val marks: List<Pair<LocalTime, Chain>>, val pick: Pair<LocalTime, Pick>)

    private fun golden(): List<Golden> {
        val text = javaClass.getResource("/ft3/golden.txt")!!.readText()
        val marks = HashMap<String, MutableList<Pair<LocalTime, Chain>>>()
        val picks = LinkedHashMap<String, Pair<LocalTime, Pick>>()
        fun d(s: String) = if (s == "-") null else s.toDouble()
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split("|")
            if (f[0] == "pick") {
                picks["${f[1]}|${f[2]}"] = LocalTime.parse(f[3]) to Pick(f[4].toDouble(), f[5] == "C", f[6].toDouble(), f[7].toDouble(), f[8].toDouble())
                continue
            }
            val legs = f[6].split(";").filter { it.isNotBlank() }.map { l ->
                val (k, s, c, tr) = l.split(":")
                Leg(k.toDouble(), s == "C", d(c), tr == "1")
            }
            marks.getOrPut("${f[0]}|${f[1]}") { ArrayList() } += LocalTime.parse(f[2]) to Chain(f[0], f[3].toDouble(), d(f[4]), d(f[5]), legs)
        }
        return picks.map { (k, p) -> val (u, day) = k.split("|"); Golden(u, day, marks.getValue(k), p) }
    }

    @Test fun onFiveRealExpiryDaysItPicksWhatTheResearchReplayPicked() {
        val g = golden()
        assertEquals(setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"), g.map { it.und }.toSet())
        for (c in g) {
            val results = c.marks.associate { (m, ch) -> m to Ft3Rules.pick(m, ch) }
            val first = Ft3Rules.firstQualifying(results)
            assertNotNull(first, "${c.und} ${c.day}: a ticket")
            val (m, p) = first
            val (rm, rp) = c.pick
            assertEquals(rm, m, "${c.und} ${c.day}: the mark")
            assertEquals(rp.strike, p.strike, "${c.und} ${c.day}: the strike")
            assertEquals(rp.call, p.call, "${c.und} ${c.day}: the side")
            assertEquals(rp.price, p.price, 1e-9, "${c.und} ${c.day}: the price")
            assertEquals(rp.distEm, p.distEm, 1e-5, "${c.und} ${c.day}: the distance")
            assertEquals(rp.ivRatio, p.ivRatio, 1e-4, "${c.und} ${c.day}: the IV ratio")
            // Every earlier mark gave nothing (that is why the research ticket came at its mark).
            assertTrue(c.marks.filter { it.first < rm }.all { results[it.first] == null }, "${c.und} ${c.day}: earlier marks")
        }
    }
}
