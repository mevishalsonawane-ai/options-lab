package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.TradeManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A fast lane's look at the trade manager never waits (review R4 #9): while another look is evaluating, [TradeManagerHost.onLook]
 * skips instead of blocking - the live lane's exits are never held behind it.
 */
class TradeManagerLookTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L

    private fun trade() = TradeManager.ManagedTrade("pine:1", "pine:1:x", "Pine · Level", "NIFTY", 1, "NIFTY25OCT25000CE",
        100.0, 75, 70.0, 160.0, TradeManager.Caps(t0 + 3 * 3_600_000L), TradeManager.Account.PAPER, t0, 1.0)

    @Before fun up() {
        SmartWorkers.resetForTest()
        TradeManagerHost.resetForTest()
        TradeManagerHost.testNoWake = true
        TradeManagerHost.testBars = { emptyList() }
    }

    @After fun down() { TradeManagerHost.resetForTest() }

    @Test fun aLookWhileAnotherIsEvaluatingSkipsInsteadOfWaiting() {
        TradeManagerHost.attach(TradeManagerHost.Registration(trade(), premium = { 120.0 }))
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        TradeManagerHost.testSnapshot = { _, at ->
            inside.countDown()
            release.await(20, TimeUnit.SECONDS)
            TradeManager.Snapshot(at, 120.0)
        }
        val slow = Thread { TradeManagerHost.evaluate(t0) }.also { it.start() }
        try {
            assertTrue("the slow look is evaluating", inside.await(10, TimeUnit.SECONDS))
            val started = System.nanoTime()
            TradeManagerHost.onLook()
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("onLook waited $tookMs ms behind another look", tookMs < 2_000)
        } finally {
            release.countDown()
            slow.join(20_000)
        }
    }
}
