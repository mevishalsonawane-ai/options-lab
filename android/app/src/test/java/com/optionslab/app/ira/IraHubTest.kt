package com.optionslab.app.ira

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.ira.Candle
import com.optionslab.ira.History
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import com.optionslab.ira.Market as IraMarket

/** Ira inside IraAlgo: learning from candles, snapshots, the conversation, and what it keeps on the phone. */
class IraHubTest : RobolectricTest() {
    private fun days(n: Int, start: Double, step: Double, seed: Long): List<Candle> {
        val r = java.util.Random(seed); val out = ArrayList<Candle>()
        var px = start; var d = LocalDate.of(2026, 8, 3); var k = 0
        while (k < n) {
            if (d.dayOfWeek.value <= 5) {
                var t = d.atTime(LocalTime.of(9, 15))
                while (t.toLocalTime().isBefore(LocalTime.of(15, 30))) {
                    val o = px; px += r.nextGaussian() * step
                    out += Candle(t, o, maxOf(o, px) + step / 2, minOf(o, px) - step / 2, px); t = t.plusMinutes(1)
                }
                k++
            }
            d = d.plusDays(1)
        }
        return out
    }

    private val histories = mapOf(
        IraMarket.NIFTY to History(IraMarket.NIFTY, days(25, 24_000.0, 4.0, 1)),
        IraMarket.BANKNIFTY to History(IraMarket.BANKNIFTY, days(25, 52_000.0, 9.0, 2)),
        IraMarket.VIX to History(IraMarket.VIX, days(25, 13.0, 0.02, 3)),
    )

