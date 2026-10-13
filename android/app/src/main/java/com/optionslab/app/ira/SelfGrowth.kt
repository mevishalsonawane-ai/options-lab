package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.AppHealth
import com.optionslab.app.data.Diag
import com.optionslab.app.data.Market
import com.optionslab.ira.Consolidation
import com.optionslab.ira.Findings
import com.optionslab.ira.GrowthReport
import com.optionslab.ira.HealthBook
import com.optionslab.ira.LearningGoals
import com.optionslab.ira.MoveEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Jarvis's growth beyond his lessons (Boss, 11 Oct: "enhance the self improvement"; the logic is ira-core's):
 *
 *  - what he tries to learn next ([LearningGoals]): each night after his learning pass, at most three goals where the
 *    evidence is thinnest but worth the most. They steer recording only ([shadowLogBytes]: the order flow's shadow log kept
 *    longer, within its cap; the move recorder's files of a goal's index trimmed last) - never a trade;
 *  - the weekly memory consolidation ([Consolidation]): on Sunday, or the first run after it - his lessons' near-duplicates
 *    merged by their own rule, the findings history compacted, old recordings beyond their caps trimmed, a week summary kept;
 *  - the weekly self-report ([GrowthReport]): Saturday morning, or the first open after Friday ends - one notification,
 *    the "Weekly report" sheet from Home's Autopilot card, and "how did you grow this week?".
 *
 * Everything runs off the main thread, reads the agents' own records and writes only noBackupFilesDir/growth (never the
 * settings vault, never a backup). Nothing here places, changes or sizes an order, or changes a setting. Not in IraGoldAlgo.
 */
internal object SelfGrowth {
    private const val DIR = "growth"
    private const val GOALS = "goals.txt"
    private const val WEEKS = "weeks.txt"
    const val NOTICE_ID = 5140

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val running = AtomicBoolean(false)

    private val _goals = MutableStateFlow(LearningGoals.Book())
    /** What Jarvis tries to learn next ("What Jarvis has learned" shows them). */
    val goals: StateFlow<LearningGoals.Book> = _goals

    private val _weeks = MutableStateFlow<List<GrowthReport.Week>>(emptyList())
    /** The weekly reports kept, oldest first (the sheet shows the newest). */
    val weeks: StateFlow<List<GrowthReport.Week>> = _weeks

    @Volatile private var consolidatedOn: LocalDate? = null
    @Volatile private var reportedWeek: LocalDate? = null
    @Volatile private var plan = LearningGoals.RecordingPlan()
    @Volatile private var loaded = false

    /** TEST ONLY: the time instead of the clock's. */
    @Volatile internal var testNow: (() -> LocalDateTime)? = null

