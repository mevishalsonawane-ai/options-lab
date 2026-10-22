package com.optionslab.engine.risk

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The order fingerprint (22 Oct): the same order hashes the same, any field changed hashes differently and is named. */
class OrderFingerprintTest {
    private val day = LocalDate.of(2026, 10, 22)
    private val f = OrderFingerprint.Fields("BANKNIFTY26OCT52000CE", "BUY", 30, "MIS", "LIMIT", 200.0, null, 160.0, 240.0, day, true, "arm:orb")

    @Test fun theSameOrderHashesTheSameAndPasses() {
        val v = OrderFingerprint.Verdict(f, passed = true, by = "guard")
        assertEquals(16, v.fingerprint.length)
        assertEquals(v.fingerprint, f.copy().fingerprint())
        assertNull(OrderFingerprint.refusal(v, f))
        // Case and spaces in the words do not matter; a price on the paise does not either.
        assertNull(OrderFingerprint.refusal(v, f.copy(symbol = " banknifty26oct52000ce ", side = "buy", product = "mis", orderType = "limit", price = 200.001)))
        assertEquals("BANKNIFTY26OCT52000CE|BUY|30|MIS|LIMIT|200.00|-|160.00|240.00|2026-10-22|live|arm:orb", f.canonical())
    }

    @Test fun everyFieldChangedIsNamed() {
        val v = OrderFingerprint.Verdict(f, passed = true)
        val changes = listOf(
            f.copy(symbol = "BANKNIFTY26OCT52100CE") to "symbol BANKNIFTY26OCT52000CE → BANKNIFTY26OCT52100CE",
            f.copy(side = "SELL") to "side BUY → SELL",
            f.copy(qty = 60) to "quantity 30 → 60",
            f.copy(product = "NRML") to "product MIS → NRML",
            f.copy(orderType = "MARKET", price = null) to "order type LIMIT → MARKET",
            f.copy(price = 201.0) to "price 200.00 → 201.00",
            f.copy(trigger = 150.0) to "trigger none → 150.00",
            f.copy(stop = null) to "stop 160.00 → none",
            f.copy(target = 300.0) to "target 240.00 → 300.00",
            f.copy(live = false) to "account live → paper",
            f.copy(source = "manual") to "source arm:orb → manual",
        )
        for ((now, words) in changes) {
            assertNotEquals(v.fingerprint, now.fingerprint(), words)
            val why = OrderFingerprint.refusal(v, now)!!
            assertTrue(why.startsWith("the order changed after its check: "), why)
            assertTrue(words in why, "$why should name: $words")
        }
        assertEquals(listOf("order type LIMIT → MARKET", "price 200.00 → none"), OrderFingerprint.diff(f, f.copy(orderType = "MARKET", price = null)))
        assertEquals(emptyList(), OrderFingerprint.diff(f, f))
    }

    @Test fun aVerdictFromAnotherDayOrNoneOrARefusalNeverClears() {
        val old = OrderFingerprint.Verdict(f.copy(day = day.minusDays(1)), passed = true)
        assertEquals("the check is from 2026-10-21, not today (2026-10-22): it must be run again", OrderFingerprint.refusal(old, f))
        assertEquals("no check cleared this order (nothing was stamped for it)", OrderFingerprint.refusal(null, f))
        assertEquals("the check refused this order: Daily loss limit reached.", OrderFingerprint.refusal(OrderFingerprint.Verdict(f, false, listOf("Daily loss limit reached.")), f))
        assertEquals("the check refused this order", OrderFingerprint.refusal(OrderFingerprint.Verdict(f, false), f))
        // The day's check wins over the fields: a stale verdict is said as stale, whatever else changed.
        assertTrue(OrderFingerprint.refusal(old, f.copy(qty = 60))!!.startsWith("the check is from"))
    }

    @Test fun aPriceThatIsNotANumberNeverMatchesARealOne() {
        val v = OrderFingerprint.Verdict(f.copy(price = Double.NaN), passed = true)
        assertNotEquals(v.fingerprint, f.fingerprint())
        val why = OrderFingerprint.refusal(v, f)!!
        assertTrue("price ? → 200.00" in why, why)
        assertTrue("price none → 200.00" in OrderFingerprint.refusal(OrderFingerprint.Verdict(f.copy(price = null), true), f)!!)
    }
}
