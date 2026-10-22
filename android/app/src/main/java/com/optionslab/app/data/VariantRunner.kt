package com.optionslab.app.data

import android.content.Context
import com.optionslab.engine.orb.Arm
import com.optionslab.engine.orb.LiquidityBreakeven
import com.optionslab.engine.orb.LiquidityParams
import com.optionslab.engine.orb.OrbParams
import com.optionslab.engine.orb.VixDivParams
import com.optionslab.ira.ArmParams
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
 *  - The arms TRADE what the lab promotes (S1), paper first and never mid-day: each arm reads its parameters from memory
 *    ([orbParams], [liquidityParams], [vixParams]: the book loaded at start on IO; no settings read, no I/O, nothing new on
 *    the order path). A paper strategy's winning variant is applied to the arm's PAPER entries by itself from the next
 *    session, logged with undo, and posted on the findings bus as "variant ready"; a live strategy's is put to Boss
 *    ([com.optionslab.app.ira.IraHub.offer]) and applied only with his PIN (Home → Automation card → Strategy lab), from the next session.
 *    The safety net puts an arm back to its defaults by itself when its variant loses too much ([VariantLab.safety]).
 *    A forge survivor that keeps passing is put to Boss as "start it on paper"; the forge never proposes live.
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
    /** How each strategy ran on its last day (kept: the day's settle may run in a later process - G7-1). */
    private const val RUNS = "runs.txt"
    /** Stored days the pre-check of a new variant reads; days the forge backtests on per index. */
    const val PRECHECK_DAYS = 40
    const val FORGE_DAYS = 100
    /** The indices the lab reads from the store (what the store keeps minutes of, with their options). */
    val UNDERLYINGS = listOf("BANKNIFTY", "NIFTY")
    /** The variants' indices: the forge's and Liquidity 15+5's other two (read when the store keeps them). */
    val VARIANT_UNDERLYINGS = UNDERLYINGS + listOf("FINNIFTY", "MIDCPNIFTY")
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
    @Volatile private var armLoadTried = false

    /** Each arm's last day seen running, and whether live (the arms' hook writes it; memory only). */
    private val seen = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /** TEST ONLY: the stored days instead of the store: (index, the last day, how many) -> its days, oldest first. */
    @Volatile internal var testDays: ((String, LocalDate, Int) -> List<ShadowSim.Day>)? = null

    fun enabled(): Boolean = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!enabled()) return
        scope.launch { com.optionslab.app.data.PositionsFirst.awaitPositions(); runCatching { ensureLoaded() } }
        runCatching { com.optionslab.app.work.DiscoveryWorker.schedule(context.applicationContext) }
    }

    private fun dir(): File? = appContext?.let { File(it.noBackupFilesDir, DIR) }

    /**
     * The lab's files read once. A file that is absent is an empty book; one that exists but cannot be read leaves the lab
     * NOT loaded (G7-11): nothing is saved over it, the arms trade their defaults, and the next call tries again.
     */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            // Before init there is no folder to read: not loaded (never an empty book saved over the real one later).
            val d = dir() ?: return
            val (vText, fText) = try { readOrNull(d, VARIANTS) to readOrNull(d, FORGE) } catch (e: Exception) {
                runCatching { Diag.record("strategylab", "the lab's book could not be read (${e.javaClass.simpleName}): not loaded, nothing saved over it") }
                return
            }
            if (_variants.value == VariantLab.Book()) _variants.value = VariantLab.decode(vText)
            if (_forge.value == StrategyForge.Book()) _forge.value = StrategyForge.decode(fText)
            // How each strategy ran on its last days, kept across a restart (G7-1): the settle after the close may run in a new process.
            runCatching { readOrNull(d, RUNS) }.getOrNull()?.lines()?.forEach { l ->
                val p = l.split('|')
                val day = p.getOrNull(1)?.toLongOrNull() ?: return@forEach
                if (p[0].isNotEmpty()) seen.merge(p[0], day to (p.getOrNull(2) == "1")) { a, b -> mergeSeen(a, b) }
            }
            loaded = true
        }
    }

    /** [f]'s text; null when it does not exist; throws when it exists but cannot be read. */
    private fun readOrNull(d: File?, name: String): String? {
        val f = File(d ?: return null, name)
        return if (f.exists()) f.readText() else null
    }

    private fun save() {
        // Never over a book that could not be read (G7-11).
        if (!loaded) return
        val d = dir() ?: return
        runCatching {
            d.mkdirs()
            write(File(d, VARIANTS), VariantLab.encode(_variants.value))
            write(File(d, FORGE), StrategyForge.encode(_forge.value))
        }
    }

    /** Written whole: a temporary file synced to the disk, then renamed over (a power loss never leaves half a book: G7-11). */
    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.flush(); out.fd.sync() }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** The newer day wins; the same day is live when either pass was (G7-12). */
    private fun mergeSeen(a: Pair<Long, Boolean>, b: Pair<Long, Boolean>): Pair<Long, Boolean> = when {
        b.first > a.first -> b
        b.first < a.first -> a
        else -> a.first to (a.second || b.second)
    }

    private val seenLock = Any()

    /** The runs written off the arms' pass, on IO (one small file; only when a strategy's day or account changed). */
    private fun saveSeenSoon() {
        scope.launch {
            runCatching {
                synchronized(seenLock) {
                    val d = dir() ?: return@synchronized
                    d.mkdirs()
                    write(File(d, RUNS), seen.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}|${it.value.first}|${if (it.value.second) 1 else 0}" })
                }
            }
        }
    }

    private fun saveSoon() { scope.launch { synchronized(lock) { save() } } }

    // ---- directed variants (the order review, 12 Oct) ---------------------------------------------------------------------

    /** Variants asked of the lab and not answered yet (memory; [OrderReviewHost] keeps them in its own file and asks again). */
    private val seeds = ConcurrentHashMap<String, VariantLab.Seed>()
    /** What became of each asked variant at a settle (memory; the review reads it, then the lab's own book). */
    private val seededResults = ConcurrentHashMap<String, VariantLab.Seeded>()

    /**
     * The order review asks the lab to test ONE directed variant ([VariantLab.seed]): handed to the next day's settle, where it
     * goes through the same pre-check, trial count, minimum days and tests as any other (paper first; live only with the PIN).
     * Nothing is applied by asking. Memory only.
     */
    fun ask(seed: VariantLab.Seed) {
        if (!enabled()) return
        if (seededResults[seed.id]?.final == true) return
        // 13 Oct (the shared knowledge): a direction the research or the experiment log already tested and rejected is not tested
        // again - answered at once, final, with the finding (never for Liquidity, whose behaviour is frozen). Memory only.
        val rejected = runCatching { com.optionslab.ira.SharedKnowledge.variantRejected(SharedKnowledgeHost.snapshot, seed.strategy, seed.param, seed.dir) }.getOrNull()
        if (rejected != null) {
            seededResults[seed.id] = VariantLab.Seeded(seed.id, null, "not tested: $rejected", true)
            SharedKnowledgeHost.read("Strategy lab", "the experiment log before a variant ($rejected)")
            runCatching { Diag.record("strategylab", "variant ${seed.strategy} ${seed.param} ${if (seed.dir > 0) "higher" else "lower"} not tested: $rejected") }
            return
        }
        seeds[seed.id] = seed
        while (seeds.size > 40) seeds.keys.firstOrNull()?.let { seeds.remove(it) }
    }

    /** The lab's answer to asked variant [id] (null: not settled yet). */
    fun seededResult(id: String): VariantLab.Seeded? = seededResults[id]

    // ---- the arms' hook ---------------------------------------------------------------------------------------------------

    /**
     * An arm ran a pass while armed ([key]: its source; [live]: armed for the live account). Cheap: one map write, no read of
     * the settings, no I/O, nothing on the order path. Never throws.
     */
    fun saw(key: String, live: Boolean) {
        if (!enabled()) return
        runCatching {
            val day = Market.today().toEpochDay()
            var changed = false
            // One strategy, several books (Liquidity's): live when any of them ran live that day, never the last one's (G7-12).
            seen.compute(lab(key)) { _, prev ->
                val next = if (prev == null) day to live else mergeSeen(prev, day to live)
                if (next != prev) changed = true
                next
            }
            // Kept on the disk for the settle after the close (G7-1): written on IO, only when the day or account changed.
            if (changed) saveSeenSoon()
        }
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

    // ---- what the arms trade (S1) -----------------------------------------------------------------------------------------

    /** The parameters worked out from the book in memory, per (strategy, account, day); dropped whenever the book changes. */
    private class Tuned(val book: VariantLab.Book, val params: ConcurrentHashMap<String, Any> = ConcurrentHashMap())
    @Volatile private var tuned = Tuned(VariantLab.Book())

    /**
     * [key]'s parameter values on [day] for the [live] account (null: the arm's own constants). Memory only: the book was read
     * at start on IO ([init]); until it is, and in a build without the lab, every arm trades its defaults.
     */
    private fun valuesFor(key: String, day: LocalDate, live: Boolean): Map<String, Double>? {
        if (!loaded || !enabled()) return null
        val b = _variants.value
        if (b.tunes.isEmpty()) return null
        return runCatching { VariantLab.valuesFor(b, key, live, day) }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(key: String, day: LocalDate, live: Boolean, make: (Map<String, Double>?) -> T, default: T): T {
        // The first arm pass after a start finds the book not read yet: it is read here, once, off the main thread (as the arms
        // read their own book on their first pass), so no entry of the session is taken on the defaults and the next on a
        // variant (G7-13). Tried once: a book that cannot be read is not read again on every pass.
        if (!loaded && !armLoadTried && appContext != null && enabled() && android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            armLoadTried = true
            runCatching { ensureLoaded() }
        }
        if (!loaded || !enabled() || _variants.value.tunes.isEmpty()) return default
        val b = _variants.value
        var t = tuned
        if (t.book !== b) { t = Tuned(b); tuned = t }
        return t.params.getOrPut("$key|$live|$day") { make(valuesFor(key, day, live)) } as T
    }

    /** The ORB family arm [arm]'s parameters on [day] for the [live] account (its constants unless a variant is in force). */
    fun orbParams(arm: Arm, day: LocalDate, live: Boolean): OrbParams {
        val d = OrbParams.of(arm)
        if (arm.source !in ArmParams.ORB_ARMS) return d
        // R14: ORB / ORB Fresh's PAPER entries take at most 1 a side a day by default, until the lab's book has a paper tune of
        // its own for the arm (R14's change itself, a later promotion, or Boss's Undo of it - which then rules). Live: unchanged
        // unless Boss accepted the cap with his PIN.
        val base = if (!live && arm.source in com.optionslab.engine.orb.ReentryCap.ARMS && !hasTune(arm.source, false, day))
            d.copy(sideCap = com.optionslab.engine.orb.ReentryCap.PAPER_CAP) else d
        return cached(arm.source, day, live, { v -> v?.let { ArmParams.orb(arm.source, it) } ?: base }, base)
    }

    /** Whether the lab's book (in memory) has a tune for [key]'s [live] account in force on [day]: memory only, no I/O. */
    private fun hasTune(key: String, live: Boolean, day: LocalDate): Boolean =
        loaded && enabled() && runCatching { VariantLab.effective(_variants.value, key, live, day) != null }.getOrDefault(false)

    /** Liquidity 15+5's EXIT parameters on [day] for the [live] account (its entries are frozen in code). */
    fun liquidityParams(day: LocalDate, live: Boolean): LiquidityParams =
        cached("liquidity", day, live, { v -> ArmParams.liquidity(v) }, LiquidityParams.DEFAULT)

    /** VIX divergence's parameters on [day] (paper only). */
    fun vixParams(day: LocalDate): VixDivParams =
        cached(com.optionslab.engine.orb.VixDivRules.SOURCE, day, false, { v -> ArmParams.vixDiv(v) }, VixDivParams.DEFAULT)

    /** The first trading day after [d]. */
    private fun nextSession(d: LocalDate): LocalDate {
        var n = d.plusDays(1); var guard = 0
        while (!Market.isTradingDay(n) && guard++ < 15) n = n.plusDays(1)
        return n
    }

    /**
     * The session a change decided now is traded from: never the one running or about to run. After [d]'s close: the next
     * trading day, unless that is today and the market day has begun (from 09:00), then the one after.
     */
    internal fun fromAfter(d: LocalDate, now: java.time.ZonedDateTime = Market.now()): LocalDate {
        val n = nextSession(d)
        val today = now.toLocalDate()
        return if (!n.isAfter(today) && (n.isBefore(today) || now.hour * 60 + now.minute >= 9 * 60)) nextSession(today) else n
    }

    /** Each lab strategy's real result on [d] on the account it ran (rupees a lot, after charges), from the arms' own books. */
    private suspend fun actualOn(d: LocalDate, runs: Map<String, VariantLab.Run>): Map<String, Double> {
        val out = HashMap<String, Double>()
        runCatching {
            val closed = OrbArms.closedPaper() + OrbArms.closedLive()
            for (p in closed) {
                // Boss's opt-in Liquidity give-back trail (R19b) or breakeven at +10% (R100 A4, 22 Oct) is not the rules' own
                // result: left out of the baseline.
                if (p.day != d || p.exit == null || LiquidityBreakeven.ownRules(p.trail, p.be)) continue
                val key = lab(p.arm)
                val run = runs[key] ?: continue
                if (run == VariantLab.Run.OFF || p.live != (run == VariantLab.Run.LIVE)) continue
                out[key] = (out[key] ?: 0.0) + ((p.grossPnl ?: 0.0) - p.charges) / p.lots
            }
        }
        runCatching {
            if (runs[com.optionslab.engine.orb.VixDivRules.SOURCE] == VariantLab.Run.PAPER) {
                val nets = VixDivArm.view.value.closed.filter { it.entryDay == d }.mapNotNull { it.net }
                if (nets.isNotEmpty()) out[com.optionslab.engine.orb.VixDivRules.SOURCE] = nets.sum()
            }
        }
        return out
    }

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
        if (!loaded) return Settled(null, "the lab's book could not be read")
        val vb = _variants.value; val fb = _forge.value
        if ((vb.lastDay?.let { !d.isAfter(it) } == true) && (fb.lastForward?.let { !d.isAfter(it) } == true)) return Settled(null, "already settled")
        // While the day's minutes are not stored yet: the day lists only (a manifest and a directory), not every session decoded.
        // Every index that keeps days must have this one (the harvest writes one at a time: G7-2).
        if (testDays == null && withContext(Dispatchers.IO) {
                !VariantLab.readyToSettle(d, VARIANT_UNDERLYINGS.associateWith { u -> runCatching { Store.barDays(u) }.getOrDefault(emptyList()).filter { !it.isAfter(d) && !it.isBefore(d.minusDays(8)) } }, today)
            })
            return Settled(null, "waiting for $d's stored minutes")
        if (!com.optionslab.app.work.BackgroundBudget.allow(com.optionslab.ira.BudgetRule.Job.FORGE)) return Settled(null, "battery / heat budget")
        settling = true
        try {
            val data = withContext(Dispatchers.IO) { VARIANT_UNDERLYINGS.associateWith { u -> runCatching { days(u, d, PRECHECK_DAYS + 1) }.getOrDefault(emptyList()) } }
            val todays = data.mapNotNull { (u, ds) -> ds.lastOrNull()?.takeIf { it.date == d }?.let { u to it } }.toMap()
            if (todays.isEmpty() || !VariantLab.readyToSettle(d, data.mapValues { (_, ds) -> ds.map { it.date } }, today))
                return Settled(null, "waiting for $d's stored minutes")
            val history = data.values.flatMap { ds -> ds.filter { it.date.isBefore(d) } }
            val runs = runsOn(d)
            // A change decided tonight is traded from the next session, never the one running (S1); the safety net reads what
            // each arm really made that day, where its book has it.
            val from = fromAfter(d, now)
            val actual = if (vb.tunes.isEmpty()) emptyMap() else actualOn(d, runs)
            // An event day is not evidence (G9-6, [com.optionslab.ira.LearningFilter]): no variant compared or judged and no
            // forward day counted on it; the safety net still runs.
            val evidence = runCatching { com.optionslab.ira.LearningFilter.include(d) }.getOrDefault(true)
            // The day's books and their save in one critical section (G7-14): a cancel can never leave memory settled and the
            // disk not.
            val (vp, fp) = withContext(Dispatchers.IO) {
                synchronized(lock) {
                    val v0 = com.optionslab.app.ira.LearningFreeze.holdPromotions(VariantLab.day(_variants.value, d, runs, todays, history,
                        DiscoveryStats.Rng(DiscoveryStats.seedOf(d, 3)), from, actual, evidence, seeds.values.sortedBy { it.id }))
                    // R14's re-entry cap through the lab's own path, once: on paper by itself (undoable), proposed for live (PIN).
                    val r14 = VariantLab.research(v0.book, d, from)
                    val v = v0.copy(book = r14.book, events = v0.events + r14.events)
                    val f = StrategyForge.forwardDay(_forge.value, d, if (evidence) todays.filterKeys { it in UNDERLYINGS } else emptyMap())
                    _variants.value = v.book; _forge.value = f.book
                    save()
                    v to f
                }
            }
            // The asked variants' answers: kept for the review; a final one is not asked again.
            for (r in vp.seeded) { seededResults[r.id] = r; if (r.final) seeds.remove(r.id) }
            while (seededResults.size > 200) seededResults.keys.firstOrNull()?.let { seededResults.remove(it) }
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
                if (e.change.kind == VariantLab.ChangeKind.RESEARCH) {
                    val from = e.change.effective?.let { " from ${DiscoveryStats.day(it)}" } ?: ""
                    post("$WHO · $label", "research R14: ${VariantLab.moved(e.change)} on its paper trades$from (fewer re-entries, fewer charges)")
                    runCatching { com.optionslab.app.ira.IraActivity.add("Strategy lab: $label's paper trades take at most 1 entry a side a day$from " +
                        "(research R14: re-entries made about nothing before charges); undo on $WHERE.") }
                    continue
                }
                val v = _variants.value.variants.firstOrNull { it.id == e.change.variant }
                val from = e.change.effective?.let { " from ${DiscoveryStats.day(it)}" } ?: ""
                post("$WHO · $label", "variant ready: ${v?.moved() ?: "new rules"} applied to its paper trades$from by itself - ${e.change.why}")
                runCatching { com.optionslab.app.ira.IraActivity.add("Strategy lab: $label's paper rules change$from (${v?.moved()}); undo on Home → Automation card → Strategy lab.") }
            }
            is VariantLab.Event.Reverted -> {
                val label = VariantLab.strategy(e.change.strategy)?.label ?: e.change.strategy
                post("$WHO · safety net", "$label back to its defaults: ${e.change.why}")
                runCatching { com.optionslab.app.ira.IraActivity.add("Strategy lab safety net: $label back to its defaults - ${e.change.why}") }
                // The live arm kept its tighter stop: the defaults' wider one adds live risk - Boss's, with his PIN (the Undo of the
                // accepted live change in the Strategy lab).
                if (e.liveStopKept) runCatching {
                    com.optionslab.app.ira.IraHub.offer("strategy lab: $label live stop", "Boss, $label's live stop",
                        "Boss, the safety net put $label back to its defaults, but its live trades keep their tighter stop and exits: the " +
                            "defaults' are wider or later and add live risk. To go back to them, Undo the live change with your PIN in $WHERE.",
                        suspend { "Undo the live change with your PIN in $WHERE (a wider live stop or a later exit adds risk)." }, addsRisk = true, alwaysAsk = true)
                }
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
    const val WHERE = "Home → Automation card → Strategy lab"

    private fun offerLive(c: VariantLab.Change) {
        val label = VariantLab.strategy(c.strategy)?.label ?: c.strategy
        val v = _variants.value.variants.firstOrNull { it.id == c.variant }
        val what = if (c.variant == "R14") "Boss, research R14 says $label should take at most 1 entry a side a day (${VariantLab.moved(c)}): ${c.why}. " +
            "It lowers live risk and charges; it trades live, so it changes only with your PIN in $WHERE."
            else "Boss, a variant of $label (${v?.moved() ?: "new rules"}) beat its current rules: ${c.why}. It trades live, so it changes only with your PIN in $WHERE."
        com.optionslab.app.ira.IraHub.offer("strategy lab: $label", "Boss, a better $label?", what,
            suspend { "Apply it with your PIN in $WHERE (it changes a live strategy)." }, addsRisk = true, alwaysAsk = true,
            declined = { synchronized(lock) { _variants.value = VariantLab.decline(_variants.value, c.id) }; saveSoon() })
    }

    private fun offerPaper(r: StrategyForge.Record) {
        com.optionslab.app.ira.IraHub.offer("strategy lab: start ${r.c.id} on paper", "Boss, a new strategy kept passing",
            "Boss, ${r.c.name} ${r.why}. Start it on paper? Paper only: it trades on the paper account (at most 3 ideas at once), never a live order.",
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
        if (!loaded || _forge.value.lastNight == today) return null
        val hist = withContext(Dispatchers.IO) { UNDERLYINGS.associateWith { u -> runCatching { days(u, today, FORGE_DAYS) }.getOrDefault(emptyList()) } }
        if (hist.values.all { it.size < 30 }) return null
        val t0 = System.nanoTime()
        // 13 Oct (the shared knowledge): the plain form of an idea the research or the experiment log rejected is not tested again,
        // and the blocks are seeded from the findings (a prior beside their own record; the gates never read it).
        // 22 Oct: at a start in the background the knowledge's first build waits for the app's use; the forge asks for it here.
        SharedKnowledgeHost.awaitFirstBuild("the strategy forge")
        val sk = SharedKnowledgeHost.snapshot
        val prior = runCatching { com.optionslab.ira.SharedKnowledge.forgePrior(sk) }.getOrDefault(emptyMap())
        val avoid: (StrategyForge.Candidate) -> String? = { c ->
            runCatching { com.optionslab.ira.SharedKnowledge.forgeAvoid(sk, c.rules.entry.name, c.rules.filters.size) }.getOrNull()
        }
        val night = withContext(Dispatchers.Default) {
            StrategyForge.night(_forge.value, today, hist, { (System.nanoTime() - t0) / 1_000_000 }, StrategyForge.BUDGET_MS, stopped,
                avoid = avoid, prior = prior)
        }
        if (prior.isNotEmpty()) SharedKnowledgeHost.read("Strategy lab", "the research's verdicts as the forge's seeds (${prior.size} blocks)")
        SharedKnowledgeHost.forgeSkipped(night.skipped)
        if (night.made.isNotEmpty() || night.skipped.isNotEmpty())
            SharedKnowledgeHost.added("Strategy lab", "${night.made.size} ideas tried, ${night.skipped.size} not tested again (the research rejected them)")
        // Every idea tried is logged (its gates' result), and every idea skipped with the finding that rejected it.
        for (r in night.made) runCatching { Diag.record("strategylab", "idea ${r.c.name}: ${r.why}") }
        for ((idea, why) in night.skipped.distinct().take(20)) runCatching { Diag.record("strategylab", "idea $idea not tested: $why") }
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

    /** The next session from now: where Boss's changes take effect (never mid-day). */
    private fun fromNow(): LocalDate = nextSession(Market.today())

    /** Undo an applied paper change (no PIN: paper only), from the next session. */
    fun undo(changeId: String): String {
        ensureLoaded()
        val from = fromNow()
        val b = synchronized(lock) { VariantLab.undo(_variants.value, changeId, from)?.also { _variants.value = it } } ?: return "That change can't be undone now."
        saveSoon()
        val c = b.changes.first { it.id == changeId }
        return "Undone: ${VariantLab.strategy(c.strategy)?.label ?: c.strategy}'s paper rules are back to before from ${DiscoveryStats.day(from)}."
    }

    const val PIN_NEEDED = "It changes a live strategy: confirm with your PIN. Nothing changed."

    /** Whether undoing live change [changeId] only puts the arm's defaults back (no PIN needed: it lowers risk). */
    fun undoLiveNeedsPin(changeId: String): Boolean = !VariantLab.restoresDefault(_variants.value, changeId)

    /**
     * Undo a live change applied with the PIN, from the next session: without [pinConfirmed] only when it puts the defaults
     * back (lower or equal risk); any other live undo needs the PIN.
     */
    fun undoLive(changeId: String, pinConfirmed: Boolean): String {
        ensureLoaded()
        if (!pinConfirmed && undoLiveNeedsPin(changeId)) return PIN_NEEDED
        val from = fromNow()
        val b = synchronized(lock) { VariantLab.undoLive(_variants.value, changeId, pinConfirmed, from)?.also { _variants.value = it } }
            ?: return "That change can't be undone now."
        saveSoon()
        runCatching { Diag.record("strategylab", "live change $changeId undone from $from" + if (pinConfirmed) " with the PIN" else " (defaults back)") }
        val c = b.changes.first { it.id == changeId }
        return "Undone: ${VariantLab.strategy(c.strategy)?.label ?: c.strategy} is back to before on both accounts from ${DiscoveryStats.day(from)}."
    }

    /** Apply a live proposal: ONLY with [pinConfirmed] (the card asks for the PIN first), from the next session. */
    fun acceptLive(changeId: String, pinConfirmed: Boolean): String {
        if (!pinConfirmed) return PIN_NEEDED
        ensureLoaded()
        val from = fromNow()
        synchronized(lock) { VariantLab.accept(_variants.value, changeId, true, from)?.also { _variants.value = it } } ?: return "That proposal is no longer waiting."
        saveSoon()
        runCatching { Diag.record("strategylab", "live proposal $changeId applied with the PIN from $from") }
        return "Applied with your PIN: the strategy trades the variant from ${DiscoveryStats.day(from)} (never mid-day)."
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
        return "$id is approved for paper: it trades on the paper account from its next signal (at most 3 ideas at once). Live stays yours, with the PIN, after a long paper record."
    }

    fun declinePaper(id: String): String {
        val day = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        synchronized(lock) { _forge.value = StrategyForge.declineProposal(_forge.value, id, day) }
        saveSoon()
        return "Declined: $id stays out."
    }

    // ---- Jarvis -------------------------------------------------------------------------------------------------------------

    /** Jarvis's answer; on a [locked] phone none (the lab's books carry the bots' rupees on Boss's account: G9-4). */
    fun answer(a: LabTalk.Ask, locked: Boolean = false): String {
        if (!locked) ensureLoaded()
        return LabTalk.answer(a, _forge.value, _variants.value, locked)
    }

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
        seen.clear(); loaded = true; settling = false; testDays = null; tuned = Tuned(VariantLab.Book()); armLoadTried = false
        seeds.clear(); seededResults.clear()
    }

    /** TEST ONLY: as a fresh process - nothing in memory, the files not read yet. */
    internal fun unloadForTest() {
        synchronized(lock) { _variants.value = VariantLab.Book(); _forge.value = StrategyForge.Book() }
        seen.clear(); loaded = false; armLoadTried = false; tuned = Tuned(VariantLab.Book())
    }

    internal val isLoaded: Boolean get() = loaded

    internal fun setForTest(v: VariantLab.Book, f: StrategyForge.Book) { synchronized(lock) { _variants.value = v; _forge.value = f }; loaded = true }
}
