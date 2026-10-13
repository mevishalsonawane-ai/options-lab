package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.FindingsTally
import com.optionslab.ira.MemoryPlan
import com.optionslab.ira.StartOrder
import com.optionslab.ira.LessonsBundle
import com.optionslab.ira.SharedKnowledge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shared knowledge on the phone (Boss, 13 Oct: "all the agents ... learn from whatever findings we have, and vice versa").
 * ira-core's [SharedKnowledge] merges the research (the knowledge pack's shared section), Jarvis's lessons and the recent
 * findings into one [SharedKnowledge.Snapshot]; this keeps it:
 *
 *  - Built once in the background after the start ([init]: Boss, 14 Oct - only once the open positions are restored and
 *    watched and the stream is back ([PositionsFirst]), on one low-priority thread; in market hours under memory pressure or
 *    battery saver it waits for the close) and again each night after Jarvis's pass ([nightly]). The pack's shared section
 *    comes from the head of the APK's prebuilt index ([com.optionslab.app.ira.KnowledgeLibrary.shared]: the index itself is
 *    not opened for it); the findings logs are counted line by line, never kept ([FindingsTally]); each night the counts of
 *    the days before today go into one small encrypted snapshot ([SNAPSHOT]), so the next morning counts only the new logs.
 *    Every agent reads it with [snapshot]: a volatile field, memory only - nothing on the order or tick path ever touches the
 *    disk, the settings vault or the network through here, and nothing waits. Before the first build it is [SharedKnowledge.EMPTY] (no opinion).
 *  - What each agent read from it and added today ([read], [added]; a small map in memory): the "What I learned" sheet's lines
 *    ([agentLines]).
 *  - Nightly, under the recordings folder: the day's lessons bundle ([LessonsBundle], recordings/YYYY-MM-DD/lessons_bundle.json):
 *    rates and counts in the clear, Boss's money only encrypted with the app's [Vault] ("money_enc") - the PIN-confirmed
 *    "Export recordings" decrypts it ([LiveRecorder.exportDecrypted]). The future G1 uploader sends the file as stored
 *    ([bundleFiles] is its hook).
 *  - The one new shadow rule ([SharedKnowledge.shadowSkips], "the research says this strategy does not pay"): counted at night
 *    from the day's closed trips, logged only - and only when Boss turns [shadowOn] on (off by default). It never skips, sizes
 *    or touches an order; Liquidity is never part of it (frozen to 26 Oct).
 *
 * Reading knowledge only explains, ranks or (in shadow) would lower risk. Not in IraGoldAlgo.
 */
object SharedKnowledgeHost {
    private const val K_SHADOW = "knowledge.shadow"
    /** Findings log lines read at most per night (the logs can be large; a count needs no more). */
    private const val MAX_FINDING_LINES = 200_000
    private const val FINDING_DAYS = 30L
    /** The findings counts of the days before the night it was written (encrypted with the app's Vault; counts only). */
    const val SNAPSHOT = "findings_snapshot.vault"

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val building = AtomicBoolean(false)

    @Volatile private var current: SharedKnowledge.Snapshot = SharedKnowledge.EMPTY
    /** The shared knowledge now (memory only; [SharedKnowledge.EMPTY] until the first build). Safe from any thread. */
    val snapshot: SharedKnowledge.Snapshot get() = current

    @Volatile private var nightOf: LocalDate? = null
    @Volatile private var shadowKept: Boolean? = null
    /** Tonight's forge ideas the shared knowledge kept from being tested again (for the bundle). */
    private val skippedIdeas = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    /** One line per agent: what it read from the shared knowledge and what it added today (the "What I learned" sheet). */
    val lines: StateFlow<List<String>> = _lines

    fun enabled(): Boolean = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD

    fun init(context: Context) {
        appContext = context.applicationContext
        if (enabled()) background.launch { runCatching { afterStart() } }
    }

    /** The first build's own thread: one at a time, at the background's priority (never main, never the tick or order threads). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /**
     * The first build, after the start (Boss, 14 Oct): once the open positions are watched and the stream is back, and - in
     * NSE's or MCX's hours - not while the phone is under memory pressure or in battery saver (then a look each minute, so it
     * runs after the close). Answers that need it before then say they are still loading; nothing waits on it.
     */
    internal suspend fun afterStart() {
        PositionsFirst.awaitPositions()
        PositionsFirst.awaitStream()
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        while (!loadNow()) kotlinx.coroutines.delay(60_000L)
        refresh()
        PositionsFirst.order.done(StartOrder.Step.KNOWLEDGE, android.os.SystemClock.uptimeMillis())
    }

