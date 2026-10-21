package com.optionslab.engine.signals

import com.optionslab.engine.signals.CallEval.Variant
import com.optionslab.engine.signals.ChannelScore.Item
import com.optionslab.engine.signals.ChannelScore.Kind
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A channel's scorecard ([ChannelScore]) and its one-time verdict; and the Telegram export reader ([TelegramExport]). */
class ChannelScoreTest {
    private fun win(x: Double, twins: List<Double> = List(20) { -500.0 }, moved: Boolean = false) =
        Item(Kind.CALL, triggered = true, nets = mapOf(Variant.T1 to x / 2, Variant.STOPPED to x), movedBefore = moved, preMove = 0.05, twins = twins)

    @Test fun aSmallCardHasNoPYet() {
        val items = listOf(win(1000.0), win(-400.0, moved = true), Item(Kind.CALL, triggered = false), Item(Kind.CALL, byRules = false, triggered = true),
            Item(Kind.FOLLOW_UP), Item(Kind.NOT_A_CALL), Item(Kind.REVIEW), win(5000.0).copy(edited = true))
        val c = ChannelScore.card("Sensex Kings", items)
        assertEquals(8, c.messages); assertEquals(5, c.calls); assertEquals(1, c.followUps); assertEquals(1, c.notCalls); assertEquals(1, c.review)
        assertEquals(4, c.evaluated); assertEquals(2, c.triggered); assertEquals(1, c.wins)
        assertEquals(1000.0, c.avgWin); assertEquals(-400.0, c.avgLoss)
        assertEquals(600.0, c.mainNet); assertEquals(300.0, c.net[Variant.T1]); assertFalse(Variant.T2 in c.net)
        assertEquals(400.0, c.maxDrawdown); assertEquals(1, c.movedBefore); assertEquals(0.25, c.movedShare)
        assertNull(c.p); assertFalse(c.passed)
        assertEquals(5.0 / 7, c.parseRate!!, 1e-9); assertEquals(0.5, c.triggeredRate); assertEquals(0.5, c.winRate)
        assertEquals("5 calls, 2 triggered, 50% won, with a stop +₹600", ChannelScore.line(c))
        val d = ChannelScore.details(c)
        assertTrue(d.last().startsWith("The test against random entries starts at 40 triggered calls (2 so far)"))
        assertTrue(d.any { it.contains("out at T1 +₹300 · with a stop +₹600") })
        assertTrue(ChannelScore.verdictLine(c).contains("did not beat random"))
    }

    @Test fun fortyTriggeredCallsGetAPAndAVerdict() {
        val good = ChannelScore.card("A", List(40) { win(800.0) })
        assertTrue(good.p!! < 0.05); assertTrue(good.passed)
        assertTrue(ChannelScore.line(good).contains(", p 0.000"))
        assertTrue(ChannelScore.details(good).last().startsWith("Against random entries"))
        assertTrue(ChannelScore.verdictLine(good).contains("better than random entries")); assertTrue(ChannelScore.verdictLine(good).contains("stays shadow"))
        val bad = ChannelScore.card("B", List(40) { win(-800.0, twins = List(20) { 0.0 }) })
        assertFalse(bad.passed); assertTrue(ChannelScore.verdictLine(bad).contains("p 1.000 against random entries"))
        val empty = ChannelScore.card("C", emptyList())
        assertNull(empty.parseRate); assertNull(empty.triggeredRate); assertNull(empty.winRate); assertNull(empty.movedShare); assertEquals(0.0, empty.mainNet)
        assertTrue(ChannelScore.details(empty).any { it.contains("–") })
        assertEquals("−₹1,200", ChannelScore.rs(-1200.0))
    }

    @Test fun aTelegramExportIsReadWithItsTimes() {
        val json = javaClass.getResourceAsStream("/signals/result.json")!!.bufferedReader().readText()
        val chats = TelegramExport.read(json)
        val c = chats.single()
        assertEquals("Sensex Kings", c.name)
        assertEquals(5, c.messages.size)
        val first = c.messages.first()
        assertEquals(LocalDateTime.of(2026, 10, 15, 9, 28, 4), first.at)
        assertEquals("#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++", first.text)
        assertFalse(first.edited)
        assertTrue(c.messages[2].edited)
        assertEquals(LocalDateTime.of(2026, 10, 15, 9, 41, 0), c.messages[1].at)
        // The calls in it, by the rules.
        val calls = c.messages.flatMap { CallParser.read(it.text, it.at.toLocalDate()) }.count { it is CallParser.Reading.NewCall }
        assertEquals(2, calls)
    }

    @Test fun aWholeAccountExportAndBadFiles() {
        val all = """{"chats":{"list":[{"name":"A","messages":[{"type":"message","date":"2026-10-14T10:00:00","text":"x"}]},{"messages":[]}]}}"""
        val chats = TelegramExport.read(all)
        assertEquals(listOf("A", "(no name)"), chats.map { it.name })
        assertEquals(LocalDateTime.of(2026, 10, 14, 10, 0), chats[0].messages.single().at)
        val bad = listOf("not json", "[1,2]", """{"name":"x"}""")
        for (b in bad) assertTrue(runCatching { TelegramExport.read(b) }.exceptionOrNull() is TelegramExport.Bad)
        val odd = """{"name":"Z","messages":[{"type":"message","date":"nope","text":"a"},{"type":"message","date_unixtime":1760500000,"text":5},""" +
            """{"type":"message","date_unixtime":1760500000,"text":["a",{"type":"bold"}]},"x"]}"""
        val z = TelegramExport.read(odd).single()
        assertEquals(listOf("a"), z.messages.map { it.text })
    }
}
