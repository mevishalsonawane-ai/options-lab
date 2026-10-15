package com.optionslab.app.data

import android.content.Context
import com.optionslab.ira.StartOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * OPEN POSITIONS AND ORDERS COME FIRST (Boss, 14 Oct; the memory audit's item 10). Android ended the app for memory about
 * 20 times in three days; each time it starts again, the open positions and their stops are restored and watched before
 * the start's other reads of the encrypted books (Jarvis's memory and the shared knowledge wait for it):
 *
 *  1. [StartOrder.Step.POSITIONS] - on a thread of its own at the top priority, started first thing in the app's start: every
 *     position book (paper NSE and MCX, the ORB family with its live positions, the protections with Zerodha's stops, the Pine
 *     scripts' holdings, the Strategy Module's runs, the night, VIX and MCX arms, the Hero ₹1,000 ticket, the expiry ticket) is read, so the fast
 *     exits' memory signals are right from the first tick; then, when anything is open, the watch is started (it is a no-op
 *     when it runs already, and it waits for the start's restore-disarm step first).
 *  2. [StartOrder.Step.STREAM] - the price stream brought back ([KiteStream.ensure]); the arms and the paper book are read.
 *  3. [StartOrder.Step.KNOWLEDGE] - only then the shared knowledge and the research index, in the background
 *     ([SharedKnowledgeHost]); the warm-up reads of the parts that never trade (Jarvis's lessons, the order review, the
 *     problems list, Autopilot, self-growth, the strategy lab, the market context, the app's health) wait for step 1
 *     ([awaitPositions]).
 *
 * Nothing here places, changes or refuses an order. A failure in any read counts as "something may be open" (the watch is
 * started; it decides for itself). Never on the main thread; never on the order or tick path.
 */
object PositionsFirst {
    /** The start-up's steps, in order (the diagnostics line and the tests read it). */
    @Volatile var order = StartOrder()
        private set
    /** When this process's start began (uptime ms; 0: never started - the unit tests' app). */
    @Volatile var startedAt = 0L
        private set
    /** The app's start finished its own synchronous steps (the restore-disarm among them): the watch may start. */
    @Volatile private var appReady = CompletableDeferred<Unit>()
    /** What step 1 found open (null: not yet). */
    @Volatile var foundOpen: Boolean? = null
        private set

    /** The parts that never trade wait at most this long for step 1 (it is milliseconds normally; never forever). */
    private const val WAIT_MS = 20_000L

    /** The first thing the app's start does after the settings: step 1 and 2 on a thread of their own. */
    fun start(context: Context) {
        if (com.optionslab.app.BuildConfig.GOLD) {
            // IraGoldAlgo's books are its own (GoldBooks, read at start); nothing here to restore.
            startedAt = android.os.SystemClock.uptimeMillis()
            order.done(StartOrder.Step.POSITIONS, startedAt); order.done(StartOrder.Step.STREAM, startedAt)
            return
        }
        val app = context.applicationContext
        startedAt = android.os.SystemClock.uptimeMillis()
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND) }
            runBlocking { steps(::restore, { runCatching { com.optionslab.app.work.Jobs.ensureWatch(app) } }, { runCatching { KiteStream.ensure() } }) }
        // The nice level only (review, 14 Oct: Java's MAX_PRIORITY would be inherited by every thread this one creates).
        }, "positions-first").apply { isDaemon = true }.start()
    }

    /** The app's start has done its synchronous steps (called at the end of the app's onCreate). */
    fun appReady() { appReady.complete(Unit) }

    /**
     * Steps 1 and 2: [restore] (true: something is open), then [watch] when it is (after the app's start is done), then
     * [stream]. Each step is marked in [order] the moment it ends. Internal: the tests run it with their own steps.
     */
    internal suspend fun steps(restore: suspend () -> Boolean, watch: () -> Unit, stream: () -> Unit, clock: () -> Long = { android.os.SystemClock.uptimeMillis() }) {
        val open = runCatching { restore() }.getOrDefault(true)
        foundOpen = open
        if (open) {
            // The restore-disarm step of the app's start runs before any watch can tick (a few ms). Not done in time (a start
            // that took longer than [WAIT_MS]): this thread does not start the watch; the start's own alarms and the screens do.
            if (withTimeoutOrNull(WAIT_MS) { appReady.await() } != null) watch()
        }
        order.done(StartOrder.Step.POSITIONS, clock())
        runCatching { Diag.record("start", "positions first: ${if (open) "open positions or orders found, watch started" else "nothing open"} " +
            "${order.positionsWatchedAfter(startedAt)?.let { "($it ms after the start)" } ?: ""}".trimEnd()) }
        stream()
        order.done(StartOrder.Step.STREAM, clock())
    }

    /**
     * Every position book read now (each one's own lock; a book already read is not read again), true when any position,
     * order, protection or running strategy is open - or when a book could not be read (then the watch decides).
     */
    internal suspend fun restore(): Boolean {
        var open = false
        var failed = false
        suspend fun one(f: suspend () -> Boolean) { runCatching { if (f()) open = true }.onFailure { failed = true } }
        // The paper account first (NSE and MCX paper positions and resting orders: the fast exits' STREAM lane).
        one { Paper.watching() }
        one { OrbArms.holding() }
        one { Protections.active().isNotEmpty() }
        one { PineAuto.load(); PineAuto.holding() }
        one { Strategies.anyRunning() }
        one { McxPaperArms.refresh().open.isNotEmpty() }
        one { NightArm.refresh().open.isNotEmpty() }
        one { VixDivArm.refresh().open.isNotEmpty() }
        // 15 Oct: the Hero ₹1,000 ticket's paper tickets (its own book) - restored here, before any other start-up work.
        one { HeroTicketArm.refresh().open.isNotEmpty() }
        // 15 Oct: the passed-research paper arms (R51, R52, Solo 2.0; their own book) - restored here, with their stops, first.
        one { PassedArmsHost.refresh().open.isNotEmpty() }
        // 15 Oct: a Telegram chat's calls on paper (Boss's choice; its own book) - restored here, with their stops, first.
        one { SignalPaperArm.refresh().open.isNotEmpty() }
        one { GrammarArm.refresh(); false }
        one { Ledger.openTicket() != null }
        one { McxMarket.liveExposureOrThrow() }
        // Jarvis's own paper trades (review, 14 Oct; the fast exits' minute lane counts them).
        if (com.optionslab.app.BuildConfig.JARVIS) one { com.optionslab.app.ira.IraSolo.holdingOrThrow() }
        return open || failed
    }

    /**
     * Suspends until step 1 is done (at most [WAIT_MS]); at once when this process never ran it (the unit tests' app) or it
     * is done already. For the warm-up reads of the parts that never trade.
     */
    suspend fun awaitPositions() {
        if (startedAt == 0L || order.isDone(StartOrder.Step.POSITIONS)) return
        withTimeoutOrNull(WAIT_MS) { order.await(StartOrder.Step.POSITIONS) }
    }

    /** Suspends until step 2 is done (at most [WAIT_MS] more); at once when never started. */
    suspend fun awaitStream() {
        if (startedAt == 0L || order.isDone(StartOrder.Step.STREAM)) return
        withTimeoutOrNull(WAIT_MS) { order.await(StartOrder.Step.STREAM) }
    }

    /** The diagnostics' line: the start-up's steps and when each ended. */
    fun diagLine(): String {
        val s = startedAt
        if (s == 0L) return "Start-up: not run in this process"
        val steps = order.log().joinToString(", ") { (step, at) -> "${step.name.lowercase()} +${at - s} ms" }
        return "Start-up: ${steps.ifEmpty { "running" }}" + (foundOpen?.let { if (it) "; open positions found and watched first" else "; nothing open" } ?: "")
    }

    /** TEST ONLY: a fresh start ([startedAt] set as if the app had just started). */
    internal fun resetForTest(started: Boolean) {
        order = StartOrder(); appReady = CompletableDeferred(); foundOpen = null
        startedAt = if (started) android.os.SystemClock.uptimeMillis().coerceAtLeast(1L) else 0L
    }
}