    @After fun down() { IraHub.testLabBars = null; IraAccount.testView = null; IraHub.testHistories = null; IraHub.testAutoLab = false
        IraHub.testLoadHold?.complete(Unit); IraHub.testLoadHold = null; runBlocking { IraHub.forgetAll() }; IraHub.resetAskSpeed() }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!ok()) { check(System.currentTimeMillis() - t0 < 60_000) { "timed out waiting for $what" }; Thread.sleep(50) }
    }

    private val sixty = mapOf(IraMarket.BANKNIFTY to History(IraMarket.BANKNIFTY, days(60, 52_000.0, 9.0, 4)))
    /** Two years of 15-minute candles (the lab's rule: never less), built once. */
    private val twoYears: List<Candle> by lazy {
        val r = java.util.Random(4); val out = ArrayList<Candle>()
        var px = 52_000.0; var d = LocalDate.of(2024, 8, 1)
        while (out.size < 530 * 25) {
            if (d.dayOfWeek.value <= 5) {
                var t = d.atTime(LocalTime.of(9, 15))
                while (t.toLocalTime().isBefore(LocalTime.of(15, 30))) {
                    val o = px; px += r.nextGaussian() * 35
                    out += Candle(t, o, maxOf(o, px) + 15, minOf(o, px) - 15, px); t = t.plusMinutes(15)
                }
            }
            d = d.plusDays(1)
        }
        out
    }

    @Test fun aBacktestIsOfferedAndApprovedAsAPaperArm() = runBlocking {
        IraHub.testLabBars = { _, _ -> twoYears }
        com.optionslab.app.data.PineScripts.init(context)
        IraHub.testHistories = { sixty }
        IraHub.refresh()
        IraHub.ask("Backtest the breakout on BankNifty 15m")
        waitFor("the backtest") { IraHub.state.value.proposals.isNotEmpty() }
        val p = IraHub.state.value.proposals.single()
        assertEquals(com.optionslab.ira.PatternKind.BREAKOUT_UP, p.result.kind)
        assertEquals(IraMarket.BANKNIFTY, p.result.market); assertEquals(15, p.result.minutes)
        assertNull(p.result.error); assertTrue(p.result.days >= com.optionslab.ira.StrategyLab.MIN_DAYS)
        assertTrue(IraHub.state.value.messages.any { it.proposal == p.id && it.text.contains("Backtest of Jarvis: breakout") })
        val said = IraHub.approve(p.id)
        assertTrue(said, said.startsWith("Added"))
        val item = com.optionslab.app.data.PineScripts.items.value.single { it.name == p.result.name }
        assertTrue(item.auto.on); assertEquals("BANKNIFTY", item.auto.symbol); assertEquals("15m", item.auto.interval); assertEquals(1, item.auto.lots)
        assertEquals(p.result.script, item.code)
        assertEquals(IraHub.Proposal.APPROVED, IraHub.state.value.proposals.single().status)
        assertEquals("Already approved.", IraHub.approve(p.id))
        assertEquals("That strategy is gone.", IraHub.approve(99))
    }

    @Test fun dismissedAndNothingToTest() = runBlocking {
        IraHub.testLabBars = { _, _ -> twoYears }
        IraHub.testHistories = { sixty }
        IraHub.refresh()
        IraHub.ask("backtest the hammer on banknifty 1 hour")
        waitFor("the backtest") { IraHub.state.value.proposals.isNotEmpty() }
        val p = IraHub.state.value.proposals.single()
        assertEquals(60, p.result.minutes)
        IraHub.dismiss(p.id)
        assertEquals(IraHub.Proposal.DISMISSED, IraHub.state.value.proposals.single().status)
        IraHub.ask("backtest the hammer on gold")
        assertTrue(IraHub.state.value.messages.last().text.startsWith("I can't write a strategy for Gold"))
    }

    @Test fun theAutomaticHuntOffersOnlyWhatIsWorthATrial() = runBlocking {
        IraHub.testLabBars = { _, _ -> twoYears }
        IraHub.testAutoLab = true
        IraHub.testHistories = { sixty }
        IraHub.refresh()
        IraHub.refresh()                                           // each pattern is tried once a day
        val ps = IraHub.state.value.proposals
        assertTrue(ps.all { it.result.recommended })
        assertEquals(ps.size, ps.map { it.result.name }.toSet().size)
    }

    @Test fun learnsBuildsSnapshotsAndAnswers() = runBlocking {
        IraHub.testHistories = { histories }
        IraHub.refresh()
        val st = IraHub.state.value
        assertEquals(setOf(IraMarket.NIFTY, IraMarket.BANKNIFTY, IraMarket.VIX), st.snaps.keys)
        assertEquals(25, st.days)
        assertTrue(st.learned > 0)
        assertNull(st.problem)
        val learned = st.learned
        IraHub.refresh()                                           // the same candles teach nothing new
        assertEquals(learned, IraHub.state.value.learned)
        assertTrue("the book is kept, encrypted", File(context.noBackupFilesDir, "ira-book.vault").exists())
        IraHub.ask("What is BankNifty doing today?")
        val msgs = IraHub.state.value.messages
        assertEquals(2, msgs.size)
        assertTrue(msgs[1].fromIra && msgs[1].text.startsWith("BankNifty is at"))
        assertTrue(msgs[1].facts.isNotEmpty())
        IraHub.ask("buy 1 lot banknifty 52000 ce")
        assertNotNull(IraHub.state.value.messages.last().order)
        IraHub.ask("   ")
        assertEquals(4, IraHub.state.value.messages.size)
        IraHub.forgetConversation()
        assertTrue(IraHub.state.value.messages.isEmpty())
        // a fresh start reads the book back
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        assertEquals(learned, IraHub.state.value.learned)
    }

    /** 4 Oct additions answered at once, in the app: the clock, the exchange calendar, the distance to a level, reminders. */
    @Test fun quickAnswersOfTheFourthOfOctober() = runBlocking {
        IraHub.testHistories = { histories }
        IraHub.refresh()
        fun last() = IraHub.state.value.messages.last().text
        IraHub.ask("what time is it")
        assertTrue(last(), last().startsWith("It's ") && last().endsWith(", Boss."))
        IraHub.ask("when is the next holiday")
        assertTrue(last(), last().contains("holiday", ignoreCase = true))
        IraHub.ask("how far is nifty from 30000")
        assertTrue(last(), last().contains("points") && last().contains("below 30,000.00"))
        // Reminders are Jarvis's own (CI runs these tests with Jarvis off as well).
        if (com.optionslab.app.BuildConfig.JARVIS) {
            IraHub.ask("remind me to check nifty")
            assertTrue(last(), last().startsWith("Boss, tell me when"))
            IraHub.ask("cancel my reminders")
            assertTrue(last(), last() == "You have no reminders set, Boss." || last().startsWith("I could not reach the reminders"))
        }
        // None of them placed or prepared anything.
        assertTrue(IraHub.state.value.messages.none { it.order != null || it.action != null })
    }

    /** More 4 Oct quick answers, in the app: the next expiry everywhere; the self check, "what did I miss" and the model with Jarvis. */
    @Test fun moreQuickAnswersOfTheFourthOfOctober() = runBlocking {
        fun last() = IraHub.state.value.messages.last().text
        IraHub.ask("when is the next expiry")
        assertTrue(last(), last().startsWith("Next expiry, Boss") || last().startsWith("I have no expiry dates loaded yet"))
        if (com.optionslab.app.BuildConfig.JARVIS) {
            IraHub.ask("run a self check")
            assertTrue(last(), last().startsWith("Self-check:"))
            IraHub.ask("which model are you using")
            assertTrue(last(), last().startsWith("I'm set to Qwen2.5"))
            IraHub.note("Relay down.")
            IraHub.ask("what did I miss")
            assertTrue(last(), last() == "Since you last asked, Boss: Relay down." || last() == "Unlock the phone for that, Boss.")
        }
        assertTrue(IraHub.state.value.messages.none { it.order != null || it.action != null })
    }

    /** "How are you" is answered at once and not in the same words twice running; "who won the match" is still not small talk. */
    @Test fun smallTalkIsAnsweredInVariedWords() = runBlocking {
        IraHub.ask("How are you, Jarvis?")
        IraHub.ask("how are you")
        val ms = IraHub.state.value.messages
        assertEquals(4, ms.size)
        assertTrue(ms[1].fromIra && ms[3].fromIra)
        assertTrue(ms[1].text != ms[3].text)
        assertNull(ms[1].order); assertNull(ms[3].order)
        assertNull(com.optionslab.ira.Chat.smallTalk("who won the match", 0))
    }

    /** A follow-up's reply is the answer, not the "I took that as" note (or the "next time" note beside it). */
    @Test fun theReplySpokenIsTheAnswerPastTheNotes() {
        fun m(fromIra: Boolean, text: String) = IraHub.Msg(fromIra, text)
        val ms = listOf(m(false, "and BankNifty?"), m(true, "I took that as: \"How is BankNifty?\"."),
            m(false, "How is BankNifty?"))
        assertNull(IraHub.replyAfter(ms, "and BankNifty?"))
        val done = ms + m(true, "Got it, Boss: next time \"x\" means \"y\".") + m(true, "Shall I take \"x\" to mean \"y\" from now on, Boss?") +
            m(true, "BankNifty is at 52,000.")
        assertEquals("BankNifty is at 52,000.", IraHub.replyAfter(done, "and BankNifty?")?.text)
        assertEquals("BankNifty is at 52,000.", IraHub.replyAfter(done, "How is BankNifty?")?.text)
    }

    /** "Hit the panic button" is read at once as the kill switch on, and it still waits for Confirm. */
    @Test fun everydayWordsAreReadAtOnceAndStillConfirmed() = runBlocking {
        IraHub.ask("hit the panic button")
        waitFor("read as meant") { IraHub.state.value.messages.any { it.text == "I took that as: \"turn the kill switch on\"." } }
        waitFor("the confirm") { IraHub.state.value.pending.isNotEmpty() }
        IraHub.state.value.pending.forEach { IraHub.cancelAction(it) }
    }

    @Test fun theUsualIsLearned() = runBlocking {
        IraTools.forgetHabits()
        IraHub.ask("the usual")
        waitFor("not known yet") { IraHub.state.value.messages.lastOrNull()?.text?.startsWith("I don't know your usual yet") == true }
        repeat(3) { IraTools.noteHabit("what are the levels on banknifty") }
        IraHub.ask("my usual")
        waitFor("asked as the usual") { IraHub.state.value.messages.any { it.text == "I took that as: \"what are the levels on BankNifty\"." } }
    }

    @Test fun bossWordsAreRememberedNeverActedOn() = runBlocking {
        IraTools.forgetMemory(); IraTools.forgetLearned()
        try {
            IraHub.ask("Jarvis, remember that I stop trading after two losses")
            waitFor("noted") { IraHub.state.value.messages.lastOrNull()?.text?.startsWith("Noted, Boss") == true }
            assertTrue(IraHub.state.value.pending.isEmpty())
            waitFor("kept") { IraTools.memory().isNotEmpty() }
            IraHub.ask("what did I tell you")
            waitFor("recalled") { IraHub.state.value.messages.lastOrNull()?.text?.contains("I stop trading after two losses") == true }
        } finally { IraTools.forgetMemory() }
    }

    @Test fun aTradingWordIsExplained() = runBlocking {
        IraHub.ask("what is theta")
        waitFor("explained") { IraHub.state.value.messages.lastOrNull()?.let { it.fromIra && it.text.startsWith("Theta") } == true }
    }

    @Test fun liveCandlesJoinTheStoredOnesAndHeadlinesAreRead() = runBlocking {
        IraHub.testHistories = { histories }
        val liveDay = histories.getValue(IraMarket.NIFTY).days.last().plusDays(1)
        val fresh = (0 until 120).map { Candle(liveDay.atTime(9, 15).plusMinutes(it.toLong()), 25_000.0 + it, 25_001.0 + it, 24_999.0 + it, 25_000.5 + it) }
        IraHub.testLive = { m -> if (m == IraMarket.NIFTY || m == IraMarket.GOLD) fresh else emptyList() }
        IraHub.testFeed = { url ->
            if (url.contains("economictimes")) "<rss><channel><item><title>Nifty rallies as banks surge</title><link>https://e.com/1</link></item></channel></rss>"
            else throw java.io.IOException("down")
        }
        IraHub.refresh()
        val st = IraHub.state.value
        assertEquals(25_119.5, st.snaps.getValue(IraMarket.NIFTY).price, 1e-9)
        assertEquals(liveDay, st.lastDay)
        assertNotNull("gold comes from its live feed alone", st.snaps[IraMarket.GOLD])
        assertNotNull(st.liveAt)
        assertTrue(st.liveMissing.containsAll(listOf(IraMarket.FINNIFTY, IraMarket.SENSEX)))
        assertEquals("Nifty rallies as banks surge", st.news.single().title)
        assertEquals(IraHub.NEWS_FEEDS.size - 1, st.newsMissing.size)
        IraHub.ask("any news on nifty?")
        assertTrue(IraHub.state.value.messages.last().text, IraHub.state.value.messages.last().text.contains("Nifty rallies as banks surge"))
        // the news is not fetched again within ten minutes
        IraHub.testFeed = { throw java.io.IOException("should not be called") }
        IraHub.refresh()
        assertEquals(1, IraHub.state.value.news.size)
    }

    @Test fun aLiveDayReplacesTheStoredCopy() {
        val day = histories.getValue(IraMarket.NIFTY).days.last()
        val live = listOf(Candle(day.atTime(9, 15), 1.0, 2.0, 0.5, 1.5))
        val m = IraHub.merge(histories, mapOf(IraMarket.NIFTY to live))
        val bars = m.getValue(IraMarket.NIFTY).bars
        assertEquals(1, bars.count { it.t.toLocalDate() == day })
        assertEquals(histories.getValue(IraMarket.NIFTY).bars.count { it.t.toLocalDate() != day } + 1, bars.size)
        assertEquals(histories.getValue(IraMarket.VIX).bars.size, m.getValue(IraMarket.VIX).bars.size)
        assertTrue(IraHub.merge(emptyMap(), mapOf(IraMarket.GOLD to emptyList())).isEmpty())
    }

    @Test fun noDataSaysSo() = runBlocking {
        IraHub.testHistories = { emptyMap() }
        IraHub.refresh()
        assertEquals("No market data on this phone yet", IraHub.state.value.problem)
        IraHub.ask("how is nifty")
        assertEquals("I have no prices for Nifty yet.", IraHub.state.value.messages.last().text)
    }

    @Test fun theBundledRecordIsRead() = runBlocking {
        IraHub.refresh()                                           // the app's own bundled candles, no network
        val st = IraHub.state.value
        assertNotNull(st.snaps[IraMarket.NIFTY]); assertNotNull(st.snaps[IraMarket.BANKNIFTY])
        assertTrue(st.learned > 0)
    }

    @Test fun indexSeriesBecomeCandles() {
        val s = Series(null, 0.0, Right.IX, 1, intArrayOf(555, 556), doubleArrayOf(10.0, 11.0), null, null, null, null, longArrayOf(0, 0))
        val c = IraHub.candles(LocalDate.of(2026, 9, 1), s)
        assertEquals(LocalDate.of(2026, 9, 1).atTime(9, 15), c[0].t)
        assertEquals(11.0, c[1].c, 0.0); assertEquals(11.0, c[1].o, 0.0)
        assertTrue(IraHub.candles(LocalDate.of(2026, 9, 1), Series(null, 1.0, Right.CE, 1, intArrayOf(555), doubleArrayOf(1.0), null, null, null, null, longArrayOf(0))).isEmpty())
    }

    /** The evening review scores each completed session once; the journal and the proposals survive a restart, the conversation does not. */
    @Test fun sessionsAreReviewedOnceAndKeptWithTheProposals() = runBlocking {
        IraHub.testLabBars = { _, _ -> twoYears }
        IraHub.testHistories = { sixty }
        IraHub.refresh()
        val j = IraHub.state.value.journal
        // Only sessions that have closed are reviewed (today's after 15:35 IST): the expectation follows the clock.
        val nowIst = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))
        val closed = sixty.getValue(IraMarket.BANKNIFTY).days.filter { it.isBefore(nowIst.toLocalDate()) || !nowIst.toLocalTime().isBefore(LocalTime.of(15, 35)) }
        assertEquals(closed.takeLast(IraHub.BACKFILL), j.map { it.day })
        assertTrue(j.all { it.worked in 0..it.seen }); assertTrue(j.sumOf { it.seen } > 0)
        assertNotNull(IraHub.state.value.nightlyAt)
        IraHub.refresh()
        assertEquals("a session is reviewed once", j, IraHub.state.value.journal)
        IraHub.ask("backtest the hammer on banknifty 1 hour")
        waitFor("the backtest") { IraHub.state.value.proposals.isNotEmpty() }
        val p = IraHub.state.value.proposals.single()
        IraHub.init(context); IraHub.awaitLoadedBlocking()                                       // the app starts again
        var st = IraHub.state.value
        assertEquals(j, st.journal)
        assertEquals(listOf(p), st.proposals)
        assertTrue("the conversation is remembered", st.messages.any { it.proposal == p.id && it.text.contains("Backtest of") })
        assertTrue(st.messages.any { !it.fromIra && it.text == "backtest the hammer on banknifty 1 hour" })
        IraHub.dismiss(p.id)
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        st = IraHub.state.value
        assertEquals(IraHub.Proposal.DISMISSED, st.proposals.single().status)
        assertEquals("Dismissed. I won't offer that one again today.", st.messages.last().text)
        val f = File(context.noBackupFilesDir, "ira-state.vault")
        assertTrue(f.exists())
        assertTrue("kept encrypted", !String(f.readBytes(), Charsets.ISO_8859_1).contains("Backtest") && !String(f.readBytes(), Charsets.ISO_8859_1).contains("hammer"))
        IraHub.forgetAll()
        assertTrue(!f.exists())
    }

    @Test fun whatIsKeptReadsBackAndOldTriesAreDropped() {
        val today = LocalDate.of(2026, 10, 2)
        val day = IraHub.DayScore(LocalDate.of(2026, 10, 1), 9, 5)
        val text = IraSaved.write(emptyList(), listOf(day), java.time.Instant.parse("2026-10-01T10:10:00Z"),
            listOf("HAMMER|NIFTY|15|2026-09-20", "HAMMER|NIFTY|15|2026-09-30", "junk"), today)
        val r = IraSaved.read(text, today)
        assertEquals(listOf(day), r.journal)
        assertEquals(java.time.Instant.parse("2026-10-01T10:10:00Z"), r.nightlyAt)
        assertEquals(listOf("HAMMER|NIFTY|15|2026-09-30"), r.tested)
        assertEquals(5.0 / 9, day.rate, 1e-12); assertEquals(0.0, IraHub.DayScore(today, 0, 0).rate, 0.0)
        val odd = IraSaved.read("""{"proposals":[{"id":1,"status":"weird"}],"journal":[["bad",1,1]]}""", today)
        assertTrue(odd.proposals.isEmpty() && odd.journal.isEmpty() && odd.nightlyAt == null)
    }

    /** Without the microphone permission (and outside IraAlgo) the voice never starts: nothing listening. */
    @Test fun voiceNeverStartsWithoutTheMicrophone() {
        assertTrue(!JarvisVoice.permitted(context))
        JarvisVoice.start(context)                                  // a no-op here
        val svc = org.robolectric.Robolectric.buildService(JarvisVoice::class.java).create()
        svc.startCommand(0, 1)
        assertEquals(if (com.optionslab.app.BuildConfig.JARVIS) "Jarvis needs the microphone permission to listen." else "Voice is in IraAlgo only.",
            JarvisVoice.state.value.problem)
        assertEquals(JarvisVoice.Mode.OFF, JarvisVoice.state.value.mode)
        svc.destroy()
    }

    /** The model is one pinned file from Hugging Face only, checked by SHA-256; where it cannot run nothing about it runs. */
    @Test fun theModelIsPinnedAndComesFromHuggingFaceOnly() {
        // The fast model is the default; both are pinned to an exact commit and fingerprint.
        assertEquals(IraModel.FAST, IraModel.choice)
        assertTrue(IraModel.URL.startsWith("https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/${IraModel.COMMIT}/"))
        for (s in IraModel.SPECS) { assertEquals(64, s.sha256.length); assertEquals(40, s.commit.length); assertTrue(s.url.startsWith("https://huggingface.co/Qwen/")) }
        for (ok in listOf("huggingface.co", "us.aws.cdn.hf.co", "cdn-lfs.huggingface.co")) assertTrue(ok, IraModel.hostAllowed(ok))
        for (bad in listOf("evil.com", "huggingface.co.evil.com", "nothf.co", "hf.co.evil.net", null)) assertTrue("$bad", !IraModel.hostAllowed(bad))
        val f = File(context.cacheDir, "abc.txt").also { it.writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelDownload.sha256(f))
        IraModel.init(context)
        assertEquals(IraModel.Status.UNSUPPORTED, IraModel.state.value.status)
        assertTrue("never asked where the model cannot run", !IraModel.shouldAsk())
        assertTrue(!IraModel.usable())
        ModelDownload.start(context)                                  // a no-op here
        assertTrue(!IraModel.file(context).exists() && !IraModel.part(context).exists())
    }

    /** A file already in the model's place is checked, never trusted: one that does not match its fingerprint is deleted. */
    @Test fun aModelFileOnThePhoneIsCheckedAgainNotTrusted() {
        val f = IraModel.file(context).also { it.parentFile?.mkdirs(); it.writeText("not the model") }
        assertTrue(!IraModel.recheck(context))
        assertTrue("a wrong file is deleted", !f.exists())
        assertEquals(IraModel.Status.UNSUPPORTED, IraModel.state.value.status)   // not a phone the model runs on
    }

    /** "Analyze my orders": the app's own books, answered as facts; "can you listen to me": what Ira can do. */
    @Test fun yourOwnTradingAndIraItself() = runBlocking {
        var asked: Set<com.optionslab.ira.Section> = emptySet()
        IraAccount.testView = { secs -> asked = secs; com.optionslab.ira.AppView("Paper", mapOf(
            com.optionslab.ira.Section.ORDERS to com.optionslab.ira.AppFacts.orders("Paper",
                listOf(com.optionslab.ira.AppFacts.OrderLine("09:31", "NIFTY25O0724500CE", "BUY", 75, "COMPLETE", 120.5, "Manual · Ira")), true),
            com.optionslab.ira.Section.STRATEGIES to com.optionslab.ira.AppFacts.arms(
                listOf(com.optionslab.ira.AppFacts.ArmLine("Jarvis: breakout", "Pine", true, "BANKNIFTY 15m", 500.0, null, 1)), true))) }
        IraHub.ask("can you analyze my strategies orders")
        waitFor("the app answer") { IraHub.state.value.messages.size == 2 }
        val a = IraHub.state.value.messages.last()
        assertTrue(a.text, a.text.contains("Paper: 1 order today, 1 filled") && a.text.contains("Jarvis: breakout (Pine, BANKNIFTY 15m): on today +Rs 500.00"))
        assertEquals(setOf(com.optionslab.ira.Section.ORDERS, com.optionslab.ira.Section.STRATEGIES), asked)
        assertNull("never an order", a.order)
        IraAccount.testView = { null }
        IraHub.ask("my pnl")
        waitFor("the second answer") { IraHub.state.value.messages.size == 4 }
        assertTrue(IraHub.state.value.messages.last().text.startsWith("I could not read the app"))
        IraHub.ask("you can listen to me")
        assertTrue(IraHub.state.value.messages.last().text, IraHub.state.value.messages.last().text.startsWith(
            if (com.optionslab.app.BuildConfig.JARVIS) "Yes. Voice: switch on" else "Voice is in IraAlgo only"))
        IraHub.ask("what can you do")
        assertTrue(IraHub.state.value.messages.last().text.startsWith("I can tell you about Nifty"))
    }

    /** The real books on a fresh install: every section reads, nothing is placed. */
    @Test fun theRealBooksAreRead() = runBlocking {
        com.optionslab.app.data.PineScripts.init(context)
        val v = IraAccount.read(com.optionslab.ira.Section.entries.toSet() - com.optionslab.ira.Section.CHAIN)
        assertNotNull(v)
        val l = v!!.lines
        assertTrue(l[com.optionslab.ira.Section.ORDERS]!!.toString(), l[com.optionslab.ira.Section.ORDERS]!!.first().let { it == "No orders on Paper today." || it.startsWith("The paper account") })
        assertTrue(l[com.optionslab.ira.Section.RISK]!!.first().startsWith("Kill switch: off"))
        assertEquals(listOf("No price alarms are set."), l[com.optionslab.ira.Section.ALARMS])
        assertTrue(l[com.optionslab.ira.Section.SETTINGS]!!.first().startsWith("Mode: Paper"))
        assertTrue(l[com.optionslab.ira.Section.STATUS]!!.toString(), l[com.optionslab.ira.Section.STATUS]!!.any { it.startsWith("Zerodha: not set up") })
    }


    /** A backtest on less than two years of candles is refused, not judged (the owner's rule). */
    @Test fun lessThanTwoYearsIsRefused() = runBlocking {
        IraHub.testHistories = { sixty }
        IraHub.testLabBars = { m, _ -> sixty.getValue(m).bars }
        IraHub.refresh()
        IraHub.ask("Backtest the breakout on BankNifty 15m")
        waitFor("the backtest") { IraHub.state.value.proposals.isNotEmpty() }
        val p = IraHub.state.value.proposals.single()
        assertTrue(p.result.error!!, p.result.error!!.endsWith("needs at least 2 years"))
        assertTrue(!p.result.recommended)
    }

    /** "Turn on the kill switch", "set an alarm": in IraAlgo every action waits for Confirm; nothing happens before it. */
    @Test fun actionsWaitForConfirmAndUseTheAppsOwnControls() = runBlocking {
        IraHub.ask("turn on the kill switch")
        waitFor("the confirm") { IraHub.state.value.pending.isNotEmpty() }
        val id = IraHub.state.value.pending.single()
        assertEquals("Tap Confirm to turn the kill switch on (no new live positions).", IraHub.state.value.messages.last().text)
        assertTrue("nothing before Confirm", !com.optionslab.app.data.AppSettings.load().guardKill)
        IraHub.confirm(id)
        assertTrue(com.optionslab.app.data.AppSettings.load().guardKill)
        assertTrue(IraHub.state.value.messages.last().text.startsWith("Kill switch on"))
        IraHub.ask("alert me when nifty goes above 25000")
        waitFor("the alarm confirm") { IraHub.state.value.pending.isNotEmpty() }
        IraHub.cancelAction(IraHub.state.value.pending.single())
        assertTrue(com.optionslab.app.data.Alarms.all().isEmpty())
        IraHub.ask("set an alarm on banknifty below 51000")
        waitFor("the alarm confirm") { IraHub.state.value.pending.isNotEmpty() }
        IraHub.confirm(IraHub.state.value.pending.single())
        val a = com.optionslab.app.data.Alarms.all().single()
        assertEquals("BANKNIFTY", a.symbol); assertTrue(!a.above); assertEquals(51000.0, a.level, 0.0)
        com.optionslab.app.data.Alarms.remove(a.id)
        com.optionslab.app.data.AppSettings.save(com.optionslab.app.data.AppSettings.load().copy(guardKill = false))
        IraHub.ask("stop strategy 3")
        waitFor("the answer") { IraHub.state.value.messages.last().let { it.fromIra && it.text != "Kill switch off." } && IraHub.state.value.messages.dropLast(1).last().text == "stop strategy 3" }
        val ans = IraHub.state.value.messages.last()
        assertTrue(ans.text, ans.text.startsWith("Tap Confirm to stop ") || ans.text.startsWith("There are no strategies or arms") || ans.text.startsWith("Which one?"))
        ans.action?.let { IraHub.cancelAction(it) }
        Unit
    }

    /** A secret said or typed is never kept: not in the conversation, not in the saved history. */
    @Test fun secretsAreNeverKept() = runBlocking {
        IraHub.ask("my password is hunter2 and card 4111 1111 1111 1111")
        val said = IraHub.state.value.messages.first { !it.fromIra }.text
        assertEquals("my password is [hidden]", said)
        kotlinx.coroutines.delay(800)
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        assertTrue(IraHub.state.value.messages.none { it.text.contains("hunter2") || it.text.contains("4111") })
    }

    /**
     * Speed, round 2: the saved conversation is read off the main thread at the start. While it is being read nothing is
     * saved over it, and what is said meanwhile is kept after it - nothing is lost either way.
     */
    @Test fun theSavedConversationIsNeverWrittenOverWhileItIsRead() = runBlocking {
        IraHub.ask("what time is it")
        kotlinx.coroutines.delay(800)                               // the keeper saves it
        val f = File(context.noBackupFilesDir, "ira-state.vault")
        assertTrue(f.exists())
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        IraHub.testLoadHold = hold
        IraHub.init(context)                                        // the app starts again; its memory is still being read
        assertTrue(!IraHub.ready.value)
        val savedBefore = f.readBytes()
        IraHub.ask("Nifty levels")                                   // said while it is read
        kotlinx.coroutines.delay(800)
        assertTrue("nothing saved while the memory is read", savedBefore.contentEquals(f.readBytes()))
        hold.complete(Unit)
        IraHub.awaitLoadedBlocking()
        assertTrue(IraHub.ready.value)
        val said = IraHub.state.value.messages.filter { !it.fromIra }.map { it.text }
        assertEquals(listOf("what time is it", "Nifty levels"), said)
        IraHub.testLoadHold = null
        kotlinx.coroutines.delay(800)
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        assertEquals(said, IraHub.state.value.messages.filter { !it.fromIra }.map { it.text })
    }

    /** "Add event RBI policy on 5 Dec" is kept and comes back in "any events"; the Fed's 2026 days are built in. */
    @Test fun eventsAreNotedAndListed() = runBlocking {
        IraHub.ask("add event RBI policy on 5 Dec")
        waitFor("the confirm") { IraHub.state.value.pending.isNotEmpty() }
        IraHub.confirm(IraHub.state.value.pending.single())
        assertTrue(IraHub.state.value.messages.last().text, IraHub.state.value.messages.last().text.startsWith("Noted, Boss: rbi policy"))
        assertEquals("Rbi policy", IraEvents.owner().single().name)
        val upcoming = IraEvents.upcoming(400).map { it.name }
        assertTrue(upcoming.toString(), "Rbi policy" in upcoming)
        IraEvents.remove(IraEvents.owner().single())
        assertTrue(IraEvents.owner().isEmpty())
    }

    /** "Should I trade now?": a verdict with reasons, whatever the hour the test runs at. */
    @Test fun theTradeCheckAnswers() = runBlocking {
        IraHub.testHistories = { histories }
        IraHub.refresh()
        IraHub.ask("Jarvis, should I trade now?")
        waitFor("the verdict") { IraHub.state.value.messages.size == 2 }
        val t = IraHub.state.value.messages.last().text
        assertTrue(t, Regex("(Don't trade now|Careful today|Conditions are normal)").containsMatchIn(t))
        assertTrue(t, t.contains("Nifty is ") && t.endsWith("not a forecast."))
    }

    @Test fun theStudyRunsAtNightOnTwoYearsAndIsAskable() = runBlocking {
        org.junit.Assume.assumeTrue(com.optionslab.app.BuildConfig.JARVIS)
        IraHub.testLabBars = { _, _ -> twoYears }
        IraStudy.studyIfDue(java.time.ZonedDateTime.of(2026, 10, 2, 21, 0, 0, 0, java.time.ZoneId.of("Asia/Kolkata")))
        assertTrue(IraStudy.state.value.findings.isNotEmpty())
        assertTrue(IraStudy.lines().first().startsWith("Studied "))
        assertTrue(IraStudy.lines().any { it.contains("not a promise") })
        IraStudy.overnight(java.time.Instant.now(), "RBI keeps rates unchanged: market-wide policy news.")
        assertTrue(IraStudy.brief().any { it.startsWith("Overnight, 1 headline mattered.") })
    }

    @Test fun patternsAreExplainedAndTradesAreNeverGivenOnDemand() {
        IraHub.ask("What is a hammer?")
        waitFor("the explanation") { IraHub.state.value.messages.lastOrNull()?.fromIra == true }
        assertTrue(IraHub.state.value.messages.last().text.startsWith("A hammer: a small body at the top"))
        IraHub.ask("what should I buy now?")
        waitFor("the answer") { IraHub.state.value.messages.lastOrNull()?.fromIra == true }
        val m = IraHub.state.value.messages.last()
        assertTrue(m.text, m.text.contains("bring you a trade for approval"))
        assertNull(m.action); assertTrue(IraHub.state.value.pending.isEmpty())
    }

    @Test fun jarvisTradesStayOnPaperUntilProvenAndKeepTheirOwnLimit() = runBlocking {
        // Boss, 4 Oct: paper until he switches "AI trades go live" on; even then only once proven, asked each time.
        assertTrue(IraNewsTrades.paperFirst)
        assertTrue(!IraNewsTrades.goesLive()); assertTrue(!IraNewsTrades.goesLive(solo = true))
        assertTrue(IraSolo.provenWhy()!!.startsWith("Solo's trades stay on paper until 20"))
        IraHub.ask("Jarvis, let your trades go live")
        waitFor("the refusal") { IraHub.state.value.messages.lastOrNull()?.fromIra == true }
        assertTrue(IraHub.state.value.messages.last().text, IraHub.state.value.messages.last().text.contains("stay on paper until 20"))
        assertTrue(!IraNewsTrades.goesLive())
        IraHub.ask("set Jarvis loss limit to 2000")
        waitFor("the limit") { IraHub.state.value.messages.lastOrNull()?.fromIra == true }
        IraHub.state.value.pending.singleOrNull()?.let { IraHub.confirm(it) }
        assertEquals(2000.0, IraNewsTrades.dailyLimit, 0.0)
        assertTrue(!IraNewsTrades.lossLimitHit())
        assertTrue(IraNewsTrades.record().contains("Daily loss limit for my trades: Rs 2,000.00."))
        assertEquals(listOf("No trades suggested today."), IraNewsTrades.scorecard())
        // Without a taught voice, voice cannot trade.
        assertTrue(!VoiceGuard.isBoss(null) && !VoiceGuard.isBoss(ShortArray(16_000)))
        assertTrue(VoiceGuard.blocked() != null)
    }

    @Test fun jarvisSpeaksInANormalMaleVoiceByDefault() {
        assertEquals(JarvisVoice.Style.MAN, JarvisVoice.style)
        assertEquals(JarvisVoice.Style.MAN, JarvisVoice.Style.entries.first())
    }

    @Test fun repliesAreSaidToBossInPlainWords() {
        assertTrue(JarvisSpeaker.speakTyped)
        assertEquals("Boss, Nifty is up 120 rupees.", JarvisSpeaker.words("Nifty is up Rs 120."))
    }

    @Test fun theModelTestSaysPlainlyWhenThereIsNoModel() = runBlocking {
        val r = IraModel.selfTest()
        assertTrue(r, r.contains("not on the phone") || r.contains("phone") || r.contains("not ready") || r.contains("IraAlgo only"))
    }

    @Test fun onAHolidayTheMarketIsSaidToBeClosed() {
        // 2 Oct 2026 (Gandhi Jayanti) and a Sunday; a normal Thursday says nothing.
        assertTrue(IraHub.closedToday(LocalDate.of(2026, 10, 2))!!.startsWith("The market is closed today ("))
        assertEquals("The market is closed today (weekend). Prices shown are from the last session.", IraHub.closedToday(LocalDate.of(2026, 10, 4)))
        assertNull(IraHub.closedToday(LocalDate.of(2026, 10, 1)))
    }

    @Test fun riskPerTradeWaitsForTheProvenRecordAndHinglishCommandsWork() = runBlocking {
        IraHub.ask("set Jarvis risk per trade to 2000")
        waitFor("the risk") { IraHub.state.value.messages.lastOrNull()?.fromIra == true }
        IraHub.state.value.pending.singleOrNull()?.let { IraHub.confirm(it) }
        assertEquals(2000.0, IraNewsTrades.riskPerTrade!!, 0.0)
        assertEquals(1, IraNewsTrades.lotsFor(100.0, 75))              // not proven yet: 1 lot
        IraHub.ask("kill switch on karo")
        waitFor("the command") { IraHub.state.value.messages.lastOrNull()?.fromIra == true }
        assertTrue(IraHub.state.value.messages.last().text, IraHub.state.value.messages.last().text.contains("kill switch"))
        assertEquals(null, IraHub.waitingTrade())
        Unit
    }

    @Test fun accountAnswersAreKeptReadyAndReadAfreshAfterAnAction() = runBlocking {
        val first = IraAccount.readFast(setOf(com.optionslab.ira.Section.STATUS))
        assertNotNull(first)
        assertEquals(first, IraAccount.readFast(setOf(com.optionslab.ira.Section.STATUS)))
        IraAccount.invalidate()
        assertNotNull(IraAccount.readFast(setOf(com.optionslab.ira.Section.STATUS)))
        Unit
    }

    @Test fun jarvisTakesTurnsByDefault() {
        // Boss has made no choice, and with no headset or echo cancelling it stays off (CutIn decides automatically).
        assertTrue("cutting in is Boss's choice or automatic", JarvisVoice.cutInChoice == null)
        assertTrue("no headset, no echo cancelling: off", !JarvisVoice.cutInNow(null).on)
    }

    @Test fun muteAndUnmuteAtOnce() = runBlocking {
        IraHub.ask("Jarvis, mute")
        waitFor("muted") { JarvisVoice.muted }
        waitFor("the reply") { IraHub.state.value.messages.lastOrNull()?.let { it.fromIra && it.text.startsWith("Muted") } == true }
        assertTrue("nothing waits for Confirm", IraHub.state.value.pending.isEmpty())
        IraHub.ask("unmute")
        waitFor("unmuted") { !JarvisVoice.muted }
        Unit
    }

    @Test fun theNewQuestionsAreAnswered() = runBlocking {
        val v = IraAccount.read(setOf(com.optionslab.ira.Section.ACTIVITY, com.optionslab.ira.Section.READY,
            com.optionslab.ira.Section.REGIME, com.optionslab.ira.Section.LOSSES))
        assertNotNull(v)
        val l = v!!.lines
        assertTrue(l.toString(), l[com.optionslab.ira.Section.READY]!!.first().let { it.startsWith("Not yet") || it.startsWith("Yes Boss") })
        assertTrue(l[com.optionslab.ira.Section.REGIME]!!.isNotEmpty())
        assertTrue(l[com.optionslab.ira.Section.LOSSES]!!.isNotEmpty())
        if (com.optionslab.app.BuildConfig.JARVIS) {
            IraActivity.add("Stopped ORB 5.")
            assertTrue(IraActivity.lines().last().endsWith("Stopped ORB 5."))
        }
        Unit
    }

    @Test fun batterySaverLeavesNormalGapsAlone() {
        // The test phone is not low on battery: nothing slows down.
        assertEquals(3_000L, com.optionslab.app.work.Battery.gap(context, 3_000))
    }

    @Test fun limitsChangeOnlyAfterConfirm() = runBlocking {
        val before = com.optionslab.app.data.AppSettings.load()
        IraHub.ask("set max lots to 5")
        waitFor("the confirm") { IraHub.state.value.pending.isNotEmpty() }
        val id = IraHub.state.value.pending.single()
        // The confirm anywhere after the question (a late note from an earlier test may land after it).
        val after = IraHub.state.value.messages.let { ms -> ms.drop(ms.indexOfLast { !it.fromIra && it.text == "set max lots to 5" } + 1) }
        assertTrue(after.joinToString(" | ") { it.text },
            after.any { it.fromIra && it.text.contains("max lots per instrument from ${before.guardMaxLots} to 5 (this allows more risk)") })
        assertEquals("nothing before Confirm", before.guardMaxLots, com.optionslab.app.data.AppSettings.load().guardMaxLots)
        IraHub.confirm(id)
        assertEquals(5, com.optionslab.app.data.AppSettings.load().guardMaxLots)
        IraHub.ask("change my PIN to 1234")
        // The refusal anywhere after the question (a late reply from an earlier test may land after it).
        waitFor("the refusal") { IraHub.state.value.messages.let { ms -> ms.drop(ms.indexOfLast { !it.fromIra } + 1).any { it.fromIra && it.text.contains("only in Settings") } } }
        assertTrue(IraHub.state.value.pending.isEmpty())
        com.optionslab.app.data.AppSettings.save(com.optionslab.app.data.AppSettings.load().copy(guardMaxLots = before.guardMaxLots))
    }

    @Test fun aChangeIsLoggedAndUndone() = runBlocking {
        val before = com.optionslab.app.data.AppSettings.load()
        IraHub.ask("set max open positions to 4")
        waitFor("the confirm") { IraHub.state.value.pending.isNotEmpty() }
        IraHub.confirm(IraHub.state.value.pending.single())
        assertEquals(4, com.optionslab.app.data.AppSettings.load().guardMaxOpen)
        val last = com.optionslab.app.data.SettingsLog.all().last()
        assertEquals(com.optionslab.ira.SettingsTalk.Key.MAX_OPEN, last.key); assertEquals("Jarvis", last.by)
        assertTrue(com.optionslab.app.data.SettingsLog.lines().any { it.contains("max open positions") })
        IraHub.ask("undo")
        waitFor("the undo confirm") { IraHub.state.value.pending.isNotEmpty() }
        assertTrue(IraHub.state.value.messages.last().text, IraHub.state.value.messages.last().text.startsWith("Tap Confirm to undo: change max open positions from 4 to"))
        IraHub.confirm(IraHub.state.value.pending.single())
        assertEquals(before.guardMaxOpen, com.optionslab.app.data.AppSettings.load().guardMaxOpen)
        // A change on the Settings screen is logged too.
        com.optionslab.app.data.AppSettings.save(com.optionslab.app.data.AppSettings.load().copy(guardMaxTrades = before.guardMaxTrades + 1))
        assertEquals("Settings screen", com.optionslab.app.data.SettingsLog.all().last().by)
        com.optionslab.app.data.AppSettings.save(before)
    }

    @Test fun quietHoursAndReplays() = runBlocking {
        assertTrue("quiet hours on by default", JarvisVoice.quietHours)
        IraHub.ask("turn off quiet hours")
        waitFor("quiet off") { !JarvisVoice.quietHours }
        JarvisVoice.quietHours = true
        if (com.optionslab.app.BuildConfig.JARVIS) assertTrue(IraNewsTrades.whatIf("what if I had taken the 10:30 suggestion").single().isNotEmpty())
        Unit
    }

    @Test fun coachDefaultsAndAnswers() = runBlocking {
        assertTrue("trailing stops is off until the owner switches it on (it moves live stop orders)", !IraCoach.autoTrail)
        assertEquals(0f, IraHub.caution(), 0f)
        val v = IraAccount.read(setOf(com.optionslab.ira.Section.EXPLAIN_POS))
        assertTrue(v.toString(), v!!.lines[com.optionslab.ira.Section.EXPLAIN_POS]!!.isNotEmpty())
        IraHub.ask("reset my preferences")
        // Any reply after the question (another note may land after it).
        waitFor("the reset") { IraHub.state.value.messages.let { ms -> ms.drop(ms.indexOfLast { !it.fromIra && it.text == "reset my preferences" } + 1) }
            .any { it.fromIra && it.text.contains("every kind of suggestion") } }
        assertTrue(IraHub.state.value.pending.isEmpty())
        // Nothing to trail, overtrade or warn about on an empty book: these never throw.
        IraCoach.trailWatch(); IraCoach.overtradeWatch(); IraCoach.gapWatch()
        assertNull(IraCoach.lossSizeLine())
    }

    @Test fun undoWalksBackAndAChangeIsCheckedAtConfirm() = runBlocking {
        val before = com.optionslab.app.data.AppSettings.load()
        suspend fun change(q: String) {
            IraHub.ask(q)
            waitFor("the confirm for $q") { IraHub.state.value.pending.isNotEmpty() }
            IraHub.confirm(IraHub.state.value.pending.single())
        }
        change("set max lots to 3"); change("set max lots to 4")
        assertEquals(4, com.optionslab.app.data.AppSettings.load().guardMaxLots)
        change("undo"); assertEquals(3, com.optionslab.app.data.AppSettings.load().guardMaxLots)
        change("undo"); assertEquals("a second undo goes further back, never redoes", before.guardMaxLots, com.optionslab.app.data.AppSettings.load().guardMaxLots)
        // Asked, then changed by hand before Confirm: nothing is applied.
        IraHub.ask("set max lots to 6")
        waitFor("the confirm") { IraHub.state.value.pending.isNotEmpty() }
        com.optionslab.app.data.AppSettings.save(com.optionslab.app.data.AppSettings.load().copy(guardMaxLots = 1))
        val r = IraHub.confirm(IraHub.state.value.pending.single())
        assertTrue(r.toString(), r!!.contains("changed since you asked"))
        assertEquals(1, com.optionslab.app.data.AppSettings.load().guardMaxLots)
        com.optionslab.app.data.AppSettings.save(before)
    }

    @Test fun journalTargetsNotesAndAutomations() = runBlocking {
        IraHub.ask("Jarvis, my target today is 3000")
        waitFor("the target") { IraJournal.target() == 3000.0 }
        IraHub.ask("clear my target")
        waitFor("cleared") { IraJournal.target() == null }
        IraHub.ask("Jarvis, note: I bought because of the hammer at support")
        waitFor("the note") { IraHub.state.value.messages.lastOrNull()?.text?.startsWith("Noted, Boss") == true }
        assertTrue(IraJournal.reasons().single().startsWith("Not enough noted trades"))
        assertEquals("No Thursday trades found.", IraJournal.search("how did my thursday trades do").single())
        // Each automation starts at its default: all on, except trailing the owner's own stops (it moves live orders).
        assertTrue(Automations.Auto.entries.all { Automations.on(it) == it.byDefault })
        assertTrue(!Automations.on(Automations.Auto.TRAIL))
        Automations.set(Automations.Auto.STALE, false); assertTrue(!Automations.on(Automations.Auto.STALE)); Automations.set(Automations.Auto.STALE, true)
        // Boss, 4 Oct: a few grouped switches; the safety helpers have none and stay on.
        assertTrue(Automations.Group.entries.size <= 7)
        assertTrue(Automations.Auto.entries.all { it in Automations.ALWAYS || Automations.groupOf(it) != null })
        Automations.set(Automations.Auto.FEED, false); assertTrue(Automations.on(Automations.Auto.FEED))
        Automations.set(Automations.Group.HELP, false); assertTrue(!Automations.on(Automations.Auto.RESCUE)); Automations.set(Automations.Group.HELP, true)
        IraJournal.targetWatch(); IraJournal.staleWatch()
        Unit
    }

    @Test fun mistakesUsageBriefAndExit() = runBlocking {
        IraHub.ask("how is nifty")
        waitFor("the answer") { IraHub.state.value.messages.lastOrNull()?.fromIra == true }
        IraHub.ask("Jarvis, that was wrong")
        waitFor("the mistake noted") { IraHub.state.value.messages.lastOrNull()?.text?.startsWith("Sorry, Boss") == true }
        assertEquals("how is nifty", IraTools.mistakes().last().said)
        assertTrue(IraTools.usageToday().heard >= 2)
        IraHub.ask("short answers please")
        waitFor("brief on") { IraTools.brief }
        IraHub.ask("detailed answers")
        waitFor("brief off") { !IraTools.brief }
        // The emergency exit always waits for Confirm, and asks for the fingerprint where the phone has one.
        IraHub.ask("Jarvis, exit everything")
        waitFor("the confirm") { IraHub.state.value.pending.isNotEmpty() }
        val id = IraHub.state.value.pending.single()
        assertTrue(IraHub.isExit(id))
        if (IraHub.needsFingerprint(id)) assertTrue(IraHub.confirm(id)!!.contains("fingerprint"))
        val r = IraHub.confirm(id, fingerprint = true)
        assertTrue(r.toString(), r != null)
        assertTrue("the kill switch is on after the exit", com.optionslab.app.data.AppSettings.load().guardKill)
        com.optionslab.app.data.AppSettings.save(com.optionslab.app.data.AppSettings.load().copy(guardKill = false))
        assertTrue(!IraTools.weeklyHit())
    }

    @Test fun learnsFromACorrection() = runBlocking {
        IraTools.forgetLearned()
        IraHub.ask("how is the nifdee boi doing")
        waitFor("the first answer") { IraHub.state.value.messages.lastOrNull()?.fromIra == true }
        IraHub.ask("that was wrong")
        waitFor("the mistake") { IraHub.state.value.messages.lastOrNull()?.text?.startsWith("Sorry, Boss") == true }
        IraHub.ask("how is nifty doing")
        // Proposed, never kept by itself: only Boss's yes keeps it.
        waitFor("the proposal") { IraHub.state.value.messages.any { it.text.startsWith("Shall I take \"how is the nifdee boi doing\" to mean \"how is nifty doing\"") } }
        assertTrue(IraTools.learned().isEmpty())
        IraHub.confirm(IraHub.state.value.pending.last())
        waitFor("learned") { IraTools.learned().isNotEmpty() }
        assertEquals("how is nifty doing", IraTools.learned().last().right)
        IraHub.ask("how is the nifdee boi doing")
        waitFor("read as meant") { IraHub.state.value.messages.any { it.text == "I took that as: \"how is nifty doing\"." } }
        IraHub.ask("what words have you learned?")
        waitFor("listed") { IraHub.state.value.messages.lastOrNull()?.text?.contains("\"how is the nifdee boi doing\" means \"how is nifty doing\"") == true }
        IraHub.ask("forget the word nifdee boi")
        waitFor("one forgotten") { IraTools.learned().isEmpty() }
        IraHub.ask("forget what you learned")
        waitFor("forgotten") { IraTools.learned().isEmpty() }
    }

    @Test fun followUpsKeepTheContext() = runBlocking {
        IraHub.ask("How is Nifty doing?")
        waitFor("the answer") { IraHub.state.value.messages.lastOrNull()?.fromIra == true }
        IraHub.ask("and BankNifty?")
        waitFor("read in context") { IraHub.state.value.messages.any { it.text == "I took that as: \"How is BankNifty doing?\"." } }
        waitFor("its answer") { IraHub.state.value.messages.lastOrNull()?.fromIra == true }
        IraHub.ask("what are the levels?")
        waitFor("about the market just asked about") { IraHub.state.value.messages.any { it.text == "I took that as: \"what are the levels on BankNifty\"." } }
        IraHub.ask("stop all strategies")
        waitFor("the command") { IraHub.state.value.messages.lastOrNull()?.fromIra == true }
        IraHub.state.value.pending.forEach { IraHub.cancelAction(it) }
        IraHub.ask("and banknifty?")
        kotlinx.coroutines.delay(500)
        assertTrue("a command is never repeated by a follow-up", IraHub.state.value.messages.none { it.text.contains("I took that as: \"stop") })
    }
}
