package com.optionslab.app.data

import android.content.Context
import com.optionslab.ira.OpenProblems
import com.optionslab.ira.PolicyCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "What I missed / problems found" on the phone (Boss, 12 Oct: "find out why all my agents were not able to find these
 * issues"; the board is ira-core's [OpenProblems]). Any agent reports a problem here ([report]) - the order review, the app's
 * health, the session coverage check, the policy check - and the Automation card shows the open ones, most serious first, in
 * a few lines with "Show details" for the rest, so Boss sees them without reading the diagnostics.
 *
 *  - [report] / [clear] are memory steps (never a file or the settings vault on the caller's thread); the board is written to
 *    noBackupFilesDir/problems/board.txt a minute later on an IO thread (never a backup, never the network).
 *  - A problem marked [report]'s `notify` (a gap in the market watch, an armed thing a standing decision forbids) also posts
 *    one notification a day per problem: words only, no rupees and no account figures (it may show on a lock screen).
 *  - [policySweep]: what is armed against the standing decisions ([PolicyCheck]), from memory only (the arms' hints, the
 *    Pine scripts in memory); the morning check runs the same check for its failing lines.
 *
 * Nothing here places, changes or cancels an order, or switches anything off: it only tells. Not in IraGoldAlgo.
 */
object ProblemsHost {
    private const val DIR = "problems"
    private const val FILE = "board.txt"
    /** The notification's id (the health channel). */
    const val NOTICE_ID = 5190
    private const val SAVE_DELAY_MS = 60_000L
    /** The policy sweep at most this often (memory reads only, but no need to run each look). */
    const val SWEEP_MS = 10 * 60_000L

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var loaded = false
    private val saving = AtomicBoolean(false)
    @Volatile private var sweptAt = 0L
    /** "key|day" already notified (memory; a restart may tell once more that day, never more often). */
    private val told = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val _board = MutableStateFlow(OpenProblems.Board())
    /** The board (the Automation card reads it). */
    val board: StateFlow<OpenProblems.Board> = _board

    fun enabled(): Boolean = !com.optionslab.app.BuildConfig.GOLD

    fun init(context: Context) {
        appContext = context.applicationContext
        if (enabled()) scope.launch { com.optionslab.app.data.PositionsFirst.awaitPositions(); runCatching { ensureLoaded() } }
    }

    private fun file(): File? = appContext?.let { File(File(it.noBackupFilesDir, DIR), FILE) }

    /** The kept board read once (a worker thread); what was reported before it was read is kept on top of it. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val f = file() ?: return
            val read = runCatching { if (f.isFile) f.readText() else null }
            if (read.isFailure) return
            var b = OpenProblems.decode(read.getOrNull())
            for (p in _board.value.items) b = OpenProblems.Board((b.items.filter { it.key != p.key } + p).sortedBy { it.lastMs }.takeLast(OpenProblems.MAX))
            _board.value = b
            loaded = true
        }
    }

    private fun saveSoon() {
        if (appContext == null || !saving.compareAndSet(false, true)) return
        scope.launch {
            try { delay(SAVE_DELAY_MS); saveNow() } finally { saving.set(false) }
        }
    }

    /** Written now (a worker thread). */
    fun saveNow() {
        val f = file() ?: return
        if (!loaded) runCatching { ensureLoaded() }
        if (!loaded) return
        runCatching {
            val text = synchronized(lock) { OpenProblems.encode(_board.value) }
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    /**
     * [agent] found [text] ([key]: the same thing keeps one line; [fix]: what to do). [notify]: also one notification a day
     * for it - words without rupees only. Memory now, the file a minute later. Never throws.
     */
    fun report(key: String, agent: String, severity: OpenProblems.Severity, text: String, fix: String? = null, notify: Boolean = false) {
        if (!enabled()) return
        runCatching {
            val t = now()
            val isNew = synchronized(lock) {
                val was = _board.value.items.any { it.key == key && t - it.lastMs in 0..OpenProblems.OPEN_MS }
                _board.value = OpenProblems.report(_board.value, OpenProblems.Problem(key, agent, severity, text, fix), t)
                !was
            }
            if (isNew) runCatching { Diag.record("problems", "${severity.word}: $agent: $text") }
            if (notify) notifyOnce(key, agent, text)
            saveSoon()
        }
    }

    private fun notifyOnce(key: String, agent: String, text: String) {
        val ctx = appContext ?: return
        val day = runCatching { Market.today().toString() }.getOrDefault("")
        if (!told.add("$key|$day")) return
        runCatching { com.optionslab.app.work.Notifier.post(ctx, NOTICE_ID + (key.hashCode() and 7), com.optionslab.app.work.Notifier.HEALTH,
            "Problem found: $agent", text.take(240), "health") }
    }

    /** [key]'s problem is over. */
    fun clear(key: String) {
        if (!enabled()) return
        val changed = synchronized(lock) {
            val b = OpenProblems.clear(_board.value, key)
            (b != _board.value).also { _board.value = b }
        }
        if (changed) saveSoon()
    }

    /** An agent's sweep found only [keep] among its problems ([prefix]): the others are over. */
    fun clearOthers(prefix: String, keep: Set<String>) {
        if (!enabled()) return
        val changed = synchronized(lock) {
            val b = OpenProblems.clearOthers(_board.value, prefix, keep)
            (b != _board.value).also { _board.value = b }
        }
        if (changed) saveSoon()
    }

    /** The open problems now, the most serious first. */
    fun open(b: OpenProblems.Board = _board.value, nowMs: Long = now()): List<OpenProblems.Problem> = OpenProblems.open(b, nowMs)

    // ---- the policy check -----------------------------------------------------------------------------------------------

    /**
     * What is armed now, from memory only: the ORB family's armed arms ([OrbArms.armedLabelsHint]) and the Pine scripts switched
     * on ([PineScripts.items]); [live] whether the app trades live (orders to Zerodha).
     */
    fun armedNow(live: Boolean): List<PolicyCheck.Armed> {
        val out = ArrayList<PolicyCheck.Armed>()
        runCatching { OrbArms.armedLabelsHint }.getOrDefault(emptyList()).forEach { out += PolicyCheck.Armed(it, live = live) }
        runCatching { PineScripts.items.value }.getOrDefault(emptyList()).filter { it.auto.on }.forEach { p ->
            out += PolicyCheck.Armed(p.name, p.auto.symbol, trades = p.auto.mode == "trade", live = live, kind = "Pine script")
        }
        return out
    }

    /** The policy check now: each violation a problem (told once a day), the ones gone cleared. At most every [SWEEP_MS] unless [force]. */
    fun policySweep(live: Boolean, force: Boolean = false): List<PolicyCheck.Violation> {
        if (!enabled()) return emptyList()
        val t = now()
        if (!force && t - sweptAt < SWEEP_MS) return emptyList()
        sweptAt = t
        val v = runCatching { PolicyCheck.check(armedNow(live)) }.getOrDefault(emptyList())
        for (x in v) report(x.key, "Policy check", x.severity, x.text, x.fix, notify = x.severity == OpenProblems.Severity.SERIOUS)
        clearOthers("policy:", v.map { it.key }.toSet())
        return v
    }

    /** The diagnostics' lines: the open problems (words only). */
    fun diagLines(): List<String> {
        val open = open()
        return listOf("Problems found: " + if (open.isEmpty()) "none open" else "${open.size} open") + OpenProblems.lines(open).map { "  $it" }
    }

    /** TEST ONLY: nothing kept, the file read (as empty). */
    internal fun resetForTest() {
        synchronized(lock) { _board.value = OpenProblems.Board() }
        loaded = true; sweptAt = 0L; told.clear()
    }
}
