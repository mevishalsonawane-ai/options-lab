package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recorder's off-hours feeds ([RecorderFeeds]): NSE's participant-wise OI / volume files and NSE IX's GIFT Nifty
 * ticker parsed from real answers (fetched 06 Oct 2026 into test resources), when each is due, the alarm's next slot,
 * the gap throttle, the records, the card and Jarvis.
 */
class RecorderFeedsTest {
    private fun res(name: String) = javaClass.getResource("/recorder/$name")!!.readText()

    /** Weekdays trade; 2 Oct 2026 (Gandhi Jayanti, a Friday) is a holiday. */
    private val trading: (LocalDate) -> Boolean = { d -> d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && d != LocalDate.of(2026, 10, 2) }
    private val mon = LocalDate.of(2026, 10, 5)
    private val tue = LocalDate.of(2026, 10, 6)
    private fun at(d: LocalDate, h: Int, m: Int, s: Int = 0) = d.atTime(h, m, s)

    // ---- the sources ----------------------------------------------------------------------------------------------------

    @Test fun theSourcesAreNsesOwnPublicFiles() {
        assertEquals("https://nsearchives.nseindia.com/content/nsccl/fao_participant_oi_05102026.csv", RecorderFeeds.oiUrl(mon))
        assertEquals("https://nsearchives.nseindia.com/content/nsccl/fao_participant_vol_05102026.csv", RecorderFeeds.volUrl(mon))
        assertEquals("https://www.nseix.com/api/market-rate?type=derivative", RecorderFeeds.GIFT_URL)
        for (u in listOf(RecorderFeeds.oiUrl(mon), RecorderFeeds.volUrl(mon), RecorderFeeds.GIFT_URL)) {
            assertTrue(u.startsWith("https://"), u)
            assertFalse(Regex("(?i)token|key=|secret|password").containsMatchIn(u), u)
        }
    }

    // ---- participant-wise OI --------------------------------------------------------------------------------------------

    @Test fun theParticipantOiFileParsesFromNsesRealAnswer() {
        val p = RecorderFeeds.parseParticipants(res("fao_participant_oi_05102026.csv"))
        assertEquals(mon, p.date)
        assertEquals(listOf("Client", "DII", "FII", "Pro", "TOTAL"), p.rows.map { it.client })
        val fii = p.rows.single { it.client == "FII" }
        assertEquals(29117, fii.of("Future Index Long")); assertEquals(331508, fii.of("Future Index Short"))
        assertEquals(616526, fii.of("Option Index Call Long")); assertEquals(1175238, fii.of("Option Index Put Long"))
        assertEquals(982257, fii.of("Option Index Call Short")); assertEquals(586923, fii.of("Option Index Put Short"))
        assertEquals(3420425, fii.of("Future Stock Long")); assertEquals(2853437, fii.of("Future Stock Short"))
        assertEquals(5615965, fii.of("Total Long Contracts")); assertEquals(5096796, fii.of("Total Short Contracts"))
        assertEquals(29117L - 331508L, fii.futIdxNet)
        // The four participants add up to NSE's TOTAL in every column, to NSE's own rounding (its 5 Oct file is one
        // contract off in "Option Index Call Long"): the TOTAL row is kept as published, never recomputed.
        val total = p.rows.single { it.client == "TOTAL" }
        for (i in RecorderFeeds.COLUMNS.indices)
            assertTrue(kotlin.math.abs(total.figures[i] - p.rows.filter { it.client != "TOTAL" }.sumOf { it.figures[i] }) <= 1, RecorderFeeds.COLUMNS[i])
        assertEquals(6073080, total.of("Option Index Call Long"))
        val line = RecorderFeeds.participant(p, LocalDateTime.of(2026, 10, 5, 18, 31, 2, 5), "oi", fii)
        assertEquals("P,2026-10-05,2026-10-05T18:31:02,oi,FII,29117,331508,3420425,2853437,616526,1175238,982257,586923,129499,245160,237610,105061,5615965,5096796", line)
        assertEquals(19, MarketRecord.fields(line).size)
    }

    @Test fun theVolumeFileHasTheSameLayout() {
        val v = RecorderFeeds.parseParticipants(res("fao_participant_vol_05102026.csv"))
        assertEquals(mon, v.date)
        assertEquals(42734, v.rows.single { it.client == "Client" }.of("Future Index Long"))
        assertEquals(200466714, v.rows.single { it.client == "TOTAL" }.of("Total Short Contracts"))
    }

