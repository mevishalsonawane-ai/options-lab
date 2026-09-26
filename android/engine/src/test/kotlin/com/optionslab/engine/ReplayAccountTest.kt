package com.optionslab.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class ReplayAccountTest {
    @Test fun longThenPartialCloseThenFlip() {
        val a = ReplayAccount()
        a.trade(560, 1, 50, 100.0)
        a.trade(561, 1, 50, 110.0)
        assertEquals(100, a.position); assertEquals(105.0, a.average, 1e-9)
        assertEquals(500.0, a.unrealised(110.0), 1e-9)
        a.trade(562, -1, 40, 120.0)                       // books (120-105) x 40
        assertEquals(600.0, a.realised, 1e-9); assertEquals(60, a.position)
        a.trade(563, -1, 80, 90.0, cost = 20.0)           // closes 60 at a loss, opens 20 short at 90
        assertEquals(600.0 - 15.0 * 60, a.realised, 1e-9)
        assertEquals(-20, a.position); assertEquals(90.0, a.average, 1e-9)
        assertEquals(200.0, a.unrealised(80.0), 1e-9)
        assertEquals(a.realised + 200.0 - 20.0, a.net(80.0), 1e-9)
    }

    @Test fun shortCoveredFlat() {
        val a = ReplayAccount()
        a.trade(600, -1, 25, 200.0)
        a.trade(601, 1, 25, 180.0)
        assertEquals(0, a.position); assertEquals(500.0, a.realised, 1e-9); assertEquals(0.0, a.unrealised(1.0), 1e-9)
        a.reset(); assertEquals(0, a.fills.size)
    }
}