    fun enabled(): Boolean = JarvisLearning.enabled()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (enabled()) scope.launch { com.optionslab.app.data.PositionsFirst.awaitPositions(); runCatching { ensureLoaded() } }
    }

    private fun dir(): File? = appContext?.let { File(it.noBackupFilesDir, DIR) }

    /** The goals and the weeks read once (a worker thread; the files are small). */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val d = dir()
            val g = runCatching { LearningGoals.decode(d?.let { File(it, GOALS) }?.takeIf { it.isFile }?.readText()) }.getOrDefault(LearningGoals.Book())
            val lines = runCatching { d?.let { File(it, WEEKS) }?.takeIf { it.isFile }?.readLines() }.getOrNull().orEmpty()
            if (_goals.value == LearningGoals.Book()) setGoals(g)
            if (_weeks.value.isEmpty()) _weeks.value = lines.mapNotNull { GrowthReport.decode(it) }.sortedBy { it.monday }
            lines.firstOrNull { it.startsWith("M|") }?.split('|')?.let { p ->
                consolidatedOn = consolidatedOn ?: p.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                reportedWeek = reportedWeek ?: p.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            }
            loaded = true
        }
    }

    private fun setGoals(g: LearningGoals.Book) {
        _goals.value = g
        plan = LearningGoals.plan(g.goals).takeIf { LearningGoals.safe(it) } ?: LearningGoals.RecordingPlan()
    }

    private fun write(name: String, text: String) {
        val d = dir() ?: return
        runCatching {
            d.mkdirs()
            val f = File(d, name)
            val tmp = File(d, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun saveWeeks() = synchronized(lock) {
        write(WEEKS, "M|${consolidatedOn ?: ""}|${reportedWeek ?: ""}\n" + _weeks.value.joinToString("") { GrowthReport.encode(it) + "\n" })
    }

    /** The order flow's shadow log size before it rotates: 4 MB, up to 8 MB while a goal needs it. Memory only. */
    fun shadowLogBytes(): Long = plan.shadowLogBytes

    // ---- the tick --------------------------------------------------------------------------------------------------------

    /**
     * Cheap when nothing is due (memory checks): the goals after Jarvis's pass, the weekly consolidation, the weekly report.
     * Off the main thread (the brain's worker, the app's open, Saturday's morning report); one at a time.
     */
    suspend fun tick(context: Context? = null) {
        if (!enabled()) return
        if (context != null && appContext == null) appContext = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) { ensureLoaded() }
            runCatching { goalsIfDue() }
            runCatching { consolidateIfDue() }
            runCatching { reportIfDue(context ?: appContext) }
        } finally { running.set(false) }
    }

    private fun now(): LocalDateTime = testNow?.invoke() ?: Market.now().toLocalDateTime()

    /** After each learning pass (a new day in Jarvis's book): at most three goals; one finding when they change. True: set. */
    internal suspend fun goalsIfDue(): Boolean {
        val book = JarvisLearning.book.value
        val day = book.lastPass ?: return false
        if (_goals.value.day == day) return false
        val instruments = withContext(Dispatchers.IO) {
            runCatching { LearningGoals.instruments(com.optionslab.app.data.FlowGate.signalsForLearning()) }.getOrDefault(emptyMap())
        }
        val old = _goals.value
        val next = withContext(Dispatchers.Default) { LearningGoals.next(old, book.lessons, instruments, day) }
        // Only goals that record and never trade are kept.
        val safe = next.copy(goals = next.goals.filter { LearningGoals.safe(it) })
        synchronized(lock) { setGoals(safe) }
        withContext(Dispatchers.IO) { synchronized(lock) { write(GOALS, LearningGoals.encode(safe)) } }
        if (safe.goals.map { it.id } != old.goals.map { it.id } && safe.goals.isNotEmpty()) {
            val words = "What I'm trying to learn next: " + safe.goals.joinToString("; ") { it.text }
            runCatching { Diag.record("jarvis", "learning goals: " + safe.goals.joinToString("; ") { it.id }) }
            // Its own name on the bus (the learning pass's "Jarvis" finding a moment before would hide a second one of that name).
            runCatching { com.optionslab.app.data.SmartWorkers.found("Jarvis's goals", Findings.Kind.OTHER, Findings.ALL, words = words, strength = 40, ttlMs = 24 * 3_600_000L) }
        }
        return true
    }

    /** On Sunday or the first run after it: the week's consolidation. What it did, or null when not due. */
    internal suspend fun consolidateIfDue(): Consolidation.Done? {
        val today = now().toLocalDate()
        val sunday = Consolidation.due(today, consolidatedOn) ?: return null
        // S18: battery / heat budget; its CPU is counted when it ends ([BackgroundBudget.done], G6-9), however long it ran.
        try {
            if (!com.optionslab.app.work.BackgroundBudget.allow(com.optionslab.ira.BudgetRule.Job.CONSOLIDATION)) return null
            return consolidate(today, sunday)
        } finally {
            runCatching { com.optionslab.app.work.BackgroundBudget.done(com.optionslab.ira.BudgetRule.Job.CONSOLIDATION) }
        }
    }

    private suspend fun consolidate(today: LocalDate, sunday: LocalDate): Consolidation.Done? {
        val first = consolidatedOn == null
        consolidatedOn = sunday
        // The very first run on this phone only sets the week (nothing kept yet to tidy; the first consolidation is next Sunday).
        if (first) { withContext(Dispatchers.IO) { saveWeeks() }; return null }
        val done = withContext(Dispatchers.IO) {
            val merged = runCatching { JarvisLearning.consolidate { Consolidation.lessons(it) } }.getOrDefault(0)
            val compacted = runCatching { compactFindings(today) }.getOrDefault(0)
            val trimmed = runCatching { trimRecordings(today) }.getOrDefault(0)
            val week = sunday.minusDays(6)
            val w = summary(week)
            synchronized(lock) { _weeks.value = GrowthReport.keep(_weeks.value, w) }
            saveWeeks()
            Consolidation.Done(merged, compacted, trimmed, week)
        }
        runCatching { Diag.record("jarvis", done.words()) }
        runCatching { com.optionslab.app.data.SmartWorkers.found("Jarvis's memory", Findings.Kind.OTHER, Findings.ALL, words = done.words(), strength = 30, ttlMs = 24 * 3_600_000L) }
        return done
    }

    /** The findings logs older than a week compacted in place (their data-health lines kept whole). How many changed. */
    private fun compactFindings(today: LocalDate): Int {
        val d = appContext?.let { File(it.noBackupFilesDir, "findings") } ?: return 0
        var n = 0
        for (f in d.listFiles().orEmpty().sortedBy { it.name }) runCatching {
            val day = LocalDate.parse(f.name.removeSuffix(".log"))
            if (!Consolidation.compactDue(day, today)) return@runCatching
            val lines = f.readLines()
            val out = Consolidation.compact(lines)
            if (out == lines) return@runCatching
            val tmp = File(d, f.name + ".tmp")
            tmp.writeText(out.joinToString("\n") + "\n")
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            n++
        }
        return n
    }

    /** Old recordings beyond their caps: the findings logs (30 days, 20 MB), the big moves (12 months, 1.5 GB; a goal's index last). */
    private fun trimRecordings(today: LocalDate): Int {
        var n = 0
        val fd = appContext?.let { File(it.noBackupFilesDir, "findings") }
        val logs = fd?.listFiles().orEmpty().mapNotNull { f -> runCatching { Consolidation.Rec(f.name, LocalDate.parse(f.name.removeSuffix(".log")), f.length()) }.getOrNull() }
        for (name in Consolidation.trim(logs, Consolidation.FINDINGS_KEEP_DAYS, Consolidation.FINDINGS_MAX_BYTES, today)) if (File(fd, name).delete()) n++
        val moves = runCatching { com.optionslab.app.data.MoveRecorder.files() }.getOrDefault(emptyList())
        val recs = moves.map { (named, f) -> Consolidation.Rec(f.name, named.day, f.length(), named.name) }
        val byName = moves.associate { it.second.name to it.second }
        for (name in Consolidation.trim(recs, MoveEvents.KEEP_DAYS, MoveEvents.BUDGET_BYTES, today, plan.indices)) if (byName[name]?.delete() == true) n++
        return n
    }

    /** The week of [monday] from the records as they are now. */
    private fun summary(monday: LocalDate): GrowthReport.Week {
        runCatching { JarvisLearning.ensureLoaded() }
        runCatching { Autopilot.ensureLoaded() }
        AppHealth.ensureLoaded()
        return GrowthReport.summarize(monday, JarvisLearning.book.value, Autopilot.book.value, AppHealth.book(), _goals.value)
    }

    /**
     * Saturday morning, or the first run after Friday ends: the week's report, once - kept, notified. The week, or null. The
     * first run on a phone only notes the week due (no report of a week the app did not watch).
     */
    internal suspend fun reportIfDue(context: Context?): GrowthReport.Week? {
        val at = now()
        if (!GrowthReport.due(at, reportedWeek)) return null
        val monday = GrowthReport.dueWeek(at)
        val first = reportedWeek == null
        reportedWeek = monday
        // The very first run on this phone only sets the week: the app did not watch that week, so there is nothing to report.
        if (first) { withContext(Dispatchers.IO) { saveWeeks() }; return null }
        val w = withContext(Dispatchers.IO) {
            val w = summary(monday)
            synchronized(lock) { _weeks.value = GrowthReport.keep(_weeks.value, w) }
            saveWeeks()
            w
        }
        runCatching { Diag.record("jarvis", "weekly self-report: ${GrowthReport.title(monday)}") }
        if (context != null) runCatching {
            com.optionslab.app.work.Notifier.post(context, NOTICE_ID, com.optionslab.app.work.Notifier.IRA, "Jarvis's weekly report · ${GrowthReport.title(monday)}",
                GrowthReport.notice(w), "almanac")
        }
        return w
    }

    // ---- Jarvis ----------------------------------------------------------------------------------------------------------

    enum class Ask { HEALTHY, SLOW, GOALS, WEEK }

    /** "Is the app healthy?", "why was the app slow?", "what are you trying to learn?", "how did you grow this week?", else null. */
    fun asked(text: String): Ask? {
        when (runCatching { HealthBook.asked(text) }.getOrNull()) {
            HealthBook.Ask.HEALTHY -> return Ask.HEALTHY
            HealthBook.Ask.SLOW -> return Ask.SLOW
            null -> {}
        }
        if (!enabled()) return null
        if (LearningGoals.asked(text)) return Ask.GOALS
        if (GrowthReport.asked(text)) return Ask.WEEK
        return null
    }

    /** The answer from memory (the files were read at start). [locked]: the phone is locked - no bot's rupees (G6-1). */
    fun answer(a: Ask, locked: Boolean = false): String = when (a) {
        Ask.HEALTHY -> AppHealth.sayHealthy()
        Ask.SLOW -> AppHealth.sayWhySlow()
        Ask.GOALS -> { ensureLoaded(); LearningGoals.say(_goals.value, locked) }
        Ask.WEEK -> { ensureLoaded(); GrowthReport.say(_weeks.value.lastOrNull(), locked) }
    }

    /** TEST ONLY: nothing kept. */
    internal fun resetForTest() {
        synchronized(lock) { _goals.value = LearningGoals.Book(); _weeks.value = emptyList(); plan = LearningGoals.RecordingPlan() }
        consolidatedOn = null; reportedWeek = null; loaded = true; testNow = null; running.set(false)
    }

    /** TEST ONLY: [g] and [w] as kept. */
    internal fun setForTest(g: LearningGoals.Book = _goals.value, w: List<GrowthReport.Week> = _weeks.value) {
        synchronized(lock) { setGoals(g); _weeks.value = w }; loaded = true
    }
}
