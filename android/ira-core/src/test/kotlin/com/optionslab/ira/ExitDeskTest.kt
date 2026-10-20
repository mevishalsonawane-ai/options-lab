package com.optionslab.ira

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 20 Oct, enhancement round 2 (idea 1): the exit coordinator's registry, its read-again and its turns. */
class ExitDeskTest {
    /** The app's inline wrapper, as a plain function: enter, run, leave. */
    private suspend fun <T> ExitTurns.turn(key: String, who: String, on: Boolean, note: (ExitTurns.Took, String?, Long) -> Unit = { _, _, _ -> },
                                           block: suspend () -> T): T {
        val pass = enter(key, who, on, note)
        try { return block() } finally { pass.leave() }
    }

    private var now = 1_000_000L
    private val reg = ExitRegistry(clock = { now })
    private val k = ExitDesk.key("nfo", "nifty26oct24500ce")
    private fun row(id: String, qty: Int = 75, type: String = "MARKET", side: String = "SELL", key: String = k, at: Long? = null) =
        ExitRegistry.Row(id, key, side, qty, type, at)

    @Test fun theKeyIsTheExchangeAndSymbolInCapitals() {
        assertEquals("NFO:NIFTY26OCT24500CE", k)
    }

    @Test fun anExitIsUnseenFromItsSendUntilAReadShowsIt() {
        val t = reg.sending(ExitRegistry.Kind.ORDER, k, "sell", 75, "market", "iraorb")
        assertEquals(ExitRegistry.State.SENDING, reg.unseen(ExitRegistry.Kind.ORDER, k).single().state)
        // A read while its request is on its way cannot show it.
        reg.seen(ExitRegistry.Kind.ORDER, listOf(row("1")))
        assertEquals(1, reg.unseen(ExitRegistry.Kind.ORDER, k).size)
        reg.answered(t, "9")
        reg.seen(ExitRegistry.Kind.ORDER, listOf(row("1")))
        assertEquals("9", reg.unseen(ExitRegistry.Kind.ORDER, k).single().id, "the book trails the order: still looked for")
        reg.seen(ExitRegistry.Kind.ORDER, listOf(row("1"), row("9")))
        assertTrue(reg.unseen(ExitRegistry.Kind.ORDER, k).isEmpty())
    }

    @Test fun aRefusedExitIsNeverLookedFor() {
        val t = reg.sending(ExitRegistry.Kind.ORDER, k, "SELL", 75, "MARKET", "x")
        reg.refused(t)
        assertTrue(reg.pending().isEmpty())
    }

    @Test fun anotherContractOrAGttIsNotThisContractsOrder() {
        reg.sending(ExitRegistry.Kind.ORDER, ExitDesk.key("NFO", "OTHER"), "SELL", 75, "MARKET", "x")
        reg.sending(ExitRegistry.Kind.GTT, k, "SELL", 75, "GTT", "x")
        assertTrue(reg.unseen(ExitRegistry.Kind.ORDER, k).isEmpty())
        assertEquals(1, reg.unseen(ExitRegistry.Kind.GTT, k).size)
        // A GTT read does not take an order away, nor an order read a GTT.
        reg.seen(ExitRegistry.Kind.ORDER, listOf(row("1", type = "GTT")))
        assertEquals(1, reg.unseen(ExitRegistry.Kind.GTT, k).size)
    }

    @Test fun aLostReplyIsShownByAMatchingRowPlacedSinceAMinuteBeforeIt() {
        val t = reg.sending(ExitRegistry.Kind.ORDER, k, "SELL", 75, "MARKET", "x")
        reg.lost(t)
        // An older identical order (placed two minutes before) is not it; another size, side or type is not it.
        reg.seen(ExitRegistry.Kind.ORDER, listOf(row("1", at = now - 120_000), row("2", qty = 150), row("3", side = "BUY"), row("4", type = "SL")))
        assertEquals(ExitRegistry.State.LOST, reg.unseen(ExitRegistry.Kind.ORDER, k).single().state)
        reg.seen(ExitRegistry.Kind.ORDER, listOf(row("5", at = now - 2_000)))
        assertTrue(reg.unseen(ExitRegistry.Kind.ORDER, k).isEmpty())
    }

