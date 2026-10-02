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

    @After fun down() { IraHub.testHistories = null; IraHub.testAutoLab = false; runBlocking { IraHub.forgetAll() } }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!ok()) { check(System.currentTimeMillis() - t0 < 60_000) { "timed out waiting for $what" }; Thread.sleep(50) }
    }

    private val sixty = mapOf(IraMarket.BANKNIFTY to History(IraMarket.BANKNIFTY, days(60, 52_000.0, 9.0, 4)))

    @Test fun aBacktestIsOfferedAndApprovedAsAPaperArm() = runBlocking {
        com.optionslab.app.data.PineScripts.init(context)
        IraHub.testHistories = { sixty }
        IraHub.refresh()
        IraHub.ask("Backtest the breakout on BankNifty 15m")
        waitFor("the backtest") { IraHub.state.value.proposals.isNotEmpty() }
        val p = IraHub.state.value.proposals.single()
        assertEquals(com.optionslab.ira.PatternKind.BREAKOUT_UP, p.result.kind)
        assertEquals(IraMarket.BANKNIFTY, p.result.market); assertEquals(15, p.result.minutes)
        assertNull(p.result.error); assertEquals(60, p.result.days)
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
        IraHub.init(context)
        assertEquals(learned, IraHub.state.value.learned)
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
}
