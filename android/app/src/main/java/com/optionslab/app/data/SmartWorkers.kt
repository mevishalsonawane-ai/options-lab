package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.DriftReview
import com.optionslab.ira.EntryBrake
import com.optionslab.ira.Findings
import com.optionslab.ira.ForwardCheck
import com.optionslab.ira.MarketBrain
import com.optionslab.ira.MoveEvents
import com.optionslab.ira.SelfHeal
import com.optionslab.ira.WorkerWake
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * The smart workers on the phone (Boss, 10 Oct: smart scheduling, self-healing, one shared market brain, self-review and
 * auto-park, and the findings bus; the logic is ira-core's - [WorkerWake], [SelfHeal], [MarketBrain], [Findings],
 * [DriftReview]). One object every worker and strategy reads:
 *
 *  - [brain]: the market picture (regime, today's event windows, the trap flags, the big-move alarm, the data's health),
 *    refreshed by its own worker every [REFRESH_MS] ([refresh]); readers only read memory.
 *  - [bus]: the findings bus - any worker posts ([found]), any reads; written to a daily log under noBackupFilesDir/findings
 *    at most every 30 s, never per tick.
 *  - [health]: each dependency's health (Zerodha's requests sampled as they are answered, the stream, the relay, the candle
 *    feed, the phone); an incident is told ONCE (a notification and a line from Jarvis) and again when it is over.
 *  - [sched]: which arms are due at a look ([dueSteps]); never one while anything is held.
 *  - [entry]: the one question every NEW entry asks (the global pause and the findings' reactions, [EntryBrake]); an exit
 *    never asks it. [doorRefusal]: the entry door's part (news windows ON, unreliable data, the relay down), memory only.
 *  - [reviewIfDue]: the daily self-review after 15:45 - a drifting live strategy parks itself to PAPER ([parkedToPaper]) and
 *    asks Boss; it never un-parks (Boss re-arms it in Live with his PIN, [ownerRearmed]).
 *
 * Nothing here places, enlarges or closes an order, and nothing here adds risk. Settings are read once off the hot paths
 * ([ensureLoaded], on a worker thread) and then kept in memory; the order path reads no setting through here.
 */
object SmartWorkers {
    const val REFRESH_MS = 10_000L

    val bus = Findings.Bus()
    val health = SelfHeal.Monitor()
    val sched = WorkerWake.Scheduler()

    private val _brain = MutableStateFlow(MarketBrain.Context())
    /** The shared market picture (every worker and strategy reads it; the Brain section and Home show it). */
    val brain: StateFlow<MarketBrain.Context> = _brain

    @Volatile private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }

    // ---- Boss's settings (memory; read once off the hot paths) -------------------------------------------------------------

    private const val K_PAUSE = "brain.pause"
    private const val K_REACT = "brain.reactions"
    private const val K_NET = "brain.netFlatSec"
    private const val K_PAPER = "brain.paperDrift"
    private const val K_PARKED = "brain.parked"
    private const val K_PAUSED = "brain.paused"
    private const val K_REVIEWED = "brain.reviewedOn"

    /** What the Brain sheet shows and sets. */
    data class Settings(
        val pause: MarketBrain.Policy = MarketBrain.Policy(),
        val reactions: Findings.Policy = Findings.Policy(),
        val netFlatSec: Int = WorkerWake.NET_FLAT_DEFAULT_SEC,
        val paperDrift: DriftReview.PaperPolicy = DriftReview.PaperPolicy.WARN,
        /** Strategy review keys ("liquidity") parked to paper by their own review: only Boss puts them back. */
        val parked: Set<String> = emptySet(),
        /** Strategy review keys whose new paper entries are paused (Boss's PAUSE setting for paper drift). */
        val paused: Set<String> = emptySet(),
    )

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    @Volatile private var loaded = false

    /** The settings read once (a worker thread: never the main thread, never the order path). */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val s = runCatching {
                Settings(
                    MarketBrain.decode(SecurePrefs.getString(K_PAUSE)), Findings.decode(SecurePrefs.getString(K_REACT)),
                    SecurePrefs.getInt(K_NET, WorkerWake.NET_FLAT_DEFAULT_SEC).coerceIn(WorkerWake.NET_MIN_SEC, WorkerWake.NET_MAX_SEC),
                    if (SecurePrefs.getString(K_PAPER) == DriftReview.PaperPolicy.PAUSE.name) DriftReview.PaperPolicy.PAUSE else DriftReview.PaperPolicy.WARN,
                    SecurePrefs.getString(K_PARKED).orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                    SecurePrefs.getString(K_PAUSED).orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                )
            }.getOrNull() ?: return       // unreadable now: defaults stay (they only ever pause more), tried again next time
            _settings.value = s
            loaded = true
        }
    }

    private fun save(s: Settings, what: String) {
        _settings.value = s
        runCatching {
            SecurePrefs.putAllSoon(mapOf(K_PAUSE to MarketBrain.encode(s.pause), K_REACT to Findings.encode(s.reactions), K_NET to s.netFlatSec,
                K_PAPER to s.paperDrift.name, K_PARKED to s.parked.sorted().joinToString(","), K_PAUSED to s.paused.sorted().joinToString(",")))
        }
        runCatching { Diag.record("brain", what) }
    }

    /** Boss sets one pause cause (OFF / SHADOW / ON). Returns what to say. */
    fun setPause(c: MarketBrain.Cause, m: MarketBrain.Mode): String {
        ensureLoaded()
        val s = _settings.value
        save(s.copy(pause = MarketBrain.Policy(s.pause.modes + (c to m))), "pause new entries during ${c.words}: ${m.name}")
        return when (m) {
            MarketBrain.Mode.ON -> "New entries now wait during ${c.words} (paper and live). Exits always go."
            MarketBrain.Mode.SHADOW -> "During ${c.words} entries go ahead; what a pause would have done is logged beside each signal."
            MarketBrain.Mode.OFF -> "Nothing is paused or logged for ${c.words}."
        }
    }

    /** Boss sets a reaction for every strategy ([key] null) or one. */
    fun setReaction(key: String?, r: Findings.Reaction, m: Findings.Mode): String {
        ensureLoaded()
        val s = _settings.value
        val p = s.reactions
        val next = if (key == null) p.copy(global = p.global + (r to m))
            else p.copy(perStrategy = p.perStrategy + (key to (p.perStrategy[key].orEmpty() + (r to m))))
        save(s.copy(reactions = next), "reaction ${r.key} ${key ?: "(all)"}: ${m.name}")
        return when (m) {
            Findings.Mode.ACT -> "It now skips new entries when this applies: ${r.words}. Exits always go."
            Findings.Mode.SHADOW -> "Logged only: ${r.words}."
            Findings.Mode.OFF -> "Off: ${r.words}."
        }
    }

    fun setNetFlatSec(sec: Int): String {
        ensureLoaded()
        val v = sec.coerceIn(WorkerWake.NET_MIN_SEC, WorkerWake.NET_MAX_SEC)
        save(_settings.value.copy(netFlatSec = v), "safety-net pass with nothing held: every $v s")
        return "With nothing held, the safety-net check runs every $v s (every ${WorkerWake.NET_HOLDING_SEC} s while anything is held, always)."
    }

    fun setPaperDrift(p: DriftReview.PaperPolicy): String {
        ensureLoaded()
        save(_settings.value.copy(paperDrift = p), "paper strategy drifting: ${p.name}")
        return if (p == DriftReview.PaperPolicy.PAUSE) "A paper strategy drifting from its backtest now pauses its new entries and asks you."
            else "A paper strategy drifting from its backtest only warns you."
    }

    /** Is the review key [key] ("liquidity") parked to paper by its own review? Read by the arm at each entry (memory). */
    fun parkedToPaper(key: String): Boolean { ensureLoaded(); return key in _settings.value.parked }

    /** Boss re-armed [key] himself (in Live, with his PIN): it is no longer parked. Only ever called from Boss's own action. */
    fun ownerRearmed(key: String) {
        val s = _settings.value
        if (key in s.parked || key in s.paused) save(s.copy(parked = s.parked - key, paused = s.paused - key), "$key switched back on by you")
    }

    // ---- the entry check ---------------------------------------------------------------------------------------------------

    /** The review key of a FlowGate strategy key ("liquidity:BANKNIFTY" -> "liquidity"). */
    fun reviewKey(flowKey: String): String = flowKey.substringBefore(':')

    /**
     * A NEW entry by strategy [key] on [underlying] leaning [side] (+1 / -1): the brain's pause by Boss's policy, the findings'
     * reactions, and a paper pause by its own review. Memory only (the settings were read once, off this path). Never asked
     * by an exit.
     */
    fun entry(key: String, underlying: String?, side: Int, nowMs: Long = System.currentTimeMillis()): EntryBrake.Decision {
        ensureLoaded()
        val s = _settings.value
        val d = EntryBrake.decide(_brain.value, s.pause, underlying?.let { bus.recent(nowMs, it) } ?: bus.recent(nowMs), s.reactions,
            key, underlying, side, nowMs)
        if (!d.block && reviewKey(key) in s.paused)
            return d.copy(block = true, why = "paused: its self-review found it drifting from its backtest (switch it back on yourself)")
        return d
    }

    /** The underlying of a Zerodha trading symbol ("BANKNIFTY26OCT52000CE" -> "BANKNIFTY"). */
    fun underlyingOf(symbol: String): String = symbol.takeWhile { it.isLetter() }.uppercase(Locale.ENGLISH)

    /**
     * The entry door's part (a bot's NEW live entry, asked inside the door): a news window paused ON, the data unreliable
     * (acting by default), the relay down. Memory only; null when it may go.
     */
    fun doorRefusal(symbol: String, nowMs: Long = System.currentTimeMillis()): String? {
        val s = _settings.value
        val und = underlyingOf(symbol)
        if (runCatching { health.fallbacks(nowMs).blockLiveEntries }.getOrDefault(false))
            return "refused: the static-IP relay is not answering (new live entries wait; exits still go)"
        if (s.pause.mode(MarketBrain.Cause.NEWS) == MarketBrain.Mode.ON)
            _brain.value.activeWindows(nowMs, und).firstOrNull { it.kind == MarketBrain.Kind.NEWS }?.let { return "refused: paused for the ${it.name} window" }
        if (s.reactions.mode("*", Findings.Reaction.DATA_UNRELIABLE) == Findings.Mode.ACT &&
            bus.recent(nowMs, und, setOf(Findings.Kind.DATA_UNRELIABLE)).isNotEmpty())
            return "refused: the data is unreliable just now (new entries wait; exits still go)"
        return null
    }

    // ---- findings ----------------------------------------------------------------------------------------------------------

    /** The last post of each (who, kind, instrument, direction): the same finding again within [REPEAT_MS] is not posted twice. */
    private val lastPost = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val REPEAT_MS = 5 * 60_000L

    /** Post a finding (one atomic step; never throws, never blocks; a repeat within 5 minutes is dropped). */
    fun found(who: String, kind: Findings.Kind, instrument: String, direction: Int = 0, level: Double? = null, strength: Int = 60,
              words: String = "", ttlMs: Long = Findings.DEFAULT_TTL_MS, evidence: Map<String, Double> = emptyMap()) {
        runCatching {
            val now = System.currentTimeMillis()
            val k = "$who|$kind|$instrument|$direction"
            val was = lastPost[k]
            if (was != null && now - was in 0 until REPEAT_MS) return
            lastPost[k] = now
            if (lastPost.size > 2_000) lastPost.clear()
            bus.post(Findings.Finding(who, kind, instrument.uppercase(Locale.ENGLISH), direction, level, strength,
                System.currentTimeMillis(), ttlMs, evidence, words))
        }
    }

    /** The move recorder's trigger (its own thread): the big-move alarm in the brain and a finding. */
    fun bigMove(name: String, dir: Int, atMs: Long) {
        runCatching {
            _brain.value = _brain.value.copy(bigMove = MarketBrain.BigMove(name, dir, atMs))
            found("move recorder", Findings.Kind.BIG_MOVE, name, dir, null, 80,
                "big move ${if (dir > 0) "up" else "down"} started ${MoveEvents.hhmm(atMs / 1000 - 60)}", 30 * 60_000L)
        }
    }

    // ---- wakes -------------------------------------------------------------------------------------------------------------

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** An event a worker waits for (an order update, a position change, a news window): posted, the quiet wait ends. Cheap. */
    fun event(cause: WorkerWake.Cause, instrument: String? = null) {
        runCatching { sched.post(cause, instrument) }
        wake.trySend(Unit)
    }

    /** The watch's quiet wait: [ms] at most, ended early by an [event]. */
    suspend fun awaitWake(ms: Long) {
        if (ms <= 0) return
        withTimeoutOrNull(ms) { wake.receive() }
    }

    /** The safety-net interval now (15 s while anything is held, else Boss's 15-60 s, 30 by default). */
    fun safetyNetMs(anyOpen: Boolean): Long = WorkerWake.safetyNetMs(anyOpen, _settings.value.netFlatSec)

    /** The arms the scheduler knows, with what wakes each (the names are the watch's own step names). */
    private val SPECS = listOf(
        // ORB, Liquidity and Hero decide on 5-, 15- and 30-minute bars, but a bar the feed has not finished is looked at again on
        // the next minute (as the full pass did): woken by every minute's close, never later than before.
        WorkerWake.Spec("ORB arms", setOf(WorkerWake.Cause.CANDLE_1M, WorkerWake.Cause.CANDLE_5M, WorkerWake.Cause.ORDER_UPDATE,
            WorkerWake.Cause.POSITION_CHANGE, WorkerWake.Cause.SIGNAL_WINDOW, WorkerWake.Cause.NEWS, WorkerWake.Cause.NEAR_LEVEL),
            maxSleepMs = 60_000L, minIntervalMs = 10_000L),
    ) + listOf("Night (R3)", "VIX divergence", "Pine scripts", "MCX paper arms", "strategies", "Jarvis's trades", "Solo").map {
        WorkerWake.Spec(it, setOf(WorkerWake.Cause.CANDLE_1M, WorkerWake.Cause.ORDER_UPDATE, WorkerWake.Cause.POSITION_CHANGE,
            WorkerWake.Cause.NEWS), maxSleepMs = 60_000L, minIntervalMs = 10_000L)
    }

    @Volatile private var registered = false
    private fun register() {
        if (registered) return
        synchronized(this) { if (!registered) { SPECS.forEach { sched.register(it) }; registered = true } }
    }

    @Volatile private var nearUntil = 0L
    /** The price is near a level an arm watches (a Liquidity pool, a break level) until [untilMs]: its next look comes sooner. */
    fun nearLevel(untilMs: Long) { nearUntil = maxOf(nearUntil, untilMs); event(WorkerWake.Cause.NEAR_LEVEL) }

    /** Anything held anywhere (memory hints only; anything unreadable counts as held: nothing is skipped on a guess). */
    fun holdingAny(): Boolean = runCatching {
        com.optionslab.app.work.PositionCards.anyOpen || OrbArms.holdingHint || PineAuto.holding() || Strategies.runningHint ||
            NightArm.view.value.open.isNotEmpty() || VixDivArm.view.value.open.isNotEmpty() || McxPaperArms.view.value.open.isNotEmpty() ||
            Paper.state.positions.any { it.quantity != 0 }
    }.getOrDefault(true)

    private fun heroWindow(): Boolean = runCatching {
        val m = Market.minuteNow()
        m in 13 * 60 + 30..14 * 60 + 45 && Market.isExpiryDay("NIFTY")
    }.getOrDefault(false)

    /**
     * Smart scheduling: of [steps] (the arms), those due now - all of them while anything is held ([holding], or unknown);
     * else each by its own wake rules ([WorkerWake.Scheduler.decide], counted). A step the scheduler does not know always runs.
     */
    fun dueSteps(steps: List<com.optionslab.ira.Supervisor.Step>, holding: Boolean = holdingAny(),
                 /** TEST: the market hours as given (null: NSE's and MCX's own). */ inHours: Boolean? = null): List<com.optionslab.ira.Supervisor.Step> {
        register()
        val now = System.currentTimeMillis()
        val ctx = _brain.value
        val nse = inHours ?: runCatching { Market.isOpen() }.getOrDefault(true)
        val mcx = inHours ?: runCatching { McxMarket.isOpen() }.getOrDefault(true)
        return steps.filter { st ->
            val pace = WorkerWake.Pace(inHours = if (st.name == "MCX paper arms") mcx || nse else nse, holding = holding,
                nearLevel = now < nearUntil, busy = ctx.anyBusy(), quiet = ctx.allQuiet(),
                inWindow = st.name == "ORB arms" && heroWindow())
            val d = runCatching { sched.decide(st.name, pace, now) }.getOrNull() ?: WorkerWake.Decision(true, WorkerWake.Cause.FIRST)
            if (d.run) runCatching { sched.ran(st.name, now) }
            d.run
        }
    }

    /** Another lane ran [names] (the stream's bar-close and minute lanes): their events are used up, nothing runs twice for one. */
    fun ranByLane(names: List<String>) {
        register()
        val now = System.currentTimeMillis()
        names.forEach { runCatching { sched.ran(it, now) } }
    }

    // ---- the brain's own worker ----------------------------------------------------------------------------------------------

    @Volatile private var windowsAt = 0L
    @Volatile private var windowsDay: LocalDate? = null
    private var windows: List<MarketBrain.Window> = emptyList()
    private val trapsSeen = HashMap<String, Set<String>>()
    private val sideSeen = HashMap<String, String>()
    private val activeWindowsSeen = HashSet<String>()
    private val closedSeen = HashSet<String>()
    @Volatile private var closedAt = 0L
    @Volatile private var unreliablePostedAt = 0L
    @Volatile private var flushedAt = 0L
    @Volatile private var countsDay: LocalDate? = null

    /** While market hours, NSE's or MCX's: every [REFRESH_MS]; else asleep (null). */
    fun refreshDelay(lastRunMs: Long): Long? {
        val open = runCatching { Market.isTradingDay() && Market.minuteNow() in Market.OPEN - 15..Market.foClose() + 15 }.getOrDefault(false) ||
            runCatching { McxMarket.isOpen() }.getOrDefault(false)
        if (!open) return null
        return (REFRESH_MS - (System.currentTimeMillis() - lastRunMs)).coerceIn(0L, REFRESH_MS)
    }

    /**
     * One look (the brain's worker, a worker thread): the regimes from the cash indices' last 30 minutes (the move recorder's
     * ring), today's event windows (re-read every 5 minutes), the trap flags, the data's health and its incidents (told once),
     * the findings from what changed, the findings log, and the daily self-review once due. Each part in its own try.
     */
    suspend fun refresh(context: Context?) {
        ensureLoaded()
        register()
        val now = System.currentTimeMillis()
        val today = runCatching { Market.today() }.getOrNull() ?: return
        if (countsDay != today) { countsDay = today; runCatching { sched.resetCounts() }; synchronized(closedSeen) { closedSeen.clear() } }
        val regs = runCatching { regimes(now) }.getOrDefault(_brain.value.regimes)
        if (windowsDay != today || now - windowsAt > 5 * 60_000L) runCatching {
            val events = com.optionslab.app.ira.IraEvents.upcoming(0).filter { it.day == today }
            val names = events.map { it.name }
            val expiries = names.filter { it.endsWith(" expiry") }.map { it.removeSuffix(" expiry") }
            windows = MarketBrain.windows(today, names, expiries, Market.foClose(today))
            windowsDay = today; windowsAt = now
        }
        val traps = runCatching { OrderFlowLive.reads.value.mapValues { (_, r) -> r.flags.map { it.name }.toSet() } }.getOrDefault(emptyMap())
        runCatching { postFlowFindings(now) }
        runCatching { sampleHealth(context, now) }
        val changes = runCatching { health.evaluate(now) }.getOrDefault(emptyList())
        for (c in changes) runCatching { tell(context, c) }
        val fb = runCatching { health.fallbacks(now) }.getOrDefault(SelfHeal.Fallbacks())
        if (fb.dataUnreliable && now - unreliablePostedAt > 30_000L) {
            unreliablePostedAt = now
            // Posted afresh every 30 s while it lasts (not through [found]'s repeat filter): the reaction stands only while true.
            runCatching { bus.post(Findings.Finding("self-healing", Findings.Kind.DATA_UNRELIABLE, Findings.ALL, 0, null, 100, now, 60_000L,
                emptyMap(), "no reliable prices (stream stale and Zerodha failing)")) }
        }
        val incidents = runCatching { health.openIncidents().map { "${it.dep.words}: ${it.what}" } }.getOrDefault(emptyList())
        val cur = _brain.value
        _brain.value = cur.copy(atMs = now, regimes = regs, windows = windows, traps = traps, health = fb, incidents = incidents)
        // A news window opening wakes the workers waiting on one, and is a finding (once a window).
        for (w in windows) if (w.kind == MarketBrain.Kind.NEWS && w.active(now) && activeWindowsSeen.add("$today ${w.name} ${w.startMs}")) {
            event(WorkerWake.Cause.NEWS)
            found("calendar", Findings.Kind.NEWS_WINDOW, if (w.scope.isEmpty()) Findings.ALL else w.scope.first(), 0, null, 50,
                "${w.name} window", (w.endMs - now).coerceAtLeast(1_000L))
        }
        if (now - closedAt > 30_000L) { closedAt = now; runCatching { postExits(today) } }
        if (now - flushedAt > 30_000L) { flushedAt = now; runCatching { flushFindings(context, today) } }
        runCatching { reviewIfDue(context) }
        // The trade manager's specialists: their nightly report cards and slow self-tuning (paper and shadow only), once a day.
        runCatching { ManagerSpecialists.reviewIfDue() }
    }

    /** The cash indices' last 31 minute closes and each minute's normal move, into a regime each. */
    private fun regimes(now: Long): Map<String, MarketBrain.Regime> {
        val out = LinkedHashMap<String, MarketBrain.Regime>()
        val sec = now / 1000
        val vix = MoveRecorder.vixAt(sec)
        for (n in MoveEvents.INDICES) {
            val closes = MoveRecorder.minuteCloses(n, sec, MarketBrain.WINDOW_MINUTES + 1)
            if (closes.isEmpty()) continue
            val norms = closes.drop(1).mapNotNull { (startSec, _) -> MoveEvents.normalBp(n, MoveEvents.minuteIndex(startSec), vix) }
            out[n] = MarketBrain.regime(n, closes.map { it.second }, norms)
        }
        return out
    }

    /** New trap flags and a change of hands in the order flow, as findings (once each as they appear). */
    private fun postFlowFindings(now: Long) {
        val reads = OrderFlowLive.reads.value
        for ((name, r) in reads) {
            val flags = r.flags.map { it.name }.toSet()
            val before = synchronized(trapsSeen) { trapsSeen.put(name, flags) }.orEmpty()
            for (f in flags - before) {
                val (kind, dir) = when (f) {
                    "PULL_BID" -> Findings.Kind.PULL to -1
                    "PULL_ASK" -> Findings.Kind.PULL to 1
                    "ABSORPTION_BUY" -> Findings.Kind.ABSORPTION to -1
                    "ABSORPTION_SELL" -> Findings.Kind.ABSORPTION to 1
                    "STOP_HUNT" -> Findings.Kind.STOP_HUNT to 0
                    "FAILED_BREAK" -> Findings.Kind.BREAK to 0
                    "UNRELIABLE" -> Findings.Kind.DATA_HEALTH to 0
                    else -> continue
                }
                val words = com.optionslab.ira.TrapGuard.Trap.entries.firstOrNull { it.name == f }?.words ?: f.lowercase(Locale.ENGLISH)
                found("trap guard", kind, name, dir, r.mid.takeIf { it > 0 }, 60, words, 10 * 60_000L)
            }
            if (!r.warm) continue
            val side = if (r.strength >= 60) r.side.name else "BALANCED"
            val was = synchronized(sideSeen) { sideSeen.put(name, side) }
            if (was != null && was != side && side != "BALANCED")
                found("order flow", Findings.Kind.OTHER, name, if (side == "BUYERS") 1 else -1, r.mid.takeIf { it > 0 }, r.strength,
                    "${side.lowercase(Locale.ENGLISH)} in control (${r.strength})", 10 * 60_000L)
        }
    }

    /** Today's closed arm positions not seen yet: a stop hit or a target hit is a finding (the stops-cluster reaction reads them). */
    private suspend fun postExits(today: LocalDate) {
        val closed = withTimeoutOrNull(2_000L) { OrbArms.closedToday() } ?: return
        val liq = com.optionslab.engine.orb.LiquidityRules.BOOKS.map { it.source }.toSet()
        for (p in closed) {
            val id = "${p.arm}|${p.symbol}|${p.entryTime}"
            if (!synchronized(closedSeen) { closedSeen.add(id) }) continue
            val kind = when (p.why) { "stop", "index_stop" -> Findings.Kind.STOP_HIT; "target" -> Findings.Kind.TARGET_HIT; else -> null } ?: continue
            val who = if (p.arm in liq) "Liquidity 15+5" else p.arm
            val dir = if (p.right == "CE") 1 else -1
            found(who, kind, underlyingOf(p.symbol), if (kind == Findings.Kind.STOP_HIT) -dir else dir, p.exit, 60,
                "${if (kind == Findings.Kind.STOP_HIT) "my stop hit" else "target hit"} (${p.right})", 30 * 60_000L)
            event(WorkerWake.Cause.POSITION_CHANGE)
        }
    }

    // ---- health --------------------------------------------------------------------------------------------------------------

    /** One Zerodha answer (after it came back: never before a request). */
    fun restSample(ok: Boolean, ms: Long) { runCatching { health.rest(ok, ms) } }
    fun restLimited() { runCatching { health.rest(ok = true, latencyMs = 0, rateLimited = true) } }
    fun relaySample(ok: Boolean, enabled: Boolean) { runCatching { health.relay(ok, enabled) } }
    fun candleSample(ok: Boolean) { runCatching { health.candles(ok) } }

    private fun sampleHealth(context: Context?, now: Long) {
        val expected = runCatching { Market.isOpen() && Broker.loggedIn && KiteStream.status.value != KiteStream.Status.OFF }.getOrDefault(false)
        health.stream(KiteStream.lastTickAt.takeIf { it > 0 }, expected)
        val ctx = context ?: appContext ?: return
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
        val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 1..100 }
        val charging = bm?.isCharging ?: true
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29)
            ctx.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0 else 0
        health.phone(pct, charging, thermal)
    }

    /** An incident started or ended: told once - a notification, a line from Jarvis, the diary, a finding. */
    private fun tell(context: Context?, c: SelfHeal.Change) {
        val ctx = context ?: appContext
        val title = if (c.started) "Problem: ${c.incident.dep.words}" else "Back to normal: ${c.incident.dep.words}"
        runCatching { Diag.record("health", (if (c.started) "incident: " else "recovered: ") + c.words) }
        if (ctx != null) runCatching { com.optionslab.app.work.Notifier.post(ctx, 5101 + c.incident.dep.ordinal, com.optionslab.app.work.Notifier.HEALTH, title, c.words, "health") }
        if (com.optionslab.app.BuildConfig.JARVIS) runCatching { com.optionslab.app.ira.IraHub.note(c.words) }
        if (c.started) found("self-healing", Findings.Kind.DATA_HEALTH, Findings.ALL, 0, null, 70, c.words, 30 * 60_000L)
    }

    // ---- the findings log ----------------------------------------------------------------------------------------------------

    private fun flushFindings(context: Context?, today: LocalDate) {
        val lines = bus.drainLines()
        if (lines.isEmpty()) return
        val dir = File((context ?: appContext ?: return).noBackupFilesDir, "findings")
        dir.mkdirs()
        java.io.FileOutputStream(File(dir, "$today.log"), true).use { out -> out.write((lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)) }
        // Kept 30 days.
        dir.listFiles()?.forEach { f -> runCatching { if (LocalDate.parse(f.name.removeSuffix(".log")).isBefore(today.minusDays(30))) f.delete() } }
    }

    // ---- the daily self-review -----------------------------------------------------------------------------------------------

    private val _reviews = MutableStateFlow<List<DriftReview.Review>>(emptyList())
    /** The latest reviews (paper and live separately), for Home's chips and Jarvis. */
    val reviews: StateFlow<List<DriftReview.Review>> = _reviews
    @Volatile private var reviewedOn: String? = null

    /** The reviews now from the strategies' own books (reads only). */
    private suspend fun reviewNow(): List<DriftReview.Review> {
        val out = ArrayList<DriftReview.Review>()
        val paper = runCatching { OrbArms.closedPaper() }.getOrDefault(emptyList())
        val live = runCatching { OrbArms.closedLive() }.getOrDefault(emptyList())
        fun nets(e: ForwardCheck.Expectation, t: List<ForwardCheck.Trade>) =
            t.filter { x -> e.since?.let { !x.day.isBefore(it) } ?: true }.sortedBy { it.day }.map { it.net }
        val liqPaper = nets(ForwardCheck.LIQUIDITY, ForwardRecords.liquidity(paper))
        out += DriftReview.review(ForwardCheck.LIQUIDITY, liqPaper, live = false)
        val liqLive = nets(ForwardCheck.LIQUIDITY, ForwardRecords.liquidityLive(live))
        if (liqLive.isNotEmpty()) out += DriftReview.review(ForwardCheck.LIQUIDITY, liqLive, live = true)
        val hero = nets(ForwardCheck.HERO, ForwardRecords.hero(paper))
        if (hero.isNotEmpty()) out += DriftReview.review(ForwardCheck.HERO, hero, live = false)
        if (com.optionslab.app.BuildConfig.JARVIS) {
            val solo = runCatching { com.optionslab.app.ira.IraSolo.all().filter { it.midday && it.closed && it.net != null } }.getOrDefault(emptyList())
                .mapNotNull { t -> runCatching { ForwardCheck.Trade(LocalDate.parse(t.day), t.net!!) }.getOrNull() }
            if (solo.isNotEmpty()) out += DriftReview.review(ForwardCheck.SOLO, nets(ForwardCheck.SOLO, solo), live = false)
        }
        return out
    }

    /** Home's chips with no review yet today (the app just opened): the reviews worked out, nothing done. Off the main thread. */
    suspend fun refreshReviews() { if (_reviews.value.isEmpty()) runCatching { _reviews.value = reviewNow() } }

    /**
     * After F&O's close (15:45) on a trading day, once a day: each strategy reviewed; a drifting live one parks itself to
     * paper and asks Boss, a drifting paper one warns (or pauses under Boss's setting) and asks. Never un-parks anything.
     */
    suspend fun reviewIfDue(context: Context?) {
        val today = Market.today()
        if (!Market.isTradingDay(today) || Market.minuteNow() < 15 * 60 + 45) return
        if (reviewedOn == today.toString()) return
        ensureLoaded()
        if (runCatching { SecurePrefs.getString(K_REVIEWED) }.getOrNull() == today.toString()) { reviewedOn = today.toString(); refreshReviews(); return }
        reviewedOn = today.toString()
        runCatching { SecurePrefs.putAllSoon(mapOf(K_REVIEWED to today.toString())) }
        val list = reviewNow()
        _reviews.value = list
        val ctx = context ?: appContext
        for ((i, r) in list.withIndex()) {
            val s = _settings.value
            val key = r.key
            val already = if (r.live) key in s.parked else key in s.paused
            val a = DriftReview.action(r, s.paperDrift, alreadyParked = already)
            if (a == DriftReview.Action.NONE) continue
            when (a) {
                DriftReview.Action.PARK_TO_PAPER -> save(s.copy(parked = s.parked + key), "${r.name}: parked to paper by its self-review")
                DriftReview.Action.PAUSE -> save(s.copy(paused = s.paused + key), "${r.name}: new paper entries paused by its self-review")
                else -> runCatching { Diag.record("brain", "${r.name} (${r.venue}): drifting from its backtest (warned)") }
            }
            val text = DriftReview.ask(r, a)
            found("self-review", Findings.Kind.DRIFT, Findings.ALL, 0, null, 70, "${r.name} (${r.venue}) drifting from its backtest", 24 * 3_600_000L)
            if (ctx != null) runCatching { com.optionslab.app.work.Notifier.post(ctx, 5120 + i, com.optionslab.app.work.Notifier.HEALTH,
                "${r.name} is drifting from its backtest", text, "almanac") }
            if (com.optionslab.app.BuildConfig.JARVIS) runCatching {
                val name = r.name
                when (a) {
                    DriftReview.Action.PARK_TO_PAPER -> com.optionslab.app.ira.IraHub.offer("keep $name on paper", "Boss, keep $name on paper?", text,
                        { "$name stays on paper. To put it back on Live, re-arm it yourself with your PIN." })
                    DriftReview.Action.PAUSE -> com.optionslab.app.ira.IraHub.offer("keep $name paused", "Boss, keep $name's paper entries paused?", text,
                        { "$name's new paper entries stay paused. Switch it back on yourself when you want it to carry on." })
                    else -> com.optionslab.app.ira.IraHub.offer("pause $name's paper entries", "Boss, pause $name's paper entries?", text,
                        { setPausedByOwner(key); "$name's new paper entries are paused. Switch it back on yourself when you want it to carry on." })
                }
            }
        }
    }

    /** Boss said yes to pausing [key]'s paper entries (an offer he confirmed): lowers risk only. */
    private fun setPausedByOwner(key: String) {
        ensureLoaded()
        val s = _settings.value
        if (key !in s.paused) save(s.copy(paused = s.paused + key), "$key: new paper entries paused on your yes")
    }

    /** Home's chip for each reviewed strategy: name and venue to "on track" / "drifting" / "parked" / "too few trades". */
    fun chips(reviews: List<DriftReview.Review>, s: Settings): List<Pair<String, String>> = reviews.map { r ->
        "${r.name} (${r.venue})" to DriftReview.chip(r, parked = if (r.live) r.key in s.parked else r.key in s.paused)
    }

    /** Jarvis's part of "how are my strategies doing vs backtest?". */
    fun reviewAnswer(): String {
        val s = _settings.value
        val parked = (s.parked.map { "$it:live" } + s.paused.map { "$it:paper" }).toSet()
        return DriftReview.answer(_reviews.value, parked, reviewedOn)
    }

    // ---- words ---------------------------------------------------------------------------------------------------------------

    private val HHMM = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    private val HHMMSS = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    fun hhmm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(Market.now().zone).format(HHMM)
    private fun hhmmss(ms: Long): String = Instant.ofEpochMilli(ms).atZone(Market.now().zone).format(HHMMSS)

    /** The consensus per index for Home ("BANKNIFTY: 3 bullish, 1 bearish (leaning bullish)"). */
    fun consensusLines(nowMs: Long = System.currentTimeMillis()): List<String> {
        val all = bus.recent(nowMs)
        return listOf("BANKNIFTY", "NIFTY", "FINNIFTY", "MIDCPNIFTY").map { "$it: ${Findings.consensus(all, it, nowMs).words()}" }
    }

    /** The newest findings in words (the findings sheet and the Brain section). */
    fun feed(n: Int = 20, nowMs: Long = System.currentTimeMillis()): List<String> = bus.recent(nowMs).take(n).map { Findings.said(it, { hhmm(it) }) }

    /** Jarvis: "what are the bots saying about banknifty?" ("" index: all). */
    fun answer(index: String): String = Findings.answer(index, bus.recent(System.currentTimeMillis()), System.currentTimeMillis(), { hhmm(it) })

    /** The diagnostics' "Brain" section. */
    fun diagLines(): List<String> {
        val now = System.currentTimeMillis()
        val s = _settings.value
        val out = ArrayList<String>()
        out += "Brain (smart workers):"
        out += MarketBrain.lines(_brain.value, now, s.pause, { hhmm(it) }).map { "  $it" }
        out += "  Findings reactions: " + Findings.Reaction.entries.joinToString(", ") { "${it.key} ${s.reactions.mode("*", it).name.lowercase(Locale.ENGLISH)}" } +
            if (s.reactions.perStrategy.isNotEmpty()) " (per strategy: ${Findings.encode(s.reactions.copy(global = emptyMap()))})" else ""
        out += "  Safety net with nothing held: every ${s.netFlatSec} s (every ${WorkerWake.NET_HOLDING_SEC} s while held)"
        out += "  Self-review: " + (if (s.parked.isEmpty() && s.paused.isEmpty()) "nothing parked or paused" else
            "parked to paper: ${s.parked.ifEmpty { setOf("none") }.joinToString()}; paper paused: ${s.paused.ifEmpty { setOf("none") }.joinToString()}") +
            (reviewedOn?.let { " · last review $it" } ?: "")
        _reviews.value.forEach { out += "    ${DriftReview.line(it, if (it.live) it.key in s.parked else it.key in s.paused)}" }
        val open = health.openIncidents()
        val past = health.past().takeLast(10)
        out += "  Health incidents: " + if (open.isEmpty() && past.isEmpty()) "none today" else ""
        (open + past).forEach { out += "    " + SelfHeal.line(it, { hhmmss(it) }) }
        out += "  Breakers: " + SelfHeal.Dep.entries.joinToString(", ") { "${it.name.lowercase(Locale.ENGLISH)} ${health.breaker(it).name.lowercase(Locale.ENGLISH)}" }
        out += "  Wakes and skips today:"
        sched.counts().forEach { (n, c) -> out += "    " + WorkerWake.words(n, c) }
        out += "  Consensus: " + consensusLines(now).joinToString(" · ")
        out += "  Findings (newest first, ${bus.posted} posted since start):"
        feed(15, now).ifEmpty { listOf("none standing") }.forEach { out += "    $it" }
        runCatching { TradeManagerHost.diagLines() }.getOrDefault(emptyList()).forEach { out += "  $it" }
        return out
    }

    /** The Order speed card's line: wakes and skips today, in one line. */
    fun speedLine(): String {
        val c = sched.counts()
        if (c.isEmpty()) return "Smart scheduling: no look yet today"
        return "Smart scheduling today: " + c.entries.joinToString(" · ") { (n, x) -> "$n ${x.wakeTotal} run, ${x.skips} skipped" }
    }

    /** TEST ONLY. */
    internal fun resetForTest() {
        bus.clear(); health.reset(); sched.resetCounts(); lastPost.clear()
        _brain.value = MarketBrain.Context(); _settings.value = Settings(); loaded = true
        _reviews.value = emptyList(); reviewedOn = null; nearUntil = 0L
        synchronized(trapsSeen) { trapsSeen.clear() }; synchronized(sideSeen) { sideSeen.clear() }
        activeWindowsSeen.clear(); synchronized(closedSeen) { closedSeen.clear() }
    }

    /** TEST ONLY: the brain as given (a news window, traps). */
    internal fun setBrainForTest(c: MarketBrain.Context) { _brain.value = c }
}
