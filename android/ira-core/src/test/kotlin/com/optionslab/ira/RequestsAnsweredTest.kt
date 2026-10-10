package com.optionslab.ira

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An answered request opened from Recent (Boss, 6 Oct): the same banner with its details - words only, no Yes or No. */
class RequestsAnsweredTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, 6, h, m, s).atZone(ist).toInstant().toEpochMilli()

    private val trade = Requests.RequestView(1, Requests.Kind.TRADE, "buy 1 lot NIFTY 25000 CE", "buy 1 lot NIFTY 25000 CE at market",
        "Strong results lift the index.", Requests.Venue.ZERODHA, at(10, 30), at(10, 40), symbol = "NIFTY 25000 CE", qty = "75",
        price = "120.5", details = "Risk 1,200. IV is low. Confidence 4 out of 5.")
    private val kill = Requests.RequestView(2, Requests.Kind.COMMAND, "Hit the panic button", "hit the panic button", null,
        Requests.Venue.NONE, at(11, 0), at(11, 30))

    @Test fun anApprovedTradeSaysEverything() {
        val r = Requests.Recent(trade, Requests.Outcome.APPROVED, at(10, 32, 5), Requests.By.FINGERPRINT,
            "Bought 75 NIFTY 25000 CE at 120.50 on Zerodha.", fingerprint = true)
        assertEquals("Approved: Buy 1 lot NIFTY 25000 CE", Requests.answeredTitle(r))
        assertEquals(listOf(
            "Trade idea: Buy 1 lot NIFTY 25000 CE at market.",
            "Venue: Zerodha · real money",
            "Fingerprint: needed for a yes",
            "Symbol: NIFTY 25000 CE · Qty: 75 · Price: 120.5",
            "Why: Strong results lift the index.",
            "Details: Risk 1,200. IV is low. Confidence 4 out of 5.",
            "Asked: Tue 6 Oct, 10:30:00",
            "Approved: Tue 6 Oct, 10:32:05 (by tap, with your fingerprint)",
            "Result: Bought 75 NIFTY 25000 CE at 120.50 on Zerodha.",
        ), Requests.answeredLines(r, ist))
    }

    @Test fun aRejectedOneSaysRejectedAndHow() {
        val r = Requests.Recent(kill, Requests.Outcome.DECLINED, at(11, 2), Requests.By.VOICE, "Cancelled; nothing was done.", fingerprint = false)
        assertEquals("Rejected: Hit the panic button", Requests.answeredTitle(r))
        val lines = Requests.answeredLines(r, ist)
        assertEquals("Command: Hit the panic button.", lines[0])
        assertTrue("Venue: No order" in lines)
        assertTrue("Fingerprint: not needed" in lines)
        assertTrue("Asked: Tue 6 Oct, 11:00:00" in lines)
        assertTrue("Rejected: Tue 6 Oct, 11:02:00 (by voice)" in lines)
        assertEquals("Result: Cancelled; nothing was done.", lines.last())
        // Nothing absent is made up: no figures, no why, no details.
        assertFalse(lines.any { it.startsWith("Why:") || it.startsWith("Details:") || it.startsWith("Symbol:") })
    }

    @Test fun anExpiredOneAndAFailedOneAndUnknownHow() {
        val lapsed = Requests.Recent(kill, Requests.Outcome.LAPSED, at(11, 30))
        assertEquals("Expired: Hit the panic button", Requests.answeredTitle(lapsed))
        val l = Requests.answeredLines(lapsed, ist)
        assertTrue("Expired: Tue 6 Oct, 11:30:00 - no answer came, so nothing was done" in l)
        assertFalse(l.any { it.startsWith("Result:") })
        // How it was answered is said only when known.
        val tapped = Requests.answeredLines(Requests.Recent(kill, Requests.Outcome.APPROVED, at(11, 5)), ist)
        assertTrue("Approved: Tue 6 Oct, 11:05:00" in tapped)
        val failed = Requests.Recent(trade, Requests.Outcome.FAILED, at(10, 33), Requests.By.NOTIFICATION, "Zerodha rejected the order: margin.")
        assertEquals("Approved, but it failed: Buy 1 lot NIFTY 25000 CE", Requests.answeredTitle(failed))
        val f = Requests.answeredLines(failed, ist)
        assertTrue("Approved, but it failed: Tue 6 Oct, 10:33:00 (from the notification)" in f)
        // No fingerprint recorded: the view's own flag (false here).
        assertTrue("Fingerprint: not needed" in f)
        assertEquals("Result: Zerodha rejected the order: margin.", f.last())
    }

    @Test fun theRecordNeverAsksForAYes() {
        val r = Requests.Recent(trade, Requests.Outcome.APPROVED, at(10, 32), Requests.By.TAP, "Done.")
        val all = (listOf(Requests.answeredTitle(r)) + Requests.answeredLines(r, ist) + Requests.ANSWERED_NOTE).joinToString("\n")
        assertFalse(Regex("Yes or no\\?|Approve or reject\\.|Tap Confirm|Yes, approve|No, reject").containsMatchIn(all), all)
    }
}
