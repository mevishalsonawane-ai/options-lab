package com.optionslab.app.work

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * 22 Oct: the stop lane. The open positions' stops, targets, trails and locks get a look every 15 s while anything is held,
 * on a coroutine of their own that nothing else can hold up (CLAUDE.md: open positions and orders come first).
 *
 * The 21 Oct diagnostics logged twice "the stops were looked at N s or more apart while a position was held" (the phone hot,
 * 15% battery, 16 worker overruns). The 15-second stop check ran INSIDE the watch loop, one step after another:
 *  - after the full pass ([Tasks.watchTick]: p95 5.5 s, max 20.2 s; its arms groups wait 10 s each, its quotes and chart reads
 *    up to 20 s each), so a long pass was a long gap;
 *  - after each bar-close entry check, which waited up to 45 s ([WatchWorkers.ARMS_WAIT_MS]) for the arms' entries;
 *  - after "is anything held?", which waited for the ORB arms' lock while their entry pass held it;
 *  - and its own arms' exits were waited on 20 s, so a slow one pushed the next look past 15 s.
 * Now the look runs here, beside the full pass, the bar-close check and the event-driven lanes ([com.optionslab.app.data.FastPath],
 * which already run these same functions beside the watch), never after them:
 *  - its clock is [com.optionslab.ira.StopCadence.Ticker]: a look 15 s after the last one STARTED, 1 s after a look that itself
 *    ran long, at once when something is first held; no battery, saver, heat or memory input;
 *  - 25 Oct (Boss's decision on the app audit, H4): every 5 s instead while a held position's price is quiet ([quietFor]: no fresh
 *    stream tick); such a look waits for the arms' exits [QUIET_EXITS_WAIT_MS] at most, and the gaps are measured ([quietGaps]);
 *  - "is anything held?" from memory only ([heldFor]: never a lock, a vault read or the network);
 *  - every step runs under its book's own lock, as before (each arm's lock, Paper's, Protections', the sweep's and the orphan
 *    watch's try-locks, ArmLive's one look at a time), so two lanes never sell one holding twice;
 *  - the arms' exits are waited on [WatchWorkers.EXITS_WAIT_MS] (8 s): one still running is left to finish by itself (never cut
 *    mid-order) and is not started again beside itself.
 * Outside NSE's hours MCX's own pass looks at its stops every 15 s while held (its start is noted as a look): there the lane looks
 * only when that pass is late (a slow Zerodha read in it), 16 s after its start (a second's grace for the pass, which now comes
 * back 15 s after its start, [WatchService.mcxWait]), so the two never double the broker's work.
 * Which market's look is due is the watch loop's to say ([Mode], set at each pass); the lane only reads it. Nothing here writes
 * the vault or the disk beyond what each book's own exit already did, and nothing runs on the main thread.
 */
internal object StopLane {
    /** Which look the watch loop is in now: none (before the open, the evening look, a closed day), NSE's, or MCX's (outside NSE's hours). */
    enum class Mode { OFF, NSE, MCX }

    /** One watch run's switches for its lane (memory only): the passes set [mode]; [ended] ends the lane after its look under way. */
    class Control {
        @Volatile var mode: Mode = Mode.OFF
        @Volatile var ended: Boolean = false
        /** When MCX's own pass last started (wall-clock ms, 0: none): that pass looks at MCX's stops itself. */
        @Volatile var mcxPassAt: Long = 0L
    }

    /** The step times the diagnostics and the Order speed card read. */
    const val NSE_TOTAL = "15-second stop check (total)"
    const val MCX_TOTAL = "MCX stop check (total)"

