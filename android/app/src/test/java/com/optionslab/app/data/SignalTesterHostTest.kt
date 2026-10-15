package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.signals.CallEval
import com.optionslab.engine.signals.CallParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The Signal tester in SHADOW ([SignalTesterHost], 15 Oct): off by default; only allow-listed channels are recorded (anything
 * else is dropped and never stored); the 15 Oct SENSEX sample is read, checked against the index, priced from its option's
 * minutes with the paper costs and scored - and not one order is placed, paper or live. The language model (a deterministic
 * stub) reads what the rules are unsure of, never while a position is open (positions first), and without it the message waits
 * for review; Boss's fix becomes the channel's example for the next prompt. Follow-ups link to the open call and are checked
 * against the prices. The log survives a restart, and it is in neither the phone backup nor the diagnostics.
 */
class SignalTesterHostTest : RobolectricTest() {
    private lateinit var day: LocalDate
    private val sample = "#sensex 73100 CE\nAbove 280\nTarget 310-345-400+++"
    private val key = "BSE_FO|SIG_73100CE"

    private fun at(h: Int, m: Int, s: Int = 0): LocalDateTime = day.atTime(h, m, s)
    private fun clock(t: LocalDateTime) { Market.testClock = Clock.fixed(t.atZone(IST).toInstant(), IST) }

    /** The option: 270 to 09:28, crossing 280 at 09:29, then +6 a minute to 420. The index: flat 73,000. */
    private fun bars(k: String, d: LocalDate): List<Bar> {
        val out = ArrayList<Bar>()
        var x = LocalTime.of(9, 15)
        while (x.isBefore(LocalTime.of(15, 30))) {
            val m = x.hour * 60 + x.minute - (9 * 60 + 29)
            out += if (k.startsWith("INDEX:")) Bar(d.atTime(x), 73_000.0, 73_000.0, 73_000.0, 73_000.0)
            else when {
                m < 0 -> Bar(d.atTime(x), 270.0, 270.0, 270.0, 270.0)
                m == 0 -> Bar(d.atTime(x), 272.0, 285.0, 270.0, 283.0)
                else -> { val o = minOf(283.0 + 6 * (m - 1), 420.0); Bar(d.atTime(x), o, minOf(o + 7, 421.0), o - 1, minOf(o + 6, 420.0)) }
            }
            x = x.plusMinutes(1)
        }
        return out
    }

    @Before fun up() {
        day = AutomationSupport.tradingDayAt(9, 20).toLocalDate()
        clock(at(9, 0))
        SignalTesterHost.testBars = { k, d -> bars(k, d) }
        SignalTesterHost.testContracts = listOf(Upstox.Contract("SENSEX", day.plusDays(6), 73100.0, Right.CE, 20, key, "SENSEX-SIG-CE"))
        SignalTesterHost.testHolding = false
    }

    @After fun down() { Market.testClock = null; SignalTesterHost.resetForTest() }

    private fun on(vararg channels: String) { SignalTesterHost.setOn(true); channels.forEach { SignalTesterHost.allow(it) } }

    @Test fun offByDefaultAndOnlyAllowListedChannelsAreRecorded() {
        assertFalse(SignalTesterHost.on())
        assertTrue(SignalTesterHost.ingest("Sensex Kings", sample, at(9, 28, 4), SignalTesterHost.PASTED).isEmpty())
        on("Sensex Kings")
        assertTrue(SignalTesterHost.ingest("Some Other Channel", sample, at(9, 28, 4), SignalTesterHost.PASTED).isEmpty())
        assertTrue(SignalTesterHost.view.value.recs.isEmpty())
        // A title with emojis is the same channel; the list refuses a duplicate and an empty name.
        assertEquals(1, SignalTesterHost.ingest("Sensex Kings 🚀", sample, at(9, 28, 4), SignalTesterHost.PASTED).size)
        assertEquals("\"sensex kings\" is already on the list.", SignalTesterHost.allow("sensex kings"))
        assertEquals("Type the channel's name as Telegram shows it.", SignalTesterHost.allow("🚀"))
    }

