package com.optionslab.engine.signals

import com.optionslab.engine.Right
import com.optionslab.engine.signals.CallParser.Follow
import com.optionslab.engine.signals.CallParser.Reading
import com.optionslab.engine.signals.CallParser.Trig
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Signal tester's rule reader ([CallParser], stage a): the 15 Oct SENSEX sample field by field, the whole message fixture
 * ([CallFixture]: dozens of channel styles, follow-ups, junk), and the edges - expiry dates, Hinglish "pe", percent stops,
 * stocks the phone knows, ranges, empty and emoji-only text.
 */
class CallParserTest {
    private val on = LocalDate.of(2026, 10, 15)
    private fun one(text: String, known: Set<String> = emptySet()) = CallParser.read(text, on, known).single()
    private fun call(text: String, known: Set<String> = emptySet()) = assertIs<Reading.NewCall>(one(text, known))

    @Test fun theFifteenOctoberSampleIsReadFieldByField() {
        val r = call("#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++")
        val d = r.draft
        assertEquals("SENSEX", d.underlying); assertEquals(73100.0, d.strike); assertEquals(Right.CE, d.right)
        assertEquals(Trig.ABOVE, d.trig); assertEquals(280.0, d.trigger); assertNull(d.triggerHigh)
        assertNull(d.stop); assertNull(d.stopLevel); assertEquals(listOf(310.0, 345.0, 400.0), d.targets); assertNull(d.expiry)
        assertTrue(r.sure); assertEquals(1.0, r.confidence)
        assertEquals("SENSEX 73100 CE above 280, stop none given, targets 310 / 345 / 400", d.line())
    }

    @Test fun theFixtureReadsAsExpectedByTheRules() {
        val bad = ArrayList<String>()
        for (c in CallFixture.CASES) {
            val got = CallParser.read(c.text, on).joinToString(" || ") { CallFixture.describe(it) }
            if (got != c.rules) bad += "${c.text.replace("\n", "\\n")}\n   want: ${c.rules}\n   got:  $got"
        }
        assertEquals("", bad.joinToString("\n"))
        assertTrue(CallFixture.CASES.size >= 50)
    }

    @Test fun aStockThePhoneKnowsIsSure() {
        assertFalse(call("RELIANCE 3000 CE above 45 sl 38 target 55").sure)
        assertTrue(call("RELIANCE 3000 CE above 45 sl 38 target 55", setOf("RELIANCE")).sure)
    }

    @Test fun expiriesAreReadInEveryUsualWay() {
        assertEquals(LocalDate.of(2026, 10, 16), call("NIFTY 16th Oct 25200 CE above 85").draft.expiry)
        assertEquals(LocalDate.of(2026, 10, 16), call("NIFTY oct 16 25200 CE above 85").draft.expiry)
        assertEquals(LocalDate.of(2026, 10, 16), call("NIFTY 25200 CE above 85 exp 16/10").draft.expiry)
        assertEquals(LocalDate.of(2026, 10, 28), call("NIFTY 28-OCT-2026 25200 CE above 85").draft.expiry)
        // Written without a year and over a week before the message: next year's.
        assertEquals(LocalDate.of(2027, 1, 6), call("NIFTY 6 jan 25200 CE above 85").draft.expiry)
        // Not a date: left alone (31 Feb), the number then not understood.
        val r = call("NIFTY 31 feb 25200 CE above 85")
        assertNull(r.draft.expiry)
        assertEquals(null to "abc", CallParser.expiry("abc", on))
    }

    @Test fun percentStopsRangesAndCmp() {
        val p = call("NIFTY 25000 CE @ 120 SL 20% TGT 140").draft
        assertEquals(20.0, p.stopPct); assertEquals(96.0, p.stopLevel)
        assertNull(call("NIFTY 25000 CE cmp sl 20%").draft.stopLevel)
        val z = call("Bank nifty 51500 pe buy range 240-245 sl 210").draft
        assertEquals(Trig.ZONE, z.trig); assertEquals(240.0, z.trigger); assertEquals(245.0, z.triggerHigh)
        // A "range" whose top is far above is not a range: the second number is not understood.
        val far = call("NIFTY 25000 CE near 100-200")
        assertNull(far.draft.triggerHigh); assertFalse(far.sure)
        val c = call("NIFTY 25000 CE buy at cmp")
        assertEquals(Trig.CMP, c.draft.trig); assertNull(c.draft.trigger)
        assertEquals("NIFTY 25000 CE at market, stop none given, targets none given", c.draft.line())
        assertEquals("NIFTY 25000 CE, no entry, stop none given, targets none given", Reading.NewCall(c.draft.copy(trig = Trig.ABOVE), 0.5, listOf("x")).draft.line())
    }

