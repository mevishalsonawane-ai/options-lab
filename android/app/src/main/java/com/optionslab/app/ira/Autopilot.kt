package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Diag
import com.optionslab.app.data.FlowGate
import com.optionslab.app.data.ManagerSpecialists
import com.optionslab.app.data.Market
import com.optionslab.app.data.SmartWorkers
import com.optionslab.app.data.TradeManagerHost
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.AutoChange
import com.optionslab.ira.AutoGate
import com.optionslab.ira.Experiments
import com.optionslab.ira.Findings
import com.optionslab.ira.OrderFlow
import com.optionslab.ira.Preflight
import com.optionslab.ira.SelfHeal
import com.optionslab.ira.SpecialistBook
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * Autopilot learning (Boss, 11 Oct: "I want Jarvis and the agents we created to learn and auto-heal"): after Jarvis's nightly
 * pass ([JarvisLearning], 15:45+) and once at 12:30 for health-triggered risk-lowering only, the changes the lessons and the
 * agents' records point to ([AutoChange.candidates]) are sorted ([AutoChange.plan]) and:
 *
 *  - AUTO ones are applied through the agents' own setters - the trade manager's mode ([TradeManagerHost.setMode]), a
 *    specialist's mute ([ManagerSpecialists.mute]), the order flow toward SHADOW only ([FlowGate.set]), a paper pause or a park
 *    by the self-review ([SmartWorkers]), the learned knobs (the safety-net schedule, self-healing's relay and candle backoff,
 *    the trap guard's and the brain's shadow thresholds, the self-review's stricter thresholds) - logged with the reason and
 *    the numbers, on probation, undoable with one tap (an undo that raises live risk takes the PIN);
 *  - ASK ones go to Boss through [IraHub.offer] as before (live ones need his PIN in the change log);
 *  - NEVER ones are never touched. Nothing here places, changes or cancels an order, sizes anything, or reads the settings
 *    vault on an order path; the mode is read once on a worker thread.
 *
 * Smarter learning (11 Oct): before anything is applied the plan goes through [AutoGate] - a lesson must clear tonight's bar
 * (Benjamini-Hochberg q, raised by the trials so far), a building one may instead start a paper EXPERIMENT ([Experiments],
 * at most two, paper only, judged on alternate paper trades' shadow copies), a regime-only lesson on a target with no
 * per-regime setting is recorded, and every paper or live change is first replayed on the stored history ([Preflight]:
 * the trade manager's wick-settled records, the closed trades, the order flow's shadow log; off the main thread, 200 ms
 * at most) - a worse replay is skipped and logged "pre-flight failed".
 *
 * Every probation is checked again ([AutoChange.review], a sequential test capped at the old 15 cases / 5 days): worse
 * reverts by itself, or is asked when the revert would raise live risk. The log lives in noBackupFilesDir/autopilot (never the vault, never a backup); its setting
 * ([K_MODE]) is never taken from a backup ([com.optionslab.ira.Upkeep.PRIVATE]) and a voice may only lower it. Not in GOLD.
 */
internal object Autopilot {
    private const val DIR = "autopilot"
    private const val LOG = "log.txt"
    private const val KNOBS = "knobs.txt"
    /** Boss's setting (OFF / SAFE / SHADOW_ONLY): on this phone only - never in or from a backup. */
    const val K_MODE = "jarvis.autolearn.mode"
    private const val WHO = "Autopilot"
    private const val MIDDAY = 12 * 60 + 30
    private const val NIGHT = 15 * 60 + 45

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _book = MutableStateFlow(AutoChange.Book())
    /** The change log (the Home card and its sheet read it). */
    val book: StateFlow<AutoChange.Book> = _book

    private val _mode = MutableStateFlow(AutoChange.Mode.SAFE)
    val mode: StateFlow<AutoChange.Mode> = _mode

    /** The learned knob values (absent: the agent's default). Memory; kept in noBackupFilesDir. */
    private val _knobs = MutableStateFlow<Map<String, Double>>(emptyMap())
    val knobs: StateFlow<Map<String, Double>> = _knobs

    @Volatile private var loaded = false
    /**
     * Boss's setting really read from the vault (or set by him since). Until then the mode is OFF - nothing changes by itself
     * on a storage failure - and it is read again on the next [ensureLoaded] (review G5-4).
     */
    @Volatile private var modeLoaded = false
    /** TEST ONLY: the setting's read instead of the vault's (a throw: the vault could not be read). */
    @Volatile internal var testModeRead: (() -> String?)? = null
    /** One pass at a time (compare-and-set: two workers waking together never both run it). */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** TEST ONLY: the inputs instead of the agents' stores; and whether the live account is on. */
    @Volatile internal var testInputs: ((LocalDate) -> AutoChange.Inputs)? = null
    @Volatile internal var testLive: Boolean? = null
    /** TEST ONLY: the stored history a pre-flight replays and experiments are judged on (default with [testInputs]: its records). */
    @Volatile internal var testReplay: ((LocalDate) -> Preflight.Data)? = null

    fun enabled(): Boolean = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    fun init(context: Context) {
        appContext = context.applicationContext
        if (enabled()) scope.launch {
            com.optionslab.app.data.PositionsFirst.awaitPositions(); runCatching { ensureLoaded() }
            // 21 Oct: commodities on their own paper limits by Boss's choice, shown once in the timeline (off the main thread).
            runCatching { if (com.optionslab.app.data.AppSettings.load().mcxOwnLimits) logSegmentSplitOnce() }
        }
    }

    private fun dir(): File? = appContext?.let { File(it.noBackupFilesDir, DIR) }

    /** The log, the knobs and the setting read once (a worker thread). */
    fun ensureLoaded() {
        if (loaded && modeLoaded) return
        synchronized(lock) {
            if (loaded && modeLoaded) return
            if (!loaded) loadFiles()
            readMode()
        }
    }

    /** The setting from the vault; an unreadable vault (or a failed read) leaves it OFF and not read, to be tried again. */
    private fun readMode() {
        if (modeLoaded) return
        val r = runCatching { testModeRead?.let { it() } ?: SecurePrefs.getString(K_MODE) }
        if (r.isFailure || (testModeRead == null && SecurePrefs.unreadable)) { _mode.value = AutoChange.Mode.OFF; return }
        val m = r.getOrNull()
        _mode.value = AutoChange.Mode.entries.firstOrNull { it.name == m } ?: AutoChange.Mode.SAFE
        modeLoaded = true
    }

    private fun loadFiles() {
        val d = dir()
        val text = runCatching { d?.let { File(it, LOG) }?.takeIf { it.isFile }?.readText() }.getOrNull()
        if (_book.value == AutoChange.Book()) _book.value = AutoChange.decode(text)
        val kn = runCatching { d?.let { File(it, KNOBS) }?.takeIf { it.isFile }?.readLines() }.getOrNull().orEmpty()
            .mapNotNull { l -> l.split('=').takeIf { it.size == 2 }?.let { (k, v) -> v.toDoubleOrNull()?.let { x -> k to x } } }
            .filter { (k, v) -> AutoChange.knob(k)?.let { v in it.min..it.max } == true }.toMap()
        if (_knobs.value.isEmpty()) _knobs.value = kn
        loaded = true
    }

    private fun save() {
        val d = dir() ?: return
        // 26 Oct: after "Erase everything" an empty log writes no files (a save may still be queued).
        if (_book.value == AutoChange.Book() && _knobs.value.isEmpty() && !File(d, LOG).exists() && !File(d, KNOBS).exists()) return
        runCatching {
            d.mkdirs()
            write(File(d, LOG), AutoChange.encode(_book.value))
            write(File(d, KNOBS), _knobs.value.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }

    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun saveSoon() { scope.launch { synchronized(lock) { save() } } }

    /** Written now, on the caller's thread (tests: the erase's seed). */
    internal fun saveNow() { synchronized(lock) { save() } }

    // ---- the setting ---------------------------------------------------------------------------------------------------------

    private fun liveOn(): Boolean = testLive ?: runCatching { AppSettings.load().let { it.live && it.allowRealOrders } }.getOrDefault(false)

    /** Does setting [m] ask for the PIN? Only SAFE, from a lower level, while the live account is on. */
    fun needsPin(m: AutoChange.Mode): Boolean = m == AutoChange.Mode.SAFE && _mode.value != AutoChange.Mode.SAFE && liveOn()

    const val PIN_NEEDED = "The live account is on: confirm with your PIN to set Autopilot learning to SAFE. Nothing changed."

    /** Boss sets the level (Settings → Bot settings → Autopilot learning). OFF and SHADOW-ONLY need nothing; SAFE the PIN when live. */
    fun setMode(m: AutoChange.Mode, pinConfirmed: Boolean = false): String {
        if (needsPin(m) && !pinConfirmed) return PIN_NEEDED
        _mode.value = m
        // Boss's own choice: it stands over a vault read that had failed before (never overwritten by a later read).
        modeLoaded = true
        runCatching { SecurePrefs.putAllSoon(mapOf(K_MODE to m.name)) }
        runCatching { Diag.record("autopilot", "learning: ${m.name}") }
        return when (m) {
            AutoChange.Mode.OFF -> "Autopilot learning is off: nothing changes by itself. Jarvis still learns and asks you."
            AutoChange.Mode.SHADOW_ONLY -> "Autopilot learning is on for shadow changes only; paper and live changes are asked."
            AutoChange.Mode.SAFE -> "Autopilot learning is SAFE: shadow and paper changes by itself, live only to lower risk; anything that adds risk is asked."
        }
    }

    /** By voice: only ever lower ([AutoChange.voiceSet]). What to say. */
    fun voiceSet(want: AutoChange.Mode): String {
        ensureLoaded()
        val now = _mode.value
        val next = AutoChange.voiceSet(now, want)
        if (next == now && want.level > now.level) return AutoChange.VOICE_RAISE
        if (next == now) return "Autopilot learning is already ${now.words}, Boss."
        return setMode(next)
    }

    // ---- the passes ----------------------------------------------------------------------------------------------------------

    /**
     * After Jarvis's nightly pass (15:45+) once a trading day, and once at 12:30 (risk-lowering only). Memory checks until it
     * is due; never the main thread, no network. True when a pass ran.
     */
    suspend fun runIfDue(context: Context? = null): Boolean {
        if (!enabled()) return false
        if (context != null && appContext == null) appContext = context.applicationContext
        val today = Market.today()
        if (!Market.isTradingDay(today)) return false
        val minute = Market.minuteNow()
        withContext(Dispatchers.IO) { ensureLoaded() }
        // Boss's setting not read (the vault could not be read): no pass until it is - tried again on the next look (G5-4).
        if (!modeLoaded) return false
        val b = _book.value
        return when {
            minute >= NIGHT && b.lastRun != today && (JarvisLearning.book.value.lastPass == today || testInputs != null) -> { run(today, healthOnly = false); true }
            minute in MIDDAY until NIGHT && b.lastMidday != today -> { run(today, healthOnly = true); true }
            else -> false
        }
    }

    /** One pass (and by tests): gather, plan, apply, ask, review the probations, tell. */
    internal suspend fun run(today: LocalDate, healthOnly: Boolean, nowMs: Long = System.currentTimeMillis()): AutoChange.Plan? {
        if (!running.compareAndSet(false, true)) return null
        try {
            synchronized(lock) { _book.value = if (healthOnly) _book.value.copy(lastMidday = today) else _book.value.copy(lastRun = today) }
            // The pass's day kept on disk before anything is applied: a crash or a kill never runs it twice.
            withContext(Dispatchers.IO) { synchronized(lock) { save() } }
            val inputs = withContext(Dispatchers.IO) { runCatching { testInputs?.invoke(today) ?: gather(today) }.getOrNull() } ?: return null
            // The stored history the pre-flight replays and the experiments are judged on (files and memory; no network).
            val data = withContext(Dispatchers.IO) {
                runCatching { testReplay?.invoke(today) ?: if (testInputs != null) Preflight.Data(records = inputs.records) else replayData() }.getOrNull()
            } ?: Preflight.Data(records = inputs.records)
            val m = _mode.value
            val lessons = inputs.lessons.associateBy { it.id }
            val (plan, routed) = withContext(Dispatchers.Default) {
                val p = AutoChange.plan(_book.value, AutoChange.candidates(inputs), today, m, healthOnly)
                p to AutoGate.route(_book.value, p, lessons, data, healthOnly)
            }
            // Not applied: a pre-flight that came out worse (or over its budget), a lesson that holds under one regime only.
            for (s in routed.skipped) {
                synchronized(lock) { _book.value = AutoChange.record(_book.value, s.change, AutoChange.Status.SKIPPED, nowMs, today, note = s.note, q = s.q).first }
                runCatching { Diag.record("autopilot", "skipped ${s.change.what}: ${s.note}") }
            }
            // A building lesson on paper: an experiment on alternate paper trades instead of a change (paper only, at most two).
            for (c in routed.experiments) {
                synchronized(lock) { _book.value = Experiments.start(_book.value, c, today, nowMs) }
                runCatching { IraActivity.add("Autopilot started a paper experiment: ${c.what} ${c.from} → ${c.to}") }
            }
            val made = ArrayList<AutoChange.Change>()
            for (g in routed.apply) {
                // S14: learning frozen - recorded as skipped, not applied; a change that only lowers risk still goes (the freeze
                // is there to stop learning from adding to a fall, never to keep risk up).
                if (AutoChange.riskOf(g.change) != AutoChange.Risk.LOWER && LearningFreeze.holds()) { synchronized(lock) { _book.value = AutoChange.record(_book.value, g.change, AutoChange.Status.SKIPPED, nowMs, today, note = LearningFreeze.NOTE, q = g.q).first }; continue }
                val c = g.change
                val said = runCatching { apply(c, pinConfirmed = false) }.getOrNull() ?: continue
                synchronized(lock) {
                    _book.value = AutoChange.record(_book.value, c, AutoChange.Status.PROBATION, nowMs, today, q = g.q, preflight = g.preflight?.words().orEmpty()).first
                }
                // Kept on disk at once: a crash later in the pass never forgets a change that was made.
                withContext(Dispatchers.IO) { synchronized(lock) { save() } }
                made += c
                tellOne(c, said)
                c.lessonId?.let { id -> runCatching { JarvisLearning.autoStatus(id, "applied") } }
            }
            for (c in plan.ask) runCatching { ask(c, today, nowMs) }
            if (!healthOnly) runCatching { reviewProbations(today) }
            if (!healthOnly) runCatching { reviewExperiments(today, data, nowMs) }
            withContext(Dispatchers.IO) { synchronized(lock) { save() } }
            if (made.isNotEmpty()) runCatching { Diag.record("autopilot", "${made.size} change(s): " + made.joinToString("; ") { "${it.what}: ${it.from} → ${it.to}" }) }
            if (!healthOnly || made.isNotEmpty()) runCatching { notifyDay(today) }
            return plan
        } finally {
            running.set(false)
        }
    }

    /** Every agent's state now, from memory and their own small files (no network). */
    private suspend fun gather(today: LocalDate): AutoChange.Inputs {
        JarvisLearning.ensureLoaded()
        val lessons = JarvisLearning.book.value.lessons
        val policy = TradeManagerHost.policy.value
        val sb = ManagerSpecialists.book.value
        runCatching { SmartWorkers.refreshReviews() }
        val s = SmartWorkers.settings.value
        val own = _book.value.entries.filter { it.applied && it.by == WHO.lowercase(Locale.ENGLISH) && it.change.to == AutoChange.PAUSED }.map { it.change.target }.toSet()
        // Event days are not evidence (G9-6, LearningFilter): their trades are left out, as the lessons already leave them out.
        val records = TradeManagerHost.records.value.filter { runCatching { com.optionslab.ira.LearningFilter.includeMs(it.trade.entryMs) }.getOrDefault(true) }
        return AutoChange.Inputs(today, lessons, records, policy,
            sb.tuned.mapValues { it.value.muted }, sb.frozen.mapValues { it.value.muted }, SmartWorkers.reviews.value,
            s.parked, s.paused, FlowGate.modes.value, _knobs.value, own)
    }

    // ---- applying one change through its agent's own setter ------------------------------------------------------------------

    /**
     * [c] applied through its agent's setter: what was said, or null when the agent refused or nothing changed. A change that
     * raises live risk goes only with [pinConfirmed]. Never an order, a size, a stop or a safety setting.
     */
    internal fun apply(c: AutoChange.Change, pinConfirmed: Boolean, byBoss: Boolean = false): String? {
        if (AutoChange.never(c)) return null
        // Liquidity BANKNIFTY frozen for its paper test (Boss, 12 Oct): no automatic change aimed at Liquidity applies - it is
        // recorded only - except parking its LIVE entries to paper (that only takes risk off). Boss's own yes still applies.
        if (!byBoss && com.optionslab.ira.LiquidityFreeze.touches(c.target) && runCatching { com.optionslab.app.data.LiquidityTest.frozenNow() }.getOrDefault(true) &&
            !(c.kind == AutoChange.Kind.LIVE_ENTRIES && c.to == AutoChange.PARKED)) {
            runCatching { com.optionslab.app.data.Diag.record("autopilot", "${c.what}: ${com.optionslab.ira.LiquidityFreeze.NOTE}") }
            return null
        }
        val raisesLive = c.scope == AutoChange.Scope.LIVE && AutoChange.riskOf(c) == AutoChange.Risk.RAISE
        if (raisesLive && !pinConfirmed) return null
        val parts = c.target.split(':')
        return when (c.kind) {
            AutoChange.Kind.SPECIALIST_MUTE -> {
                if (parts.size != 3 || parts[0] != "spec") return null
                // A mute Boss said yes to is his hand ("muted by you"): Autopilot never un-mutes it by itself.
                if (c.to == AutoChange.MUTED) ManagerSpecialists.mute(parts[1], parts[2], by = if (byBoss) "you (on Autopilot's ask)" else WHO)
                else ManagerSpecialists.unmute(parts[1], parts[2], pinConfirmed).takeIf { it != ManagerSpecialists.PIN_UNMUTE }
            }
            AutoChange.Kind.SPECIALIST_WEIGHT -> {
                if (parts.size != 3) return null
                val w = c.to.toDoubleOrNull() ?: return null
                ManagerSpecialists.setTunedWeight(parts[1], parts[2], w)
            }
            AutoChange.Kind.MANAGER_MODE -> {
                if (parts.size != 3) return null
                val acct = if (parts[2] == "live") TradeManager.Account.LIVE else TradeManager.Account.PAPER
                val to = TradeManager.Mode.entries.firstOrNull { it.name == c.to } ?: return null
                // Only ACT <-> SHADOW: never OFF by itself (the record goes on), and the setter keeps its own PIN rule.
                if (to == TradeManager.Mode.OFF) return null
                val said = TradeManagerHost.setMode(parts[1], acct, to, pinConfirmed)
                said.takeIf { TradeManagerHost.policy.value.mode(parts[1], acct) == to }
            }
            AutoChange.Kind.PAPER_ENTRIES -> {
                val key = c.target.removePrefix("paper:")
                if (c.to == AutoChange.PAUSED) SmartWorkers.pausePaperByAutopilot(key) else SmartWorkers.resumePaperByAutopilot(key)
            }
            // Boss's 13 Oct park ([logBossPark]): only its Undo applies here - the arm switched back on, ON PAPER (never cleared
            // for Zerodha: in Live an ordinary arm asks for the PIN, which is never given here, so nothing changes). Never re-parked.
            // 14 Oct: the MCX paper arm switched off on Boss's OK (research R42, [logMcxPark], target "mcx:"): its Undo switches it
            // back on - the MCX arms are paper only, always (nothing there can reach Zerodha). Only the SILVERM morning call.
            AutoChange.Kind.ARM_PARK -> if (c.target.startsWith(AutoChange.MCX_ARM)) {
                val source = c.target.removePrefix(AutoChange.MCX_ARM)
                // 25 Oct: the NG evening breakout's switch-off ([logMcxEvePark]) is undone the same way.
                if (c.to != AutoChange.ON || (source != com.optionslab.engine.mcx.McxMorningPark.SOURCE && source != com.optionslab.engine.mcx.McxEvePark.SOURCE)) return null
                val said = kotlinx.coroutines.runBlocking { com.optionslab.app.data.McxPaperArms.setArmed(source, true) }
                said.takeIf { it.contains("on, paper only") || it.contains("already on") }
            } else {
                val source = c.target.removePrefix("arm:")
                if ((c.to != AutoChange.ON && c.to != AutoChange.ON_APPROVE) || !com.optionslab.engine.orb.FamilyPark.covers(source)) return null
                val said = kotlinx.coroutines.runBlocking {
                    com.optionslab.app.data.OrbArms.setArmed(source, true, automatic = c.to == AutoChange.ON, pinConfirmed = false)
                }
                said.takeIf { it.contains("armed on paper") }
            }
            // Boss's own Liquidity paper trail switch ([logBossTrail]): only its Undo applies here - the switch flipped back,
            // without logging the flip again (log = false: the Undo marks this entry undone, nothing new is recorded). Paper only.
            // 21 Oct (Boss's choice, [logSegmentSplit]): only its Undo applies here - commodities back on the shared paper lock and
            // loss limit (lowers paper risk). Never switched on from here: that is Bot settings' switch, with the PIN. Paper only.
            AutoChange.Kind.SEGMENT_SPLIT -> {
                if (c.target != AutoChange.SPLIT_TARGET || c.to != AutoChange.SWITCHED_OFF) return null
                com.optionslab.app.data.DayLockGuard.setSplit(false)
            }
            AutoChange.Kind.LIQ_TRAIL -> {
                if (c.target != AutoChange.TRAIL_TARGET || (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF)) return null
                com.optionslab.app.data.LiquidityTest.setTrail(c.to == AutoChange.ON, log = false)
            }
            // 22 Oct: Boss's own Liquidity paper breakeven switch ([logBossBreakeven]): only its Undo applies here, as the trail's.
            AutoChange.Kind.LIQ_BREAKEVEN -> {
                if (c.target != AutoChange.BREAKEVEN_TARGET || (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF)) return null
                com.optionslab.app.data.LiquidityTest.setBreakeven(c.to == AutoChange.ON, log = false)
            }
            // 15 Oct: Boss's Hero ₹1,000 ticket switched on by the update on their choice ([logBossTicketOn]): its Undo switches the
            // arm off (or, undone twice, back on) - a PAPER arm, never Zerodha, so no PIN either way; the flip is not logged again.
            // 15 Oct (Boss's "Both"): a passed-research paper arm ("paperarm:<source>": R51, R52, Solo 2.0) - its Undo switches it
            // off (or back on after its forward bar switched it off). Paper only, never Zerodha: no PIN either way; not logged again.
            // 25 Oct: ntfy alerts' paper trading ("paperarm:ntfy") - its Undo switches it off (or back on). Paper only, never
            // Zerodha: no PIN either way; not logged again. The removed Telegram chats' old entries: nothing to undo now.
            AutoChange.Kind.PAPER_ARM_ON -> if (c.target.startsWith(AutoChange.REMOVED_CHAT_PAPER)) {
                null
            } else if (c.target == AutoChange.NTFY_PAPER) {
                if (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF) return null
                kotlinx.coroutines.runBlocking { com.optionslab.app.data.NtfyArm.setOn(c.to == AutoChange.ON, log = false) }
            } else if (c.target == AutoChange.FLOW_TARGET) {
                // 28 Oct: IraThinker's flow ([logFlowOn]): its Undo switches the arm off (or, undone twice, back on) - paper, no PIN.
                if (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF) return null
                val said = kotlinx.coroutines.runBlocking { com.optionslab.app.data.FlowArm.setArmed(c.to == AutoChange.ON, log = false) }
                said.takeIf { !it.contains("not ready") }
            } else if (c.target == AutoChange.FT3_TARGET) {
                // 21 Oct: FT3 near-ATM morning ([logFt3On]): its Undo switches the arm off (or, undone twice, back on) - on paper,
                // never Zerodha itself (its live path is the standard one, behind the PIN and Shadow), so no PIN either way.
                if (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF) return null
                val said = kotlinx.coroutines.runBlocking { com.optionslab.app.data.Ft3Arm.setArmed(c.to == AutoChange.ON, log = false) }
                said.takeIf { !it.contains("not ready") }
            } else if (c.target != AutoChange.TICKET_TARGET) {
                val source = c.target.removePrefix(AutoChange.PAPER_ARM)
                // 19 Oct: "record only" (Boss: "Yes, keep only breadth donchain") is the arm's third state: its Undo puts back what it was.
                val mode = when (c.to) {
                    AutoChange.ON -> com.optionslab.app.data.PassedArmsHost.Mode.ON
                    AutoChange.SWITCHED_OFF -> com.optionslab.app.data.PassedArmsHost.Mode.OFF
                    AutoChange.RECORD_ONLY -> com.optionslab.app.data.PassedArmsHost.Mode.RECORD
                    else -> null
                }
                if (!c.target.startsWith(AutoChange.PAPER_ARM) || source !in com.optionslab.app.data.PassedArmsHost.SOURCES || mode == null) return null
                val said = kotlinx.coroutines.runBlocking { com.optionslab.app.data.PassedArmsHost.setMode(source, mode, log = false) }
                said.takeIf { !it.contains("not ready") }
            } else {
                if (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF) return null
                val said = kotlinx.coroutines.runBlocking { com.optionslab.app.data.HeroTicketArm.setArmed(c.to == AutoChange.ON, log = false) }
                said.takeIf { !it.contains("not ready") }
            }
            // 15 Oct (Boss's cleanup, [logArmHidden] / [logArmRestored]): an arm's switch-off undone restores it (back on PAPER
            // only when it was on: each arm's no-PIN path); a restore undone hides it again (switched off once it holds nothing).
            AutoChange.Kind.ARM_HIDE -> {
                val key = c.target.removePrefix(AutoChange.HIDE)
                if (!c.target.startsWith(AutoChange.HIDE) || !com.optionslab.engine.orb.ArmsCleanup.covers(key)) return null
                when (c.to) {
                    AutoChange.HIDDEN -> kotlinx.coroutines.runBlocking { com.optionslab.app.data.HiddenArms.hideAgain(key) }.takeIf { it.contains("hidden again") }
                    AutoChange.ON, AutoChange.SWITCHED_OFF, AutoChange.RESTORED ->
                        kotlinx.coroutines.runBlocking { com.optionslab.app.data.HiddenArms.restore(key, log = false) }.takeIf { it.contains("is back on its row") }
                    else -> null
                }
            }
            // 15 Oct: Liquidity's FINNIFTY or MIDCPNIFTY books on by Boss's choice ([logLiqIndexOn]): its Undo parks the index again.
            AutoChange.Kind.LIQ_INDEX_ON -> {
                val index = c.target.removePrefix(AutoChange.LIQ_INDEX)
                if (!c.target.startsWith(AutoChange.LIQ_INDEX) || index !in com.optionslab.engine.orb.ArmsCleanup.LIQ_INDICES) return null
                when (c.to) {
                    AutoChange.PARKED, AutoChange.SWITCHED_OFF -> kotlinx.coroutines.runBlocking { com.optionslab.app.data.OrbArms.parkLiquidity(index) }
                    AutoChange.ON -> kotlinx.coroutines.runBlocking { com.optionslab.app.data.OrbArms.unparkLiquidity(index) }
                    else -> null
                }
            }
            // 16 Oct (research R63): the Trend watch's paper exit for a source whose rule passed (today only the Hero ticket). Its
            // Undo switches that exit off (a sold leg is not bought back); undone twice, on again. Paper only: no PIN either way.
            AutoChange.Kind.TREND_EXIT -> {
                val src = com.optionslab.engine.trend.TrendWatch.Source.of(c.target.removePrefix(AutoChange.TREND_TARGET))
                if (!c.target.startsWith(AutoChange.TREND_TARGET) || src == null || !com.optionslab.engine.trend.TrendWatch.canAct(src)) return null
                when (c.to) {
                    AutoChange.SWITCHED_OFF, AutoChange.HELD -> com.optionslab.app.data.TrendWatchHost.setAct(src, false, log = false)
                    AutoChange.ON, AutoChange.SOLD_ON_TURN -> com.optionslab.app.data.TrendWatchHost.setAct(src, true, log = false)
                    else -> null
                }
            }
            // 16 Oct (Boss's choice: "All three"): R64's 30-60-100 lock on the passed paper arms ([logPassedLockOn]): its Undo switches it off
            // for new trades (open ones keep what they entered with). Paper only, never Zerodha: no PIN either way; not logged again.
            // 16 Oct (Boss's choice: "Select the best"): R66's +5% once +25% step ([AutoChange.L25_TARGET], its own entry): its Undo
            // switches the step off for new trades (back to the plain 30-60-100 lock). Paper only; not logged again.
            AutoChange.Kind.PAPER_LOCK -> {
                if ((c.target != AutoChange.LOCK_TARGET && c.target != AutoChange.L25_TARGET) || (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF)) return null
                kotlinx.coroutines.runBlocking {
                    if (c.target == AutoChange.L25_TARGET) com.optionslab.app.data.PassedArmsHost.setL25(c.to == AutoChange.ON, log = false)
                    else com.optionslab.app.data.PassedArmsHost.setLock(c.to == AutoChange.ON, log = false)
                }.takeIf { !it.contains("not ready") }
            }
            // 16 Oct (Boss: "T7 supertrend agree"): R73 T7's Supertrend filter on R51 Donchian 20 ([logPassedSupertrendOn]): its Undo
            // switches it off for new breaks (back to the plain rules). Paper only, never Zerodha: no PIN either way; not logged again.
            // 19 Oct (Boss: "Resolve the issues"): the same-direction cap on the automatic paper entries ([logSameDirCapOn]): its Undo
            // switches it off for paper (or back on). Paper only (live stays in shadow behind its own switch): no PIN; not logged again.
            // 25 Oct (Boss's decisions on the app audit): the new paper rules' Undo flips their paper switch back (loosening a risk
            // limit takes the PIN: checked in [undo] through [needsPin] and again by the setter); the Hero ticket's trail start
            // goes back to "after 2x" for new tickets. Paper only; not logged again.
            AutoChange.Kind.PAPER_FILTER -> if (c.target in AutoChange.BOSS_PAPER_RULES) {
                if (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF) return null
                val on = c.to == AutoChange.ON
                if (c.target == AutoChange.TICKET_TRAIL_TARGET) {
                    kotlinx.coroutines.runBlocking { com.optionslab.app.data.HeroTicketArm.setTrailStart(
                        if (on) com.optionslab.engine.orb.HeroTicketRules.TrailStart.AT_FILL else com.optionslab.engine.orb.HeroTicketRules.TrailStart.AFTER_2X) }
                        .takeIf { !it.contains("not ready") }
                } else {
                    val rule = com.optionslab.app.data.BossRiskHost.Rule.ofTarget(c.target) ?: return null
                    com.optionslab.app.data.BossRiskHost.setPaper(rule, on, pinConfirmed = pinConfirmed || on, log = false)
                        .takeIf { it != com.optionslab.app.data.BossRiskHost.PIN_LOOSEN }
                }
            } else if (c.target == AutoChange.SAME_DIR_TARGET) {
                if (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF) return null
                com.optionslab.app.data.ExposureCapHost.setPaperOn(c.to == AutoChange.ON, log = false)
            } else if (c.target == AutoChange.NO_TWIN_TARGET) {
                // 20 Oct, enhancement round 1: Liquidity's no-twin rule on paper ([logLiqNoTwinOn], [logBossNoTwin]): its Undo flips the
                // switch back for paper (live stays in shadow). Paper only: no PIN; not logged again.
                if (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF) return null
                com.optionslab.app.data.LiquidityTest.setNoTwin(c.to == AutoChange.ON, log = false)
            } else {
                if (c.target != AutoChange.ST_TARGET || (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF)) return null
                kotlinx.coroutines.runBlocking { com.optionslab.app.data.PassedArmsHost.setSupertrend(c.to == AutoChange.ON, log = false) }
                    .takeIf { !it.contains("not ready") }
            }
            // 18 Oct (Boss: every paper strategy present for live too): an arm's own "Trade live" switch ([logArmLive]). Its Undo
            // flips it back: off is free; on raises live risk, so it goes only with the PIN (checked above and in the setter).
            AutoChange.Kind.ARM_LIVE -> {
                val key = c.target.removePrefix(AutoChange.ARM_LIVE)
                if (!c.target.startsWith(AutoChange.ARM_LIVE) || com.optionslab.engine.orb.LiveMirror.spec(key) == null ||
                    (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF)) return null
                kotlinx.coroutines.runBlocking { com.optionslab.app.data.ArmLive.setLive(key, c.to == AutoChange.ON, pinConfirmed, log = false) }
                    .takeIf { com.optionslab.app.data.ArmLive.done(it) }
            }
            // 18 Oct (Boss: "add a shadow button"): an arm's own Shadow switch ([logArmShadow]). Its Undo flips it back: into shadow is
            // free; out of shadow sends real live orders, so it goes only with the PIN (checked above and in the setter).
            AutoChange.Kind.ARM_SHADOW -> {
                val key = c.target.removePrefix(AutoChange.ARM_SHADOW)
                if (!c.target.startsWith(AutoChange.ARM_SHADOW) || com.optionslab.engine.orb.LiveMirror.spec(key) == null ||
                    (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF)) return null
                kotlinx.coroutines.runBlocking { com.optionslab.app.data.ArmLive.setShadow(key, c.to == AutoChange.ON, pinConfirmed, log = false) }
                    .takeIf { com.optionslab.app.data.ArmLive.done(it) }
            }
            AutoChange.Kind.LIVE_ENTRIES -> {
                val key = c.target.removePrefix("live:")
                if (c.to == AutoChange.PARKED) SmartWorkers.parkByAutopilot(key)
                else { SmartWorkers.ownerRearmed(key); "${key} may take live entries again." }
            }
            AutoChange.Kind.FLOW_THRESHOLD -> {
                val key = c.target.removePrefix("flow:")
                val th = c.to.toIntOrNull() ?: return null
                // Only while that strategy is in SHADOW: under CONFIRM the threshold decides real skips.
                if (FlowGate.setting(key).mode != OrderFlow.Mode.SHADOW) return null
                FlowGate.set(key, OrderFlow.Mode.SHADOW, th)
            }
            AutoChange.Kind.FLOW_MODE -> {
                val key = c.target.removePrefix("flow:")
                val to = OrderFlow.Mode.entries.firstOrNull { it.name == c.to } ?: return null
                // Only toward SHADOW or OFF - or back to the CONFIRM an earlier change took away (it only skips); the order
                // flow's own rule stands: CONFIRM on a live-capable strategy in Live takes Boss's PIN.
                if (to == OrderFlow.Mode.CONFIRM && !(c.revert && c.from == OrderFlow.Mode.SHADOW.name)) return null
                FlowGate.set(key, to, pinConfirmed = pinConfirmed).takeIf { it != FlowGate.PIN_NEEDED }
            }
            AutoChange.Kind.TRAP_THRESHOLD, AutoChange.Kind.WAKE_SCHEDULE, AutoChange.Kind.HEAL_BACKOFF, AutoChange.Kind.REGIME_THRESHOLD,
            AutoChange.Kind.DRIFT_THRESHOLD -> setKnob(c.target.removePrefix("knob:"), c.to.toDoubleOrNull() ?: return null)
            else -> null
        }
    }

    /** A learned knob set within its bounds, and handed to its agent where it acts. What to say. */
    private fun setKnob(key: String, v: Double): String? {
        val k = AutoChange.knob(key) ?: return null
        if (v < k.min || v > k.max) return null
        _knobs.value = if (v == k.default) _knobs.value - key else _knobs.value + (key to v)
        handOver(key, v)
        saveSoon()
        return "${k.words}: ${AutoChange.fmt(v)}"
    }

    /** Where a knob acts at once: the safety-net schedule and the relay's backoff (the others are read where they are used). */
    private fun handOver(key: String, v: Double) {
        when (key) {
            "wake.net_flat_sec" -> runCatching { SmartWorkers.setNetFlatSec(v.toInt()) }
            "heal.relay_open_ms" -> runCatching { SmartWorkers.health.tune(SelfHeal.Dep.RELAY, v.toLong()) }
        }
    }

    /** The knob's value now (its default when not learned). Memory only. */
    fun knob(key: String): Double = _knobs.value[key] ?: AutoChange.knob(key)?.default ?: Double.NaN

    // ---- asking --------------------------------------------------------------------------------------------------------------

    /** Where a live raise is confirmed with the PIN. */
    const val WHERE = "Home → Automation card → Autopilot → Change log"

    private fun ask(c: AutoChange.Change, today: LocalDate, nowMs: Long) {
        val e = synchronized(lock) { AutoChange.record(_book.value, c, AutoChange.Status.OFFERED, nowMs, today).also { _book.value = it.first }.second }
        val live = c.scope == AutoChange.Scope.LIVE && AutoChange.riskOf(c) == AutoChange.Risk.RAISE
        val text = "Boss, ${c.what}: ${c.from} → ${c.to}, because ${c.reason}." +
            if (live) " That adds live risk: confirm here, then apply it with your PIN in $WHERE." else " Shall I?"
        IraHub.offer("autopilot: ${c.what}", "Boss, Autopilot suggests a change", text, suspend { acceptAsked(e.id, pinConfirmed = false) },
            addsRisk = AutoChange.riskOf(c) == AutoChange.Risk.RAISE, alwaysAsk = true, declined = { declined(e.id) })
    }

    /** Boss said yes to an asked change ([pinConfirmed]: from the change log with his PIN). What to say. */
    fun acceptAsked(id: String, pinConfirmed: Boolean): String {
        ensureLoaded()
        val e = AutoChange.entry(_book.value, id) ?: return "That change is no longer waiting."
        if (e.status != AutoChange.Status.OFFERED) return "That change is no longer waiting."
        val live = e.change.scope == AutoChange.Scope.LIVE && AutoChange.riskOf(e.change) == AutoChange.Risk.RAISE
        if (live && !pinConfirmed) return "It adds live risk, Boss: apply it with your PIN in $WHERE."
        val said = runCatching { apply(e.change, pinConfirmed, byBoss = true) }.getOrNull() ?: return "Nothing changed: ${e.change.what} could not be changed just now."
        val today = Market.today()
        synchronized(lock) { _book.value = AutoChange.accepted(_book.value, id, System.currentTimeMillis(), today) }
        e.change.lessonId?.let { runCatching { JarvisLearning.autoStatus(it, "applied") } }
        saveSoon()
        return said
    }

    private fun declined(id: String) {
        synchronized(lock) { _book.value = AutoChange.declined(_book.value, id, Market.today()) }
        saveSoon()
    }

    // ---- undo ----------------------------------------------------------------------------------------------------------------

    const val PIN_UNDO = "Undoing that raises live risk: confirm with your PIN in $WHERE. Nothing changed."

    /** Does undoing [id] need the PIN (it raises live risk)? */
    fun undoNeedsPin(id: String): Boolean = AutoChange.undo(_book.value, id)?.let { needsPin(it.inverse, it.needsPin) } == true

    /**
     * Does applying [inverse] need the PIN: by its scope ([scopeSays]), or by what its agent holds now - un-muting where the
     * trade manager acts on live (any mute also mutes the live book), the order flow's CONFIRM where its own rule takes the PIN.
     */
    private fun needsPin(inverse: AutoChange.Change, scopeSays: Boolean): Boolean {
        if (scopeSays) return true
        val p = inverse.target.split(':')
        return when (inverse.kind) {
            AutoChange.Kind.SPECIALIST_MUTE -> inverse.to != AutoChange.MUTED && p.size == 3 &&
                runCatching { ManagerSpecialists.liveActs(p[1]) }.getOrDefault(true)
            AutoChange.Kind.FLOW_MODE -> inverse.to == OrderFlow.Mode.CONFIRM.name &&
                runCatching { FlowGate.needsPin(inverse.target.removePrefix("flow:"), OrderFlow.Mode.CONFIRM) }.getOrDefault(true)
            // 25 Oct: switching one of the new paper risk limits off loosens it: the PIN, as loosening the paper loss limit takes it.
            AutoChange.Kind.PAPER_FILTER -> inverse.target in AutoChange.PIN_TO_LOOSEN && inverse.to == AutoChange.SWITCHED_OFF &&
                runCatching { com.optionslab.app.security.GuardPin.asks(true, runCatching { AppSettings.load().live }.getOrDefault(true)) }.getOrDefault(true)
            else -> false
        }
    }

    /** Boss's Undo on [id] (the change log): the inverse through the same setter; a live raise only with [pinConfirmed]. */
    fun undo(id: String, pinConfirmed: Boolean = false): String {
        ensureLoaded()
        // An older flip of Boss's Liquidity paper trail switch: only the newest one undoes (13 Oct review). Said, nothing changed.
        AutoChange.entry(_book.value, id)?.let { e -> if (e.applied && AutoChange.superseded(_book.value, e)) return AutoChange.SUPERSEDED_UNDO }
        val u = AutoChange.undo(_book.value, id) ?: return "That change can't be undone now (it was already undone or reverted)."
        if (needsPin(u.inverse, u.needsPin) && !pinConfirmed) return PIN_UNDO
        val said = runCatching { apply(u.inverse, pinConfirmed) }.getOrNull() ?: return "Nothing changed: ${u.entry.change.what} could not be put back just now."
        synchronized(lock) { _book.value = AutoChange.undone(_book.value, id, Market.today()) }
        runCatching { Diag.record("autopilot", "undone by you: ${u.entry.change.what}") }
        saveSoon()
        // A leg sold on the trend turn is not bought back: its Undo only switches the trend exit off ([AutoChange.trendExitSold]).
        if (u.entry.change.kind == AutoChange.Kind.TREND_EXIT && u.entry.change.to == AutoChange.SOLD_ON_TURN) return "Undone: $said The leg sold stays sold."
        return "Undone: ${u.entry.change.what} is back to ${u.entry.change.from}. $said"
    }

    /** "Undo the last change" by voice: put to Boss as a request (an undo that raises live risk is done only with the PIN). */
    fun offerUndoLast(): String {
        ensureLoaded()
        val e = AutoChange.lastUndoable(_book.value) ?: return "I have no change of my own to undo, Boss."
        val pin = undoNeedsPin(e.id)
        val text = "Undo ${e.change.what}: ${e.change.to} → ${e.change.from}?" + if (pin) " It raises live risk, so it needs your PIN in $WHERE." else ""
        IraHub.offer("undo Autopilot's last change", "Boss, undo Autopilot's last change?", text,
            suspend { if (pin) PIN_UNDO else undo(e.id) }, addsRisk = pin, alwaysAsk = true)
        return "Asked: undo ${e.change.what}" + if (pin) " (it raises live risk: your PIN in $WHERE)." else "."
    }

    // ---- probation -----------------------------------------------------------------------------------------------------------

    private suspend fun reviewProbations(today: LocalDate) {
        val records = TradeManagerHost.records.value
        val outcomes by lazy { SpecialistBook.outcomes(records) }
        val signals by lazy { runCatching { FlowGate.signalsForLearning() }.getOrDefault(emptyList()) }
        fun after(e: AutoChange.Entry): List<Double>? {
            val p = e.change.target.split(':')
            return when (p.getOrNull(0)) {
                "spec" -> outcomes.filter { it.family == p.getOrNull(1) && it.specialist == p.getOrNull(2) && it.atMs > e.atMs }.sortedBy { it.atMs }.map { it.rs }
                "mgr" -> records.filter { it.trade.family == p.getOrNull(1) && it.trade.account.name.lowercase(Locale.ENGLISH) == p.getOrNull(2) &&
                    (it.actual?.atMs ?: 0L) > e.atMs }.sortedBy { it.actual?.atMs ?: 0L }.mapNotNull { it.vsOriginal }
                "flow" -> signals.filter { it.key == p.getOrNull(1) && it.epochSec * 1000 > e.atMs && !it.live && it.agreement == OrderFlow.Agreement.DISAGREES }
                    .mapNotNull { it.net }
                "knob" -> trapGroup(p.getOrNull(1))?.let { flags -> signals.filter { s -> s.epochSec * 1000 > e.atMs && !s.live && s.flags.any { it in flags } }.mapNotNull { it.net } }
                else -> null
            }
        }
        val judged = withContext(Dispatchers.Default) { synchronized(lock) {
            val (b, j) = AutoChange.review(_book.value, today) { after(it) }
            _book.value = b
            j
        } }
        for (j in judged) when (j.verdict) {
            AutoChange.Verdict.KEEP -> j.entry.change.lessonId?.let { runCatching { JarvisLearning.autoStatus(it, "kept") } }
            AutoChange.Verdict.REVERT -> {
                val inv = j.inverse ?: continue
                // Recorded as reverted only when it really was: an agent that refused (its PIN rule, nothing to change) leaves
                // the revert to Boss, asked like a live one.
                if (runCatching { apply(inv, pinConfirmed = false) }.getOrNull() == null) {
                    synchronized(lock) { _book.value = AutoChange.revertNotApplied(_book.value, j.entry.id, j.why) }
                    offerRevert(j)
                    continue
                }
                j.entry.change.lessonId?.let { runCatching { JarvisLearning.autoStatus(it, "reverted") } }
                runCatching { SmartWorkers.found(WHO, Findings.Kind.OTHER, Findings.ALL, words = "Autopilot reverted ${j.entry.change.what}: ${j.why}", strength = 55,
                    ttlMs = 24 * 3_600_000L) }
                runCatching { Diag.record("autopilot", "reverted ${j.entry.change.what}") }
            }
            AutoChange.Verdict.ASK_REVERT -> {
                if (j.inverse == null) continue
                j.entry.change.lessonId?.let { runCatching { JarvisLearning.autoStatus(it, "reverted") } }
                offerRevert(j)
            }
            AutoChange.Verdict.WAIT -> {}
        }
    }

    /** A revert left to Boss: asked, done with his PIN in the change log ([revertWithPin]). */
    private fun offerRevert(j: AutoChange.Judged) {
        val id = j.entry.id
        runCatching {
            IraHub.offer("autopilot: revert ${j.entry.change.what}", "Boss, revert an Autopilot change?",
                "Boss, ${j.entry.change.what} did worse since I changed it (${j.why}). Putting it back needs your PIN: confirm here, then revert it with your PIN in $WHERE.",
                suspend { "Revert it with your PIN in $WHERE." }, addsRisk = true, alwaysAsk = true,
                declined = { synchronized(lock) { _book.value = AutoChange.declined(_book.value, id, Market.today()) }; saveSoon() })
        }
    }

    /** The history a pre-flight replays: the trade manager's records, the closed paper and live trades, the order flow's shadow log. */
    private suspend fun replayData(): Preflight.Data {
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        return Preflight.Data(TradeManagerHost.records.value,
            runCatching { IraAccount.trips(false, owners) }.getOrDefault(emptyList()),
            runCatching { IraAccount.trips(true, owners) }.getOrDefault(emptyList()),
            runCatching { FlowGate.signalsForLearning() }.getOrDefault(emptyList()))
    }

    // ---- paper experiments ---------------------------------------------------------------------------------------------------

    /**
     * Every running experiment judged ([Experiments.review]): a win graduates to a paper change (pre-flight and probation as
     * ever; a live twin only asked), a loss ends it and Jarvis keeps a negative lesson; nothing ever touches live by itself.
     */
    private suspend fun reviewExperiments(today: LocalDate, data: Preflight.Data, nowMs: Long) {
        val outs = withContext(Dispatchers.Default) { synchronized(lock) {
            val (b, o) = Experiments.review(_book.value, today, data)
            _book.value = b
            o
        } }
        for (o in outs) {
            val x = o.experiment
            if (x.status == Experiments.Status.RUNNING) continue
            runCatching { Diag.record("autopilot", "experiment ${x.status.words}: ${x.change.what}") }
            if (x.status == Experiments.Status.WON || x.status == Experiments.Status.LOST) withContext(Dispatchers.IO) {
                runCatching { JarvisLearning.experimentResult(x.change.lessonId, x.change.target, x.change.what, x.status == Experiments.Status.WON, o.pairs) }
            }
            val grad = o.graduate
            if (grad != null) runCatching { graduate(grad, today, data, nowMs) }
            val twin = o.ask
            if (twin != null && !AutoChange.never(twin)) runCatching { ask(twin, today, nowMs) }
        }
    }

    /** A won experiment's paper change made: pre-flight first, then through its agent's setter, on probation like any other. */
    private suspend fun graduate(c: AutoChange.Change, today: LocalDate, data: Preflight.Data, nowMs: Long) {
        if (c.scope != AutoChange.Scope.PAPER || AutoChange.never(c) || LearningFreeze.holds()) return
        // The same gates as any automatic change: SAFE only, a resting or blacklisted target left alone, the day's cap.
        val held = AutoChange.graduateHeld(_book.value, c, today, _mode.value)
        if (held != null) {
            synchronized(lock) { _book.value = AutoChange.record(_book.value, c, AutoChange.Status.SKIPPED, nowMs, today, by = Experiments.BY, note = "won, not applied: $held").first }
            return
        }
        val pre = withContext(Dispatchers.Default) { Preflight.check(c, data) }
        if (!pre.ok) {
            synchronized(lock) { _book.value = AutoChange.record(_book.value, c, AutoChange.Status.SKIPPED, nowMs, today, by = Experiments.BY, note = pre.words()).first }
            return
        }
        val said = runCatching { apply(c, pinConfirmed = false) }.getOrNull() ?: return
        synchronized(lock) {
            _book.value = AutoChange.record(_book.value, c, AutoChange.Status.PROBATION, nowMs, today, by = Experiments.BY,
                note = "graduated from its paper experiment", preflight = pre.words()).first
        }
        tellOne(c, said)
    }

    const val PIN_REVERT = "Putting that back needs your PIN: revert it with your PIN in $WHERE. Nothing changed."

    /**
     * Boss's revert of a probation left to him (the change log): only with his PIN ([pinConfirmed], from the PIN prompt itself).
     * A revert is left to Boss only because its agent refused it without the PIN - un-muting where the manager acts on live,
     * a live raise - so it is never done without it, whatever the change's own scope (review G5-1).
     */
    fun revertWithPin(id: String, pinConfirmed: Boolean): String {
        if (!pinConfirmed) return PIN_REVERT
        ensureLoaded()
        val e = AutoChange.entry(_book.value, id) ?: return "That change is gone."
        if (e.status != AutoChange.Status.REVERT_ASKED) return "That change is not waiting for a revert."
        val inv = AutoChange.inverse(e.change, "reverted on your PIN")
        val said = runCatching { apply(inv, pinConfirmed = pinConfirmed) }.getOrNull() ?: return "Nothing changed: it could not be put back just now."
        synchronized(lock) { _book.value = AutoChange.revertAccepted(_book.value, id, Market.today()) }
        saveSoon()
        return "Reverted: ${e.change.what} is back to ${e.change.from}. $said"
    }

    private fun trapGroup(key: String?): Set<String>? = when (key) {
        "trap.lockout_sec" -> setOf("STOP_HUNT", "FAILED_BREAK")
        "trap.pull_drop" -> setOf("PULL_BID", "PULL_ASK")
        "trap.absorb_x" -> setOf("ABSORPTION_BUY", "ABSORPTION_SELL", "EXHAUSTION_BUY", "EXHAUSTION_SELL")
        else -> null
    }

    // ---- the specialists' own nightly tuning, logged as Autopilot's ------------------------------------------------------------

    /**
     * The specialists of [family] whose weight Boss undid (or said no to) and which still rest on [day]: the nightly tuning
     * leaves their weight alone ([SpecialistBook.tune]). Memory (the log is read once on a worker thread).
     */
    fun restedWeights(family: String, day: LocalDate): Set<String> {
        if (!enabled()) return emptySet()
        ensureLoaded()
        val pre = "${AutoChange.Kind.SPECIALIST_WEIGHT.name}:weight:$family:"
        return _book.value.rest.filter { (k, until) -> k.startsWith(pre) && !day.isAfter(until) }.keys.map { it.removePrefix(pre) }.toSet()
    }

    /** [ManagerSpecialists]' nightly tuning of [family] (already applied there): logged here, undoable. */
    fun logTuning(family: String, before: SpecialistBook.Tuning?, after: SpecialistBook.Tuning, base: com.optionslab.ira.ManagerConfig) {
        if (!enabled()) return
        val acts = TradeManagerHost.policy.value.mode(family, TradeManager.Account.PAPER) == TradeManager.Mode.ACT
        val cs = AutoChange.fromTuning(family, before, after, base, if (acts) AutoChange.Scope.PAPER else AutoChange.Scope.SHADOW)
        if (cs.isEmpty()) return
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        synchronized(lock) {
            var b = _book.value
            for (c in cs) b = AutoChange.record(b, c, AutoChange.Status.KEPT, System.currentTimeMillis(), today, by = "specialists' tuning",
                note = "the specialists' own nightly tuning").first
            _book.value = b
        }
        saveSoon()
    }

    /**
     * Boss's 13 Oct park of [source] ([label]; [com.optionslab.engine.orb.FamilyPark]), just applied by the arms' one-time
     * change: logged as Boss's (kept, not on probation) so "Today's changes" shows it and one tap undoes it (back on, on paper).
     * [wasLive]: it was armed for Zerodha; [holding]: its open position is still managed to its exit. In the background, on
     * Autopilot's own worker: the arms' load never waits for the log's disk.
     */
    fun logBossPark(source: String, label: String, automatic: Boolean, wasLive: Boolean, holding: Boolean) {
        if (!enabled()) return
        val fp = com.optionslab.engine.orb.FamilyPark
        val c = AutoChange.armPark(source, label, fp.record(source), fp.TESTED[source]?.let { it.first + it.second } ?: 0.0, automatic)
        val note = "your choice, 13 Oct" +
            (if (wasLive) "; it was armed for Zerodha - in Live, switch it on with your PIN on its row" else "") +
            (if (holding) "; its open position is still managed to its exit" else "")
        logBossOwnOnce(c, note)
    }

    /** How long a park logged at start-up waits for [init] (the arms' books are read before Autopilot's own init runs). */
    private const val INIT_WAIT_MS = 120_000L

    /**
     * Boss's own change [c] (an arm's park, [AutoChange.bossOwn]) logged once, kept, by "your choice", with [note]: never twice
     * for the same target (a second load of an old book, a restore). In the background, on Autopilot's own worker. 14 Oct
     * review: it first waits for [init] - the arms' books are read at start-up before Autopilot's init, and reading the log
     * before its file is known would load an empty log, mark it read and later save over Boss's real change history.
     */
    private fun logBossOwnOnce(c: AutoChange.Change, note: String) {
        scope.launch {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) { runCatching { Diag.record("autopilot", "not logged (Autopilot not started): ${c.what}") }; return@runCatching }
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    if (_book.value.entries.none { it.change.kind == c.kind && it.change.target == c.target }) {
                        _book.value = AutoChange.record(_book.value, c, AutoChange.Status.KEPT, System.currentTimeMillis(), today, by = "your choice", note = note).first
                        save()
                    }
                }
            }
        }
    }

    /**
     * 14 Oct (research R42, Boss's OK; [com.optionslab.engine.mcx.McxMorningPark]): the SILVERM morning call, an MCX PAPER
     * arm, just switched off by the MCX arms' one-time change. Logged as Boss's (kept, not on probation) so "Today's changes"
     * and the change log show it with R42's reason, and one tap undoes it (back on, paper only - no PIN: the MCX arms never
     * reach Zerodha). [holding]: its open position still closes by its own exit. Once: never logged twice (a second load, a
     * restart). In the background, on Autopilot's own worker: the arms' load never waits for the log's disk.
     */
    fun logMcxPark(source: String, label: String, holding: Boolean) {
        if (!enabled()) return
        val mp = com.optionslab.engine.mcx.McxMorningPark
        val c = AutoChange.mcxArmPark(source, label, mp.REASON, mp.WALK_FORWARD_TRADES, mp.WALK_FORWARD_RS.toDouble())
        logBossOwnOnce(c, "your OK, 14 Oct (research R42); paper only" + (if (holding) "; its open position still closes by its own exit" else ""))
    }

    /**
     * 25 Oct (Boss's decision on the arms audit; research R53 and R83, [com.optionslab.engine.mcx.McxEvePark]): the NG evening
     * breakout, an MCX PAPER arm, switched off by the MCX arms' one-time change. Logged as Boss's (kept), once; one tap undoes it (back
     * on, paper only - no PIN: the MCX arms never reach Zerodha). [holding]: its open position still closes by its own exit.
     */
    fun logMcxEvePark(source: String, label: String, holding: Boolean) {
        if (!enabled()) return
        val ep = com.optionslab.engine.mcx.McxEvePark
        val c = AutoChange.mcxArmPark(source, label, ep.REASON, ep.R83_TRADES, ep.R83_RS.toDouble())
        logBossOwnOnce(c, "your decision, 25 Oct (research R53 and R83); paper only" + (if (holding) "; its open position still closes by its own exit" else ""))
    }

    /**
     * 21 Oct (Boss: "Yes, give commodities their own day lock"): commodities on their own PAPER day lock and loss limit by this
     * update ([AutoChange.segmentSplit]), logged once as Boss's choice (kept, not on probation) so the timeline shows it and one
     * tap puts the shared lock back. Never logged twice (a restart, an undone entry): Bot settings' switch logs later flips.
     */
    private fun logSegmentSplitOnce() = logBossOwnOnce(AutoChange.segmentSplit(), "your choice, 21 Oct; paper only, never live")

    /**
     * Bot settings' "Commodities keep their own paper limits" switch flipped by Boss (21 Oct): on is logged as a new entry (its Undo
     * puts the shared lock back); off closes the newest kept entry as undone, so no Undo is left that would do nothing.
     */
    fun logSegmentSplit(on: Boolean) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                if (appContext == null) return@runCatching
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    if (on) _book.value = AutoChange.record(_book.value, AutoChange.segmentSplit(), AutoChange.Status.KEPT, System.currentTimeMillis(),
                        today, by = "your choice", note = "your choice in Bot settings; paper only, never live").first
                    else {
                        val e = _book.value.entries.lastOrNull { it.change.kind == AutoChange.Kind.SEGMENT_SPLIT && it.status == AutoChange.Status.KEPT } ?: return@runCatching
                        _book.value = AutoChange.undone(_book.value, e.id, today)
                    }
                    save()
                }
            }
        }
    }

    /**
     * Boss switched the MCX arm [source] back on from its own row after its 14 Oct switch-off ([logMcxPark]): the change log's
     * entry is closed as undone, so it no longer shows "on → parked" with an Undo that could switch the arm on again later
     * (after Boss switched it off by hand). Its own Undo lands here too; marking it undone twice changes nothing.
     */
    fun mcxUnparkedByBoss(source: String) {
        if (!enabled()) return
        scope.launch {
            runCatching {
                if (appContext == null) return@runCatching
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    val e = _book.value.entries.lastOrNull { it.change.kind == AutoChange.Kind.ARM_PARK && it.change.target == AutoChange.MCX_ARM + source }
                    if (e != null && e.status == AutoChange.Status.KEPT) { _book.value = AutoChange.undone(_book.value, e.id, today); save() }
                }
            }
        }
    }

    /**
     * 15 Oct (Boss: "add it directly to arm"): the Hero ₹1,000 ticket, a PAPER arm that did not pass research (R50), just switched
     * on once by its book's one-time change ([com.optionslab.app.data.HeroTicketArm]). Logged as Boss's (kept, not on probation,
     * never Autopilot's) so "Today's changes" and the change log show it with R50's verdict, and one tap undoes it (the arm off).
     * Once: never logged twice (a second load, a restart). In the background, on Autopilot's own worker.
     */
    fun logBossTicketOn() {
        if (!enabled()) return
        logBossOwnOnce(AutoChange.heroTicketOn(), "your choice, 15 Oct; paper only - it never trades on Zerodha; its trades are kept in their own record")
    }

    /**
     * 21 Oct (Boss: "Add FT3 to the app paper bot until live, when live is started then it should move to live"): FT3 near-ATM
     * morning, the R98 forward test that did not pass research, just switched on once by its book's one-time change
     * ([com.optionslab.app.data.Ft3Arm]). Logged as Boss's (kept, never Autopilot's), and one tap undoes it (the arm off). Once.
     */
    /** 28 Oct: IraThinker's flow switched on once by the update, on Boss's ask - logged as Boss's, once, undone in one tap. */
    fun logFlowOn() {
        if (!enabled()) return
        logBossOwnOnce(AutoChange.flowOn(), "your ask, 28 Oct; on paper, index options only - not proven (R110); live only the standard " +
            "way (Live with your PIN, AI trades live, its own Trade live switch, off until you turn it on)")
    }

    /** Boss switched IraThinker's flow from its row or page: both ways logged as a new kept entry ([bossPaperFlip]). */
    fun flowSwitchedByBoss(on: Boolean) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) { runCatching { Diag.record("autopilot", "not logged (Autopilot not started): IraThinker's flow ${if (on) "on" else "off"}") }; return@runCatching }
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    bossPaperFlip(AutoChange.FLOW_TARGET, on, today, if (on) AutoChange.flowOn() else AutoChange.paperArmOffByBoss(AutoChange.FLOW_TARGET, "IraThinker's flow"),
                        if (on) "switched on from its row; on paper - not proven (R110)" else "switched off from its row; paper only")
                }
            }
        }
    }

    fun logFt3On() {
        if (!enabled()) return
        logBossOwnOnce(AutoChange.ft3On(), "your choice, 21 Oct; on paper - live only the standard way (Live with your PIN, AI trades live, " +
            "its Trade live switch, Shadow off with your PIN); its trades are the FT3 forward test's own record")
    }

    /**
     * 25 Oct (app audit; 23 Oct: R51, R52 and Solo 2.0 switched off between 09:49 and 10:36 left no line in the change timeline):
     * Boss's flip of the paper arm [target] from its row, [on] or off, logged BOTH ways as a new kept entry dated [today] (the
     * timeline shows the day's entries only), with "your choice" and [note]. The newest kept entry of that switch is closed as
     * undone first, so an older Undo can never flip it later. Paper only. Under [lock].
     */
    private fun bossPaperFlip(target: String, on: Boolean, today: LocalDate, change: AutoChange.Change, note: String) {
        _book.value.entries.lastOrNull { it.change.kind == AutoChange.Kind.PAPER_ARM_ON && it.change.target == target && it.status == AutoChange.Status.KEPT }
            ?.let { _book.value = AutoChange.undone(_book.value, it.id, today) }
        _book.value = AutoChange.record(_book.value, change, AutoChange.Status.KEPT, System.currentTimeMillis(), today, by = "your choice", note = note).first
        save()
        runCatching { Diag.record("autopilot", "logged: ${change.what} ${change.from} → ${change.to} (your choice, from its row)") }
    }

    /**
     * Boss switched FT3 from its own row: as [heroTicketSwitchedByBoss] - off closes the newest "off → on" entry as undone; on logs
     * it as Boss's again.
     */
    fun ft3SwitchedByBoss(on: Boolean) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) { runCatching { Diag.record("autopilot", "not logged (Autopilot not started): FT3 ${if (on) "on" else "off"}") }; return@runCatching }
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    // 25 Oct (app audit): both ways logged, each dated today ([bossPaperFlip]).
                    bossPaperFlip(AutoChange.FT3_TARGET, on, today, if (on) AutoChange.ft3On() else AutoChange.paperArmOffByBoss(AutoChange.FT3_TARGET, "FT3 near-ATM morning"),
                        if (on) "switched on from its row; on paper - live only the standard way" else "switched off from its row; paper only")
                }
            }
        }
    }

    /**
     * Boss switched the Hero ₹1,000 ticket from its own row. Off: the newest "off → on" entry ([logBossTicketOn]) is closed as
     * undone, so its Undo can never switch the arm off (or on) again later; marking it undone twice changes nothing. On: logged
     * as Boss's again ("off → on", kept, undone in one tap). One lane, in the order Boss flipped it; it waits for Autopilot's
     * start like [logBossOwnOnce], so a flip right after a restart is not lost.
     */
    fun heroTicketSwitchedByBoss(on: Boolean) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) { runCatching { Diag.record("autopilot", "not logged (Autopilot not started): Hero ₹1,000 ticket ${if (on) "on" else "off"}") }; return@runCatching }
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    // 25 Oct (app audit): both ways logged, each dated today ([bossPaperFlip]).
                    bossPaperFlip(AutoChange.TICKET_TARGET, on, today, if (on) AutoChange.heroTicketOn() else AutoChange.paperArmOffByBoss(AutoChange.TICKET_TARGET, "Hero ₹1,000 ticket"),
                        if (on) "switched on from its row; paper only - it never trades on Zerodha" else "switched off from its row; paper only")
                }
            }
        }
    }

    /**
     * 15 Oct (Boss's "Both"): the paper arm [source] PASSED research and was switched on once by the update under the PAPER rule
     * ([com.optionslab.app.data.PassedArmsHost]). Logged as Boss's (kept, never Autopilot's) so the change log shows it and one
     * tap undoes it (the arm off; paper only, no PIN). Once: never logged twice. In the background, on Autopilot's own worker.
     * 16 Oct: R72 Breadth Donchian's own switch-on gives Boss's words ([choice], "Okay add it") and its [note] (with its risk line).
     */
    fun logPassedArmOn(source: String, label: String, research: String, choice: String? = null, note: String? = null) {
        if (!enabled()) return
        val c = AutoChange.passedArmOn(source, label, research, choice ?: AutoChange.passedArmChoice(source))
        logBossOwnOnce(c, note ?: "the 15 Oct update (your \"Both\"); paper only - never live")
    }

    /**
     * 16 Oct (Boss's choice: "All three"): R64's 30-60-100 lock switched on once for new trades of the three passed PAPER arms - Boss's own
     * choice (A4 passed every R64 gate but was not better than the research exits in every period): logged as Boss's (kept) so the change log and "Today's changes" show it; one tap undoes it. Paper only.
     */
    fun logPassedLockOn() {
        if (!enabled()) return
        logBossOwnOnce(AutoChange.passedLock(true), "the 16 Oct update (your choice: \"All three\"); paper only - never live")
    }

    /**
     * 16 Oct (Boss's choice after R65 - R69: "Select the best"): R66's L25, +5% locked once +25%, switched on once for new trades of the
     * three passed PAPER arms under the 30-60-100 lock - not proven (a small improvement that failed the random-exit check), so it
     * is Boss's: logged as Boss's (kept) so the change log and "Today's changes" show it; one tap undoes it. Paper only.
     */
    fun logPassedL25On() {
        if (!enabled()) return
        logBossOwnOnce(AutoChange.passedL25(true), "the 16 Oct update (your choice: \"Select the best\"); paper only - never live")
    }

    /**
     * 19 Oct (Boss: "Resolve the issues"): the same-direction cap ([com.optionslab.ira.SameDirectionCap]) on for the automatic PAPER
     * entries by this update - logged once as Boss's ask (kept) so the change log and "Today's changes" show it; one tap undoes it.
     */
    fun logSameDirCapOn(max: Int) {
        if (!enabled()) return
        logBossOwnOnce(AutoChange.sameDirCap(true, max), "the 19 Oct update (your ask: \"Resolve the issues\"); paper only - live only logs what it would refuse")
    }

    /**
     * 25 Oct (Boss: "Do whatever you think better for application", on the app audit's decisions): one of the new paper rules
     * ([AutoChange.bossPaperRule]: the account stop's paper copy, the weekly stop, the 2% cap, opposite bets, h14's capped limit,
     * Liquidity's expiry-day skip, the Hero ticket's trail from the fill) on by this update - logged once (kept) as Boss's decision,
     * so the change log and "Today's changes" show it; one tap undoes it (the risk limits' Undo takes the PIN). Paper only.
     */
    fun logBossRuleOnce(c: AutoChange.Change) {
        if (!enabled()) return
        logBossOwnOnce(c, "your decision on the app audit (25 Oct); paper only - live stays in shadow, logged only")
    }

    /** Boss flipped one of 25 Oct's paper rules on the Account guard page: logged (kept); one tap undoes it. Paper only. */
    fun logBossRule(c: AutoChange.Change) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                if (appContext == null) return@runCatching
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    _book.value = AutoChange.record(_book.value, c, AutoChange.Status.KEPT, System.currentTimeMillis(), today,
                        by = "your choice", note = "from the Account guard page; paper only").first
                    save()
                }
            }
        }
    }

    /**
     * 20 Oct, enhancement round 1 (idea 3): Liquidity's no-twin rule on for its PAPER books by this update - logged once (kept) so the
     * change log and "Today's changes" show it; one tap undoes it. Paper only.
     */
    fun logLiqNoTwinOn() {
        if (!enabled()) return
        logBossOwnOnce(AutoChange.liqNoTwin(true), "the 20 Oct update (19 Oct's triple stop); paper only - live only logs what it would skip")
    }

    /**
     * Boss flipped Liquidity's no-twin switch on the Strategy lab card ([com.optionslab.app.data.LiquidityTest.setNoTwin]): logged as
     * Boss's (kept); one tap undoes it (only the newest flip undoes). Paper only, no PIN. On Autopilot's own worker.
     */
    fun logBossNoTwin(on: Boolean) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    _book.value = AutoChange.record(_book.value, AutoChange.liqNoTwin(on), AutoChange.Status.KEPT, System.currentTimeMillis(), today,
                        by = "your choice", note = "from the Strategy lab card; paper only").first
                    save()
                }
            }
        }
    }

    /**
     * Boss flipped the same-direction cap's paper switch in Bot settings ([com.optionslab.app.data.ExposureCapHost.setPaperOn]):
     * logged as Boss's (kept); one tap undoes it (only the newest flip undoes). Paper only, no PIN. On Autopilot's own worker.
     */
    fun logSameDirCap(on: Boolean, max: Int) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) { runCatching { Diag.record("autopilot", "not logged (Autopilot not started): the same-direction cap") }; return@runCatching }
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    _book.value = AutoChange.record(_book.value, AutoChange.sameDirCap(on, max), AutoChange.Status.KEPT, System.currentTimeMillis(), today,
                        by = "your choice", note = "from Bot settings; paper only").first
                    save()
                }
            }
        }
    }

    /**
     * 16 Oct (Boss: "Update normal Donchian as you recommended, T7 supertrend agree"): R73 T7's Supertrend filter switched on once for
     * R51 Donchian 20's new paper breaks (it passed every R73 gate): logged as Boss's (kept) so the change log and "Today's changes"
     * show it; one tap undoes it. Paper only.
     */
    fun logPassedSupertrendOn() {
        if (!enabled()) return
        logBossOwnOnce(AutoChange.passedSupertrend(true), "the 16 Oct update (your ask: \"T7 supertrend agree\"); paper only - never live")
    }

    /**
     * 18 Oct (Boss: "What ever strategies are present for paper, should be present for live too ... i will switch it off, if i need
     * it"): Boss switched [label]'s own "Trade live" [on] or off ([com.optionslab.app.data.ArmLive.setLive]). Logged as Boss's (kept)
     * so the change log and "Today's changes" show it; one tap undoes it (an undo that turns it back on asks the PIN). Every flip is
     * logged (only the newest undoes). In the background, on Autopilot's own worker.
     */
    fun logArmLive(key: String, label: String, on: Boolean) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) { runCatching { Diag.record("autopilot", "not logged (Autopilot not started): $label's live switch") }; return@runCatching }
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    _book.value = AutoChange.record(_book.value, AutoChange.armLive(key, label, on), AutoChange.Status.KEPT, System.currentTimeMillis(), today,
                        by = "your choice", note = if (on) "live only in Live with AI trades on, never in the paper-only period" else "paper only from now on").first
                    save()
                }
            }
        }
    }

    /**
     * 18 Oct (Boss: "add a shadow button, so that even i move to live, until is on, it will not trade, but when i see thats making money,
     * i will make it off"): Boss switched [label]'s own Shadow [on] or off ([com.optionslab.app.data.ArmLive.setShadow]). Logged as
     * Boss's (kept); one tap undoes it (an undo that takes it out of shadow asks the PIN). In the background, on Autopilot's worker.
     */
    fun logArmShadow(key: String, label: String, on: Boolean) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) { runCatching { Diag.record("autopilot", "not logged (Autopilot not started): $label's shadow switch") }; return@runCatching }
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    _book.value = AutoChange.record(_book.value, AutoChange.armShadow(key, label, on), AutoChange.Status.KEPT, System.currentTimeMillis(), today,
                        by = "your choice", note = if (on) "records what it would trade live, sends nothing" else "real live orders in Live with AI trades and its Trade live on").first
                    save()
                }
            }
        }
    }

    /**
     * The paper arm [source] switched itself off on its forward-test bar ([why]): logged (kept, by its bar) so the change log and
     * "Today's changes" show it; one tap undoes it (back on, paper only - Boss's choice). Its "on" entry is closed as undone.
     */
    fun logPassedArmOff(source: String, label: String, why: String) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) return@runCatching
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    val target = AutoChange.PAPER_ARM + source
                    _book.value.entries.lastOrNull { it.change.kind == AutoChange.Kind.PAPER_ARM_ON && it.change.target == target && it.status == AutoChange.Status.KEPT }
                        ?.let { _book.value = AutoChange.undone(_book.value, it.id, today) }
                    _book.value = AutoChange.record(_book.value, AutoChange.passedArmOff(source, label, why), AutoChange.Status.KEPT, System.currentTimeMillis(), today,
                        by = "its forward-test bar", note = "paper only; switching it back on is your choice").first
                    save()
                }
            }
        }
    }

    /**
     * Boss switched ntfy alerts' paper trading (25 Oct) from its page: off closes the newest kept entry (so its Undo can never flip
     * it later); on records "off → on" as Boss's (kept, undone in one tap). Paper only.
     */
    fun ntfyPaperSwitchedByBoss(on: Boolean) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) return@runCatching
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    val target = AutoChange.NTFY_PAPER
                    val e = _book.value.entries.lastOrNull { it.change.kind == AutoChange.Kind.PAPER_ARM_ON && it.change.target == target }
                    if (e != null && e.status == AutoChange.Status.KEPT) _book.value = AutoChange.undone(_book.value, e.id, today)
                    if (on) _book.value = AutoChange.record(_book.value, AutoChange.ntfyPaperOn(), AutoChange.Status.KEPT,
                        System.currentTimeMillis(), today, by = "your choice", note = "switched on from Settings → ntfy alerts; paper only").first
                    save()
                }
            }
        }
    }

    /**
     * Boss switched the passed-research paper arm [source] from its row: off closes the newest kept entry of that arm (so its Undo
     * can never flip it later); on records "off → on" as Boss's (kept, undone in one tap). One lane, in the order Boss flipped it.
     * 16 Oct (R72 review): its words name the arm's own choice ([AutoChange.passedArmChoice]: R72's "Okay add it", not "Both").
     */
    fun passedArmSwitchedByBoss(source: String, label: String, research: String, on: Boolean, from: String = AutoChange.SWITCHED_OFF) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) return@runCatching
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    val target = AutoChange.PAPER_ARM + source
                    // 19 Oct: on from record only says so, so its Undo puts record only back. 25 Oct (app audit): off is logged too.
                    bossPaperFlip(target, on, today,
                        if (on) AutoChange.passedArmOn(source, label, research, AutoChange.passedArmChoice(source)).copy(from = from)
                        else AutoChange.paperArmOffByBoss(target, label).copy(from = from),
                        if (on) "switched on from its row; paper only - never live" else "switched off from its row; paper only")
                }
            }
        }
    }

    /**
     * 19 Oct: Boss set the passed-research paper arm [source] to record only from its row (from [from]: "on" or "off"): the newest
     * kept entry of that arm is closed (so its Undo can never flip it later) and "[from] → record only" recorded as Boss's (kept,
     * undone in one tap). Paper only. One lane with the on / off flips, in the order Boss picked them.
     */
    fun passedArmRecordOnlyByBoss(source: String, label: String, from: String) {
        logRecordOnly(AutoChange.passedArmRecordOnly(source, label, from, "your choice"), "picked on its row; paper only - nothing is bought", once = false)
    }

    /**
     * 19 Oct (Boss's choice: "Yes, keep only breadth donchain"): R51 Donchian 20 set to record only once by the update - its breaks are
     * kept as would-be trades, nothing bought, so it can still be compared with R72 Breadth Donchian ([r72]: its state, in words). Logged once as
     * Boss's ask (kept); one tap undoes it (back on, paper only, no PIN). Its earlier "on" entry is closed. On Autopilot's own worker.
     */
    fun logPassedArmRecordOnly(source: String, label: String, r72: String) {
        logRecordOnly(AutoChange.passedArmRecordOnly(source, label, AutoChange.ON, AutoChange.CHOICE_KEEP_BREADTH),
            "the 19 Oct update (your ask: \"Yes, keep only breadth donchain\"); paper only - $r72", once = true)
    }

    /** [c] (an arm set to record only) recorded as Boss's (kept), the arm's newest kept entry closed first; [once]: never twice. */
    private fun logRecordOnly(c: AutoChange.Change, note: String, once: Boolean) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) { runCatching { Diag.record("autopilot", "not logged (Autopilot not started): ${c.what}") }; return@runCatching }
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    if (once && _book.value.entries.any { it.change.kind == c.kind && it.change.target == c.target && it.change.reason == c.reason }) return@synchronized
                    val e = _book.value.entries.lastOrNull { it.change.kind == AutoChange.Kind.PAPER_ARM_ON && it.change.target == c.target }
                    if (e != null && e.status == AutoChange.Status.KEPT) _book.value = AutoChange.undone(_book.value, e.id, today)
                    _book.value = AutoChange.record(_book.value, c, AutoChange.Status.KEPT, System.currentTimeMillis(), today, by = "your choice", note = note).first
                    save()
                }
            }
        }
    }

    /**
     * 15 Oct (Boss's cleanup, [com.optionslab.app.data.HiddenArms]): the arm [key] ([label]) switched off ([wasOn]) or found off,
     * and hidden - once it held nothing. Logged as Boss's (kept, never Autopilot's) so the change log and "Today's changes" show
     * it, and one tap undoes it (restored; back on paper only when it was on). One lane with the restores, in their order.
     */
    fun logArmHidden(key: String, label: String, wasOn: Boolean) {
        // 21 Oct: the two MCX paper arms Boss asked to remove are hidden by the same cleanup, on that day's request.
        val late = key !in com.optionslab.engine.orb.ArmsCleanup.FIRST_15OCT
        val why = if (late) "your request (21 Oct)" else "your cleanup (15 Oct)"
        logArmHide(AutoChange.armHidden(key, label, wasOn, why),
            (if (late) "your 21 Oct request" else "your 15 Oct cleanup") + "; its code, settings and trades are kept")
    }

    /** Boss restored [key] ([label]) from the Hidden arms list: logged as his (kept), its Undo hides it again. */
    fun logArmRestored(key: String, label: String) {
        logArmHide(AutoChange.armRestored(key, label), "restored from the Hidden arms list; paper only")
    }

    private fun logArmHide(c: AutoChange.Change, note: String) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) { runCatching { Diag.record("autopilot", "not logged (Autopilot not started): ${c.what}") }; return@runCatching }
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    // The newest entry of this arm is the one that undoes ([AutoChange.superseded]); a kept older one is closed.
                    _book.value.entries.lastOrNull { it.change.kind == AutoChange.Kind.ARM_HIDE && it.change.target == c.target && it.status == AutoChange.Status.KEPT }
                        ?.let { _book.value = AutoChange.undone(_book.value, it.id, today) }
                    _book.value = AutoChange.record(_book.value, c, AutoChange.Status.KEPT, System.currentTimeMillis(), today, by = "your choice", note = note).first
                    save()
                }
            }
        }
    }

    /**
     * 15 Oct (Boss's cleanup): Liquidity 15+5's [index] books kept on with its switch, on paper ([parked]: it was parked on 9 Oct).
     * Logged as Boss's (kept) so the change log shows it; one tap parks the index again. Once: never logged twice.
     */
    fun logLiqIndexOn(index: String, parked: Boolean) {
        if (!enabled()) return
        logBossOwnOnce(AutoChange.liqIndexOn(index, parked), "your choice, 15 Oct; paper only - never cleared for Zerodha by this change")
    }

    /**
     * 16 Oct (research R63): [label]'s trend-turn exit on PAPER ([source], a [com.optionslab.engine.trend.TrendWatch.Source] key)
     * switched on once by the update - its rule passed every gate, so the PAPER rule lets it act at once. Logged as Boss's ask
     * (kept), undone in one tap (the exit off). Once: never logged twice.
     */
    fun logTrendExitOn(source: String, label: String, rule: String, research: String) {
        if (!enabled()) return
        val c = AutoChange.trendExitOn(source, label, rule, research)
        // On the same lane as the sales, so the switch-on is always logged before them; once (by its "on", not any entry).
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) { runCatching { Diag.record("autopilot", "not logged (Autopilot not started): ${c.what}") }; return@runCatching }
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    if (_book.value.entries.none { it.change.kind == c.kind && it.change.target == c.target && it.change.to == AutoChange.ON }) {
                        _book.value = AutoChange.record(_book.value, c, AutoChange.Status.KEPT, System.currentTimeMillis(), today, by = "your choice",
                            note = "your ask, 16 Oct (research R63); paper only - never live").first
                        save()
                    }
                }
            }
        }
    }

    /** One paper leg of [label] sold on the trend turn ([line]): logged (kept); its Undo switches the trend exit off. */
    fun logTrendExitSold(source: String, label: String, line: String) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) { runCatching { Diag.record("autopilot", "not logged (Autopilot not started): $line") }; return@runCatching }
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    _book.value = AutoChange.record(_book.value, AutoChange.trendExitSold(source, label, line), AutoChange.Status.KEPT,
                        System.currentTimeMillis(), today, by = "the Trend watch", note = "paper only; research R63 T3").first
                    save()
                }
            }
        }
    }

    /**
     * Boss switched [label]'s trend-turn exit from the Trend watch's dialog: off closes the newest kept entry of it (so an
     * older Undo can never flip it later); on records "off → on" as Boss's (kept, undone in one tap).
     */
    fun trendExitSwitchedByBoss(source: String, label: String, on: Boolean) {
        if (!enabled()) return
        scope.launch(trailLane) {
            runCatching {
                val waited = kotlinx.coroutines.withTimeoutOrNull(INIT_WAIT_MS) { while (appContext == null) kotlinx.coroutines.delay(100) }
                if (waited == null) return@runCatching
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    val target = AutoChange.TREND_TARGET + source
                    val e = _book.value.entries.lastOrNull { it.change.kind == AutoChange.Kind.TREND_EXIT && it.change.target == target }
                    if (e != null && e.status == AutoChange.Status.KEPT) _book.value = AutoChange.undone(_book.value, e.id, today)
                    if (on) _book.value = AutoChange.record(_book.value, AutoChange.trendExitOn(source, label, "T3",
                        com.optionslab.engine.trend.TrendWatch.RECORD), AutoChange.Status.KEPT, System.currentTimeMillis(), today,
                        by = "your choice", note = "switched on in the Trend watch; paper only - never live").first
                    save()
                }
            }
        }
    }

    /** One lane for [logBossTrail]: the switch's flips are logged in the order they were made. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val trailLane = Dispatchers.IO.limitedParallelism(1)

    /**
     * Boss switched their own Rs 500 give-back trail on the Liquidity PAPER books [on] or off ([com.optionslab.app.data.LiquidityTest.setTrail],
     * the Strategy lab card): logged as Boss's (kept, not on probation, never counted as Autopilot's change) so "Today's
     * changes" shows it and one tap undoes it (the switch flipped back, on paper; only the newest flip undoes). Called only on a
     * real change. In the background, on Autopilot's own worker: the switch never waits for the log's disk.
     */
    fun logBossTrail(on: Boolean) {
        if (!enabled()) return
        val c = AutoChange.liqTrail(on)
        val note = if (on) "your choice; live trades never use it" else "your choice"
        // One lane, in the order Boss flipped it: two quick taps are logged as they were made.
        scope.launch(trailLane) {
            runCatching {
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                // Every call is a real change ([com.optionslab.app.data.LiquidityTest.setTrail] logs only those): each is kept, and the
                // older flips are no longer undoable ([AutoChange.superseded]), so the newest entry always matches the switch.
                synchronized(lock) {
                    _book.value = AutoChange.record(_book.value, c, AutoChange.Status.KEPT, System.currentTimeMillis(), today, by = "your choice", note = note).first
                    save()
                }
            }
        }
    }

    /**
     * 22 Oct: Boss switched their own breakeven at +10% on the Liquidity PAPER books [on] or off
     * ([com.optionslab.app.data.LiquidityTest.setBreakeven], the Strategy lab card): logged as Boss's, exactly as [logBossTrail]
     * (kept, never counted as Autopilot's change, one tap undoes the newest flip), in the background on the same lane.
     */
    fun logBossBreakeven(on: Boolean) {
        if (!enabled()) return
        val c = AutoChange.liqBreakeven(on)
        val note = if (on) "your choice; live trades never use it" else "your choice"
        scope.launch(trailLane) {
            runCatching {
                ensureLoaded()
                val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
                synchronized(lock) {
                    _book.value = AutoChange.record(_book.value, c, AutoChange.Status.KEPT, System.currentTimeMillis(), today, by = "your choice", note = note).first
                    save()
                }
            }
        }
    }

    // ---- telling -------------------------------------------------------------------------------------------------------------

    private fun tellOne(c: AutoChange.Change, said: String) {
        runCatching {
            SmartWorkers.found(WHO, Findings.Kind.OTHER, Findings.ALL, words = "Autopilot: ${c.what} ${c.from} → ${c.to} (${c.scope.words}, " +
                "${AutoChange.riskOf(c).words}) - ${c.reason}", strength = 50, ttlMs = 24 * 3_600_000L)
        }
        runCatching { IraActivity.add("Autopilot: ${c.what} ${c.from} → ${c.to}. $said") }
    }

    /** The day's notice as last posted (its day and words). */
    @Volatile private var notified: Pair<LocalDate, String>? = null

    /**
     * One notification a day: "Autopilot made 3 changes (2 paper, 1 live risk-lowering). Tap to review." Posted again (the
     * same id: it replaces the earlier one) when the day's summary changed - the evening's changes after a midday notice
     * are told too (review G5-7).
     */
    private fun notifyDay(today: LocalDate) {
        val text = AutoChange.notice(AutoChange.summary(_book.value, today)) ?: return
        if (notified == today to text) return
        val ctx = appContext ?: return
        notified = today to text
        runCatching { com.optionslab.app.work.Notifier.post(ctx, 5180, com.optionslab.app.work.Notifier.IRA, "Autopilot learning", text, "almanac") }
    }

    // ---- answers -------------------------------------------------------------------------------------------------------------

    /** Jarvis's answer to [a] (OFF, SHADOW and RAISE by voice: [voiceSet]; UNDO: [offerUndoLast]). */
    fun answer(a: AutoChange.Ask): String {
        ensureLoaded()
        return when (a.kind) {
            AutoChange.AskKind.TODAY -> AutoChange.sayToday(_book.value, Market.today())
            AutoChange.AskKind.WHY -> AutoChange.sayWhy(_book.value, a.subject)
            AutoChange.AskKind.HOW -> AutoChange.sayHow(_book.value, _mode.value)
            AutoChange.AskKind.UNDO -> offerUndoLast()
            AutoChange.AskKind.OFF -> voiceSet(AutoChange.Mode.OFF)
            AutoChange.AskKind.RAISE -> AutoChange.VOICE_RAISE
            // "Set autopilot to shadow only": lowers from SAFE; from OFF it is a raise and refused ([voiceSet]).
            AutoChange.AskKind.SHADOW -> voiceSet(AutoChange.Mode.SHADOW_ONLY)
            AutoChange.AskKind.SURE -> {
                runCatching { JarvisLearning.ensureLoaded() }
                AutoGate.sayHowSure(JarvisLearning.book.value, _book.value, a.subject ?: "that")
            }
            AutoChange.AskKind.EXPERIMENTS -> Experiments.sayRunning(_book.value)
        }
    }

    /** Copy diagnostics: the setting, today's changes, probation and reverts, the learned knobs (no secrets). */
    fun diagLine(): String {
        val s = AutoChange.summary(_book.value, runCatching { Market.today() }.getOrElse { LocalDate.now() })
        val brain = runCatching {
            SmartWorkers.brain.value.regimes["NIFTY"]?.let { r -> " · brain (shadow, busy at ${AutoChange.fmt(knob("brain.busy_z"))} z): " +
                com.optionslab.ira.MarketBrain.stateWith(r, knob("brain.busy_z")).words }
        }.getOrNull() ?: ""
        return "Autopilot learning: ${_mode.value.words}; today ${s.today}, on probation ${s.probation}, kept ${s.kept}, reverted ${s.reverted}" +
            "; trials ${_book.value.trials}, experiments running ${Experiments.running(_book.value).size}" +
            (if (_knobs.value.isNotEmpty()) "; knobs " + _knobs.value.toSortedMap().entries.joinToString(", ") { "${it.key}=${AutoChange.fmt(it.value)}" } else "") + brain
    }

    /**
     * "Erase everything" (26 Oct): the change timeline, the learned knobs and the day's notice dropped from memory, Boss's
     * setting back to its fresh-phone value (SAFE: its key goes with [SecurePrefs.wipe]), and the folder deleted. Under the
     * log's lock (a save in progress ends first; one queued after writes nothing). Never reads the settings; safe before [init].
     */
    fun wipe() {
        synchronized(lock) {
            _book.value = AutoChange.Book(); _knobs.value = emptyMap()
            _mode.value = AutoChange.Mode.SAFE; modeLoaded = true
            if (appContext != null) loaded = true
            notified = null
            dir()?.let { runCatching { it.deleteRecursively() } }
        }
    }

    /** Memory only, for the erase's tests. */
    internal fun holdsForErase(): Boolean = _book.value != AutoChange.Book() || _knobs.value.isNotEmpty() || _mode.value != AutoChange.Mode.SAFE || notified != null

    /** The timeline's folder (none before [init]). */
    internal fun filesForErase(): List<File> = listOfNotNull(dir())

    /** TEST ONLY: nothing kept, SAFE, nothing learned (in memory). */
    internal fun resetForTest() {
        synchronized(lock) { _book.value = AutoChange.Book(); _knobs.value = emptyMap() }
        _mode.value = AutoChange.Mode.SAFE
        loaded = true; modeLoaded = true; running.set(false); notified = null; testInputs = null; testLive = null; testReplay = null; testModeRead = null
    }

    /** TEST ONLY: as at a cold start - nothing read yet (the log and knobs kept in memory are dropped). */
    internal fun unloadForTest() {
        synchronized(lock) { _book.value = AutoChange.Book(); _knobs.value = emptyMap() }
        _mode.value = AutoChange.Mode.SAFE
        loaded = false; modeLoaded = false
    }

    /** TEST ONLY: [b] as the log. */
    internal fun setForTest(b: AutoChange.Book) { synchronized(lock) { _book.value = b }; loaded = true; modeLoaded = true }
}
