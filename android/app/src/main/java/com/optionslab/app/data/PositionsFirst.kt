package com.optionslab.app.data

import android.content.Context
import com.optionslab.ira.StartOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * OPEN POSITIONS AND ORDERS COME FIRST (Boss, 14 Oct; the memory audit's item 10). Android ended the app for memory about
 * 20 times in three days; each time it starts again, the open positions and their stops are restored and watched before
 * the start's other reads of the encrypted books (Jarvis's memory and the shared knowledge wait for it):
 *
 *  1. [StartOrder.Step.POSITIONS] - on a thread of its own at the top priority, started first thing in the app's start: the
 *     watch is asked at once (17 Oct; it waits only for the start's restore-disarm step, and is a no-op when it runs already
 *     or is not due) while every position book (paper NSE and MCX, the ORB family with its live positions, the protections
 *     with Zerodha's stops, the Pine scripts' holdings, the night, VIX and MCX arms, the Hero ₹1,000 ticket, the expiry
 *     ticket, the Strategy Module's runs) is read, so the fast exits' memory signals are right from the first tick; the
 *     slowest book (the Strategy Module's: 4.4 s at Boss's 17 Oct restart) no longer holds the watch back. Books that hold
 *     no position (the grammar arm's) are read after step 2 ([restoreLate]).
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
            runBlocking { steps(::restore, { runCatching { com.optionslab.app.work.Jobs.ensureWatch(app) } }, { runCatching { KiteStream.ensure() } }, ::restoreLate) }
            // 16 Oct: why Android ended the process last time (its own record), after the positions are watched.
            runCatching { com.optionslab.app.work.ProcessExits.read(app) }
        // The nice level only (review, 14 Oct: Java's MAX_PRIORITY would be inherited by every thread this one creates).
        }, "positions-first").apply { isDaemon = true }.start()
    }

    /** The app's start has done its synchronous steps (called at the end of the app's onCreate). */
    fun appReady() { appReady.complete(Unit) }

    /**
     * Steps 1 and 2: [watch] asked as soon as the app's start is done, with [restore] (true: something is open) reading the
     * position books beside it; then [stream]; then [late], the books off the critical path (the stream asked again when
     * they hold something). Each step is marked in [order] the moment it ends. Internal: the tests run it with their own steps.
     */
    internal suspend fun steps(restore: suspend () -> Boolean, watch: () -> Unit, stream: () -> Unit, late: suspend () -> Boolean = { false },
                               clock: () -> Long = { android.os.SystemClock.uptimeMillis() }): Unit = kotlinx.coroutines.coroutineScope {
        // 17 Oct (Boss's diagnostics: "positions +8707 ms ... slowest book: strategies 4411 ms"): the watch was asked only
        // after every book had been read, so it waited on the slowest of them. The books are read now while the watch is
        // asked: it reads what it checks itself (each book under its own lock), so nothing open waits for an unrelated book.
        val restoring = async(kotlinx.coroutines.Dispatchers.IO) { runCatching { restore() }.getOrDefault(true) }
        // The restore-disarm step of the app's start runs before any watch can tick (a few ms). Not done in time (a start
        // that took longer than [WAIT_MS]): this thread does not start the watch; the start's own alarms and the screens do.
        // 16 Oct: asked whether anything is open or not - [watch] (Jobs.ensureWatch) decides itself, and is a no-op when the
        // watch is not due. With nothing open it was never asked, so an MCX arm armed in its evening (nothing held yet) stayed
        // unwatched after Android ended the app, until the next dead-man check ("Session coverage", 15 Oct 17:56).
        if (withTimeoutOrNull(WAIT_MS) { appReady.await() } != null) { watch(); watchAskedAt = clock() }
        val open = restoring.await()
        // A start slower than [WAIT_MS] (review, 17 Oct): as before, the watch is still asked once the restore is done and the
        // start finishes within [WAIT_MS] more.
        if (watchAskedAt == 0L && withTimeoutOrNull(WAIT_MS) { appReady.await() } != null) { watch(); watchAskedAt = clock() }
        foundOpen = open
        order.done(StartOrder.Step.POSITIONS, clock())
        runCatching { Diag.record("start", "positions first: ${if (open) "open positions or orders found, watch started" else "nothing open"} " +
            "${order.positionsWatchedAfter(startedAt)?.let { "($it ms after the start)" } ?: ""}".trimEnd()) }
        stream()
        order.done(StartOrder.Step.STREAM, clock())
        // The books that hold no position, read after the stream (17 Oct); one unreadable asks the stream again (it decides).
        val lateOpen = runCatching { late() }.getOrDefault(true)
        if (lateOpen) {
            if (!open) foundOpen = true
            stream()
        }
    }

    /** When the watch was asked at this start (uptime ms; 0: not yet, or the start took too long). */
    @Volatile var watchAskedAt = 0L
        private set

    /**
     * Every position book read now (each one's own lock; a book already read is not read again), true when any position,
     * order, protection or running strategy is open - or when a book could not be read (then the watch decides).
     */
    internal suspend fun restore(): Boolean {
        val books = ArrayList<Pair<String, suspend () -> Boolean>>()
        fun one(name: String, f: suspend () -> Boolean) { books += name to f }
        // The paper account first (NSE and MCX paper positions and resting orders: the fast exits' STREAM lane).
        one("paper") { Paper.watching() }
        one("ORB") { OrbArms.holding() }
        // 18 Oct: the paper strategies' live trades (their own book, [ArmLive]) - restored here, with their Zerodha stops, first.
        one("arms live") { ArmLive.holding() }
        one("protections") { Protections.active().isNotEmpty() }
        one("Pine") { PineAuto.load(); PineAuto.holding() }
        // The Strategy Module's runs are open positions too (CLAUDE.md: restored first). Its book is the slowest (4.4 s at Boss's
        // 17 Oct restart), so it is started first among the books; the watch no longer waits for it (asked beside the restore).
        one("strategies") { Strategies.anyRunning() }
        one("MCX arms") { McxPaperArms.refresh().open.isNotEmpty() }
        one("night") { NightArm.refresh().open.isNotEmpty() }
        one("VIX") { VixDivArm.refresh().open.isNotEmpty() }
        // 15 Oct: the Hero ₹1,000 ticket's paper tickets (its own book) - restored here, before any other start-up work.
        one("Hero ticket") { HeroTicketArm.refresh().open.isNotEmpty() }
        // 21 Oct: FT3's paper tickets (its own book) - restored here, before any other start-up work.
        one("FT3") { Ft3Arm.refresh().open.isNotEmpty() }
        // 15 Oct: the passed-research paper arms (R51, R52, Solo 2.0; their own book) - restored here, with their stops, first.
        one("passed arms") { PassedArmsHost.refresh().open.isNotEmpty() }
        // 15 Oct: a Telegram chat's calls on paper (Boss's choice; its own book) - restored here, with their stops, first.
        one("Telegram calls") { SignalPaperArm.refresh().open.isNotEmpty() }
        one("ticket") { Ledger.openTicket() != null }
        one("MCX live") { McxMarket.liveExposureOrThrow() }
        // Jarvis's own paper trades (review, 14 Oct; the fast exits' minute lane counts them).
        if (com.optionslab.app.BuildConfig.JARVIS) one("Jarvis") { com.optionslab.app.ira.IraSolo.holdingOrThrow() }
        return readAll(books)
    }

    /**
     * The books read after the stream, off the start's critical path (17 Oct): those that hold no position (the grammar arm's
     * ideas). True when one could not be read.
     */
    internal suspend fun restoreLate(): Boolean = readAll(listOf(
        "grammar" to (suspend { GrammarArm.refresh(); false }),
    ), into = { lateSlowest = it })

    /** The slowest of the books read after the stream ("grammar 12 ms"), for the diagnostics. */
    @Volatile var lateSlowest: String? = null
        private set

    /**
     * 16 Oct (Boss's diagnostics: "positions +15640 ms" after each restart): the books were read one after another, each a
     * Keystore decryption, so the last one waited for all the others. They are read side by side now ([PARALLEL] at a time,
     * each book under its own lock as before), and the slowest one is named in the diagnostics ([slowest]). True when any
     * book holds something open, or when any book could not be read (then the watch decides).
     */
    internal suspend fun readAll(books: List<Pair<String, suspend () -> Boolean>>, clock: () -> Long = { android.os.SystemClock.uptimeMillis() },
                                 into: (String?) -> Unit = { slowest = it }): Boolean {
        val open = java.util.concurrent.atomic.AtomicBoolean(false)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val times = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val gate = kotlinx.coroutines.sync.Semaphore(PARALLEL)
        kotlinx.coroutines.coroutineScope {
            for ((name, f) in books) launch(kotlinx.coroutines.Dispatchers.IO) {
                gate.acquire()
                val at = clock()
                try { if (f()) open.set(true) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { failed.set(true) }
                finally { times[name] = clock() - at; gate.release() }
            }
        }
        into(times.maxByOrNull { it.value }?.let { "${it.key} ${it.value} ms" })
        return open.get() || failed.get()
    }

    /** Books read at once at the restore (the Keystore does little in parallel; this keeps the queue short). */
    private const val PARALLEL = 4
    /** The slowest book at the last restore ("paper 812 ms"), for the diagnostics. */
    @Volatile var slowest: String? = null
        private set

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
        val asked = watchAskedAt.takeIf { it > 0L }?.let { "watch asked +${it - s} ms, " } ?: ""
        return "Start-up: $asked${steps.ifEmpty { "running" }}" + (slowest?.let { " (slowest book: $it)" } ?: "") +
            (lateSlowest?.let { " (read after the stream: $it)" } ?: "") +
            (foundOpen?.let { if (it) "; open positions found and watched first" else "; nothing open" } ?: "")
    }

    /** TEST ONLY: a fresh start ([startedAt] set as if the app had just started). */
    internal fun resetForTest(started: Boolean) {
        order = StartOrder(); appReady = CompletableDeferred(); foundOpen = null; slowest = null; lateSlowest = null; watchAskedAt = 0L
        startedAt = if (started) android.os.SystemClock.uptimeMillis().coerceAtLeast(1L) else 0L
    }
}