    @Test fun anythingElseIsRefusedNotMisread() {
        assertFailsWith<IllegalArgumentException> { RecorderFeeds.parseParticipants("<html><body>Resource not found</body></html>") }
        val good = res("fao_participant_oi_05102026.csv")
        assertFailsWith<IllegalArgumentException> { RecorderFeeds.parseParticipants(good.lines().take(4).joinToString("\n")) }   // cut off
        assertFailsWith<IllegalArgumentException> { RecorderFeeds.parseParticipants(good.replace("Future Index Long", "Something")) }
        assertFailsWith<IllegalArgumentException> { RecorderFeeds.parseParticipants(good.replace("Oct 05, 2026", "soon")) }
        assertFailsWith<IllegalArgumentException> { RecorderFeeds.parseParticipants(good.replace(",301687,", ",n/a,")) }
        // Columns found by name: a reordered file files every figure in its place; LF line ends and a BOM are fine.
        val l = good.split("\r\n").filter { it.isNotEmpty() }
        val swap: (String) -> String = { s -> MarketRecord.fields(s).let { f -> (listOf(f[0], f[2], f[1]) + f.drop(3)).joinToString(",") } }
        val reordered = "﻿" + (listOf(l[0]) + l.drop(1).map(swap)).joinToString("\n")
        val p = RecorderFeeds.parseParticipants(reordered)
        assertEquals(29117, p.rows.single { it.client == "FII" }.of("Future Index Long"))
        assertEquals(331508, p.rows.single { it.client == "FII" }.of("Future Index Short"))
    }

    // ---- GIFT Nifty -----------------------------------------------------------------------------------------------------

    @Test fun giftNiftyParsesFromNseIxsRealAnswer() {
        val g = assertNotNull(RecorderFeeds.parseGift(res("nseix_market_rate_derivative.json")))
        assertEquals("NIFTY", g.symbol)
        assertEquals(LocalDate.of(2026, 10, 27), g.expiry)
        assertEquals(22804.5, g.last); assertEquals(30.5, g.change); assertEquals(0.13, g.pct); assertEquals(56491L, g.contracts)
        assertEquals(LocalDateTime.of(2026, 10, 6, 22, 2, 1), g.at)
        assertEquals("I,23:30:04,NIFTY,2026-10-27,22804.5,30.5,0.13,56491,2026-10-06T22:02:01", RecorderFeeds.gift(LocalTime.of(23, 30, 4), g))
        assertEquals(9, MarketRecord.fields(RecorderFeeds.gift(LocalTime.NOON, g)).size)
    }

    @Test fun giftNiftyPicksTheNearMonthAndRefusesTheRest() {
        val two = """{"data":[{"INSTRUMENTTYPE":"FUTIDX","SYMBOL":"NIFTY","EXPIRYDATE":"24-NOV-2026","LASTPRICE":"22900.00","DAYCHANGE":"-1","PERCHANGE":"-0.01","CONTRACTSTRADED":12,"TIMESTMP":"06-Oct-2026 09:00:00"},
            {"INSTRUMENTTYPE":"FUTIDX","SYMBOL":"BANKNIFTY","EXPIRYDATE":"27-Oct-2026","LASTPRICE":"50000.00"},
            {"INSTRUMENTTYPE":"OPTIDX","SYMBOL":"NIFTY","EXPIRYDATE":"13-Oct-2026","LASTPRICE":"10.00"},
            {"INSTRUMENTTYPE":"FUTIDX","SYMBOL":"NIFTY","EXPIRYDATE":"27-Oct-2026","LASTPRICE":"22,850.25","DAYCHANGE":"-12.75","PERCHANGE":"-.06","CONTRACTSTRADED":40,"TIMESTMP":"06-Oct-2026 09:00:00"}]}"""
        val g = assertNotNull(RecorderFeeds.parseGift(two))
        assertEquals(LocalDate.of(2026, 10, 27), g.expiry); assertEquals(22850.25, g.last); assertEquals(-12.75, g.change); assertEquals(-0.06, g.pct)
        assertNull(RecorderFeeds.parseGift("""{"data":[]}"""))
        assertNull(RecorderFeeds.parseGift("<html>login</html>"))
        assertNull(RecorderFeeds.parseGift("""{"data":[{"INSTRUMENTTYPE":"FUTIDX","SYMBOL":"NIFTY","LASTPRICE":"0"}]}"""))
        val odd = assertNotNull(RecorderFeeds.parseGift("""{"data":[{"INSTRUMENTTYPE":"FUTIDX","SYMBOL":"NIFTY","LASTPRICE":"22800","TIMESTMP":"junk"}]}"""))
        assertNull(odd.expiry); assertNull(odd.at); assertNull(odd.change)
        assertEquals("I,07:00:00,NIFTY,,22800,,,,", RecorderFeeds.gift(LocalTime.of(7, 0), odd))
    }