    @Test fun aLostReplysRowIsNeverAnotherExitsOwnId() {
        val a = reg.sending(ExitRegistry.Kind.ORDER, k, "SELL", 75, "MARKET", "a")
        reg.answered(a, "7")
        val b = reg.sending(ExitRegistry.Kind.ORDER, k, "SELL", 75, "MARKET", "b")
        reg.lost(b)
        reg.seen(ExitRegistry.Kind.ORDER, listOf(row("7")))
        // Order 7 is a's: b is still looked for.
        assertEquals(listOf(b), reg.unseen(ExitRegistry.Kind.ORDER, k).map { it.ticket })
    }

    @Test fun aLostReplyAdoptedByItsSenderIsThatOrder() {
        val t = reg.sending(ExitRegistry.Kind.ORDER, k, "SELL", 75, "MARKET", "x")
        reg.lost(t)
        reg.adopted(ExitRegistry.Kind.ORDER, k, "SELL", 75, "MARKET", "42")
        assertEquals("42", reg.unseen(ExitRegistry.Kind.ORDER, k).single().id)
        reg.seen(ExitRegistry.Kind.ORDER, listOf(row("42")))
        assertTrue(reg.pending().isEmpty())
    }

    @Test fun lostOnlyChangesAnExitStillOnItsWay() {
        val t = reg.sending(ExitRegistry.Kind.ORDER, k, "SELL", 75, "MARKET", "x")
        reg.answered(t, "5")
        reg.lost(t)
        assertEquals(ExitRegistry.State.ANSWERED, reg.pending().single().state)
        // Unknown tickets are ignored.
        reg.answered(99, "1"); reg.lost(99); reg.refused(99)
        assertEquals(1, reg.pending().size)
    }

    @Test fun anExitNeverShownIsLetGoAfterAMinute() {
        val t = reg.sending(ExitRegistry.Kind.ORDER, k, "SELL", 75, "MARKET", "x")
        reg.lost(t)
        now += ExitDesk.KEEP_MS
        assertEquals(1, reg.unseen(ExitRegistry.Kind.ORDER, k).size)
        now += 1
        assertTrue(reg.unseen(ExitRegistry.Kind.ORDER, k).isEmpty())
    }

    @Test fun aRestartStartsEmpty() {
        reg.sending(ExitRegistry.Kind.ORDER, k, "SELL", 75, "MARKET", "x")
        reg.clear()
        assertTrue(reg.pending().isEmpty())
        // An empty registry ignores reads.
        reg.seen(ExitRegistry.Kind.ORDER, emptyList())
        assertTrue(reg.pending().isEmpty())
    }

    // ---- settle --------------------------------------------------------------------------------------------------------

    private fun sent() = ExitRegistry.Sent(1, ExitRegistry.Kind.ORDER, k, "SELL", 75, "MARKET", "a", 0)

    @Test fun aWholeReadIsReadOnce() = runBlocking {
        var reads = 0
        val notes = ArrayList<ExitDesk.Settled>()
        val r = ExitDesk.settle(true, { ++reads }, { emptyList() }, longArrayOf(1, 1)) { s, _ -> notes += s }
        assertEquals(1, r); assertEquals(1, reads); assertEquals(listOf(ExitDesk.Settled.WHOLE), notes)
    }

    @Test fun inShadowAReadMissingAnExitIsOnlyNoted() = runBlocking {
        var reads = 0
        val notes = ArrayList<Pair<ExitDesk.Settled, Int>>()
        ExitDesk.settle(false, { ++reads }, { listOf(sent()) }, longArrayOf(1, 1)) { s, m -> notes += s to m.size }
        assertEquals(1, reads)
        assertEquals(listOf(ExitDesk.Settled.SHADOW_MISSED to 1), notes)
    }