    /**
     * The lane until [stop] says so (read between looks: a look under way is finished). [mode] is read before each look, [held]
     * says whether anything is held for that mode, [look] runs one look from its start time, [mcxPassAt] when MCX's own pass
     * last started ([Control.mcxPassAt]). The clock and the sleep are the tests' seams.
     */
    suspend fun run(
        stop: () -> Boolean,
        mode: () -> Mode,
        held: (Mode) -> Boolean,
        look: suspend (Mode, Long) -> Unit,
        mcxPassAt: () -> Long,
        now: () -> Long = System::currentTimeMillis,
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        /** 25 Oct (H4): whether the held prices for [Mode] are quiet now ([quietFor]); the lane then looks every 5 s. */
        quiet: (Mode) -> Boolean = { false },
    ) {
        var m = Mode.OFF
        val heldIn = held
        val lookIn = look
        com.optionslab.ira.StopCadence.Ticker(now, sleep, quiet = { quiet(m) }, started = { t, q -> quietNow = q
            runCatching { quietGaps.look(t, q, com.optionslab.app.data.Market.today()) } }).run(
            stop = stop,
            held = { m = mode(); m != Mode.OFF && heldIn(m) },
            look = { t -> lookIn(m, t) },
            // Nothing held any more: the drift guard starts its count again (a gap from a look with nothing held is no drift).
            idle = { com.optionslab.app.data.WatchClock.looked(held = false, now = now()); quietGaps.idle() },
            failed = { e -> Tasks.stepFailed("stop lane", e) },
            // MCX's own pass runs these checks itself (its start is noted as a look): the lane looks only when that pass is late.
            // NSE's full pass is not counted (its stops come after its arms): the lane keeps its own 15 s there.
            outside = { if (m == Mode.MCX) mcxPassAt() else 0L },
        )
    }

    /**
     * Anything held for [mode]'s look, from memory only (unknown counts as held). NSE: the shared answer
     * ([com.optionslab.app.data.HeldNow]) or the watch loop's own last answer [nseSeen] (it also reads ORB's and Solo's books);
     * MCX: what is held or working on MCX ([com.optionslab.app.data.McxMarket.heldQuick]).
     */
    fun heldFor(mode: Mode, nseSeen: Boolean): Boolean = when (mode) {
        Mode.OFF -> false
        Mode.NSE -> nseSeen || com.optionslab.app.data.HeldNow.any()
        Mode.MCX -> runCatching { com.optionslab.app.data.McxMarket.heldQuick() }.getOrDefault(true)
    }

    /**
     * 25 Oct (Boss's decision on the app audit, H4: "faster stop checks while the price stream is quiet and a position is open"):
     * whether [mode]'s held prices are quiet now - logged in to Zerodha (the stream is expected) and a held paper position on that
     * side has no fresh tick, or the stream is not live while the bots hold a live position. Memory only; it only ever adds looks.
     */
    fun quietFor(mode: Mode): Boolean = runCatching {
        if (mode == Mode.OFF || !com.optionslab.app.data.Broker.loggedIn) return@runCatching false
        if (com.optionslab.app.data.Paper.heldQuiet(mcx = mode == Mode.MCX)) return@runCatching true
        val liveHeld = com.optionslab.app.data.ArmLive.holdingHint || com.optionslab.app.data.OrbArms.liveHint
        liveHeld && com.optionslab.app.data.KiteStream.status.value != com.optionslab.app.data.KiteStream.Status.LIVE
    }.getOrDefault(false)

    /** The look under way is on the quiet clock (its exits are waited on [QUIET_EXITS_WAIT_MS], so the next look is on time). */
    @Volatile internal var quietNow = false

    /** 25 Oct: how long a quiet look waits for the arms' exits (each still running finishes by itself, never cut mid-order). */
    const val QUIET_EXITS_WAIT_MS = 3_000L

    /** The day's quiet-stream gaps ([com.optionslab.ira.StopCadence.QuietGaps]); memory only. */
    val quietGaps = com.optionslab.ira.StopCadence.QuietGaps()

    /** The diagnostics' line. */
    fun quietLine(): String = quietGaps.line(com.optionslab.app.data.Market.today())

