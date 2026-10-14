package com.optionslab.app.data

import com.optionslab.app.ira.Automations
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.OiWallBlast
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The OI-wall check in shadow (14 Oct 2026, R26 rule P; [OiWallShadowHost]): on an expiry day it records the afternoon from
 * memory a minute at a time, logs the first trigger and what its ticket would have done, encrypted; it places no order,
 * paper or live; a non-expiry day, its switch off, a start after 13:00 or a restart after the trigger are all handled.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OiWallShadowHostTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 10, 13)          // a Tuesday: NIFTY's weekly expiry in this test
    private val niftyToken = 256_265L
    private val strikes = (-12..12).map { 25_000.0 + it * 50 }
    private val wall = 25_100.0

    private fun tokenOf(strike: Double, right: String) = 31_000_000L + (strike / 50).toLong() * 2 + if (right == "CE") 0 else 1

    @Before fun up() {
        Market.testClock = java.time.Clock.fixed(day.atTime(11, 0).atZone(zone).toInstant(), zone)
        kite = FakeKite()
        kite.keepAlive = true
        kite.login()
        for (k in strikes) for (r in listOf("CE", "PE"))
            kite.instruments += FakeKite.Ins(tokenOf(k, r), "NIFTY26O13${k.toInt()}$r", "NIFTY", day, k, r, 65)
        runBlocking { Broker.instruments() }
        KiteStream.resetForTest()
        OiWallShadowHost.init(context)
        OiWallShadowHost.wipeNow()
        OiWallShadowHost.resetForTest()
        OiWallShadowHost.testExpiring = { u, d -> u == "NIFTY" && d == day }
        SecurePrefs.putAll(mapOf("oiwall.told" to null))
    }

    @After fun down() {
        OiWallShadowHost.testExpiring = null
        OiWallShadowHost.wipeNow()
        OiWallShadowHost.resetForTest()
        Market.testClock = null
        KiteStream.stop()
        KiteStream.resetForTest()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun ms(t: LocalTime) = day.atTime(t).atZone(zone).toInstant().toEpochMilli()

    /** R26's synthetic firing day (the 12 Sep 2024 shape): flat at 25,000, then up 150 points from 13:30 over 40 minutes. */
    private fun spotAt(row: Int) = 25_000.0 + ((row - OiWallBlast.minute(LocalTime.of(13, 30))) / 40.0).coerceIn(0.0, 1.0) * 150.0

    /**
     * The minute starting [t] on the stream: the index, and every option with its OI (the call wall's falling from 3 lakh
     * to 1.2 lakh between 13:35 and 14:30 when [unwind]) and its day's volume (five times the pace from 13:35). Every call
     * is at Rs 20 (the bought one in R26's band) until 14:40, then 90.
     */
    private fun feedMinute(t: LocalTime, unwind: Boolean = true) {
        val row = OiWallBlast.minute(t)
        val atMs = ms(t) + 30_000
        val sec = atMs / 1000
        val from = OiWallBlast.minute(LocalTime.of(13, 35)); val to = OiWallBlast.minute(LocalTime.of(14, 30))
        val vol = 5_000L * minOf(row + 1, from) + 25_000L * maxOf(0, row + 1 - from)
        val ticks = ArrayList<KiteTicks.Tick>()
        ticks += KiteTicks.Tick(niftyToken, spotAt(row), exchangeTime = sec)
        for (k in strikes) for (r in listOf("CE", "PE")) {
            val oi = if (unwind && r == "CE" && k == wall) (300_000 - ((row - from).toDouble() / (to - from)).coerceIn(0.0, 1.0) * 180_000).toLong()
                else if (r == "CE" && k == wall) 300_000L else 100_000L
            val px = if (r == "CE") (if (t.isBefore(LocalTime.of(14, 40))) 20.0 else 90.0) else 50.0
            ticks += KiteTicks.Tick(tokenOf(k, r), px, volume = vol, oi = oi, exchangeTime = sec)
        }
        KiteStream.feedForTest(ticks, atMs)
    }

    /** Each minute from [from] to [to] (bar starts): fed, then the words lane's round 3 s after it closes. */
    private fun run(from: LocalTime, to: LocalTime, unwind: Boolean = true) {
        var t = from
        while (!t.isAfter(to)) {
            feedMinute(t, unwind)
            val at = t.plusMinutes(1).plusSeconds(3)
            OiWallShadowHost.round(day.atTime(at), ms(at))
            t = t.plusMinutes(1)
        }
    }

    @Test fun anExpiryDayIsRecordedFromMemoryAndTheTriggerIsLoggedWithItsTicketInPoints() {
        // An open position's instrument is followed first, before and after (open positions come first, 14 Oct).
        val held = 77_000_001L
        KiteStream.want("positions", listOf(held))
        run(LocalTime.of(11, 58), LocalTime.of(14, 30))
        // After the trigger the bought option is followed under the log's own owner, beside the position, never instead of it.
        val option = tokenOf(25_200.0, "CE")
        assertTrue(option in KiteStream.followedNow())
        assertTrue(held in KiteStream.followed())
        assertFalse("the log's option is not one of the stream's first group", option in KiteStream.followed())
        run(LocalTime.of(14, 31), LocalTime.of(15, 20))
        // Priced and let go at 15:21; the position is still followed.
        assertFalse(option in KiteStream.followedNow())
        assertTrue(held in KiteStream.followed())
        KiteStream.want("positions", emptyList())
        val r = OiWallShadowHost.resultsOn(day).single()
        assertEquals("NIFTY", r.index)
        val s = r.signal
        assertNotNull("the rule fired", s)
        s!!
        // As research/hunt/r26 on the same shape: up through the 25,100 call wall, held 16 closes, at 14:13 (row 297).
        assertEquals(297, s.row)
        assertEquals(LocalTime.of(14, 13), s.time)
        assertEquals(1, s.side)
        assertEquals(wall, s.wall, 0.0)
        assertEquals(25_200.0, s.strike, 0.0)
        assertEquals(20.0, s.ref, 0.0)
        val t = r.ticket
        assertNotNull("the ticket would have filled", t)
        t!!
        assertEquals(20.05, t.fill, 1e-9)
        assertEquals(1, t.lots)
        assertEquals(65, t.lot)
        val four = t.of(OiWallBlast.Exit.TARGET_4X)!!
        assertEquals("4x", four.sales.last().why)
        assertEquals(80.2, four.sales.last().price, 1e-9)
        assertTrue("${four.points}", four.points > 55)
        assertNull(r.note)
        // Nothing was ordered, paper or live.
        assertTrue(kite.placed.isEmpty())
        assertTrue(Paper.state.trades.isEmpty())
        // The log is encrypted on disk; what others read is in points, never rupees.
        val f = File(context.noBackupFilesDir, "oiwall.vault")
        assertTrue(f.exists())
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("NIFTY"))
        val note = OiWallShadowHost.reviewNotes(day).single()
        assertTrue(note, note.startsWith("OI-wall check (shadow, not traded; information only, not a trade signal), NIFTY: fired at 14:13, 25200 CE at 20.00 (wall 25100)"))
        assertTrue(note, "points a unit after costs" in note)
        val diag = OiWallShadowHost.diagLine(day)
        assertFalse(diag, Regex("Rs|₹|rupee").containsMatchIn(diag))
        // The evening line is put once (remembered across a restart).
        assertEquals(day.toString(), SecurePrefs.getString("oiwall.told"))
        val answer = OiWallShadowHost.answer()
        assertTrue(answer, answer.startsWith("OI-wall check (shadow, not traded; information only, not a trade signal): fired on Nifty at 14:13") && "1 trigger" in answer)
    }

    @Test fun withoutTheOiUnwindingItDoesNotFireAndSaysSo() {
        run(LocalTime.of(11, 58), LocalTime.of(14, 31), unwind = false)
        val r = OiWallShadowHost.resultsOn(day).single()
        assertNull(r.signal)
        assertNull(r.ticket)
        assertTrue(OiWallShadowHost.reviewNotes(day).single().endsWith("did not fire"))
    }

    @Test fun startedAfterOnePmItHasNoWallAndNeverFires() {
        run(LocalTime.of(13, 10), LocalTime.of(14, 31))
        val r = OiWallShadowHost.resultsOn(day).single()
        assertNull(r.signal)
        assertTrue(r.note.orEmpty(), "no option chain at 13:00" in r.note.orEmpty())
    }

    @Test fun aRestartAfterTheTriggerPicksItUpAndSaysPricesAreMissing() {
        run(LocalTime.of(11, 58), LocalTime.of(14, 20))
        assertTrue("pending until the ticket is priced", OiWallShadowHost.resultsOn(day).isEmpty())
        OiWallShadowHost.resetForTest()                    // the process restarts: memory gone, the encrypted log kept
        run(LocalTime.of(14, 21), LocalTime.of(15, 20))
        val r = OiWallShadowHost.resultsOn(day).single()
        assertEquals(297, r.signal!!.row)
        assertTrue(r.note.orEmpty(), "partial data since restart at 14:22" in r.note.orEmpty())
    }

    @Test fun aRestartBeforeTheWindowWithNoTriggerYetIsFlaggedAsPartial() {
        // 15 Oct review: restarted at 12:31, before any trigger was logged - the noon OI peak's window was not all watched.
        run(LocalTime.of(11, 58), LocalTime.of(12, 30))
        OiWallShadowHost.resetForTest()
        run(LocalTime.of(12, 31), LocalTime.of(15, 20))
        val r = OiWallShadowHost.resultsOn(day).single()
        assertEquals(297, r.signal!!.row)
        assertNotNull(r.ticket)
        assertTrue(r.note.orEmpty(), "partial data since restart at 12:32" in r.note.orEmpty())
        // Watched whole from 11:59: no such flag (the first test's day has no note at all).
    }

    @Test fun anOiPacketWithoutATradeIsFiledUnderTheMinuteItArrivedNotTheLastTrade() {
        // 15 Oct review: the call wall last TRADED in the 13:19 minute, but a newer OI packet (no trade) reached the phone in
        // the 13:20 minute. The 13:19 minute's decision must not see that OI: it is filed under 13:20.
        val r19 = OiWallBlast.minute(LocalTime.of(13, 19)); val r20 = r19 + 1
        fun wallTick(oi: Long, tradeAt: LocalTime, arriveAt: LocalTime) = KiteStream.feedForTest(listOf(
            KiteTicks.Tick(niftyToken, 25_000.0, exchangeTime = ms(arriveAt) / 1000),
            KiteTicks.Tick(tokenOf(wall, "CE"), 20.0, volume = 1_000_000, oi = oi, exchangeTime = ms(tradeAt) / 1000,
                lastTradeTime = ms(tradeAt) / 1000)), ms(arriveAt))
        wallTick(300_000, LocalTime.of(13, 19, 10), LocalTime.of(13, 19, 10))
        OiWallShadowHost.round(day.atTime(13, 20, 3), ms(LocalTime.of(13, 20, 3)))
        wallTick(150_000, LocalTime.of(13, 19, 10), LocalTime.of(13, 20, 40))
        OiWallShadowHost.round(day.atTime(13, 21, 3), ms(LocalTime.of(13, 21, 3)))
        assertEquals(300_000.0, OiWallShadowHost.oiForTest("NIFTY", wall, "CE", r19), 0.0)
        assertEquals(150_000.0, OiWallShadowHost.oiForTest("NIFTY", wall, "CE", r20), 0.0)
    }

    @Test fun switchedOffAfterTheTriggerAndOnAgainTheDayIsReportedAsSwitchedOff() {
        run(LocalTime.of(11, 58), LocalTime.of(14, 20))
        val option = tokenOf(25_200.0, "CE")
        assertTrue(option in KiteStream.followedNow())
        // Switched off at 14:22: a final record saying so at once, the option let go.
        Automations.set(Automations.Auto.OIWALL, false)
        Market.testClock = java.time.Clock.fixed(day.atTime(14, 22, 3).atZone(zone).toInstant(), zone)
        runBlocking { OiWallShadowHost.watch() }
        assertFalse(option in KiteStream.followedNow())
        val r = OiWallShadowHost.resultsOn(day).single()
        assertEquals(297, r.signal!!.row)
        assertNull(r.ticket)
        assertEquals("switched off at 14:22 after the trigger: not priced", r.note)
        // On again before 15:21: the day is not restarted, and the evening line says what happened (never "no expiry").
        Automations.set(Automations.Auto.OIWALL, true)
        run(LocalTime.of(14, 30), LocalTime.of(15, 20))
        assertEquals(r.note, OiWallShadowHost.resultsOn(day).single().note)
        assertEquals(day.toString(), SecurePrefs.getString("oiwall.told"))
        val line = OiWallBlast.eveningLine(OiWallShadowHost.resultsOn(day))
        assertTrue(line, "fired on Nifty at 14:13" in line && "switched off at 14:22 after the trigger" in line && "no expiry" !in line)
        assertTrue(OiWallShadowHost.answer(), "1 trigger" in OiWallShadowHost.answer())
    }

    @Test fun aTriggerNotPricedThatDayIsClosedTheNextDay() {
        run(LocalTime.of(11, 58), LocalTime.of(14, 20))
        // The app is not running at 15:21 (killed): the next trading day's first round closes it as not priced.
        OiWallShadowHost.resetForTest()
        val next = LocalDate.of(2026, 10, 14)
        OiWallShadowHost.round(next.atTime(12, 0, 3), next.atTime(12, 0, 3).atZone(zone).toInstant().toEpochMilli())
        val r = OiWallShadowHost.resultsOn(day).single()
        assertEquals(297, r.signal!!.row)
        assertNull(r.ticket)
        assertTrue(r.note.orEmpty(), "not priced: the app was not running at 15:21" in r.note.orEmpty())
    }

    @Test fun aDayWithoutAnExpiryOrWithTheSwitchOffRecordsNothing() {
        OiWallShadowHost.testExpiring = { _, _ -> false }
        run(LocalTime.of(11, 58), LocalTime.of(14, 31))
        assertTrue(OiWallShadowHost.results().isEmpty())
        assertFalse(File(context.noBackupFilesDir, "oiwall.vault").exists())
        // The switch: on by default, its own under Market alerts; off, the lane's round does nothing.
        assertTrue(Automations.Auto.OIWALL.byDefault)
        assertEquals(Automations.Group.MARKET, Automations.groupOf(Automations.Auto.OIWALL))
        OiWallShadowHost.resetForTest()
        OiWallShadowHost.testExpiring = { u, d -> u == "NIFTY" && d == day }
        Automations.set(Automations.Auto.OIWALL, false)
        Market.testClock = java.time.Clock.fixed(day.atTime(14, 31, 3).atZone(zone).toInstant(), zone)
        runBlocking { OiWallShadowHost.watch() }
        assertTrue(OiWallShadowHost.results().isEmpty())
        Automations.set(Automations.Auto.OIWALL, true)
        // A holiday or a weekend: nothing either.
        OiWallShadowHost.round(LocalDate.of(2026, 10, 17).atTime(14, 31), ms(LocalTime.of(14, 31)))
        assertTrue(OiWallShadowHost.results().isEmpty())
    }
}
