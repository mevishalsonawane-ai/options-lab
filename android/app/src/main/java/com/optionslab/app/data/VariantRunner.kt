package com.optionslab.app.data

import android.content.Context
import com.optionslab.ira.DiscoveryStats
import com.optionslab.ira.Findings
import com.optionslab.ira.LabLessons
import com.optionslab.ira.LabTalk
import com.optionslab.ira.ShadowSim
import com.optionslab.ira.StrategyForge
import com.optionslab.ira.VariantLab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The strategy lab's host (11 Oct, Boss: "make the strategies self-improve and find new strategies"): the shadow variants
 * ([VariantLab]) and the forge ([StrategyForge]) on this phone.
 *
 *  - The arms tell it they ran today ([saw]: one map write, memory only - no settings read, no I/O, no order path), so the lab
 *    knows each strategy's account (paper / live) that day.
 *  - After F&O's close, once the day's minutes are stored (the harvest keeps the index and its options), [settleIfDue] follows
 *    every variant and every forward candidate over that day as VIRTUAL trades - no order, not even paper; never in market
 *    hours, so nothing is added per tick.
 *  - A paper strategy's winning variant is applied to the lab's paper rules by itself, logged with undo, and posted on the
 *    findings bus as "variant ready"; a live strategy's is put to Boss ([com.optionslab.app.ira.IraHub.offer]) and applied
 *    only with his PIN (Home → Strategy lab). A forge survivor that keeps passing is put to Boss as "start it on paper";
 *    the forge never proposes live.
 *  - The nightly forge ([forgeIfDue], from [com.optionslab.app.work.DiscoveryWorker]) runs only while charging and idle or
 *    after 18:00, within a minute of work, and stops when the worker is stopped.
 *
 * Kept in noBackupFilesDir/strategylab (never the settings vault, never a backup). Nothing here places, changes or cancels an
 * order, or touches the PIN, Live, AI-live, a guard or lots. Not in IraGoldAlgo.
 */
object VariantRunner {
    private const val DIR = "strategylab"
    private const val VARIANTS = "variants.txt"
    private const val FORGE = "forge.txt"
    /** Stored days the pre-check of a new variant reads; days the forge backtests on per index. */
    const val PRECHECK_DAYS = 40
    const val FORGE_DAYS = 100
    /** The indices the lab reads from the store (what the store keeps minutes of, with their options). */
    val UNDERLYINGS = listOf("BANKNIFTY", "NIFTY")
    /** F&O's close and the day's settle (after it): never in market hours. */
    const val SETTLE_FROM = 15 * 60 + 45
    const val WHO = "Strategy lab"

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _variants = MutableStateFlow(VariantLab.Book())
    val variants: StateFlow<VariantLab.Book> = _variants
    private val _forge = MutableStateFlow(StrategyForge.Book())
    val forge: StateFlow<StrategyForge.Book> = _forge

    @Volatile private var loaded = false
    @Volatile private var settling = false

    /** Each arm's last day seen running, and whether live (the arms' hook writes it; memory only). */
    private val seen = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /** TEST ONLY: the stored days instead of the store: (index, the last day, how many) -> its days, oldest first. */
    @Volatile internal var testDays: ((String, LocalDate, Int) -> List<ShadowSim.Day>)? = null

