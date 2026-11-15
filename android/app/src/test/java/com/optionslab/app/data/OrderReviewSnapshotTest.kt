package com.optionslab.app.data

import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.OrderReview
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * 15 Nov (Boss's diagnostics: the day's live orders were gone after a restart): the day's live orders kept by the order review's
 * host in a sealed file a day ([OrderReviewHost.snapshotOrders]), read back with memory by [OrderReviewHost.ordersIn] (the
 * review's and the day's diagnostics' orders), old days pruned, Erase everything deleting them. In every build but IraGoldAlgo
 * (the diagnostics read them too, with or without Jarvis). Made-up orders; no network.
 */
class OrderReviewSnapshotTest : RobolectricTest() {
    private fun ms(d: LocalDate): Long = d.atStartOfDay(com.optionslab.engine.IST).toInstant().toEpochMilli()

    @Before fun up() {
        assumeFalse("the live orders are not kept in IraGoldAlgo", com.optionslab.app.BuildConfig.GOLD)
        OrderReviewHost.resetForTest()
        AppState.resetForTest()
    }

    @After fun down() {
        OrderReviewHost.resetForTest()
        AppState.resetForTest()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private val td: LocalDate get() = Market.today()

    private fun row(id: String, d: LocalDate, status: String, filled: Int = 0) = Broker.OrderRow(id = id, symbol = "BANKNIFTY26OCT55000CE", side = "BUY",
        qty = 30, filled = filled, price = 100.0, avg = if (filled > 0) 100.5 else 0.0, status = status, message = "", placedAt = "$d 10:0$id:00")

    private fun liveIn(d: LocalDate): List<OrderReview.Order> =
        OrderReviewHost.ordersIn(emptyMap(), ms(d), ms(d.plusDays(1)) - 1).filter { it.venue == OrderReview.Venue.LIVE }

    private fun snapFile(d: LocalDate) = java.io.File(context.noBackupFilesDir, "orderreview/orders-$d.vault")

    @Test fun theDaysLiveOrdersAreKeptSealedAndReadAfterARestart() {
        // Empty: nothing kept, no file.
        assertEquals(0, OrderReviewHost.snapshotOrders(emptyList()))
        assertTrue(liveIn(td).isEmpty())
        assertFalse(snapFile(td).exists())
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(row("1", td, "OPEN"), row("2", td, "COMPLETE", 30))))
        // Sealed with the vault's key: no symbol or status in plain text.
        val raw = String(snapFile(td).readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("BANKNIFTY") || raw.contains("COMPLETE"))
        // The same book again writes nothing.
        assertEquals(0, OrderReviewHost.snapshotOrders(listOf(row("1", td, "OPEN"), row("2", td, "COMPLETE", 30))))
        // A restart (a low-memory kill): nothing in memory, the broker's book not read yet.
        OrderReviewHost.reloadForTest()
        AppState.resetForTest()
        val got = liveIn(td)
        assertEquals(listOf("kite:1", "kite:2"), got.map { it.id }.sorted())
        assertEquals("COMPLETE", got.first { it.id == "kite:2" }.status)
        // Another day reads none of them.
        assertTrue(liveIn(td.minusDays(1)).isEmpty())
    }

    @Test fun memoryAndTheKeptOrdersMergeByIdTheNewestStateWins() {
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(row("1", td, "OPEN"), row("2", td, "OPEN"))))
        OrderReviewHost.reloadForTest()
        // After the restart the broker's book shows order 1 filled and a new order 3 (order 2 is not in this read).
        AppState.publishOrders(listOf(row("1", td, "COMPLETE", 30), row("3", td, "OPEN")), System.currentTimeMillis())
        val got = liveIn(td)
        assertEquals(listOf("kite:1", "kite:2", "kite:3"), got.map { it.id }.sorted())
        assertEquals("COMPLETE", got.single { it.id == "kite:1" }.status)
        // Kept: order 1's newest state, order 2 still there.
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(row("1", td, "COMPLETE", 30), row("3", td, "OPEN"))))
        OrderReviewHost.reloadForTest()
        AppState.resetForTest()
        val kept = liveIn(td)
        assertEquals(listOf("kite:1", "kite:2", "kite:3"), kept.map { it.id }.sorted())
        assertEquals("COMPLETE", kept.single { it.id == "kite:1" }.status)
    }

    @Test fun oldDaysArePrunedAndADamagedFileIsNeverAnError() {
        val old = td.minusDays(OrderReviewHost.SNAP_KEEP_DAYS + 3)
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(row("1", old, "COMPLETE", 30)), today = old))
        assertTrue(snapFile(old).exists())
        val junk = java.io.File(context.noBackupFilesDir, "orderreview/orders-junk.vault").apply { writeText("x") }
        // A damaged day: garbage where the sealed file should be.
        val bad = td.minusDays(1)
        snapFile(bad).writeBytes(ByteArray(64) { it.toByte() })
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(row("2", td, "OPEN"))))
        assertFalse("older than the days kept: deleted", snapFile(old).exists())
        assertFalse(junk.exists())
        assertTrue(snapFile(td).exists())
        // The damaged day reads as no orders, never an error.
        assertTrue(liveIn(bad).isEmpty())
    }

    @Test fun eraseEverythingDeletesTheKeptOrders() {
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(row("1", td, "OPEN"))))
        liveIn(td)
        assertTrue(OrderReviewHost.holdsForErase())
        assertTrue(OrderReviewHost.filesForErase().any { it.name == "orders-$td.vault" && it.exists() })
        OrderReviewHost.wipe()
        assertFalse(snapFile(td).exists())
        assertFalse(OrderReviewHost.holdsForErase())
        assertTrue(OrderReviewHost.filesForErase().none { it.exists() })
        assertTrue(liveIn(td).isEmpty())
    }

    @Test fun theWatcherKeepsTheBookTheBrokerReadsOffTheCallersThread() {
        OrderReviewHost.snapshotsOn = true
        // As the broker's read does: published into memory; the watcher (its own thread) writes the day's file.
        AppState.publishOrders(listOf(row("1", td, "OPEN")), System.currentTimeMillis())
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && !snapFile(td).exists()) Thread.sleep(20)
        assertTrue("the watcher kept the day's live orders", snapFile(td).exists())
        OrderReviewHost.snapshotsOn = false
        OrderReviewHost.reloadForTest()
        AppState.resetForTest()
        assertEquals(listOf("kite:1"), liveIn(td).map { it.id })
    }

    /**
     * A kept order is named as a live one is ([ordersIn]: the owners' book, else [ArmOrderLabels]): after a restart, a REJECTED
     * order whose id never reached the phone is still named by its arm's refused try (the exchange and product are kept for it).
     */
    @Test fun aKeptRejectedOrderIsNamedByItsArmsRefusedTry() {
        val at = td.atTime(10, 0)
        val placed = at.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val rejected = Broker.OrderRow("301", "NIFTY26NOV25000CE", "BUY", 75, 0, 0.0, 0.0, "REJECTED", "Insufficient funds", placed, "NFO", "MIS", "MARKET", tag = "iraarm")
        assertEquals(1, OrderReviewHost.snapshotOrders(listOf(rejected)))
        OrderReviewHost.reloadForTest()
        AppState.resetForTest()
        val keepWas = ArmOrderLabels.keep
        ArmOrderLabels.resetForTest()
        ArmOrderLabels.keep = { _, _ -> }
        try {
            val atMs = at.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()
            val ticket = ArmOrderLabels.sending(com.optionslab.engine.Kite.Order("NIFTY26NOV25000CE", com.optionslab.engine.Kite.Side.BUY, 75, 75, "MIS", "MARKET",
                null, 0.05, "NFO", "iraarm"), "R72 · live entry", atMs)
            ArmOrderLabels.refused(ticket)
            val o = liveIn(td).single()
            assertEquals("kite:301", o.id)
            assertEquals(com.optionslab.ira.Charges.owner("R72 · live entry"), o.strategy)
        } finally {
            ArmOrderLabels.keep = keepWas
            ArmOrderLabels.resetForTest()
        }
    }
}