    @Test fun onAReadMissingAnExitIsReadAgainUntilItShows() = runBlocking {
        var reads = 0
        val notes = ArrayList<ExitDesk.Settled>()
        val r = ExitDesk.settle(true, { ++reads }, { n -> if (n < 3) listOf(sent()) else emptyList() }, longArrayOf(1, 1, 1)) { s, _ -> notes += s }
        assertEquals(3, r)
        assertEquals(listOf(ExitDesk.Settled.READ_AGAIN), notes)
    }

    @Test fun onAnExitThatNeverShowsIsLetThroughWithTheLastRead() = runBlocking {
        var reads = 0
        val notes = ArrayList<ExitDesk.Settled>()
        val r = ExitDesk.settle(true, { ++reads }, { listOf(sent()) }, longArrayOf(1, 1, 1)) { s, _ -> notes += s }
        assertEquals(4, r, "the first read and one after each wait")
        assertEquals(listOf(ExitDesk.Settled.LET_THROUGH), notes)
    }

    @Test fun aFailedReadThrowsToTheSenderAsBefore() = runBlocking {
        val r = runCatching { ExitDesk.settle<Int>(true, { error("unreadable") }, { emptyList() }) }
        assertEquals("unreadable", r.exceptionOrNull()?.message)
    }

    @Test fun theWholeWaitIsUnderTwoSeconds() {
        assertTrue(ExitDesk.REREAD_MS.sum() < 2_000)
    }

    // ---- turns ---------------------------------------------------------------------------------------------------------

    @Test fun onASecondSenderWaitsForTheFirstsTurnToEnd() = runBlocking {
        val turns = ExitTurns(waitMs = 5_000)
        val order = ArrayList<String>()
        val took = ArrayList<ExitTurns.Took>()
        val firstIn = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val a = async { turns.turn(k, "arm", true, { t, _, _ -> synchronized(took) { took += t } }) { firstIn.complete(Unit); order += "a in"; release.await(); order += "a out" } }
        firstIn.await()
        val b = async { turns.turn(k, "protection", true, { t, o, _ -> synchronized(took) { took += t }; assertEquals("arm", o) }) { order += "b in" } }
        yield(); delay(20)
        assertEquals(listOf("a in"), order, "b waits while a reads and sends")
        release.complete(Unit)
        a.await(); b.await()
        assertEquals(listOf("a in", "a out", "b in"), order)
        assertEquals(listOf(ExitTurns.Took.ALONE, ExitTurns.Took.WAITED), took)
    }

    @Test fun inShadowNobodyWaitsAndTwoAtOnceIsNoted() = runBlocking {
        val turns = ExitTurns(waitMs = 5_000)
        val order = ArrayList<String>()
        val took = ArrayList<Pair<ExitTurns.Took, String?>>()
        val firstIn = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val a = async { turns.turn(k, "arm", false, { t, o, _ -> took += t to o }) { firstIn.complete(Unit); release.await(); order += "a out" } }
        firstIn.await()
        turns.turn(k, "expiry", false, { t, o, _ -> took += t to o }) { order += "b in" }
        release.complete(Unit); a.await()
        assertEquals(listOf("b in", "a out"), order)
        assertEquals(listOf(ExitTurns.Took.ALONE to null, ExitTurns.Took.TWO_AT_ONCE to "arm"), took)
        assertTrue(turns.busy().isEmpty())
    }

    @Test fun pastTheLongestWaitTheExitGoesAnyway() = runBlocking {
        val turns = ExitTurns(waitMs = 50)
        val took = ArrayList<ExitTurns.Took>()
        val firstIn = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val a = async { turns.turn(k, "stuck", true) { firstIn.complete(Unit); release.await() } }
        firstIn.await()
        var ran = false
        turns.turn(k, "expiry", true, { t, _, ms -> took += t; assertTrue(ms >= 50) }) { ran = true }
        assertTrue(ran, "never held back past the wait")
        assertEquals(listOf(ExitTurns.Took.LET_THROUGH), took)
        release.complete(Unit); a.await()
        // The lock is free again afterwards.
        turns.turn(k, "next", true, { t, _, _ -> took += t }) { }
        assertEquals(ExitTurns.Took.ALONE, took.last())
    }