    /** A source that records which chats were asked for their messages. */
    private class Spy(val chats: Map<String, List<com.optionslab.engine.signals.TelegramExport.Msg>>) : MessageSource {
        val asked = ArrayList<String>()
        override val key = "spy"
        override suspend fun titles() = chats.keys.toList()
        override suspend fun messages(title: String) = chats[title].orEmpty().also { asked += title }
    }

    @Test fun onlyTheSelectedChannelIsEverRead() {
        on()
        fun m(t: String, h: Int) = com.optionslab.engine.signals.TelegramExport.Msg(day.atTime(h, 0), t, false)
        val spy = Spy(linkedMapOf(
            "Sensex Kings" to listOf(m(sample, 9), m("Join our VIP channel for 95% accuracy", 10), m("T1 done 🔥 +300%", 11)),
            "Mom" to listOf(m("NIFTY 25000 CE above 120 call me", 9)),
            "Office group" to listOf(m("BANKNIFTY 52000 PE above 300 tgt 350", 9)),
            "BN Pro" to listOf(m("BANKNIFTY 52000 CE above 300 tgt 350", 9))))
        // Nothing is followed until Boss picks a channel; the picker gets titles only - no chat is asked for its messages.
        assertTrue(SignalTesterHost.view.value.allow.isEmpty())
        val seen = runBlocking { SignalTesterHost.scan(spy) }
        assertEquals(listOf("Sensex Kings", "Mom", "Office group", "BN Pro"), seen.map { it.title })
        assertTrue(seen.all { it.stats == null }); assertTrue(spy.asked.isEmpty())
        // Boss picks one channel: only it is read. Its promo and its results post are not calls; the follow-up links to its call.
        assertTrue(runBlocking { SignalTesterHost.follow(spy, setOf("Sensex Kings")) }.startsWith("Read 3 messages from Sensex Kings: 1 look like calls"))
        assertEquals(listOf("Sensex Kings"), spy.asked)
        val recs = SignalTesterHost.view.value.recs
        assertEquals(listOf("Sensex Kings"), recs.map { it.channel }.distinct())
        assertEquals(listOf(SignalTesterHost.CALL, SignalTesterHost.NOT, SignalTesterHost.FOLLOW), recs.map { it.kind })
        assertTrue(recs.none { (it.text ?: "").contains("call me") || (it.text ?: "").contains("52000") })
        // A message of any other chat handed in directly (a private chat, a group) never reaches the reader or the store.
        assertTrue(SignalTesterHost.ingest("Mom", "NIFTY 25000 CE above 120", day.atTime(12, 0), SignalTesterHost.PASTED).isEmpty())
        assertTrue(SignalTesterHost.ingest("Office group", "BANKNIFTY 52000 PE above 300", day.atTime(12, 0), SignalTesterHost.PASTED).isEmpty())
        assertEquals(3, SignalTesterHost.view.value.recs.size)
        // "Check this chat": on Boss's tap, that one chat only.
        val bn = runBlocking { SignalTesterHost.check(spy, "BN Pro") }
        assertEquals(1, bn.stats!!.calls); assertEquals(listOf("Sensex Kings", "BN Pro"), spy.asked)
        assertTrue(SignalTesterHost.view.value.allow == listOf("Sensex Kings"))
        // The log file never holds another chat's messages.
        val plain = String(com.optionslab.app.security.Vault.readFile(java.io.File(context.noBackupFilesDir, SignalTesterHost.FILE))!!, Charsets.UTF_8)
        assertFalse(plain.contains("call me")); assertFalse(plain.contains("52000 PE"))
    }

