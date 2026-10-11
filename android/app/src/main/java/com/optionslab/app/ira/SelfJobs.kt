package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.Diag
import com.optionslab.app.data.Market
import com.optionslab.app.data.MoveRecorder
import com.optionslab.app.data.TradeManagerHost
import com.optionslab.ira.AutoScorecard
import com.optionslab.ira.DailyLesson
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The roadmap's background jobs (S14, S15, S20, S21), run from the brain's worker ([tick]: memory checks until each is due;
 * never the main thread, never the order path):
 *
 *  - the learning freeze worked out again when stale ([LearningFreeze.holds] starts it);
 *  - the health tuner's change days kept (the freeze and the monthly scorecard count them);
 *  - after F&O's close, once a trading day: today's lesson ([DailyLesson]) and each closed bot trade's option minutes kept
 *    for its replay ([BotReplayStore]);
 *  - from the 1st of a month: the month before's automation scorecard ([MonthlyScorecard]), a sheet and one notification.
 *
 * Its small files live in noBackupFilesDir/self (never a backup); today's lesson there is sealed with the vault. Not in IraGoldAlgo.
 */
object SelfJobs {
    private const val DIR = "self"
    private const val MARKS = "jobs.txt"
    private const val HEALTH_DAYS = "health-days.txt"
    private const val LESSON = "lesson.txt"
    private const val CLOSE = 15 * 60 + 45

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    @Volatile private var marks: Map<String, String>? = null
    @Volatile private var healthDays: Set<LocalDate>? = null

    private val _lesson = MutableStateFlow<DailyLesson.Lesson?>(null)
    /** The newest lesson (the Ira page's card shows it on its own day). */
    val lesson: StateFlow<DailyLesson.Lesson?> = _lesson

    fun enabled(): Boolean = Autopilot.enabled()

    fun init(context: Context) {
        appContext = context.applicationContext
        LearningFreeze.init(context)
        BossTradeLog.init(context)
        MonthlyScorecard.init(context)
        com.optionslab.app.work.BackgroundBudget.init(context)
        if (enabled()) scope.launch { runCatching { _lesson.value = readLesson() } }
    }

    internal fun dir(): File? = appContext?.let { File(it.noBackupFilesDir, DIR) }

    private fun write(name: String, text: String) {
        val d = dir() ?: return
        runCatching {
            d.mkdirs()
            val f = File(d, name); val tmp = File(d, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /**
     * Today's lesson is sealed with the [com.optionslab.app.security.Vault] (it can carry Boss's own habit and a bot's rupees;
     * review R5 #4). A file that does not open (an older plain one) is dropped, never read as plain text.
     */
    private fun readLesson(): DailyLesson.Lesson? {
        val f = dir()?.let { File(it, LESSON) }?.takeIf { it.isFile } ?: return null
        val plain = runCatching { com.optionslab.app.security.Vault.readFile(f) }.getOrNull()
        if (plain == null) { runCatching { f.delete() }; return null }
        return DailyLesson.decode(String(plain, Charsets.UTF_8))
    }

    private fun writeLesson(text: String) {
        val d = dir() ?: return
        runCatching { com.optionslab.app.security.Vault.writeFile(File(d, LESSON), text.toByteArray(Charsets.UTF_8)) }
    }

    private fun marks(): Map<String, String> = marks ?: runCatching {
        dir()?.let { File(it, MARKS) }?.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }.getOrDefault(emptyMap()).also { marks = it }

    private fun mark(k: String, v: String) {
        val m = marks() + (k to v)
        marks = m
        write(MARKS, m.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    /** The days the health tuner changed a cadence (kept 120 days). */
    fun healthDays(): Set<LocalDate> = healthDays ?: runCatching {
        dir()?.let { File(it, HEALTH_DAYS) }?.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }.toSet()
    }.getOrDefault(emptySet()).also { healthDays = it }

    private fun keepHealthDays(today: LocalDate) {
        val stepped = runCatching { com.optionslab.app.data.AppHealth.book().tuned.values.map { it.stepOn } }.getOrDefault(emptyList())
        val now = healthDays()
        val next = (now + stepped).filter { !it.isBefore(today.minusDays(120)) }.toSet()
        if (next != now) { healthDays = next; write(HEALTH_DAYS, next.sorted().joinToString("\n")) }
    }

    /** One look (the brain's worker, every few seconds): memory checks until a job is due; the jobs run in the background. */
    fun tick(context: Context? = null) {
        if (!enabled()) return
        if (context != null && appContext == null) init(context)
        LearningFreeze.holds()
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try { runCatching { due() } } finally { running.set(false) }
        }
    }

    private suspend fun due() {
        val today = Market.today()
        val m = marks()
        if (m["health"] != today.toString()) { keepHealthDays(today); mark("health", today.toString()) }
        if (Market.isTradingDay(today) && Market.minuteNow() >= CLOSE && m["evening"] != today.toString()) {
            mark("evening", today.toString())
            runCatching { BotReplayStore.keepToday(today) }
            runCatching { buildLesson(today) }
        }
        val lastFor = m["scorecard"]?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        if (AutoScorecard.due(today, lastFor)) {
            val month = YearMonth.from(today).minusMonths(1)
            mark("scorecard", month.toString())
            // A first install has no month before to score: noted, never notified.
            if (lastFor != null || today.dayOfMonth == 1) runCatching { MonthlyScorecard.make(month, notify = true) }
        }
    }

    // ---- today's lesson ----------------------------------------------------------------------------------------------------

    /** Today's lesson from today's records (a worker thread); kept, and null when nothing qualifies. */
    fun buildLesson(today: LocalDate): DailyLesson.Lesson? {
        val moves = runCatching {
            MoveRecorder.files().filter { it.first.day == today && it.first.kind != com.optionslab.ira.MoveEvents.Kind.CONTROL }
                .mapNotNull { MoveRecorder.summary(it.second) }
                .map { s -> DailyLesson.Move(s.name, s.startSec, s.retBp, s.x, s.first) }
        }.getOrDefault(emptyList())
        val bots = TradeManagerHost.records.value.filter { r -> r.actual?.let { com.optionslab.ira.AgentLessons.dayOf(it.atMs) == today } == true }.map { r ->
            val a = r.actual!!
            DailyLesson.BotTrade(r.trade.label, (a.price - r.trade.entry) * r.trade.qty, r.vsOriginal, a.why, r.original?.why, r.mode == TradeManager.Mode.ACT)
        }
        val habit = runCatching { BossTradeLog.todayInsight(today) }.getOrNull()
        val l = DailyLesson.of(today, moves, bots, habit)
        if (l != null) {
            _lesson.value = l
            writeLesson(DailyLesson.encode(l))
            runCatching { Diag.record("lesson", "today's lesson: ${l.kind.words}") }
        }
        return l
    }

    /** "What's today's lesson?" (a worker thread). */
    fun answerLesson(locked: Boolean = false): String {
        val today = Market.today()
        val closed = !Market.isTradingDay(today) || Market.minuteNow() >= CLOSE
        val l = _lesson.value?.takeIf { it.day == today } ?: if (closed && Market.isTradingDay(today)) buildLesson(today) else null
        return DailyLesson.say(l, closed, locked)
    }

    /** TEST ONLY: nothing kept. */
    internal fun resetForTest() { marks = emptyMap(); healthDays = emptySet(); _lesson.value = null; running.set(false) }

    /** TEST ONLY: [l] as the lesson. */
    internal fun setLessonForTest(l: DailyLesson.Lesson?) { _lesson.value = l }
}
