package com.optionslab.ira

import com.optionslab.ira.OpenBook.Ord
import com.optionslab.ira.OpenBook.Pos
import com.optionslab.ira.OpenBook.Tone
import com.optionslab.ira.OpenBook.Venue
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenBookTest {
    private val z = Venue(OpenBook.ZERODHA, 1_234.4, listOf(Pos("NIFTY26OCT25000CE", -75, 112.5, 1_500.0), Pos("BANKNIFTY26OCT56000PE", 0, 80.0, -266.0)),
        listOf(Ord("NIFTY26OCT25100CE", "BUY", 75, "LIMIT", 90.0, 0.0, "OPEN"),
            Ord("NIFTY26OCT25000CE", "BUY", 75, "SL", 130.0, 128.0, "TRIGGER PENDING"),
            Ord("NIFTY26OCT24900PE", "SELL", 75, "LIMIT", 50.0, 0.0, "COMPLETE"),
            Ord("NIFTY26OCT24800PE", "SELL", 75, "LIMIT", 40.0, 0.0, "CANCELLED"),
            Ord("NIFTY26OCT24700PE", "SELL", 75, "LIMIT", 40.0, 0.0, "REJECTED"),
            Ord("NIFTY26OCT24600PE", "BUY", 75, "LIMIT", 20.0, 0.0, "AMO REQ RECEIVED", "amo")), primary = true)

    @Test fun pendingMeansOpenTriggerPendingOrAmoOnly() {
        assertEquals("OPEN", OpenBook.pendingLabel("OPEN"))
        assertEquals("OPEN", OpenBook.pendingLabel("open"))
        assertEquals("TRIGGER PENDING", OpenBook.pendingLabel("trigger pending"))
        assertEquals("AMO", OpenBook.pendingLabel("AMO REQ RECEIVED"))
        assertEquals("AMO", OpenBook.pendingLabel("OPEN", "amo"))
        assertEquals("OPEN", OpenBook.pendingLabel("MODIFY PENDING"))
        listOf("COMPLETE", "CANCELLED", "REJECTED", "CANCELLED AMO", "", "something new").forEach { assertNull(OpenBook.pendingLabel(it), it) }
    }

    @Test fun onlyWhatIsOpenPositionsFirst() {
        val s = OpenBook.screen(listOf(z))
        assertEquals("Today's P&L · Zerodha", s.caption)
        assertEquals("+₹1,234", s.headline)
        assertEquals(Tone.GAIN, s.tone)
        assertEquals(emptyList(), s.split, "one account: no split lines")
        assertEquals(listOf("NIFTY26OCT25000CE", "NIFTY26OCT25100CE", "NIFTY26OCT25000CE", "NIFTY26OCT24600PE"), s.rows.map { it.title })
        assertEquals(OpenBook.Row("NIFTY26OCT25000CE", "-75 · LTP 112.50", "+₹1,500", Tone.GAIN), s.rows[0])
        assertEquals(OpenBook.Row("NIFTY26OCT25100CE", "BUY 75 · LIMIT · OPEN", "@ 90.00", Tone.PLAIN), s.rows[1])
        assertEquals("BUY 75 · SL · TRIGGER PENDING · trg 128.00", s.rows[2].detail)
        assertEquals("BUY 75 · LIMIT · AMO", s.rows[3].detail)
        assertNull(s.more); assertNull(s.note)
    }

    @Test fun theHeadlineIsTheMainAccountOnlyNeverRealPlusPaper() {
        val p = Venue(OpenBook.PAPER, -2_000.0, listOf(Pos("BANKNIFTY26OCT56000CE", 30, null, -2_000.0)),
            listOf(Ord("BANKNIFTY26OCT56500CE", "SELL", 30, "SL-M", 0.0, 410.0, "trigger pending")))
        val s = OpenBook.screen(listOf(z, p))
        assertEquals("Today's P&L · Zerodha", s.caption)
        assertEquals("+₹1,234", s.headline, "live mode: Zerodha's alone, paper not added in")
        assertEquals(Tone.GAIN, s.tone)
        assertEquals(listOf("Paper  −₹2,000"), s.split)
        // Paper mode: Paper on top, Zerodha below it.
        val paperMode = OpenBook.screen(listOf(z.copy(primary = false), p.copy(primary = true)))
        assertEquals("Today's P&L · Paper", paperMode.caption)
        assertEquals("−₹2,000", paperMode.headline)
        assertEquals(Tone.LOSS, paperMode.tone)
        assertEquals(listOf("Zerodha  +₹1,234"), paperMode.split)
        assertEquals("+30 · LTP — · Paper", s.rows[1].detail)
        assertEquals("-75 · LTP 112.50 · Zerodha", s.rows[0].detail)
        assertEquals(OpenBook.Row("BANKNIFTY26OCT56500CE", "SELL 30 · SL-M · TRIGGER PENDING · Paper", "trg 410.00", Tone.PLAIN), s.rows.last())
    }

    @Test fun aQuietPaperAccountIsLeftOutWhileLive() {
        val s = OpenBook.screen(listOf(z, Venue(OpenBook.PAPER, 0.0)))
        assertEquals("Today's P&L · Zerodha", s.caption)
        assertEquals(emptyList(), s.split)
    }

    @Test fun aFailedReadIsSaidNotShownAsEmpty() {
        val s = OpenBook.screen(listOf(Venue(OpenBook.ZERODHA, null, problem = "could not read", primary = true)))
        assertEquals("—", s.headline)
        assertEquals(listOf("Zerodha: could not read"), s.split)
        assertTrue(s.rows.isEmpty())
        assertNull(s.note, "not 'No open positions'")

        val both = OpenBook.screen(listOf(Venue(OpenBook.ZERODHA, null, problem = "could not read", primary = true), Venue(OpenBook.PAPER, 500.0)))
        assertEquals("Today's P&L · Zerodha", both.caption)
        assertEquals("—", both.headline, "the main account unread: no paper figure in its place")
        assertEquals(listOf("Zerodha: could not read", "Paper  +₹500"), both.split)
        assertEquals("No open Paper positions or orders", both.note)
    }

    @Test fun nothingOpen() {
        val s = OpenBook.screen(listOf(Venue(OpenBook.PAPER, -150.0, listOf(Pos("X", 0, 1.0, -150.0)),
            listOf(Ord("Y", "BUY", 1, "LIMIT", 1.0, 0.0, "COMPLETE")), primary = true)))
        assertEquals(OpenBook.NOTHING_OPEN, s.note)
        assertEquals("−₹150", s.headline)
        assertEquals(OpenBook.WAITING, OpenBook.screen(emptyList()).note)
        assertEquals("—", OpenBook.screen(emptyList()).headline)
    }

    @Test fun cappedWithAMoreLine() {
        val v = Venue(OpenBook.PAPER, 0.0, (1..11).map { Pos("S$it", it, 1.0, 0.0) }, primary = true)
        val s = OpenBook.screen(listOf(v), maxRows = 8)
        assertEquals(8, s.rows.size)
        assertEquals("S8", s.rows.last().title)
        assertEquals("+3 more", s.more)
        assertEquals(Tone.PLAIN, s.rows[0].tone)
        assertNull(OpenBook.screen(listOf(v), maxRows = 11).more)
    }

    @Test fun theStamp() {
        val now = LocalDateTime.of(2026, 10, 5, 14, 7)
        assertEquals("as of 09:05", OpenBook.asOf(LocalDateTime.of(2026, 10, 5, 9, 5), now))
        assertEquals("as of 3 Oct 15:29", OpenBook.asOf(LocalDateTime.of(2026, 10, 3, 15, 29), now))
    }

    @Test fun eachAccountHasItsOwnTimeAndAnEarlierDayIsNotShown() {
        val now = LocalDateTime.of(2026, 10, 5, 14, 7)
        val zt = z.copy(at = LocalDateTime.of(2026, 10, 5, 11, 30))
        val pt = Venue(OpenBook.PAPER, 500.0, at = LocalDateTime.of(2026, 10, 5, 13, 59))
        val (s, stamp) = OpenBook.view(listOf(zt, pt), now)
        assertEquals("+₹1,234", s.headline)
        assertEquals("as of 11:30", stamp, "the oldest account shown")

        // Zerodha last read yesterday: not yesterday's figures, said plainly; the stamp is paper's.
        val old = zt.copy(at = LocalDateTime.of(2026, 10, 4, 15, 29))
        val (y, yStamp) = OpenBook.view(listOf(old, pt), now)
        assertEquals("—", y.headline)
        assertEquals(listOf("Zerodha: not updated today", "Paper  +₹500"), y.split)
        assertTrue(y.rows.none { it.title.startsWith("NIFTY") }, "no stale Zerodha rows")
        assertEquals("as of 13:59", yStamp)

        // A side account from an earlier day is left out; one with no time is never taken for today's.
        val (q, qStamp) = OpenBook.view(listOf(zt, pt.copy(at = LocalDateTime.of(2026, 10, 2, 15, 0))), now)
        assertEquals(emptyList(), q.split)
        assertEquals("as of 11:30", qStamp)
        val (u, uStamp) = OpenBook.view(listOf(z), now)
        assertEquals(listOf("Zerodha: not updated today"), u.split)
        assertNull(uStamp)
    }

    @Test fun theCodecRoundTrips() {
        val tricky = z.copy(positions = z.positions + Pos("A\tB\nC", 1, null, 0.0), problem = null)
        val back = OpenBook.decode(OpenBook.encode(tricky))!!
        assertEquals(tricky.copy(positions = z.positions + Pos("A B C", 1, null, 0.0)), back)
        val failed = Venue(OpenBook.ZERODHA, null, problem = "could not read")
        assertEquals(failed, OpenBook.decode(OpenBook.encode(failed)))
        assertNull(OpenBook.decode(null)); assertNull(OpenBook.decode("")); assertNull(OpenBook.decode("garbage"))
        assertNull(OpenBook.decode("V\tZerodha\tnotanumber\t\t1"))
        val stamped = z.copy(at = LocalDateTime.of(2026, 10, 5, 14, 7, 41))
        assertEquals(stamped.copy(at = LocalDateTime.of(2026, 10, 5, 14, 7)), OpenBook.decode(OpenBook.encode(stamped)))
        assertEquals(z, OpenBook.decode("V\tZerodha\t1234.4\t\t1\n" + OpenBook.encode(z).substringAfter('\n')), "an older record, without a time")
        assertTrue(OpenBook.sameFigures(OpenBook.encode(stamped), OpenBook.encode(stamped.copy(at = LocalDateTime.of(2026, 10, 5, 15, 0)))))
        assertTrue(!OpenBook.sameFigures(OpenBook.encode(stamped), OpenBook.encode(stamped.copy(at = LocalDateTime.of(2026, 10, 6, 9, 15)))))
        assertTrue(!OpenBook.sameFigures(OpenBook.encode(stamped), OpenBook.encode(stamped.copy(pnl = 1.0))))

        val text = OpenBook.encodeOrders("2026-10-05", z.orders)
        assertEquals(z.orders, OpenBook.decodeOrders(text, "2026-10-05"))
        assertEquals(emptyList(), OpenBook.decodeOrders(text, "2026-10-06"), "yesterday's orders are not today's")
        assertEquals(emptyList(), OpenBook.decodeOrders(null, "2026-10-05"))
    }
}
