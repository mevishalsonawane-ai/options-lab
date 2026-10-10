package com.optionslab.ira

import com.optionslab.ira.OrderFlow.Agreement
import com.optionslab.ira.OrderFlow.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The order flow's evidence log ([FlowShadow]): the strategies, their modes, the lines and the with / against record. */
class FlowShadowTest {
    @Test fun everyDirectionalStrategyIsListedOnceAndDefaultsToShadow() {
        val keys = FlowShadow.STRATEGIES.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        for (k in listOf("orb", "orb_fresh", "orb_sweep", "range_fade", "liquidity:BANKNIFTY", "liquidity:FINNIFTY", "liquidity:MIDCPNIFTY",
            "night_r3", "hero", "solo", "vix_div", "pine", "saved", "jarvis", "mcx_ng_eve", "mcx_silverm_am", "mcx_trend12", "mcx_us_silver"))
            assertTrue(k in keys, k)
        assertEquals(Mode.SHADOW, FlowShadow.setting(emptyMap(), "orb").mode)
        assertEquals(OrderFlow.THRESHOLD, FlowShadow.setting(emptyMap(), "orb").threshold)
        // The paper-only bots can never reach Zerodha; the live-capable ones are the ones a CONFIRM in Live asks the PIN for.
        assertFalse(FlowShadow.strategy("orb_sweep")!!.liveCapable); assertFalse(FlowShadow.strategy("mcx_trend12")!!.liveCapable)
        assertTrue(FlowShadow.strategy("orb")!!.liveCapable); assertTrue(FlowShadow.strategy("liquidity:FINNIFTY")!!.liveCapable)
        assertEquals("liquidity:MIDCPNIFTY", FlowShadow.liquidityKey("MIDCPNIFTY"))
        assertNull(FlowShadow.strategy("nope"))
    }

    @Test fun settingsRoundTripAndBadPartsAreDropped() {
        val m = mapOf("orb" to FlowShadow.Setting(Mode.CONFIRM, 60), "pine" to FlowShadow.Setting(Mode.OFF))
        assertEquals("orb=CONFIRM:60;pine=OFF:55", FlowShadow.encode(m))
        assertEquals(m, FlowShadow.decode(FlowShadow.encode(m)))
        val bad = FlowShadow.decode("orb=CONFIRM:99;x=OFF:55;hero=MAYBE:55;night_r3=SHADOW:abc;;=;vix_div")
        assertEquals(mapOf("orb" to FlowShadow.Setting(Mode.CONFIRM, 80), "night_r3" to FlowShadow.Setting(Mode.SHADOW, 55)), bad)
        assertTrue(FlowShadow.decode(null).isEmpty()); assertTrue(FlowShadow.decode(" ").isEmpty())
    }

    private val read = OrderFlow.Read("BANKNIFTY", 100, OrderFlow.Side.BUYERS, 72, 72, true, 55_000.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0,
        0.2, 0.1, 1.3, 10.0, -5.0, 0.6, OrderFlow.BuildUp.LONG_BUILDUP, 300, mapOf("ofi" to 1.5, "cvd" to null))

    @Test fun linesReadBackIntoSignals() {
        val a = FlowShadow.id("orb", "2026-10-09T11:05", "CE")
        val b = FlowShadow.id("liquidity:BANKNIFTY", "2026-10-09T13:00")
        val c = FlowShadow.id("pine|x", "t")
        assertEquals("pine/x@t", c)
        val lines = listOf(
            FlowShadow.signal(a, 1000, "orb", "BANKNIFTY", 1, false, Mode.SHADOW, 55, Agreement.AGREES, false, read),
            FlowShadow.signal(b, 2000, "liquidity:BANKNIFTY", "BANKNIFTY", -1, true, Mode.CONFIRM, 60, Agreement.DISAGREES, true, null),
            FlowShadow.signal(a, 1001, "orb", "BANKNIFTY", 1, false, Mode.SHADOW, 55, Agreement.AGREES, false, read),   // a repeat: ignored
            FlowShadow.verdict(a, "entered"), FlowShadow.order(a, "P-1"), FlowShadow.result(a, 1250.5),
            FlowShadow.verdict(b, "skipped_by_flow"), FlowShadow.move(b, -12.0, null), FlowShadow.move(a, 20.0, 35.5),
            "garbage", "S|short", "V|unknown|x", FlowShadow.verdict(a, "refused: later"),
        )
        val s = FlowShadow.parse(lines.asSequence())
        assertEquals(listOf(a, b), s.map { it.id })
        val x = s[0]
        assertEquals(1000L, x.epochSec); assertEquals(72, x.buyers); assertEquals("entered", x.verdict)
        assertEquals("P-1", x.orderId); assertEquals(1250.5, x.net!!, 1e-9); assertEquals(35.5, x.m30!!, 1e-9); assertTrue(x.taken)
        val y = s[1]
        assertTrue(y.live && y.skipped); assertEquals(Mode.CONFIRM, y.mode); assertNull(y.buyers); assertFalse(y.taken)
        assertEquals(-12.0, y.m15!!, 1e-9); assertNull(y.m30)
        // Logged while waiting for an approval, skipped at the approval: the later verdict marks it skipped.
        val z = FlowShadow.parse(sequenceOf(
            FlowShadow.signal("w", 5, "orb", "BANKNIFTY", 1, false, Mode.CONFIRM, 55, Agreement.DISAGREES, false, null),
            FlowShadow.verdict("w", "awaiting_approval"), FlowShadow.verdict("w", FlowShadow.SKIPPED))).single()
        assertTrue(z.skipped); assertEquals(FlowShadow.SKIPPED, z.verdict)
        // The trap guard's flags and the traded option's book ride with the signal.
        val trapped = read.copy(flags = setOf(TrapGuard.Trap.PULL_ASK, TrapGuard.Trap.STOP_HUNT))
        val line = FlowShadow.signal("t", 9, "orb", "BANKNIFTY", 1, false, Mode.SHADOW, 55, Agreement.AGREES, false, trapped, "b 1.0x5/1;a 1.1x5/1")
        assertTrue("|b 1.0x5/1;a 1.1x5/1|" in line, line)
        assertEquals(listOf("PULL_ASK", "STOP_HUNT"), FlowShadow.parse(sequenceOf(line)).single().flags)
        assertTrue(FlowShadow.clean("a|b\nc").let { '|' !in it && '\n' !in it }, "no separator or line break inside a field")
    }

