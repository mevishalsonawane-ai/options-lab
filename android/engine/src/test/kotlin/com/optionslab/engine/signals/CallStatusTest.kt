package com.optionslab.engine.signals

import com.optionslab.engine.Right
import com.optionslab.engine.signals.CallParser.Draft
import com.optionslab.engine.signals.CallParser.Follow
import com.optionslab.engine.signals.CallParser.Reading
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Status updates (15 Oct 2026, Boss): channels post the same call again with ✅ / ✔️ ticks on the levels reached, or say "done",
 * "T1 achieved", "SL hit", "booked", "exit", "hold for T3", sometimes as a reply. Each is matched to the earlier open call -
 * reply first, else the same contract that day (the most similar text), else the channel's latest open call - and only
 * annotates it; a status for an unknown call is ignored. Then the channel's claims checked against prices (claims accuracy),
 * the model's prompt carrying the open calls, and the paper month's pass marks.
 */
class CallStatusTest {
    private val on = LocalDate.of(2026, 10, 15)
    private val t0 = LocalDateTime.of(2026, 10, 15, 9, 28, 4)

    private fun open(id: String, text: String, at: LocalDateTime = t0, msgId: Long? = null): CallReader.Open {
        val d = (CallParser.read(text, at.toLocalDate()).single() as Reading.NewCall).draft
        return CallReader.Open(id, d, at, msgId, text)
    }

    @Test fun bossesThreeFormatsReadAsCalls() {
        fun one(t: String) = CallFixture.describe(CallParser.read(t, on).single())
        assertEquals("CALL SENSEX 73100 CE above 280, stop none given, targets 310 / 345 / 400", one("#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++"))
        // Ticks and emojis decorate a fresh call; "SL.VIP" names no stop (a paid channel's), so none is given.
        assertEquals("CALL NIFTY 22500 CE above 55, stop none given, targets 70", one("⚡️ BUY NIFTY50 ✅ ➡️ 22500CE✅ 💘ABOVE 55✔️ 🛑 TARGET @70🔫 ⏩SL.VIP ❌"))
        assertEquals("CALL SENSEX 73100 PE (2026-10-15) above 360, stop 320, targets 380 / 460 / 560",
            one("SENSEX 15 OCT 73100 PE\nBuy above 360\nTARGET: 380/460/560 SL: 320"))
    }

    @Test fun ticksAreReadOnTheLevels() {
        assertEquals(setOf(280.0, 310.0, 345.0), CallParser.ticked("#sensex 73100 CE\nAbove 280✅\nTarget 310✅-345✅-400+++"))
        assertEquals(setOf(310.0, 345.0), CallParser.ticked("Target 310-345-400 ✅✅"))
        assertEquals(setOf(280.0), CallParser.ticked("Above 280 ✔️"))
        assertEquals(setOf(22500.0, 55.0), CallParser.ticked("⚡️ BUY NIFTY50 ✅ ➡️ 22500CE✅ 💘ABOVE 55✔️ 🛑 TARGET @70🔫"))
        assertEquals(setOf(73100.0), CallParser.ticked("SENSEX 73,100 CE ✅"))
        assertEquals(emptySet(), CallParser.ticked("no ticks 280 310"))
        assertEquals(emptySet(), CallParser.ticked("✅✅ only ticks"))
        assertEquals(emptySet(), CallParser.ticked(""))
    }

    @Test fun shortStatusWordsAreFollowUps() {
        fun one(t: String) = CallFixture.describe(CallParser.read(t, on).single())
        assertEquals("FOLLOW target", one("done"))
        assertEquals("FOLLOW target", one("✅"))
        assertEquals("FOLLOW target", one("Done ✅✅"))
        assertEquals("FOLLOW target #1", one("T1 achieved"))
        assertEquals("FOLLOW stop", one("SL hit"))
        assertEquals("FOLLOW book", one("booked"))
        assertEquals("FOLLOW exit", one("exit"))
        assertEquals("FOLLOW hold", one("hold for T3"))
    }

