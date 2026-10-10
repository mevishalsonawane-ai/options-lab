package com.optionslab.app.data

import android.app.Application
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.OfflineModel
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Alerts
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock

/** Hand orders on the paper account follow the exchange's hours: refused with "Market is closed now" outside them. */
class PaperMarketHoursTest : RobolectricTest() {
    private var offline: OfflineModel? = null
    private val model get() = (offline ?: OfflineModel(context as Application).also { offline = it }).model

    @Before fun up() { AreaE.resetGlobals(); Market.testOrdersAnyTime = false }

    @After fun down() {
        offline?.close()
        Market.testOrdersAnyTime = true
        Market.testClock = null
        AreaE.resetGlobals()
    }

    private fun at(h: Int, m: Int) {
        val t = AutomationSupport.tradingDayAt(h, m)
        Market.testClock = Clock.fixed(t.toInstant(), IST)
    }

    private fun refused() = Alerts.posted.any { it.text == Market.CLOSED_FOR_ORDERS && it.kind == Alerts.Kind.ERROR }

    @Test fun aHandOrderAfterTheCloseIsRefused() {
        at(20, 9)
        assertFalse(Market.acceptsOrders())
        model.paperPlace("BANKNIFTY", Market.today().plusDays(1), 54_100.0, Right.PE, "BUY", 1, "MARKET", "NRML", null, null)
        assertTrue("told the market is closed", refused())
        assertTrue("nothing reached the paper book", Paper.state.orders.isEmpty())
    }

    @Test fun closingAPositionAfterTheCloseIsRefusedToo() {
        at(8, 0)
        model.paperClose("BANKNIFTY26SEP54100PE", "NRML")
        assertTrue(refused())
    }

    @Test fun cancellingAWorkingOrderIsAlwaysAllowed() {
        at(20, 9)
        model.paperCancel("no-such-order")
        assertFalse("a cancel is never refused for the hour", refused())
    }

    private fun on(day: java.time.LocalDate, h: Int, m: Int) {
        Market.testClock = Clock.fixed(day.atTime(h, m).atZone(IST).toInstant(), IST)
    }

    /** F&O trades to 15:40 from 3 Aug 2026: a hand order at 15:35 is taken on such a day. */
    @Test fun at1535AfterThe3Aug2026ChangeOrdersAreTaken() {
        on(java.time.LocalDate.of(2026, 8, 5), 15, 35)
        assertTrue(Market.acceptsOrders())
        model.paperPlace("BANKNIFTY", Market.today().plusDays(1), 54_100.0, Right.PE, "BUY", 1, "MARKET", "NRML", null, null)
        assertFalse(refused())
    }

    /** Before 3 Aug 2026 F&O closed at 15:30: the same order at 15:35 on a July day is refused. */
    @Test fun at1535OnAJuly2026DayOrdersAreRefused() {
        on(java.time.LocalDate.of(2026, 7, 29), 15, 35)
        assertFalse(Market.acceptsOrders())
        model.paperPlace("BANKNIFTY", Market.today().plusDays(1), 54_100.0, Right.PE, "BUY", 1, "MARKET", "NRML", null, null)
        assertTrue("told the market is closed", refused())
        assertTrue(Market.CLOSED_FOR_ORDERS.contains("09:15-15:30"))
        assertTrue("nothing reached the paper book", Paper.state.orders.isEmpty())
    }

    @Test fun inMarketHoursOrdersAreTaken() {
        at(11, 0)
        assertTrue(Market.acceptsOrders())
        model.paperPlace("BANKNIFTY", Market.today().plusDays(1), 54_100.0, Right.PE, "BUY", 1, "MARKET", "NRML", null, null)
        assertFalse(refused())
    }
}