    @Test fun aSenderInsideItsTurnTakesNoSecondTurn() = runBlocking {
        val turns = ExitTurns(waitMs = 50)
        val took = ArrayList<ExitTurns.Took>()
        turns.turn(k, "arm", true, { t, _, _ -> took += t }) {
            // Putting its stop back inside the same turn: no wait, no deadlock.
            turns.turn(k, "arm", true, { t, _, _ -> took += t }) { }
            // Another contract inside is its own turn.
            turns.turn(ExitDesk.key("NFO", "X"), "arm", true, { t, _, _ -> took += t }) { }
        }
        assertEquals(listOf(ExitTurns.Took.ALONE, ExitTurns.Took.AGAIN, ExitTurns.Took.ALONE), took)
        // Left once each: the contract is free again.
        assertTrue(turns.busy().isEmpty())
    }

    @Test fun inShadowTheSameSendersSecondTurnIsNotTwoAtOnce() = runBlocking {
        val turns = ExitTurns()
        val took = ArrayList<ExitTurns.Took>()
        turns.turn(k, "arm", false, { t, _, _ -> took += t }) { turns.turn(k, "arm", false, { t, _, _ -> took += t }) { } }
        assertEquals(listOf(ExitTurns.Took.ALONE, ExitTurns.Took.AGAIN), took)
        assertTrue(turns.busy().isEmpty())
    }

    @Test fun aPassLeftTwiceFreesOnlyOnce() = runBlocking {
        val turns = ExitTurns(waitMs = 50)
        val a = turns.enter(k, "a", true)
        a.leave(); a.leave()
        val b = turns.enter(k, "b", true)
        val took = ArrayList<ExitTurns.Took>()
        // b holds it now: a's second leave did not free b's turn.
        val c = async { turns.turn(k, "c", true, { t, _, _ -> took += t }) { } }
        c.await()
        assertEquals(listOf(ExitTurns.Took.LET_THROUGH), took)
        b.leave()
    }

    @Test fun differentContractsNeverWaitForEachOther() = runBlocking {
        val turns = ExitTurns(waitMs = 5_000)
        val firstIn = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val a = async { turns.turn(k, "a", true) { firstIn.complete(Unit); release.await() } }
        firstIn.await()
        var took: ExitTurns.Took? = null
        turns.turn(ExitDesk.key("NFO", "OTHER"), "b", true, { t, _, _ -> took = t }) { }
        assertEquals(ExitTurns.Took.ALONE, took)
        release.complete(Unit); a.await()
    }

    @Test fun aBlockThatThrowsFreesTheTurn() = runBlocking {
        val turns = ExitTurns(waitMs = 50)
        runCatching { turns.turn(k, "a", true) { error("refused") } }
        var took: ExitTurns.Took? = null
        turns.turn(k, "b", true, { t, _, _ -> took = t }) { }
        assertEquals(ExitTurns.Took.ALONE, took)
        assertTrue(turns.busy().isEmpty())
    }

    @Test fun theCountsLineSaysShadowAndOnPlainly() {
        val c = ExitDesk.Counts("2026-10-20", sent = 4, twoAtOnce = 1, waited = 2, waitedMs = 300, missedInShadow = 3, readAgain = 1, letThrough = 0, turnLetThrough = 1)
        val off = c.line(false)
        assertTrue(off.contains("shadow (logged only)") && off.contains("at once 1") && off.contains("just sent 3"), off)
        val on = c.line(true)
        assertTrue(on.contains("on;") && on.contains("same contract 2 (150 ms on average)") && on.contains("read again until the last exit showed 1") &&
            on.contains("longest wait 1"), on)
        assertTrue(ExitDesk.Counts().line(true).contains("same contract 0;"))
    }
}