    private fun loadNow(): Boolean {
        val market = runCatching { Market.isOpen() || McxMarket.isOpen() }.getOrDefault(true)
        val saver = runCatching { appContext?.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)
        return MemoryPlan.knowledgeNow(market, MemoryTrim.underPressure(), saver)
    }

    // ---- the switch ----------------------------------------------------------------------------------------------------------

    /**
     * Boss's switch for the shadow rule (off by default): when on, the trades the research's verdict would have skipped are
     * counted each night - a log line and the bundle, never a skip. Read off the main thread (the first read is the settings').
     */
    var shadowOn: Boolean
        get() = shadowKept ?: runCatching { SecurePrefs.getBoolean(K_SHADOW, false) }.getOrDefault(false).also { shadowKept = it }
        set(v) {
            shadowKept = v
            runCatching { SecurePrefs.putAllSoon(mapOf(K_SHADOW to v)) }
            runCatching { Diag.record("knowledge", "shadow rule \"the research says it does not pay\" ${if (v) "on (logs only)" else "off"} (by you)") }
        }

    /** The switch as kept in memory (false until read): for screens, never reads the settings. */
    fun shadowInMemory(): Boolean = shadowKept ?: false

    // ---- the build -----------------------------------------------------------------------------------------------------------

    /** The snapshot built again from the pack, Jarvis's lessons and the findings logs. A worker thread; one at a time. */
    fun refresh(day: LocalDate? = runCatching { Market.today() }.getOrNull(), writeSnapshot: Boolean = false): SharedKnowledge.Snapshot? {
        if (!building.compareAndSet(false, true)) return null
        try {
            val ctx = appContext
            val shared = runCatching { com.optionslab.app.ira.KnowledgeLibrary.shared(ctx) }.getOrNull()
            runCatching { com.optionslab.app.ira.JarvisLearning.ensureLoaded() }
            val book = runCatching { com.optionslab.app.ira.JarvisLearning.book.value }.getOrNull()
            val tally = runCatching { findingsRecent(day) }.getOrDefault(FindingsTally())
            val s = SharedKnowledge.build(shared?.first, book, tally, day, shared?.second.orEmpty())
            current = s
            lastCounts = LessonsBundle.counts(tally)
            if (writeSnapshot && day != null) runCatching { writeSnapshot(tally, day) }
            runCatching { Diag.record("knowledge", "shared knowledge built: ${s.summary()}" + if (s.conflicts.isNotEmpty()) "; ${s.conflicts.size} conflict(s) kept" else "") }
            publish()
            return s
        } finally { building.set(false) }
    }

    /** The last build's findings counts by kind and agent (the night's bundle; a few dozen rows). Dropped on a memory trim. */
    @Volatile private var lastCounts: List<LessonsBundle.FindingCount> = emptyList()

    /** Memory trim: the counts kept for the bundle let go (the night's build counts again before it writes). */
    fun dropCounts() { lastCounts = emptyList() }

    /**
     * The findings of the last [FINDING_DAYS] days counted (memory audit, 14 Oct: no finding is kept as an object): the
     * snapshot's days, then only the logs (noBackupFilesDir/findings) after its last day, line by line up to a cap. Without a
     * snapshot (the first night, a damaged file) every log in the window is counted, as before.
     */
    private fun findingsRecent(day: LocalDate?): FindingsTally {
        val ctx = appContext ?: return FindingsTally()
        val from = (day ?: LocalDate.now()).minusDays(FINDING_DAYS)
        val snap = runCatching { readSnapshot(ctx) }.getOrNull()
        return countLogs(File(ctx.noBackupFilesDir, "findings"), from, snap)
    }