    @Test fun theLastReadingIsKeptForTheCard() {
        val g = RecorderFeeds.parseGift(res("nseix_market_rate_derivative.json"))!!
        val read = LocalDateTime.of(2026, 10, 6, 23, 30, 4)
        val (r, back) = assertNotNull(RecorderFeeds.giftDecode(RecorderFeeds.giftEncode(g, read)))
        assertEquals(read, r); assertEquals(g.last, back.last); assertEquals(g.change, back.change); assertEquals(g.at, back.at); assertEquals(g.expiry, back.expiry)
        assertNull(RecorderFeeds.giftDecode(null)); assertNull(RecorderFeeds.giftDecode("x|y")); assertNull(RecorderFeeds.giftDecode("junk|1|2|3|4"))
        assertNull(RecorderFeeds.giftDecode("2026-10-06T23:30:04|x||| "))
        assertEquals("22804.5 (+30.5) at 22:02:01 today", RecorderFeeds.giftText(g, read, tue))
        assertEquals("22800 (-4) at 07:00:00 on 6 Oct 2026", RecorderFeeds.giftText(g.copy(last = 22800.0, change = -4.0, at = null), at(tue, 7, 0), tue.plusDays(1)))
        assertEquals("22800 at 07:00:00 today", RecorderFeeds.giftText(g.copy(last = 22800.0, change = null, at = null), at(tue, 7, 0), tue))
    }

    // ---- when each is due -----------------------------------------------------------------------------------------------

    @Test fun giftNiftyEvery15MinutesBeforeTheOpenEvery5InHoursOnceAt2330() {
        assertFalse(RecorderFeeds.giftDue(true, at(tue, 6, 29, 59), null))
        assertTrue(RecorderFeeds.giftDue(true, at(tue, 6, 30), null))
        assertFalse(RecorderFeeds.giftDue(true, at(tue, 6, 40), at(tue, 6, 30)))
        assertTrue(RecorderFeeds.giftDue(true, at(tue, 6, 44, 35), at(tue, 6, 30)), "an alarm a little early still counts")
        assertTrue(RecorderFeeds.giftDue(true, at(tue, 9, 10), at(tue, 8, 55)))
        assertFalse(RecorderFeeds.giftDue(true, at(tue, 9, 18), at(tue, 9, 15)))
        assertTrue(RecorderFeeds.giftDue(true, at(tue, 9, 20), at(tue, 9, 15)))
        assertTrue(RecorderFeeds.giftDue(true, at(tue, 15, 30), at(tue, 15, 25)))
        assertFalse(RecorderFeeds.giftDue(true, at(tue, 15, 31), null), "after the close: nothing until the night reading")
        assertFalse(RecorderFeeds.giftDue(true, at(tue, 20, 0), null))
        assertTrue(RecorderFeeds.giftDue(true, at(tue, 23, 30), at(tue, 15, 30)))
        assertTrue(RecorderFeeds.giftDue(true, at(tue, 23, 16), null))
        assertFalse(RecorderFeeds.giftDue(true, at(tue, 23, 45), at(tue, 23, 30)), "the night reading once")
        assertFalse(RecorderFeeds.giftDue(false, at(LocalDate.of(2026, 10, 3), 7, 0), null), "never on a closed day")
        assertTrue(RecorderFeeds.giftDue(true, at(tue, 10, 0), at(tue, 11, 0)), "a clock set back does not stop it")
    }