    @Test fun aRepostWithTicksIsAStatusOfTheCallNeverANewOne() {
        val a = open("a", "#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++\nSL 250")
        val later = t0.plusMinutes(25)
        fun st(text: String, open: List<CallReader.Open> = listOf(a), replyTo: Long? = null, at: LocalDateTime = later) =
            CallReader.repost(CallParser.read(text, at.toLocalDate()), text, at, open, replyTo)
        // T1 and T2 ticked: the highest target ticked.
        val s = assertNotNull(st("#sensex 73100 CE\nAbove 280✅\nTarget 310✅-345✅-400+++\nSL 250"))
        assertEquals("a", s.callId); assertEquals(Follow.TARGET, s.follow.kind); assertEquals(2, s.follow.targetNo); assertEquals(345.0, s.follow.level)
        // Only the entry ticked: triggered.
        assertEquals(Follow.TRIGGERED, st("#sensex 73100 CE\nAbove 280✅\nTarget 310-345-400+++\nSL 250")?.follow?.kind)
        // The stop ticked, or "SL hit" added: the stop.
        assertEquals(Follow.STOP, st("#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++\nSL 250✅")?.follow?.kind)
        assertEquals(Follow.STOP, st("#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++\nSL 250\nSL hit")?.follow?.kind)
        // Words added under the same call: "T1 done".
        assertEquals(1, st("#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++\nSL 250\nT1 done")?.follow?.targetNo)
        // The same text again: a re-post (no claim).
        assertEquals(Follow.REPOST, st("#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++\nSL 250")?.follow?.kind)
        // A different contract: a new call. The same strike with a different call later: also new (different text, no ticks).
        assertNull(st("#nifty 25000 CE\nAbove 120✅\nTarget 140"))
        assertNull(st("#sensex 73100 CE\nBuy above 190 only\nTargets 230 260\nSL 150 hero"))
        // A re-entry: the same contract with another entry, even near-identical text - a new call.
        assertNull(st("#sensex 73100 CE\nAbove 290\nTarget 310-345-400+++\nSL 250"))
        // A re-post leaving a line out (no stop line) with T1 ticked: still that call.
        assertEquals(1, st("#sensex 73100 CE\nAbove 280\nTarget 310✅-345-400+++")?.follow?.targetNo)
        // Another day: the call is not that day's, so a new call.
        assertNull(st("#sensex 73100 CE\nAbove 280✅\nTarget 310✅-345-400+++\nSL 250", at = later.plusDays(1)))
        // Nothing open: not a status.
        assertNull(st("#sensex 73100 CE\nAbove 280✅", open = emptyList()))
        // Two messages read as two calls: not a re-post.
        assertNull(st("#sensex 73100 CE above 280 ✅\n#nifty 25000 CE above 120"))
    }

    @Test fun decorationTicksOfTheFirstPostAreNotClaims() {
        val first = "⚡️ BUY NIFTY50 ✅ ➡️ 22500CE✅ 💘ABOVE 55✔️ 🛑 TARGET @70🔫 ⏩SL.VIP ❌"
        val a = open("n", first)
        val same = CallReader.repost(CallParser.read(first, on), first, t0.plusMinutes(5), listOf(a))
        assertEquals(Follow.REPOST, same?.follow?.kind)
        val hit = "⚡️ BUY NIFTY50 ✅ ➡️ 22500CE✅ 💘ABOVE 55✔️ 🛑 TARGET @70✅🔫 ⏩SL.VIP ❌"
        val s = CallReader.repost(CallParser.read(hit, on), hit, t0.plusMinutes(9), listOf(a))
        assertEquals(Follow.TARGET, s?.follow?.kind); assertEquals(1, s?.follow?.targetNo)
    }

    @Test fun repliesLinkFirstThenTheContractThenTheLatest() {
        val a = open("a", "#sensex 73100 CE\nAbove 280\nTarget 310-345-400", msgId = 101)
        val b = open("b", "#sensex 73200 PE\nAbove 250\nTarget 280-300", t0.plusMinutes(5), msgId = 102)
        val c = open("c", "#sensex 73100 CE\nBuy above 190\nTargets 230 260", t0.plusMinutes(10), msgId = 103)
        val all = listOf(a, b, c)
        val at = t0.plusMinutes(30)
        // A reply wins, even over a later call.
        assertEquals("a", CallReader.link(Reading.FollowUp(Follow.TARGET), at, all, replyTo = 101))
        // Not a reply to a known call: the latest open call.
        assertEquals("c", CallReader.link(Reading.FollowUp(Follow.TARGET), at, all, replyTo = 999))
        // The contract named: of the two 73100 CE calls, the one whose text is closer.
        val f = Reading.FollowUp(Follow.TARGET, 1, ref = Draft(null, 73100.0, Right.CE))
        assertEquals("a", CallReader.link(f, at, all, text = "sensex 73100 CE above 280 T1 310 done"))
        assertEquals("c", CallReader.link(f, at, all, text = "73100 CE buy above 190 T1 done"))
        // A contract no open call has: an unknown call (ignored).
        assertNull(CallReader.link(Reading.FollowUp(Follow.STOP, ref = Draft("SENSEX", 74000.0, Right.CE)), at, all))
        // A reply re-post of a call: linked by the reply even with no ticks.
        val r = CallReader.repost(CallParser.read("#sensex 73200 PE above 250", on), "#sensex 73200 PE above 250", at, all, replyTo = 102)
        assertEquals("b", r?.callId)
        assertTrue(CallReader.similarity("a b c", "a b c") == 1.0)
        assertEquals(0.0, CallReader.similarity(null, "x"))
        assertEquals(0.0, CallReader.similarity("✅", "x"))
    }

