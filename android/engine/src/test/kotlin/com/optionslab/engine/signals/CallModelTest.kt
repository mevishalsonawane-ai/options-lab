package com.optionslab.engine.signals

import com.optionslab.engine.Right
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.signals.CallParser.Draft
import com.optionslab.engine.signals.CallParser.Follow
import com.optionslab.engine.signals.CallParser.Reading
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage (b) and the two stages together ([CallModel], [CallReader]) with a deterministic stub model (its reply fixed per
 * message): the prompt and its few-shot examples, the reply's checks (never a number or an index the message does not hold),
 * the merge (the rules' fields stand, the model fills the gaps, a disagreement goes to review), the parse rate of the rules
 * alone against both stages on the fixture, follow-up linking and the claim check.
 */
class CallModelTest {
    private val on = LocalDate.of(2026, 10, 15)

    @Test fun theFixtureAfterBothStagesAndItsParseRates() {
        var rulesOk = 0; var bothOk = 0; var total = 0
        val bad = ArrayList<String>()
        for (c in CallFixture.CASES) {
            total++
            val rules = CallParser.read(c.text, on)
            val rulesSure = !CallReader.needsModel(rules) && rules.all { it !is Reading.NewCall || it.sure }
            if (rulesSure) rulesOk++
            // The stub model: its fixed reply for this message (asked only when the rules need it).
            val merged = CallReader.understand(c.text, on, modelReply = c.model)
            if (merged.all { it.sure }) bothOk++
            val got = merged.joinToString(" || ") { CallFixture.describe(it.reading) }
            val want = c.both ?: c.rules
            if (got != want) bad += "${c.text.replace("\n", "\\n")}\n   want: $want\n   got:  $got"
        }
        assertEquals("", bad.joinToString("\n"))
        println("Signal tester fixture: $total messages; understood by the rules alone $rulesOk (${rulesOk * 100 / total}%), " +
            "by the rules and the stub model $bothOk (${bothOk * 100 / total}%)")
        assertTrue(bothOk > rulesOk)
        assertTrue(rulesOk * 100 / total >= 85)
    }

    @Test fun thePromptCarriesTheFixedAndTheChannelsOwnExamples() {
        val own = (1..6).map { CallModel.Example("msg $it", "{\"action\":\"not_a_call\"}") }
        val p = CallModel.prompt("SENSEX 73100 CE 280", own)
        assertTrue(p.startsWith("<|im_start|>system\n"))
        assertTrue(p.endsWith("SENSEX 73100 CE 280<|im_end|>\n<|im_start|>assistant\n"))
        assertTrue(p.contains("Never guess a stop-loss"))
        assertFalse(p.contains("msg 2<")); assertTrue(p.contains("msg 3<")); assertTrue(p.contains("msg 6<"))
        assertEquals(3 + CallModel.MAX_EXAMPLES, Regex("<\\|im_start\\|>assistant\n\\{").findAll(p).count())
    }

    @Test fun aConfirmedReadingBecomesAnExampleTheReplyReaderAccepts() {
        val text = "#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++"
        val call = CallParser.read(text, on).single()
        val back = CallModel.reply(CallModel.exampleJson(call), text, on)!!
        assertEquals("new", back.action); assertEquals((call as Reading.NewCall).draft.copy(trig = CallParser.Trig.ABOVE), back.draft)
        val f = Reading.FollowUp(Follow.TARGET, 1, ref = Draft("SENSEX", 73100.0, Right.CE))
        assertTrue(CallModel.exampleJson(f).contains("\"followup\":\"target\",\"target_no\":1"))
        assertTrue(CallModel.exampleJson(Reading.FollowUp(Follow.EXIT)).contains("\"action\":\"exit\""))
        assertTrue(CallModel.exampleJson(Reading.NotACall("x")).contains("not_a_call"))
        assertEquals(3, CallModel.FIXED.size)
    }

    @Test fun theReplyIsCheckedNeverTrusted() {
        val text = "Nifty 25000 CE abv 120 sl 100"
        assertNull(CallModel.reply(null, text, on))
        assertNull(CallModel.reply("I think it is a call", text, on))
        assertNull(CallModel.reply("{\"action\":\"maybe\"}", text, on))
        assertNull(CallModel.reply("{\"underlying\":\"NIFTY\"}", text, on))
        assertNull(CallModel.reply("{not json}", text, on))
        val r = CallModel.reply("Sure! {\"action\":\"new\",\"underlying\":\"nifty\",\"strike\":\"25000\",\"right\":\"call\",\"trigger\":120,\"stop\":95," +
            "\"targets\":[140,\"160\"],\"expiry\":\"2026-10-16\",\"confidence\":1.5} done", text, on)!!
        assertEquals("NIFTY", r.draft.underlying); assertEquals(25000.0, r.draft.strike); assertEquals(Right.CE, r.draft.right)
        assertEquals(120.0, r.draft.trigger)
        // Invented: a stop of 95 and targets the message never wrote.
        assertNull(r.draft.stop); assertEquals(emptyList(), r.draft.targets)
        assertEquals(listOf("target 140 (not in the message)", "target 160 (not in the message)", "stop 95 (not in the message)"), r.dropped)
        assertEquals(1.0, r.confidence); assertEquals(LocalDate.of(2026, 10, 16), r.draft.expiry)
        val p = CallModel.reply("{\"action\":\"new\",\"right\":\"PE\",\"expiry\":\"2026-01-01\",\"underlying\":\"BANKNIFTY\"}", text, on)!!
        assertEquals(Right.PE, p.draft.right); assertNull(p.draft.expiry); assertNull(p.draft.underlying); assertEquals(0.5, p.confidence)
        val s = CallModel.reply("{\"action\":\"new\",\"underlying\":\"TATAMOTORS\",\"right\":\"x\"}", "TATAMOTORS 900 CE above 20", on)!!
        assertEquals("TATAMOTORS", s.draft.underlying); assertNull(s.draft.right)
        assertNull(CallModel.reply("{\"action\":\"new\",\"underlying\":\"INFY\"}", "TATAMOTORS 900 CE", on)!!.draft.underlying)
        assertNull(CallModel.reply("{\"action\":\"new\",\"underlying\":\"\"}", text, on)!!.draft.underlying)
    }

    private fun ruleCall(text: String) = CallParser.read(text, on).single()
    private fun reply(json: String, text: String) = CallModel.reply(json, text, on)

    @Test fun theMergeKeepsTheRulesAndFillsOnlyTheGaps() {
        val text = "SENSEX 73100 CE 280 sl 250"
        val rules = ruleCall(text)
        // The model disagrees on the stop: the rules' stop stands and it goes to review.
        val m = CallModel.merge(rules, reply("""{"action":"new","underlying":"SENSEX","strike":73100,"right":"CE","trigger":280,"stop":73100}""", text))
        val d = (m.reading as Reading.NewCall).draft
        assertEquals(250.0, d.stop); assertFalse(m.sure); assertTrue(m.unsure.contains("the rules and the model differ on the stop"))
        // The model differs on the targets: listed.
        val t2 = "SENSEX 73100 CE 280 tgt 310 345"
        val m2 = CallModel.merge(ruleCall(t2), reply("""{"action":"new","targets":[345],"trigger":280}""", t2))
        assertTrue(m2.unsure.contains("the rules and the model differ on the targets"))
        // The model fills targets the rules missed.
        val t3 = "SENSEX 73100 CE 280 then 310"
        val m3 = CallModel.merge(ruleCall(t3), reply("""{"action":"new","trigger":280,"targets":[310],"underlying":"SENSEX","strike":73100,"right":"CE","confidence":0.9}""", t3))
        assertEquals(listOf(310.0), (m3.reading as Reading.NewCall).draft.targets); assertTrue(m3.sure)
        // A percent stop is never replaced by a level.
        val t4 = "NIFTY 25000 CE 120 sl 20%"
        val m4 = CallModel.merge(ruleCall(t4), reply("""{"action":"new","trigger":120,"stop":20}""", t4))
        assertNull((m4.reading as Reading.NewCall).draft.stop); assertEquals(96.0, m4.reading.let { (it as Reading.NewCall).draft.stopLevel })
        // No entry anywhere: still no entry.
        val t5 = "NIFTY 25000 CE target 140"
        val m5 = CallModel.merge(ruleCall(t5), reply("""{"action":"new","targets":[140]}""", t5))
        assertTrue(m5.unsure.contains("no entry price"))
    }

    @Test fun theModelsOtherAnswers() {
        val call = ruleCall("SENSEX 73100 CE 280")
        // A call the model calls not a call: review.
        assertTrue(CallModel.merge(call, reply("""{"action":"not_a_call"}""", "SENSEX 73100 CE 280")).unsure.contains("the model read it as not a call"))
        assertTrue(CallModel.merge(call, reply("""{"action":"followup"}""", "SENSEX 73100 CE 280")).unsure.contains("the model read it as a follow-up"))
        // A follow-up the model calls not a call.
        val f = Reading.FollowUp(Follow.EXIT)
        assertFalse(CallModel.merge(f, reply("""{"action":"not_a_call"}""", "exit")).sure)
        assertTrue(CallModel.merge(f, reply("""{"action":"exit","confidence":0.95}""", "exit")).sure)
        // Not a call, a maybe: the model's follow-up kind, its option and target number.
        val maybe = Reading.NotACall("names CE / PE but no strike", maybe = true)
        val text = "73100 CE wala first one aa gaya"
        val k = CallModel.merge(maybe, reply("""{"action":"followup","followup":"target","target_no":1,"strike":73100,"right":"CE","confidence":0.9}""", text))
        val fu = assertIs<Reading.FollowUp>(k.reading)
        assertEquals(1, fu.targetNo); assertEquals(73100.0, fu.ref?.strike); assertTrue(k.sure); assertEquals("model", k.by)
        val k2 = CallModel.merge(maybe, reply("""{"action":"exit","confidence":0.5}""", "nikal lo"))
        assertEquals(Follow.EXIT, (k2.reading as Reading.FollowUp).kind); assertNull((k2.reading as Reading.FollowUp).ref); assertFalse(k2.sure)
        assertFalse(CallModel.merge(maybe, reply("""{"action":"followup"}""", "x")).sure)
        // Only the model saw a call: never counted without Boss.
        val text2 = "Sensex 73100 c 280"
        val only = CallModel.merge(maybe, reply("""{"action":"new","underlying":"SENSEX","strike":73100,"right":"CE","trigger":280,"confidence":0.99}""", text2))
        assertFalse(only.sure); assertTrue(only.unsure.contains("only the model read it as a call")); assertEquals(0.7, only.confidence)
        val unsureNot = CallModel.merge(maybe, reply("""{"action":"not_a_call","confidence":0.6}""", "x"))
        assertFalse(unsureNot.sure)
        // No model: the rules stand, unsure says why.
        assertTrue(CallModel.merge(call, null, modelMissing = true).unsure.contains("the language model is not on the phone"))
        assertTrue(CallModel.merge(call, null).unsure.contains("the language model gave no usable answer"))
        assertTrue(CallModel.merge(f, null).sure)
        assertTrue(CallReader.understand("SENSEX 73100 CE 280", on, modelMissing = true).single().unsure.contains("the language model is not on the phone"))
    }

    @Test fun followUpsLinkToTheChannelsOpenCall() {
        val t0 = LocalDateTime.of(2026, 10, 15, 9, 28, 4)
        val a = CallReader.Open("a", Draft("SENSEX", 73100.0, Right.CE, targets = listOf(310.0, 345.0, 400.0), trigger = 280.0, stop = 250.0), t0)
        val b = CallReader.Open("b", Draft("NIFTY", 25000.0, Right.PE, targets = listOf(130.0)), t0.plusMinutes(20))
        val yesterday = CallReader.Open("y", Draft("NIFTY", 25000.0, Right.CE), t0.minusDays(1))
        val open = listOf(a, b, yesterday)
        assertEquals("b", CallReader.link(Reading.FollowUp(Follow.TARGET, 1), t0.plusMinutes(30), open))
        assertEquals("a", CallReader.link(Reading.FollowUp(Follow.TARGET, 1), t0.plusMinutes(10), open))
        assertEquals("a", CallReader.link(Reading.FollowUp(Follow.STOP, ref = Draft(null, 73100.0, Right.CE)), t0.plusMinutes(30), open))
        assertNull(CallReader.link(Reading.FollowUp(Follow.STOP, ref = Draft("NIFTY", 73100.0, null)), t0.plusMinutes(30), open))
        // No call of its day before it: the channel's latest open call of the last 3 days (yesterday's); older: an unknown call.
        assertEquals("y", CallReader.link(Reading.FollowUp(Follow.EXIT), t0.minusMinutes(1), open))
        assertNull(CallReader.link(Reading.FollowUp(Follow.EXIT), t0.minusMinutes(1), listOf(yesterday.copy(at = t0.minusDays(4)))))
        // The claim against the prices.
        val bars = (0 until 30).map { i -> Bar(t0.withSecond(0).plusMinutes(i.toLong()), 280.0 + i, 281.0 + i * 2, 279.0 - i, 280.0 + i) }
        assertEquals(310.0, CallReader.claimedLevel(Reading.FollowUp(Follow.TARGET, 1), a.draft))
        assertEquals(400.0, CallReader.claimedLevel(Reading.FollowUp(Follow.TARGET, 99), a.draft))
        assertEquals(333.0, CallReader.claimedLevel(Reading.FollowUp(Follow.TARGET, level = 333.0), a.draft))
        // A bare "done": the next target after the claims already made.
        assertEquals(345.0, CallReader.claimedLevel(Reading.FollowUp(Follow.TARGET), a.draft, prior = 1))
        assertNull(CallReader.claimedLevel(Reading.FollowUp(Follow.EXIT), a.draft))
        val v = CallReader.Verdict.CONFIRMED
        assertEquals(v, CallReader.verdict(Reading.FollowUp(Follow.TARGET, 1), a.draft, bars, t0, 280.0, t0.plusMinutes(20)))
        assertEquals(CallReader.Verdict.NOT_REACHED, CallReader.verdict(Reading.FollowUp(Follow.TARGET, 3), a.draft, bars, t0, 280.0, t0.plusMinutes(20)))
        assertEquals(v, CallReader.verdict(Reading.FollowUp(Follow.STOP), a.draft, bars, t0, 280.0, t0.plusMinutes(29)))
        // No stop given: our stop (20% under the fill) is the level - never reached here.
        assertEquals(CallReader.Verdict.NOT_REACHED, CallReader.verdict(Reading.FollowUp(Follow.STOP), b.draft, bars, t0, 280.0, t0.plusMinutes(29)))
        assertNull(CallReader.verdict(Reading.FollowUp(Follow.EXIT), a.draft, bars, t0, 280.0, t0.plusMinutes(29)))
        assertNull(CallReader.verdict(Reading.FollowUp(Follow.TARGET, 1), a.draft, emptyList(), t0, 280.0, t0.plusMinutes(29)))
        // Down through our stop first, then up to T1: "reached only after our stop"; the same minute counts the stop first.
        val dip = listOf(Bar(t0.withSecond(0), 280.0, 282.0, 240.0, 245.0), Bar(t0.withSecond(0).plusMinutes(1), 245.0, 315.0, 244.0, 312.0))
        assertEquals(CallReader.Verdict.AFTER_STOP, CallReader.verdict(Reading.FollowUp(Follow.TARGET, 1), a.draft, dip, t0, 280.0, t0.plusMinutes(5)))
        val both = listOf(Bar(t0.withSecond(0), 280.0, 320.0, 240.0, 300.0))
        assertEquals(CallReader.Verdict.AFTER_STOP, CallReader.verdict(Reading.FollowUp(Follow.TARGET, 1), a.draft, both, t0, 280.0, t0.plusMinutes(5)))
        assertNotNull(CallReader.needsModel(emptyList()))
        assertFalse(CallReader.needsModel(CallParser.read("NIFTY 25000 CE above 1 tgt 2\nNIFTY 25100 CE 5", on)))
    }
}
