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
        if (enabled()) scope.launch { com.optionslab.app.data.PositionsFirst.awaitPositions(); runCatching { ensureLoaded() } }
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
                if (c.to != AutoChange.ON || source != com.optionslab.engine.mcx.McxMorningPark.SOURCE) return null
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
            AutoChange.Kind.LIQ_TRAIL -> {
                if (c.target != AutoChange.TRAIL_TARGET || (c.to != AutoChange.ON && c.to != AutoChange.SWITCHED_OFF)) return null
                com.optionslab.app.data.LiquidityTest.setTrail(c.to == AutoChange.ON, log = false)
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
