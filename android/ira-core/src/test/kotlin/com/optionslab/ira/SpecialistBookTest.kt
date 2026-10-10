package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The specialists' learning loop ([SpecialistBook]): outcomes, report cards, slow bounded tuning, mutes, the nightly line. */
class SpecialistBookTest {
    private val t0 = 1_760_000_000_000L
    private val B = SpecialistBook

    private fun trade(id: String, family: String = "liquidity", account: TradeManager.Account = TradeManager.Account.PAPER) =
        TradeManager.ManagedTrade("$family:x", id, "Liquidity 15+5", "NIFTY", 1, "NIFTY25OCT25000CE", 100.0, 75, 70.0, 160.0,
            TradeManager.Caps(t0 + 3_600_000L), account, t0, 1.0)

    /** A settled trade: an early exit led by [lead] with [voters], worth [vs] rupees against the original rules. */
    private fun settled(id: String, lead: String, voters: List<String>, vs: Double, family: String = "liquidity", kind: String = TradeManager.EXIT_EARLY): TradeManager.Record {
        val note = TradeManager.Note(t0, kind, "team", "out", 110.0, true, lead = lead, voters = voters)
        return TradeManager.Record(trade(id, family), TradeManager.Mode.ACT, listOf(note),
            actual = TradeManager.Exit(t0, 110.0, "trade manager"), manager = TradeManager.Exit(t0, 110.0, "trade manager"),
            original = TradeManager.Exit(t0 + 1, 110.0 - vs / 75, "STOP"), actedAtMs = t0)
    }

    @Test fun eachVoterIsCreditedOrDebitedWithTheTrade() {
        val rs = listOf(settled("a", "vwap", listOf("vwap", "volume"), 750.0), settled("b", "oi", listOf("oi", "vwap"), -300.0))
        val o = B.outcomes(rs)
        assertEquals(4, o.size)
        assertEquals(750.0, o.first { it.tradeId == "a" && it.specialist == "volume" }.rs, 1e-6)
        assertTrue(o.first { it.tradeId == "b" && it.specialist == "oi" }.led)
        val cards = B.cards(rs + TradeManager.Record(trade("open"), TradeManager.Mode.ACT,
            listOf(TradeManager.Note(t0, TradeManager.EXIT_EARLY, "team", "out", 1.0, true, lead = "vwap", voters = listOf("vwap")))))
        val vwap = cards.first { it.specialist == "vwap" }
        assertEquals(3, vwap.votes); assertEquals(2, vwap.led); assertEquals(2, vwap.settled)
        assertEquals(750.0, vwap.helped, 1e-6); assertEquals(300.0, vwap.hurt, 1e-6); assertEquals(0.5, vwap.hitRate!!, 1e-9)
        assertTrue(B.cardLine(vwap).startsWith("VWAP: 3 votes, led 2, helped +₹750, hurt −₹300, hit rate 50% (2 settled)"), B.cardLine(vwap))
        // Committee order.
        assertEquals(listOf("vwap", "volume", "oi"), cards.map { it.specialist })
    }

    private fun many(n: Int, id: String, vs: Double, family: String = "liquidity") =
        (1..n).map { settled("$id$it$family", id, listOf(id), vs, family) }