    private fun sig(key: String, a: Agreement, net: Double?, live: Boolean = false, skipped: Boolean = false, m30: Double? = null) =
        FlowShadow.Signal("$key${Math.random()}", 1, key, "BANKNIFTY", 1, live, Mode.SHADOW, a, skipped, 60, net = net, m30 = m30)

    @Test fun theRecordSetsTradesWithTheFlowAgainstThoseAgainstIt() {
        val sigs = listOf(
            sig("orb", Agreement.AGREES, 1000.0, m30 = 20.0), sig("orb", Agreement.AGREES, -200.0, m30 = 10.0), sig("orb", Agreement.AGREES, null),
            sig("orb", Agreement.DISAGREES, -500.0, m30 = -8.0), sig("orb", Agreement.DISAGREES, null, skipped = true),
            sig("orb", Agreement.UNKNOWN, 300.0), sig("orb", Agreement.AGREES, 9999.0, live = true),
            sig("pine", Agreement.DISAGREES, 50.0),
        )
        val r = FlowShadow.summary(sigs)
        assertEquals(listOf("orb", "pine"), r.map { it.key })
        val orb = r[0]
        assertEquals(7, orb.signals); assertEquals(1, orb.skipped)
        assertEquals(2, orb.with.trades); assertEquals(1, orb.with.wins); assertEquals(800.0, orb.with.net, 1e-9)
        assertEquals(50, orb.with.winPct); assertEquals(15.0, orb.with.avgMove!!, 1e-9)
        assertEquals(1, orb.against.trades); assertEquals(0, orb.against.winPct); assertEquals(-8.0, orb.against.avgMove!!, 1e-9)
        assertEquals(1, orb.unknown.trades)
        assertEquals("With flow: 2 trades, 50% won, +Rs 800 · against: 1 trade, 0% won, −Rs 500 · 1 skipped by the flow · 7 signals logged",
            FlowShadow.recordLine(orb))
        assertEquals("No signals logged yet.", FlowShadow.recordLine(null))
        assertEquals("no closed paper trades", FlowShadow.tallyWords(FlowShadow.Tally()))
        assertNull(FlowShadow.Tally().avgMove)
    }

    @Test fun jarvisSaysHowTheFlowIsHelping() {
        assertTrue(FlowShadow.helpAsked("how is order flow helping?"))
        assertTrue(FlowShadow.helpAsked("Is the order-flow filter working"))
        assertTrue(FlowShadow.helpAsked("order flow ka record kya hai"))
        assertFalse(FlowShadow.helpAsked("what is the order flow on banknifty"))
        assertFalse(FlowShadow.helpAsked("help me with nifty"))
        assertTrue(FlowShadow.helpAnswer(emptyList()).startsWith("No signals logged with the order flow yet"))
        val sigs = listOf(sig("orb", Agreement.AGREES, 1000.0, m30 = 12.0), sig("orb", Agreement.DISAGREES, -400.0, m30 = -3.0),
            sig("liquidity:BANKNIFTY", Agreement.DISAGREES, null, skipped = true))
        val a = FlowShadow.helpAnswer(FlowShadow.summary(sigs))
        assertTrue(a.startsWith("3 signals logged with the flow, 1 skipped by it. Paper trades taken with the flow: 1 trade, 100% won, +Rs 1,000; " +
            "against it: 1 trade, 0% won, −Rs 400."), a)
        assertTrue("+12.0 points" in a && "ORB: with +Rs 1,000 over 1, against −Rs 400 over 1" in a && "Too few trades" in a, a)
        val many = (1..20).flatMap { listOf(sig("orb", Agreement.AGREES, 100.0), sig("orb", Agreement.DISAGREES, -100.0)) }
        assertTrue("enough trades to start comparing" in FlowShadow.helpAnswer(FlowShadow.summary(many)))
    }
}
