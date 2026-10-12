package com.optionslab.app.data

import com.optionslab.ira.PositionSync
import kotlinx.coroutines.withTimeoutOrNull
import java.time.format.DateTimeFormatter

/**
 * Zerodha reconciliation (round 4, report only): once a round of Jarvis's words-only checks, what the app believes is open
 * or resting - the arms' live positions and their exchange stops, Pine's live holdings and stops, the live protections -
 * against Zerodha's positions (the round's own shared read, [Broker.passPositionBook]: no extra call) and its order book as
 * a pass last read it ([AppState.orders], used only when fresh; never read for this). The decision is
 * [PositionSync.check]; a mismatch is said once it stood in two checks on two different reads, and a new one is put in
 * Jarvis's activity and the diagnostics once a day - names and quantities, never rupees, and never spoken.
 *
 * It NEVER places, changes or cancels an order, and never touches a book: it reads and reports.
 */
object ZerodhaSync {
    /** Zerodha's order book is judged only when its read is at most this old. */
    const val FRESH_MS = 120_000L

    @Volatile private var tracker = PositionSync.Tracker()
    @Volatile private var confirmed: List<PositionSync.Mismatch> = emptyList()
    @Volatile private var checkedAt: String? = null
    @Volatile private var why: String? = "not checked yet"
    /** The positions read the last check stepped on: the same read again is not a second check. */
    @Volatile private var lastRead = 0L

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * One check (the words pass, off the main thread; Zerodha's positions are the pass's one shared read). Returns the
     * confirmed mismatches, or null when nothing was checked (not logged in, no read, the books busy).
     */
    suspend fun check(): List<PositionSync.Mismatch>? {
        if (com.optionslab.app.BuildConfig.GOLD) return null
        if (!Broker.loggedIn) { why = "not logged in to Zerodha"; return null }
        val book = Broker.within(8_000) { Broker.passPositionBook() } ?: run { why = "Zerodha's positions could not be read"; return null }
        val read = AppState.positions.value?.at ?: System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val orders = AppState.orders.value?.takeIf { now - it.at in 0..FRESH_MS }?.value
        val beliefs = beliefs() ?: run { why = "the app's books were busy"; return null }
        val positions = book.net.filter { it.exchange in com.optionslab.engine.Kite.DERIVATIVE_EXCHANGES && it.qty != 0 }
            .groupBy { it.symbol }.mapValues { (_, v) -> v.sumOf { it.qty } }
        val found = PositionSync.check(beliefs, positions, orders?.map { PositionSync.Order(it.id, it.symbol, it.status, it.working) })
        if (read == lastRead) return confirmed                  // the same read: not a second look
        lastRead = read
        val day = runCatching { Market.today().toString() }.getOrDefault("")
        val t = tracker
        val step = synchronized(t) { t.step(day, found) }
        confirmed = step.confirmed
        checkedAt = runCatching { Market.now().toLocalTime().format(HHMM) }.getOrNull()
        why = null
        for (m in step.fresh) {
            val said = "Zerodha sync: ${PositionSync.say(m)}. Reported only; nothing was placed, changed or cancelled."
            runCatching { Diag.record("sync", said) }
            runCatching { com.optionslab.app.ira.IraActivity.add(said) }
        }
        return step.confirmed
    }

    /** What the app believes is open at Zerodha now; null when a book could not be read in time. */
    private suspend fun beliefs(): List<PositionSync.Belief>? {
        val out = ArrayList<PositionSync.Belief>()
        val arms = withTimeoutOrNull(3_000) { runCatching { OrbArms.liveOpen() }.getOrNull() } ?: return null
        arms.forEach { (label, p) ->
            val sym = p.kite ?: return@forEach
            out += PositionSync.Belief(label, sym, p.qty, stopOrderId = p.stopOrderId, unconfirmed = p.unconfirmed)
        }
        runCatching { PineAuto.held.value }.getOrNull()?.forEach { (id, h) ->
            val sym = h.kite ?: return@forEach
            if (h.live) out += PositionSync.Belief("Pine #$id", sym, h.qty, stopOrderId = h.stop, unconfirmed = h.unconfirmed)
        }
        val prot = withTimeoutOrNull(3_000) { runCatching { Protections.active() }.getOrNull() } ?: return null
        prot.filter { it.live && it.exchange in com.optionslab.engine.Kite.DERIVATIVE_EXCHANGES }.forEach { p ->
            // A protection being removed has its orders on the way out: its stop is not judged.
            out += PositionSync.Belief("Protection", p.symbol, p.qty, holds = false,
                stopOrderId = p.stopOrderId.takeIf { p.note != "removing" })
        }
        return out
    }

    /** The diagnostics / health line. */
    fun line(): String = PositionSync.line(confirmed, checkedAt, why)

    /** Jarvis's answer to "are my positions in sync" (an unlocked phone only). */
    fun answer(): String = PositionSync.answer(confirmed, checkedAt, why)

    /** TEST ONLY: nothing checked. */
    internal fun resetForTest() {
        confirmed = emptyList(); checkedAt = null; why = "not checked yet"; lastRead = 0L
        tracker = PositionSync.Tracker()
    }
}