    @Test fun tuningWaitsForThirtyMovesTenPercentADayWithinItsBoundsAndMutesTheHarmful() {
        val base = ManagerConfig("liquidity")
        // 29 settled: nothing moves.
        val few = B.cards(many(29, "vwap", 100.0))
        assertEquals(SpecialistBook.Tuning(lastDay = "d1"), B.tune("liquidity", base, SpecialistBook.Tuning(), few, "d1").tuning)
        // 30 helped: +10% a day, never past 2x the default.
        val good = B.cards(many(30, "vwap", 100.0))
        var tu = SpecialistBook.Tuning()
        val seen = ArrayList<Double>()
        for (d in 1..20) { tu = B.tune("liquidity", base, tu, good, "d$d").tuning; seen += tu.weights.getValue("vwap") }
        assertEquals(0.275, seen[0], 1e-9)
        for (i in 1 until seen.size) assertTrue(seen[i] <= seen[i - 1] * 1.1 + 1e-9)
        assertEquals(0.5, seen.last(), 1e-9, "2x the default at most")
        // The same day twice: nothing more.
        assertEquals(tu, B.tune("liquidity", base, tu, good, "d20").tuning)
        // 30 hurt: down 10% a day to a quarter of the default, and muted with the reason.
        val bad = B.cards(many(34, "oi", -50.0))
        val t1 = B.tune("liquidity", base, SpecialistBook.Tuning(), bad, "d1")
        assertEquals(0.225, t1.tuning.weights.getValue("oi"), 1e-9)
        assertEquals("oi", t1.newlyMuted.single().first)
        assertTrue("hurt −₹1,700 against +₹0 helped over 34 decisions" in t1.tuning.muted.getValue("oi"), t1.tuning.muted.getValue("oi"))
        var t2 = t1.tuning
        for (d in 2..30) t2 = B.tune("liquidity", base, t2, bad, "d$d").tuning
        assertEquals(0.0625, t2.weights.getValue("oi"), 1e-9, "a quarter of the default at least")
        // The applied config: the weights, the mute (OI does not count), the vetoes untouched.
        val applied = B.apply(base, t2)
        assertFalse(applied.counts("oi"))
        assertEquals(1.0, applied.weight("news"))
        // Un-muted by hand: not muted again until 30 more decisions say so.
        val back = B.unmute(t2, "oi", 34)
        assertTrue("oi" !in back.muted)
        assertTrue(B.tune("liquidity", base, back, bad, "d40").newlyMuted.isEmpty())
        assertEquals("oi", B.tune("liquidity", base, back, B.cards(many(64, "oi", -50.0)), "d41").newlyMuted.single().first)
        // Another strategy's record is its own.
        assertTrue(B.tune("pine", ManagerConfig("pine"), SpecialistBook.Tuning(), bad, "d1").tuning.weights.isEmpty())
    }

    @Test fun theSuggestionForLiveIsWhatTuningChangedAgainstTheFrozenWeights() {
        val base = ManagerConfig("pine")
        val tuned = SpecialistBook.Tuning(weights = mapOf("vwap" to 0.3), muted = mapOf("oi" to "hurt"))
        assertEquals(listOf("VWAP weight 0.25 → 0.30", "OI muted"), B.suggestion(tuned, null, base))
        assertTrue(B.suggestion(tuned, tuned, base).isEmpty())
        assertEquals(base, B.apply(base, null))
    }

    @Test fun theNightLine() {
        val rs = many(20, "vwap", 40.0) + many(14, "volume", 31.43) + many(34, "oi", -9.12)
        val cards = B.cards(rs)
        val line = B.nightLine("liquidity", cards, SpecialistBook.Tuning(muted = mapOf("oi" to "hurt")), 34)
        assertTrue(line.startsWith("Liquidity 15+5: VWAP and Volume specialists helped +₹1,240 over 34 trades; OI hurt −₹310, muted"), line)
        assertEquals("Pine: no settled decision for its specialists yet.", B.nightLine("pine", cards, null, 0))
    }

    @Test fun jarvisSaysWhichSpecialistMadeItExitAndHowTheyAreDoing() {
        val r = settled("s1", "flow", listOf("flow", "vwap"), 450.0, family = "solo")
        val which = B.answerWhich("solo", listOf(r)) { "12:41" }
        assertTrue("exited at 12:41: the Order flow specialist led it" in which && "Also voting to exit: VWAP" in which && "+₹450" in which, which)
        assertTrue("has not exited early" in B.answerWhich("pine", listOf(r)) { "x" })
        val how = B.answerHow(null, listOf(r), emptyMap())
        assertTrue("Solo: Order flow and VWAP specialists helped +₹900 over 1 trade" in how, how)
        assertTrue("frozen weights" in how)
        assertTrue("not voted" in B.answerHow("pine", listOf(r), emptyMap()))
        assertNull(B.cards(emptyList()).firstOrNull())
    }
}
