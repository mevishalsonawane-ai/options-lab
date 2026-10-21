package com.optionslab.app.data

import android.content.Context
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.FastLane
import com.optionslab.ira.OrderSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * Event-driven checks (Boss, 9 Oct: "order latency as low as possible"). The watch looked at stops, targets and exits every
 * 15 seconds (and decided entries a few seconds after each bar close): an exit could wait up to 15 s. Now a price arriving on
 * Zerodha's stream, or a minute candle closing, wakes the same checks at once:
 *
 *  - the stream's socket thread only hands the ticks over ([offer]: the latest per instrument, a wake), never runs a check;
 *  - one consumer on its OWN thread at an elevated priority (round 2: [android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY],
 *    never the main thread, never the shared IO pool) takes them and asks [FastLane.plan] which lanes are due;
 *  - each lane runs on a small pool of its own ([LANES] threads), independently: a lane still running is never started
 *    beside itself and never holds back another ([FastLane.Busy]) - a slow Zerodha read in the live lane cannot stall the
 *    stream's checks, and every Zerodha read on this path gives up after 3 s ([Broker.FastRead]);
 *  - round 2: exits of what is held at Zerodha decide from the stream's ticks while every held instrument has a fresh one
 *    (2 s), at the stream's pace (200 ms), with Zerodha's orders and positions as last read (the 15-second pass reads them
 *    again); only the order itself goes over REST. A stale instrument falls back to reading Zerodha, every 2 s;
 *  - round 2: the minute arms run at the first tick after a minute's close on candles built from the stream
 *    ([LocalCandles]), and again 3 s after it from the candle feed (the source of truth);
 *  - each step is the very function the watch's pass calls (under that arm's own lock, so nothing runs twice), with every
 *    gate it has: the kill switch, the day lock, the daily loss limit, the stale-price refusal, Live's PIN/fingerprint
 *    arming, paper-only arms, "AI trades go live", the arms that ask Boss first. Nothing here places an order itself.
 *  - nothing here writes the vault per tick: the books keep their in-memory, write-on-change saves.
 *
 * The watch's own 15-second pass stays as the safety net. Runs only while the watch runs (started and stopped with it),
 * never in IraGoldAlgo. Each run carries an [OrderTiming.Trigger], so an order it leads to is timed from the tick.
 */
object FastPath {
    private val inbox = FastLane.Inbox()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Threads for the lanes: one each for the stream, live, minute and bar-close lanes, so no lane waits for a thread. */
    private const val LANES = 4

    /** The consumer's own thread, at an elevated priority (an app may use it; a refused raise keeps the default). */
    private val consumer = Executors.newSingleThreadExecutor { r ->
        Thread({ raise(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY); r.run() }, "fast-path").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val laneThreads = java.util.concurrent.atomic.AtomicInteger()
    private val lanePool = Executors.newFixedThreadPool(LANES) { r ->
        Thread({ raise(android.os.Process.THREAD_PRIORITY_DISPLAY); r.run() }, "fast-lane-${laneThreads.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private fun raise(priority: Int) {
        runCatching { android.os.Process.setThreadPriority(priority) }
            .onFailure { runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE) } }
    }

    private val scope = CoroutineScope(SupervisorJob() + consumer)
    private val laneScope = CoroutineScope(SupervisorJob() + lanePool)
    private val busy = FastLane.Busy()
    @Volatile private var job: Job? = null
    @Volatile private var app: Context? = null

    val running: Boolean get() = job?.isActive == true

    /** From the stream's socket thread: kept (latest per instrument) and the consumer woken. Cheap; never blocks. */
    fun offer(got: List<KiteTicks.Tick>, now: Long) {
        if (job?.isActive != true) return
        for (t in got) inbox.offer(FastLane.Ev(t.token, now, t.exchangeTime))
        wake.trySend(Unit)
    }

    /** Watch runs that started it and have not stopped it yet (an old run ending never stops a newer run's checks). */
    private var owners = 0