    @Test fun theModelSeesTheChannelsOpenCalls() {
        val p = CallModel.prompt("T2 done", open = (1..7).map { "SENSEX 7310$it CE above 280" })
        assertTrue(p.contains("Open calls of this channel"))
        assertTrue(p.contains("- SENSEX 73107 CE above 280")); assertTrue(!p.contains("73102 CE"))
        assertTrue(p.endsWith("Message:\nT2 done<|im_end|>\n<|im_start|>assistant\n"))
        assertTrue(!CallModel.prompt("T2 done").contains("Open calls"))
        // The model's own follow-up kinds include the new ones.
        val m = CallModel.reply("""{"action":"followup","followup":"triggered","targets":[],"confidence":0.9}""", "entry triggered", on)
        assertEquals(Follow.TRIGGERED, m?.follow)
    }

    @Test fun claimsAccuracyOnTheScorecard() {
        val items = listOf(
            ChannelScore.Item(ChannelScore.Kind.CALL, triggered = true, nets = mapOf(CallEval.Variant.STOPPED to 500.0), editedLater = true),
            ChannelScore.Item(ChannelScore.Kind.FOLLOW_UP, verdict = CallReader.Verdict.CONFIRMED),
            ChannelScore.Item(ChannelScore.Kind.FOLLOW_UP, verdict = CallReader.Verdict.CONFIRMED),
            ChannelScore.Item(ChannelScore.Kind.FOLLOW_UP, verdict = CallReader.Verdict.NOT_REACHED),
            ChannelScore.Item(ChannelScore.Kind.FOLLOW_UP, verdict = CallReader.Verdict.AFTER_STOP),
            ChannelScore.Item(ChannelScore.Kind.FOLLOW_UP),
        )
        val c = ChannelScore.card("Sensex Kings", items)
        assertEquals(4, c.claims)
        val d = ChannelScore.details(c)
        assertTrue(d.contains("Claims accuracy: of 4 hits the channel claimed, 2 confirmed by the real prices, 1 not reached, 1 reached only after our stop."), d.toString())
        assertTrue(d.contains("1 call was edited after posting (scored as first posted)."))
        assertTrue(ChannelScore.details(ChannelScore.card("x", emptyList())).contains("Claims accuracy: no target or stop claims checked yet."))
    }

    @Test fun theMonthCheckAgainstTheBossesPassMarks() {
        val d0 = LocalDate.of(2026, 10, 16)
        // 45 trades: mostly small wins, net positive even without the top 5.
        val good = (0 until 45).map { SignalPaperRules.Closed(d0.plusDays((it / 3).toLong()), if (it % 4 == 0) -300.0 else 400.0) }
        val card = ChannelScore.Card("x", 60, 45, 0, 0, 0, 45, 45, 45, 34, 400.0, -300.0, mapOf(CallEval.Variant.STOPPED to 9000.0), 600.0, 10, 0.1, 0.01)
        val marks = SignalPaperRules.monthCheck(good, card)
        assertEquals(6, marks.size)
        assertTrue(marks.all { it.ok == true }, marks.toString())
        assertTrue(SignalPaperRules.monthLine(marks).startsWith("passed every pass mark (6 of 6). It stays paper"))
        // Too few, one big loss month, carried by the top 5: fails with the reasons.
        val bad = listOf(SignalPaperRules.Closed(d0, 20_000.0)) + (1..10).map { SignalPaperRules.Closed(d0.plusDays(it.toLong()), -4_000.0) }
        val m2 = SignalPaperRules.monthCheck(bad, card.copy(p = 0.4, movedBefore = 40))
        assertEquals(listOf(false, false, false, false, false, false), m2.map { it.ok })
        assertTrue(SignalPaperRules.monthLine(m2).startsWith("did not pass: at least 40 triggered calls (11 paper trades)"))
        // Nothing yet: undecided, never "passed".
        val m3 = SignalPaperRules.monthCheck(emptyList(), null)
        assertTrue(SignalPaperRules.monthLine(m3).startsWith("did not pass: at least 40"))
        assertTrue(m3.drop(1).all { it.ok == null })
        assertTrue(SignalPaperRules.monthLine(m3.drop(1)).startsWith("not decided yet:"))
    }

    @Test fun anExportKeepsMessageIdsAndReplies() {
        val json = """{"name":"Sensex Kings","messages":[
            {"id":101,"type":"message","date":"2026-10-15T09:28:04","date_unixtime":"1792035484","text":"#sensex 73100 CE above 280"},
            {"id":102,"type":"message","date":"2026-10-15T09:50:00","text":"T1 done","reply_to_message_id":101}]}"""
        val m = TelegramExport.read(json).single().messages
        assertEquals(101L, m[0].id); assertNull(m[0].replyTo)
        assertEquals(102L, m[1].id); assertEquals(101L, m[1].replyTo)
    }
}
