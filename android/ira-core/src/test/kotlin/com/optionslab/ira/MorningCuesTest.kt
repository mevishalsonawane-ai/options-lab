package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The morning cues ([MorningCues]) on the recorder's real sample files (test resources, fetched 06 Oct 2026): NSE's
 * participant-wise OI for 05 Oct and NSE IX's GIFT Nifty answer - the gap against Nifty's previous close, the FIIs'
 * positioning against the day before, missing data, the brief's lines and the question.
 */
class MorningCuesTest {
    private fun res(name: String) = javaClass.getResource("/recorder/$name")!!.readText()
    private val mon = LocalDate.of(2026, 10, 5)
    private val tue = LocalDate.of(2026, 10, 6)
    private val wedMorning = LocalDateTime.of(2026, 10, 7, 8, 50)

    /** A whole session of 1-minute candles on [d], flat at [close]. */
    private fun session(d: LocalDate, close: Double): List<Candle> {
        val out = ArrayList<Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(LocalTime.of(15, 30))) { out += Candle(d.atTime(t), close, close, close, close); t = t.plusMinutes(1) }
        return out
    }

    private val NO_TRADE = Regex("(?i)\\b(buy|sell)\\b")

    // ---- GIFT Nifty ---------------------------------------------------------------------------------------------------

    @Test fun giftNiftyPointsToAGapFromNiftysPreviousClose() {
        val g = RecorderFeeds.parseGift(res("nseix_market_rate_derivative.json"))!!
        assertEquals(22804.5, g.last)
        val bars = session(mon, 22700.0) + session(tue, 22744.3)
        val prev = MorningCues.prevClose(bars, g.at!!)
        assertEquals(MorningCues.Close(tue, 22744.3), prev)
        val said = MorningCues.gift(g, g.at, prev, wedMorning)
        assertEquals("GIFT Nifty points to a gap-up of ~60 pts (+0.26%), read at 22:02 yesterday.", said[0])
        assertTrue(said[1].startsWith("That is 22,804.5 against Nifty's 22,744.3 close on 6 Oct."), said[1])
        assertEquals(MorningCues.GIFT_NOTE, said.last())
        assertFalse(said.any { NO_TRADE.containsMatchIn(it) })
        assertEquals("GIFT Nifty points to a gap-up of ~60 pts (+0.26%), read at 22:02 yesterday", MorningCues.giftBrief(g, g.at, prev, wedMorning))
        // A morning reading before the session: the close it is set against is the day before's.
        val morning = g.copy(last = 22650.0, at = tue.atTime(8, 45))
        val prevMon = MorningCues.prevClose(bars, morning.at!!)!!
        assertEquals(mon, prevMon.day)
        assertEquals("GIFT Nifty points to a gap-down of ~50 pts (-0.22%), read at 08:45 today.",
            MorningCues.gift(morning, morning.at, prevMon, tue.atTime(8, 50))[0])
        // Within 0.1%: roughly flat.
        val flat = g.copy(last = 22760.0)
        assertTrue(MorningCues.gift(flat, flat.at, prev, wedMorning)[0].startsWith("GIFT Nifty points to a roughly flat open (+16 pts, +0.07%)"))
    }

    @Test fun giftMissingOrOldIsSaid() {
        assertEquals(listOf(MorningCues.NO_GIFT), MorningCues.gift(null, null, null, wedMorning))
        val g = RecorderFeeds.parseGift(res("nseix_market_rate_derivative.json"))!!
        val noClose = MorningCues.gift(g, g.at, null, wedMorning)
        assertTrue(noClose[0].contains("I don't have Nifty's previous close"), noClose[0])
        assertNull(MorningCues.giftBrief(g, g.at, null, wedMorning))
        assertNull(MorningCues.prevClose(emptyList(), g.at!!))
        // Two days later the same reading is old: said so, left out of the brief.
        val later = wedMorning.plusDays(2)
        val prev = MorningCues.Close(tue, 22744.3)
        assertTrue(MorningCues.gift(g, g.at, prev, later)[1].contains("That reading is old"))
        assertNull(MorningCues.giftBrief(g, g.at, prev, later))
    }

    // ---- FII positioning ----------------------------------------------------------------------------------------------

    @Test fun fiiPositioningFromNsesRealFile() {
        val p = RecorderFeeds.parseParticipants(res("fao_participant_oi_05102026.csv"))
        val f = MorningCues.fii(p)!!
        assertEquals(29117L, f.futLong); assertEquals(331508L, f.futShort)
        val alone = MorningCues.fiiSay(f, null)
        assertEquals("FIIs are net short index futures, 8% long.", alone[0])
        assertTrue(alone[1].contains("29,117 long against 331,508 short contracts in NSE's participant OI for 5 Oct (no earlier day"), alone[1])
        assertEquals("In index options they are net short 365,731 calls and net long 588,315 puts - both lean bearish.", alone[2])
        assertFalse(alone.any { NO_TRADE.containsMatchIn(it) })
        // Against the session before: more short (12% long then), less short (5% long then), about the same.
        val before = f.copy(date = LocalDate.of(2026, 10, 1), futLong = 12, futShort = 88)
        assertEquals("FIIs are net short index futures, 8% long; more short than the session before (1 Oct: 12% long).", MorningCues.fiiSay(f, before)[0])
        assertTrue(MorningCues.fiiSay(f, before.copy(futLong = 5, futShort = 95))[0].contains("less short than"))
        assertTrue(MorningCues.fiiSay(f, before.copy(futLong = 8, futShort = 92))[0].contains("about the same as"))
        // Net long: more long / less long.
        val long = f.copy(futLong = 70, futShort = 30)
        assertTrue(MorningCues.fiiSay(long, before.copy(futLong = 60, futShort = 40))[0].startsWith("FIIs are net long index futures, 70% long; more long than"))
        assertEquals("FIIs are net short index futures, 8% long; more short than the session before (1 Oct: 12% long) (NSE participant OI, 5 Oct)",
            MorningCues.fiiBrief(f, before, tue))
    }

    @Test fun fiiFromTheRecordersOwnRows() {
        val p = RecorderFeeds.parseParticipants(res("fao_participant_oi_05102026.csv"))
        val fetched = LocalDateTime.of(2026, 10, 5, 18, 31)
        val rows = p.rows.map { RecorderFeeds.participant(p, fetched, "oi", it) } +
            p.rows.map { RecorderFeeds.participant(p, fetched, "vol", it) } + listOf("N,09:00:00,x", "P,bad,row")
        val read = MorningCues.participants(rows)
        assertEquals(1, read.size)
        assertEquals(MorningCues.fii(p), MorningCues.fii(read[0]))
        // Two trade dates: newest first.
        val older = RecorderFeeds.Participants(LocalDate.of(2026, 10, 1), p.rows)
        val both = MorningCues.participants(older.rows.map { RecorderFeeds.participant(older, fetched, "oi", it) } + rows)
        assertEquals(listOf(mon, LocalDate.of(2026, 10, 1)), both.map { it.date })
    }

    @Test fun fiiMissingIsSaid() {
        assertEquals(listOf(MorningCues.NO_FII), MorningCues.fiiSay(null, null))
        assertNull(MorningCues.fiiBrief(null, null, tue))
        assertNull(MorningCues.fii(null))
        assertEquals(emptyList(), MorningCues.participants(emptyList()))
        // A file over five days old stays out of the morning check.
        val f = MorningCues.fii(RecorderFeeds.parseParticipants(res("fao_participant_oi_05102026.csv")))
        assertNull(MorningCues.fiiBrief(f, null, mon.plusDays(9)))
        assertNotNull(MorningCues.fiiBrief(f, null, tue))
    }

    @Test fun theAnswerLeadsWithEachPart() {
        val a = MorningCues.answer(MorningCues.Ask.BOTH, listOf("G1.", "G2."), listOf("F1.", "F2."))
        assertEquals("G1. F1. G2. F2.", a)
        assertEquals("G1. G2.", MorningCues.answer(MorningCues.Ask.GIFT, listOf("G1.", "G2."), listOf("F1.")))
    }

    // ---- the question -------------------------------------------------------------------------------------------------

    @Test fun theQuestion() {
        for (s in listOf("gift nifty kya bol raha hai", "what is gift nifty saying", "how is gift nifty", "gift nifty", "what's sgx nifty",
            "where is gift nifty", "gift nifty kitna hai"))
            assertEquals(MorningCues.Ask.GIFT, MorningCues.asked(s), s)
        for (s in listOf("what are fiis doing", "fii position", "fii positioning", "what is the fii long short ratio", "fii ka position kya hai",
            "fii kya kar rahe hain", "are fiis long or short", "fii index futures position"))
            assertEquals(MorningCues.Ask.FII, MorningCues.asked(s), s)
        for (s in listOf("morning cues", "pre market cues", "gift nifty and fii position"))
            assertEquals(MorningCues.Ask.BOTH, MorningCues.asked(s), s)
        for (s in listOf("what did fiis do yesterday", "fii data", "fii dii data", "did fiis buy or sell", "fii ne aaj kitna becha",
            "news on fii flows", "what is fii", "what does gift nifty mean", "what are my positions", "how is nifty"))
            assertNull(MorningCues.asked(s), s)
    }
}
