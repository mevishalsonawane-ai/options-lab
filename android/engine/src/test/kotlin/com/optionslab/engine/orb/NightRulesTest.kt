package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Night (R3), research/hunt/x2/PREREG.md rule R3. The decisions below are research/hunt/x2/run.py's own (load_night: h31's
 * features and h26's BUopen at 15:19, z-scored against the previous 60 sessions), for days of Aug-Sep 2026.
 */
class NightRulesTest {
    private fun csv(s: String) = s.split(',').map { it.toDouble() }

    /** NIFTY 2026-09-25: BUopen 0.372031569 and its previous 60 sessions (h26's opt_NIFTY.npz, column 364). */
    private val niftyPrev = csv("0.5326069593429565,-0.3021593689918518,0.6148056387901306,-0.15467843413352966,-0.5719786882400513," +
        "0.04459493234753609,0.3991581201553345,0.37279823422431946,-0.11814212054014206,-0.4360714554786682,-0.07124960422515869," +
        "0.5454286336898804,0.0,-0.19396886229515076,-0.5942869186401367,-0.06503600627183914,0.5270677208900452,0.5523269772529602," +
        "-0.09824424237012863,0.6462244987487793,0.2884531319141388,0.1421184092760086,0.4243672490119934,-0.4705857038497925," +
        "-0.3897659182548523,0.38280561566352844,-0.11646265536546707,0.15638868510723114,-0.18560479581356049,-0.588814377784729," +
        "0.3457626402378082,0.4130363166332245,0.4845176339149475,-0.06586667150259018,-0.275494784116745,0.49618324637413025," +
        "-0.4160298705101013,-0.4835410416126251,0.33193397521972656,-0.4045106768608093,-0.4602884352207184,0.39511215686798096," +
        "0.47925809025764465,-0.6909184455871582,0.5017071962356567,-0.38436102867126465,0.20924611389636993,-0.6800383925437927," +
        "-0.6197466254234314,-0.5658039450645447,-0.3592704236507416,0.37346142530441284,-0.7279956936836243,-0.4257950186729431," +
        "0.32240763306617737,0.1752476990222931,0.6412891745567322,-0.32257428765296936,0.4467069208621979,-0.5207704305648804")

    /** BANKNIFTY 2026-09-28: BUopen -0.641415238 and its previous 60 sessions. */
    private val bankPrev = csv("-0.16214139759540558,0.22378262877464294,-0.08445090800523758,-0.13686014711856842,0.4722391963005066," +
        "0.18003392219543457,0.0853336751461029,-0.25936242938041687,0.07743697613477707,-0.2116173803806305,0.09775520861148834,0.0," +
        "-0.21833014488220215,-0.4070545434951782,-0.5386462807655334,0.3940753638744354,-0.03300766274333,-0.22920724749565125," +
        "0.08594543486833572,0.19108648598194122,-0.1678379774093628,0.40605682134628296,-0.20022377371788025,-0.09790743142366409," +
        "0.15170295536518097,-0.008069092407822609,-0.23140841722488403,-0.07874102145433426,0.11035630851984024,-0.060037869960069656," +
        "-0.07688195258378983,0.15948368608951569,-0.26307010650634766,0.00034892771509476006,-0.013840806670486927,0.22774910926818848," +
        "-0.49934956431388855,0.07058217376470566,0.23379608988761902,-0.08610182255506516,0.09514395892620087,0.04877621680498123," +
        "-0.08049467206001282,0.17539124190807343,-0.04400550201535225,0.018806729465723038,-0.23282967507839203,-0.16437995433807373," +
        "-0.3212185800075531,-0.2710134983062744,0.10860144346952438,-0.2303871214389801,0.19753114879131317,-0.03456202894449234," +
        "0.13169722259044647,0.0341314934194088,-0.21979321539402008,0.26081040501594543,-0.5359072685241699,0.18221072852611542")

