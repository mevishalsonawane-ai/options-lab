package com.optionslab.ira

import com.optionslab.engine.options.ChainRow
import com.optionslab.engine.options.ChainSnapshot
import com.optionslab.engine.options.OptLeg
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChainIntelTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val expiry = LocalDate.of(2026, 10, 7)

    /** Nine strikes 24,000-24,400 around a 24,160 spot; ATM 24,150. Calls written at 24,300, puts at 24,000. */
    private fun read(h: Int, m: Int, ceAdd: Long = 0, peAdd: Long = 0, start: Boolean = true, putIv: Double = 15.0, callIv: Double = 11.0,
                     atmCe: Double = 95.0, atmPe: Double = 88.0) = ChainIntel.Read("NIFTY", expiry, today.atTime(h, m), 24_160.0, 24_150.0, 12.4,
        (0..8).map { i ->
            val k = 24_000.0 + i * 50
            val ce = 100_000L + (if (k == 24_300.0) 800_000 + ceAdd else 0) - (if (k == 24_050.0) 30_000 else 0)
            val pe = 90_000L + (if (k == 24_000.0) 710_000 + peAdd else 0) - (if (k == 24_400.0) 20_000 else 0)
            ChainIntel.Strike(k, ce, pe, if (start) 100_000L else null, if (start) 90_000L else null,
                if (k == 24_150.0) atmCe else 200.0 - i * 20, if (k == 24_150.0) atmPe else 10.0 + i * 20,
                ceIv = if (k > 24_150.0) callIv else 12.0, peIv = if (k < 24_150.0) putIv else 12.5)
        })

    @Test fun theQuestions() {
        assertEquals(ChainIntel.Ask.WRITING, ChainIntel.asked("Where is the most call writing?"))
        assertEquals(ChainIntel.Ask.WRITING, ChainIntel.asked("Jarvis, which strike has the most put writing in BankNifty"))
        assertEquals(ChainIntel.Ask.WRITING, ChainIntel.asked("where are the call writers"))
        assertEquals(ChainIntel.Ask.SHIFT, ChainIntel.asked("How has OI shifted since morning?"))
        assertEquals(ChainIntel.Ask.SHIFT, ChainIntel.asked("how has the open interest changed since the open"))
        assertEquals(ChainIntel.Ask.SKEW, ChainIntel.asked("What's the IV skew - are puts dearer than calls?"))
        assertEquals(ChainIntel.Ask.SKEW, ChainIntel.asked("are calls more expensive than puts"))
        assertEquals(ChainIntel.Ask.MOVE, ChainIntel.asked("What's the expected move by expiry from the straddle?"))
        assertEquals(ChainIntel.Ask.MOVE, ChainIntel.asked("what is the ATM straddle implying"))
        assertEquals(ChainIntel.Ask.MOVE, ChainIntel.asked("expected move by expiry for nifty"))
        assertEquals(ChainIntel.Ask.ALL, ChainIntel.asked("give me the option chain deep dive"))
        // Not these: the broad chain read, the VIX range, moves, advice and forecasts, Boss's own book.
        assertNull(ChainIntel.asked("what is the nifty pcr and max pain"))
        assertNull(ChainIntel.asked("what's the expected move today"))
        assertNull(ChainIntel.asked("how much has nifty moved since morning"))
        assertNull(ChainIntel.asked("should I sell calls where the most call writing is"))
        assertNull(ChainIntel.asked("will the straddle expand tomorrow"))
        assertNull(ChainIntel.asked("how is my straddle doing"))
        assertNull(ChainIntel.asked("make the case"))
        assertFalse(TradeCase.asked("where is the most call writing"))
        assertFalse(ExpectedRange.asked("where is the most call writing"))
        assertEquals("NIFTY", ChainIntel.underlying(emptyList()))
        assertEquals("BANKNIFTY", ChainIntel.underlying(listOf(Market.BANKNIFTY)))
        assertNull(ChainIntel.underlying(listOf(Market.GOLD)))
    }

    @Test fun whereTheWritingIs() {
        val s = ChainIntel.writing(read(11, 0), today)
        assertTrue(s.startsWith("Nifty chain at 11:00 (expiry 7 Oct, spot 24,160), across the 9 strikes I read:"), s)
        assertTrue(s.contains("the most call writing today is at 24,300 (+800,000 OI)"), s)
        assertTrue(s.contains("The most put writing is at 24,000 (+710,000 OI)"), s)
        assertTrue(s.contains("The biggest OI stands at 24,300 on calls (900,000) and 24,000 on puts (800,000)."), s)
        assertTrue(s.contains("a reading, not a promise"), s)
        // Without where OI started: OI as it stands, said so.
        val n = ChainIntel.writing(read(11, 0, start = false), today)
        assertTrue(n.contains("doesn't show where OI started today") && n.contains("most call OI at 24,300 (900,000)"), n)
        // An old chain says its day.
        assertTrue(ChainIntel.writing(read(15, 29), today.plusDays(1)).startsWith("Nifty chain at 15:29 on 5 Oct"))
    }

    @Test fun howOiShifted() {
        val morning = read(9, 40)
        val now = read(11, 0, ceAdd = 50_000, peAdd = 200_000)
        val s = ChainIntel.shift(now, morning, today)
        assertTrue(s.startsWith("Nifty chain at 11:00"), s)
        assertTrue(s.contains("Since the day's first OI reading, across the 9 strikes near the money: call OI +820,000, put OI +890,000, put-call ratio 0.90 to 0.99."), s)
        assertTrue(s.contains("Most OI shed: calls at 24,050 (-30,000), puts at 24,400 (-20,000)."), s)
        assertTrue(s.contains("Since my first read today at 09:40 (spot 24,160 then): call OI +50,000, put OI +200,000"), s)
        assertTrue(s.contains("Most added since then: calls at 24,300 (+50,000), puts at 24,000 (+200,000)."), s)
        // One read and no start: says it can't tell yet.
        val one = ChainIntel.shift(read(11, 0, start = false), null, today)
        assertTrue(one.contains("I can't say how it shifted yet"), one)
        // A read only a minute older is not compared.
        assertFalse(ChainIntel.shift(read(11, 0), read(10, 59), today).contains("Since my first read"))
    }

    @Test fun theSkew() {
        val s = ChainIntel.skew(read(11, 0, putIv = 15.0, callIv = 11.0), read(9, 40, putIv = 14.0, callIv = 11.5), today)
        assertTrue(s.contains("ATM IV 12.4%"), s)
        assertTrue(s.contains("150 points either side, the 24,000 put is at 15.0% IV and the 24,300 call at 11.0%: puts are dearer than calls by 4.0 IV points."), s)
        assertTrue(s.contains("At my 09:40 read the same pair was 14.0% and 11.5%, a gap of +2.5 points."), s)
        assertTrue(ChainIntel.skew(read(11, 0, putIv = 11.2, callIv = 11.0), null, today).contains("about even"))
        assertTrue(ChainIntel.skew(read(11, 0, putIv = 10.0, callIv = 12.0), null, today).contains("calls are dearer than puts by 2.0"))
    }

    @Test fun theImpliedMove() {
        val s = ChainIntel.move(read(11, 0), read(9, 40, atmCe = 110.0, atmPe = 100.0), today)
        assertTrue(s.contains("the 24,150 straddle (call 95 + put 88) costs 183, so option prices imply about ±183 points (±0.76%) by expiry on 7 Oct (2 days away) - roughly 23,977 to 24,343."), s)
        assertTrue(s.contains("At my 09:40 read the ATM straddle (24,150) cost 210."), s)
        assertTrue(s.endsWith("not a forecast: the market can and does move past it."), s)
        val ex = ChainIntel.move(read(11, 0).copy(expiry = today), null, today)
        assertTrue(ex.contains("by today's expiry"), ex)
        assertTrue(ChainIntel.move(read(11, 0, atmPe = 0.0).let { r -> r.copy(strikes = r.strikes.map { if (it.strike == 24_150.0) it.copy(pe = null) else it }) }, null, today)
            .contains("can't read the straddle"))
        val all = ChainIntel.answer(ChainIntel.Ask.ALL, read(11, 0), null, today)
        assertTrue(all.contains("call writing") && all.contains("dearer") && all.contains("straddle") && all.endsWith("not advice."), all)
    }

    @Test fun theBookKeepsTodaysReads() {
        val b = ChainIntel.Book(max = 3)
        b.keep(read(9, 20)); b.keep(read(9, 40)); b.keep(read(10, 0)); b.keep(read(10, 20))
        assertEquals(3, b.count("NIFTY"))
        assertEquals(today.atTime(9, 20), b.first("NIFTY", today)?.at)
        assertEquals(today.atTime(10, 20), b.latest("NIFTY")?.at)
        b.keep(read(10, 0))   // older than the newest: not kept
        assertEquals(today.atTime(10, 20), b.latest("NIFTY")?.at)
        // A new day starts afresh.
        val tomorrow = read(9, 30).let { it.copy(at = it.at.plusDays(1)) }
        b.keep(tomorrow)
        assertEquals(1, b.count("NIFTY"))
        assertNull(b.first("NIFTY", today))
    }

    @Test fun readFromTheChain() {
        val rows = (0..8).map { i ->
            val k = 24_000.0 + i * 50
            ChainRow(k, OptLeg("CE$i", 100.0 - i * 10, oi = 100_000L + i, volume = 5_000, prevOi = 100_000),
                OptLeg("PE$i", 10.0 + i * 10, oi = 90_000L + i, volume = 4_000, prevOi = 90_000))
        }
        val c = ChainSnapshot.of("NIFTY", expiry, 24_160.0, 75, rows, ZonedDateTime.of(2026, 10, 5, 11, 0, 0, 0, ZoneId.of("Asia/Kolkata")))
        val r = ChainIntel.read(c, LocalDateTime.of(2026, 10, 5, 11, 0))
        assertEquals(9, r.strikes.size)
        assertEquals(24_150.0, r.atm)
        val atm = r.strikes.first { it.strike == 24_150.0 }
        assertEquals(100_003L, atm.ceOi); assertEquals(100_000L, atm.ceStart); assertEquals(70.0, atm.ce); assertEquals(40.0, atm.pe)
        assertNotNull(ChainIntel.straddle(r))
    }

    @Test fun inTheCase() {
        val f = ChainIntel.caseFacts(read(11, 0), today)
        assertEquals(3, f.size, f.toString())
        assertTrue(f[0].startsWith("In the Nifty chain at 11:00, the 24,150 straddle costs 183: option prices imply about ±183 points by the 7 Oct expiry, a price, not a forecast."), f[0])
        assertEquals("The biggest call OI is at 24,300 and the biggest put OI at 24,000.", f[1])
        assertTrue(f[2].startsWith("Puts are dearer than calls by 4.0 IV points"), f[2])
        val n = TradeCheck.Now(true, true, 11 * 60, false, true, null, true, false, false, false, null, 10_000.0,
            14.0, 1.0, 0.3, 0.1, 0.05, 0.15, null, emptyList(), false, emptyList(), emptyList())
        val case = TradeCase.build(TradeCase.Input(n, today.atTime(11, 0), locked = true, chain = f)).say()
        assertTrue(case.contains("From the option chain: In the Nifty chain at 11:00"), case)
        assertTrue(case.endsWith(TradeCase.YOURS), case)
        assertFalse(Regex("(?i)\\b(you should|i recommend|buy now|sell now|go long|go short)\\b").containsMatchIn(case), case)
    }
}
