package com.optionslab.app.ira

import com.optionslab.app.data.Broker
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 18 Oct, review: a live Jarvis trade's profit-lock stop was re-set with the order product as it is NOW, not the one the
 * trade was bought with - an MIS buy re-protected as NRML after a switch would open a new short and leave the buy open.
 * The trade now keeps its product, and its stop is re-set with that one.
 */
class IraNewsTradesProductTest : RobolectricTest() {
    private fun pos(product: String?) = IraNewsTrades.Pos("NIFTY26OCT25000CE", true, 100.0, 75, 100.0, 70.0, "news: x", "2026-10-16",
        target = IraNewsTrades.TARGET_POINTS, product = product)
    private fun row(symbol: String, product: String, qty: Int) = Broker.Position(symbol, product, qty, 100.0, 110.0, 0.0)

    @Test fun theStopIsReSetWithTheProductTheTradeWasBoughtWith() {
        // Bought MIS; the setting is NRML now: MIS.
        assertEquals("MIS", IraNewsTrades.productFor(pos("MIS"), null, "NRML"))
        assertEquals("MIS", IraNewsTrades.productFor(pos("MIS"), listOf(row("NIFTY26OCT25000CE", "NRML", 75)), "NRML"))
        assertEquals("NRML", IraNewsTrades.productFor(pos("NRML"), null, "MIS"))
    }

    @Test fun anOlderRecordTakesTheProductTheBrokerHoldsItIn() {
        // Recorded before the product was kept: the position book's product for that symbol.
        assertEquals("MIS", IraNewsTrades.productFor(pos(null), listOf(row("NIFTY26OCT25000CE", "MIS", 75), row("BANKNIFTY26OCT55000PE", "NRML", 30)), "NRML"))
        // Blank counts as not kept.
        assertEquals("MIS", IraNewsTrades.productFor(pos(""), listOf(row("NIFTY26OCT25000CE", "MIS", 75)), "NRML"))
        // A closed row (qty 0) is not the holding.
        assertEquals("NRML", IraNewsTrades.productFor(pos(null), listOf(row("NIFTY26OCT25000CE", "MIS", 0)), "NRML"))
        // Held in both products: not guessed - the setting, as before.
        assertEquals("NRML", IraNewsTrades.productFor(pos(null),
            listOf(row("NIFTY26OCT25000CE", "MIS", 75), row("NIFTY26OCT25000CE", "NRML", 75)), "NRML"))
        // No position book (Zerodha slow or failed) and nothing kept: the setting, as before.
        assertEquals("NRML", IraNewsTrades.productFor(pos(null), null, "NRML"))
        assertEquals("NRML", IraNewsTrades.productFor(pos(null), emptyList(), "NRML"))
    }

    @Test fun theProductIsKeptAndReadBack() {
        IraNewsTrades.save(listOf(pos("MIS"), pos(null).copy(symbol = "NIFTY26OCT25100CE", live = false)))
        val back = IraNewsTrades.all()
        assertEquals(2, back.size)
        assertEquals("MIS", back[0].product)
        assertNull(back[1].product)
        assertEquals(pos("MIS"), back[0])
    }

    @Test fun aTradeSavedBeforeTheProductWasKeptStillReads() {
        SecurePrefs.put("jarvis.newstrades",
            """[{"s":"NIFTY26OCT25000CE","l":true,"e":100.0,"q":75,"p":104.0,"st":70.0,"h":"news: x","d":"2026-10-15","c":false,"tg":60.0}]""")
        val p = IraNewsTrades.all().single()
        assertEquals("NIFTY26OCT25000CE", p.symbol)
        assertNull(p.product)
        assertEquals("MIS", IraNewsTrades.productFor(p, listOf(row("NIFTY26OCT25000CE", "MIS", 75)), "NRML"))
    }
}
