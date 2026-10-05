package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PassShareTest {
    private var t = 1_000L
    private val share = PassShare<String>(20_000) { t }

    /** A counting reader: each call is one "network read", returning a new value. */
    private var reads = 0
    private fun read(): String { reads++; return "book$reads" }

    @Test fun onePassReadsOnce() {
        share.open()
        // Five watches in one pass: one read, all see the same book.
        val seen = (1..5).map { t += 1_000; share.share { read() } }
        assertEquals(1, reads)
        assertEquals(List(5) { "book1" }, seen)
    }

    @Test fun outsideAPassEveryReadIsFresh() {
        assertEquals("book1", share.share { read() })
        assertEquals("book2", share.share { read() })
        assertEquals(2, reads)
        share.open(); share.share { read() }; share.close()
        assertEquals("book4", share.share { read() })
    }

    @Test fun eachPassStartsFresh() {
        share.open(); share.share { read() }; share.close()
        share.open()
        assertEquals("book2", share.share { read() })
        assertEquals("book2", share.share { read() })
    }

    @Test fun tooOldIsReadAgain() {
        share.open()
        share.share { read() }
        t += 20_000
        assertEquals("book1", share.share { read() })
        t += 1
        assertEquals("book2", share.share { read() })
        // A clock that went back is not trusted either.
        t -= 60_000
        assertEquals("book3", share.share { read() })
    }

    @Test fun aWriteDropsIt() {
        share.open()
        share.share { read() }
        share.drop()
        assertEquals("book2", share.share { read() })
        assertEquals("book2", share.share { read() })
    }

    @Test fun aReadStartedBeforeAWriteIsNotKept() {
        share.open()
        // An order is placed while the read is on the wire: its (maybe older) answer is given to its caller only.
        assertEquals("book1", share.share { read().also { share.drop() } })
        assertNull(share.get())
        assertEquals("book2", share.share { read() })
        // Same if the pass ended mid-read.
        share.drop()
        share.share { read().also { share.close() } }
        assertNull(share.get())
    }

    @Test fun aFailedReadIsNotKept() {
        share.open()
        assertFailsWith<IllegalStateException> { share.share { error("no answer") } }
        assertNull(share.get())
        assertEquals("book1", share.share { read() })
    }

    @Test fun aFreshReadElsewhereFeedsThePass() {
        share.open()
        // A safety read (always fresh) put in by the broker itself; the words-only watch after it reuses it.
        val tk = share.ticket(); val at = share.now()
        share.put(tk, at, read())
        assertEquals("book1", share.share { read() })
        assertEquals(1, reads)
    }
}
