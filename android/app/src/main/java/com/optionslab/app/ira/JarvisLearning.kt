package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.Market
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.Findings
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Jarvis learns from the agents (Boss, 11 Oct): once a trading day after F&O's close (15:45, on the brain's worker or the
 * 15:45 day report, whichever comes first - never the main thread, no network), every agent's records are read from where
 * they already live (the bots' trades, the market brain, the self-review, the trade manager and its specialists, the order
 * flow's shadow log with the trap guard's flags, the findings logs, self-healing's incidents, the move recorder's files) and
 * digested ([AgentLessons.pass]) into his lessons, kept in noBackupFilesDir/jarvis/lessons.txt (never the settings vault,
 * never a backup). One finding says what he learned; the evening report gets the line.
 *
 * A strong lesson that implies a change is put to Boss as a request ([IraHub.offer], always asked): mute a specialist, pause
 * a strategy's new paper entries, or let the trade manager act on a strategy's paper trades. His yes or no is kept and moves
 * the lesson ([AgentLessons.answered]); two noes rest it 30 days. Nothing here touches the PIN, the lock, Live, AI-live, a
 * guard, the Google speech choice, the day lock or real orders, and nothing changes without his yes. Not in IraGoldAlgo.
 */
internal object JarvisLearning {
    private const val DIR = "jarvis"
    private const val FILE = "lessons.txt"
    /** "Why do you think that?" reads the lessons of the answer said this recently. */
    private const val WHY_MS = 10 * 60_000L

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _book = MutableStateFlow(AgentLessons.Book())
    /** What Jarvis has learned (the sheet on the Ira page reads it). */
    val book: StateFlow<AgentLessons.Book> = _book

    @Volatile private var loaded = false
    @Volatile private var passedOn: LocalDate? = null

    /** The lessons the last answer was built from, its words and when (for "why do you think that?"). */
    @Volatile private var shown: List<String> = emptyList()
    @Volatile private var shownText: String? = null
    @Volatile private var shownAt = 0L

    /** TEST ONLY: the agents' records instead of the stores. */
    @Volatile internal var testInputs: ((LocalDate) -> AgentLessons.Inputs)? = null

    fun enabled(): Boolean = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    fun init(context: Context) {
        appContext = context.applicationContext
        if (enabled()) scope.launch { runCatching { ensureLoaded() } }
    }

    private fun file(): File? = appContext?.let { File(File(it.noBackupFilesDir, DIR), FILE) }