    /** One look, started at [startMs]: the drift guard notes it, then [mode]'s checks run. */
    suspend fun look(context: Context, mode: Mode, startMs: Long) {
        if (mode == Mode.OFF) return
        runCatching { com.optionslab.app.data.WatchClock.looked(held = true, now = startMs) }
        try {
            if (mode == Mode.MCX) mcxCheck(context) else nseCheck(context)
        } finally {
            WatchWorkers.timed(if (mode == Mode.MCX) MCX_TOTAL else NSE_TOTAL, System.currentTimeMillis() - startMs)
        }
    }

    /** One step in its own try: a failure is kept, never thrown; a cancellation goes up only when the lane itself was cancelled. */
    private suspend inline fun guarded(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            Tasks.stepFailed(name, e)
        } catch (e: Throwable) {
            Tasks.stepFailed(name, e)
        }
    }

    /**
     * NSE's 15-second stop check (moved here unchanged from the watch loop, 22 Oct): paper fills, unwatched positions, every
     * arm's exits side by side, the missed-lock sweep, the stops and targets. As safety work: its Zerodha requests go first.
     */
    private suspend fun nseCheck(context: Context) = Tasks.safely {
        // 26 Oct (R105 lead 4: the stops were looked at 30 s or more apart while held, 4 times on 23 Oct and 3 on 26 Oct, with the
        // stream quiet and Zerodha slow): the paper book's pass reads every held option's price, up to 30 s on a slow feed, and the
        // arms' exits waited behind it. It is still first, but waited on [PAPER_WAIT_MS] at most: one still running is left to
        // finish by itself (never cut mid-order, not started again beside itself) and the exits go on time.
        Tasks.together("15-second stop check: paper", listOf(com.optionslab.ira.Supervisor.Step("15-second stop check: paper orders") {
            guarded("15-second stop check: paper orders") { Tasks.paperEventsPublic(context, com.optionslab.app.data.Paper.tick()) }
            // 16 Oct: an arm's paper position with no record in its arm's book is taken back (or protected) first.
            guarded("15-second stop check: unwatched positions") { com.optionslab.app.data.OrphanWatch.run(context) }
        }), PAPER_WAIT_MS, inOrder = false, named = false)
        // The arms' exits side by side (10 Oct): ORB's (with Liquidity's index exits), and every money exit run here too (08 Oct,
        // research/PROFIT_LOCK_8OCT.md fix 3) - the Pine scripts' stops and locks, Solo's exits, Jarvis's own trades' profit-lock
        // stop and exits. Each under its own lock, as in the full pass, so a holding never has two sells in flight.
        Tasks.together("15-second stop check", listOfNotNull(
            com.optionslab.ira.Supervisor.Step("15-second stop check: ORB arms") { com.optionslab.app.data.OrbArms.priceCheckOnly() },
            com.optionslab.ira.Supervisor.Step("15-second stop check: Pine scripts") { com.optionslab.app.data.PineAuto.watchOnly() },
            if (com.optionslab.app.BuildConfig.JARVIS) com.optionslab.ira.Supervisor.Step("15-second stop check: Solo") { com.optionslab.app.ira.IraSolo.manageOnly() } else null,
            if (com.optionslab.app.BuildConfig.JARVIS) com.optionslab.ira.Supervisor.Step("15-second stop check: Jarvis's trades") { com.optionslab.app.ira.IraNewsTrades.tick() } else null,
            // 16 Oct: the paper arms whose exits otherwise ran only in the minute lane (which needs the stream's ticks).
            if (com.optionslab.app.data.PassedArmsHost.holding()) com.optionslab.ira.Supervisor.Step("15-second stop check: ${com.optionslab.app.data.FastPath.PASSED_ARMS}") { com.optionslab.app.data.PassedArmsHost.manageOnly() } else null,
            if (com.optionslab.app.data.HeroTicketArm.holding()) com.optionslab.ira.Supervisor.Step("15-second stop check: ${com.optionslab.app.data.FastPath.HERO_TICKET}") { com.optionslab.app.data.HeroTicketArm.manageOnly() } else null,
            if (com.optionslab.app.data.Ft3Arm.holding()) com.optionslab.ira.Supervisor.Step("15-second stop check: ${com.optionslab.app.data.FastPath.FT3}") { com.optionslab.app.data.Ft3Arm.manageOnly() } else null,
            if (com.optionslab.app.data.NtfyArm.holding()) com.optionslab.ira.Supervisor.Step("15-second stop check: ${com.optionslab.app.data.FastPath.NTFY}") { com.optionslab.app.data.NtfyArm.manageOnly() } else null,
            // 18 Oct: the paper strategies' live trades ([com.optionslab.app.data.ArmLive]): their Zerodha stops, the paper exits
            // they follow and the 15:15 square-off.
            if (com.optionslab.app.data.ArmLive.holdingHint || com.optionslab.app.data.ArmLive.recoverHint) com.optionslab.ira.Supervisor.Step("15-second stop check: ${com.optionslab.app.data.FastPath.ARMS_LIVE}") { com.optionslab.app.data.ArmLive.watch() } else null,
            // 18 Oct, review: the Strategy Module's open runs - their per-leg and basket stops, targets and trails (no scheduled
            // starts or square-offs: those stay in the full pass).
            if (com.optionslab.app.data.Strategies.runningHint) com.optionslab.ira.Supervisor.Step("15-second stop check: strategies") {
                val bad = com.optionslab.app.security.Integrity.compromised(com.optionslab.app.security.Integrity.reportWithin(context, 60_000))
                com.optionslab.app.data.Strategies.manageOnly(bad)
            } else null,
        ), if (quietNow) QUIET_EXITS_WAIT_MS else WatchWorkers.EXITS_WAIT_MS, inOrder = false, named = false)
        // Boss's 08 Oct safety items: the missed-lock sweeper (paper and Zerodha), the backup GTTs beside the bots' live stops,
        // and the no-price failsafe (REST quotes; a loud warning after two minutes).
        // 26 Oct: in the same order, waited on [SAFETY_WAIT_MS] at most (a slow Zerodha answer finishes by itself), so the next look
        // starts on time: the look is bounded by [LOOK_BOUND_MS], inside its 15 s.
        Tasks.together("15-second stop check: safety", listOf(com.optionslab.ira.Supervisor.Step("15-second stop check: sweep and stops") {
            guarded("15-second stop check: missed-lock sweep") { com.optionslab.app.data.Sweeper.run(context) }
            guarded("15-second stop check: stops and targets") { com.optionslab.app.data.Protections.tick() }
        }), SAFETY_WAIT_MS, inOrder = false, named = false)
    }

    /** 26 Oct: how long a look waits for the paper book's pass before the arms' exits (it finishes by itself after). */
    const val PAPER_WAIT_MS = 3_000L
    /** 26 Oct: how long a look waits for the missed-lock sweep and the stops-and-targets pass (they finish by themselves after). */
    const val SAFETY_WAIT_MS = 3_000L
    /** The longest a look waits in all: within the 15 s window, so a slow read never stretches the gap between looks. */
    const val LOOK_BOUND_MS = PAPER_WAIT_MS + WatchWorkers.EXITS_WAIT_MS + SAFETY_WAIT_MS

    /**
     * MCX's stop look between its passes (22 Oct): the steps of [Tasks.mcxTick] that guard what is held - paper fills (resting
     * stops and targets), the stops and targets (paper and Zerodha), the missed-lock sweep. The MCX pass still runs all of
     * them, the loss limit, the expiry exit and the MCX paper arms; this look only keeps the stops on time when that pass is slow.
     */
    private suspend fun mcxCheck(context: Context) = Tasks.safely {
        guarded("MCX stop check: paper orders") { Tasks.paperEventsPublic(context, com.optionslab.app.data.Paper.tick()) }
        guarded("MCX stop check: stops and targets") { com.optionslab.app.data.Protections.tick() }
        guarded("MCX stop check: missed-lock sweep") { com.optionslab.app.data.Sweeper.run(context) }
    }
}
