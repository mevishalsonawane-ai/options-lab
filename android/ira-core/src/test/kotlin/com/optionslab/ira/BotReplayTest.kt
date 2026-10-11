package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A closed bot trade's replay with commentary ([BotReplay]): the hold, the specialists' timeline, the counterfactual, the chart. */
class BotReplayTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val d0 = LocalDate.of(2026, 10, 12)
    private fun at(h: Int, m: Int) = d0.atTime(h, m).atZone(ist).toInstant().toEpochMilli()

    private fun record(family: String = "liquidity", label: String = "Liquidity 15+5", exitAt: Long = at(10, 20)): TradeManager.Record {
        val t = TradeManager.ManagedTrade("$family:x", "$family-1", label, "BANKNIFTY", 1, "BANKNIFTY25OCT55000CE", 100.0, 30, 85.0, 140.0,
            TradeManager.Caps(at(15, 10)), TradeManager.Account.PAPER, at(10, 0))
        val notes = listOf(
            TradeManager.Note(at(10, 8), TradeManager.EXTEND_NOTE, "EXTEND", "momentum strong, target out", 118.0, true, lead = "flow", voters = listOf("flow", "brain")),
            TradeManager.Note(at(10, 19), TradeManager.EXIT_EARLY, "FLOW_TURN", "the flow turned against it", 112.0, true, lead = "flow", voters = listOf("flow")))
        return TradeManager.Record(t, TradeManager.Mode.ACT, notes, actual = TradeManager.Exit(exitAt, 112.0, "EARLY"),
            original = TradeManager.Exit(at(10, 40), 140.0, "TARGET"), actedAtMs = at(10, 0))
    }

    private fun bars() = (0 until 60).map { i ->
        val o = 100.0 + i * 0.5
        TradeManager.Bar(at(9, 55) + i * 60_000L, o, o + 2, o - 1, o + 0.5)
    }

    @Test fun theReplayTellsTheTimelineTheExitAndTheCounterfactual() {
        val r = BotReplay.of(record(), bars(), BotReplay.flowWords("AGREES", 62, listOf("STOP_HUNT")))
        assertEquals(at(9, 55), r.bars.first().startMs, "five minutes before the entry")
        assertEquals(at(10, 25), r.bars.last().startMs, "five minutes after the exit")
        assertTrue(r.timeline.first().startsWith("10:00 entry at 100"), r.timeline.first())
        assertTrue(r.timeline.any { it.startsWith("10:19 exit early - Order flow") || it.contains("the flow turned against it") }, r.timeline.toString())
        assertTrue(r.timeline.last().startsWith("10:20 exit at 112"), r.timeline.last())
        assertTrue(r.exitWhy.startsWith("The trade manager took it out"), r.exitWhy)
        assertEquals(360.0, r.net!!, 1e-9)
        assertEquals("The original rules would have exited at 140 (target, 10:40) for +₹1,200 - the trade made +₹360, −₹840 against them.", r.counterfactual)
        assertEquals("Order flow at the entry agreed with the trade (buyers 62 of 100); the trap guard flagged stop hunt.", r.flowAtEntry)
        val l = assertNotNull(BotReplay.layout(r, 300, 200, 6))
        assertEquals(r.bars.size, l.candles.size)
        assertTrue(l.holdFrom < l.holdTo)
        assertNull(BotReplay.layout(r.copy(bars = emptyList()), 300, 200, 6))
        assertNotNull(BotReplay.rangeLine(r))
    }

    @Test fun jarvisPicksTheLastTradeOfTheStrategyNamed() {
        val liq = record()
        val pine = record("pine", "Pine · EMA", at(11, 0))
        val open = record("liquidity").let { it.copy(actual = null) }
        val recs = listOf(liq, pine, open)
        assertEquals(liq, BotReplay.pick(recs, "liquidity"))
        assertEquals(pine, BotReplay.pick(recs, null))
        assertEquals(pine, BotReplay.pick(recs, "pine"))
        assertNull(BotReplay.pick(recs, "hero"))
        assertTrue(BotReplay.say(null, "hero").contains("no closed hero"))
    }
}
