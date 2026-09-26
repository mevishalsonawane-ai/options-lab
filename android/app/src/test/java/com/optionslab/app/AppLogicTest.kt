package com.optionslab.app

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.KiteStream
import com.optionslab.app.work.Alerts
import kotlin.test.Test
import kotlin.test.assertEquals

/** The app's own pure logic, on the JVM (no device). */
class AppLogicTest {
    @Test fun alertColours() {
        assertEquals(Alerts.Kind.ERROR, Alerts.classify("Not placed (account guard): the kill switch is on"))
        assertEquals(Alerts.Kind.ERROR, Alerts.classify("Paper order failed: no quote"))
        assertEquals(Alerts.Kind.ERROR, Alerts.classify("Refused: premium 38.00 is at or below 40"))
        assertEquals(Alerts.Kind.SUCCESS, Alerts.classify("Paper BUY 30 BANKNIFTY27OCT2656000CE filled @ 212.40"))
        assertEquals(Alerts.Kind.SUCCESS, Alerts.classify("Redirect URL copied"))
    }

    @Test fun paperAndLiveLimits() {
        val s = AppSettings()
        val live = s.guardLimits()
        val paper = s.guardLimits(paper = true)
        assertEquals(2_000.0, live.maxDailyLoss)
        assertEquals(6_000.0, paper.maxDailyLoss)
        assertEquals(10, live.maxTradesPerDay)
        assertEquals(60, paper.maxTradesPerDay)
        assertEquals(200_000.0, live.maxSymbolExposure)
        assertEquals(14 * 60 + 55, live.entryCutoffMinute)
    }

    @Test fun zerodhaLimitsFollowBotSettings() {
        val l = AppSettings(guardMaxTrades = 0, guardMaxLots = 0, guardMaxValue = 0.0).limits()
        assertEquals(Int.MAX_VALUE, l.maxOrdersPerDay)
        assertEquals(1_000, l.maxLotsPerOrder)
        assertEquals(Double.MAX_VALUE, l.maxOrderValue)
    }

    @Test fun positionMovesWithTheTick() {
        val p = Broker.Position("BANKNIFTY27OCT2656000CE", "MIS", 30, 200.0, 210.0, 300.0, m2m = 300.0, unrealised = 300.0)
        val q = KiteStream.moved(p, 215.0)
        assertEquals(215.0, q.last)
        assertEquals(450.0, q.pnl)          // +5 x 30
        assertEquals(450.0, q.m2m)
        val short = KiteStream.moved(p.copy(qty = -30, pnl = -300.0), 215.0)
        assertEquals(-450.0, short.pnl)
        assertEquals(p, KiteStream.moved(p.copy(last = 0.0), 215.0).copy(last = 210.0))   // no base price: unchanged
    }
}