    @Test fun theParticipantFileIsReadFrom1830UntilTheNextSessionOpens() {
        assertNull(RecorderFeeds.oiTradeDate(at(tue, 18, 29), trading), "Monday's was due by 09:00, Tuesday's not yet")
        assertEquals(tue, RecorderFeeds.oiTradeDate(at(tue, 18, 30), trading))
        assertEquals(tue, RecorderFeeds.oiTradeDate(at(tue, 23, 59), trading))
        assertEquals(tue, RecorderFeeds.oiTradeDate(at(tue.plusDays(1), 8, 59), trading), "the next morning before 09:00")
        assertNull(RecorderFeeds.oiTradeDate(at(tue.plusDays(1), 9, 0), trading))
        assertNull(RecorderFeeds.oiTradeDate(at(tue, 12, 0), trading))
        // Thursday 1 Oct's file: through the holiday and the weekend, until Monday 09:00.
        val thu = LocalDate.of(2026, 10, 1)
        assertEquals(thu, RecorderFeeds.oiTradeDate(at(LocalDate.of(2026, 10, 3), 8, 0), trading))
        assertEquals(thu, RecorderFeeds.oiTradeDate(at(mon, 6, 30), trading))
        assertNull(RecorderFeeds.oiTradeDate(at(mon, 9, 0), trading))
        assertNull(RecorderFeeds.oiTradeDate(at(tue, 18, 0)) { false }, "no trading day found")
        // Due: pending and not tried in the last 55 minutes; recorded: nothing.
        assertEquals(tue, RecorderFeeds.oiDue(at(tue, 18, 30), trading, mon, null))
        assertNull(RecorderFeeds.oiDue(at(tue, 19, 0), trading, mon, at(tue, 18, 30)))
        assertEquals(tue, RecorderFeeds.oiDue(at(tue, 19, 30), trading, mon, at(tue, 18, 30)))
        assertNull(RecorderFeeds.oiDue(at(tue, 19, 30), trading, tue, at(tue, 18, 30)), "already recorded")
        assertEquals(tue, RecorderFeeds.oiDue(at(tue, 18, 30), trading, null, null), "the first ever")
    }

    @Test fun aFileNeverReadIsOneGapAtTheNextOpen() {
        val wed = tue.plusDays(1)
        val tried = at(wed, 8, 30)
        assertNull(RecorderFeeds.oiMissed(at(wed, 8, 59), trading, mon, tried, null), "still being tried")
        assertEquals(tue, RecorderFeeds.oiMissed(at(wed, 9, 0), trading, mon, tried, null))
        assertEquals(tue, RecorderFeeds.oiMissed(at(wed, 9, 0), trading, mon, at(tue, 18, 30), null))
        assertNull(RecorderFeeds.oiMissed(at(wed, 9, 1), trading, mon, tried, tue), "its gap written once")
        assertNull(RecorderFeeds.oiMissed(at(wed, 9, 0), trading, tue, tried, null), "it was read")
        assertNull(RecorderFeeds.oiMissed(at(wed, 18, 30), trading, mon, at(wed, 18, 30), null), "this evening's try is for today's file")
        assertNull(RecorderFeeds.oiMissed(at(wed, 9, 0), trading, mon, null, null), "never tried (the phone was off): no gap invented")
        assertNull(RecorderFeeds.oiMissed(at(wed, 9, 0), trading, mon, at(tue, 18, 29), null), "a try before it was due is not one")
        val thu = LocalDate.of(2026, 10, 1)
        assertNull(RecorderFeeds.oiMissed(at(LocalDate.of(2026, 10, 3), 10, 0), trading, null, at(thu, 19, 0), null), "a closed day waits for the session")
        assertEquals(thu, RecorderFeeds.oiMissed(at(mon, 9, 30), trading, null, at(thu, 19, 0), null))
        assertNull(RecorderFeeds.oiMissed(at(mon, 9, 30), { it == mon }, null, tried, null), "no trading day before")
    }

