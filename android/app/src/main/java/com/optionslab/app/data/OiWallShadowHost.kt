package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.ira.Automations
import com.optionslab.app.security.Vault
import com.optionslab.ira.OiWallBlast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Boss's OI-wall short-covering framework in SHADOW (Boss approved, 14 Oct 2026; the rule is [OiWallBlast]'s, R26's rule P).
 * A RECORD ONLY: nothing here places, changes, skips or resizes an order, paper or live, and nothing raises an alert that
 * looks like a trade signal. R26 found no edge (0 of 10 versions passed on 770 expiries); this builds the forward sample.
 *
 * On an expiry day of NIFTY, BANKNIFTY or FINNIFTY (SENSEX too, should its BSE chain ever be on the phone - it is not
 * today), in Jarvis's words lane (a worker, about a round a minute; [watch]):
 *  - 11:59-14:30: each round copies from memory ([MemoryChain]) each option's OI, the stream's day volume and its price,
 *    each filed under the minute it became known (never a later one, so a decision never sees what came after its close):
 *    a traded price under the minute of its trade; the OI and the day volume under the minute their packet ARRIVED (15 Oct
 *    review: the exchange sends a new OI without a trade, so an OI is as new as its packet, not as old as the last trade);
 *    and spot's 1-minute closes from the stream's own candles (else its last few minutes' path). A volume or a "traded"
 *    price comes only from the stream (a chain read's is not this minute's); a chain read's OI counts, aged from the read.
 *    A strike stays "stored" for 5 rows after a reading, as a lake bar did.
 *  - The data must be watched from 12:00 (the OI peak's window) to the trigger: a day the app (re)started later is
 *    flagged "partial data since restart at HH:MM"; switched off after a trigger, the day is closed then, as switched off.
 *  - 13:45-14:30: each minute is checked once, as it closes. The first trigger is written to the log at once, and the
 *    bought option is followed on the stream (one instrument under [OWNER]; it never starts or keeps the stream by itself).
 *  - From 15:21 (or 14:31 without a trigger): the ticket is priced with the app's paper cost model for all four exits from
 *    the option's own minute bars, the day written, the subscription dropped, and Jarvis's one evening line goes in the
 *    chat (aloud only by Boss's "Jarvis speaks" choice, never on a locked phone): "OI-wall check (shadow, not traded;
 *    information only, not a trade signal): fired / didn't fire; would have made X points". Switched off mid-day, the
 *    day is closed at once as switched off; a trigger the app could not price that day (killed) is closed the next day as
 *    "not priced", so the record never silently drops one.
 *
 * Battery: on any other day a round costs one look at the calendar; only these windows read anything, and nothing is on
 * the order or tick path (open positions keep their stream priority: [OWNER] is not in the stream's first group). The log
 * is its own file, encrypted with [Vault] (its tickets carry rupees), on this phone only, the last [KEEP] index-days.
 * Order Review's notes and the evening diagnostics read it in POINTS only. Its own switch under Settings → Jarvis → Market
 * alerts ([Automations.Auto.OIWALL], on by default): off, nothing is recorded (and nothing is followed).
 */
object OiWallShadowHost {
    const val OWNER = "oiwall"
    /** A reading older than this when the round runs is left out (the lane runs about once a minute). */
    const val FRESH_MS = 120_000L
    const val KEEP = 400
    private const val FILE = "oiwall.vault"
    private const val KEY_TOLD = "oiwall.told"

    private val START = LocalTime.of(11, 59)
    private val NO_TRIGGER_END = OiWallBlast.closeTime(OiWallBlast.LAST).plusMinutes(1)
    private val TICKET_END = LocalTime.of(15, 21)
    /** Until then, an empty expiry list is looked at again every 10 minutes (the day's list may not be loaded yet). */
    private val EXPIRY_LOOK_UNTIL = LocalTime.of(13, 0)

    @Volatile private var file: File? = null
    private val written = Vault.LastWrite()

    fun init(context: Context) { file = File(context.applicationContext.noBackupFilesDir, FILE) }

    /** One index's expiry day in memory: its rows, the trigger, the option followed, the minutes checked. */
    private class Live(val index: String, val day: LocalDate, /** When this process began watching the day (a later start misses part of the noon OI window). */ val startedAt: LocalTime) {
        val d = OiWallBlast.Day(index)
        /** The last closed row whose spot close was copied. */
        var lastRow = -1
        /** The last row the rule was checked at (each minute once). */
        var checked = OiWallBlast.FIRST - 1
        var signal: OiWallBlast.Signal? = null
        var token: Long? = null
        var lot: Int? = null
        var done = false
        var restarted = false
        /** Each option's stream token as last read (the bought one is followed once it fires). */
        var tokens: Map<Pair<Double, String>, Long> = emptyMap()
    }

    private val lock = Any()
    private var today: LocalDate? = null
    private var expiries: List<String> = emptyList()
    private var expiriesAt: LocalTime? = null
    private val live = HashMap<String, Live>()

    /** The day the evening line was put, as last read or written (so a round after it reads no setting). */
    private var toldDay: LocalDate? = null

    /** The day the evening line was put (remembered across a restart, so it is said once). */
    private fun toldOn(): LocalDate? = runCatching { com.optionslab.app.security.SecurePrefs.getString(KEY_TOLD)?.let { LocalDate.parse(it) } }.getOrNull()

    // ---- the log (encrypted; read and written on the words lane's worker only) -------------------------------------

    private var cache: MutableList<JSONObject>? = null

    private fun log(): MutableList<JSONObject> {
        cache?.let { return it }
        val out = ArrayList<JSONObject>()
        val f = file
        val ok = f == null || runCatching {
            val a = JSONObject(String(Vault.readFileSteady(f) ?: return@runCatching, Charsets.UTF_8)).optJSONArray("days") ?: JSONArray()
            for (i in 0 until a.length()) out += a.getJSONObject(i)
        }.isSuccess
        // Never overwrite what could not be read: set it aside and start a fresh log.
        if (!ok && f != null && f.exists()) runCatching { Vault.setAside(f) }
        cache = out
        return out
    }

    private fun save(l: MutableList<JSONObject>) {
        while (l.size > KEEP) l.removeAt(0)
        cache = l
        val f = file ?: return
        runCatching { written.write(f, JSONObject().put("days", JSONArray(l)).toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun key(o: JSONObject) = o.optString("day") + "|" + o.optString("index")

    private fun put(rec: JSONObject) = synchronized(lock) {
        val l = log()
        l.removeAll { key(it) == key(rec) }
        l += rec
        save(l)
    }

    private fun sigJson(s: OiWallBlast.Signal) = JSONObject().put("row", s.row).put("side", s.side).put("strike", s.strike).put("ref", s.ref)
        .put("wall", s.wall).put("edge", s.edge).apply { s.oiFallPct?.let { put("oiFall", it) } }

    private fun sigOf(o: JSONObject?): OiWallBlast.Signal? = o?.let {
        OiWallBlast.Signal(it.getInt("row"), it.getInt("side"), it.getDouble("strike"), it.getDouble("ref"), it.getDouble("wall"),
            it.optBoolean("edge"), if (it.has("oiFall")) it.getDouble("oiFall") else null)
    }

    private fun ticketJson(t: OiWallBlast.Ticket) = JSONObject().put("fillRow", t.fillRow).put("fill", t.fill).put("lots", t.lots).put("lot", t.lot)
        .put("outcomes", JSONArray(t.outcomes.map { o ->
            JSONObject().put("exit", o.exit.name).put("net", o.net).put("charges", o.charges).put("points", o.points).put("peak", o.peakMult)
                .put("sales", JSONArray(o.sales.map { s -> JSONObject().put("row", s.row).put("units", s.units).put("price", s.price).put("why", s.why) }))
        }))

    private fun ticketOf(o: JSONObject?): OiWallBlast.Ticket? = o?.let { t ->
        val outs = t.getJSONArray("outcomes")
        OiWallBlast.Ticket(t.getInt("fillRow"), t.getDouble("fill"), t.getInt("lots"), t.getInt("lot"), (0 until outs.length()).map { i ->
            val x = outs.getJSONObject(i)
            val sales = x.getJSONArray("sales")
            OiWallBlast.Outcome(OiWallBlast.Exit.valueOf(x.getString("exit")), (0 until sales.length()).map { j ->
                val s = sales.getJSONObject(j)
                OiWallBlast.Sale(s.getInt("row"), s.getInt("units"), s.getDouble("price"), s.getString("why"))
            }, x.getDouble("net"), x.getDouble("charges"), x.getDouble("points"), x.getDouble("peak"))
        })
    }

    private fun resultOf(o: JSONObject): OiWallBlast.Result? = runCatching {
        OiWallBlast.Result(o.getString("index"), LocalDate.parse(o.getString("day")), sigOf(o.optJSONObject("signal")),
            ticketOf(o.optJSONObject("ticket")), o.optString("note").ifEmpty { null })
    }.getOrNull()

    /** Every finished index-day in the log, oldest first (a trigger still being priced is left out). */
    fun results(): List<OiWallBlast.Result> = synchronized(lock) { log().filter { it.optBoolean("final") }.mapNotNull(::resultOf) }

    /** [day]'s finished results. */
    fun resultsOn(day: LocalDate): List<OiWallBlast.Result> = results().filter { it.day == day }

    // ---- each round ------------------------------------------------------------------------------------------------

    /** The 1-minute bar (open, high, low, close) of [token] that started at [startMs], from the stream's memory: its candles, else its path. */
    private fun bar(token: Long, startMs: Long): DoubleArray? =
        KiteStream.candles.candle(token, startMs / 1000)?.let { doubleArrayOf(it.open, it.high, it.low, it.close) }
            ?: KiteStream.minuteBar(token, startMs)

    private fun rowStartMs(day: LocalDate, row: Int): Long =
        day.atTime(9, 15).plusMinutes(row.toLong()).atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    /** The grid row of the minute running at [ms] (row t = the bar starting 09:15 + t). */
    private fun rowAt(ms: Long): Int = OiWallBlast.minute(Instant.ofEpochMilli(ms).atZone(com.optionslab.engine.IST).toLocalTime())

    /** The instruments followed for the log now: the bought option of each trigger still being priced. */
    private fun followed(): Set<Long> = live.values.filter { !it.done && it.signal != null }.mapNotNull { it.token }.toSet()

    private fun follow() { runCatching { KiteStream.want(OWNER, followed()) } }

    /**
     * The indices expiring on [day] (looked up once a day; an empty answer again every 10 minutes until 13:00, as the day's
     * list may not be loaded yet). A new day first closes any trigger an earlier day could not price, and picks up a
     * trigger already logged today (a restart), following its option again.
     */
    private fun expiringToday(day: LocalDate, t: LocalTime): List<String> {
        if (today != day) {
            today = day
            live.clear()
            expiries = emptyList(); expiriesAt = null
            closeStale(day)
            runCatching { KiteStream.want(OWNER, emptyList()) }
        }
        val again = expiries.isEmpty() && !t.isAfter(EXPIRY_LOOK_UNTIL) && expiriesAt.let { it == null || !t.isBefore(it.plusMinutes(10)) }
        if (expiriesAt == null || again) {
            expiriesAt = t
            expiries = OiWallBlast.INDICES.filter { u ->
                testExpiring?.invoke(u, day) ?: runCatching { Market.isExpiryDay(u, day) }.getOrDefault(false)
            }
            restore(day, t)
        }
        return expiries
    }

    /** Today's triggers already in the log (a restart): picked up, their option followed again (their earlier minutes are gone). */
    private fun restore(day: LocalDate, t: LocalTime) {
        for (p in log().filter { it.optString("day") == day.toString() }) {
            val idx = p.optString("index")
            if (idx !in expiries || live.containsKey(idx)) continue
            val l = Live(idx, day, t)
            l.restarted = true
            l.signal = sigOf(p.optJSONObject("signal"))
            l.done = p.optBoolean("final")
            if (p.has("token")) l.token = p.getLong("token")
            if (p.has("lot")) l.lot = p.getInt("lot")
            live[idx] = l
        }
        follow()
    }

    /** A trigger an earlier day logged but could not price (the app was killed or switched off before 15:21): closed as such. */
    private fun closeStale(day: LocalDate) {
        val stale = log().filter { !it.optBoolean("final") && it.optString("day") < day.toString() }
        for (p in stale) put(JSONObject(p.toString()).put("final", true)
            .put("note", "not priced: the app was not running at 15:21 that day"))
    }

    /**
     * One round of the words lane. Memory only, apart from the log's file when something is written (a trigger, a finished
     * day). Never throws. Switched off: nothing more is recorded; a day still open is closed with a "switched off" record
     * ([switchedOff]) and an option it followed is let go.
     */
    suspend fun watch() {
        if (com.optionslab.app.BuildConfig.GOLD) return
        val now = Market.now().toLocalDateTime()
        // Battery: before 11:59 a round reads nothing at all, not even the settings vault.
        if (now.toLocalTime().isBefore(START)) return
        if (!Automations.ownSwitch(Automations.Auto.OIWALL)) {
            runCatching { synchronized(lock) { switchedOff(now) } }
            return
        }
        runCatching { round(now, System.currentTimeMillis()) }
    }

    internal fun round(now: LocalDateTime, nowMs: Long) = synchronized(lock) {
        val day = now.toLocalDate()
        val t = now.toLocalTime()
        if (t.isBefore(START) || !runCatching { Market.isTradingDay(day) }.getOrDefault(false)) return@synchronized
        val indices = expiringToday(day, t)
        if (indices.isEmpty()) return@synchronized
        // The bar that closed at the start of this minute.
        val closed = OiWallBlast.minute(t) - 1
        for (idx in indices) {
            val l = live.getOrPut(idx) { Live(idx, day, t) }
            if (l.done) continue
            runCatching { step(l, closed, t, nowMs) }
        }
        if (indices.all { live[it]?.done == true } && !t.isBefore(TICKET_END) && toldDay != day) {
            if (toldOn() == day) { toldDay = day } else { evening(day) }
        }
        Unit
    }

    private fun step(l: Live, closedNow: Int, t: LocalTime, nowMs: Long) {
        if (closedNow < OiWallBlast.NOON - 1) return
        // After the session's last bar (a round late in the day) the last row stands for it.
        val closed = minOf(closedNow, OiWallBlast.N - 1)
        val s0 = l.signal
        if (s0 == null) {
            // Spot's closes, minute by minute, from the stream's candles (a missed round's minutes too).
            val token = Broker.indexToken(l.index)
            for (r in maxOf(l.lastRow + 1, OiWallBlast.NOON - 5, closed - 120)..closed)
                l.d.record(r, token?.let { bar(it, rowStartMs(l.day, r)) }?.get(3), emptyList())
            l.lastRow = maxOf(l.lastRow, closed)
            // The options as read now, filed under the minute each reading was taken (never later than the bar running now).
            if (closed < OiWallBlast.LAST) readings(l, nowMs, closed + 1)
            // Each closed minute of the window checked once.
            val upTo = minOf(closed, OiWallBlast.LAST)
            if (upTo > l.checked) {
                val s = OiWallBlast.signal(l.d, upTo, from = l.checked + 1)
                l.checked = upTo
                if (s != null) triggered(l, s)
            }
            if (l.signal == null && !t.isBefore(NO_TRIGGER_END)) finish(l)
            return
        }
        // After the trigger: the bought option's closed minute bars (a missed round's too), then the ticket from 15:21.
        val token = l.token
        if (token != null) for (r in maxOf(s0.row + 1, closed - 120)..minOf(closed, OiWallBlast.EXIT_1520)) {
            bar(token, rowStartMs(l.day, r))?.let { b -> l.d.bar(r, s0.strike, s0.right, b[0], b[1], b[2], b[3]) }
        }
        if (!t.isBefore(TICKET_END)) finish(l)
    }

    /**
     * The options as read now, each part filed under the row it became known in (at most [current], the bar running now):
     * a traded price under the minute of its trade; the OI and the day volume under the minute their packet ARRIVED (15 Oct
     * review: the exchange sends a new OI without a trade, so filing it under an older trade's minute would let that
     * minute's decision see an OI from after its close). Parts older than [FRESH_MS] are left out.
     */
    private fun readings(l: Live, nowMs: Long, current: Int) {
        val snap = MemoryChain.read(l.index, nowMs, each = 12)
        if (l.lot == null) l.lot = snap.lot
        l.tokens = l.tokens + snap.legs.mapNotNull { leg -> leg.token?.let { (leg.strike to leg.right) to it } }.toMap()
        val parts = ArrayList<Pair<Int, OiWallBlast.Obs>>()
        for (leg in snap.legs) {
            // A price counts as traded in its minute only from the stream itself (a chain read's is not this minute's).
            if (leg.fromTick && leg.ageMs <= FRESH_MS) {
                val row = minOf(rowAt(nowMs - leg.ageMs), current)
                val b = leg.token?.let { bar(it, rowStartMs(l.day, row)) }
                parts += row to OiWallBlast.Obs(leg.strike, leg.right, close = b?.get(3) ?: leg.ltp, open = b?.get(0), high = b?.get(1), low = b?.get(2))
            }
            // The OI (and, from the stream, the day's volume) under the minute its packet arrived.
            if (leg.packetAgeMs <= FRESH_MS) {
                val row = minOf(rowAt(nowMs - leg.packetAgeMs), current)
                parts += row to OiWallBlast.Obs(leg.strike, leg.right, oi = leg.oi, cumVol = if (leg.fromTick) leg.cumVol else null)
            }
        }
        for ((row, obs) in parts.groupBy({ it.first }, { it.second })) {
            if (row < OiWallBlast.NOON - 5) continue
            l.d.record(row, null, obs)
        }
    }

    private fun triggered(l: Live, s: OiWallBlast.Signal) {
        l.signal = s
        l.token = l.tokens[s.strike to s.right]
        // The bought option's prices from now to 15:20: one more instrument on the stream (never a wake of the stream itself).
        follow()
        put(record(l, final = false))
        runCatching { Diag.record("oiwall", "${l.index} shadow trigger at ${s.time} (${s.strike.toLong()} ${s.right}); no order") }
    }

    private fun finish(l: Live) {
        l.done = true
        val s = l.signal
        val lot = l.lot
        val ticket = if (s != null && lot != null) runCatching { OiWallBlast.ticket(l.d, s, lot) }.getOrNull() else null
        val note = listOfNotNull(partialNote(l), when {
            s == null -> OiWallBlast.gaps(l.d)
            lot == null -> "the lot size was not known"
            l.token == null -> "the option's own prices were not on the stream"
            ticket == null -> "the ticket would not have filled"
            else -> null
        }).joinToString("; ").ifEmpty { null }
        put(record(l, final = true, ticket = ticket, note = note))
        // Memory: the day's minutes (about a megabyte an index) are let go once written; only "done" is kept.
        live[l.index] = Live(l.index, l.day, l.startedAt).also { it.done = true }
        follow()
    }

    private fun hm(t: LocalTime) = "%02d:%02d".format(java.util.Locale.ENGLISH, t.hour, t.minute)

    /**
     * The noon OI window (12:00 to the trigger) was not fully watched: this process began the day at or after 12:00, or
     * picked up a logged trigger after a restart (15 Oct review). Null when it was watched whole.
     */
    private fun partialNote(l: Live): String? =
        if (l.restarted || !l.startedAt.isBefore(LocalTime.NOON)) "partial data since restart at ${hm(l.startedAt)}" else null

    /**
     * Switched off mid-day (15 Oct review): every index still open today is closed now with a final record saying so (a
     * trigger as "switched off after the trigger", never priced; a day without one as not judged), its minutes let go and
     * its option no longer followed. Switched on again the same day, the evening line reports these as they are.
     */
    private fun switchedOff(now: LocalDateTime) {
        val open = live.values.filter { !it.done && it.day == now.toLocalDate() }
        if (open.isEmpty()) return
        for (l in open) {
            val note = if (l.signal != null) "switched off at ${hm(now.toLocalTime())} after the trigger: not priced" else "switched off at ${hm(now.toLocalTime())}: not judged"
            put(record(l, final = true, note = note))
            live[l.index] = Live(l.index, l.day, l.startedAt).also { it.done = true }
        }
        follow()
    }

    private fun record(l: Live, final: Boolean, ticket: OiWallBlast.Ticket? = null, note: String? = null): JSONObject =
        JSONObject().put("day", l.day.toString()).put("index", l.index).put("final", final)
            .apply {
                l.signal?.let { put("signal", sigJson(it)) }
                ticket?.let { put("ticket", ticketJson(it)) }
                note?.let { put("note", it) }
                l.token?.let { put("token", it) }
                l.lot?.let { put("lot", it) }
            }

    /** Jarvis's one evening line on an expiry day (once; in the chat, aloud only by Boss's choice and never when locked). */
    private fun evening(day: LocalDate) {
        toldDay = day
        runCatching { com.optionslab.app.security.SecurePrefs.put(KEY_TOLD, day.toString()) }
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.OIWALL)) return
        val text = OiWallBlast.eveningLine(resultsOn(day))
        val locked = runCatching { com.optionslab.app.ira.IraHub.locked() }.getOrDefault(true)
        runCatching {
            if (locked) com.optionslab.app.ira.IraHub.note(text, from = Automations.Auto.OIWALL)
            else com.optionslab.app.ira.IraHub.noteAloud(text, com.optionslab.ira.SpeakChoice.Weight.MINOR, Automations.Auto.OIWALL)
        }
        Automations.acted(Automations.Auto.OIWALL, "Put the OI-wall shadow line in the chat.")
    }

    // ---- what others read (points only) ----------------------------------------------------------------------------

    /** Order Review's notes for [day]: one line an index (points only). */
    fun reviewNotes(day: LocalDate): List<String> =
        resultsOn(day).map { "${OiWallBlast.HEAD}, ${it.index}: ${OiWallBlast.summary(it)}" }

    /** The diagnostics line for [day] (points only; empty days say so). */
    fun diagLine(day: LocalDate): String = OiWallBlast.diagLine(resultsOn(day))

    /** "How is the OI-wall check doing?": today's results, then the tally of every day logged. */
    fun answer(): String {
        val day = Market.today()
        val todays = resultsOn(day)
        val head = if (todays.isNotEmpty()) OiWallBlast.eveningLine(todays) + " " else ""
        return head + OiWallBlast.tally(results())
    }

    /**
     * TEST ONLY: which index expires on a day (null in the app, always: the calendar, [Market.isExpiryDay], is read). Its
     * setter throws unless BuildConfig.DEBUG.
     */
    @Volatile internal var testExpiring: ((String, LocalDate) -> Boolean)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test calendar exists only in debug builds" }; field = v }

    /** TEST ONLY: [index]'s OI of a strike as the rule reads it at [row] (NaN: none, or the day not in memory). */
    internal fun oiForTest(index: String, strike: Double, right: String, row: Int): Double =
        synchronized(lock) { live[index]?.d?.oiAt(strike, right, row) ?: Double.NaN }

    /** TEST ONLY: forget the day in memory and the log's cache (the file stays), as a restart of the app would. */
    internal fun resetForTest() = synchronized(lock) {
        today = null; expiries = emptyList(); expiriesAt = null; live.clear(); cache = null; toldDay = null; written.forget()
    }

    /** Erase the log now (on a worker: it waits for a round that may be reading). */
    internal fun wipeNow() = synchronized(lock) {
        cache = null; live.clear(); today = null; expiries = emptyList(); expiriesAt = null; toldDay = null; written.forget()
        runCatching { KiteStream.want(OWNER, emptyList()) }
        runCatching { file?.delete() }
        Unit
    }

    /** Erase the log (Boss's erase-everything, called on the main thread): done on a thread of its own, never waiting here. */
    fun wipe() { Thread { runCatching { wipeNow() } }.apply { name = "oiwall-wipe" }.start() }

    /** 26 Oct: the erase's files: the log and any copy of it set aside as unreadable (empty before [init]). */
    internal fun filesForErase(): List<File> = file?.let { f ->
        listOf(f) + f.parentFile?.listFiles { x -> x.name.startsWith(f.name + ".unreadable.") }.orEmpty()
    } ?: emptyList()

    /** 26 Oct: the log or a day in memory (the erase's test). Memory only, never waits for a round (a size read, no lock). */
    internal fun holdsForErase(): Boolean = runCatching { cache?.isNotEmpty() == true || live.isNotEmpty() }.getOrDefault(false)

    /** TEST ONLY: one finished index-day put in the log (memory and file), as a round would. Debug builds only. */
    internal fun putForTest(index: String, day: LocalDate, note: String) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        put(JSONObject().put("day", day.toString()).put("index", index).put("final", true).put("note", note))
    }
}
