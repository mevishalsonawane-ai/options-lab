package com.optionslab.app.data

import android.content.Context
import com.optionslab.engine.Right
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.risk.TimeMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * [afterEntry] only queues (one memory write) and starts a background coroutine on its own IO scope; the bars are read from
 * the store there, after the entry, never during a decision; nothing here runs on the position watch. A premium stop or
 * target is converted with the delta the arm has, else said as "premium stops: odds on the underlying move only" with the
 * at-the-money delta 0.5 assumed.
 *
 * A forward log (date, arm, ref, verdict, odds, and the trade's actual outcome once its arm reports it) is kept in the app's
 * encrypted store (noBackupFilesDir/self/timemachine.vault), so research can judge the verdicts after 30 signal days.
 */
object TimeMachineHost {
    private const val FILE = "timemachine.vault"
    private const val KEEP = 600

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
            val tail = listOfNotNull(note.takeIf { odds != null }, sessionsSource.takeIf { odds != null }).joinToString("; ").let { if (it.isEmpty()) "" else " [$it]" }
            return head + body + tail
        }
        val verdict: String get() = odds?.verdict?.word ?: "unknown"
    }

    /** One line of the forward log: the check and, once reported, the trade's actual outcome. */
    data class Record(val day: LocalDate, val arm: String, val ref: String, val verdict: String, val odds: String, val outcome: String?, val live: Boolean)

    /** Where the bars come from (replaceable in tests): the sessions of [underlying] before [today], and a word for the source. */
    fun interface Bars { fun sessions(underlying: String, today: LocalDate, barMinutes: Int): Pair<List<TimeMachine.Session>, String> }

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val io = Any()
    @Volatile private var loaded = false
    @Volatile private var records: List<Record> = emptyList()
    private val results = ConcurrentHashMap<String, Result>()
    private val _latest = MutableStateFlow<List<Result>>(emptyList())
    /** The checks of this process, newest first (the tickets and Jarvis read it; memory only). */
    val latest: StateFlow<List<Result>> = _latest
    /** TEST: a check's completion, to wait on. */
    @Volatile internal var testDone: ((Result) -> Unit)? = null

    /** The store's bars (the default [Bars]): the harvested index sessions first, else the history keeper's days. */
    @Volatile internal var bars: Bars = Bars { u, today, minutes -> storeSessions(u, today, minutes) }

    /** The sessions read once per underlying, interval and day (the same past for every entry of the day; memory). */
    private val sessionsKept = ConcurrentHashMap<String, Pair<List<TimeMachine.Session>, String>>()

    fun init(context: Context) { appContext = context.applicationContext }

    private fun file(): File? = appContext?.let { File(File(it.noBackupFilesDir, "self"), FILE) }

    // ---- the entry hook (the order path's one memory write) -----------------------------------------------------------------

    /** An arm just entered: its check runs in the background; the arm goes on at once. */
    fun afterEntry(e: Entry) {
        scope.launch { runCatching { check(e) }.onFailure { x -> runCatching { Diag.record("timemachine", "${e.label}: the check failed: ${x.message}") } } }
    }

    /** The trade [ref]'s actual outcome, as its arm reports it when it closes ("target", "stop", "time", "+Rs 1,200"). */
    fun settle(ref: String, outcome: String) {
        scope.launch { runCatching { synchronized(io) { ensureLoaded(); records = records.map { if (it.ref == ref && it.outcome == null) it.copy(outcome = outcome) else it }; save() } } }
    }

    internal fun check(e: Entry): Result {
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
        results[e.ref] = r
        _latest.update { (listOf(r) + it.filter { x -> x.entry.ref != e.ref }).take(60) }
        runCatching { Diag.record("timemachine", r.line()) }
        // The Autopilot change timeline shows it as its own finding (words only; nothing to undo).
        runCatching { SmartWorkers.bus.post(com.optionslab.ira.Findings.Finding("time machine", com.optionslab.ira.Findings.Kind.OTHER, e.underlying, 0, null, 50,
            r.atMs, 12 * 3_600_000L, words = "Time machine checked ${e.label}'s entry: verdict ${r.verdict} → " + (r.odds?.let { "target first ${it.targetPct.toInt()}%, stop first ${it.stopPct.toInt()}%, break-even ${(it.breakEven * 100).toInt()}%" } ?: (r.note ?: "no odds")))) }
        synchronized(io) {
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

    /** The index sessions harvested on the phone ([Store]), else the history keeper's days; folded to [minutes]-minute bars. */
    private fun storeSessions(u: String, today: LocalDate, minutes: Int): Pair<List<TimeMachine.Session>, String> {
        val fromStore = runCatching {
            Store.barDays(u).filter { it.isBefore(today) }.takeLast(TimeMachine.KEEP_SESSIONS).mapNotNull { d ->
                val s = Store.barSession(u, d) ?: return@mapNotNull null
                val ix = s.series.firstOrNull { it.right == Right.IX } ?: return@mapNotNull null
                val ones = (0 until ix.size).map { i ->
                    val m = ix.minutes[i]
                    Bar(d.atTime(m / 60, m % 60), ix.open?.get(i) ?: ix.close[i], ix.high?.get(i) ?: ix.close[i], ix.low?.get(i) ?: ix.close[i], ix.close[i])
                }
                TimeMachine.Session(d, fold(ones, minutes))
            }
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
        val todays = _latest.value.filter { it.day == day }
        if (todays.isEmpty()) return "The time machine checked no entry today, Boss."
        return "Time machine today, Boss: " + todays.joinToString(" ") { r ->
            "${r.entry.label} ${r.verdict}" + (r.odds?.takeIf { it.used > 0 }?.let { " (target first ${it.targetPct.toInt()}%, stop first ${it.stopPct.toInt()}%, break-even ${(it.breakEven * 100).toInt()}%, ${it.sessions} sessions)" } ?: "") + "."
        } + " It only reports; it never skips or resizes a trade."
    }

    /** The diagnostics' line: today's checks and the forward log's size. */
    fun diagLine(): String {
        val today = Market.today()
        val todays = _latest.value.filter { it.day == today }
        val n = synchronized(io) { records.size }
        return "Time machine: ${todays.size} check(s) today" + (todays.takeIf { it.isNotEmpty() }?.joinToString(" | ", prefix = " - ") { it.line() } ?: "") +
            "; forward log $n record(s) (shadow only: never skips, resizes or delays a trade)"
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

    /** TEST: forget everything in memory (the file stays). */
    internal fun resetForTest() { synchronized(io) { loaded = false; records = emptyList() }; results.clear(); sessionsKept.clear(); _latest.value = emptyList(); testDone = null }
}