    @Test fun theBuildUpsZScoreIsTheResearchs() {
        assertEquals(0.887484467, NightRules.z(0.372031569, niftyPrev)!!, 1e-6)
        assertEquals(-2.771107987, NightRules.z(-0.641415238, bankPrev)!!, 1e-6)
        // Only the last 60 count; under 20 or no spread: not known.
        assertEquals(NightRules.z(0.3, niftyPrev), NightRules.z(0.3, listOf(9.0, 9.0) + niftyPrev))
        assertNull(NightRules.z(0.3, niftyPrev.take(19)))
        assertNull(NightRules.z(0.3, List(30) { 1.0 }))
        assertNull(NightRules.z(Double.NaN, niftyPrev))
        // The seed the app starts from is the research's last 60 sessions to 5 Oct.
        assertEquals(60, NightRules.SEED.getValue("NIFTY").size); assertEquals(60, NightRules.SEED.getValue("BANKNIFTY").size)
        assertEquals(-0.119814, NightRules.SEED.getValue("BANKNIFTY").last(), 1e-9)
        assertEquals(LocalDate.of(2026, 10, 5), NightRules.SEED_UNTIL)
    }

    /** (index, day, loc, DAY, BR, buz) -> r3, from run.py's load_night. */
    @Test fun theResearchsDecisions() {
        data class Row(val what: String, val loc: Double, val day: Double, val br: Double, val buz: Double, val r3: Int)
        val rows = listOf(
            Row("NIFTY 2026-09-25", 0.755908, 0.002818, 0.570093, 0.887484, 1),
            Row("NIFTY 2026-09-24", 0.167303, -0.015405, 0.098131, -1.235059, -1),
            Row("NIFTY 2026-08-03", 0.616852, 0.008481, 0.738318, 0.984302, 1),
            Row("BANKNIFTY 2026-09-28", 0.031499, -0.020020, 0.098131, -2.771108, -1),
            Row("BANKNIFTY 2026-09-16", 0.744924, 0.007743, 0.565421, 1.052190, 1),
            // A strong close the OI does not back: nothing.
            Row("BANKNIFTY 2026-08-07", 0.372199, -0.004521, 0.439252, -0.023791, 0),
            Row("NIFTY 2026-09-16", 0.640676, 0.004566, 0.565421, -0.970913, 0),
            Row("NIFTY 2026-08-13", 0.349002, -0.003380, 0.401869, 0.791766, 0),
        )
        for (r in rows) assertEquals(r.r3, NightRules.decide(r.loc, r.day, r.br, r.buz).side, r.what)
        // The day's move and location from the 15:19 close: NIFTY 2026-09-25, 23,128.10 against 23,063.10.
        assertEquals(0.002818, NightRules.dayMove(23_128.10, 23_063.10), 1e-6)
        assertEquals(0.75, NightRules.loc(107.5, 110.0, 100.0), 1e-12)
        assertEquals(0.5, NightRules.loc(100.0, 100.0, 100.0))
    }

    @Test fun theWordsOfEachDecision() {
        assertEquals(NightRules.Decision(0, "night_no_breadth"), NightRules.decide(0.9, 0.01, null, 2.0))
        assertEquals(NightRules.Decision(0, "night_no_strong_close"), NightRules.decide(0.5, 0.01, 0.7, 2.0))
        assertEquals(NightRules.Decision(0, "night_no_oi_history"), NightRules.decide(0.9, 0.01, 0.7, null))
        assertEquals(NightRules.Decision(0, "night_oi_disagrees"), NightRules.decide(0.9, 0.01, 0.7, 0.2))
        assertEquals(NightRules.Decision(1, "night_buy_ce"), NightRules.decide(0.9, 0.01, 0.7, 0.5))
        assertEquals(NightRules.Decision(-1, "night_buy_pe"), NightRules.decide(0.1, -0.01, 0.3, -0.5))
        // All three must agree.
        assertEquals(0, NightRules.strongClose(0.9, -0.001, 0.7)); assertEquals(0, NightRules.strongClose(0.9, 0.01, 0.4))
        assertEquals(0, NightRules.strongClose(0.1, 0.001, 0.3)); assertEquals(0, NightRules.strongClose(0.1, -0.01, 0.6))
        assertEquals(0, NightRules.strongClose(0.9, 0.01, Double.NaN))
        assertEquals(0, NightRules.side(1, Double.NaN)); assertEquals(0, NightRules.side(0, 3.0))
    }