    /** The kept lessons read once (a worker thread; the file is small). */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val f = file()
            val read = runCatching { f?.takeIf { it.isFile }?.let { testRead?.invoke(it) ?: it.readText() } }
            // A file there that could not be read (G6-8) is never taken as "no lessons": [loaded] stays false, nothing is saved
            // over it ([save]) and the next look reads it again - one failed read never wipes his history.
            if (read.isFailure && f != null && f.exists()) return
            // (A pass or an answer in the moment before the file was read wins: it is written over the file next.)
            if (_book.value == AgentLessons.Book()) _book.value = AgentLessons.decode(read.getOrNull())
            loaded = true
        }
    }

    /** TEST ONLY: the file's read (a failing read). */
    @Volatile internal var testRead: ((File) -> String)? = null

    private fun save(b: AgentLessons.Book) {
        // Never over a file that was not read (a failed load would otherwise wipe what he has learned).
        if (!loaded) return
        val f = file() ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(AgentLessons.encode(b))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    // ---- the nightly pass -------------------------------------------------------------------------------------------------

    /**
     * Once a trading day after 15:45: the day's learning pass. Cheap when not due (memory only). True when it ran.
     */
    suspend fun learnIfDue(context: Context? = null): Boolean {
        if (!enabled()) return false
        if (context != null && appContext == null) appContext = context.applicationContext
        val today = Market.today()
        if (passedOn == today) return false
        if (!Market.isTradingDay(today) || Market.minuteNow() < 15 * 60 + 45) return false
        // One pass at a time (the brain's worker and the day report's worker both ask); the day is marked done only once its
        // pass has run, so a pass cut short (cancelled, its records unreadable) is tried again on a later look.
        if (!passing.compareAndSet(false, true)) return false
        try {
            // S18: battery / heat budget - asked inside the try (G6-9), so every ask is ended by its [BackgroundBudget.done].
            if (!com.optionslab.app.work.BackgroundBudget.allow(com.optionslab.ira.BudgetRule.Job.LEARNING)) return false
            if (passedOn == today) return false
            withContext(Dispatchers.IO) { ensureLoaded() }
            if (_book.value.lastPass == today) { passedOn = today; return false }
            val nowMs = Market.now().toInstant().toEpochMilli()
            if (nowMs < retryAtMs) return false
            if (runPass(today) == null) { retryAtMs = nowMs + RETRY_MS; return false }
            passedOn = today
            return true
        } finally {
            runCatching { com.optionslab.app.work.BackgroundBudget.done(com.optionslab.ira.BudgetRule.Job.LEARNING) }
            passing.set(false)
        }
    }

    private val passing = java.util.concurrent.atomic.AtomicBoolean(false)
    /** A pass whose records could not be read waits this long before it is tried again (not every look). */
    private const val RETRY_MS = 10 * 60_000L
    @Volatile private var retryAtMs = 0L

    /** The pass itself (and by tests): read, digest, keep, tell, propose. */
    internal suspend fun runPass(today: LocalDate): AgentLessons.Pass? {
        // Never a pass on an unread book (G6-8): its result would be saved over the lessons the file holds.
        withContext(Dispatchers.IO) { ensureLoaded() }
        if (!loaded) return null
        val read = withContext(Dispatchers.IO) { runCatching { testInputs?.invoke(today) ?: gather(today) }.getOrNull() } ?: return null
        // The strategy lab's results are evidence like every other agent's (S, 11 Oct): one digest, one set of rules.
        val inputs = if (read.lab.isNotEmpty()) read else read.copy(lab = withContext(Dispatchers.IO) { labResults(today) })
        val pass = withContext(Dispatchers.Default) {
            synchronized(lock) {
                val p = AgentLessons.pass(_book.value, inputs)
                _book.value = p.book
                p
            }
        }
        withContext(Dispatchers.IO) { synchronized(lock) { save(_book.value) } }
        runCatching { com.optionslab.app.data.Diag.record("jarvis", pass.finding) }
        // One finding, only when something was learned (nothing posted on a day with nothing new).
        if (AgentLessons.learnedCount(pass.book, pass.changes) > 0) runCatching {
            com.optionslab.app.data.SmartWorkers.found("Jarvis", Findings.Kind.OTHER, Findings.ALL, words = pass.finding, strength = 50,
                ttlMs = 24 * 3_600_000L)
        }
        for (p in pass.proposals) runCatching { propose(p) }
        // (The strategy lab's separate notes post is gone: its results are in this pass's lessons and its one finding.)
        return pass
    }

    /** Days of the lab's records read again each night once Jarvis holds lab lessons (older days stay as he read them). */
    private const val LAB_REREAD_DAYS = 5L

    /** The strategy lab's results ([com.optionslab.ira.LabLessons.results]): its small files, in memory once loaded; empty when off. */
    private fun labResults(today: LocalDate): List<AgentLessons.LabResult> = runCatching {
        val vr = com.optionslab.app.data.VariantRunner
        if (!vr.enabled()) return@runCatching emptyList<AgentLessons.LabResult>()
        vr.ensureLoaded()
        val since = if (_book.value.lessons.any { it.id.startsWith("lab:") }) today.minusDays(LAB_REREAD_DAYS) else null
        com.optionslab.ira.LabLessons.results(vr.forge.value, vr.variants.value, since)
    }.getOrDefault(emptyList())

    /** Every agent's records, read from where they already live (files and memory; no network). */
    private suspend fun gather(today: LocalDate): AgentLessons.Inputs {
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val trips = runCatching { IraAccount.trips(false, owners) }.getOrDefault(emptyList()) +
            runCatching { IraAccount.trips(true, owners) }.getOrDefault(emptyList())
        // The day's regime as the regime settings used it: frozen at 09:45 from the morning's read (RegimeParams), not the
        // brain's state now after the close (the last half hour's) - a "busy days only" lesson must mean the days the busy
        // setting would apply on (R3 finding 3). Not frozen today (a phone that first looked after 10:15): not known.
        val regime = runCatching { com.optionslab.ira.RegimeParams.book.day?.takeIf { it.day == today }?.regime }.getOrNull()?.let {
            when (it) {
                com.optionslab.ira.RegimeParams.Regime.BUSY -> "busy"
                com.optionslab.ira.RegimeParams.Regime.QUIET -> "quiet"
                com.optionslab.ira.RegimeParams.Regime.TRENDING, com.optionslab.ira.RegimeParams.Regime.SIDEWAYS -> "normal"
            }
        }
        runCatching { com.optionslab.app.data.SmartWorkers.refreshReviews() }
        val drift = com.optionslab.app.data.SmartWorkers.reviews.value
        val managed = com.optionslab.app.data.TradeManagerHost.records.value
        val flow = runCatching { com.optionslab.app.data.FlowGate.signalsForLearning() }.getOrDefault(emptyList())
        val health = com.optionslab.app.data.SmartWorkers.health
        val incidents = runCatching { health.past() + health.openIncidents() }.getOrDefault(emptyList())
        val moves = runCatching { com.optionslab.app.data.MoveRecorder.files().map { it.first } }.getOrDefault(emptyList())
        val book = com.optionslab.app.data.ManagerSpecialists.book.value
        val muted = (book.tuned.entries + book.frozen.entries).flatMap { (f, t) -> t.muted.map { (id, why) -> "$f:$id" to why } }.toMap()
        val paused = com.optionslab.app.data.SmartWorkers.settings.value.paused
        val policy = com.optionslab.app.data.TradeManagerHost.policy.value
        val modes = TradeManager.FAMILIES.associate { it.key to policy.mode(it.key, TradeManager.Account.PAPER) }
        // Event days (TODO S11): RBI policy, the Budget, results, rebalances and shifted expiries are kept out of the evidence -
        // a day like that teaches about the event, not about the strategy.
        val f = com.optionslab.ira.LearningFilter
        val keptTrips = trips.filter { runCatching { f.include(it.closedAt.toLocalDate()) }.getOrDefault(true) }
        val keptManaged = managed.filter { r -> runCatching {
            f.include(java.time.Instant.ofEpochMilli(r.trade.entryMs).atZone(com.optionslab.engine.IST).toLocalDate()) }.getOrDefault(true) }
        // The order review's recurring flaws only (a one-off never becomes a lesson): its own small file, read here.
        val orders = runCatching { com.optionslab.app.data.OrderReviewHost.lessonInputs() }.getOrDefault(emptyList())
        return AgentLessons.Inputs(today, keptTrips, regime, drift, keptManaged, flow, findingsKept(), incidents, moves, muted, paused, modes, orders = orders)
    }

    /**
     * The findings logs (30 days, noBackupFilesDir/findings): the data-health posts, and one note a day the log was kept (the
     * days watched). Only those lines are read in whole.
     */
    private fun findingsKept(): List<Findings.Finding> {
        val dir = appContext?.let { File(it.noBackupFilesDir, "findings") } ?: return emptyList()
        val out = ArrayList<Findings.Finding>()
        for (f in dir.listFiles().orEmpty()) runCatching {
            val day = LocalDate.parse(f.name.removeSuffix(".log"))
            out += Findings.Finding("findings bus", Findings.Kind.OTHER, Findings.ALL, atMs = day.atTime(12, 0).atZone(com.optionslab.engine.IST).toInstant().toEpochMilli())
            f.useLines { lines -> for (l in lines) if (l.contains("|DATA_")) Findings.parse(l)?.let { out += it } }
        }
        return out
    }

    // ---- proposals and Boss's answers ---------------------------------------------------------------------------------------

    /** A strong lesson's change put to Boss (always asked; done only on his yes). */
    private fun propose(p: AgentLessons.Proposal) {
        if (!AgentLessons.safe(p)) return
        val act: suspend () -> String = when (p.kind) {
            AgentLessons.ProposalKind.MUTE_SPECIALIST -> suspend {
                answered(p.lessonId, true)
                com.optionslab.app.data.ManagerSpecialists.mute(p.family, p.specialist ?: "", by = "Jarvis's lesson, on your yes")
            }
            AgentLessons.ProposalKind.PAUSE_PAPER_ENTRIES -> suspend {
                answered(p.lessonId, true)
                com.optionslab.app.data.SmartWorkers.pausePaperByOwner(p.family)
            }
            AgentLessons.ProposalKind.MANAGER_ACT_PAPER -> suspend {
                answered(p.lessonId, true)
                // Paper only: the trade manager's live mode is never touched here (that stays Boss's, with his PIN).
                com.optionslab.app.data.TradeManagerHost.setMode(p.family, TradeManager.Account.PAPER, TradeManager.Mode.ACT)
            }
        }
        IraHub.offer(p.title, "Boss, a lesson suggests a change", p.text, act, alwaysAsk = true, declined = { answered(p.lessonId, false) })
    }

    /** What Autopilot made of a lesson's change ("applied", "kept", "reverted"): kept on the lesson, so he learns from his own changes. */
    fun autoStatus(lessonId: String, status: String) {
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now(com.optionslab.engine.IST) }
        // On the IO pool (G6-7): the lock is held across a whole pass and its save, and a caller may be the main thread.
        scope.launch {
            ensureLoaded()
            if (!loaded) return@launch
            synchronized(lock) { _book.value = AgentLessons.autoStatus(_book.value, lessonId, status, today); save(_book.value) }
        }
    }

    /**
     * A paper experiment's end ([com.optionslab.ira.Experiments]): kept as a lesson of its own (a loss: a negative one) and the
     * lesson that started it moved ([AgentLessons.experimentResult]). A worker thread (the file is read first if it was not).
     */
    fun experimentResult(lessonId: String?, target: String, what: String, won: Boolean, pairs: List<Pair<LocalDate, Double>>) {
        ensureLoaded()
        if (!loaded) return
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now(com.optionslab.engine.IST) }
        synchronized(lock) { _book.value = AgentLessons.experimentResult(_book.value, lessonId, target, what, won, pairs, today) }
        scope.launch { synchronized(lock) { save(_book.value) } }
    }

    /** Boss's yes or no to a lesson's proposal: kept, and it moves the lesson. */
    fun answered(lessonId: String, accepted: Boolean) {
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now(com.optionslab.engine.IST) }
        // On the IO pool (G6-7): "no" is tapped on the main thread, and the lock is held across a whole pass and its save.
        scope.launch {
            ensureLoaded()
            if (!loaded) return@launch
            synchronized(lock) { _book.value = AgentLessons.answered(_book.value, lessonId, accepted, today); save(_book.value) }
        }
        runCatching { com.optionslab.app.data.Diag.record("jarvis", if (accepted) "a lesson's proposal: yes" else "a lesson's proposal: no") }
    }

    // ---- answers ------------------------------------------------------------------------------------------------------------

    /** "What have you learned?", "what did you learn today?", "how are you growing?", "what do you know about X?": words and facts. */
    fun answer(a: AgentLessons.Ask, locked: Boolean = false): Pair<String, List<String>> {
        ensureLoaded()
        val b = _book.value
        // A locked phone (G6-1): the counts only - the lessons carry the bots' rupees, live round trips among them.
        if (locked) {
            shown = AgentLessons.ranked(AgentLessons.active(b)).take(4).map { it.id }
            return AgentLessons.sayLocked(b).also { shownText = it; shownAt = System.currentTimeMillis() } to emptyList()
        }
        val today = Market.today()
        val todays: List<AgentLessons.Lesson> = if (b.lastPass != today) emptyList()
            else b.today.mapNotNull { c -> b.lessons.firstOrNull { it.id == c.id } }.filter { it.confidence != AgentLessons.Confidence.WEAK }.take(4)
        val (text, used) = when (a.kind) {
            AgentLessons.AskKind.ALL -> Pair(AgentLessons.sayAll(b), AgentLessons.ranked(AgentLessons.active(b)).filter { it.confidence != AgentLessons.Confidence.WEAK }.take(4))
            AgentLessons.AskKind.TODAY -> Pair(AgentLessons.sayToday(b, today), todays)
            AgentLessons.AskKind.GROWTH -> Pair(AgentLessons.sayGrowth(b), emptyList<AgentLessons.Lesson>())
            AgentLessons.AskKind.ABOUT -> Pair(AgentLessons.sayAbout(b, a), AgentLessons.about(b, a).take(4))
        }
        shown = used.map { it.id }; shownText = text; shownAt = System.currentTimeMillis()
        return text to AgentLessons.facts(used)
    }

    /** Is "why do you think that?" about the lessons answer just said ([lastIra]: the newest thing Jarvis said)? */
    fun whyFresh(lastIra: String?): Boolean = shown.isNotEmpty() && lastIra != null && lastIra == shownText &&
        System.currentTimeMillis() - shownAt in 0..WHY_MS

    /** "Why do you think that?": the evidence behind the lessons of the last answer. */
    fun why(locked: Boolean = false): String {
        val b = _book.value
        return AgentLessons.sayWhy(shown.mapNotNull { id -> b.lessons.firstOrNull { it.id == id } }, locked)
    }

    /** One strong lesson for an answer about [tag] (a strategy family) or [topic], or null (memory only: nothing read here). */
    fun appendFor(tag: String?, topic: AgentLessons.Topic? = null): String? =
        if (!enabled() || !loaded) null else runCatching { AgentLessons.appendFor(_book.value, tag, topic) }.getOrNull()

    /** The model's notes for [question]: the relevant lessons, short with their numbers, at most 600 characters. */
    fun modelNotes(question: String): List<String> =
        if (!enabled() || !loaded) emptyList() else runCatching { AgentLessons.forModel(_book.value, question) }.getOrDefault(emptyList()) +
            runCatching { com.optionslab.ira.LabTalk.asked(question)?.let { com.optionslab.app.data.VariantRunner.modelNotes() } }.getOrNull().orEmpty()

    /** The evening report's "What I learned today" line (null: no pass today, or nothing learned from the agents yet). */
    fun eveningLine(today: LocalDate = Market.today()): String? = _book.value.takeIf { it.lastPass == today && it.lessons.isNotEmpty() }?.line

    /** A line for Copy diagnostics: how many lessons, how strong, when last studied. */
    fun diagLine(): String {
        val b = _book.value
        val c = AgentLessons.counts(b)
        return "Jarvis's lessons: ${AgentLessons.active(b).size} (${c[AgentLessons.Confidence.STRONG]} strong, ${c[AgentLessons.Confidence.BUILDING]} building), " +
            "last studied ${b.lastPass ?: "never"}" + (if (shownAt > 0) ", last asked ${Instant.ofEpochMilli(shownAt)}" else "")
    }

    /**
     * The weekly consolidation ([SelfGrowth]): [f] - the lessons' own merge and caps ([com.optionslab.ira.Consolidation.lessons]) -
     * applied to what he has learned and written back. How many lessons fewer. A worker thread.
     */
    internal fun consolidate(f: (AgentLessons.Book) -> Pair<AgentLessons.Book, Int>): Int {
        if (!enabled()) return 0
        ensureLoaded()
        if (!loaded) return 0
        val n = synchronized(lock) {
            val (b, n) = f(_book.value)
            _book.value = b
            save(b)
            n
        }
        return n
    }

    /** TEST ONLY: nothing kept, nothing shown (in memory). */
    internal fun resetForTest() {
        synchronized(lock) { _book.value = AgentLessons.Book() }
        loaded = true; passedOn = null; passing.set(false); retryAtMs = 0L; shown = emptyList(); shownText = null; shownAt = 0L; testInputs = null
        testRead = null
    }

    /** TEST ONLY: nothing in memory and the file not read yet (a new process). */
    internal fun reloadForTest() { synchronized(lock) { _book.value = AgentLessons.Book() }; loaded = false }

    /** TEST ONLY: [b] as what he has learned. */
    internal fun setForTest(b: AgentLessons.Book) { synchronized(lock) { _book.value = b }; loaded = true }
}