    /** Started by each watch run (one consumer whatever the count). Not in IraGoldAlgo, which only talks. */
    fun start(context: Context) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        synchronized(this) {
            owners++
            app = context.applicationContext
            if (job?.isActive == true) return
            inbox.drain()
            job = scope.launch { runCatching { loop() } }
        }
    }

    /** A watch run ended: the consumer stops when no run is left (a lane already running finishes its step). */
    fun stop() {
        if (com.optionslab.app.BuildConfig.GOLD) return
        synchronized(this) {
            owners = (owners - 1).coerceAtLeast(0)
            if (owners > 0) return
            job?.cancel(); job = null
        }
        inbox.drain()
        runCatching { KiteStream.want(LIVE_OWNER, emptyList()) }
    }

    /** TEST ONLY: no consumer and no owner left from an earlier test. */
    internal fun resetForTest() {
        synchronized(this) { owners = 0; job?.cancel(); job = null }
        warmedAt = 0L; warming.set(false); deadKeys = null
        inbox.drain()
        snap = null
        modeAt = 0L
        faultForTest = null
    }

    // ---- what is held and armed, re-read at most every [STATE_MS] -----------------------------------------------------

    private const val STATE_MS = 500L
    /** The stream's follow list of the instruments held at Zerodha (their ticks decide the live exits). */
    private const val LIVE_OWNER = "live-exits"

    private class Snap(val at: Long, val st: FastLane.State, val tokens: Set<Long>, val paper: Boolean, val liveTokens: Set<Long>,
                       val liveActive: Boolean)
    @Volatile private var snap: Snap? = null

    /** Live mode, read from the settings at most every 5 s (never per tick). */
    @Volatile private var modeAt = 0L
    @Volatile private var liveMode = false

    private fun liveMode(now: Long): Boolean {
        if (now - modeAt !in 0 until 5_000L) { liveMode = runCatching { OrbArms.liveNow() }.getOrDefault(false); modeAt = now }
        return liveMode
    }

    /** Zerodha's instrument tokens of what is held there and watched by an app exit (ORB/Liquidity, Pine, protections). */
    /** The paper strategies' live trades' step's name ([ArmLive]). */
    const val ARMS_LIVE = "Arms live"

    internal fun liveTokens(): Set<Long> {
        val keys = (OrbArms.liveSymbolsHint.map { "NFO:$it" } + PineAuto.liveSymbols().map { "NFO:$it" } + Protections.liveKeysHint +
            ArmLive.liveKeysHint).distinct()
        // 20 Oct, review: memory only on this (the consumer's) thread. [Broker.tokenOf] built the day's map from the instruments
        // file (several MB) the first time after a restart - disk on the tick path. A token not in memory yet is looked up once
        // in the background ([warmTokens]) and the next snapshot has it; until then the stop is judged as before, on REST quotes.
        val out = HashSet<Long>(keys.size)
        var missing = false
        val dead = deadKeys?.takeIf { System.currentTimeMillis() - it.first in 0 until DEAD_MS }?.second.orEmpty()
        for (k in keys) runCatching { Broker.tokenKnown(k) }.getOrNull()?.let { out += it } ?: run { if (k !in dead) missing = true }
        if (missing) warmTokens(keys)
        return out
    }

    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var warmedAt = 0L
    /**
     * Keys the last background lookup could not resolve (an NSE stock, a list not downloaded yet) and when: not looked up again
     * every 5 s, only after [DEAD_MS] (the day's list may come in meanwhile).
     */
    @Volatile private var deadKeys: Pair<Long, Set<String>>? = null
    private const val DEAD_MS = 5 * 60_000L

    /** [keys]' tokens looked up off the tick path (the day's instrument map built from its file once), at most every 5 s. */
    private fun warmTokens(keys: List<String>) {
        val now = System.currentTimeMillis()
        if (now - warmedAt in 0 until WARM_MS || !warming.compareAndSet(false, true)) return
        warmedAt = now
        warmScope.launch {
            try {
                val dead = keys.filter { k -> runCatching { Broker.tokenOf(k) }.getOrNull() == null }.toSet()
                deadKeys = if (dead.isEmpty()) null else System.currentTimeMillis() to dead
            } finally { warming.set(false); snap = null }
        }
    }

    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val WARM_MS = 5_000L

    private fun snapshot(now: Long): Snap {
        snap?.takeIf { now - it.at in 0 until STATE_MS }?.let { return it }
        // Asked BEFORE the cover is read (13 Oct): the cover's own read starts the paper book's background load, which can
        // finish before the end of this function - asked afterwards, a "not covered" read from before the load was kept the
        // full 500 ms, and a live stop's tick in that window planned no LIVE lane (OrderPathRound2Test, after a quick load).
        val paperReady = runCatching { Paper.loaded() }.getOrDefault(true)
        val cover = runCatching { Paper.streamCover() }.getOrDefault(Paper.StreamCover(emptySet(), covered = false, any = false))
        val orbLive = OrbArms.liveHint
        val jarvis = com.optionslab.app.BuildConfig.JARVIS
        val anyLive = runCatching { com.optionslab.app.work.PositionCards.anyOpen }.getOrDefault(false)
        // 18 Oct: the paper strategies' live trades ([ArmLive]) are real-money positions too: the LIVE lane runs for them.
        val liveHolding = orbLive || PineAuto.holding() || Protections.activeHint != false || ArmLive.holdingHint || ArmLive.recoverHint
        val holding = cover.any || OrbArms.holdingHint
        val minuteArms = NightArm.view.value.let { it.armed || it.open.isNotEmpty() } ||
            VixDivArm.view.value.let { it.armed || it.open.isNotEmpty() } ||
            HeroTicketArm.holding() ||
            PassedArmsHost.holding() ||
            SignalPaperArm.holding() ||
            McxPaperArms.view.value.let { v -> v.armed.values.any { it } || v.open.isNotEmpty() } ||
            (jarvis && runCatching { com.optionslab.app.ira.IraSolo.holding() }.getOrDefault(false)) ||
            holding || liveHolding || anyLive
        val live = runCatching { liveTokens() }.getOrDefault(emptySet())
        // The held instruments followed on the stream (their ticks are what the live exits decide on).
        runCatching { KiteStream.want(LIVE_OWNER, live) }
        val liveFresh = live.isNotEmpty() && runCatching { KiteStream.allFresh(live) }.getOrDefault(false)
        val localCandles = runCatching { KiteStream.INDEX_TOKENS.any { LocalCandles.building(it) } }.getOrDefault(false)
        val st = FastLane.State(holding = holding, liveHolding = liveHolding, armed = OrbArms.armedHint, covered = cover.covered,
            minuteArms = minuteArms, liveFresh = liveFresh, localCandles = localCandles)
        val tokens = cover.tokens + KiteStream.followed() + KiteStream.INDEX_TOKENS + live
        val liveActive = live.isNotEmpty() || (OrbArms.armedHint && liveMode(now))
        // Built before the paper book was in memory (not covered for now): kept only briefly, so the cover is read again soon.
        val at = if (paperReady) now else now - STATE_MS + 25
        return Snap(at, st, tokens, cover.any, live, liveActive).also { snap = it }
    }

    // ---- the consumer ----------------------------------------------------------------------------------------------------

    private suspend fun loop() {
        val rolls = FastLane.Rolls()
        val gate = FastLane.Debounce()
        var carry: List<FastLane.Ev> = emptyList()
        var retryAt: Long? = null
        var minuteAt: Long? = null
        var minuteAgain: Long? = null
        var minuteTrigger: Long? = null
        var lastWarm: Long? = null
        while (currentCoroutineContext().isActive) {
            // G1-7: one pass that throws is recorded and the consumer carries on; it never ends the event-driven exits silently.
            try {
                val before = System.currentTimeMillis()
                val s0 = snap
                val warmAt = com.optionslab.ira.RouteWarm.next(before, lastWarm, liveActive = s0?.liveActive == true, armed = OrbArms.armedHint)
                val until = listOfNotNull(retryAt, minuteAt, minuteAgain, warmAt).minOrNull() ?: (before + 60_000)
                if (until > before) withTimeoutOrNull(until - before) { wake.receive() }
                faultForTest?.invoke()
                val now = System.currentTimeMillis()
                if (warmAt != null && now >= warmAt) {
                    lastWarm = now
                    runCatching { warmAhead() }
                }
                // The kept margins for a live entry's check: read again in the background when 25 s old (never on this thread).
                if (s0?.liveActive == true) runCatching { Broker.refreshMarginsIfDue() }
                val again = minuteAgain
                if (again != null && now >= again) {
                    minuteAgain = if (launchLane(FastLane.Lane.MINUTE, minuteTrigger ?: now, null, OrderSpeed.Source.FEED)) null else now + 500
                }
                val fresh = inbox.drain()
                val evs = if (carry.isEmpty()) fresh else (carry + fresh).associateBy { it.token }.values.toList()
                if (evs.isNotEmpty()) {
                    val s = snapshot(now)
                    val plan = FastLane.plan(evs, rolls, gate, now, s.st, busy.now()) { it in s.tokens }
                    carry = if (plan.retryMs != null) evs else emptyList()
                    retryAt = plan.retryMs?.let { now + it }
                    plan.minuteAtMs?.let { minuteAt = it; minuteTrigger = plan.triggerMs }
                    plan.minuteAgainMs?.let { minuteAgain = it }
                    for (lane in plan.lanes) {
                        val src = if (lane == FastLane.Lane.LIVE && !s.st.liveFresh) OrderSpeed.Source.FEED else OrderSpeed.Source.STREAM
                        launchLane(lane, plan.triggerMs ?: now, plan.triggerExchMs, src, s)
                    }
                } else retryAt = null
                // The minute lane at once when due now (at the close on local candles), else at its time.
                val due = minuteAt
                if (due != null && System.currentTimeMillis() >= due) {
                    minuteAt = null
                    if (!launchLane(FastLane.Lane.MINUTE, minuteTrigger ?: now, null, OrderSpeed.Source.FEED)) minuteAgain = minuteAgain ?: (now + 500)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                runCatching { com.optionslab.app.work.Tasks.stepFailed("fast check: consumer", e) }
                carry = emptyList(); retryAt = null
                // A fault that repeats never spins this thread.
                kotlinx.coroutines.delay(200)
            }
        }
    }

    /** TEST ONLY: run at each pass of the consumer, after its wait (a test makes it throw). */
    @Volatile internal var faultForTest: (() -> Unit)? = null

    /**
     * The order route warmed (relay, pooled connection, Zerodha's address, the static-IP reading): every 20 s while a Live
     * arm is armed or a live position is open, and 5 s before a bar close the armed arms decide on. Only in Live, logged
     * in, in market hours (NSE's, or MCX's).
     */
    private fun warmAhead() {
        if (!Broker.loggedIn) return
        if (!Market.isOpen() && !runCatching { McxMarket.isOpen() }.getOrDefault(false)) return
        if (!liveMode(System.currentTimeMillis())) return
        Broker.warmOrderRoute(entry = OrbArms.armedHint, force = true)
    }

    /** [lane] started on its own thread, unless it is still running (false). */
    private fun launchLane(lane: FastLane.Lane, triggerMs: Long, exchMs: Long?, source: OrderSpeed.Source, s: Snap? = snap): Boolean {
        val ctx = app ?: return false
        if (!busy.start(lane)) return false
        laneScope.launch {
            try {
                runLane(ctx, lane, triggerMs, exchMs, source, s)
            } finally {
                busy.end(lane)
                // A lane that held others' ticks back is looked at again now.
                wake.trySend(Unit)
            }
        }
        return true
    }

    /** One lane's steps, each in its own try (one failing never skips the next), timed from [triggerMs]. */
    private suspend fun runLane(ctx: Context, lane: FastLane.Lane, triggerMs: Long, exchMs: Long?, source: OrderSpeed.Source, s: Snap?) {
        // 10 Oct: the exits' lanes go first in Zerodha's shared request budget (the entries' lanes as ordinary requests).
        val priority = if (lane == FastLane.Lane.STREAM || lane == FastLane.Lane.LIVE) Broker.Lane(com.optionslab.ira.RateGate.Priority.SAFETY)
            else kotlin.coroutines.EmptyCoroutineContext
        withContext(OrderTiming.Trigger(triggerMs, exchMs, source) + Broker.FastRead() + priority) {
            when (lane) {
                FastLane.Lane.STREAM -> {
                    // The trade manager first (10 Oct; memory only, at most once a second): an exit it wants is then taken here.
                    runCatching { TradeManagerHost.onLook() }
                    if (s?.paper != false) step("paper orders") { com.optionslab.app.work.Tasks.paperEventsPublic(ctx, Paper.tick()) }
                    if (OrbArms.holdingHint && !OrbArms.liveHint) step("ORB arms") { OrbArms.priceCheckOnly(fast = true) }
                }
                // Jarvis's news trades are left to the watch's passes: their exits rest as protections, checked here.
                // [stream]: every held instrument has a fresh tick - decided from the stream, Zerodha's books as last read.
                FastLane.Lane.LIVE -> com.optionslab.app.work.TradingBusy.during {
                    // Real-money exits: Jarvis's model gives way while they run (10 Oct).
                    val stream = s?.st?.liveFresh == true
                    // 18 Oct, review: the ORB book's look also marks its paper positions, so it waits for those to be streamed
                    // (the 15-second pass covers it meanwhile); the stops and targets below run on every live tick regardless.
                    if (OrbArms.liveHint && s?.st?.let { FastLane.liveMarksPaper(it) } != false) step("ORB arms") { OrbArms.priceCheckOnly(fast = true, stream = stream) }
                    if (PineAuto.holding()) step("Pine scripts") { PineAuto.watchOnly(fast = true, stream = stream) }
                    if (Protections.activeHint != false) step("stops and targets") { Protections.tick(fast = true, stream = stream) }
                    // 18 Oct: the paper strategies' live trades ([ArmLive]): their stops, the paper exits they follow, 15:15.
                    if (ArmLive.holdingHint || ArmLive.recoverHint) step(ARMS_LIVE) { ArmLive.watch(stream = stream) }
                    // The trade manager after the exits (R4): nothing runs before a real-money exit; an exit it decides here wakes
                    // the strategy's own look at once. Never waits on another lane's look ([TradeManagerHost.onLook]).
                    runCatching { TradeManagerHost.onLook() }
                    Unit
                }
                FastLane.Lane.MINUTE -> {
                    runCatching { TradeManagerHost.onLook() }
                    step("Night (R3)") { NightArm.tick() }
                    step("VIX divergence") { VixDivArm.tick() }
                    step("MCX paper arms") { McxPaperArms.tick() }
                    step("Forge ideas") { GrammarArm.tick() }
                    if (com.optionslab.app.BuildConfig.JARVIS) step("Solo") { com.optionslab.app.ira.IraSolo.manageOnly() }
                    step("missed-lock sweep") { Sweeper.run(ctx) }
                    // 15 Oct: the Hero ₹1,000 ticket (paper): its open tickets' trail, target and 15:05 every minute (exits only - its
                    // 12:30 chain reads run in the watch's arms passes, never in this lane).
                    // 16 Oct: an arm's paper position with no record in its arm's book is taken back (or protected) before their exits.
                    step("unwatched positions") { OrphanWatch.run(ctx) }
                    step(HERO_TICKET) { HeroTicketArm.manageOnly() }
                    // 15 Oct: the passed-research paper arms (R51, R52, Solo 2.0): their open trades' stops, targets, locks, time exits
                    // and square-offs every minute (exits only - their entries' reads run in the watch's arms passes).
                    step(PASSED_ARMS) { PassedArmsHost.manageOnly() }
                    // 15 Oct: a Telegram chat's calls on paper (Boss's choice): their stops, targets and square-off every minute (exits only).
                    step(SIGNAL_PAPER) { SignalPaperArm.manageOnly() }
                    // 18 Oct: after the paper exits above, their live trades follow at once ([ArmLive]; nothing held: returns at once).
                    // (A shadow trade or a damaged book's recovery too: no order for a shadow, its record checked every minute.)
                    if (ArmLive.lookDue) step(ARMS_LIVE) { ArmLive.watch() }
                    // Smart scheduling (10 Oct): these looked at this minute; the watch's passes need not look again for it.
                    runCatching { SmartWorkers.ranByLane(MINUTE_ARMS) }
                }
                FastLane.Lane.BAR_CLOSE -> {
                    if (s?.paper != false) step("paper orders") { com.optionslab.app.work.Tasks.paperEventsPublic(ctx, Paper.tick()) }
                    step("ORB arms (bar close)") { OrbArms.tick() }
                }
            }
            Unit
        }
    }

    /** The watch's step names of the arms these lanes run (the smart scheduler counts their looks as done). */
    private val MINUTE_ARMS = listOf("Night (R3)", "VIX divergence", "MCX paper arms", "Forge ideas")
    /**
     * The Hero ₹1,000 ticket's step name (the minute lane and the watch's rounds). Not a smart-scheduler step: the rounds include
     * it only when it needs a pass ([HeroTicketArm.wantsPass]), and then it always runs.
     */
    const val HERO_TICKET = "Hero 1000 ticket"
    /** The passed-research paper arms' step name (as [HERO_TICKET]: in the rounds only when they need a pass, [PassedArmsHost.wantsPass]). */
    const val PASSED_ARMS = "Passed-research arms"
    /** The Telegram calls' paper arm's step name (as [HERO_TICKET]: in the rounds only when it needs a pass, [SignalPaperArm.wantsPass]). */
    const val SIGNAL_PAPER = "Telegram calls (paper)"

    private suspend inline fun step(name: String, block: () -> Unit) {
        // Measured (10 Oct): each check's time today, beside the watch's steps.
        val t0 = System.currentTimeMillis()
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.optionslab.app.work.Tasks.stepFailed("fast check: $name", e)
        } finally {
            com.optionslab.app.work.WatchWorkers.timed("fast check: $name", System.currentTimeMillis() - t0)
        }
    }
}