    @Test fun theSampleIsReadCheckedPricedAndScoredWithNoOrder() {
        on("Sensex Kings")
        val r = SignalTesterHost.ingest("Sensex Kings", sample, at(9, 28, 4), SignalTesterHost.PASTED).single()
        assertEquals(SignalTesterHost.CALL, r.kind)
        assertEquals("SENSEX 73100 CE above 280, stop none given, targets 310 / 345 / 400", r.draft!!.line())
        assertEquals(sample, r.text)
        // The same message again within 3 minutes (a notification re-posted): dropped.
        assertTrue(SignalTesterHost.ingest("Sensex Kings", sample, at(9, 28, 30), SignalTesterHost.PASTED).isEmpty())
        // 09:45: priced so far (T1, T2 out), still open.
        clock(at(9, 45)); runBlocking { SignalTesterHost.round(at(9, 45)) }
        val mid = SignalTesterHost.view.value.recs.single()
        assertEquals("SENSEX-SIG-CE", mid.symbol); assertEquals(20, mid.lot); assertTrue(mid.checked)
        assertEquals(CallEval.Status.OPEN, mid.result!!.status)
        assertEquals(at(9, 29), mid.result!!.fillAt)
        // 10:00: every variant out (400 reached at 09:49), but the twins wait for the close (they need the whole day).
        clock(at(10, 0)); runBlocking { SignalTesterHost.round(at(10, 0)) }
        val out = SignalTesterHost.view.value.recs.single()
        assertEquals(CallEval.Status.DONE, out.result!!.status); assertTrue(out.twins.isEmpty()); assertFalse(out.final)
        // A restart mid-call: the log is read back and the round goes on from the bars.
        SignalTesterHost.resetForTest()
        SignalTesterHost.testBars = { k, d -> bars(k, d) }
        SignalTesterHost.testContracts = emptyList()
        SignalTesterHost.testHolding = false
        clock(at(15, 40)); runBlocking { SignalTesterHost.round(at(15, 40)) }
        val done = SignalTesterHost.view.value.recs.single()
        assertEquals(CallEval.Status.DONE, done.result!!.status)
        assertEquals(400.0, done.result!!.legs.getValue(CallEval.Variant.GIVEN).exitRaw, 1e-9)
        assertEquals(CallEval.TWINS, done.twins.size)
        val card = SignalTesterHost.view.value.cards.single()
        assertEquals(1, card.triggered); assertTrue(card.mainNet > 0)
        // Not one order, paper or live.
        assertTrue(Paper.state.orders.isEmpty())
        assertTrue(Paper.state.positions.isEmpty())
        // The diagnostics: counts only.
        val diag = SignalTesterHost.diagLine()
        assertTrue(diag.contains("1 calls")); assertFalse(diag.contains("Sensex Kings")); assertFalse(diag.contains("73100"))
        assertTrue(SignalTesterHost.talk(com.optionslab.ira.SignalTalk.Ask("sensex kings")).contains("Sensex Kings: 1 call, 1 triggered"))
    }

    @Test fun aStrikeOffTheGridGoesToReviewAndANotACallKeepsNoText() {
        on("BN Pro")
        SignalTesterHost.testContracts = listOf(Upstox.Contract("SENSEX", day.plusDays(6), 73150.0, Right.CE, 20, key, "X"))
        SignalTesterHost.ingest("BN Pro", "SENSEX 73150 CE above 280 tgt 300", at(9, 28), SignalTesterHost.PASTED)
        clock(at(9, 40)); runBlocking { SignalTesterHost.round(at(9, 40)) }
        val r = SignalTesterHost.view.value.review.single()
        assertTrue(r.unsure.single().contains("not on SENSEX's grid"))
        // Boss confirms it as written: it counts, and the next round does not send it back.
        SignalTesterHost.confirm(r.id, r.draft)
        runBlocking { SignalTesterHost.round(at(9, 41)) }
        assertEquals(SignalTesterHost.CALL, SignalTesterHost.view.value.recs.single { it.id == r.id }.kind)
        val n = SignalTesterHost.ingest("BN Pro", "Good morning traders, big day today", at(9, 41), SignalTesterHost.PASTED).single()
        assertEquals(SignalTesterHost.NOT, n.kind); assertNull(n.text); assertTrue(n.reason!!.startsWith("chatter"))
        // A contract the phone has no list for: not priced, said why.
        SignalTesterHost.ingest("BN Pro", "BANKEX 62000 CE above 300 tgt 350", at(9, 42), SignalTesterHost.PASTED)
        runBlocking { SignalTesterHost.round(at(9, 43)) }
        assertEquals("the phone has no contract list for BANKEX", SignalTesterHost.view.value.recs.last { it.draft?.underlying == "BANKEX" }.unpriced)
    }