    @Test fun theStopWordsOnlyWithAnEntryForBelow() {
        // "below" before any entry is not a stop.
        val r = call("NIFTY 25000 PE below 80 tgt 60")
        assertNull(r.draft.stop)
        assertEquals(70.0, call("NIFTY 25000 PE above 80 below 70").draft.stop)
        assertEquals(240.0, call("SENSEX 73100 CE above 280 exit below 240").draft.stop)
        assertEquals(240.0, call("SENSEX 73100 CE above 280 sl-240").draft.stop)
    }

    @Test fun strikeAfterTheRightAndHindiPe() {
        val r = call("NIFTY CE 25000 above 120")
        assertEquals(25000.0, r.draft.strike); assertEquals(120.0, r.draft.trigger)
        // "pe" as the Hindi "at": never a 280 put.
        val h = CallParser.read("NIFTY 25000 CE 120 pe le lo", on)
        assertEquals(1, h.size)
        assertEquals(Right.CE, (h.single() as Reading.NewCall).draft.right)
        // "put" as a verb ("put sl at 240") is not a put.
        assertEquals(Right.CE, call("NIFTY 25000 CE above 120 put sl at 100").draft.right)
    }

    @Test fun followUpsOfEveryKind() {
        fun f(t: String) = assertIs<Reading.FollowUp>(one(t))
        assertEquals(Follow.TARGET to 99, f("All targets done 🎯🎯🎯").let { it.kind to it.targetNo })
        assertEquals(3, f("third target achieved").targetNo)
        assertEquals(1, f("first tgt hit").targetNo)
        assertEquals(345.0, f("345 done ✅").level)
        assertNull(f("target achieved").level)
        assertTrue(f("Book half here").partial)
        assertEquals(Follow.BOOK, f("book profits now").kind)
        assertEquals(Follow.EXIT, f("exit karo").kind)
        assertEquals(Follow.EXIT, f("square off all").kind)
        assertEquals(Follow.TRAIL, f("cost to cost").kind)
        assertEquals(Follow.TRAIL, f("sl at cost").kind)
        assertEquals(Follow.STOP, f("stopped out").kind)
        assertEquals(Follow.HOLD, f("hold tight").kind)
        // A follow-up naming its option.
        val n = f("SENSEX 73100 CE T1 done ✅")
        assertEquals(1, n.targetNo); assertEquals("SENSEX", n.ref?.underlying); assertEquals(73100.0, n.ref?.strike)
        // Long chatter mentioning "hold" is not a follow-up.
        assertIs<Reading.NotACall>(one("we hold our view that the market is strong and will go up in the coming days for sure friends"))
        assertNull(Follow.of("nope")); assertEquals(Follow.BOOK, Follow.of("book"))
    }

    @Test fun whatIsNotACallSaysWhy() {
        fun n(t: String) = assertIs<Reading.NotACall>(one(t))
        assertEquals("no text", n("   ").reason)
        assertTrue(n("Join VIP 👉 link in bio").reason.startsWith("an advert"))
        assertTrue(n("2 new messages").reason.contains("preview"))
        assertTrue(n("NIFTY CE").maybe)
    }

    @Test fun normalisingAndNumbers() {
        assertEquals("sensex 73100 ce above 280", CallParser.normalize("#Sensex 73,100CE ABOVE ₹280"))
        assertEquals("tgtno1 done", CallParser.normalize("T1 ✅"))
        assertEquals(setOf(73100.0, 280.0, 310.0), CallParser.numbers("SENSEX 73100 CE above 280 tgt 310 at 09:28"))
        assertEquals("BANKNIFTY", CallParser.indexIn("bank nifty is weak"))
        assertNull(CallParser.indexIn("market is weak"))
        assertEquals("280", CallParser.num(280.0)); assertEquals("280.5", CallParser.num(280.5)); assertEquals("1222.55", CallParser.num(1222.55))
        assertEquals(Trig.ZONE, Trig.of("near")); assertEquals(Trig.ABOVE, Trig.of(null))
    }

    @Test fun aSellOnlyWithoutABuy() {
        assertIs<Reading.NotACall>(one("short NIFTY 25000 PE at 80"))
        // "Sell" inside a buy call (sell at the target) does not make it a sell call.
        assertIs<Reading.NewCall>(one("buy NIFTY 25000 CE above 120 sell at 140"))
    }

    @Test fun noIndexInAPartFallsBackToTheHeader() {
        val r = CallParser.read("BANKNIFTY calls\n52000 CE above 300\n52100 CE above 250", on)
        assertEquals(listOf("BANKNIFTY", "BANKNIFTY"), r.map { (it as Reading.NewCall).draft.underlying })
    }
}