    /** [dir]'s logs from [from] on that come after [snap]'s last day, newest first, counted; then [snap]'s days from [from]. */
    internal fun countLogs(dir: File, from: LocalDate, snap: Pair<LocalDate, FindingsTally>?): FindingsTally {
        val out = FindingsTally()
        var lines = 0
        for (f in dir.listFiles().orEmpty().sortedByDescending { it.name }) {
            val d = runCatching { LocalDate.parse(f.name.removeSuffix(".log")) }.getOrNull() ?: continue
            if (d.isBefore(from)) continue
            if (snap != null && !d.isAfter(snap.first)) continue
            runCatching { f.useLines { ls -> for (l in ls) { if (++lines > MAX_FINDING_LINES) break; out.line(l) } } }
            if (lines > MAX_FINDING_LINES) break
        }
        snap?.let { out.addAll(it.second.since(from)) }
        return out
    }

    private fun snapshotFile(ctx: Context) = File(ctx.noBackupFilesDir, SNAPSHOT)

    private fun readSnapshot(ctx: Context): Pair<LocalDate, FindingsTally>? =
        Vault.readFile(snapshotFile(ctx))?.let { FindingsTally.read(String(it, Charsets.UTF_8)) }

    /** Tonight's snapshot: the counts of the days before [today] (today's log is counted whole again tomorrow). */
    private fun writeSnapshot(tally: FindingsTally, today: LocalDate) {
        val ctx = appContext ?: return
        Vault.writeFile(snapshotFile(ctx), tally.before(today).text(today.minusDays(1)).toByteArray(Charsets.UTF_8))
    }

    // ---- what each agent read and added ---------------------------------------------------------------------------------------

    // 13 Oct (review): the counters are atomic and a day's tally is made once (compute), so two agents counting at the same
    // moment from different threads never lose a count.
    private class Tally(val day: LocalDate) {
        val reads = AtomicInteger()
        val adds = AtomicInteger()
        @Volatile var readWhat: String = ""
        @Volatile var addWhat: String = ""
    }

    private val tallies = ConcurrentHashMap<String, Tally>()

    private fun tally(agent: String): Tally {
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        return tallies.compute(agent) { _, t -> if (t != null && t.day == today) t else Tally(today) }!!
    }

    /** [agent] read [what] from the shared knowledge (memory only: a map update; never throws). */
    fun read(agent: String, what: String) {
        runCatching { val t = tally(agent); t.reads.incrementAndGet(); t.readWhat = what.take(140) }
    }

    /** [agent] added [what] to Jarvis's lessons or the research bundle today (memory only). */
    fun added(agent: String, what: String) {
        runCatching { val t = tally(agent); t.adds.incrementAndGet(); t.addWhat = what.take(140) }
        publish()
    }

    /** Every agent's line for today, in a fixed order (the ones that did nothing say so). */
    fun agentLines(): List<String> {
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        return AGENTS.map { a ->
            val t = tallies[a]?.takeIf { it.day == today }
            val reads = t?.reads?.get() ?: 0
            val read = if (t == null || reads == 0) "read nothing yet today" else "read ${t.readWhat}" + if (reads > 1) " ($reads times)" else ""
            val add = if (t == null || t.adds.get() == 0) "added nothing yet" else "added ${t.addWhat}"
            "$a: $read; $add."
        }
    }

    /** The agents that read and write the shared knowledge, as the sheet names them. */
    val AGENTS = listOf("Jarvis", "Trade manager", "Order review", "Order flow", "Liquidity paper test", "Slippage record", "Market study", "Strategy lab")

    private fun publish() { runCatching { _lines.value = agentLines() } }

    // ---- the forge's skips (for the bundle) -----------------------------------------------------------------------------------

    /** The forge's night: the ideas it did not test again (kept in memory for tonight's bundle). */
    fun forgeSkipped(skipped: List<Pair<String, String>>) {
        synchronized(skippedIdeas) { skippedIdeas.clear(); skippedIdeas.addAll(skipped.take(100)) }
    }

    // ---- the night ------------------------------------------------------------------------------------------------------------

    /**
     * Once a trading day after Jarvis's pass (the brain's nightly worker): the snapshot rebuilt, the shadow rule counted when Boss
     * turned it on, and the day's lessons bundle written under the recordings folder. Never the main thread; never the network.
     * True when it ran.
     */
    suspend fun nightly(context: Context? = null): Boolean {
        if (!enabled()) return false
        if (context != null && appContext == null) appContext = context.applicationContext
        val today = runCatching { Market.today() }.getOrNull() ?: return false
        if (nightOf == today) return false
        if (!Market.isTradingDay(today) || Market.minuteNow() < 15 * 60 + 45) return false
        val s = refresh(today, writeSnapshot = true) ?: return false
        nightOf = today
        val trips = runCatching {
            val owners = Strategies.owners()
            com.optionslab.app.ira.IraAccount.trips(false, owners) + com.optionslab.app.ira.IraAccount.trips(true, owners)
        }.getOrDefault(emptyList())
        val shadow = if (shadowOn) runCatching { SharedKnowledge.shadowSkips(s, trips, today) }.getOrDefault(emptyList()) else emptyList()
        for (sk in shadow) runCatching { Diag.record("knowledge", "shadow (logs only): ${sk.why} - would have skipped ${sk.wouldSkip} of ${sk.trades} trades today; nothing was skipped") }
        runCatching { writeBundle(today, s, shadow) }
        return true
    }