    @Test fun aFailedPriceReadIsTriedAgainNeverNoData() {
        on("Sensex Kings")
        SignalTesterHost.ingest("Sensex Kings", sample, at(9, 28, 4), SignalTesterHost.PASTED)
        SignalTesterHost.testBars = { _, _ -> null }
        clock(at(15, 45)); runBlocking { SignalTesterHost.round(at(15, 45)) }
        assertNull(SignalTesterHost.view.value.recs.single().result)
        SignalTesterHost.testBars = { k, d -> bars(k, d) }
        runBlocking { SignalTesterHost.round(at(15, 46)) }
        assertEquals(CallEval.Status.DONE, SignalTesterHost.view.value.recs.single().result!!.status)
        assertTrue(SignalTesterHost.view.value.recs.single().final)
    }

    @Test fun theModelReadsWhatTheRulesAreUnsureOfButNeverWhileAPositionIsOpen() {
        on("Sensex Kings")
        val prompts = ArrayList<String>()
        SignalTesterHost.testModel = { p -> prompts += p; """{"action":"new","underlying":"SENSEX","strike":73100,"right":"CE","trigger":280,"confidence":0.9}""" }
        // Positions first: with a position open the model waits (the background pass the message started included).
        SignalTesterHost.testHolding = true
        val r = SignalTesterHost.ingest("Sensex Kings", "SENSEX 73100 CE 280", at(9, 28), SignalTesterHost.PASTED).single()
        assertEquals(SignalTesterHost.PENDING, r.kind)
        waitModelIdle()
        assertEquals(0, runBlocking { SignalTesterHost.modelPass() })
        assertTrue(prompts.isEmpty())
        assertEquals(SignalTesterHost.PENDING, SignalTesterHost.view.value.recs.single().kind)
        SignalTesterHost.testHolding = false
        assertEquals(1, runBlocking { SignalTesterHost.modelPass() })
        val c = SignalTesterHost.view.value.recs.single()
        assertEquals(SignalTesterHost.CALL, c.kind); assertEquals("rules+model", c.by); assertEquals(280.0, c.draft!!.trigger)
        assertTrue(prompts.last().contains("SENSEX 73100 CE 280"))
    }

