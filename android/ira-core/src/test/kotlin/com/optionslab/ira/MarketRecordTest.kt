package com.optionslab.ira

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The market recorder's pure parts ([MarketRecord]): formats, the window, frames, rotation, no secrets, IV, Jarvis. */
class MarketRecordTest {
    private val day = LocalDate.of(2026, 10, 6)
    private val t = LocalTime.of(10, 15, 7)

    // ---- the window -----------------------------------------------------------------------------------------------------

    @Test fun recordsOnlyOnTradingDaysFromNineToFifteenThirtyFive() {
        assertTrue(MarketRecord.inWindow(true, LocalTime.of(9, 0)))
        assertTrue(MarketRecord.inWindow(true, LocalTime.of(12, 0)))
        assertTrue(MarketRecord.inWindow(true, LocalTime.of(15, 35)))
        assertFalse(MarketRecord.inWindow(true, LocalTime.of(8, 59, 59)))
        assertFalse(MarketRecord.inWindow(true, LocalTime.of(15, 35, 1)))
        assertFalse(MarketRecord.inWindow(false, LocalTime.of(12, 0)), "a holiday or a weekend: nothing")
    }

    // ---- the formats ----------------------------------------------------------------------------------------------------

    @Test fun numbersAndTextFieldsAreShortAndQuotedOnlyWhenNeeded() {
        assertEquals("", MarketRecord.num(null))
        assertEquals("", MarketRecord.num(Double.NaN))
        assertEquals("25000", MarketRecord.num(25_000.0))
        assertEquals("212.55", MarketRecord.num(212.55))
        assertEquals("0.1", MarketRecord.num(0.1000001))
        assertEquals("-3.5", MarketRecord.num(-3.5))
        assertEquals("plain", MarketRecord.esc("plain"))
        assertEquals("\"a, b\"", MarketRecord.esc("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", MarketRecord.esc("say \"hi\""))
        assertEquals("one two", MarketRecord.esc("one\ntwo"))
        assertEquals(listOf("N", "a, b", "say \"hi\"", ""), MarketRecord.fields("N,\"a, b\",\"say \"\"hi\"\"\","))
    }

    @Test fun eachRecordKindHasItsFieldsInOrder() {
        assertEquals("H,v1,2026-10-06", MarketRecord.header(day))
        val n = MarketRecord.News(t, "RBI", Instant.parse("2026-10-06T04:40:00Z"), "RBI keeps repo rate at 5.5%, says growth \"robust\"",
            "https://rbi.example/1", -0.25, "negative", listOf("NIFTY", "BANKNIFTY"), true, listOf("Livemint"))
        val news = MarketRecord.news(n)
        assertEquals("N,10:15:07,RBI,2026-10-06T10:10,-0.25,negative,NIFTY;BANKNIFTY,1,Livemint,\"RBI keeps repo rate at 5.5%, says growth \"\"robust\"\"\",https://rbi.example/1", news)
        assertEquals(11, MarketRecord.fields(news).size)
        assertEquals("https://rbi.example/1", MarketRecord.newsKey(news))
        assertEquals("Only words", MarketRecord.newsKey(MarketRecord.news(n.copy(link = "", title = "Only words"))))
        assertNull(MarketRecord.newsKey("S,10:15:07,NIFTY,25000"))
        assertNull(MarketRecord.newsKey("N,short"))
        assertEquals("E,2026-10-07,,NIFTY expiry,0", MarketRecord.event(MarketRecord.Event(day.plusDays(1), null, "NIFTY expiry", false)))
        assertEquals("E,2026-10-08,10:00,RBI policy,1", MarketRecord.event(MarketRecord.Event(day.plusDays(2), LocalTime.of(10, 0), "RBI policy", true)))
        assertEquals("X,05-Oct-2026,FII,12345.6,13000,-654.4", MarketRecord.flow("05-Oct-2026", "FII", 12_345.6, 13_000.0, -654.4))
        assertEquals("S,10:15:07,NIFTY,25012.35", MarketRecord.spot(t, "NIFTY", 25_012.35))
        val fq = MarketRecord.Quote(25_080.0, 1_234_500, 9_870_000, 25_079.5, 75, 25_080.5, 150, 400_000, 380_000)
        assertEquals("F,10:15:07,NIFTY,NIFTY26OCTFUT,2026-10-27,25080,1234500,9870000,25012.35,67.65",
            MarketRecord.future(t, "NIFTY", "NIFTY26OCTFUT", LocalDate.of(2026, 10, 27), fq, 25_012.35))
        assertEquals("F,10:15:07,NIFTY,NIFTY26OCTFUT,2026-10-27,25080,1234500,9870000,,",
            MarketRecord.future(t, "NIFTY", "NIFTY26OCTFUT", LocalDate.of(2026, 10, 27), fq, null), "no index level: no basis")
        assertEquals("D,10:15:07,NIFTY26OCTFUT,25080,25079.5,75,25080.5,150,400000,380000,1,r", MarketRecord.depth(t, "NIFTY26OCTFUT", fq))
        assertEquals("D,10:15:07,X,10,,,,,,,,s", MarketRecord.depth(t, "X", MarketRecord.Quote(10.0, stream = true)))
        assertEquals("B,09:15,NIFTY,NIFTY26OCTFUT,25000,25010,24990,25005,1200,9800000",
            MarketRecord.bar(LocalTime.of(9, 15), "NIFTY", "NIFTY26OCTFUT", 25_000.0, 25_010.0, 24_990.0, 25_005.0, 1_200, 9_800_000))
        val oq = MarketRecord.Quote(3.2, 5_000_000, 2_000_000, 3.1, 1_500, 3.3, 900)
        assertEquals("C,10:15,NIFTY,2026-10-07,25500,CE,3.2,5000000,2000000,18.25,3.1,3.3",
            MarketRecord.chain(t, "NIFTY", day.plusDays(1), 25_500.0, "CE", oq, 0.1825))
        assertEquals("C,10:15,NIFTY,2026-10-07,25500,CE,3.2,5000000,2000000,,3.1,3.3",
            MarketRecord.chain(t, "NIFTY", day.plusDays(1), 25_500.0, "CE", oq, null))
        assertEquals("L,10:15,NIFTY,2026-10-07,25500,CE,3.2,3.1,3.3,1500,900", MarketRecord.cheap(t, "NIFTY", day.plusDays(1), 25_500.0, "CE", oq))
        assertEquals("G,10:15:07,quotes,SocketTimeoutException", MarketRecord.gap(t, "quotes", MarketRecord.why(java.net.SocketTimeoutException("https://x?access_token=abc"))))
        assertTrue(MarketRecord.isCheap(1.0) && MarketRecord.isCheap(5.0) && !MarketRecord.isCheap(0.95) && !MarketRecord.isCheap(5.05))
        assertNull(MarketRecord.Quote(10.0, bid = 0.0, ask = 10.5).spread)
        assertNull(MarketRecord.basis(25_000.0, 0.0))
    }

    @Test fun theStrikesAroundTheMoneyAndTheForward() {
        val strikes = (240..260).map { it * 100.0 } + 24_000.0
        assertEquals(listOf(24_900.0, 25_000.0, 25_100.0), MarketRecord.around(strikes, 25_020.0, 1))
        assertEquals(31, MarketRecord.around(strikes.filter { it >= 24_000 } + (200..300).map { it * 100.0 }, 25_000.0, 15).size)
        assertEquals(listOf(24_000.0, 24_100.0, 24_200.0), MarketRecord.around(strikes, 10.0, 2), "clipped at the list's end")
        assertEquals(emptyList(), MarketRecord.around(emptyList(), 25_000.0, 2))
        assertEquals(25_000.0, MarketRecord.atm(strikes, 25_020.0))
        assertNull(MarketRecord.atm(emptyList(), 1.0))
        assertEquals(25_030.0, MarketRecord.forward(25_000.0, 130.0, 100.0))
        assertNull(MarketRecord.forward(25_000.0, null, 100.0))
        assertNull(MarketRecord.forward(25_000.0, 130.0, 0.0))
    }

    @Test fun impliedVolatilityFromTheChainsOwnForward() {
        val now = LocalDateTime.of(2026, 10, 6, 10, 15)
        val iv = MarketRecord.iv(130.0, true, 25_030.0, 25_000.0, now, LocalDate.of(2026, 10, 13))
        assertNotNull(iv)
        assertTrue(iv in 0.05..0.40, "$iv")
        assertNull(MarketRecord.iv(130.0, true, null, 25_000.0, now, LocalDate.of(2026, 10, 13)), "no forward")
        assertNull(MarketRecord.iv(0.0, true, 25_030.0, 25_000.0, now, LocalDate.of(2026, 10, 13)), "no price")
        assertNull(MarketRecord.iv(130.0, true, 25_030.0, 25_000.0, LocalDateTime.of(2026, 10, 6, 15, 30), day), "at the expiry")
    }

    // ---- no secrets -----------------------------------------------------------------------------------------------------

    @Test fun noRecordCarriesATokenAKeyOrACredentialShape() {
        val token = "Zx9TOKENvalue123456"
        val key = "kiteapikey42"
        val line = MarketRecord.news(MarketRecord.News(t, "Feed", null, "leak $token and $key", "https://h/x?access_token=$token&api_key=$key",
            0.0, "neutral", emptyList(), false))
        val clean = MarketRecord.scrub(line, listOf(token, key, null, "abc"))
        assertFalse(token in clean); assertFalse(key in clean)
        assertFalse(Regex("(?i)access_token=[^*]").containsMatchIn(clean), clean)
        assertEquals("S,10:15:07,NIFTY,25000", MarketRecord.scrub("S,10:15:07,NIFTY,25000", listOf(token)), "plain records unchanged")
        assertEquals("G,1,***", MarketRecord.scrub("G,1,Authorization: token abc:def", emptyList()))
        assertEquals("N,***,x", MarketRecord.scrub("N,Bearer abcdefghijklmnopqrstu,x", emptyList()))
        // A headline merely naming them keeps its words: "SEBI authorization for brokers" is news, not a credential.
        assertEquals("N,SEBI authorization for brokers", MarketRecord.scrub("N,SEBI authorization for brokers", emptyList()))
        // A short value (under 6 characters) is not a secret: "abc" in a headline stays.
        assertTrue("abc" in MarketRecord.scrub("N,abc", listOf("abc")))
    }

    // ---- the encrypted frames -------------------------------------------------------------------------------------------

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private fun seal(b: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(b)
    }
    private fun open(b: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b, 0, 12))
        return c.doFinal(b, 12, b.size - 12)
    }

    @Test fun framesAppendSealedAndReadBackInOrderATornTailIsSkipped() {
        val a = MarketRecord.frame(listOf("H,v1,2026-10-06", "S,09:15:01,NIFTY,25000"), ::seal)
        val b = MarketRecord.frame(listOf("S,09:16:01,NIFTY,25010"), ::seal)
        val file = a + b
        assertFalse(String(file, Charsets.ISO_8859_1).contains("NIFTY"), "nothing readable at rest")
        assertEquals(MarketRecord.Read(listOf("H,v1,2026-10-06", "S,09:15:01,NIFTY,25000", "S,09:16:01,NIFTY,25010"), 0), MarketRecord.read(file, ::open))
        // A power cut mid-append: the torn frame is counted, the ones before it kept.
        val torn = file + b.copyOf(b.size - 5)
        assertEquals(3, MarketRecord.read(torn, ::open).lines.size)
        assertEquals(1, MarketRecord.read(torn, ::open).bad)
        assertEquals(1, MarketRecord.read(file + byteArrayOf(0, 0), ::open).bad, "under a length's 4 bytes")
        // A frame that fails authentication (flipped bit) is skipped; the next one still reads.
        val flipped = a.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val r = MarketRecord.read(flipped + b, ::open)
        assertEquals(listOf("S,09:16:01,NIFTY,25010"), r.lines); assertEquals(1, r.bad)
        assertEquals(1, MarketRecord.read(byteArrayOf(0, 0, 0, 0), ::open).bad, "a zero length is a broken file")
        assertEquals(MarketRecord.Read(emptyList(), 0), MarketRecord.read(ByteArray(0), ::open))
    }

    @Test fun aMinuteOfRecordsPacksSmall() {
        // About a minute's lines with a chain snapshot: what the per-day estimate rests on.
        val q = MarketRecord.Quote(123.45, 1_234_567, 2_345_678, 123.4, 750, 123.5, 1_500, 120_000, 110_000)
        val lines = (0 until 124).map { MarketRecord.chain(t, "NIFTY", day, 25_000.0 + 50 * it, if (it % 2 == 0) "CE" else "PE", q, 0.1234) } +
            (0 until 28).map { MarketRecord.depth(t, "NIFTY26OCT25000CE", q) }
        val raw = lines.joinToString("\n").length
        val packed = MarketRecord.frame(lines) { it }.size
        assertTrue(packed < raw / 3, "$packed of $raw")
    }

    // ---- rotation and the cap -------------------------------------------------------------------------------------------

    @Test fun twelveMonthsAreKeptAndTheCapDropsTheOldestNeverToday() {
        val today = LocalDate.of(2026, 10, 6)
        val files = listOf(MarketRecord.DayFile(LocalDate.of(2025, 10, 3), 10), MarketRecord.DayFile(LocalDate.of(2025, 10, 6), 10),
            MarketRecord.DayFile(LocalDate.of(2026, 5, 4), 50), MarketRecord.DayFile(LocalDate.of(2026, 10, 5), 50), MarketRecord.DayFile(today, 70))
        val p = MarketRecord.rotate(files, today, cap = 1_000, budget = 150)
        assertEquals(listOf(LocalDate.of(2025, 10, 3)), p.delete, "older than 12 months")
        assertEquals(180, p.keptBytes)
        assertTrue(p.overBudget)
        val capped = MarketRecord.rotate(files, today, cap = 120, budget = 100)
        assertEquals(listOf(LocalDate.of(2025, 10, 3), LocalDate.of(2025, 10, 6), LocalDate.of(2026, 5, 4)), capped.delete)
        assertEquals(120, capped.keptBytes)
        // Today's alone over the cap: kept (it is being written).
        val alone = MarketRecord.rotate(listOf(MarketRecord.DayFile(today, 5_000)), today, cap = 100, budget = 50)
        assertEquals(emptyList(), alone.delete)
        assertFalse(MarketRecord.rotate(emptyList(), today).overBudget)
    }

    @Test fun gapCountsRoundTripAndKeepTheNewestDays() {
        val m = mapOf(LocalDate.of(2026, 10, 5) to 2, LocalDate.of(2026, 10, 6) to 0)
        assertEquals("2026-10-05=2;2026-10-06=0", MarketRecord.gapsEncode(m))
        assertEquals(m, MarketRecord.gapsDecode("2026-10-05=2;2026-10-06=0"))
        assertEquals(mapOf(LocalDate.of(2026, 10, 6) to 0), MarketRecord.gapsDecode(MarketRecord.gapsEncode(m, keep = 1)))
        assertEquals(emptyMap(), MarketRecord.gapsDecode(null))
        assertEquals(mapOf(LocalDate.of(2026, 10, 5) to 1), MarketRecord.gapsDecode("junk;2026-10-05=1;2026-10-07=x"))
    }

    // ---- the card and Jarvis --------------------------------------------------------------------------------------------

    private val status = MarketRecord.Status(true, 3, 12_400_000, LocalDate.of(2026, 10, 1), day, LocalDateTime.of(2026, 10, 6, 14, 32, 5), 1, 4, false)

    @Test fun theCardSaysStorageDaysTheLastWriteAndGaps() {
        val c = MarketRecord.card(status, day).toMap()
        assertEquals("12.4 MB", c["Storage used"])
        assertEquals("3 (since 1 Oct 2026)", c["Days recorded"])
        assertEquals("14:32:05 today", c["Last write"])
        assertEquals("1 today, 4 in all", c["Gaps"])
        val none = MarketRecord.card(MarketRecord.Status(true, 0, 0, null, null, null, 0, 0, false), day).toMap()
        assertEquals("none yet", none["Days recorded"]); assertEquals("none yet", none["Last write"]); assertEquals("0 bytes", none["Storage used"])
        assertTrue(MarketRecord.card(status.copy(bytes = 1_600_000_000, overBudget = true), day).toMap().getValue("Storage used").contains("over the"))
        assertEquals("15:34:58 on 5 Oct 2026", MarketRecord.whenText(LocalDateTime.of(2026, 10, 5, 15, 34, 58), day))
        assertEquals("2 KB", MarketRecord.size(2_000))
        assertEquals("1.60 GB", MarketRecord.size(1_600_000_000))
    }

    @Test fun jarvisIsAskedHowMuchIsRecordedAndNothingElse() {
        for (q in listOf("how much market data have we recorded", "is the market recorder running", "market recorder status",
            "how many days of market data have you recorded", "kitna market data record hua hai", "How much data have you recorded?",
            "how much space does the recorded market data take", "market data kitne din ka record hua hai"))
            assertTrue(MarketRecord.asked(q), q)
        for (q in listOf("delete the recorded market data", "export the recorded market data", "turn off the market recorder",
            "is my voice being recorded", "what was the record high of nifty", "is your data fresh", "how much data do you have",
            "record my trade", "market recorder", "what's the gap record"))
            assertFalse(MarketRecord.asked(q), q)
    }

    @Test fun jarvisAnswersFromTheStatus() {
        val a = MarketRecord.answer(status, day)
        assertTrue(a.startsWith("3 trading days of market data recorded since 1 Oct 2026, 12.4 MB on this phone, last written at 14:32:05 today."), a)
        assertTrue("4 gaps in all, 1 today" in a, a)
        assertTrue("Export it from More, Data & Harvest" in a)
        assertTrue("No gaps so far." in MarketRecord.answer(status.copy(gapsToday = 0, gapsTotal = 0), day))
        assertTrue("1 gap in all, none today." in MarketRecord.answer(status.copy(gapsToday = 0, gapsTotal = 1), day))
        assertTrue("over its" in MarketRecord.answer(status.copy(overBudget = true), day))
        assertTrue("switched off now" in MarketRecord.answer(status.copy(on = false), day))
        assertTrue(MarketRecord.answer(status.copy(days = 1, first = null, lastWrite = null), day).startsWith("1 trading day of market data recorded, 12.4 MB on this phone."))
        assertTrue(MarketRecord.answer(status.copy(days = 0), day).startsWith("Nothing recorded yet"))
        assertTrue(MarketRecord.answer(status.copy(days = 0, on = false), day).startsWith("The market recorder is off"))
        // His own words: never an order, a stop or a buy/sell said back.
        assertFalse(Regex("(?i)\\b(buy|sell|close|stop)\\b").containsMatchIn(a))
        assertTrue(MarketRecord.README.lines().size > 10)
    }
}