    @Test fun theOffHoursAlarmFiresOnlyAtItsSlots() {
        assertEquals(at(tue, 6, 30), RecorderFeeds.nextSlot(at(tue, 2, 0), trading, false))
        assertEquals(at(tue, 6, 45), RecorderFeeds.nextSlot(at(tue, 6, 30), trading, false))
        assertEquals(at(tue, 9, 0), RecorderFeeds.nextSlot(at(tue, 8, 50), trading, false))
        assertEquals(at(tue, 18, 30), RecorderFeeds.nextSlot(at(tue, 9, 0), trading, false), "market hours are the watch's")
        assertEquals(at(tue, 19, 30), RecorderFeeds.nextSlot(at(tue, 18, 30), trading, true), "missing: the evening's retries")
        assertEquals(at(tue, 23, 30), RecorderFeeds.nextSlot(at(tue, 18, 30), trading, false), "read: straight to the night reading")
        assertEquals(at(tue, 23, 30), RecorderFeeds.nextSlot(at(tue, 22, 30), trading, true))
        assertEquals(at(tue.plusDays(1), 6, 30), RecorderFeeds.nextSlot(at(tue, 23, 30), trading, true))
        // Friday 9 Oct night -> Saturday 08:00 only while Friday's file is missing, else Monday 06:30.
        val fri = LocalDate.of(2026, 10, 9)
        assertEquals(at(fri.plusDays(1), 8, 0), RecorderFeeds.nextSlot(at(fri, 23, 30), trading, true))
        assertEquals(at(fri.plusDays(3), 6, 30), RecorderFeeds.nextSlot(at(fri, 23, 30), trading, false))
        // A long closed stretch: tomorrow 06:30, to look again.
        assertEquals(tue.plusDays(1).atTime(6, 30), RecorderFeeds.nextSlot(at(tue, 7, 0), { false }, false))
        // A day has 11 morning slots (06:30-09:00), 18:30 and 23:30: 13 wakes, 17 on a day its file comes late.
        var n = 0; var t = at(tue, 0, 0)
        while (true) { t = RecorderFeeds.nextSlot(t, trading, false); if (t.toLocalDate() != tue) break; n++ }
        assertEquals(13, n)
    }

    @Test fun aBrokenFeedIsOneGapAnHour() {
        assertTrue(RecorderFeeds.gapAllowed(null, at(tue, 10, 0)))
        assertFalse(RecorderFeeds.gapAllowed(at(tue, 10, 0), at(tue, 10, 55)))
        assertTrue(RecorderFeeds.gapAllowed(at(tue, 10, 0), at(tue, 11, 0)))
    }

    // ---- the card, Jarvis and the README -----------------------------------------------------------------------------

    private val status = MarketRecord.Status(true, 3, 12_400_000, LocalDate.of(2026, 10, 1), tue, LocalDateTime.of(2026, 10, 6, 14, 32, 5), 0, 0, false)

    @Test fun theCardAndJarvisSayTheLastParticipantDateAndGiftReading() {
        val g = RecorderFeeds.parseGift(res("nseix_market_rate_derivative.json"))!!
        val s = status.copy(participants = mon, gift = LocalDateTime.of(2026, 10, 6, 23, 30, 4) to g)
        val c = MarketRecord.card(s, tue).toMap()
        assertEquals("5 Oct 2026", c["Participant OI"])
        assertEquals("22804.5 (+30.5) at 22:02:01 today", c["GIFT Nifty"])
        val none = MarketRecord.card(status, tue).toMap()
        assertEquals("none yet", none["Participant OI"]); assertEquals("none yet", none["GIFT Nifty"])
        val a = MarketRecord.answer(s, tue)
        assertTrue("NSE participant-wise OI recorded up to 5 Oct 2026." in a, a)
        assertTrue("Last GIFT Nifty reading 22804.5 (+30.5) at 22:02:01 today." in a, a)
        val b = MarketRecord.answer(status, tue)
        assertTrue("No NSE participant-wise OI recorded yet." in b && "No GIFT Nifty reading yet." in b, b)
        assertFalse(Regex("(?i)\\b(buy|sell|close|stop)\\b").containsMatchIn(a))
        assertTrue(MarketRecord.README.contains("P,tradeDate,fetched,file,client,futIdxLong"))
        assertTrue(MarketRecord.README.contains("I,time,symbol,expiry,last,change"))
        assertTrue(MarketRecord.README.trimEnd().endsWith("Times are IST. Blank = not available."))
    }

    @Test fun noRecordCarriesASecret() {
        val p = RecorderFeeds.parseParticipants(res("fao_participant_oi_05102026.csv"))
        val g = RecorderFeeds.parseGift(res("nseix_market_rate_derivative.json"))!!
        val lines = p.rows.map { RecorderFeeds.participant(p, at(tue, 18, 31), "oi", it) } + RecorderFeeds.gift(LocalTime.NOON, g)
        for (l in lines) assertEquals(l, MarketRecord.scrub(l, listOf("SESSIONtoken0123456789", "apikey987654")), "nothing credential-shaped in $l")
    }
}
