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
    @Volatile private var running = false

    /** TEST ONLY: the inputs instead of the agents' stores; and whether the live account is on. */
    @Volatile internal var testInputs: ((LocalDate) -> AutoChange.Inputs)? = null
    @Volatile internal var testLive: Boolean? = null
    /** TEST ONLY: the stored history a pre-flight replays and experiments are judged on (default with [testInputs]: its records). */
    @Volatile internal var testReplay: ((LocalDate) -> Preflight.Data)? = null

    fun enabled(): Boolean = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    fun init(context: Context) {
        appContext = context.applicationContext
        if (enabled()) scope.launch { runCatching { ensureLoaded() } }
    }

    private fun dir(): File? = appContext?.let { File(it.noBackupFilesDir, DIR) }

    /** The log, the knobs and the setting read once (a worker thread). */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val d = dir()
            val text = runCatching { d?.let { File(it, LOG) }?.takeIf { it.isFile }?.readText() }.getOrNull()
            if (_book.value == AutoChange.Book()) _book.value = AutoChange.decode(text)
            val kn = runCatching { d?.let { File(it, KNOBS) }?.takeIf { it.isFile }?.readLines() }.getOrNull().orEmpty()
                .mapNotNull { l -> l.split('=').takeIf { it.size == 2 }?.let { (k, v) -> v.toDoubleOrNull()?.let { x -> k to x } } }
                .filter { (k, v) -> AutoChange.knob(k)?.let { v in it.min..it.max } == true }.toMap()
            if (_knobs.value.isEmpty()) _knobs.value = kn
            val m = runCatching { SecurePrefs.getString(K_MODE) }.getOrNull()
            _mode.value = AutoChange.Mode.entries.firstOrNull { it.name == m } ?: AutoChange.Mode.SAFE
            loaded = true
        }
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
        val b = _book.value
        return when {
            minute >= NIGHT && b.lastRun != today && (JarvisLearning.book.value.lastPass == today || testInputs != null) -> { run(today, healthOnly = false); true }
            minute in MIDDAY until NIGHT && b.lastMidday != today -> { run(today, healthOnly = true); true }
            else -> false
        }
    }

    /** One pass (and by tests): gather, plan, apply, ask, review the probations, tell. */
    internal suspend fun run(today: LocalDate, healthOnly: Boolean, nowMs: Long = System.currentTimeMillis()): AutoChange.Plan? {
        if (running) return null
        running = true
        try {
            synchronized(lock) { _book.value = if (healthOnly) _book.value.copy(lastMidday = today) else _book.value.copy(lastRun = today) }
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
                // S14: learning frozen - recorded as skipped, not applied.
                if (LearningFreeze.holds()) { synchronized(lock) { _book.value = AutoChange.record(_book.value, g.change, AutoChange.Status.SKIPPED, nowMs, today, note = LearningFreeze.NOTE, q = g.q).first }; continue }
                val c = g.change
                val said = runCatching { apply(c, pinConfirmed = false) }.getOrNull() ?: continue
                synchronized(lock) {
                    _book.value = AutoChange.record(_book.value, c, AutoChange.Status.PROBATION, nowMs, today, q = g.q, preflight = g.preflight?.words().orEmpty()).first
                }
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
            running = false
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
        return AutoChange.Inputs(today, lessons, TradeManagerHost.records.value, policy,
            sb.tuned.mapValues { it.value.muted }, sb.frozen.mapValues { it.value.muted }, SmartWorkers.reviews.value,
            s.parked, s.paused, FlowGate.modes.value, _knobs.value, own)
    }

    // ---- applying one change through its agent's own setter ------------------------------------------------------------------

    /**
     * [c] applied through its agent's setter: what was said, or null when the agent refused or nothing changed. A change that
     * raises live risk goes only with [pinConfirmed]. Never an order, a size, a stop or a safety setting.
     */
    internal fun apply(c: AutoChange.Change, pinConfirmed: Boolean): String? {
        if (AutoChange.never(c)) return null
        val raisesLive = c.scope == AutoChange.Scope.LIVE && AutoChange.riskOf(c) == AutoChange.Risk.RAISE
        if (raisesLive && !pinConfirmed) return null
        val parts = c.target.split(':')
        return when (c.kind) {
            AutoChange.Kind.SPECIALIST_MUTE -> {
                if (parts.size != 3 || parts[0] != "spec") return null
                if (c.to == AutoChange.MUTED) ManagerSpecialists.mute(parts[1], parts[2], by = WHO)
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
                // Only toward SHADOW or OFF (CONFIRM takes Boss's PIN in the Order flow card).
                if (to == OrderFlow.Mode.CONFIRM) return null
                FlowGate.set(key, to)
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

    /** Where a knob acts at once: the safety-net schedule and self-healing's backoff (the others are read where they are used). */
    private fun handOver(key: String, v: Double) {
        when (key) {
            "wake.net_flat_sec" -> runCatching { SmartWorkers.setNetFlatSec(v.toInt()) }
            "heal.relay_open_ms" -> runCatching { SmartWorkers.health.tune(SelfHeal.Dep.RELAY, v.toLong()) }
            "heal.candles_open_ms" -> runCatching { SmartWorkers.health.tune(SelfHeal.Dep.CANDLES, v.toLong()) }
        }
    }

    /** The knob's value now (its default when not learned). Memory only. */
    fun knob(key: String): Double = _knobs.value[key] ?: AutoChange.knob(key)?.default ?: Double.NaN

    // ---- asking --------------------------------------------------------------------------------------------------------------

    /** Where a live raise is confirmed with the PIN. */
    const val WHERE = "Home → Autopilot → Change log"

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
        val said = runCatching { apply(e.change, pinConfirmed) }.getOrNull() ?: return "Nothing changed: ${e.change.what} could not be changed just now."
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
    fun undoNeedsPin(id: String): Boolean = AutoChange.undo(_book.value, id)?.needsPin == true

    /** Boss's Undo on [id] (the change log): the inverse through the same setter; a live raise only with [pinConfirmed]. */
    fun undo(id: String, pinConfirmed: Boolean = false): String {
        ensureLoaded()
        val u = AutoChange.undo(_book.value, id) ?: return "That change can't be undone now (it was already undone or reverted)."
        if (u.needsPin && !pinConfirmed) return PIN_UNDO
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
        val pin = AutoChange.undoNeedsPin(e.change)
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
                runCatching { apply(inv, pinConfirmed = false) }
                j.entry.change.lessonId?.let { runCatching { JarvisLearning.autoStatus(it, "reverted") } }
                runCatching { SmartWorkers.found(WHO, Findings.Kind.OTHER, Findings.ALL, words = "Autopilot reverted ${j.entry.change.what}: ${j.why}", strength = 55,
                    ttlMs = 24 * 3_600_000L) }
                runCatching { Diag.record("autopilot", "reverted ${j.entry.change.what}") }
            }
            AutoChange.Verdict.ASK_REVERT -> {
                if (j.inverse == null) continue
                j.entry.change.lessonId?.let { runCatching { JarvisLearning.autoStatus(it, "reverted") } }
                val id = j.entry.id
                IraHub.offer("autopilot: revert ${j.entry.change.what}", "Boss, revert an Autopilot change?",
                    "Boss, ${j.entry.change.what} did worse since I changed it (${j.why}). Putting it back adds live risk: confirm here, then revert it with your PIN in $WHERE.",
                    suspend { "Revert it with your PIN in $WHERE (it adds live risk)." }, addsRisk = true, alwaysAsk = true,
                    declined = { synchronized(lock) { _book.value = AutoChange.declined(_book.value, id, Market.today()) }; saveSoon() })
            }
            AutoChange.Verdict.WAIT -> {}
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

    /** Boss's revert of a live probation with his PIN (the change log). */
    fun revertWithPin(id: String): String {
        val e = AutoChange.entry(_book.value, id) ?: return "That change is gone."
        if (e.status != AutoChange.Status.REVERT_ASKED) return "That change is not waiting for a revert."
        val inv = AutoChange.inverse(e.change, "reverted on your PIN")
        val said = runCatching { apply(inv, pinConfirmed = true) }.getOrNull() ?: return "Nothing changed: it could not be put back just now."
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

    // ---- telling -------------------------------------------------------------------------------------------------------------

    private fun tellOne(c: AutoChange.Change, said: String) {
        runCatching {
            SmartWorkers.found(WHO, Findings.Kind.OTHER, Findings.ALL, words = "Autopilot: ${c.what} ${c.from} → ${c.to} (${c.scope.words}, " +
                "${AutoChange.riskOf(c).words}) - ${c.reason}", strength = 50, ttlMs = 24 * 3_600_000L)
        }
        runCatching { IraActivity.add("Autopilot: ${c.what} ${c.from} → ${c.to}. $said") }
    }

    @Volatile private var notifiedOn: LocalDate? = null

    /** One notification a day: "Autopilot made 3 changes (2 paper, 1 live risk-lowering). Tap to review." */
    private fun notifyDay(today: LocalDate) {
        if (notifiedOn == today) return
        val text = AutoChange.notice(AutoChange.summary(_book.value, today)) ?: return
        val ctx = appContext ?: return
        notifiedOn = today
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
        loaded = true; running = false; notifiedOn = null; testInputs = null; testLive = null; testReplay = null
    }

    /** TEST ONLY: [b] as the log. */
    internal fun setForTest(b: AutoChange.Book) { synchronized(lock) { _book.value = b }; loaded = true }
}
