package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChainDriftTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val expiry = LocalDate.of(2026, 10, 7)

    /** Nine strikes 24,000-24,400; the biggest call OI at [ceWall], the biggest put OI at [peWall]. */
    private fun read(h: Int, m: Int, spot: Double, ceWall: Double, peWall: Double, maxPain: Double? = null, peExtra: Long = 0) =
        ChainIntel.Read("NIFTY", expiry, today.atTime(h, m), spot, 24_150.0, 12.4,
            (0..8).map { i ->
                val k = 24_000.0 + i * 50
                ChainIntel.Strike(k, 100_000L + (if (k == ceWall) 900_000 else 0), 90_000L + (if (k == peWall) 800_000 + peExtra else 0),
                    null, null, null, null, null, null)
            }, maxPain)

    @Test fun theQuestions() {
        assertEquals(ChainDrift.Ask.MAX_PAIN, ChainDrift.asked("How has max pain moved today?"))
        assertEquals(ChainDrift.Ask.MAX_PAIN, ChainDrift.asked("has the nifty max pain shifted since morning"))
        assertEquals(ChainDrift.Ask.MAX_PAIN, ChainDrift.asked("max pain drift"))
        assertEquals(ChainDrift.Ask.MAX_PAIN, ChainDrift.asked("where has max pain gone today"))
        assertEquals(ChainDrift.Ask.MAX_PAIN, ChainDrift.asked("max pain kitna shift hua"))
        assertEquals(ChainDrift.Ask.MAX_PAIN, ChainDrift.asked("BankNifty max pain through the day"))
        assertEquals(ChainDrift.Ask.WALLS, ChainDrift.asked("Is the call wall shifting?"))
        assertEquals(ChainDrift.Ask.WALLS, ChainDrift.asked("where is the put wall"))
        assertEquals(ChainDrift.Ask.WALLS, ChainDrift.asked("has the biggest call OI moved"))
        assertEquals(ChainDrift.Ask.WALLS, ChainDrift.asked("highest put open interest since morning"))
        assertEquals(ChainDrift.Ask.ALL, ChainDrift.asked("how has the chain drifted today"))
        assertEquals(ChainDrift.Ask.ALL, ChainDrift.asked("max pain and call wall shift since the open"))
        // Not these: max pain as it stands, the OI shift, the word itself, forecasts, advice, his own book.
        listOf("what is the nifty pcr and max pain", "nifty max pain", "how has the oi shifted since morning", "what is max pain",
            "what is a call wall", "max pain ka matlab", "where will max pain be tomorrow", "should I sell at the call wall",
            "how has my position moved", "where is the most call writing", "how much has nifty moved since morning").forEach {
            assertNull(ChainDrift.asked(it), it)
        }
        // And the chain readers it sits beside keep their own questions.
        assertNull(ChainIntel.asked("how has max pain moved today"))
        assertEquals(ChainIntel.Ask.SHIFT, ChainIntel.asked("how has the oi shifted since morning"))
    }

    @Test fun maxPainIsWorkedOutWhenTheChainDoesNotGiveIt() {
        // All the OI at 24,000: a settle there costs the writers nothing; higher, the calls written there lose.
        val r = ChainIntel.Read("NIFTY", expiry, today.atTime(10, 0), 24_200.0, 24_200.0, null, listOf(
            ChainIntel.Strike(24_000.0, 500L, 500L, null, null, null, null, null, null),
            ChainIntel.Strike(24_200.0, 0L, 0L, null, null, null, null, null, null),
            ChainIntel.Strike(24_400.0, 0L, 0L, null, null, null, null, null, null)))
        assertEquals(24_000.0, ChainDrift.maxPain(r))
        assertEquals(24_300.0, ChainDrift.maxPain(r.copy(maxPain = 24_300.0)))
        assertNull(ChainDrift.maxPain(r.copy(strikes = r.strikes.map { it.copy(ceOi = 0, peOi = 0) })))
    }

    @Test fun thePathKeepsOnlyChanges() {
        val ps = listOf(read(9, 20, 24_050.0, 24_300.0, 24_000.0, 24_100.0), read(10, 0, 24_090.0, 24_300.0, 24_000.0, 24_100.0),
            read(11, 2, 24_150.0, 24_400.0, 24_000.0, 24_150.0), read(12, 40, 24_230.0, 24_400.0, 24_100.0, 24_200.0)).map { ChainDrift.point(it) }
        val mp = ChainDrift.path(ps) { it.maxPain }
        assertEquals(listOf(24_100.0, 24_150.0, 24_200.0), mp.map { it.second })
        assertEquals("24,100 at 09:20, 24,150 at 11:02, then 24,200 at 12:40", ChainDrift.said(mp))
        assertEquals(listOf(24_300.0, 24_400.0), ChainDrift.path(ps) { it.callWall }.map { it.second })
    }

    @Test fun maxPainDriftAnswer() {
        val reads = listOf(read(9, 20, 24_050.0, 24_300.0, 24_000.0, 24_100.0), read(11, 2, 24_150.0, 24_400.0, 24_000.0, 24_150.0),
            read(12, 40, 24_230.0, 24_400.0, 24_100.0, 24_200.0))
        val s = ChainDrift.answer(ChainDrift.Ask.MAX_PAIN, reads, today)
        assertTrue(s.startsWith("Nifty chain today (expiry 7 Oct), from my 3 reads between 09:20 and 12:40."), s)
        assertTrue("Max pain: 24,100 at 09:20, 24,150 at 11:02, then 24,200 at 12:40 - up 100 points since my first read." in s, s)
        assertTrue("Spot was 24,050 at 09:20, 50 points below max pain; at 12:40 24,230, 30 points above it - closer to it than at my first read." in s, s)
        assertTrue("not a pull on price" in s, s)
        assertFalse("biggest call OI" in s, s)
        assertTrue(s.endsWith("not advice."), s)
    }

    @Test fun wallsAnswer() {
        val reads = listOf(read(9, 20, 24_050.0, 24_300.0, 24_000.0, 24_100.0), read(11, 45, 24_150.0, 24_400.0, 24_000.0, 24_150.0))
        val s = ChainDrift.answer(ChainDrift.Ask.WALLS, reads, today)
        assertTrue("I read the 9 strikes near the money." in s, s)
        assertTrue("The biggest call OI: 24,300 at 09:20, then 24,400 at 11:45 (now 250 points above spot)." in s, s)
        assertTrue("The biggest put OI has been at 24,000 all through my reads (now 150 points below spot)." in s, s)
        assertTrue("Put-call ratio across those strikes: 0.89 at 09:20, 0.89 at 11:45." in s, s)
        assertTrue("a reading, not a promise" in s, s)
        assertFalse("Max pain:" in s, s)
    }

    @Test fun aLongPathIsShortened() {
        val reads = (0 until 8).map { i -> read(9 + i / 2, (i % 2) * 30 + 1, 24_100.0, 24_300.0, 24_000.0, 24_000.0 + 50 * (i % 2) + 100 * (i / 4)) }
        val s = ChainDrift.answer(ChainDrift.Ask.MAX_PAIN, reads, today)
        assertTrue("then 7 changes - latest" in s, s)
    }

    @Test fun oneReadOrNoneIsSaidHonestly() {
        assertTrue("no read" in ChainDrift.answer(ChainDrift.Ask.ALL, emptyList(), today))
        val s = ChainDrift.answer(ChainDrift.Ask.ALL, listOf(read(10, 5, 24_160.0, 24_300.0, 24_000.0, 24_150.0)), today)
        assertTrue(s.startsWith("Nifty chain today (expiry 7 Oct): this is my first read of it today, at 10:05 - max pain 24,150, " +
            "the biggest call OI at 24,300, the biggest put OI at 24,000, spot 24,160. There is no drift to tell yet"), s)
    }

    @Test fun theBookGivesTheDaysReads() {
        val b = ChainIntel.Book()
        b.keep(read(9, 20, 24_050.0, 24_300.0, 24_000.0)); b.keep(read(10, 0, 24_050.0, 24_300.0, 24_000.0))
        assertEquals(2, b.day("NIFTY", today).size)
        assertTrue(b.day("NIFTY", today.plusDays(1)).isEmpty())
        assertTrue(b.day("BANKNIFTY", today).isEmpty())
    }
}
