package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.ArmAutoStops
import com.optionslab.ira.ArmAutoStops.Rule
import com.optionslab.ira.LiquidityStopRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 19 Nov 2026 (Boss: "Also remove the stop trades after the max loss for all r51,r72 and liquidity strategy, i want it to run until i
 * stop them"; he chose all four, each a switch turned off by PIN; then "Go"): the switches of the five automatic stops on R51 Donchian 20,
 * R72 Breadth Donchian and Liquidity 15+5 ([ArmAutoStops]: the forward-test switch-off, Liquidity's live stop rule, its self-review park
 * and pause, the paper account stop, Jarvis's losing-arm cutoff), and Boss's one-time choice that switched them all off.
 *
 *  - ON by default in code (today's behaviour, the side that lowers risk): until the start's read lands every rule acts as before.
 *  - Boss's choice runs once ([migrateNow], after the start's read, off every hot path): all of them off, logged in the Autopilot change
 *    timeline as "Boss's choice, 19 Nov: run until he stops them", one entry each, listing what each still holds stopped (nothing is
 *    switched back on: Boss switches those arms on himself), each undone in one tap (back on: no PIN).
 *  - Bot settings' rows ([com.optionslab.app.ui.screens.ArmAutoStopsCard]): ON is one tap; OFF takes the PIN, never from Jarvis or a
 *    voice command ([set]'s byVoice refuses it; no voice path switches one either way).
 *  - Every other arm keeps them all; the kill switch, the day lock, the guard and daily loss limit, Stop for today and every live gate
 *    are untouched, and so is every exit of an open position.
 *
 * The arms' entry and stop paths read memory only here ([acts], [liveStatus], [paperAccountActs], [reviewMayStop]: no settings read, no
 * disk, no network, no lock held: OrderPathRound2Test stays 0). The switches are read once in the background; writes go in the background.
 */
object ArmAutoStopsHost {
    @Volatile private var state = ArmAutoStops.State()
    @Volatile private var loaded = false
    /** 20 Nov 2026 (minor fixes, item 3): why Boss's one-time choice was not applied here (a new or erased phone, a restore); null: applied or unknown. */
    @Volatile private var notApplied: ArmAutoStops.NotApplied? = null
    /** The last read of the switches failed (the card says so instead of "Reading..." for ever). */
    @Volatile private var readFailed = false
    /** The rules Boss switched in this process (the start's late read never overrides them). Under [lock]. */
    private val switched = HashSet<Rule>()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bot settings' rows and the arm rows: each switch, and whether it was read. */
    data class View(val loaded: Boolean = false, val state: ArmAutoStops.State = ArmAutoStops.State(),
                    val notApplied: ArmAutoStops.NotApplied? = null, val readFailed: Boolean = false) {
        fun isOn(r: Rule): Boolean = state.isOn(r)
        /**
         * 20 Nov 2026 (minor fixes, item 3): the card's notice when Boss's 19 Nov choice was not applied - the switches unreadable, the
         * choice still waiting on a read, a new or erased phone, or a restore - saying why and how to switch one off (PIN). Null: none.
         */
        fun notice(): String? = when {
            !loaded -> if (readFailed) ArmAutoStops.notAppliedNote(ArmAutoStops.NotApplied.UNREAD, state, switchesShown = false) else null
            !state.ready -> ArmAutoStops.notAppliedNote(ArmAutoStops.NotApplied.UNREAD, state)
            else -> ArmAutoStops.notAppliedNote(notApplied, state)
        }
        /** The arm row's line for [source] ([ArmAutoStops.rowLine]); null before the read, for another arm, or with all of them on. */
        fun rowLine(source: String): String? = if (!loaded) null else ArmAutoStops.rowLine(state, source)
    }
    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        scope.launch {
            load()
            // Boss's one-time choice, once the open positions are restored and watched and the stream is up ([migrateNow] waits for
            // them), so the arms' books can say what each rule still holds stopped (each read in its own try).
            runCatching { migrateNow() }
        }
    }

    /** The switches from the settings vault (a worker thread only). */
    internal fun load() {
        val saved = runCatching {
            check(!testReadFails) { "test: the vault cannot be read" }
            ArmAutoStops.decode({ r -> SecurePrefs.getBoolean(r.prefKey, true) }, { r -> SecurePrefs.getString(r.offAtKey) },
                SecurePrefs.getString(ArmAutoStops.HELD_KEY), SecurePrefs.getBoolean(ArmAutoStops.MIGRATION_KEY, false),
                SecurePrefs.getBoolean(ArmAutoStops.CUTOFF_MIGRATION_KEY, false))
        }.getOrNull()
        if (saved == null) { readFailed = true; publish(); return }     // unreadable now: the defaults stay (every rule on), read again next time
        // Why the choice was not applied, as kept (one set in memory - a restore's, an erase's - stays).
        val why = runCatching { ArmAutoStops.NotApplied.ofKey(SecurePrefs.getString(ArmAutoStops.NOT_APPLIED_KEY)) }.getOrNull()
        synchronized(lock) {
            state = merged(saved, state)
            loaded = true
            readFailed = false
            if (notApplied == null) notApplied = why
        }
        publish()
    }

    /** [saved] with the rules Boss switched in this process kept as he set them ([now]). Under [lock]. */
    private fun merged(saved: ArmAutoStops.State, now: ArmAutoStops.State): ArmAutoStops.State {
        // (Review: the one-time steps marked done in memory - a restore's mark among them - are never undone by a read.)
        if (switched.isEmpty()) return saved.copy(migrated = saved.migrated || now.migrated, cutoffMigrated = saved.cutoffMigrated || now.cutoffMigrated)
        return saved.copy(on = saved.on + switched.associateWith { now.isOn(it) },
            offAtMs = saved.offAtMs.filterKeys { it !in switched } + now.offAtMs.filterKeys { it in switched },
            heldAccount = if (Rule.PAPER_ACCOUNT in switched) now.heldAccount else saved.heldAccount,
            migrated = saved.migrated || now.migrated, cutoffMigrated = saved.cutoffMigrated || now.cutoffMigrated)
    }

    private fun publish() { runCatching { _view.value = View(loaded, state, notApplied, readFailed) } }

    private fun save(s: ArmAutoStops.State) { runCatching { SecurePrefs.putAllSoon(ArmAutoStops.encode(s)) } }

    // ---- the arms' paths (memory only) -----------------------------------------------------------------------------------------

    /** Whether [rule] acts on [who] (an arm's source, or the self-review's key; never a display name): always on another arm. Memory only. */
    fun acts(rule: Rule, who: String): Boolean = ArmAutoStops.acts(state, rule, who)

    /**
     * Whether a stop that LATCHES (the forward bar switching an arm off, the self-review's park or pause: nothing undoes them by itself)
     * may act on [who] now. Review: not on the three before the switches are read or before Boss's one-time choice has run (the first
     * moments after a start, the upgrade's first start) - it waits for the next pass, so Boss's saved "off" is never beaten by the start's
     * read. Every other arm, and IraGoldAlgo, as [acts]. Memory only.
     */
    fun mayLatch(rule: Rule, who: String): Boolean =
        if (ArmAutoStops.covers(who) && deferring()) false else acts(rule, who)

    /** Liquidity's live stop rule as its books read it ([ArmAutoStops.liveStatus]). Memory only. */
    fun liveStatus(raw: LiquidityStopRule.Status, trades: List<LiquidityStopRule.Trade>, today: LocalDate, resetMs: Long): LiquidityStopRule.Status =
        ArmAutoStops.liveStatus(state, raw, trades, today, resetMs)

    /** Whether the paper account stop refuses the entry of the arm [source] (null: not an arm's own entry; [ArmAutoStops.paperAccountActs]). Memory only. */
    fun paperAccountActs(source: String?, reachedNow: String?): Boolean = ArmAutoStops.paperAccountActs(state, source, reachedNow)

    /** Whether the self-review or Autopilot (not on Boss's yes or his own Undo, [byBoss]) may park or pause [key] now ([mayLatch]). Memory only. */
    fun reviewMayStop(key: String, byBoss: Boolean = false): Boolean = byBoss || mayLatch(Rule.SELF_REVIEW, key)

    /** The switch of [rule] now, from memory (on until the start's read lands). */
    fun isOn(rule: Rule): Boolean = state.isOn(rule)

    // ---- Boss's switches -------------------------------------------------------------------------------------------------------

    /**
     * Boss's switch for [rule] (Bot settings, or the change timeline's Undo): ON at once (it lowers risk); OFF only
     * with [pinConfirmed] and never [byVoice]. [log]: into the change timeline (false for its own Undo). A worker thread only. What to say.
     */
    fun set(rule: Rule, on: Boolean, pinConfirmed: Boolean = false, log: Boolean = true, byVoice: Boolean = false): String {
        if (!on && (byVoice || !pinConfirmed)) return ArmAutoStops.set(state, rule, false, 0L, pinConfirmed, byVoice).said
        if (!loaded) load()
        // Review: never written over a vault that could not be read (Boss's saved choices are unknown).
        if (!loaded) return "Could not read the automatic stops' switches just now. Nothing changed."
        // The paper account stop reached now, kept as it goes off (it still holds the three until Boss restarts its count).
        val held = if (!on && rule == Rule.PAPER_ACCOUNT) runCatching { BossRiskHost.refreshView(); BossRiskHost.accountKeyNow() }.getOrNull() else null
        val out = synchronized(lock) {
            val o = ArmAutoStops.set(state, rule, on, System.currentTimeMillis(), pinConfirmed, byVoice, held)
            if (o.changed) { state = o.state; switched += rule }
            o
        }
        if (!out.changed) return out.said
        // Only this rule's keys (review: never the others' nor the one-time choice's).
        runCatching { SecurePrefs.putAllSoon(ArmAutoStops.encodeRule(out.state, rule)) }
        publish()
        runCatching { Diag.record("autostops", "${rule.switch}: ${if (on) "off → on" else "on → off"} (by you${if (on) "" else ", with your PIN"})") }
        if (log) runCatching { com.optionslab.app.ira.Autopilot.logArmAutoStop(rule, on, if (on) ArmAutoStops.onReason(rule) else ArmAutoStops.offReason(rule, stoppedBy(rule))) }
        // Liquidity's live stop rule on again: its books read it at once (not at their next save).
        if (on && rule == Rule.LIVE_STOP) scope.launch { runCatching { OrbArms.refreshStopHints() } }
        return out.said
    }

    // ---- Boss's one-time choice (19 Nov) ---------------------------------------------------------------------------------------

    /**
     * Boss's choice, once (19 Nov: "run until he stops them"): all of them off, logged in the change timeline - one entry each, listing what
     * it still holds stopped (nothing is switched back on). Not before the switches were read (an unreadable vault: tried at the next
     * start); never in IraGoldAlgo; never twice. A worker thread only. True when it ran now.
     */
    internal suspend fun migrateNow(nowMs: Long = System.currentTimeMillis(), existed: Boolean? = null): Boolean {
        if (com.optionslab.app.BuildConfig.GOLD) return false
        if (!loaded) load()
        if (!loaded || state.ready) return false
        // Open positions and their stops first (CLAUDE.md): the books are read only after the start's positions step and the stream
        // (at once when this process never ran them; never forever).
        PositionsFirst.awaitPositions()
        PositionsFirst.awaitStream()
        if (state.migrated) return migrateCutoffNow(nowMs)
        // Review: a new phone, or one erased since ("Erase everything": the books and their trades go too), had none of the three arms
        // trading before Boss's choice - nothing of his to carry over: the choice is marked run with every rule on, as a fresh phone has
        // them (Boss switches them in Bot settings).
        // Review (19 Nov): a book not ready or not readable says nothing - the choice is left to a later pass ([ensureReady]) or start,
        // never marked done on a guess.
        val had = existed ?: tradedBeforeChoice() ?: return false
        if (!had) {
            synchronized(lock) { state = state.copy(migrated = true, cutoffMigrated = true); notApplied = ArmAutoStops.NotApplied.NEW_PHONE }
            runCatching { SecurePrefs.putAllSoon(mapOf(ArmAutoStops.MIGRATION_KEY to true, ArmAutoStops.CUTOFF_MIGRATION_KEY to true,
                ArmAutoStops.NOT_APPLIED_KEY to ArmAutoStops.NotApplied.NEW_PHONE.key)) }
            publish()
            runCatching { Diag.record("autostops", "no trade of R51, R72 or Liquidity before 19 Nov on this phone (new, or erased since): the automatic stops stay on, as on a fresh phone") }
            return false
        }
        // What each rule holds stopped now, read before it goes off (each in its own try; null: could not be read).
        val stopped = Rule.entries.associateWith { r -> runCatching { stoppedByNow(r) }.getOrNull() }
        val held = runCatching { BossRiskHost.refreshView(); BossRiskHost.accountKeyNow() }.getOrNull()
        val next = synchronized(lock) {
            val m = ArmAutoStops.migrate(state, nowMs, held) ?: return false
            // (A rule Boss switched himself in this process before the choice ran stays as he set it.)
            merged(m, state).copy(migrated = true, cutoffMigrated = true).also { state = it }
        }
        save(next)
        publish()
        runCatching { Diag.record("autostops", "${ArmAutoStops.CHOICE}: ${ArmAutoStops.line(next, true)}") }
        for (r in Rule.entries) if (!next.isOn(r))
            runCatching { com.optionslab.app.ira.Autopilot.logArmAutoStopOff(r, ArmAutoStops.offReason(r, stopped[r])) }
        return true
    }

    /**
     * Review (19 Nov): the fifth switch's own step ([ArmAutoStops.migrateCutoff]) on a phone whose first choice ran with four switches:
     * Jarvis's cutoff goes off too (logged in the change timeline) while Boss's choice stands. True when it switched it off now.
     */
    private suspend fun migrateCutoffNow(nowMs: Long): Boolean {
        if (state.cutoffMigrated) return false
        val stopped = runCatching { stoppedByNow(Rule.JARVIS_CUTOFF) }.getOrNull()
        val next = synchronized(lock) {
            if (state.cutoffMigrated) return false
            // (Review: the cutoff's switch Boss changed himself in this process stays as he set it: the step is only marked done, unlogged.)
            if (Rule.JARVIS_CUTOFF in switched) {
                state = state.copy(cutoffMigrated = true)
                runCatching { SecurePrefs.putAllSoon(mapOf(ArmAutoStops.CUTOFF_MIGRATION_KEY to true)) }
                publish()
                return false
            }
            ArmAutoStops.migrateCutoff(state, nowMs)?.also { state = it }
        } ?: return false
        runCatching { SecurePrefs.putAllSoon(ArmAutoStops.encodeRule(next, Rule.JARVIS_CUTOFF) + (ArmAutoStops.CUTOFF_MIGRATION_KEY to true)) }
        publish()
        if (next.isOn(Rule.JARVIS_CUTOFF)) return false
        runCatching { Diag.record("autostops", "${ArmAutoStops.CHOICE}: ${Rule.JARVIS_CUTOFF.switch} off too") }
        runCatching { com.optionslab.app.ira.Autopilot.logArmAutoStopOff(Rule.JARVIS_CUTOFF, ArmAutoStops.offReason(Rule.JARVIS_CUTOFF, stopped)) }
        return true
    }

    /** The day of Boss's choice: a trade of the three closed before it shows the phone ran them before (not new, not erased since). */
    private val CHOICE_DAY: LocalDate = LocalDate.of(2026, 11, 19)

    /**
     * Whether this phone ran R51, R72 or Liquidity before Boss's choice: a trade of theirs closed before it (paper or live). Null when it
     * cannot be told now - a book not ready yet, or a read that failed: the caller then decides nothing. Review: a book set aside as
     * unreadable is no evidence (the ORB family's book holds every ORB arm, not only these): the choice is decided by the books as they
     * read now - with no trade of the three, every stop stays on (the side that lowers risk; Boss can switch them off with his PIN).
     * Reads only; a worker thread.
     */
    internal suspend fun tradedBeforeChoice(): Boolean? {
        fun before(t: java.time.LocalDateTime?) = t?.toLocalDate()?.isBefore(CHOICE_DAY) == true
        val files = OrbArms.filesForErase()
        if (files.isEmpty()) return null                                   // the arms' book not set up yet
        val orb = runCatching { (OrbArms.closedPaper() + OrbArms.closedLive()).any { ArmAutoStops.covers(it.arm) && before(it.exitTime) } }.getOrNull()
            ?: return null
        if (orb) return true
        return runCatching { PassedArmsHost.tradedBefore(CHOICE_DAY) }.getOrNull()
    }

    /** One retry at a time ([ensureReady]), and not more than once a minute. */
    private val retrying = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastRetryMs = 0L

    /**
     * Review (19 Nov): the switches not read yet, or Boss's one-time choice not run yet (a vault or a book that could not be read at the
     * start): tried again in the background, at most once a minute. Called from the arms' and the review's workers - it only looks at
     * memory here and never waits (the read and the choice run on this object's own thread). True when it is still waiting.
     */
    fun ensureReady(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (com.optionslab.app.BuildConfig.GOLD || (loaded && state.ready)) return false
        if (nowMs - lastRetryMs < RETRY_MS || !retrying.compareAndSet(false, true)) return true
        lastRetryMs = nowMs
        scope.launch { try { runCatching { if (!loaded) load(); migrateNow() } } finally { retrying.set(false) } }
        return true
    }

    private const val RETRY_MS = 60_000L

    /**
     * The switches read now if they are not, and Boss's one-time choice run if it is due - on the caller's worker (the daily review),
     * never a hot path. True when ready after it.
     */
    suspend fun readyNow(): Boolean {
        if (com.optionslab.app.BuildConfig.GOLD) return true
        if (!loaded) load()
        runCatching { migrateNow() }
        return !deferring()
    }

    /**
     * Review (19 Nov): a restore never runs Boss's one-time choice on its books ([Backup.restore] marks it done in the vault): marked done
     * in memory too, so a retry before the restart cannot run it. [why]: the card's notice (20 Nov 2026), a restore on a phone where the
     * choice had not run yet; null when it had (its switches stand as they were).
     */
    fun markChoiceRun(why: ArmAutoStops.NotApplied? = null) {
        synchronized(lock) { state = state.copy(migrated = true, cutoffMigrated = true); if (why != null) notApplied = why }
        publish()
    }

    /** Whether the stops that latch are held back on the three now ([mayLatch]): the switches not read, or the choice not run. Memory only. */
    fun deferring(): Boolean = !com.optionslab.app.BuildConfig.GOLD && (!loaded || !state.ready)

    /** TEST ONLY: the settings vault cannot be read. */
    @Volatile internal var testReadFails = false

    /** What [rule] holds stopped now, in words (the timeline's list); null when it cannot be read. A worker thread only. */
    private fun stoppedBy(rule: Rule): List<String>? = runCatching { kotlinx.coroutines.runBlocking { stoppedByNow(rule) } }.getOrNull()

    private suspend fun stoppedByNow(rule: Rule): List<String>? = when (rule) {
        Rule.FORWARD_BAR -> PassedArmsHost.forwardBarStopped()?.map { "$it (its own switch on Home puts it back on)" }
        Rule.LIVE_STOP -> {
            // The arms' book read (its first read works the stop out), then the stop as its books read it.
            runCatching { OrbArms.view() }
            val s = OrbArms.liquidityStopHint
            if (s.stopped) listOf("Liquidity 15+5's BANKNIFTY books take no new live entry since ${s.day} (${s.why}) - re-enable them with " +
                "your PIN on Liquidity's row") else emptyList()
        }
        Rule.SELF_REVIEW -> {
            SmartWorkers.ensureLoaded()
            listOfNotNull(
                if (SmartWorkers.reviewParked(ArmAutoStops.REVIEW_KEY)) "Liquidity 15+5 parked to paper by its self-review (lift it with your PIN on its row)" else null,
                if (SmartWorkers.reviewPaused(ArmAutoStops.REVIEW_KEY)) "Liquidity 15+5's new entries paused by its self-review (in Paper your switch on Home " +
                    "lifts it; in Live your PIN on its row)" else null)
        }
        Rule.PAPER_ACCOUNT -> {
            BossRiskHost.refreshView()
            listOfNotNull(BossRiskHost.accountReachedWords()?.let { "the paper account stop, reached on $it, still holds every arm's new paper entries " +
                "until you restart its count with your PIN (Account guard page)" })
        }
        Rule.JARVIS_CUTOFF -> {
            // The cutoff leaves no mark of its own: Liquidity 15+5 switched off now (by you, or by Jarvis's cutoff) is said as it is.
            val liq = OrbArms.view().arms.firstOrNull { it.arm.source == com.optionslab.engine.orb.LiquidityRules.ARM.source }
            listOfNotNull(if (liq != null && !liq.armed) "Liquidity 15+5 is switched off (by you or by Jarvis's cutoff): its switch on Home " +
                "puts it back on" else null)
        }
    }

    // ---- words, erase, tests -----------------------------------------------------------------------------------------------------

    /** The diagnostics' line. Memory only. */
    fun line(): String = ArmAutoStops.line(state, loaded)

    /** Read once if the start's read has not landed (a worker thread only: Bot settings' card). */
    fun refresh() { if (!loaded) load() else publish() }

    /**
     * Erase everything ([PersonalStores]): as on a fresh phone - every rule on, nothing held ([switched]: a late start read never brings
     * Boss's old choice back). Its keys go with the settings vault; the one-time choice is marked run in memory, so it never runs again
     * in this process.
     */
    fun wipe() {
        synchronized(lock) {
            state = ArmAutoStops.State(migrated = true)
            loaded = true
            switched += Rule.entries
            notApplied = ArmAutoStops.NotApplied.NEW_PHONE
        }
        publish()
    }

    /** Whether Boss's choice (a rule off, a stop held) is still in memory (the erase's check). Memory only. */
    internal fun holdsForErase(): Boolean = state.off.isNotEmpty() || state.heldAccount != null

    /** TEST ONLY: the retry's clock forgotten ([ensureReady] may try at once). */
    internal fun retryNowForTest() { lastRetryMs = 0L }

    /** TEST ONLY: as a fresh start would have it ([state] read, the one-time choice run unless the test says so), nothing switched. */
    internal fun resetForTest(state: ArmAutoStops.State = ArmAutoStops.State(migrated = true), loaded: Boolean = true) {
        synchronized(lock) { this.state = state; this.loaded = loaded; switched.clear(); notApplied = null; readFailed = false }
        testReadFails = false
        publish()
    }
}
