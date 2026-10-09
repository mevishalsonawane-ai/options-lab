package com.optionslab.app.data

import android.content.Context
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.FastLane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Event-driven checks (Boss, 9 Oct: "order latency as low as possible"). The watch looked at stops, targets and exits every
 * 15 seconds (and decided entries a few seconds after each bar close): an exit could wait up to 15 s. Now a price arriving on
 * Zerodha's stream, or a minute candle closing, wakes the same checks at once:
 *
 *  - the stream's socket thread only hands the ticks over ([offer]: the latest per instrument, a wake), never runs a check;
 *  - one consumer coroutine on the IO dispatcher (never the main thread) takes them, asks [FastLane.plan] which lanes are
 *    due (bursts collapse; each lane at most every 200 ms - 2 s where Zerodha is read - unless a candle closed) and runs
 *    them one at a time;
 *  - each step is the very function the watch's pass calls (under that arm's own lock, so nothing runs twice), with every
 *    gate it has: the kill switch, the day lock, the daily loss limit, the stale-price refusal, Live's PIN/fingerprint
 *    arming, paper-only arms, "AI trades go live", the arms that ask Boss first. Nothing here places an order itself.
 *  - exits keep their 1-minute high/low meaning: a tick crossing a stop is what the minute's wick would have shown;
 *  - nothing here writes the vault per tick: the books keep their in-memory, write-on-change saves.
 *
 * The watch's own 15-second pass stays as the safety net. Runs only while the watch runs (started and stopped with it),
 * never in IraGoldAlgo. Each run carries an [OrderTiming.Trigger], so an order it leads to is timed from the tick.
 */
object FastPath {
    private val inbox = FastLane.Inbox()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    /** A watch run ended: the consumer stops when no run is left. */
    fun stop() {
        if (com.optionslab.app.BuildConfig.GOLD) return
        synchronized(this) {
            owners = (owners - 1).coerceAtLeast(0)
            if (owners > 0) return
            job?.cancel(); job = null
        }
        inbox.drain()
    }

    /** TEST ONLY: no consumer and no owner left from an earlier test. */
    internal fun resetForTest() {
        synchronized(this) { owners = 0; job?.cancel(); job = null }
        inbox.drain()
        snap = null
    }

    // ---- what is held and armed, re-read at most every [STATE_MS] -----------------------------------------------------

    private const val STATE_MS = 500L
    private class Snap(val at: Long, val st: FastLane.State, val tokens: Set<Long>, val paper: Boolean)
    @Volatile private var snap: Snap? = null

    private fun snapshot(now: Long): Snap {
        snap?.takeIf { now - it.at in 0 until STATE_MS }?.let { return it }
        val cover = runCatching { Paper.streamCover() }.getOrDefault(Paper.StreamCover(emptySet(), covered = false, any = false))
        val orbLive = OrbArms.liveHint
        val jarvis = com.optionslab.app.BuildConfig.JARVIS
        val anyLive = runCatching { com.optionslab.app.work.PositionCards.anyOpen }.getOrDefault(false)
        val liveHolding = orbLive || PineAuto.holding() || Protections.activeHint != false
        val holding = cover.any || OrbArms.holdingHint
        val minuteArms = NightArm.view.value.let { it.armed || it.open.isNotEmpty() } ||
            VixDivArm.view.value.let { it.armed || it.open.isNotEmpty() } ||
            McxPaperArms.view.value.let { v -> v.armed.values.any { it } || v.open.isNotEmpty() } ||
            (jarvis && runCatching { com.optionslab.app.ira.IraSolo.holding() }.getOrDefault(false)) ||
            holding || liveHolding || anyLive
        val st = FastLane.State(holding = holding, liveHolding = liveHolding, armed = OrbArms.armedHint, covered = cover.covered, minuteArms = minuteArms)
        val tokens = cover.tokens + KiteStream.followed() + KiteStream.INDEX_TOKENS
        return Snap(now, st, tokens, cover.any).also { snap = it }
    }

    // ---- the consumer ----------------------------------------------------------------------------------------------------

