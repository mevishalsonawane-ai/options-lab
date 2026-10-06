package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoticePriceTest {
    /**
     * Counts the candle downloads of a session of once-a-minute watch passes for [contracts] held paper contracts, no
     * stream. Each pass the paper tick reads each contract fresh (the money step, unchanged), then a few seconds later
     * the notice prices the book with [noticeReuseMs]. [tickFails]: passes whose tick read failed (no price kept).
     */
    private fun session(contracts: Int, noticeReuseMs: Long, passes: Int = 375, tickFails: Set<Int> = emptySet()): Pair<Int, Int> {
        var tick = 0
        var notice = 0
        val readAt = arrayOfNulls<Long>(contracts)
        for (p in 0 until passes) {
            val t0 = p * 60_000L
            for (c in 0 until contracts) {
                // The tick always downloads (reuse 0): its cadence and inputs are as before.
                assertTrue(NoticePrice.downloads(stream = false, readAgoMs = readAt[c]?.let { t0 - it }, reuseMs = 0L))
                tick++
                if (p !in tickFails) readAt[c] = t0
            }
            val t1 = t0 + 4_000L
            for (c in 0 until contracts) {
                if (NoticePrice.downloads(stream = false, readAgoMs = readAt[c]?.let { t1 - it }, reuseMs = noticeReuseMs)) {
                    notice++
                    readAt[c] = t1
                }
            }
        }
        return tick to notice
    }

    @Test fun aSessionHoldingTwoContractsDownloadsHalfAsOften() {
        val (tickBefore, noticeBefore) = session(2, noticeReuseMs = 0L)
        val (tickAfter, noticeAfter) = session(2, noticeReuseMs = NoticePrice.REUSE_MS)
        assertEquals(750, tickBefore)
        assertEquals(tickBefore, tickAfter, "the paper tick's reads are untouched")
        assertEquals(750, noticeBefore, "before: every contract downloaded again for the notice, each pass")
        assertEquals(0, noticeAfter)
        assertEquals(1500, tickBefore + noticeBefore)
        assertEquals(750, tickAfter + noticeAfter)
    }

    @Test fun aFailedTickReadIsReadAfreshForTheNotice() {
        val fails = setOf(10, 11, 200)
        val (_, notice) = session(1, noticeReuseMs = NoticePrice.REUSE_MS, tickFails = fails)
        assertEquals(fails.size, notice, "only the passes whose tick kept no fresh price")
    }

    @Test fun theRule() {
        assertFalse(NoticePrice.downloads(stream = true, readAgoMs = null, reuseMs = 0L), "the stream is never a download")
        assertTrue(NoticePrice.downloads(stream = false, readAgoMs = null, reuseMs = NoticePrice.REUSE_MS), "never read")
        assertFalse(NoticePrice.downloads(stream = false, readAgoMs = 0L, reuseMs = NoticePrice.REUSE_MS))
        assertFalse(NoticePrice.downloads(stream = false, readAgoMs = NoticePrice.REUSE_MS, reuseMs = NoticePrice.REUSE_MS))
        assertTrue(NoticePrice.downloads(stream = false, readAgoMs = NoticePrice.REUSE_MS + 1, reuseMs = NoticePrice.REUSE_MS))
        assertTrue(NoticePrice.downloads(stream = false, readAgoMs = -1L, reuseMs = NoticePrice.REUSE_MS), "a clock step back")
        assertTrue(NoticePrice.downloads(stream = false, readAgoMs = 1L, reuseMs = 0L), "no sharing asked: fresh, as the money steps")
        assertEquals(20_000L, NoticePrice.REUSE_MS, "the position cards' own share, never longer than a candle")
    }
}