    /** Today's bundle file (recordings/YYYY-MM-DD/lessons_bundle.json), or null without a recordings folder. */
    private fun bundleFile(day: LocalDate): File? = appContext?.let { File(File(File(it.noBackupFilesDir, "recordings"), day.toString()), LessonsBundle.FILE) }

    /** The bundle files kept (one a recorded day), oldest first: the hook the future G1 uploader reads (sent as stored, encrypted). */
    fun bundleFiles(): List<File> = runCatching { LiveRecorder.days().mapNotNull { (_, d) -> File(d, LessonsBundle.FILE).takeIf { it.isFile } } }.getOrDefault(emptyList())

    internal fun writeBundle(day: LocalDate, s: SharedKnowledge.Snapshot, shadow: List<SharedKnowledge.ShadowSkip>) {
        val f = bundleFile(day) ?: return
        val book = runCatching { com.optionslab.app.ira.JarvisLearning.book.value }.getOrDefault(AgentLessons.Book())
        val vr = VariantRunner
        val app = runCatching { com.optionslab.app.BuildConfig.VERSION_NAME }.getOrDefault("")
        val built = LessonsBundle.build(LessonsBundle.Inputs(day, app, s.version, book, lastCounts,
            runCatching { if (vr.enabled()) vr.forge.value else null }.getOrNull(), synchronized(skippedIdeas) { ArrayList(skippedIdeas) },
            runCatching { if (vr.enabled()) vr.variants.value else null }.getOrNull(), runCatching { OrderReviewHost.book.value }.getOrNull(),
            shadow, agentLines()))
        // Boss's money figures: encrypted with the app's Vault, as the live recorder keeps his trades.
        val enc = runCatching {
            android.util.Base64.encodeToString(Vault.encrypt(LessonsBundle.moneyJson(built).toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
        }.getOrNull()
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(LessonsBundle.text(built, enc), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        added("Jarvis", "tonight's lessons bundle for the research (${AgentLessons.active(book).size} lessons)")
        runCatching { Diag.record("knowledge", "lessons bundle for $day written (${f.length()} bytes; money encrypted)") }
    }

    /** The bundle as the PIN-confirmed export writes it: its money decrypted here on the phone (a gap note when it cannot be). */
    fun decryptedBundle(text: String): String {
        val enc = runCatching { (com.optionslab.engine.strategy.Json.parse(text) as? Map<*, *>)?.get("money_enc") as? String }.getOrNull()
        val money = enc?.let { e -> runCatching { String(Vault.decrypt(android.util.Base64.decode(e, android.util.Base64.NO_WRAP)), Charsets.UTF_8) }.getOrNull() }
        return LessonsBundle.decrypted(text, money)
    }

    /** The diagnostics' line. */
    fun diagLine(): String = "Shared knowledge: ${current.summary()}; ${current.conflicts.size} conflict(s) kept; shadow rule ${if (shadowInMemory()) "on (logs only)" else "off"}" +
        (nightOf?.let { "; last night $it" } ?: "")

    /** TEST ONLY: [context] as the app's, without the background build ([refresh] runs when the test calls it). */
    internal fun attachForTest(context: Context) { appContext = context.applicationContext }

    /** TEST ONLY: [s] as the shared knowledge, nothing tallied, the night not run. */
    internal fun setForTest(s: SharedKnowledge.Snapshot) { current = s; tallies.clear(); nightOf = null; shadowKept = null; publish() }

    /** TEST ONLY: as a fresh start. */
    internal fun resetForTest() { current = SharedKnowledge.EMPTY; tallies.clear(); nightOf = null; shadowKept = null; skippedIdeas.clear(); _lines.value = emptyList() }
}
