package com.optionslab.ira

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsDedupTest {
    private val t0 = Instant.parse("2026-10-06T04:00:00Z")
    private fun h(title: String, link: String, source: String = "ET") = Headline(title, link, source, t0, 0.7, listOf(Market.NIFTY))

    @Test fun theSameLinkIsToldOnceEvenWithTrackingParts() {
        val d = NewsDedup()
        assertTrue(d.firstTime(h("RBI holds repo rate at 6.5%", "https://economictimes.com/markets/rbi-holds/123.cms"), t0))
        assertFalse(d.firstTime(h("RBI holds repo rate at 6.5% - live", "http://www.economictimes.com/markets/rbi-holds/123.cms?utm_source=rss#top"), t0))
        assertFalse(d.firstTime(h("RBI policy update", "https://economictimes.com/markets/rbi-holds/123.cms/amp"), t0))
    }

    @Test fun anotherSitesCopyOrARewordedUpdateIsTheSameStory() {
        val d = NewsDedup()
        assertTrue(d.firstTime(h("HDFC Bank Q2 profit rises 18%, beats estimates", "https://a.com/1"), t0))
        assertFalse(d.firstTime(h("HDFC Bank Q2 profit rises 18% and beats estimates", "https://b.com/2", "Moneycontrol"), t0.plusSeconds(600)))
        assertFalse(d.firstTime(h("HDFC Bank's Q2 profit rises 18%, beats Street estimates", "https://c.com/3", "Mint"), t0.plusSeconds(1200)))
    }

    @Test fun aDifferentStoryOnTheSameSubjectIsNew() {
        val d = NewsDedup()
        assertTrue(d.firstTime(h("Nifty rises 1% on bank gains", "https://a.com/1"), t0))
        assertTrue(d.firstTime(h("Nifty falls 1% on bank losses", "https://a.com/2"), t0))
        assertTrue(d.firstTime(h("RBI holds rates", "https://a.com/3"), t0))
        assertTrue(d.firstTime(h("RBI cuts rates", "https://a.com/4"), t0), "short titles need the exact words")
    }

    @Test fun aRewordedCopyCountsOnlyWithinTwelveHours() {
        val d = NewsDedup()
        assertTrue(d.firstTime(h("Crude oil jumps 3% after OPEC output cut", "https://a.com/1"), t0))
        assertFalse(d.firstTime(h("Crude oil jumps 3% after OPEC output cut", "https://b.com/9"), t0.plusSeconds(11 * 3600)))
        assertTrue(d.firstTime(h("Crude oil jumps 3% after OPEC output cut", "https://c.com/7"), t0.plusSeconds(24 * 3600)))
    }

    @Test fun keysAndLimits() {
        assertNull(NewsDedup.linkKey("  "))
        assertNull(NewsDedup.linkKey("https://"))
        assertEquals("site.com/a/b", NewsDedup.linkKey("HTTPS://m.site.com/a/b/"))
        assertEquals("site.com/a/b", NewsDedup.linkKey("https://site.com/a/amp/b?x=1"))
        assertEquals("site.com/a/b", NewsDedup.linkKey("https://site.com/a/b.amp"))
        assertEquals(setOf("rbi", "holds", "rate"), NewsDedup.titleWords("The RBI holds the rate!"))
        assertFalse(NewsDedup.same(emptySet(), setOf("a")))
        val d = NewsDedup(max = 2)
        assertTrue(d.firstTime(h("", "https://a.com/1"), t0))
        assertTrue(d.firstTime(h("", "https://a.com/2"), t0))
        assertTrue(d.firstTime(h("", "https://a.com/3"), t0))
        assertEquals(2, d.size)
        assertTrue(d.firstTime(h("", "https://a.com/1"), t0), "the oldest was dropped")
        assertTrue(d.firstTime(h("Only a title, no link", ""), t0))
        assertFalse(d.firstTime(h("Only a title, no link", ""), t0))
    }
}
