package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Liquidity BANKNIFTY's paper test: the freeze, the fill-quality records, the go-live checklist and the live stop rule. */
class LiquidityPaperTestTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val oct14 = LocalDate.of(2026, 10, 14)
    private fun ms(d: LocalDate, h: Int, m: Int, s: Int = 0) = d.atTime(h, m, s).atZone(ist).toInstant().toEpochMilli()

    // ---- the freeze ----

    @Test fun frozenUntilTheTenthPaperDayUnlessBossUnfreezes() {
        val s = LiquidityFreeze.State()
        assertTrue(LiquidityFreeze.frozen(s, LocalDate.of(2026, 10, 12)))
        assertTrue(LiquidityFreeze.frozen(s, LiquidityFreeze.UNTIL))
        assertFalse(LiquidityFreeze.frozen(s, LiquidityFreeze.UNTIL.plusDays(1)))
        assertFalse(LiquidityFreeze.frozen(s.copy(unfrozen = true), oct14))
        assertTrue(LiquidityFreeze.note(s, oct14).startsWith("Frozen for the paper test until Mon 26 Oct"), LiquidityFreeze.note(s, oct14))
        assertTrue(LiquidityFreeze.note(s.copy(unfrozen = true), oct14).startsWith("Unfrozen by you"))
        assertTrue(LiquidityFreeze.note(s, LocalDate.of(2026, 11, 2)).contains("ended"))
        assertTrue(LiquidityFreeze.touches("paper:liquidity")); assertTrue(LiquidityFreeze.touches("flow:liquidity:BANKNIFTY"))
        assertFalse(LiquidityFreeze.touches("mgr:orb_fresh:paper"))
        val st = LiquidityFreeze.State(unfrozen = true, stopResetAtMs = 123L, stopTold = "2026-10-20|x", watched = true)
        assertEquals(st, LiquidityFreeze.decode(LiquidityFreeze.encode(st)))
        assertEquals(LiquidityFreeze.State(), LiquidityFreeze.decode(null)); assertEquals(LiquidityFreeze.State(), LiquidityFreeze.decode("junk"))
    }

    @Test fun bossesTrailSwitchIsOffByDefaultKeptAndSaysItFailed() {
        // Off by default, and a file written before the switch existed reads back off.
        assertFalse(LiquidityFreeze.State().trail)
        assertFalse(LiquidityFreeze.decode("v1\nunfrozen=true\nstopResetAt=0\nstopTold=\nwatched=false\n").trail)
        val on = LiquidityFreeze.State(unfrozen = true, watched = true, trail = true)
        assertEquals(on, LiquidityFreeze.decode(LiquidityFreeze.encode(on)))
        assertEquals(on.copy(trail = false), LiquidityFreeze.decode(LiquidityFreeze.encode(on.copy(trail = false))))
        val offNote = LiquidityFreeze.trailNote(LiquidityFreeze.State())
        assertTrue(offNote.contains("failed research, your choice") && offNote.contains("off."), offNote)
        assertTrue(offNote.contains("12 of 13 big winners") && offNote.contains("MIDCPNIFTY was not tested"), offNote)
        // R19c (13 Oct, later): Boss's rule from +Rs 1,000 a lot, never under +Rs 500, with its failed holdout numbers.
        assertTrue(offNote.contains("from +₹1,000 a lot, sell ₹500 below the best") && offNote.contains("never under +₹500"), offNote)
        assertTrue(offNote.contains("R19c") && offNote.contains("-₹6.9k") && offNote.contains("170 trades"), offNote)
        assertTrue(LiquidityFreeze.trailSaid(true).contains("from +₹1,000 a lot"))
        val onNote = LiquidityFreeze.trailNote(on)
        assertTrue(onNote.contains("ON for new Liquidity PAPER trades") && onNote.contains("live trades never use it"), onNote)
        assertTrue(LiquidityFreeze.trailSaid(true).contains("Live trades never use it"))
        assertTrue(LiquidityFreeze.trailSaid(false).contains("plain rules again"))
        // The freeze itself is unchanged by the trail switch.
        assertTrue(LiquidityFreeze.frozen(LiquidityFreeze.State(trail = true), oct14))
    }

    // ---- fill quality ----

    private val close = ms(oct14, 10, 20)
    private val sig = FillQuality.Rec(FillQuality.Kind.SIGNAL, close + 2_100, "liquidity5", "BANKNIFTY26OCT54000CE", false, 210.3,
        bid = 210.0, ask = 210.6, bidQty = 900, askQty = 1_200, bid5 = 6_300, ask5 = 7_100, quoteAgeMs = 400, barCloseMs = close)
    private val fill = FillQuality.Rec(FillQuality.Kind.ENTRY, close + 3_400, "liquidity5", "BANKNIFTY26OCT54000CE", false, 210.6, 210.3,
        bid = 210.0, ask = 210.6, quoteAgeMs = 300, barCloseMs = close, sentMs = close + 2_500, qty = 30)

    @Test fun eachRecordKeepsTheBookTheTimingAndTheFill() {
        assertEquals(0.6 / 210.3 * 100, sig.spreadPct!!, 1e-9)
        assertEquals(sig.spreadPct!! / 2, sig.halfSpreadPct!!, 1e-12)
        assertEquals(2_100L, sig.lagMs); assertFalse(sig.stale)
        assertEquals((210.6 - 210.3) / 210.3 * 100, fill.vsSignalPct!!, 1e-9)
        assertEquals(0.3 * 30, fill.vsSignalRs!!, 1e-9)
        assertTrue(fill.bankNifty); assertEquals("5m BANKNIFTY", FillQuality.bookWords("liquidity5"))
        assertTrue(sig.copy(quoteAgeMs = null).stale); assertTrue(sig.copy(quoteAgeMs = 6_000).stale)
        assertEquals(oct14, sig.day)
        // The stored line reads back the same.
        assertEquals(sig, FillQuality.decode(FillQuality.encode(sig)))
        assertEquals(fill, FillQuality.decode(FillQuality.encode(fill)))
        assertNull(FillQuality.decode("x|y")); assertNull(FillQuality.decode("q1|z|1|a|b|0|-|-|-|-|-|-|-|-|-|-|-|0"))
        val line = FillQuality.line(fill)
        assertTrue(line.startsWith("14 Oct 10:20:03 · 5m BANKNIFTY entry · paper"), line)
        assertTrue(line.contains("bid 210.00 / ask 210.60 (spread 0.29%)"), line)
        assertTrue(line.contains("3.4 s after the bar close") && line.contains("sent 2.5 s after the close"), line)
        assertTrue(line.contains("fill 210.60 vs 210.30 at the signal (+0.14%, +Rs 9)") && line.endsWith("quote fresh"), line)
        assertTrue(FillQuality.line(sig).contains("top 900 / 1,200 (5 levels 6,300 / 7,100)"), FillQuality.line(sig))
    }

    @Test fun summariesTheDiagnosticsJarvisAndTheOrderReview() {
        val recs = listOf(sig, fill, sig.copy(book = "liquidity5_mid", symbol = "MIDCPNIFTY26OCT13000CE"))
        val s = FillQuality.stats(recs)
        assertEquals(1, s.signals); assertEquals(1, s.entries); assertEquals(0, s.stale)
        assertEquals(3.4, s.entryLagS!!, 1e-9)
        val sum = FillQuality.summary(recs)
        assertTrue(sum.startsWith("Liquidity BANKNIFTY fills since 13 Oct: 1 signal, 1 entry, 0 exits · median half-spread 0.14%"), sum)
        assertTrue(FillQuality.diagLine(recs).startsWith("Liquidity fill quality: 3 kept (BANKNIFTY 2, FINNIFTY 0, MIDCPNIFTY 1)"))
        val a = FillQuality.answer(recs)
        assertTrue(a.startsWith("Boss, liquidity BANKNIFTY fills since 13 Oct") && a.contains("within 10 seconds"), a)
        assertTrue(FillQuality.answer(emptyList()).contains("none recorded yet"))
        // Records before 13 Oct are not counted.
        assertEquals(0, FillQuality.stats(listOf(sig.copy(atMs = ms(LocalDate.of(2026, 10, 9), 10, 0)))).signals)
        val bad = fill.copy(price = 212.0, atMs = close + 14_000)
        val notes = FillQuality.reviewNotes(listOf(sig.copy(ask = 212.0), bad), oct14)
        assertTrue(notes.first().startsWith("Liquidity fill quality today: 2 records"), notes.toString())
        assertTrue(notes.any { it.contains("over its signal's price (limit +0.5%)") }, notes.toString())
        assertTrue(notes.any { it.contains("half-spread (worry above 0.3%)") }, notes.toString())
        assertTrue(notes.any { it.contains("after the bar close (aim: within 10 s)") }, notes.toString())
        assertTrue(FillQuality.reviewNotes(recs, oct14.plusDays(1)).isEmpty())
    }

    // ---- the go-live checklist ----

    private val t1 = oct14.atTime(10, 15)

    @Test fun theEveningReplayComparison() {
        val paper = listOf(LiquidityGoLive.PaperEntry("liquidity5", t1, 1, 54_000, t1.plusMinutes(5).plusSeconds(4), t1.plusMinutes(40), 5))
        val replay = mapOf(oct14 to listOf(LiquidityGoLive.ReplaySignal("liquidity5", t1, 1, 54_000),
            // Signalled while the 5m book held: not the app's to take.
            LiquidityGoLive.ReplaySignal("liquidity5", t1.plusMinutes(15), -1, 54_100),
            // The 15m book was flat: a miss.
            LiquidityGoLive.ReplaySignal("liquidity15", oct14.atTime(11, 0), -1, 54_100)))
        val f = LiquidityGoLive.fidelity(paper, replay)
        assertEquals(1, f.matched); assertEquals(2, f.compared); assertEquals(1, f.missed); assertEquals(0.5, f.share!!, 1e-9)
        // A late entry (over a minute after the close) or another strike does not match.
        assertEquals(0, LiquidityGoLive.fidelity(listOf(paper[0].copy(entryTime = t1.plusMinutes(7))), replay).matched)
        assertEquals(0, LiquidityGoLive.fidelity(listOf(paper[0].copy(strike = 54_100)), replay).matched)
        assertNull(LiquidityGoLive.fidelity(paper, emptyMap()).share)
    }

    @Test fun theChecklistInformsLineByLine() {
        val trades = List(7) { Vetting.Trade(oct14, if (it % 2 == 0) 900.0 else -400.0) }
        val ok = LiquidityGoLive.lines(LiquidityGoLive.Inputs(trades, LocalDate.of(2026, 10, 26), LiquidityGoLive.Fidelity(7, 7, 0, 9),
            listOf(3.0, 4.5, 6.0), FillQuality.stats(listOf(sig, fill)), 0, 0, true, "203.0.113.7", watched = true))
        assertEquals(10, ok.size)
        assertTrue(ok.all { it.mark == LiquidityGoLive.Mark.OK }, ok.joinToString("\n"))
        assertEquals("✓ At least 6 trades since 13 Oct: 7", ok[0].toString())
        assertTrue(ok[1].text.contains("7 of 7 (100%)")); assertTrue(ok[2].text.contains("median 4.5 s (worst 6.0 s)"), ok[2].text)
        assertTrue(LiquidityGoLive.head(ok).endsWith("10 ✓ · 0 ✗ · 0 —"), LiquidityGoLive.head(ok))
        val empty = LiquidityGoLive.lines(LiquidityGoLive.Inputs(emptyList(), oct14, null, emptyList(), null, null, null, null))
        assertEquals(LiquidityGoLive.Mark.NO, empty[0].mark)
        assertTrue(empty.drop(1).all { it.mark == LiquidityGoLive.Mark.UNKNOWN }, empty.joinToString("\n"))
        val bad = LiquidityGoLive.lines(LiquidityGoLive.Inputs(List(12) { Vetting.Trade(oct14, -3_000.0) }, oct14,
            LiquidityGoLive.Fidelity(5, 7, 1, 3), listOf(12.0, 15.0), FillQuality.stats(listOf(sig.copy(ask = 213.0), fill.copy(price = 212.0))),
            2, 1, false, "not set", watched = false))
        assertEquals(listOf("✓", "✗", "✗", "✗", "✗", "✗", "✗", "✗", "✗", "—"), bad.map { it.mark.sign }, bad.joinToString("\n"))
        assertTrue(LiquidityGoLive.NOTE.contains("nothing switches to Live by itself"))
    }

    // ---- the live stop rule ----

    private fun tr(d: LocalDate, net: Double, h: Int = 12) = LiquidityStopRule.Trade(ms(d, h, 0), d, net)

    @Test fun stopsAtFortyThousandFromTheBestOrThirteenThousandInAMonth() {
        val ok = LiquidityStopRule.check(listOf(tr(oct14, 5_000.0), tr(oct14.plusDays(1), -8_000.0)), oct14.plusDays(2))
        assertFalse(ok.stopped); assertEquals(-8_000.0, ok.drawdown, 1e-9); assertEquals(-3_000.0, ok.month, 1e-9)
        assertTrue(LiquidityStopRule.line(ok, live = true).startsWith("Live stop rule: drawdown -Rs 8,000 of -Rs 40,000, this month -Rs 3,000"))
        // A month of -13,000: stopped on the day it got there.
        val month = LiquidityStopRule.check(listOf(tr(oct14, -6_000.0), tr(oct14.plusDays(1), -7_000.0), tr(oct14.plusDays(2), 9_000.0)), oct14.plusDays(3))
        assertTrue(month.stopped); assertEquals(oct14.plusDays(1), month.day)
        assertTrue(month.why!!.startsWith("Oct net -Rs 13,000"), month.why)
        assertTrue(LiquidityStopRule.line(month, live = true).contains("no new live entries until you re-enable it with your PIN; exits continue"))
        assertTrue(LiquidityStopRule.line(month, live = false).startsWith("Live stop rule (paper, information only): would have stopped on 2026-10-15"))
        // Forty thousand from the best, across months (each month under 13,000).
        val nov = LocalDate.of(2026, 11, 2); val dec = LocalDate.of(2026, 12, 1); val jan = LocalDate.of(2027, 1, 4)
        val dd = LiquidityStopRule.check(listOf(tr(oct14, 10_000.0), tr(oct14.plusDays(1), -12_000.0), tr(nov, -12_000.0),
            tr(dec, -12_000.0), tr(jan, -4_000.0)), jan)
        assertTrue(dd.stopped); assertEquals(jan, dd.day); assertTrue(dd.why!!.startsWith("drawdown -Rs 40,000"), dd.why)
        // Re-enabled with the PIN: only trades after it count.
        assertFalse(LiquidityStopRule.check(listOf(tr(oct14, -6_000.0), tr(oct14.plusDays(1), -7_000.0)), oct14.plusDays(2),
            sinceMs = ms(oct14.plusDays(1), 13, 0)).stopped)
        // Before 13 Oct nothing counts.
        assertFalse(LiquidityStopRule.check(listOf(tr(LocalDate.of(2026, 10, 9), -50_000.0)), oct14).stopped)
        assertEquals("", ok.key); assertNotNull(month.key.takeIf { it.isNotEmpty() })
        assertFalse(LiquidityStopRule.NOTICE.contains("Rs"), "no amounts on a lock screen")
    }

    @Test fun jarvisHearsTheQuestions() {
        for (q in listOf("how are Liquidity fills", "Jarvis, liquidity fill quality?", "what spread did liquidity see today", "fills on liquidity"))
            assertTrue(FillQuality.asked(q), q)
        for (q in listOf("how is liquidity doing", "what's my slippage", "fill my order")) assertFalse(FillQuality.asked(q), q)
        assertTrue(LiquidityGoLive.asked("show the go-live checklist")); assertTrue(LiquidityGoLive.asked("is liquidity ready to go live?"))
        assertFalse(LiquidityGoLive.asked("go live with ORB"))
    }

    @Test fun theOrderReviewCarriesTheFillNotesAsFacts() {
        val d = OrderReview.review(OrderReview.Inputs(oct14, fillNotes = listOf("Liquidity fill quality today: 2 records.")))
        assertTrue(d.notes.contains("Liquidity fill quality today: 2 records."), d.notes.toString())
        assertTrue(d.flaws.isEmpty())
    }
}
