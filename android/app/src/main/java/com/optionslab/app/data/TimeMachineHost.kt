package com.optionslab.app.data

import android.content.Context
import com.optionslab.engine.Right
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.risk.TimeMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The time machine on every arm ticket (22 Oct 2026, borrowed from Jake Schincariol's day-trader skill, MIT; the maths is the
 * engine's [TimeMachine]): after an arm's entry (paper or live, hidden arms too) the entry is replayed 2,000 times by
 * bootstrap from the UNDERLYING's own past sessions kept on the phone - the harvested 1-minute index bars ([Store], never
 * pruned; NIFTY, BANKNIFTY and, on Jarvis builds, FINNIFTY and SENSEX), else the history keeper's last days
 * ([HistoryKeeper], about five sessions: futures, MCX) - at the same time-of-day slot ±2 bars, with the arm's own stop and
 * target in underlying points, the stop checked before the target inside a bar, and the arm's own rule re-run on those
 * sessions when it has one (the ORB family). Reported: target first %, stop first %, neither %, the break-even win rate,
 * the sample sizes and a one-word verdict (edge / coin flip / against), on the arm's ticket, in the Autopilot change
 * timeline, in the diagnostics, and said by Jarvis.
 *
 * SHADOW ONLY: it decides nothing - it never skips, resizes or delays a trade, paper or live. Off the order and tick path:
 * [afterEntry] only queues (one memory write) and starts a background coroutine on this object's own single low-priority
 * thread (never the watch's IO pool); the bars are read from the store there, after the entry, never during a decision;
 * nothing here runs on the position watch. The bars come ONLY from what the phone already holds on disk (the harvested
 * sessions bundled with the app or saved by the recorder, else the history keeper's days): nothing here ever opens a network
 * connection (25 Oct, CI: a test showed the arms' entries reaching the feed's host after a test's fake was closed; the read is
 * now one streaming pass over the saved days, on this thread, and [TimeMachineHostTest] proves no host is asked). With no
 * saved sessions it reports "no sessions saved yet" and does nothing else. A premium stop or target is converted with the
 * delta the arm has, else said as "premium stops: odds on the underlying move only" with the at-the-money delta 0.5 assumed.
 *
 * A forward log (date, arm, ref, verdict, odds, and the trade's actual outcome once its arm reports it) is kept in the app's
 * encrypted store (noBackupFilesDir/self/timemachine.vault), so research can judge the verdicts after 30 signal days.
 */
object TimeMachineHost {
    private const val FILE = "timemachine.vault"
    /** The finding's author on the bus (the trade manager never brings a look forward for it: words only, no read). */
    const val WHO = "time machine"
    private const val KEEP = 600

    /**
     * 28 Oct (research R120, research/HUNT_R120_TIME_MACHINE_GATE.md): worked out before each entry on 14 arms' backtests, its
     * odds did not predict which trades won (correlation 0.04 over 16,810 trades) and no gate built on it passed. Said with
     * every verdict shown, so it is read as background, never as a signal.
     */
    const val UNPROVEN = "Not a proven signal: in a backtest of 16,810 trades its odds did not predict wins (R120)."

    /**
     * 28 Oct (R120): arms whose verdict is not shown on the ticket, to Jarvis or in the timeline. The Hero Rs 1,000 ticket's stop
     * (25% of a Rs 5-30 premium) is a few index points, so the replay calls most entries "against" whatever happens (255 of
     * 355 in the backtest). Still checked and kept in the forward log for research; only the display is hidden.
     */
    val HIDDEN_ARMS: Set<String> = setOf(com.optionslab.engine.orb.HeroTicketRules.SOURCE)

    /** The timeline's opening words: the note comes first, so a long arm label can never cut it off (the timeline shows 160 characters). */
    const val NOTE_SHORT = "Time machine (not a proven signal, R120)"

    /** True when [arm]'s verdict is kept out of sight ([HIDDEN_ARMS]). */
    fun hidden(arm: String): Boolean = arm in HIDDEN_ARMS

    /** What an arm hands over right after its entry. */
    data class Entry(
        val arm: String, val label: String,
        /** The arm's own reference for the trade (what its ticket and its live mirror carry). */
        val ref: String,
        val underlying: String,
        /** +1 the underlying must rise (a call bought), -1 fall (a put bought). */
        val direction: Int,
        val at: LocalDateTime,
        /** The stop and target in UNDERLYING points (null: none), or in premium points with [premiumDelta] to convert them. */
        val stopPoints: Double?, val targetPoints: Double?,
        val premium: Boolean = false, val premiumDelta: Double? = null,
        /** The arm's own time exit (null: the session's end). */
        val exitAt: LocalTime?,
        val barMinutes: Int = 5,
        val live: Boolean = false,
        /** The reason no replay is possible (an overnight arm): kept as a note, no odds. */
        val skip: String? = null,
    )

    /** A finished check. */
    data class Result(val entry: Entry, val odds: TimeMachine.Odds?, val sessionsSource: String, val note: String?, val atMs: Long, val day: LocalDate) {
        /** The one line for the ticket, the timeline, the diagnostics and Jarvis. */
        fun line(): String {
            val head = "${entry.label}: "
            val body = odds?.line() ?: "Time machine (unknown): ${note ?: "no replay"}"
            val tail = listOfNotNull(note.takeIf { odds != null }, sessionsSource.takeIf { odds != null && it.isNotEmpty() }).joinToString("; ").let { if (it.isEmpty()) "" else " [$it]" }
            return head + body + tail
        }
        val verdict: String get() = odds?.verdict?.word ?: "unknown"
    }

    /** One line of the forward log: the check and, once reported, the trade's actual outcome. */
    data class Record(val day: LocalDate, val arm: String, val ref: String, val verdict: String, val odds: String, val outcome: String?, val live: Boolean)

    /** Where the bars come from (replaceable in tests): the sessions of [underlying] before [today], and a word for the source. */
    fun interface Bars { fun sessions(underlying: String, today: LocalDate, barMinutes: Int): Pair<List<TimeMachine.Session>, String> }

    @Volatile private var appContext: Context? = null
    /** One daemon thread at the lowest priority: a check never competes with the position watch or the order path. */
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { r ->
        Thread(r, "time-machine").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }.asCoroutineDispatcher())
    /** Checks queued or running (memory): the tests wait for zero before the fake feed closes. */
    private val inFlight = AtomicInteger()
    private val io = Any()
    /** 26 Oct: bumped by [wipe] (under [io]): a check or a settle begun before an erase never keeps or writes its record. */
    @Volatile private var wipeGen = 0
    @Volatile private var loaded = false
    @Volatile private var records: List<Record> = emptyList()
    private val results = ConcurrentHashMap<String, Result>()
    private val _latest = MutableStateFlow<List<Result>>(emptyList())
    /** The checks of this process, newest first (the tickets and Jarvis read it; memory only). */
    val latest: StateFlow<List<Result>> = _latest
    /** TEST: a check's completion, to wait on. */
    @Volatile internal var testDone: ((Result) -> Unit)? = null

    /** The store's bars (the default [Bars]): the harvested index sessions first, else the history keeper's days. */
    private val storeBars = Bars { u, today, minutes -> storeSessions(u, today, minutes) }
    @Volatile internal var bars: Bars = storeBars
    /** TEST: the store's own read (TestApp hands the other tests an empty phone). */
    internal fun storeBarsForTest(): Bars = storeBars

    /** The sessions read once per underlying, interval and day (the same past for every entry of the day; memory). */
    private val sessionsKept = ConcurrentHashMap<String, Pair<List<TimeMachine.Session>, String>>()

    fun init(context: Context) { appContext = context.applicationContext }

    private fun file(): File? = appContext?.let { File(File(it.noBackupFilesDir, "self"), FILE) }

    // ---- the entry hook (the order path's one memory write) -----------------------------------------------------------------

    /** An arm just entered: its check runs in the background; the arm goes on at once. */
    fun afterEntry(e: Entry) {
        inFlight.incrementAndGet()
        scope.launch {
            try { runCatching { check(e) }.onFailure { x -> runCatching { Diag.record("timemachine", "${e.label}: the check failed: ${x.message}") } } }
            finally { inFlight.decrementAndGet() }
        }
    }

    /** TEST: wait (up to [ms]) until no check is queued or running; true when none is. */
    internal fun awaitIdleForTest(ms: Long = 5_000): Boolean {
        val until = System.currentTimeMillis() + ms
        while (inFlight.get() > 0 && System.currentTimeMillis() < until) Thread.sleep(5)
        return inFlight.get() == 0
    }

    /** The trade [ref]'s actual outcome, as its arm reports it when it closes ("target", "stop", "time", "+Rs 1,200"). */
    fun settle(ref: String, outcome: String) {
        val gen = wipeGen
        scope.launch { runCatching { synchronized(io) { if (gen != wipeGen) return@synchronized; ensureLoaded(); records = records.map { if (it.ref == ref && it.outcome == null) it.copy(outcome = outcome) else it }; save() } } }
    }

    internal fun check(e: Entry): Result {
        val gen = wipeGen
        val day = e.at.toLocalDate()
        val r = if (e.skip != null) Result(e, null, "", e.skip, System.currentTimeMillis(), day) else {
            val (stop, target, note) = points(e)
            val key = "${e.underlying}|${e.barMinutes}|$day"
            val (sessions, source) = sessionsKept[key] ?: runCatching { bars.sessions(e.underlying, day, e.barMinutes) }
                .getOrElse { emptyList<TimeMachine.Session>() to "the bars could not be read" }
                .also { if (it.first.isNotEmpty()) { if (sessionsKept.size > 12) sessionsKept.clear(); sessionsKept[key] = it } }
            val ask = TimeMachine.Ask(e.direction, e.at.toLocalTime().let { LocalTime.of(it.hour, it.minute - it.minute % e.barMinutes) }, stop ?: Double.NaN, target ?: Double.NaN,
                e.barMinutes, e.exitAt, seed = e.ref.hashCode().toLong())
            // The ORB family's rule is re-run too (the replayable arms, as the backtest lists them).
            val rule = runCatching { com.optionslab.engine.orb.ArmsBacktest.ARMS.firstOrNull { it.source == e.arm } }.getOrNull()?.let { TimeMachine.orbRule(it) }
            val odds = TimeMachine.odds(sessions, ask, day, rule)
            val expiry = runCatching { Market.isExpiryDay(e.underlying, day) }.getOrDefault(false)
            Result(e, odds, source, listOfNotNull(note, if (expiry) "expiry day today: the odds are from all sessions, not expiry days only" else null).joinToString("; ").ifEmpty { null },
                System.currentTimeMillis(), day)
        }
        // 26 Oct: an erase while the odds were worked out: this check (of a trade from before it) is neither shown nor kept.
        if (gen != wipeGen) return r
        results[e.ref] = r
        _latest.update { (listOf(r) + it.filter { x -> x.entry.ref != e.ref }).take(60) }
        // Bounded (25 Oct review): a long-running service keeps only what the tickets can still show.
        if (results.size > 240) { val keep = _latest.value.map { it.entry.ref }.toSet(); results.keys.retainAll(keep) }
        // 26 Oct: an erase just between the check above and here: taken back out.
        if (gen != wipeGen) { results.remove(e.ref); _latest.update { l -> l.filter { it.entry.ref != e.ref } }; return r }
        runCatching { Diag.record("timemachine", r.line()) }
        // The Autopilot change timeline shows it as its own finding (words only; nothing to undo). 28 Oct: not for a hidden arm.
        if (!hidden(e.arm)) runCatching { SmartWorkers.bus.post(com.optionslab.ira.Findings.Finding(WHO, com.optionslab.ira.Findings.Kind.OTHER, e.underlying, 0, null, 50,
            r.atMs, 12 * 3_600_000L, words = "$NOTE_SHORT checked ${e.label}'s entry: verdict ${r.verdict} → " + (r.odds?.let { "target first ${it.targetPct.toInt()}%, stop first ${it.stopPct.toInt()}%, break-even ${(it.breakEven * 100).toInt()}%" } ?: (r.note ?: "no odds")))) }
        synchronized(io) {
            if (gen != wipeGen) return r
            ensureLoaded()
            records = (records + Record(day, e.arm, e.ref, r.verdict, r.odds?.line() ?: (r.note ?: ""), null, e.live)).takeLast(KEEP)
            save()
        }
        testDone?.invoke(r)
        return r
    }

    /** The stop and target in underlying points, converting premium points with the delta (0.5 assumed when the arm has none). */
    internal fun points(e: Entry): Triple<Double?, Double?, String?> {
        // An arm with a stop but no target (a time exit instead): the odds at R = 1, the target set to the stop, said so.
        val target = e.targetPoints ?: e.stopPoints
        val noTarget = if (e.targetPoints == null && e.stopPoints != null) "no target: R 1 assumed (target set to the stop)" else null
        if (!e.premium) return Triple(e.stopPoints, target, noTarget)
        val d = e.premiumDelta?.takeIf { it > 0.05 && it <= 1.0 }
        val delta = d ?: 0.5
        val f = { x: Double? -> x?.let { it / delta } }
        val note = if (d != null) "premium stop and target converted at delta %.2f".format(Locale.ENGLISH, d)
            else "premium stops: odds on the underlying move only (at-the-money delta 0.5 assumed)"
        return Triple(f(e.stopPoints), f(target), listOfNotNull(note, noTarget).joinToString("; "))
    }

    // ---- the bars --------------------------------------------------------------------------------------------------------

    /** The words when the phone holds no session of [u] at all. */
    fun noSessions(u: String): String = "no sessions saved yet on this phone for $u"

    /**
     * The index sessions harvested on the phone ([Store]: the days bundled with the app and the recorder's, read in ONE
     * streaming pass, only the newest [TimeMachine.KEEP_SESSIONS] before [today] kept), else the history keeper's days;
     * folded to [minutes]-minute bars. Disk only, this object's thread only; never the network.
     */
    private fun storeSessions(u: String, today: LocalDate, minutes: Int): Pair<List<TimeMachine.Session>, String> {
        val fromStore = runCatching {
            val kept = java.util.TreeMap<LocalDate, TimeMachine.Session>()
            Store.barSessions(u).forEach { s ->
                if (!s.day.isBefore(today)) return@forEach
                // Older than the oldest day kept once the window is full: not folded (the bundled file starts years back).
                if (kept.size >= TimeMachine.KEEP_SESSIONS && !s.day.isAfter(kept.firstKey())) return@forEach
                val ix = s.series.firstOrNull { it.right == Right.IX } ?: return@forEach
                if (ix.size == 0) return@forEach
                val ones = (0 until ix.size).map { i ->
                    val m = ix.minutes[i]
                    Bar(s.day.atTime(m / 60, m % 60), ix.open?.get(i) ?: ix.close[i], ix.high?.get(i) ?: ix.close[i], ix.low?.get(i) ?: ix.close[i], ix.close[i])
                }
                kept[s.day] = TimeMachine.Session(s.day, fold(ones, minutes))
                while (kept.size > TimeMachine.KEEP_SESSIONS) kept.pollFirstEntry()
            }
            kept.values.toList()
        }.getOrDefault(emptyList())
        if (fromStore.isNotEmpty()) return fromStore to "${fromStore.size} sessions from the phone's harvested $u bars"
        val keeper = runCatching {
            HistoryKeeper.vault()?.days().orEmpty().filter { it.isBefore(today) }.takeLast(TimeMachine.KEEP_SESSIONS).mapNotNull { d ->
                val bars = HistoryKeeper.bars(u, d)
                if (bars.isEmpty()) null else TimeMachine.Session(d, fold(bars.map { b ->
                    val t = java.time.Instant.ofEpochSecond(b.epochSec).atZone(com.optionslab.engine.IST).toLocalDateTime()
                    Bar(t, b.open, b.high, b.low, b.close)
                }, minutes))
            }
        }.getOrDefault(emptyList())
        if (keeper.isEmpty()) return emptyList<TimeMachine.Session>() to noSessions(u)
        return keeper to "${keeper.size} sessions from the phone's recent $u history (about five days are kept)"
    }

    /** 1-minute bars folded into [minutes]-minute bars labelled by their start (as the ORB arms fold them). */
    fun fold(ones: List<Bar>, minutes: Int): List<Bar> {
        if (minutes <= 1) return ones.sortedBy { it.start }
        return ones.groupBy { b -> b.start.toLocalDate().atTime((b.start.hour * 60 + b.start.minute).let { m -> m - m % minutes }.let { LocalTime.of(it / 60, it % 60) }) }
            .toSortedMap().map { (start, g) ->
                val s = g.sortedBy { it.start }
                Bar(start, s.first().open, s.maxOf { it.high }, s.minOf { it.low }, s.last().close)
            }
    }

    // ---- what the screens, Jarvis and the diagnostics read (memory) ----------------------------------------------------------

    /** The check of the trade [ref], or null while it runs (or none). */
    fun resultOf(ref: String): Result? = results[ref]

    /** The ticket's line for [ref], or null. */
    fun line(ref: String): String? = results[ref]?.line()

    /** Jarvis's words on today's checks (none: says so). */
    fun jarvisLine(day: LocalDate): String {
        val all = _latest.value.filter { it.day == day }
        val todays = all.filter { !hidden(it.entry.arm) }
        if (all.isEmpty()) return "The time machine checked no entry today, Boss."
        if (todays.isEmpty()) return "The time machine has nothing to show today, Boss: only the Hero ticket was checked, and its verdict is hidden (its stop is too close for the replay to mean anything)."
        return "Time machine today, Boss: " + todays.joinToString(" ") { r ->
            "${r.entry.label} ${r.verdict}" + (r.odds?.takeIf { it.used > 0 }?.let { " (target first ${it.targetPct.toInt()}%, stop first ${it.stopPct.toInt()}%, break-even ${(it.breakEven * 100).toInt()}%, ${it.sessions} sessions)" } ?: "") + "."
        } + " It only reports; it never skips or resizes a trade. Treat it as background: in a backtest its odds did not predict wins."
    }

    /** The diagnostics' line: today's checks and the forward log's size. */
    fun diagLine(): String {
        val today = Market.today()
        val todays = _latest.value.filter { it.day == today }
        val n = synchronized(io) { records.size }
        // 28 Oct (R120): the diagnostics keep every check for research; a hidden arm's is marked so, and the note is said once.
        return "Time machine: ${todays.size} check(s) today" + (todays.takeIf { it.isNotEmpty() }?.joinToString(" | ", prefix = " - ") { it.line() + if (hidden(it.entry.arm)) " [hidden from display]" else "" } ?: "") +
            "; forward log $n record(s) (shadow only: never skips, resizes or delays a trade; not a proven signal, R120)"
    }

    // ---- the forward log (the encrypted vault file; this object's IO scope only) --------------------------------------------

    private fun ensureLoaded() {
        if (loaded) return
        val f = file()
        val read = runCatching { f?.takeIf { it.isFile }?.let { com.optionslab.app.security.Vault.readFileSteady(it) }?.toString(Charsets.UTF_8) }
        if (read.isFailure) {
            // Unreadable: moved aside, never overwritten. Still in place (the move failed): nothing is saved over it, said once.
            if (f != null && f.exists()) runCatching { com.optionslab.app.security.Vault.setAside(f) }
            if (f != null && f.exists()) { runCatching { Diag.record("timemachine", "the forward log could not be read or moved aside: today's records stay in memory only") }; return }
        }
        val kept = decode(read.getOrNull())
        records = (kept + records.filter { r -> kept.none { it.ref == r.ref } }).takeLast(KEEP)
        loaded = true
    }

    private fun save() {
        if (!loaded) return
        val f = file() ?: return
        runCatching { f.parentFile?.mkdirs(); com.optionslab.app.security.Vault.writeFile(f, encode(records).toByteArray(Charsets.UTF_8)) }
    }

    internal fun encode(rs: List<Record>): String = JSONArray().apply {
        rs.forEach { put(JSONObject().put("day", it.day.toString()).put("arm", it.arm).put("ref", it.ref).put("verdict", it.verdict).put("odds", it.odds)
            .put("outcome", it.outcome ?: JSONObject.NULL).put("live", it.live)) }
    }.toString()

    internal fun decode(text: String?): List<Record> = runCatching {
        val a = JSONArray(text ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Record(LocalDate.parse(o.getString("day")), o.getString("arm"), o.getString("ref"), o.getString("verdict"), o.optString("odds"),
                o.optString("outcome").takeIf { !o.isNull("outcome") && it.isNotEmpty() }, o.optBoolean("live"))
        }
    }.getOrDefault(emptyList())

    /** The forward log as kept (reads the file once; a worker thread). */
    fun forwardLog(): List<Record> = synchronized(io) { ensureLoaded(); records }

    /**
     * 26 Oct ("Erase everything", [PersonalStores]): the forward log and this process's checks dropped from memory and the log's
     * file deleted; a check still running finds [wipeGen] changed and keeps nothing. Never reads the vault; safe before [init].
     */
    fun wipe() {
        synchronized(io) {
            wipeGen++
            records = emptyList(); loaded = false
            runCatching { file()?.delete() }
        }
        results.clear(); sessionsKept.clear(); _latest.value = emptyList()
    }

    /** 26 Oct: records or checks in memory (the erase's test). Memory only. */
    internal fun holdsForErase(): Boolean = records.isNotEmpty() || results.isNotEmpty() || _latest.value.isNotEmpty()

    /** 26 Oct: the forward log's file (empty before [init]). */
    internal fun filesForErase(): List<File> = listOfNotNull(file())

    /** TEST: forget everything in memory (the file stays). */
    internal fun resetForTest() {
        awaitIdleForTest()
        synchronized(io) { loaded = false; records = emptyList() }; results.clear(); sessionsKept.clear(); _latest.value = emptyList(); testDone = null
    }
}
