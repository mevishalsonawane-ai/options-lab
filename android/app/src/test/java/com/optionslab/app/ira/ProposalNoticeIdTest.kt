package com.optionslab.app.ira

import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 18 Oct, review: a strategy proposal's notification id was 7300 + its id, which grows without end - it ran into the
 * trend watch's, the pop-ups' and then the news-trade approvals' ids, replacing an approval card (its Approve and Reject
 * buttons). The id now stays in 9600-9649, which nothing else uses.
 */
class ProposalNoticeIdTest : RobolectricTest() {
    @Test fun proposalNoticesStayInTheirOwnRange() {
        assertEquals(9601, IraHub.proposalNoticeId(1))
        assertEquals(9600, IraHub.proposalNoticeId(0))
        assertEquals(9649, IraHub.proposalNoticeId(49))
        assertEquals(9600, IraHub.proposalNoticeId(50))
        // Ids long past the old overlap (100+ proposals: the pop-ups; 200+: the approvals) and the edges of a Long.
        for (id in listOf(100L, 150L, 200L, 289L, 10_000L, Int.MAX_VALUE.toLong() + 7, Long.MAX_VALUE, -1L, Long.MIN_VALUE)) {
            val n = IraHub.proposalNoticeId(id)
            assertTrue("$id -> $n", n in 9600..9649)
            // Never the pop-ups' (7400-7449), the approvals' (7500-7589), the trend watch's (7300-7499) or the paper
            // settle / square-off notices' (7000-8023).
            assertTrue("$id -> $n", n !in 7000..8023)
        }
    }
}
