package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.MoveEvents
import com.optionslab.ira.OrderFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * The big-move recorder on the phone (HUNT R11 part C; the logic and the formats are [MoveEvents]'s). Recording only: it
 * places, skips and changes nothing, and no strategy reads it.
 *
 * Fed once a second from the order flow's own thread ([OrderFlowLive]): the closed 1-second bars of the followed
 * instruments and the cash indices' and VIX's prints, kept here in a 30-minute ring each ([MoveEvents.Ring], fixed memory).
 * When the NIFTY or BANKNIFTY future closes a 1-minute candle 4.7 times its normal move (or a random control minute comes),
 * it writes the 5 minutes before from memory at once, then the next 5 minutes as they close, to one gzip file per event
 * under noBackupFilesDir/orderflow/events (plain market data, never in a backup; exported with the market recorder's zip).
 * Writes are gzip members appended off the main thread, at most every 30 seconds per open event; nothing per tick and
 * nothing through the Keystore (the switch is read once, then cached).
 *
 * Boss's switch ([on], default on): also keeps the price stream on from 09:15 to F&O's close ([OrderFlowLive.keepStreamOn]),
 * except in Android's battery saver or Jarvis's low-battery saver.
 */
object MoveRecorder {
    private const val K_ON = "orderflow.record"
    @Volatile private var onCached: Boolean? = null
    @Volatile private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext }

    /** Boss's switch (default on). Read from the settings once, then from memory. */
    var on: Boolean
        get() = onCached ?: runCatching { SecurePrefs.getBoolean(K_ON, true) }.getOrDefault(true).also { onCached = it }
        set(v) {
            onCached = v
            _onFlow.value = v
            runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to v)) }
            runCatching { Diag.record("flow", "big-move recorder ${if (v) "on" else "off"}") }
        }

    private val _onFlow = MutableStateFlow(true)
    /** The switch, for the sheet (refreshed by [refreshSwitch] off the main thread). */
    val onFlow: StateFlow<Boolean> = _onFlow

    /** Reads the switch into [onFlow] (call off the main thread). */
    fun refreshSwitch() { _onFlow.value = on }

    fun dir(): File? = appContext?.let { File(File(it.noBackupFilesDir, "orderflow"), "events") }

    // ---- the cash indices' prints (a 30-minute ring each) -----------------------------------------------------------------

    /** The index tokens the stream always carries ([KiteStream.INDEX_TOKENS]), by name. */
    val INDEX_TOKENS = mapOf(256265L to "NIFTY", 260105L to "BANKNIFTY", 264969L to MoveEvents.VIX)

    private val rings = HashMap<String, MoveEvents.Ring<Double>>()
    private var indexCursor = Long.MIN_VALUE

    /** An index tick (from the order flow's drain): its last price into its ring. */
    @Synchronized fun onIndexTick(token: Long, last: Double, ms: Long) {
        val n = INDEX_TOKENS[token] ?: return
        if (!(last > 0)) return
        rings.getOrPut(n) { MoveEvents.Ring(MoveEvents.RING_SEC) }.put(ms / 1000, last)
    }

    /** India VIX at [sec] (its last print up to a minute before), or null. */
    @Synchronized fun vixAt(sec: Long): Double? = rings[MoveEvents.VIX]?.at(sec)

    /** The indices' seconds after the last call up to [toSec] (the 1-second file's INDEX rows and the open events'). */
    @Synchronized fun indexRows(toSec: Long): List<MoveEvents.Row> {
        val from = if (indexCursor == Long.MIN_VALUE) toSec - 1 else indexCursor + 1
        if (toSec < from) return emptyList()
        indexCursor = toSec
        return indexRange(from, toSec)
    }

    @Synchronized private fun indexRange(from: Long, to: Long): List<MoveEvents.Row> {
        val out = ArrayList<MoveEvents.Row>()
        for ((token, n) in INDEX_TOKENS) rings[n]?.range(from, to)?.forEach { (s, v) ->
            out += MoveEvents.Row(token, n, MoveEvents.INDEX_ROLE, 0.0, "", MoveEvents.indexBar(s, v))
        }
        return out.sortedBy { it.bar.sec }
    }

    // ---- the events --------------------------------------------------------------------------------------------------------

    /** What the recorder reads from the order flow: the closed seconds held in memory, an option's expiry, the context. */
    interface Feed {
        fun window(fromSec: Long, pick: (OrderFlow.Board.Entry) -> Boolean): List<Pair<OrderFlow.Board.Entry, OrderFlow.Bar>>
        fun expiry(token: Long): String
        fun context(t: MoveEvents.Trigger, nowMs: Long): MoveEvents.Context
    }

    private class Open(val t: MoveEvents.Trigger, val file: File) {
        val buf = StringBuilder()
        var rows = 0
        var merged = 0
        var lastFlush = 0L
        var lastRowSec = Long.MIN_VALUE
    }

    private var detector = MoveEvents.Detector(java.util.Random())
    private val open = ArrayList<Open>()
    private val lastOi = HashMap<String, Long>()
    private var rotatedOn: LocalDate? = null

    /** A row of an event on [name]: its future and the other's, the cash indices and VIX, both indices' ATM options. */
    fun inEvent(name: String, rowName: String, role: String): Boolean =
        (role == OrderFlow.Role.FUTURE.name && (rowName == name || rowName == MoveEvents.other(name))) ||
            (role == MoveEvents.INDEX_ROLE && (rowName in MoveEvents.INDICES || rowName == MoveEvents.VIX)) ||
            (role in MoveEvents.OPTION_ROLES && rowName in MoveEvents.INDICES)

    /**
     * Once a second from the order flow's thread: [rows] are the closed seconds just taken (followed instruments and the
     * indices, each with its gap and OI change). Detects, starts, fills, merges, drops and closes events. Never throws.
     */
    suspend fun onSecond(rows: List<MoveEvents.Row>, nowMs: Long, feed: Feed) {
        val nowSec = nowMs / 1000
        val recording = on
        val signals = ArrayList<MoveEvents.Signal>()
        for (r in rows) {
            if (r.role != OrderFlow.Role.FUTURE.name || r.name !in MoveEvents.INDICES) continue
            if (r.bar.oi > 0) lastOi[r.name] = r.bar.oi
            if (recording) signals += runCatching { detector.onSecond(r.name, r.bar.sec, r.bar.last, vixAt(r.bar.sec)) }.getOrDefault(emptyList())
        }
        // The open events take this second's rows first (a new event's window is read from memory below, whole).
        for (o in open) for (r in rows) {
            if (r.bar.sec < o.t.fromSec || r.bar.sec >= o.t.endSec || !inEvent(o.t.name, r.name, r.role)) continue
            o.buf.append(MoveEvents.sLine(r.bar.sec - o.t.startSec, r)).append('\n'); o.rows++
            if (r.bar.sec > o.lastRowSec) o.lastRowSec = r.bar.sec
        }
        for (s in signals) runCatching {
            when (s) {
                is MoveEvents.Signal.Start -> start(s.t, nowMs, feed)
                is MoveEvents.Signal.Merge -> open.firstOrNull { it.t == s.into }?.let { o ->
                    o.buf.append(MoveEvents.mLine(s.startSec, s.retBp, s.x)).append('\n'); o.merged++
                }
                is MoveEvents.Signal.Drop -> drop(s.t)
            }
        }
        // Close what is done (or everything when switched off); flush the rest every 30 s or 256 KB.
        val iter = open.iterator()
        while (iter.hasNext()) {
            val o = iter.next()
            val done = !recording || nowSec >= o.t.endSec + 3 || MoveEvents.dayOf(nowSec) != MoveEvents.dayOf(o.t.startSec)
            if (done) {
                o.buf.append(MoveEvents.eLine(o.t.endSec, o.rows, o.merged, complete = o.lastRowSec >= o.t.endSec - 5)).append('\n')
                write(o.file, o.buf.toString(), first = false); o.buf.setLength(0)
                iter.remove(); changed()
            } else if (o.buf.length > 256_000 || nowMs - o.lastFlush >= 30_000) {
                write(o.file, o.buf.toString(), first = false); o.buf.setLength(0); o.lastFlush = nowMs
            }
        }
        val today = MoveEvents.date(nowSec)
        if (rotatedOn != today) { rotatedOn = today; rotate(today) }
    }

    private suspend fun start(t: MoveEvents.Trigger, nowMs: Long, feed: Feed) {
        val dir = dir() ?: return
        val f = File(dir, MoveEvents.fileName(t))
        val sb = StringBuilder(512 * 1024)
        val c = runCatching { feed.context(t, nowMs) }.getOrDefault(MoveEvents.Context())
        sb.append(MoveEvents.headerLine(t, c.copy(oi = c.oi ?: lastOi[t.name]), nowMs)).append('\n').append(MoveEvents.COLUMNS).append('\n')
        // The 5 minutes before (and the candle) from memory: the flow's 30-minute bars with their books, the indices' ring.
        val seq = MoveEvents.Sequencer()
        val bars = feed.window(t.fromSec) { e -> inEvent(t.name, e.name, e.role.name) }.sortedBy { it.second.sec }
        val o = Open(t, f)
        for ((e, b) in bars) {
            val r = seq.row(e.token, e.name, e.role.name, e.strike, feed.expiry(e.token), b)
            sb.append(MoveEvents.sLine(b.sec - t.startSec, r)).append('\n'); o.rows++
            if (b.sec > o.lastRowSec) o.lastRowSec = b.sec
        }
        val ixTo = synchronized(this) { indexCursor }
        if (ixTo != Long.MIN_VALUE) for (r in indexRange(t.fromSec, ixTo)) { sb.append(MoveEvents.sLine(r.bar.sec - t.startSec, r)).append('\n'); o.rows++ }
        write(f, sb.toString(), first = true)
        o.lastFlush = nowMs
        open += o
        changed()
        runCatching { Diag.record("flow", "big-move recorder: ${t.id} (${o.rows} rows before)") }
    }

    private suspend fun drop(t: MoveEvents.Trigger) {
        open.removeAll { it.t == t }
        val f = dir()?.let { File(it, MoveEvents.fileName(t)) } ?: return
        withContext(Dispatchers.IO) { runCatching { f.delete() } }
        changed()
    }

    /** Appends [text] to [f] as a new gzip member (a fresh file when [first]). Off the main thread; a failure is noted, never thrown. */
    private suspend fun write(f: File, text: String, first: Boolean) {
        if (text.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                f.parentFile?.mkdirs()
                java.util.zip.GZIPOutputStream(java.io.FileOutputStream(f, !first)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }.onFailure { e -> runCatching { Diag.record("flow", "big-move recorder: write failed (${e.javaClass.simpleName})") } }
        }
    }

    /** Twelve months kept, within the budget ([MoveEvents.rotate]); once a day. */
    private suspend fun rotate(today: LocalDate) {
        val dir = dir() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val files = (dir.listFiles() ?: emptyArray()).map { it.name to it.length() }
                MoveEvents.rotate(files, today).forEach { File(dir, it).delete() }
            }
        }
    }

    // ---- the list, the summaries and Jarvis ---------------------------------------------------------------------------------

    private val _version = MutableStateFlow(0)
    /** Bumped when an event file is added, finished or dropped (the sheet reloads its list). */
    val version: StateFlow<Int> = _version

    private fun changed() { _version.value = _version.value + 1 }

    /** The kept event files, newest first (the folder listing). */
    fun files(): List<Pair<MoveEvents.Named, File>> = (dir()?.listFiles() ?: emptyArray())
        .mapNotNull { f -> MoveEvents.parseName(f.name)?.let { it to f } }
        .sortedWith(compareByDescending<Pair<MoveEvents.Named, File>> { it.first.day }.thenByDescending { it.first.hhmm })

    private val summaries = object : LinkedHashMap<String, MoveEvents.Summary>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MoveEvents.Summary>?) = size > 40
    }

    /** [f]'s summary (read and parsed once per size; about 0.5 MB gzipped). Call off the main thread. */
    fun summary(f: File): MoveEvents.Summary? {
        val key = "${f.name}:${f.length()}"
        synchronized(summaries) { summaries[key]?.let { return it } }
        val e = runCatching {
            java.util.zip.GZIPInputStream(f.inputStream().buffered()).bufferedReader().useLines { MoveEvents.parse(it) }
        }.getOrNull() ?: return null
        val s = MoveEvents.summarize(e)
        synchronized(summaries) { summaries[key] = s }
        return s
    }

    /** One saved big move for the list: from its file's name and first line only. */
    data class Item(val day: LocalDate, val name: String, val kind: MoveEvents.Kind, val startSec: Long, val retBp: Double, val x: Double, val file: File) {
        val line: String get() = MoveEvents.listLine(name, kind, startSec, retBp, x)
    }

    /** The newest [limit] big moves (controls left out), each read from its first line only. Call off the main thread. */
    fun items(limit: Int = 20): List<Item> = files().filter { it.first.kind != MoveEvents.Kind.CONTROL }.take(limit).mapNotNull { (n, f) ->
        val h = runCatching { java.util.zip.GZIPInputStream(f.inputStream()).bufferedReader().use { MoveEvents.parseH(it.readLine().orEmpty()) } }.getOrNull()
            ?: return@mapNotNull null
        Item(n.day, n.name, n.kind, h["candle_start"]?.toLongOrNull() ?: return@mapNotNull null, h["ret_bp"]?.toDoubleOrNull() ?: Double.NaN,
            h["x"]?.toDoubleOrNull() ?: Double.NaN, f)
    }

    /** Controls kept (a count for the sheet). */
    fun controls(): Int = files().count { it.first.kind == MoveEvents.Kind.CONTROL }

    /** Jarvis's answer to "why did BankNifty jump at 10:32" from today's saved events. Call off the main thread. */
    fun answer(a: MoveEvents.Ask): String {
        val today = Market.today()
        val list = files().filter { it.first.day == today && it.first.kind != MoveEvents.Kind.CONTROL && (a.name == null || it.first.name == a.name) }
            .mapNotNull { summary(it.second) }
        return MoveEvents.answer(a, list)
    }

    /** Each kept event file into the export zip (copied as kept). Returns the files written. */
    fun export(zip: java.util.zip.ZipOutputStream): Int {
        var n = 0
        for ((_, f) in files().reversed()) runCatching {
            zip.putNextEntry(java.util.zip.ZipEntry("orderflow-events/${f.name}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); n++
        }
        return n
    }

    /** TEST ONLY: forget the open events, the rings and the detector (seeded). Throws unless BuildConfig.DEBUG. */
    internal fun resetForTest(seed: Long = 1) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test reset exists only in debug builds" }
        synchronized(this) { rings.clear(); indexCursor = Long.MIN_VALUE }
        open.clear(); lastOi.clear(); rotatedOn = null; detector = MoveEvents.Detector(java.util.Random(seed)); onCached = null
        synchronized(summaries) { summaries.clear() }
    }
}
