package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 28 Oct (Boss: "Yes fix finnifty"): the price of an option with a live bid and ask but no last price ([BookPrice]). */
class BookPriceTest {
    @Test fun aSaneTwoSidedBookIsBoughtAtTheAsk() {
        // FINNIFTY23NOV2624250PE-like: 0.33% half-spread, inside h14's 0.5% cap.
        val v = BookPrice.judge(149.0, 150.0)
        assertTrue(v.ok)
        assertEquals(150.0, v.ask)
        assertEquals(149.0, v.bid)
        assertNull(v.why)
    }

    @Test fun aOneTickBookIsSaneEvenOnACheapOption() {
        // 2.00 / 2.05: 1.2% of the price, but one tick is the narrowest a book can be.
        assertTrue(BookPrice.judge(2.0, 2.05).ok)
        assertTrue(BookPrice.judge(2.0, 2.0).ok, "a locked book (bid = ask) is not crossed")
        assertFalse(BookPrice.judge(2.0, 2.10).ok, "two ticks on a 2-rupee option is wide")
    }

    @Test fun anEmptyBookIsRefusedWithItsReason() {
        val v = BookPrice.judge(null, null)
        assertFalse(v.ok)
        assertNull(v.ask)
        assertEquals("no last price and an empty book (no bid, no ask)", v.why)
        assertEquals(v.why, BookPrice.judge(0.0, 0.0).why)
        assertEquals(v.why, BookPrice.judge(Double.NaN, -1.0).why)
    }

    @Test fun aOneSidedBookIsRefusedWithItsReason() {
        val noAsk = BookPrice.judge(148.0, 0.0)
        assertEquals("no last price and a one-sided book (bid 148.00, no ask)", noAsk.why)
        assertEquals(148.0, noAsk.bid, "the bid still marks a held long")
        val noBid = BookPrice.judge(null, 150.0)
        assertEquals("no last price and a one-sided book (ask 150.00, no bid)", noBid.why)
        assertNull(noBid.bid)
    }

    @Test fun aCrossedBookIsRefused() {
        val v = BookPrice.judge(151.0, 150.0)
        assertFalse(v.ok)
        assertEquals("no last price and a crossed book (bid 151.00, ask 150.00)", v.why)
    }

    @Test fun aWideSpreadIsRefusedAtH14sCap() {
        // 148 / 150: half-spread 0.67%, over h14's 0.5%.
        val v = BookPrice.judge(148.0, 150.0)
        assertFalse(v.ok)
        assertNull(v.ask)
        assertEquals("no last price and the spread is too wide (bid 148.00, ask 150.00: half-spread 0.67%, over h14's 0.5% cap)", v.why)
        // Just inside: ask <= mid x 1.005 on the tick (mid 149.35 -> cap 150.09 -> 150.05).
        assertTrue(BookPrice.judge(148.7, 150.0).ok)
    }

    /** The timeline entry: Boss's ask, paper only, one tap undoes it (no PIN), labelled as raising paper risk (it adds entries). */
    @Test fun theTimelineEntryIsPaperOnlyAndItsUndoSwitchesItOff() {
        val on = AutoChange.paperBookPrice(true)
        assertEquals(AutoChange.Kind.PAPER_FILTER, on.kind)
        assertEquals(AutoChange.BOOK_PRICE_TARGET, on.target)
        assertEquals(AutoChange.Scope.PAPER, on.scope)
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(on))
        assertTrue("never mirrored live" in on.reason, on.reason)
        val (book, entry) = AutoChange.record(AutoChange.Book(), on, AutoChange.Status.KEPT, 1L, java.time.LocalDate.of(2026, 10, 28),
            by = "your choice", note = "28 Oct")
        val u = AutoChange.undo(book, entry.id) ?: throw AssertionError("no undo")
        assertEquals(AutoChange.SWITCHED_OFF, u.inverse.to)
        assertEquals(AutoChange.BOOK_PRICE_TARGET, u.inverse.target)
        assertFalse(u.needsPin, "paper only: no PIN to undo")
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(AutoChange.paperBookPrice(false)))
    }

    @Test fun theFilledLineSaysWhatHappened() {
        assertEquals("FINNIFTY23NOV2624250PE had no last price; paper priced it from the live book (bid 149.00, ask 150.00) and the buy filled at 150.00",
            BookPrice.filledLine("FINNIFTY23NOV2624250PE", 149.0, 150.0, 150.0))
    }
}