    @Test fun theBuildUpIsH26sBuOpen() {
        fun leg(c0: Double?, cT: Double?, o0: Double?, oT: Double?) = NightRules.Leg(c0, cT, o0, oT)
        val s1 = NightRules.Strike(leg(100.0, 110.0, 1_000.0, 1_500.0), leg(80.0, 70.0, 2_000.0, 2_600.0))   // +500 + 600
        val s2 = NightRules.Strike(leg(50.0, 40.0, 500.0, 800.0), leg(120.0, 130.0, 900.0, 700.0))          // -300 - 200
        assertEquals(600.0 / 5_600.0, NightRules.buildUp(listOf(s1, s2))!!, 1e-12)
        // A strike missing a figure is out of the sum; its OI now still counts in the total when both are known.
        val s3 = NightRules.Strike(leg(null, 10.0, 100.0, 400.0), leg(10.0, 11.0, 500.0, 600.0))
        val s4 = NightRules.Strike(leg(10.0, 11.0, 100.0, 400.0), leg(10.0, 11.0, 500.0, null))
        assertEquals(600.0 / 6_600.0, NightRules.buildUp(listOf(s1, s2, s3, s4))!!, 1e-12)
        assertNull(NightRules.buildUp(emptyList()))
        assertNull(NightRules.buildUp(listOf(s4)))
    }

    @Test fun breadthNeedsAHundredStocks() {
        val up = List(60) { 101.0 to 100.0 } + List(40) { 99.0 to 100.0 }
        assertEquals(0.6, NightRules.breadth(up)!!, 1e-12)
        assertNull(NightRules.breadth(up.drop(1)))
        assertNull(NightRules.breadth(up.drop(1) + (0.0 to 100.0)), "an unpriced stock does not count")
        assertNull(NightRules.breadth(up.drop(1) + (Double.NaN to 100.0)))
        assertEquals(214, NightRules.BREADTH_UNIVERSE.size)
        assertTrue("M&M" in NightRules.BREADTH_UNIVERSE && "GVT&D" in NightRules.BREADTH_UNIVERSE)
    }

    @Test fun theOneInTheMoneyStrikeAndTheContract() {
        // NIFTY 23,128.10 -> ATM 23,150: the call 23,100, the put 23,200. BANKNIFTY 54,467.70 -> ATM 54,500: the put 54,600.
        assertEquals(23_100, NightRules.strike(23_128.10, "NIFTY", 1)); assertEquals(23_200, NightRules.strike(23_128.10, "NIFTY", -1))
        assertEquals(54_600, NightRules.strike(54_467.70, "BANKNIFTY", -1)); assertEquals(100, NightRules.step("BANKNIFTY"))
        val thu = LocalDate.of(2026, 9, 24); val fri = thu.plusDays(1); val tue = LocalDate.of(2026, 9, 29)
        // Not on an expiry day; never into the contract's expiry; else the nearest.
        assertNull(NightRules.expiry(tue, tue.plusDays(1), listOf(tue, tue.plusDays(7))))
        assertNull(NightRules.expiry(LocalDate.of(2026, 9, 28), tue, listOf(tue, tue.plusDays(7))))
        assertEquals(tue, NightRules.expiry(thu, fri, listOf(tue, tue.plusDays(7))))
        assertNull(NightRules.expiry(thu, fri, emptyList()))
    }

    @Test fun whenItBuysAndSells() {
        assertFalse(NightRules.entryWindow(LocalTime.of(15, 19, 59)))
        assertTrue(NightRules.entryWindow(LocalTime.of(15, 20, 3))); assertTrue(NightRules.entryWindow(LocalTime.of(15, 22, 59)))
        assertFalse(NightRules.entryWindow(LocalTime.of(15, 23)))
        val d = LocalDate.of(2026, 10, 8)
        assertFalse(NightRules.exitDue(d, d.atTime(15, 30)))
        assertFalse(NightRules.exitDue(d, LocalDateTime.of(2026, 10, 9, 9, 15, 59)))
        assertTrue(NightRules.exitDue(d, LocalDateTime.of(2026, 10, 9, 9, 16)))
        assertTrue(NightRules.exitDue(d, LocalDateTime.of(2026, 10, 12, 11, 0)))
        assertTrue(NightRules.RECORD.contains("207") && NightRules.RECORD.contains("150") && NightRules.RECORD.contains("not proven"))
    }
}
