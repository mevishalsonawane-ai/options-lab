package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Outcome
import com.optionslab.app.testing.FakeKite.Reply
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime

/**
 * The ORB arms in Live ([OrbArms]) against the fake Kite: an approved entry buys one lot and rests its stop at
 * Zerodha; a buy whose reply was lost is held as unconfirmed and settled from the order book; a partial exit
 * books what sold and sells the rest next pass; one arm's exit never cancels the other arm's stop; and a phone
 * that failed the security check sends nothing. The arms' state is written as the app saves it, and the
 * clock is fixed ([OrbArms.testNow]) so the session-end rules do not depend on when the tests run.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrbArmsLiveTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val paperCe = "BANKNIFTY-TEST-52000CE"
    private val paperPe = "BANKNIFTY-TEST-52000PE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private lateinit var now: LocalDateTime

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_002, "BANKNIFTY26OCT52000PE", "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        kite.requests.clear()
        val t = AutomationSupport.earlierToday()
        OrbArms.testNow = t
        now = t.toLocalDateTime().withNano(0)
    }

    @After fun down() {
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    // ---- the arms' saved state -------------------------------------------------------------------

    private fun contract(symbol: String, right: String) =
        JSONArray().put(symbol).put("BANKNIFTY").put(expiry.toString()).put(52_000.0).put(right).put(30).put("NSE_FO|TEST$right")

    /** Minutes before [now], but never yesterday (a run just after midnight IST would otherwise hold yesterday's positions). */
    private fun ago(minutes: Long) = maxOf(now.minusMinutes(minutes), now.toLocalDate().atStartOfDay())

    private fun position(arm: String, qty: Int, entry: Double, entryId: String?, stopId: String? = null, stop: Double? = null) =
        JSONObject().put("arm", arm).put("symbol", paperCe).put("right", "CE").put("qty", qty).put("entry", entry)
            .put("entryTime", ago(30).toString()).put("signalBar", ago(35).toString())
            .put("entryOrderId", entryId ?: "").put("stopOrderId", stopId ?: "").apply { stop?.let { put("stopTrigger", it) } }
            .put("exitTime", "").put("why", "").put("charges", 0.0).put("live", true).put("kite", sym).put("unconfirmed", false)

    private fun state(armed: Boolean = true, pending: Boolean = false, expires: LocalDateTime = now.plusMinutes(9), positions: List<JSONObject> = emptyList()) {
        val o = JSONObject()
            .put("armed", JSONObject().put("orb", armed)).put("auto", JSONObject().put("orb", true)).put("liveOk", JSONObject().put("orb", armed))
            .put("legs", JSONObject().put("day", now.toLocalDate().toString()).put("strike", 52_000).put("expiry", expiry.toString())
                .put("ce", contract(paperCe, "CE")).put("pe", contract(paperPe, "PE")))
            .put("positions", JSONArray(positions))
        if (pending) o.put("pending", JSONObject().put("orb", JSONObject().put("right", "CE").put("bar", ago(1).toString()).put("expires", expires.toString())))
        AutomationSupport.orbState(context, o)
    }

    private fun arm(source: String = "orb") = runBlocking { OrbArms.view() }.arms.single { it.arm.source == source }
    private fun approve(pin: Boolean = true) = runBlocking { OrbArms.approve("orb", pinConfirmed = pin) }
    private fun pass() = runBlocking { OrbArms.priceCheckOnly() }
    private fun stopOrder(qty: Int, trigger: Double, limit: Double) = runBlocking {
        Broker.placeOrder(Kite.Order(sym, Kite.Side.SELL, qty, 30, "MIS", "SL", limit, 0.05, "NFO", "iraorb", triggerPrice = trigger), exit = true)
    }

    // ---- entries --------------------------------------------------------------------------------

    @Test fun anApprovedLiveEntryBuysOneLotThenRestsItsStop() {
        state(pending = true)
        assertEquals("Entered at Zerodha (live).", approve())
        val (buy, stop) = kite.placed
        val keys = setOf("tradingsymbol", "exchange", "transaction_type", "order_type", "quantity", "product", "tag", "price", "trigger_price")
        assertEquals(mapOf("tradingsymbol" to sym, "exchange" to "NFO", "transaction_type" to "BUY", "order_type" to "MARKET",
            "quantity" to "30", "product" to "MIS", "tag" to "iraorb"), buy.form.filterKeys { it in keys })
        assertEquals(mapOf("tradingsymbol" to sym, "exchange" to "NFO", "transaction_type" to "SELL", "order_type" to "SL",
            "quantity" to "30", "product" to "MIS", "tag" to "iraorb", "price" to "152.00", "trigger_price" to "160.00"), stop.form.filterKeys { it in keys })
        val a = arm()
        val p = a.open!!
        assertTrue(p.live); assertFalse(p.unconfirmed)
        assertEquals(200.0, p.entry, 0.0); assertEquals(160.0, p.stopTrigger!!, 0.0)
        assertEquals(kite.orders.keys.last(), p.stopOrderId)
        assertNull("the approval is used up", a.pending)
        assertEquals("TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
    }

    /**
     * 18 Nov 2026 (audit: B5): approves the waiting entry and keeps the arms' book as it was on disk when the buy's POST reached Zerodha
     * (19 Nov 2026: the intent's write runs beside the POST on the book's writer thread; it is awaited there, as the write a crash right
     * after the POST would have left).
     */
    private fun approveKeepingTheBookAtThePost(): ByteArray {
        val file = java.io.File(context.filesDir, "orb.vault")
        val atPost = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
        kite.afterNext("/orders/regular") { OrbArms.awaitWrites(); atPost.set(com.optionslab.app.security.Vault.readFile(file)) }
        assertEquals("Entered at Zerodha (live).", approve())
        val snap = atPost.get()!!
        val saved = JSONObject(String(snap, Charsets.UTF_8))
        val row = saved.getJSONArray("positions").getJSONObject(0)
        assertTrue("on disk with the order", row.getBoolean("unconfirmed"))
        assertEquals("", row.getString("entryOrderId")); assertEquals("", row.getString("stopOrderId"))
        assertEquals(sym, row.getString("kite")); assertEquals(30, row.getInt("qty"))
        assertFalse("the approval used up on disk too", saved.optJSONObject("pending")?.has("orb") == true)
        return snap
    }

    /** The phone "dies" with the book on disk as [snap]; the app starts again and its book is read back. */
    private fun restartFrom(snap: ByteArray) {
        com.optionslab.app.security.Vault.writeFile(java.io.File(context.filesDir, "orb.vault"), snap)
        com.optionslab.app.testing.AutomationSupport.reloadFromDisk(OrbArms)
        assertTrue("held as unconfirmed after the restart", arm().open!!.unconfirmed)
    }

    /**
     * 18 Nov 2026 (audit: B5, a crash between the POST and the booking): the entry is on disk as an unconfirmed trade with its order.
     * The phone "dies" right after the POST (the stop never placed); after the restart the settle finds the arm's own order (labelled,
     * not claimed by any trade) and takes the lot back as the arm's trade, with its stop - never an unowned lot with no stop, no second buy.
     */
    @Test fun anEntryIsWrittenDownBeforeItsOrderGoesAndARestartSettlesIt() {
        state(pending = true)
        val snap = approveKeepingTheBookAtThePost()
        val buyId = kite.orders.keys.first()
        kite.orders.remove(arm().open!!.stopOrderId!!)                    // its stop was never placed
        restartFrom(snap)
        runBlocking { ArmLive.refresh() }
        kite.requests.clear()
        pass()
        val p = arm().open!!
        assertFalse(p.unconfirmed)
        assertEquals(30, p.qty); assertEquals(buyId, p.entryOrderId)
        val stop = kite.placed.single()
        assertEquals(mapOf("transaction_type" to "SELL", "order_type" to "SL", "quantity" to "30"),
            stop.form.filterKeys { it in setOf("transaction_type", "order_type", "quantity") })
        assertEquals(kite.orders.keys.last(), p.stopOrderId)
        assertEquals("still one buy", listOf(buyId), kite.orders.values.filter { it.side == "BUY" }.map { it.id })
    }

    /**
     * 19 Nov 2026 (second review): the phone "dies" after the first stop was placed but before the booked trade was saved: after the
     * restart the settle takes that resting stop as the trade's - exactly one stop at Zerodha, never a second beside it.
     */
    @Test fun aRestartAfterTheFirstStopWentTakesThatStopNeverASecond() {
        state(pending = true)
        val snap = approveKeepingTheBookAtThePost()
        val stopId = arm().open!!.stopOrderId!!
        restartFrom(snap)
        runBlocking { ArmLive.refresh() }
        kite.requests.clear()
        pass()
        val p = arm().open!!
        assertFalse(p.unconfirmed)
        assertEquals(30, p.qty)
        assertEquals("its stop is the one already resting", stopId, p.stopOrderId)
        assertTrue("no second stop: ${kite.placed.map { it.form }}", kite.placed.none { it.form["order_type"] == "SL" || it.form["order_type"] == "SL-M" })
        assertEquals("exactly one stop at Zerodha", 1, kite.orders.values.count { it.side == "SELL" && it.type == "SL" && it.working })
    }

    /**
     * 19 Nov 2026 (coordinator): the phone "dies" right at the POST - the intent's write still running (a 1.5 s vault write), so the book
     * on disk holds no row for the buy, and no stop was placed. After the restart the first regular look takes the lot back as the arm's
     * trade with exactly one stop, says so, marks its bar decided, and buys nothing again.
     */
    @Test fun aBuyWhoseIntentNeverReachedTheDiskIsTakenBackAtTheColdStart() {
        state(pending = true)
        val file = java.io.File(context.filesDir, "orb.vault")
        val atPost = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
        kite.afterNext("/orders/regular") { atPost.set(com.optionslab.app.security.Vault.readFile(file)) }
        com.optionslab.app.security.Vault.testWriteDelayMs = 1_500L
        try {
            assertEquals("Entered at Zerodha (live).", approve())
        } finally { com.optionslab.app.security.Vault.testWriteDelayMs = 0L }
        val snap = atPost.get()!!
        assertEquals("no row on disk at the POST", 0, JSONObject(String(snap, Charsets.UTF_8)).getJSONArray("positions").length())
        val buyId = kite.orders.values.single { it.side == "BUY" }.id
        kite.orders.remove(arm().open!!.stopOrderId!!)                    // the phone died before its stop went
        assertTrue(OrbArms.awaitWrites())
        // A new process: the book on disk is the one from the POST.
        com.optionslab.app.security.Vault.writeFile(file, snap)
        OrbArms.forgetMemoryForTest()
        assertNull("no row after the restart", arm().open)
        runBlocking { ArmLive.refresh() }
        AutomationSupport.clearAlerts()
        kite.requests.clear()
        pass()
        val p = arm().open!!
        assertFalse(p.unconfirmed); assertTrue(p.live)
        assertEquals(30, p.qty); assertEquals(buyId, p.entryOrderId); assertEquals(sym, p.kite)
        val stop = kite.placed.single()
        assertEquals(mapOf("transaction_type" to "SELL", "order_type" to "SL", "quantity" to "30"),
            stop.form.filterKeys { it in setOf("transaction_type", "order_type", "quantity") })
        assertEquals(kite.orders.keys.last(), p.stopOrderId)
        assertEquals("exactly one stop", 1, kite.orders.values.count { it.side == "SELL" && it.working })
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "found at Zerodha with no trade here" in it })
        // Its bar is decided, on disk too; a second look buys nothing and adopts nothing more.
        assertTrue(OrbArms.awaitWrites())
        val decided = JSONObject(String(com.optionslab.app.security.Vault.readFile(file)!!, Charsets.UTF_8)).getJSONObject("decided")
            .getJSONArray("orb|${OrbArms.testNow!!.toLocalDate()}")
        val minute = kite.orders.getValue(buyId).at.withSecond(0).withNano(0).toString()
        assertTrue("$minute in $decided", (0 until decided.length()).any { decided.getString(it) == minute })
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(20)
        pass()
        assertEquals("still one buy", 1, kite.orders.values.count { it.side == "BUY" })
        assertEquals(1, arm().today.count { it.open })
    }

    /**
     * 19 Nov 2026 (coordinator): "Erase everything" while the intent's write is still running never leaves the arms' book on disk: the
     * write that ends after the erase deletes the file again, and the erase itself never waits on it.
     */
    @Test fun anEraseDuringTheIntentsWriteLeavesNoBookOnDisk() {
        state(pending = true)
        val file = java.io.File(context.filesDir, "orb.vault")
        val eraseMs = java.util.concurrent.atomic.AtomicLong(-1)
        kite.afterNext("/orders/regular") {
            val t0 = System.nanoTime(); OrbArms.wipe(); eraseMs.set((System.nanoTime() - t0) / 1_000_000)
        }
        com.optionslab.app.security.Vault.testWriteDelayMs = 1_500L
        try { runCatching { approve() } } finally { com.optionslab.app.security.Vault.testWriteDelayMs = 0L }
        assertTrue(OrbArms.awaitWrites())
        assertTrue("the erase took ${eraseMs.get()} ms", eraseMs.get() in 0 until 1_000)
        assertFalse("no book on disk after the erase", file.exists())
    }

    /**
     * 19 Nov 2026 (second review): two stops of these arms rest unclaimed on the contract when the app comes back (the first stop and
     * another): neither is taken and no third is placed - the entry stays unconfirmed (the arm buys nothing more) while both rest.
     */
    @Test fun aRestartBesideTwoUnclaimedStopsTakesNeitherAndPlacesNone() {
        state(pending = true)
        val snap = approveKeepingTheBookAtThePost()
        val first = arm().open!!.stopOrderId!!
        val second = runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 30, 30, "MIS", "SL", 150.0,
            0.05, "NFO", "iraorb", triggerPrice = 155.0), exit = true) }
        restartFrom(snap)
        runBlocking { ArmLive.refresh() }
        kite.requests.clear()
        pass()
        assertTrue("nothing placed: ${kite.placed.map { it.form }}", kite.placed.isEmpty())
        assertTrue("still unconfirmed", arm().open!!.unconfirmed)
        assertEquals("TRIGGER PENDING", kite.order(first).status); assertEquals("TRIGGER PENDING", kite.order(second).status)
    }

    /**
     * 19 Nov 2026 (second review, order speed): with a 1.5 s vault write (a Pixel's StrongBox) the entry's POST never waits on the
     * intent's write - when the buy reaches Zerodha the intent is still being written (it lands moments later), and the POST comes well
     * under the write's own time after the intent was handed to the writer. (Timed from there: the approval's own earlier write of the used-up
     * approval, as before, comes ahead of the entry and is not the intent's.)
     */
    @Test fun theEntrysPostNeverWaitsOnTheIntentsVaultWrite() {
        state(pending = true)
        val file = java.io.File(context.filesDir, "orb.vault")
        val onDisk = java.util.concurrent.atomic.AtomicReference<String?>()
        val postMs = java.util.concurrent.atomic.AtomicLong(-1)
        val queuedAt = java.util.concurrent.atomic.AtomicLong(0)
        OrbArms.testIntentQueued = { queuedAt.set(System.nanoTime()) }
        kite.afterNext("/orders/regular") {
            postMs.set((System.nanoTime() - queuedAt.get()) / 1_000_000)
            onDisk.set(com.optionslab.app.security.Vault.readFile(file)?.toString(Charsets.UTF_8))
        }
        com.optionslab.app.security.Vault.testWriteDelayMs = 1_500L
        try {
            assertEquals("Entered at Zerodha (live).", approve())
        } finally { com.optionslab.app.security.Vault.testWriteDelayMs = 0L; OrbArms.testIntentQueued = null }
        val atPost = JSONObject(onDisk.get()!!).getJSONArray("positions")
        assertEquals("the intent not on disk yet when the order went (its write still running): $atPost", 0, atPost.length())
        assertTrue("the intent was queued", queuedAt.get() != 0L)
        assertTrue("the POST ${postMs.get()} ms after the intent was queued, under the 1.5 s write", postMs.get() in 0 until 1_500)
        assertTrue(OrbArms.awaitWrites())
        val now = JSONObject(String(com.optionslab.app.security.Vault.readFile(file)!!, Charsets.UTF_8)).getJSONArray("positions")
        assertEquals("on disk after", 1, now.length())
        assertFalse(now.getJSONObject(0).getBoolean("unconfirmed"))
    }

    /** 18 Nov 2026 (audit: B5): an entry Zerodha refuses outright leaves no unconfirmed row behind (the arm is free for the next signal). */
    @Test fun aRefusedEntryLeavesNoRowBehind() {
        state(pending = true)
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "Insufficient funds")
        val msg = approve()
        assertTrue(msg, msg.contains("Insufficient funds"))
        assertNull(arm().open)
        assertTrue(arm().today.isEmpty())
    }

    @Test fun inLiveAnApprovalWithoutThePinSendsNothing() {
        state(pending = true)
        assertEquals("The app is in Live: approve with your PIN on Home → Strategies.", approve(pin = false))
        assertTrue(kite.requests.isEmpty())
        assertNotNull("still waiting for the PIN", arm().pending)
    }

    @Test fun aPhoneThatFailedTheSecurityCheckSendsNoLiveEntry() {
        AutomationSupport.security(compromised = true)
        state(pending = true)
        assertEquals("Refused: this phone failed the security check; no live order sent", approve())
        assertTrue(kite.placed.isEmpty())
        assertNull(arm().open)
    }

    @Test fun theKillSwitchRefusesTheEntry() {
        SecurePrefs.put("g.kill", true)
        state(pending = true)
        assertEquals("Refused: the kill switch is on", approve())
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun anExpiredSignalIsNotEntered() {
        state(pending = true, expires = now.minusMinutes(1))
        val msg = approve()
        assertTrue(msg, msg.startsWith("The ORB signal expired at "))
        assertTrue(kite.requests.isEmpty())
        assertEquals("Nothing is waiting for approval.", approve())
    }

    @Test fun aLostReplyIsHeldUnconfirmedThenSettledWithItsStop() {
        state(pending = true)
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        kite.down += "/orders"                                         // and the order book cannot be read either
        assertEquals("Bought at Zerodha, not yet confirmed: held until the order book shows what filled.", approve())
        val held = arm().open!!
        assertTrue(held.unconfirmed); assertNull(held.stopOrderId); assertNull(held.entryOrderId)
        assertEquals("the arm buys nothing more meanwhile", "BUY", kite.placed.single().form["transaction_type"])
        assertTrue(AutomationSupport.alerts().any { it.contains("Zerodha did not confirm the buy of $sym") })

        kite.down.clear()
        pass()
        val settled = arm().open!!
        assertFalse(settled.unconfirmed)
        assertEquals(kite.orders.keys.first(), settled.entryOrderId)
        assertEquals(30, settled.qty); assertEquals(200.0, settled.entry, 0.0)
        assertEquals("its stop now rests", "SL", kite.placed.last().form["order_type"])
        assertEquals("still one buy", 1, kite.placed.count { it.form["transaction_type"] == "BUY" })
        assertEquals(settled.stopOrderId, kite.orders.keys.last())
    }

    @Test fun anUnconfirmedBuyThatNeverReachedZerodhaIsDropped() {
        val p = position("orb", 30, 200.0, entryId = null).put("unconfirmed", true)
        state(positions = listOf(p))
        pass()
        // 18 Nov 2026 (audit: B4): two reads must agree, each at least 10 s apart (entered 30 minutes ago: a read with no row counts now).
        assertTrue("kept after one read", arm().open?.unconfirmed == true)
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(11)
        pass()
        assertNull("nothing is held", arm().open)
        assertTrue(kite.placed.isEmpty())
    }

    // ---- exits ----------------------------------------------------------------------------------

    @Test fun aPartialExitBooksWhatSoldAndSellsTheRestNextPass() {
        state(positions = listOf(position("orb", 60, 200.0, entryId = "E1")))
        kite.position(sym, 60, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)                    // +40 target
        kite.nextPlace(outcome = Outcome.PARTIAL, partialQty = 30)
        pass()
        assertEquals("60", kite.placed.single().form["quantity"])
        val today = arm().today
        val closed = today.single { !it.open }
        assertEquals(30, closed.qty); assertEquals("target", closed.why); assertEquals(245.0, closed.exit!!, 0.0)
        assertEquals(30, arm().open!!.qty)
        assertEquals("the unfilled rest was cancelled", "CANCELLED", kite.order(kite.orders.keys.last()).status)

        pass()
        assertEquals(listOf("60", "30"), kite.placed.map { it.form["quantity"] })
        assertNull(arm().open)
        assertEquals(0, kite.positions.getValue("NFO:$sym:MIS").first)
    }

    @Test fun oneArmsExitNeverCancelsTheOtherArmsStop() {
        val s1 = stopOrder(30, 160.0, 152.0)
        val s2 = stopOrder(30, 190.0, 180.0)
        state(positions = listOf(position("orb", 30, 200.0, "E1", s1, 160.0), position("orb_fresh", 30, 230.0, "E2", s2, 190.0)))
        kite.position(sym, 60, 215.0, product = "MIS")
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)                    // ORB's target; not ORB Fresh's
        kite.requests.clear()
        pass()
        assertTrue("ORB's own stop comes out first", kite.requests.any { it.method == "DELETE" && it.path == "/orders/regular/$s1" })
        assertTrue("ORB Fresh's stop is never touched", kite.requests.none { it.path.endsWith("/$s2") && it.method != "GET" })
        assertEquals("TRIGGER PENDING", kite.order(s2).status)
        assertEquals("only ORB's lot is sold", listOf("30"), kite.placed.map { it.form["quantity"] })
        assertEquals("target", arm("orb").today.single().why)
        assertTrue(arm("orb_fresh").open != null)
        assertEquals(30, kite.positions.getValue("NFO:$sym:MIS").first)
    }

    @Test fun aStopThatFilledAtZerodhaIsTheExit() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(positions = listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.fill(s1, 158.0)
        kite.quote("NFO:$sym", 150.0)
        kite.requests.clear()
        pass()
        val p = arm().today.single()
        assertEquals("stop", p.why); assertEquals(158.0, p.exit!!, 0.0)
        assertTrue("no second sell after the stop", kite.placed.isEmpty())
    }

    @Test fun closedByHandTheStopComesOutAndNothingIsSold() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(positions = listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.soldInKite(sym, 30, 210.0)                                   // sold in the Kite app (18 Nov: its sale is in the book)
        kite.quote("NFO:$sym", 210.0)
        kite.requests.clear()
        pass()
        assertTrue(kite.requests.any { it.method == "DELETE" && it.path == "/orders/regular/$s1" })
        assertTrue("a sell now would open a short", kite.placed.isEmpty())
        assertEquals("closed_by_you", arm().today.single().why)
    }

    /**
     * 18 Nov 2026 (Boss, live: the Liquidity BANKNIFTY lot was booked closed on one positions read while Zerodha still held it - its
     * stop cancelled, nothing watching it): a read with no row for the contract never counts; a row at 0 with no sale in Zerodha's
     * order book closes nothing and leaves the stop resting (no sale, no new stop) until reads 30 s apart agree.
     */
    @Test fun oneReadShowingItGoneNeverClosesItOrTakesItsStopOut() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(positions = listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.quote("NFO:$sym", 210.0)
        kite.requests.clear()
        pass()                                                            // no row at all (an empty read)
        assertNotNull("kept", arm().open)
        assertEquals("its stop rests", "TRIGGER PENDING", kite.order(s1).status)
        kite.position(sym, 0, 0.0, product = "MIS")                       // a row at 0, no sale in the book
        pass()
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(29)
        pass()
        assertNotNull("not confirmed yet: kept", arm().open)
        assertEquals("TRIGGER PENDING", kite.order(s1).status)
        assertTrue("nothing sent, nothing cancelled", kite.requests.none { it.method != "GET" })
        // 18 Nov 2026 (review F3): 30 s of reads agreeing with no sale at all in the order book: still kept, its stop resting, Boss told once.
        fun told() = AutomationSupport.alerts().count { "for 30 s" in it && sym in it }
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(1)
        pass()
        assertNotNull("30 s with nothing sold: never dropped", arm().open)
        assertEquals("TRIGGER PENDING", kite.order(s1).status)
        assertEquals("Boss told", 1, told())
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(269)              // 4 min 59 s
        pass()
        assertNotNull(arm().open)
        assertEquals("TRIGGER PENDING", kite.order(s1).status)
        assertEquals("told once", 1, told())
        assertTrue("nothing sent, nothing cancelled", kite.requests.none { it.method != "GET" })
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(1)                // five minutes of reads agreeing
        pass()
        assertEquals("closed_by_you", arm().today.single().why)
        assertTrue(kite.requests.any { it.method == "DELETE" && it.path == "/orders/regular/$s1" })
        assertTrue("a sell now would open a short", kite.placed.isEmpty())
    }

    /**
     * 18 Nov 2026: an unconfirmed buy whose order the book does not show, while Zerodha holds it, is kept as the arm's trade with its
     * stop at once - never dropped as "no buy reached Zerodha".
     */
    @Test fun anUnconfirmedBuyZerodhaHoldsIsKeptWithItsStop() {
        val p = position("orb", 30, 200.0, entryId = null).put("unconfirmed", true)
        state(positions = listOf(p))
        kite.position(sym, 30, 201.0, product = "MIS")
        kite.dayBought["NFO:$sym:MIS"] = 30                              // bought today (Zerodha's day_buy_quantity)
        runBlocking { ArmLive.refresh() }                                 // the arms' live copies' book read (none of them holds it)
        pass()
        val kept = arm().open!!
        assertFalse(kept.unconfirmed)
        assertEquals(30, kept.qty); assertEquals("Zerodha's average", 201.0, kept.entry, 0.0)
        val stop = kite.placed.single()
        assertEquals(mapOf("transaction_type" to "SELL", "order_type" to "SL", "quantity" to "30"),
            stop.form.filterKeys { it in setOf("transaction_type", "order_type", "quantity") })
        assertEquals(kite.orders.keys.last(), kept.stopOrderId)
        assertTrue(AutomationSupport.alerts().joinToString(), AutomationSupport.alerts().any { "managed as this arm's trade" in it })
    }

    /** 18 Nov 2026: a position carried from an earlier day (nothing bought today) is never taken for a lost buy's fill. */
    @Test fun anUnconfirmedBuyNeverTakesAPositionCarriedFromAnEarlierDay() {
        val p = position("orb", 30, 200.0, entryId = null).put("unconfirmed", true)
        state(positions = listOf(p))
        kite.overnight(sym, 30, 180.0, product = "MIS")
        runBlocking { ArmLive.refresh() }
        pass()
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(11)               // 18 Nov 2026 (audit: B4): a second read agreeing
        pass()
        assertNull("dropped, the carried position left alone", arm().open)
        assertTrue(kite.placed.isEmpty())
    }

    /**
     * 18 Nov 2026: one whose order is not found and nothing is held waits three minutes from its entry (the book can trail), then goes.
     * 18 Nov 2026 (audit: B4): only on reads that list the contract (a row at 0), two of them agreeing.
     */
    @Test fun anUnconfirmedBuyNotFoundWaitsThreeMinutesThenIsDropped() {
        val p = position("orb", 30, 200.0, entryId = null).put("unconfirmed", true).put("entryTime", now.toString())
        state(positions = listOf(p))
        kite.position(sym, 0, 0.0, product = "MIS")
        pass()
        assertTrue("kept waiting", arm().open?.unconfirmed == true)
        OrbArms.testNow = OrbArms.testNow!!.plusMinutes(4)              // a second read agreeing, past the three minutes
        pass()
        assertNull("nothing is held", arm().open)
        assertTrue(kite.placed.isEmpty())
    }

    /**
     * 18 Nov 2026 (audit: B4): a lost-id entry is never dropped on reads that list no row for its contract (an empty or bad positions read)
     * while it is young: past three minutes it is still kept; ten minutes after the entry two reads agreeing let it go.
     */
    @Test fun anUnconfirmedBuyIsNeverDroppedOnReadsWithNoRowForItsContract() {
        val p = position("orb", 30, 200.0, entryId = null).put("unconfirmed", true).put("entryTime", now.toString())
        state(positions = listOf(p))
        OrbArms.testNow = OrbArms.testNow!!.plusMinutes(4)
        pass()
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(11)
        pass()
        assertTrue("no row is no reading: kept", arm().open?.unconfirmed == true)
        OrbArms.testNow = OrbArms.testNow!!.plusMinutes(7)
        pass()
        assertTrue("one read: kept", arm().open?.unconfirmed == true)
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(11)
        pass()
        assertNull("nothing is held", arm().open)
        assertTrue(kite.placed.isEmpty())
    }

    /** 18 Nov 2026 (audit: B4): one read listing the contract with nothing held, then one holding it: the entry is kept with its stop. */
    @Test fun oneShortReadThenAHeldOneKeepsTheLostEntry() {
        val p = position("orb", 30, 200.0, entryId = null).put("unconfirmed", true)
        state(positions = listOf(p))
        kite.position(sym, 0, 0.0, product = "MIS")
        runBlocking { ArmLive.refresh() }
        pass()
        assertTrue("one short read: kept", arm().open?.unconfirmed == true)
        kite.position(sym, 30, 201.0, product = "MIS")
        kite.dayBought["NFO:$sym:MIS"] = 30
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(11)
        pass()
        val kept = arm().open!!
        assertFalse(kept.unconfirmed)
        assertEquals(30, kept.qty)
        assertEquals("SL", kite.placed.single().form["order_type"])
    }

    /**
     * 18 Nov 2026 (review F1): Boss's sale of a lot this book already booked "closed_by_you" is no proof that the next lot on the contract
     * closed: one read showing it gone then closes nothing and leaves its stop resting.
     */
    @Test fun aSaleAlreadyBookedClosedByYouNeverConfirmsTheNextLotGone() {
        val s1 = stopOrder(30, 160.0, 152.0)
        val booked = position("orb", 30, 200.0, "E0").put("entryTime", ago(40).toString()).put("exit", 205.0)
            .put("exitTime", ago(10).toString()).put("why", "closed_by_you")
        state(positions = listOf(booked, position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 60, 200.0, product = "MIS")
        kite.soldInKite(sym, 30, 205.0)                                   // Boss's sale of the lot already booked
        kite.position(sym, 0, 0.0, product = "MIS")                       // then one bad read
        kite.quote("NFO:$sym", 210.0)
        kite.requests.clear()
        pass()
        assertNotNull("kept", arm().open)
        assertEquals("its stop rests", "TRIGGER PENDING", kite.order(s1).status)
        assertTrue("nothing sent, nothing cancelled", kite.requests.none { it.method != "GET" })
    }

    /** 18 Nov 2026 (review F1): Boss's own buy and sale of the contract in Kite (a round trip of his) never confirms the arm's lot gone. */
    @Test fun bossesOwnRoundTripInKiteNeverConfirmsItGone() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(positions = listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.boughtInKite(sym, 30, 201.0)
        kite.soldInKite(sym, 30, 203.0)
        kite.position(sym, 0, 0.0, product = "MIS")                       // one bad read
        kite.quote("NFO:$sym", 210.0)
        kite.requests.clear()
        pass()
        assertNotNull("kept", arm().open)
        assertEquals("its stop rests", "TRIGGER PENDING", kite.order(s1).status)
    }

    /**
     * 18 Nov 2026 (review F4): shown gone but not confirmed, with no stop resting at Zerodha: the arm's own stop exit still goes - it reads
     * Zerodha again and sells what is really held (here the read was bad: the lot is still there).
     */
    @Test fun shownGoneWithNoStopRestingItsOwnStopExitStillGoes() {
        state(positions = listOf(position("orb", 30, 200.0, "E1", stopId = null, stop = 160.0)))
        kite.position(sym, 0, 0.0, product = "MIS")                       // the pass's read: gone (a bad read)
        kite.afterNext("/portfolio/positions") { kite.position(sym, 30, 200.0, product = "MIS") }   // the exit's own read: held
        kite.quote("NFO:$sym", 150.0, 149.95, 150.05)                     // under its stop
        kite.requests.clear()
        pass()
        val sells = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }
        assertEquals("its own stop exit went", 1, sells.size)
        assertEquals("30", sells.single().form["quantity"])
        assertEquals(0, kite.positions["NFO:$sym:MIS"]?.first ?: 0)
    }

    /**
     * 18 Nov 2026 (review F6): a lost buy still searched for (unconfirmed, no order id) is left out of the quantity the arms' live copies
     * count as explained by this book; a confirmed one counts.
     */
    @Test fun aLostBuyStillSearchedForIsNotCountedAsHeldForTheOtherBook() {
        state(positions = listOf(position("orb", 30, 200.0, entryId = null).put("unconfirmed", true)))
        arm()
        assertNull("not counted", OrbArms.liveQtyHint[sym])
        state(positions = listOf(position("orb", 30, 200.0, "E1")))
        arm()
        assertEquals(30, OrbArms.liveQtyHint[sym])
    }

    // ---- the profit lock moves the stop at Zerodha (07 Oct, research/HUNT_H20.md F1-F3, F5) ------------------------

    /** A laddered ORB-family position (as every new entry is) with its stop resting at Zerodha; [peak]: its best so far. */
    private fun laddered(arm: String, entry: Double, stopId: String, stop: Double, peak: Double? = null) =
        position(arm, 30, entry, "E1", stopId, stop).put("ladder", true).apply { peak?.let { put("peak", it) } }

    /** The paper feed's candles (the best price without a stream), served by a fake so no test reaches the internet. */
    private fun <T> withUpstox(body: () -> T): T {
        val u = FakeUpstox()
        try { u.price("NSE_FO|TESTCE", 1.0); return body() } finally { u.close() }
    }

    @Test fun theLockModifiesTheStopAtZerodhaUpAndNeverDown() = withUpstox {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(positions = listOf(laddered("orb", 200.0, s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 222.0)                                     // +22: +10 is locked
        kite.requests.clear()
        pass()
        val o = kite.order(s1)
        assertEquals("the same order, still resting", "TRIGGER PENDING", o.status)
        assertEquals(210.0, o.trigger, 1e-9)
        assertEquals("SL", o.type)
        assertTrue("its limit sits under the trigger", o.price < 210.0)
        assertTrue("modified, never cancelled", kite.requests.none { it.method == "DELETE" })
        assertTrue("never a new sell", kite.placed.isEmpty())
        assertEquals(210.0, arm().open!!.stopTrigger!!, 1e-9)
        kite.quote("NFO:$sym", 215.0)                                     // lower again: the stop stays where it is
        kite.requests.clear()
        pass()
        assertEquals(210.0, kite.order(s1).trigger, 1e-9)
        assertTrue("never moved down", kite.requests.none { it.method == "PUT" })
        // Zerodha sells at the lock by itself (the app need not be running): booked as the profit lock.
        kite.fill(s1, 209.5)
        kite.quote("NFO:$sym", 205.0)
        pass()
        val closed = arm().today.single()
        assertEquals("profit_lock", closed.why)
        assertEquals(209.5, closed.exit!!, 1e-9)
        assertTrue("no second sell", kite.placed.isEmpty())
    }

    @Test fun aRefusedModifyKeepsTheOldStopAndIsTriedAgainOnTheNextCheck() = withUpstox {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(positions = listOf(laddered("orb", 200.0, s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 222.0)
        kite.down += "/orders/regular/$s1"
        kite.requests.clear()
        pass()
        assertEquals("the old stop still works", 160.0, kite.order(s1).trigger, 1e-9)
        assertEquals("TRIGGER PENDING", kite.order(s1).status)
        assertEquals(160.0, arm().open!!.stopTrigger!!, 1e-9)
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { it.contains("did not move the stop on $sym up to the profit lock 210.00") })
        assertTrue("nothing sold, nothing cancelled", kite.placed.isEmpty())
        kite.down.clear()
        pass()
        assertEquals(210.0, kite.order(s1).trigger, 1e-9)
        assertEquals(210.0, arm().open!!.stopTrigger!!, 1e-9)
    }

    @Test fun aLockExitThatFailsPutsTheStopBackAtTheLockNotTheMinus40() = withUpstox {
        val s1 = stopOrder(30, 160.0, 152.0)
        // Its best (222) earned +10 (210), but the stop still rests at 160 (the modify was refused); the price is back at 205.
        state(positions = listOf(laddered("orb", 200.0, s1, 160.0, peak = 222.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 205.0)
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "Markets are closed right now.")
        kite.requests.clear()
        pass()
        assertEquals("the -40 stop came out first", "CANCELLED", kite.order(s1).status)
        assertEquals("the market sell was tried", "MARKET", kite.placed.first().form["order_type"])
        val restop = kite.placed.last()
        assertEquals("SL", restop.form["order_type"])
        assertEquals("back at the lock, not at 160", "210.00", restop.form["trigger_price"])
        val p = arm().open!!
        assertEquals(210.0, p.stopTrigger!!, 1e-9)
        assertEquals(kite.orders.keys.last(), p.stopOrderId)
    }

    @Test fun liveExitsTakeEachArmsOwnTarget() {
        // F5: the live path used the ORB's +40 for every arm. ORB Sweep's own target is +80.
        val s1 = stopOrder(30, 160.0, 152.0)
        state(positions = listOf(position("orb_sweep", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 245.0)                                     // +45: the ORB's target, not ORB Sweep's
        kite.requests.clear()
        pass()
        assertNotNull("held", arm("orb_sweep").open)
        assertTrue(kite.placed.isEmpty())
        kite.quote("NFO:$sym", 281.0)                                     // +81: ORB Sweep's +80
        pass()
        assertEquals("target", arm("orb_sweep").today.single().why)
    }

    // ---- arming ---------------------------------------------------------------------------------

    /** 15 Nov: Liquidity's no-PIN switch (Boss's choice) is Liquidity's alone - ORB's own switch in Live still takes the PIN. */
    @Test fun theOrbsSwitchInLiveStillTakesThePin() = runBlocking {
        state(armed = false, positions = listOf(position("orb", 30, 200.0, "E1")))
        assertEquals("The app is in Live: arm it with your PIN or fingerprint.", OrbArms.setArmed("orb", true, automatic = true, bySwitch = true))
        assertFalse(arm().armed); assertFalse(arm().liveOk)
        assertTrue("nothing sent", kite.placed.isEmpty())
    }

    @Test fun armingInLiveTakesThePinAndDisarmingKeepsThePositionManaged() = runBlocking {
        state(armed = false, positions = listOf(position("orb", 30, 200.0, "E1")))
        // Un-retired by Boss on 07 Oct: ORB arms like any arm - in Live only with the PIN or fingerprint, exactly as before.
        assertEquals("The app is in Live: arm it with your PIN or fingerprint.", OrbArms.setArmed("orb", true, automatic = true))
        assertFalse(arm().armed)
        val on = OrbArms.setArmed("orb", true, automatic = true, pinConfirmed = true)
        assertTrue(on, on.startsWith("ORB armed on ZERODHA (live), fully automatic"))
        assertTrue(arm().liveOk)
        assertEquals("ORB disarmed. Its open position is still managed to its exit.", OrbArms.setArmed("orb", false, automatic = true))
        assertEquals("Skipped.", OrbArms.skip("orb"))
        assertEquals("You skipped the last signal.", OrbArms.describe(arm().status))
        assertTrue(kite.requests.isEmpty())
    }

    @Test fun describeSaysEveryStateInPlainWords() {
        assertEquals("Waits for the market watch.", OrbArms.describe(""))
        assertEquals("Refused by Bot settings: max open", OrbArms.describe("guard_refused: max open"))
        assertEquals("The order was refused: x", OrbArms.describe("order_refused: x"))
        assertEquals("Could not check: y", OrbArms.describe("error: y"))
        assertEquals("Breakout: waiting for your approval with PIN (live).", OrbArms.describe("awaiting_approval"))
        assertEquals("something new", OrbArms.describe("something new"))
    }
}