    private suspend fun loop() {
        val rolls = FastLane.Rolls()
        val gate = FastLane.Debounce()
        var carry: List<FastLane.Ev> = emptyList()
        var retryAt: Long? = null
        var minuteAt: Long? = null
        var minuteTrigger: Long? = null
        var warmAt = FastLane.warmAt(System.currentTimeMillis())
        while (currentCoroutineContext().isActive) {
            val before = System.currentTimeMillis()
            val until = listOfNotNull(retryAt, minuteAt, warmAt).minOrNull() ?: (before + 60_000)
            withTimeoutOrNull((until - before).coerceAtLeast(1L)) { wake.receive() }
            val now = System.currentTimeMillis()
            if (now >= warmAt) {
                warmAt = FastLane.warmAt(now)
                runCatching { warmAhead() }
            }
            val due = minuteAt
            if (due != null && now >= due) {
                minuteAt = null
                runLane(FastLane.Lane.MINUTE, minuteTrigger ?: now, null)
            }
            val fresh = inbox.drain()
            val evs = if (carry.isEmpty()) fresh else (carry + fresh).associateBy { it.token }.values.toList()
            if (evs.isEmpty()) { retryAt = null; continue }
            val s = snapshot(now)
            val plan = FastLane.plan(evs, rolls, gate, now, s.st) { it in s.tokens }
            carry = if (plan.retryMs != null) evs else emptyList()
            retryAt = plan.retryMs?.let { now + it }
            plan.minuteAtMs?.let { minuteAt = it; minuteTrigger = plan.triggerMs }
            for (lane in plan.lanes) runLane(lane, plan.triggerMs ?: now, plan.triggerExchMs)
        }
    }

    /** 5 s before a bar close the armed arms decide on: the order route warmed (relay, pooled connection, static IP). */
    private fun warmAhead() {
        if (!OrbArms.armedHint || !Broker.loggedIn || !Market.isOpen()) return
        if (!AppSettings.load().live) return
        Broker.warmOrderRoute(entry = true, force = true)
    }

    /** One lane's steps, each in its own try (one failing never skips the next), timed from [triggerMs]. */
    private suspend fun runLane(lane: FastLane.Lane, triggerMs: Long, exchMs: Long?) {
        val ctx = app ?: return
        withContext(OrderTiming.Trigger(triggerMs, exchMs)) {
            when (lane) {
                FastLane.Lane.STREAM -> {
                    if (snap?.paper != false) step("paper orders") { com.optionslab.app.work.Tasks.paperEventsPublic(ctx, Paper.tick()) }
                    if (OrbArms.holdingHint && !OrbArms.liveHint) step("ORB arms") { OrbArms.priceCheckOnly(fast = true) }
                }
                // Jarvis's news trades are left to the watch's passes: their exits rest as protections, checked here.
                FastLane.Lane.LIVE -> {
                    if (OrbArms.liveHint) step("ORB arms") { OrbArms.priceCheckOnly(fast = true) }
                    if (PineAuto.holding()) step("Pine scripts") { PineAuto.watchOnly(fast = true) }
                    if (Protections.activeHint != false) step("stops and targets") { Protections.tick(fast = true) }
                }
                FastLane.Lane.MINUTE -> {
                    step("Night (R3)") { NightArm.tick() }
                    step("VIX divergence") { VixDivArm.tick() }
                    step("MCX paper arms") { McxPaperArms.tick() }
                    if (com.optionslab.app.BuildConfig.JARVIS) step("Solo") { com.optionslab.app.ira.IraSolo.manageOnly() }
                    step("missed-lock sweep") { Sweeper.run(ctx) }
                }
                FastLane.Lane.BAR_CLOSE -> {
                    if (snap?.paper != false) step("paper orders") { com.optionslab.app.work.Tasks.paperEventsPublic(ctx, Paper.tick()) }
                    step("ORB arms (bar close)") { OrbArms.tick() }
                }
            }
            Unit
        }
    }

    private suspend inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.optionslab.app.work.Tasks.stepFailed("fast check: $name", e)
        }
    }
}
