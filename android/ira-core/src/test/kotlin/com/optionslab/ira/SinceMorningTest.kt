package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SinceMorningTest {
    private val day = LocalDate.of(2026, 10, 5)      // a Monday
    private val prev = LocalDate.of(2026, 10, 2)     // the Friday before
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    /** 1-minute bars on [d] from 09:15 for [n] minutes, the close at minute i given by [px]. */
    private fun session(d: LocalDate, px: (Int) -> Double, n: Int = 375, open: Double? = null) = List(n) { i ->
        val c = px(i); val o = if (i == 0) (open ?: c) else px(i - 1)
        Candle(d.atTime(9, 15).plusMinutes(i.toLong()), o, maxOf(o, c), minOf(o, c), c)
    }

    private val friday = session(prev, { 24_000.0 })
    /** Gap up 0.5% from 24,000, a quiet first half hour, then down through the last close and on. */
    private val gapThenFall: (Int) -> Double = { i -> if (i < 30) 24_120.0 + (if (i % 2 == 0) 5 else -5) else 24_120.0 - (i - 30) * 1.0 }

    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)

    @Test fun theQuestions() {
        listOf("what changed since this morning", "What's changed since the morning?", "what's different since the open", "anything new since 9:45",
            "what has changed in nifty since this morning", "changes since the morning brief", "now versus this morning", "now vs the morning",
            "since the morning what has changed", "what's different from this morning", "subah se kya badla", "subah se ab tak kya change hua",
            "subah aur ab mein kya fark hai").forEach { assertTrue(SinceMorning.asked(it), it) }
        // The chain's drift, the OI shift, the news, prices alone, Jarvis's learning, forecasts and advice are not this.
        listOf("has the biggest put oi moved since morning", "how has max pain changed since the open", "how has the oi shifted since morning",
            "what news is new since this morning", "how much has nifty moved since morning", "what changed in how you work",
            "what will change by the close", "should i buy since nothing changed since morning", "what matters right now",
            "how is today different from yesterday", "what's new", "what changed").forEach { assertFalse(SinceMorning.asked(it), it) }
    }

    @Test fun theIndicesAskedElseNiftyAndBankNifty() {
        assertEquals(listOf(Market.NIFTY, Market.BANKNIFTY), SinceMorning.indices(emptyList()))
        assertEquals(listOf(Market.SENSEX), SinceMorning.indices(listOf(Market.SENSEX, Market.GOLD)))
    }

    @Test fun aGapFilledAfterTheMarkANewLowAndTheMoveAreChanges() {
        val bars = friday + session(day, gapThenFall, n = 240)
        val f = SinceMorning.index(Market.NIFTY, bars, day)
        val kinds = f.changes.map { it.kind }.toSet()
        assertTrue(SinceMorning.Kind.GAP in kinds, "${f.changes}")
        assertTrue(SinceMorning.Kind.MOVE in kinds)
        assertTrue(SinceMorning.Kind.EXTREME in kinds)
        val gap = f.changes.first { it.kind == SinceMorning.Kind.GAP }.text
        assertTrue("was still open at 09:45" in gap && "filled at 11:45" in gap, gap)   // 24,120 - 120 = 24,000 at minute 150
        val move = f.changes.first { it.kind == SinceMorning.Kind.MOVE }.text
        assertTrue(move.startsWith("Nifty is down 204 points"), move)
        assertTrue(f.changes.first { it.kind == SinceMorning.Kind.EXTREME }.text.contains("new low of the day"))
    }

    @Test fun aQuietDayHolds() {
        val bars = friday + session(day, { i -> 24_000.0 + (if (i % 2 == 0) 3 else -3) }, n = 200)
        val f = SinceMorning.index(Market.NIFTY, bars, day)
        assertTrue(f.changes.none { it.score >= SinceMorning.SMALL && it.kind == SinceMorning.Kind.MOVE }, "${f.changes}")
        assertTrue(f.held.any { "within" in it }, "${f.held}")
        assertTrue(f.held.any { "inside its morning range" in it }, "${f.held}")
        // No candles after the mark: nothing to set against it.
        assertTrue(SinceMorning.index(Market.NIFTY, friday + session(day, { 24_000.0 }, n = 20), day).changes.isEmpty())
    }

    @Test fun theStructureReadThenAndNow() {
        // Range-like for the first half hour, then a steady climb: trend-like up by the close of the morning.
        val bars = friday + session(day, { i -> if (i == 0 || i == 29) 24_000.0 else if (i < 30) 24_000.0 + (if (i % 2 == 0) 4 else -4) else 24_000.0 + (i - 30) * 2.0 }, n = 200)
        val f = SinceMorning.index(Market.NIFTY, bars, day)
        val s = f.changes.firstOrNull { it.kind == SinceMorning.Kind.STRUCTURE }?.text
        assertTrue(s != null && "range-like at 09:45" in s && "trend-like up now" in s, "$s")
    }

    private val expiry = LocalDate.of(2026, 10, 6)
    private fun read(h: Int, m: Int, ceWall: Double, peWall: Double, peExtra: Long = 0) =
        ChainIntel.Read("NIFTY", expiry, day.atTime(h, m), 24_100.0, 24_100.0, 12.0,
            (0..8).map { i ->
                val k = 24_000.0 + i * 50
                ChainIntel.Strike(k, 100_000L + (if (k == ceWall) 900_000 else 0), 90_000L + (if (k == peWall) 800_000 + peExtra else 0),
                    null, null, null, null, null, null)
            })

    @Test fun theChainFirstReadAgainstTheNewest() {
        val f = SinceMorning.chain(listOf(read(9, 31, 24_300.0, 24_000.0), read(12, 0, 24_200.0, 24_000.0, peExtra = 600_000)), day)
        val call = f.changes.first { it.kind == SinceMorning.Kind.CALL_WALL }
        assertEquals("Nifty's biggest call open interest is 24,200 now against 24,300 at 09:31, 100 points lower.", call.text)
        assertTrue(f.held.any { "biggest put open interest still 24,000" in it }, "${f.held}")
        assertTrue(f.changes.any { it.kind == SinceMorning.Kind.PCR }, "${f.changes}")
        // One read only: nothing to compare. A late first read is said so.
        assertTrue(SinceMorning.chain(listOf(read(9, 31, 24_300.0, 24_000.0)), day).changes.isEmpty())
        val late = SinceMorning.chain(listOf(read(11, 5, 24_300.0, 24_000.0), read(13, 0, 24_400.0, 24_000.0)), day)
        assertTrue(late.changes.first { it.kind == SinceMorning.Kind.CALL_WALL }.text.contains("my first chain read today was only then"))
    }

    private fun h(title: String, hh: Int, mm: Int) =
        Headline(title, "", "src", day.atTime(hh, mm).atZone(zone).toInstant(), 0.0, emptyList())

    @Test fun newsThemesNewSinceTheMark() {
        val news = listOf(h("Inflation cools in September", 8, 30), h("Inflation data lifts bonds", 10, 30),
            h("RBI governor speaks on repo rate", 11, 0), h("RBI keeps repo unchanged, says governor", 11, 20), h("Nifty slips", 12, 0))
        val f = SinceMorning.news(news, zone, day, day.atTime(13, 0).atZone(zone).toInstant())
        assertEquals(1, f.changes.size, "${f.changes}")
        assertEquals("New in the news since 09:45: RBI (2 headlines, the first at 11:00).", f.changes[0].text)
        // Nothing new: the morning's themes held.
        val quiet = SinceMorning.news(news.take(2), zone, day, day.atTime(13, 0).atZone(zone).toInstant())
        assertTrue(quiet.changes.isEmpty() && quiet.held.single().contains("inflation"), "${quiet.held}")
    }

    @Test fun bossPositionsOpenedClosedResized() {
        val morning = listOf(SinceMorning.Held("Paper", "NIFTY24500PE", 75), SinceMorning.Held("Zerodha", "NIFTY24800CE", -75))
        val now = listOf(SinceMorning.Held("Paper", "NIFTY24500PE", 150), SinceMorning.Held("Paper", "BANKNIFTY52000CE", 30))
        val f = SinceMorning.positions(SinceMorning.Positions(morning, now))
        val texts = f.changes.map { it.text }
        assertTrue(texts.contains("Your Paper NIFTY24500PE went from long 75 to long 150."), "$texts")
        assertTrue(texts.contains("You opened Paper BANKNIFTY52000CE (long 30) since the morning."), "$texts")
        assertTrue(texts.contains("Your Zerodha NIFTY24800CE (short 75) from the morning is closed."), "$texts")
        assertTrue(SinceMorning.positions(SinceMorning.Positions(null, now)).held.single().contains("not noted this morning"))
        assertTrue(SinceMorning.positions(SinceMorning.Positions(now, now)).held.single().contains("as they were this morning"))
    }

    @Test fun theAnswerRanksAndKeepsTheLockedPhoneOut() {
        val bars = mapOf(Market.NIFTY to friday + session(day, gapThenFall, n = 240),
            Market.VIX to session(prev, { 12.0 }) + session(day, { i -> if (i < 30) 12.0 else 12.0 + (i - 30) * 0.01 }, n = 240))
        val positions = SinceMorning.Positions(emptyList(), listOf(SinceMorning.Held("Paper", "NIFTY24000PE", 75)))
        val unlocked = SinceMorning.answer(listOf(Market.NIFTY), bars, emptyMap(), emptyList(), zone, positions, false, at(13, 16))
        assertTrue(unlocked.startsWith("Since 09:45 this morning, to 13:15, Boss, the biggest change first: 1."), unlocked)
        assertTrue("India VIX is" in unlocked && "NIFTY24000PE" in unlocked, unlocked)
        assertTrue(unlocked.endsWith("not a forecast."))
        val locked = SinceMorning.answer(listOf(Market.NIFTY), bars, emptyMap(), emptyList(), zone, positions, true, at(13, 16))
        assertFalse("NIFTY24000PE" in locked, locked)
        assertTrue(SinceMorning.LOCKED_NOTE in locked)
        // The ranking: the biggest score first.
        val ranked = SinceMorning.say(SinceMorning.Found(listOf(SinceMorning.Change(SinceMorning.Kind.MOVE, "small.", 0.6),
            SinceMorning.Change(SinceMorning.Kind.GAP, "big.", 2.0), SinceMorning.Change(SinceMorning.Kind.PCR, "tiny.", 0.1))), null, false)
        assertEquals("Since 09:45 this morning, Boss, the biggest change first: 1. big. 2. small. Facts from the phone's own data, not a forecast.", ranked)
    }

    @Test fun tooEarlyAWeekendOrNoCandles() {
        val bars = mapOf(Market.NIFTY to friday + session(day, gapThenFall, n = 240))
        assertTrue(SinceMorning.answer(listOf(Market.NIFTY), bars, emptyMap(), emptyList(), zone, null, false, at(9, 30)).startsWith("It's only 09:30"))
        assertTrue(SinceMorning.answer(listOf(Market.NIFTY), bars, emptyMap(), emptyList(), zone, null, false, at(8, 50)).startsWith("The session hasn't opened"))
        assertTrue(SinceMorning.answer(listOf(Market.NIFTY), bars, emptyMap(), emptyList(), zone, null, false,
            LocalDate.of(2026, 10, 4).atTime(12, 0)).startsWith("There is no session today"))
        assertTrue(SinceMorning.answer(listOf(Market.BANKNIFTY), bars, emptyMap(), emptyList(), zone, null, false, at(12, 0)).startsWith("I have no candles"))
    }

    @Test fun morningZerodhaLegsNotReadAreSaidSoNeverCalledOpened() {
        val paper = SinceMorning.Held("Paper", "NIFTY24000PE", 75)
        val zLeg = SinceMorning.Held("Zerodha", "BANKNIFTY07OCT2654200CE", 35)
        // The 09:40 read: the broker timed out, so only Paper's legs were read - the note keeps that it was not read.
        val first = SinceMorning.renote(null, listOf(paper), SinceMorning.Zerodha.FAILED)
        assertEquals(SinceMorning.Zerodha.FAILED, first.zerodha)
        assertTrue(SinceMorning.wantsRead(first))
        // Kept and read back the same; a note kept before this round reads as read.
        assertEquals(first, SinceMorning.decode(SinceMorning.encode(day, first), day))
        assertEquals(null, SinceMorning.decode(SinceMorning.encode(day, first), day.plusDays(1)))
        assertEquals(SinceMorning.Zerodha.READ, SinceMorning.decode("$day\nPaper|NIFTY24000PE|75", day)!!.zerodha)
        // Asked later with Zerodha's leg held: never "opened since the morning" - said plainly that the morning's weren't read.
        val f = SinceMorning.positions(SinceMorning.Positions(first.held, listOf(paper, zLeg), first.zerodha, SinceMorning.Zerodha.READ))
        assertTrue(f.changes.isEmpty(), f.changes.toString())
        assertTrue(f.notes.single().startsWith("This morning's Zerodha legs weren't read") && f.notes.single().contains("1 Zerodha leg you hold now"), f.notes.toString())
        assertTrue(f.held.single().startsWith("your Paper positions as they were this morning"), f.held.toString())
        val said = SinceMorning.say(f, null, false)
        assertFalse(said.contains("You opened"), said)
        assertTrue(said.contains("This morning's Zerodha legs weren't read"), said)
        // A retry at 09:50 that reads Zerodha fills its legs in, keeping the 09:40 Paper legs; the window then stops reading.
        val second = SinceMorning.renote(first, listOf(SinceMorning.Held("Paper", "NIFTY24100CE", 50), zLeg), SinceMorning.Zerodha.READ)
        assertEquals(SinceMorning.Noted(listOf(paper, zLeg), SinceMorning.Zerodha.READ), second)
        assertFalse(SinceMorning.wantsRead(second))
        // Another failed retry changes nothing kept; a read note is never replaced.
        assertEquals(first, SinceMorning.renote(first, emptyList(), SinceMorning.Zerodha.FAILED))
        assertEquals(second, SinceMorning.renote(second, emptyList(), SinceMorning.Zerodha.READ))
        // Both read: Zerodha's legs compared as before.
        val both = SinceMorning.positions(SinceMorning.Positions(second.held, listOf(paper), SinceMorning.Zerodha.READ, SinceMorning.Zerodha.READ))
        assertTrue(both.changes.single().text.contains("Zerodha BANKNIFTY07OCT2654200CE (long 35) from the morning is closed"), both.changes.toString())
        // Now's read failing: the morning's Zerodha leg is not called closed.
        val nowFailed = SinceMorning.positions(SinceMorning.Positions(second.held, listOf(paper), SinceMorning.Zerodha.READ, SinceMorning.Zerodha.FAILED))
        assertTrue(nowFailed.changes.isEmpty() && nowFailed.notes.single().startsWith("Zerodha didn't answer in time just now"), nowFailed.toString())
        // Never logged in, no Zerodha legs either side: nothing to say about Zerodha.
        assertTrue(SinceMorning.positions(SinceMorning.Positions(listOf(paper), listOf(paper), SinceMorning.Zerodha.LOGGED_OUT, SinceMorning.Zerodha.LOGGED_OUT)).notes.isEmpty())
    }
}