    fun enabled(): Boolean = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!enabled()) return
        scope.launch { runCatching { ensureLoaded() } }
        runCatching { com.optionslab.app.work.DiscoveryWorker.schedule(context.applicationContext) }
    }

    private fun dir(): File? = appContext?.let { File(it.noBackupFilesDir, DIR) }

    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val d = dir()
            if (_variants.value == VariantLab.Book()) _variants.value = VariantLab.decode(runCatching { d?.let { File(it, VARIANTS) }?.takeIf { it.isFile }?.readText() }.getOrNull())
            if (_forge.value == StrategyForge.Book()) _forge.value = StrategyForge.decode(runCatching { d?.let { File(it, FORGE) }?.takeIf { it.isFile }?.readText() }.getOrNull())
            loaded = true
        }
    }

    private fun save() {
        val d = dir() ?: return
        runCatching {
            d.mkdirs()
            write(File(d, VARIANTS), VariantLab.encode(_variants.value))
            write(File(d, FORGE), StrategyForge.encode(_forge.value))
        }
    }

    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun saveSoon() { scope.launch { synchronized(lock) { save() } } }

    // ---- the arms' hook ---------------------------------------------------------------------------------------------------

    /**
     * An arm ran a pass while armed ([key]: its source; [live]: armed for the live account). Cheap: one map write, no read of
     * the settings, no I/O, nothing on the order path. Never throws.
     */
    fun saw(key: String, live: Boolean) {
        if (!enabled()) return
        runCatching { seen[lab(key)] = Market.today().toEpochDay() to live }
    }

    /** An arm's source as the lab's strategy key (Liquidity's books are one strategy; MCX's arms one family). */
    internal fun lab(key: String): String = when {
        key.startsWith("liquidity") -> "liquidity"
        key.startsWith("mcx") -> "mcx"
        else -> key
    }

    /** How each strategy ran on [day] (from [saw]); OFF when not seen that day. */
    fun runsOn(day: LocalDate): Map<String, VariantLab.Run> = VariantLab.STRATEGIES.associate { s ->
        s.key to (seen[s.key]?.takeIf { it.first == day.toEpochDay() }?.let { if (it.second) VariantLab.Run.LIVE else VariantLab.Run.PAPER } ?: VariantLab.Run.OFF)
    }

    /** The running strategies the lab does not tune today, with why (the card). */
    fun notTuned(day: LocalDate): List<String> = VariantLab.NOT_TUNED.filterKeys { k -> seen[k]?.first == day.toEpochDay() }.values.toList()

    // ---- the data ---------------------------------------------------------------------------------------------------------

    /** [u]'s last [n] stored days up to [last] (oldest first), with the busy read and India VIX where kept. Off the main thread. */
    internal fun days(u: String, last: LocalDate, n: Int): List<ShadowSim.Day> {
        testDays?.let { return it(u, last, n) }
        val wanted = runCatching { Store.barDays(u) }.getOrDefault(emptyList()).filter { !it.isAfter(last) }.takeLast(n + 21)
        if (wanted.isEmpty()) return emptyList()
        val from = wanted.first()
        val vix = HashMap<LocalDate, ShadowSim.Series>()
        runCatching {
            for (s in Store.barSessions("INDIAVIX")) {
                if (s.day.isBefore(from) || s.day.isAfter(last)) continue
                val ix = s.index ?: continue
                vix[s.day] = ShadowSim.Series(ix.minutes, ix.open ?: ix.close, ix.high ?: ix.close, ix.low ?: ix.close, ix.close)
            }
        }
        val out = ArrayList<ShadowSim.Day>()
        var prev: com.optionslab.engine.Session? = null
        for (s in Store.barSessions(u)) {
            if (s.day.isAfter(last)) continue
            if (s.day.isBefore(from)) { prev = s; continue }
            ShadowSim.fromSession(s, u, prev?.takeIf { it.day.isBefore(s.day) }, vix[s.day])?.let { out += it }
            prev = s
        }
        // Busy or quiet needs the 20 sessions before: read with them, kept without them.
        return ShadowSim.markBusy(out.sortedBy { it.date }.distinctBy { it.date }).takeLast(n)
    }

    // ---- the day's settle -------------------------------------------------------------------------------------------------

    /** What a settle did (for the tests and the diagnostics). */
    data class Settled(val day: LocalDate?, val said: String)

    /**
     * Once a trading day after [SETTLE_FROM], when the day's minutes are stored: every variant and every forward candidate
     * over that day. Never in market hours; never an order. Returns what it did.
     */
    suspend fun settleIfDue(now: java.time.ZonedDateTime = Market.now()): Settled {
        if (!enabled()) return Settled(null, "off")
        val today = now.toLocalDate()
        val minute = now.hour * 60 + now.minute
        // The newest trading day that has closed: today after the settle time, else the one before.
        var d = if (Market.isTradingDay(today) && minute >= SETTLE_FROM) today else today.minusDays(1)
        var guard = 0
        while (!Market.isTradingDay(d) && guard++ < 10) d = d.minusDays(1)
        if (Market.isTradingDay(today) && minute in (9 * 60) until SETTLE_FROM) return Settled(null, "market hours")
        if (settling) return Settled(null, "busy")
        withContext(Dispatchers.IO) { ensureLoaded() }
        val vb = _variants.value; val fb = _forge.value
        if ((vb.lastDay?.let { !d.isAfter(it) } == true) && (fb.lastForward?.let { !d.isAfter(it) } == true)) return Settled(null, "already settled")
        settling = true
        try {
            val data = withContext(Dispatchers.IO) { UNDERLYINGS.associateWith { u -> runCatching { days(u, d, PRECHECK_DAYS + 1) }.getOrDefault(emptyList()) } }
            val todays = data.mapNotNull { (u, ds) -> ds.lastOrNull()?.takeIf { it.date == d }?.let { u to it } }.toMap()
            if (todays.isEmpty()) return Settled(null, "waiting for $d's stored minutes")
            val history = data.values.flatMap { ds -> ds.filter { it.date.isBefore(d) } }
            val runs = runsOn(d)
            val (vp, fp) = withContext(Dispatchers.Default) {
                synchronized(lock) {
                    val v = VariantLab.day(_variants.value, d, runs, todays, history, DiscoveryStats.Rng(DiscoveryStats.seedOf(d, 3)))
                    val f = StrategyForge.forwardDay(_forge.value, d, todays)
                    _variants.value = v.book; _forge.value = f.book
                    v to f
                }
            }
            withContext(Dispatchers.IO) { synchronized(lock) { save() } }
            runCatching { tell(vp.events, fp.events) }
            val said = "settled $d: ${vp.book.alive().size} variants shadowing, ${fp.book.forward.size} ideas in forward test, " +
                "${vp.events.size + fp.events.size} decisions"
            runCatching { Diag.record("strategylab", said) }
            return Settled(d, said)
        } finally {
            settling = false
        }
    }

    /** The day's decisions: applied ones posted as "variant ready", live ones and forge survivors asked, retirements as lessons. */
    private fun tell(vs: List<VariantLab.Event>, fs: List<StrategyForge.Event>) {
        for (e in vs) when (e) {
            is VariantLab.Event.Applied -> {
                val label = VariantLab.strategy(e.change.strategy)?.label ?: e.change.strategy
                val v = _variants.value.variants.firstOrNull { it.id == e.change.variant }
                post("$WHO · $label", "variant ready: ${v?.moved() ?: "new rules"} applied on paper by itself - ${e.change.why}")
                runCatching { com.optionslab.app.ira.IraActivity.add("Strategy lab: $label's paper rules changed (${v?.moved()}); undo on Home → Strategy lab.") }
            }
            is VariantLab.Event.Proposed -> offerLive(e.change)
            is VariantLab.Event.Retired -> {}
            is VariantLab.Event.Waiting -> {}
        }
        for (e in fs) when (e) {
            is StrategyForge.Event.Proposed -> offerPaper(e.record)
            is StrategyForge.Event.Retired -> post("$WHO · forge", "lesson: ${e.record.c.name} ${e.record.why}")
        }
    }

    private fun post(who: String, words: String) {
        runCatching { SmartWorkers.found(who, Findings.Kind.OTHER, Findings.ALL, words = words.take(300), strength = 50, ttlMs = 24 * 3_600_000L) }
    }

    /** Where the PIN applies a live change. */
    const val WHERE = "Home → Strategy lab"

    private fun offerLive(c: VariantLab.Change) {
        val label = VariantLab.strategy(c.strategy)?.label ?: c.strategy
        val v = _variants.value.variants.firstOrNull { it.id == c.variant }
        com.optionslab.app.ira.IraHub.offer("strategy lab: $label", "Boss, a better $label?",
            "Boss, a variant of $label (${v?.moved() ?: "new rules"}) beat its current rules: ${c.why}. It trades live, so it changes only with your PIN in $WHERE.",
            suspend { "Apply it with your PIN in $WHERE (it changes a live strategy)." }, addsRisk = true, alwaysAsk = true,
            declined = { synchronized(lock) { _variants.value = VariantLab.decline(_variants.value, c.id) }; saveSoon() })
    }

    private fun offerPaper(r: StrategyForge.Record) {
        com.optionslab.app.ira.IraHub.offer("strategy lab: start ${r.c.id} on paper", "Boss, a new strategy kept passing",
            "Boss, ${r.c.name} ${r.why}. Start it on paper? Paper only: it keeps a virtual paper record, never a live order.",
            suspend { approvePaper(r.c.id) }, alwaysAsk = true, declined = { declinePaper(r.c.id) })
    }

    // ---- the nightly forge ------------------------------------------------------------------------------------------------

    /**
     * The night's forge (the worker decides it may run: charging and idle, or after 18:00): up to [StrategyForge.PER_NIGHT]
     * ideas within [StrategyForge.BUDGET_MS], stopped when [stopped] says so. Once a day.
     */
    suspend fun forgeIfDue(today: LocalDate = Market.today(), stopped: () -> Boolean = { false }): StrategyForge.Night? {
        if (!enabled()) return null
        withContext(Dispatchers.IO) { ensureLoaded() }
        if (_forge.value.lastNight == today) return null
        val hist = withContext(Dispatchers.IO) { UNDERLYINGS.associateWith { u -> runCatching { days(u, today, FORGE_DAYS) }.getOrDefault(emptyList()) } }
        if (hist.values.all { it.size < 30 }) return null
        val t0 = System.nanoTime()
        val night = withContext(Dispatchers.Default) {
            StrategyForge.night(_forge.value, today, hist, { (System.nanoTime() - t0) / 1_000_000 }, StrategyForge.BUDGET_MS, stopped)
        }
        synchronized(lock) {
            // Boss's answers made meanwhile are kept: only the night's own records and counters move.
            val now = _forge.value
            _forge.value = night.book.copy(records = night.book.records.map { r -> now.find(r.c.id)?.takeIf { it.stage != r.stage && it.stage in BOSS } ?: r })
        }
        withContext(Dispatchers.IO) { synchronized(lock) { save() } }
        runCatching { Diag.record("strategylab", "forge $today: ${night.made.size} ideas in ${night.ms} ms" + if (night.stoppedEarly) " (stopped early)" else "") }
        return night
    }

    private val BOSS = setOf(StrategyForge.Stage.PAPER, StrategyForge.Stage.DECLINED)

    // ---- Boss's hand -------------------------------------------------------------------------------------------------------

    /** Undo an applied paper change (no PIN: paper only). */
    fun undo(changeId: String): String {
        ensureLoaded()
        val b = synchronized(lock) { VariantLab.undo(_variants.value, changeId)?.also { _variants.value = it } } ?: return "That change can't be undone now."
        saveSoon()
        val c = b.changes.first { it.id == changeId }
        return "Undone: ${VariantLab.strategy(c.strategy)?.label ?: c.strategy}'s lab rules are back to before."
    }

    const val PIN_NEEDED = "It changes a live strategy: confirm with your PIN. Nothing changed."

    /** Apply a live proposal: ONLY with [pinConfirmed] (the card asks for the PIN first). */
    fun acceptLive(changeId: String, pinConfirmed: Boolean): String {
        if (!pinConfirmed) return PIN_NEEDED
        ensureLoaded()
        synchronized(lock) { VariantLab.accept(_variants.value, changeId, true)?.also { _variants.value = it } } ?: return "That proposal is no longer waiting."
        saveSoon()
        runCatching { Diag.record("strategylab", "live proposal $changeId applied with the PIN") }
        return "Applied with your PIN: the lab's live rules moved to the variant."
    }

    fun declineLive(changeId: String): String {
        synchronized(lock) { _variants.value = VariantLab.decline(_variants.value, changeId) }
        saveSoon()
        return "Declined: the current rules stay."
    }

    /** Boss's yes to "start it on paper": paper only, never live. */
    fun approvePaper(id: String): String {
        ensureLoaded()
        val day = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        synchronized(lock) { _forge.value = StrategyForge.approve(_forge.value, id, day) }
        saveSoon()
        return "$id is approved for paper: it keeps a virtual paper record from the next session. Live stays yours, with the PIN, after a long paper record."
    }

    fun declinePaper(id: String): String {
        val day = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        synchronized(lock) { _forge.value = StrategyForge.declineProposal(_forge.value, id, day) }
        saveSoon()
        return "Declined: $id stays out."
    }

    // ---- Jarvis -------------------------------------------------------------------------------------------------------------

    fun answer(a: LabTalk.Ask): String { ensureLoaded(); return LabTalk.answer(a, _forge.value, _variants.value) }

    fun lessons(): List<LabLessons.Lesson> = runCatching { LabLessons.lessons(_forge.value, _variants.value) }.getOrDefault(emptyList())

    /**
     * Jarvis's nightly pass reads what the lab taught ([com.optionslab.app.ira.JarvisLearning]): the strongest lessons, posted
     * once on the findings bus (as his other lessons are), and returned. Words only.
     */
    fun learnedForJarvis(): List<String> {
        if (!enabled()) return emptyList()
        ensureLoaded()
        val notes = LabLessons.notes(lessons(), 4)
        if (notes.isNotEmpty()) post("$WHO · lessons", "what the new strategies taught: " + notes.joinToString(" "))
        return notes
    }

    /** The lab's notes for the model (memory only). */
    fun modelNotes(): List<String> = if (!enabled() || !loaded) emptyList() else LabLessons.notes(lessons(), 3)

    fun diagLine(): String {
        val f = StrategyForge.counts(_forge.value)
        return "Strategy lab: ${_variants.value.alive().size} variants shadowing (${_variants.value.tried} tried), forge ${f.tried} ideas tried, " +
            "${f.passed} passed the backtest, ${f.forward} in forward test, ${f.proposed} proposed, ${f.retired} retired"
    }

    /** TEST ONLY. */
    internal fun resetForTest() {
        synchronized(lock) { _variants.value = VariantLab.Book(); _forge.value = StrategyForge.Book() }
        seen.clear(); loaded = true; settling = false; testDays = null
    }

    internal fun setForTest(v: VariantLab.Book, f: StrategyForge.Book) { synchronized(lock) { _variants.value = v; _forge.value = f }; loaded = true }
}