    private fun waitModelIdle() { val t0 = System.currentTimeMillis(); while (!SignalTesterHost.modelIdleForTest() && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(10) }

    @Test fun withoutTheModelItWaitsForReviewAndBosssFixTeachesTheNextPrompt() {
        on("Sensex Kings")
        SignalTesterHost.testModelMissing = true
        SignalTesterHost.ingest("Sensex Kings", "73100 CE above 280", at(9, 28), SignalTesterHost.PASTED)
        waitModelIdle(); runBlocking { SignalTesterHost.modelPass() }
        val r = SignalTesterHost.view.value.review.single()
        assertTrue(r.unsure.contains("the language model is not on the phone"))
        assertEquals("Fill in: no index or stock named.", SignalTesterHost.confirm(r.id, CallParser.Draft(null, 73100.0, Right.CE)))
        val d = CallParser.Draft("SENSEX", 73100.0, Right.CE, null, CallParser.Trig.ABOVE, 280.0)
        assertTrue(SignalTesterHost.confirm(r.id, d).startsWith("Confirmed: SENSEX 73100 CE above 280"))
        val c = SignalTesterHost.view.value.recs.single()
        assertEquals(SignalTesterHost.CALL, c.kind); assertEquals("you", c.by)
        assertEquals("That message is no longer waiting for review.", SignalTesterHost.confirm(r.id, null))
        // The next unsure message from that channel: the model's prompt carries Boss's fix.
        SignalTesterHost.testModelMissing = false
        val prompts = ArrayList<String>()
        SignalTesterHost.testModel = { p -> prompts += p; null }
        SignalTesterHost.ingest("Sensex Kings", "73200 CE above 220", at(10, 0), SignalTesterHost.PASTED)
        waitModelIdle(); runBlocking { SignalTesterHost.modelPass() }
        assertTrue(prompts.any { it.contains("73100 CE above 280<|im_end|>\n<|im_start|>assistant\n{\"action\":\"new\",\"underlying\":\"SENSEX\"") })
        // "Not a call": the text goes.
        val r2 = SignalTesterHost.view.value.review.single()
        assertEquals("Marked as not a call.", SignalTesterHost.confirm(r2.id, null))
        assertNull(SignalTesterHost.view.value.recs.last().text)
    }

    @Test fun followUpsLinkToTheOpenCallAndAreCheckedAgainstThePrices() {
        on("Sensex Kings")
        SignalTesterHost.ingest("Sensex Kings", sample, at(9, 28, 4), SignalTesterHost.PASTED)
        val f = SignalTesterHost.ingest("Sensex Kings", "T1 done ✅", at(9, 41), SignalTesterHost.PASTED).single()
        assertEquals(SignalTesterHost.FOLLOW, f.kind)
        assertEquals(SignalTesterHost.view.value.recs.first().id, f.link)
        val s = SignalTesterHost.ingest("Sensex Kings", "T3 done ✅", at(9, 35), SignalTesterHost.PASTED).single()
        clock(at(9, 45)); runBlocking { SignalTesterHost.round(at(9, 45)) }
        val recs = SignalTesterHost.view.value.recs
        assertEquals(com.optionslab.engine.signals.CallReader.Verdict.CONFIRMED, recs.first { it.id == f.id }.verdict)
        // T3 (400) was not reached by 09:35: not reached by the prices.
        assertEquals(com.optionslab.engine.signals.CallReader.Verdict.NOT_REACHED, recs.first { it.id == s.id }.verdict)
        // The scorecard's claims accuracy.
        assertTrue(com.optionslab.engine.signals.ChannelScore.details(SignalTesterHost.view.value.cards.single())
            .contains("Claims accuracy: of 2 hits the channel claimed, 1 confirmed by the real prices, 1 not reached, 0 reached only after our stop."))
    }

    @Test fun aRepostOfAFinishedCallIsStillThatCallNeverANewOne() {
        on("Sensex Kings")
        val c = SignalTesterHost.ingest("Sensex Kings", "$sample\nSL 250", at(9, 28, 4), SignalTesterHost.PASTED).single()
        assertEquals(SignalTesterHost.FOLLOW, SignalTesterHost.ingest("Sensex Kings", "SL hit", at(9, 50), SignalTesterHost.PASTED).single().kind)
        val again = SignalTesterHost.ingest("Sensex Kings", "$sample\nSL 250✅", at(9, 51), SignalTesterHost.PASTED).single()
        assertEquals(SignalTesterHost.FOLLOW, again.kind); assertEquals(c.id, again.link)
        assertEquals(1, SignalTesterHost.view.value.recs.count { it.kind == SignalTesterHost.CALL })
        // The call finished: a later bare "done" has no open call - an unknown call, ignored.
        assertEquals(SignalTesterHost.UNKNOWN_CALL, SignalTesterHost.ingest("Sensex Kings", "done", at(9, 55), SignalTesterHost.PASTED).single().reason)
    }

    @Test fun aTelegramExportIsImportedForItsAllowListedChatOnly() {
        on("Sensex Kings")
        val json = """{"name":"Sensex Kings","messages":[{"type":"message","date":"${day}T09:28:04","text":[{"type":"hashtag","text":"#sensex"}," 73100 CE\nAbove 280\nTarget 310-345-400+++"]},""" +
            """{"type":"message","date":"${day}T10:05:12","edited":"${day}T10:30:00","text":"#sensex 73300 CE\nAbove 210\nTarget 240-270\nSL 185"},""" +
            """{"type":"message","date":"${day}T11:00:00","text":"Join our VIP channel"}]}"""
        assertTrue(runBlocking { SignalTesterHost.importExport(json) }.startsWith("Read 3 messages from Sensex Kings: 2 look like calls"))
        val recs = SignalTesterHost.view.value.recs
        assertEquals(listOf(SignalTesterHost.IMPORTED), recs.map { it.source }.distinct())
        assertTrue(recs[1].edited)
        assertNull(recs[2].text)
        assertTrue(runBlocking { SignalTesterHost.importExport(json.replace("Sensex Kings", "Other")) }.startsWith("None of the chats"))
        assertEquals("This is not a Telegram export (result.json).", runBlocking { SignalTesterHost.importExport("nope") })
    }

    @Test fun theLogIsInNeitherThePhoneBackupNorTheDiagnostics() {
        on("Sensex Kings")
        SignalTesterHost.ingest("Sensex Kings", sample, at(9, 28, 4), SignalTesterHost.PASTED)
        assertTrue(File(context.noBackupFilesDir, SignalTesterHost.FILE).exists())
        assertFalse(File(context.noBackupFilesDir, SignalTesterHost.FILE).readBytes().toString(Charsets.ISO_8859_1).contains("73100"))
        val pass = "Signal-Test-Passphrase-42!".toCharArray()
        val c = runBlocking { Backup.open(Backup.create(context, pass), pass) }
        val text = c.json.toString()
        assertFalse(text.contains(SignalTesterHost.FILE)); assertFalse(text.contains("Sensex Kings")); assertFalse(text.contains("73100"))
        assertFalse(runBlocking { Diag.report() }.contains("Sensex Kings"))
    }

    @Test fun settingsAndPaste() {
        on("Sensex Kings")
        assertEquals("Switch the Signal tester on first.", run { SignalTesterHost.setOn(false); SignalTesterHost.paste("Sensex Kings", sample, at(9, 28), false) })
        SignalTesterHost.setOn(true)
        assertTrue(SignalTesterHost.paste("Sensex Kings", sample, at(9, 28), false).startsWith("Recorded: SENSEX 73100 CE above 280"))
        assertTrue(SignalTesterHost.paste("Nope", sample, at(9, 28), false).startsWith("Not recorded"))
        // The same call pasted again: a re-post of it, never a second call.
        assertEquals("Recorded: re-posted the call.", SignalTesterHost.paste("Sensex Kings", "73100 CE above 280", at(9, 29), true))
        assertTrue(SignalTesterHost.paste("Sensex Kings", "73200 CE above 220", at(9, 30), true).startsWith("Recorded;"))
        assertTrue(SignalTesterHost.shareNow("Sensex Kings", "SL hit", at(9, 50)).startsWith("Recorded"))
        assertEquals("shared (time approximate)", SignalTesterHost.view.value.recs.last().sourceWords)
        SignalTesterHost.setDelay(60); SignalTesterHost.setDelay(45); SignalTesterHost.setValidity(500)
        assertEquals(60, SignalTesterHost.view.value.delaySec); assertEquals(120, SignalTesterHost.view.value.validityMin)
        assertTrue(SignalTesterHost.disallow("Sensex Kings").startsWith("Removed"))
        assertTrue(SignalTesterHost.view.value.allow.isEmpty())
    }
}
