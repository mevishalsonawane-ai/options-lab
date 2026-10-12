package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Data Brain's live recording (L8 Feedback, "Additions before building" items 1 and 4; Boss, 12 Oct: "every day not
 * recorded is lost"). Pure logic: the record shapes (one JSON object a line, schema [SCHEMA]), the option chain's IV, the
 * strikes kept, the folder's rotation and size budget, the health rule and the card's lines. The app's
 * [com.optionslab.app.data.LiveRecorder] copies what it already holds in memory and writes these lines in the background.
 *
 * Two files a day under noBackupFilesDir/recordings/YYYY-MM-DD/:
 *  - market.jsonl.gz: plain gzip (one gzip member per write; any gunzip reads the members as one stream). Market data only:
 *    a header ("t":"H"), then a minute row per index ("t":"M") with its OHLC, the futures' last and basis, VIX and the
 *    nearest-expiry chain at ATM +/-10 (LTP, OI, OI change since the first reading today, IV).
 *  - trades.jsonl.gz.vault: Boss's own records, encrypted with the app's Vault (AES-256-GCM, a key that never leaves the
 *    phone's Keystore). Each line is base64(Vault(gzip(JSON lines))): a header first, then one "t":"S" (signal), "O" (live
 *    order sent) or "F" (fill, paper or live) snapshot a line, each with the market as it was in memory then.
 *
 * IV ([iv]): Black-76 on a forward. The forward is the chain's own put-call parity at the money (strike + call - put), which
 * already carries the interest and dividends; without both ATM prices it is spot * e^(r*T) with r = [RATE] (6.5%, near the
 * RBI repo rate). Time is calendar time to the expiry's 15:30 IST. VIX is recorded beside the chain, never used as an input.
 * OI change is today's change from the first OI this process saw for that contract today (Zerodha's ticks carry no previous
 * day's OI), so a mid-day restart restarts it; [Leg.oiFrom] says from when.
 */
object LiveRecord {
    const val SCHEMA = 1
    /** Strikes either side of the money kept. */
    const val EACH_SIDE = 10
    /** Indices recorded every minute. */
    val MINUTE_INDICES = listOf("NIFTY", "BANKNIFTY")
    const val DEFAULT_KEEP_DAYS = 60
    val KEEP_CHOICES = listOf(30, 60, 90, 180)
    /** A day's folder stops taking minute rows past this (snapshots of Boss's own trades are always written). */
    const val DAY_BUDGET_BYTES = 20_000_000L
    /** The whole folder at most (the oldest days go first, never today's). */
    const val TOTAL_CAP_BYTES = 1_500_000_000L
    /** Snapshots waiting for the writer at most (the oldest dropped and counted: never a wait on the order path). */
    const val QUEUE_CAP = 256
    /** The rate used for a forward only when the chain gives none (decimal a year). */
    const val RATE = 0.065
    /** A price older than this is written with its age, but not used for the forward. */
    const val FRESH_MS = 120_000L
    /** Recording stopped: no write for this long while NSE is open and the watch runs. */
    const val STALL_MS = 5 * 60_000L

    const val MARKET_FILE = "market.jsonl.gz"
    const val TRADES_FILE = "trades.jsonl.gz.vault"

    enum class Kind(val code: String) { SIGNAL("S"), ORDER("O"), FILL("F") }

    /** One option of the chain as recorded. [ageMs]: how old its price was; [src]: "tick" (stream) or "chain" (a chain read). */
    data class Leg(
        val strike: Double, val right: String, val ltp: Double, val oi: Long?, val oiChange: Long?, val iv: Double?,
        val ageMs: Long?, val src: String, val oiFrom: String? = null,
    )

    data class Level(val price: Double, val qty: Long, val orders: Int)

    /** The traded option's book: best bid/ask with their quantities and up to five levels a side. */
    data class Book(
        val ltp: Double?, val bid: Double?, val ask: Double?, val bidQty: Long?, val askQty: Long?,
        val bids: List<Level>, val asks: List<Level>, val ageMs: Long?,
    )

    /** The order's timing (epoch ms; null when not known). */
    data class Timing(val decidedMs: Long? = null, val sentMs: Long? = null, val filledMs: Long? = null, val exchMs: Long? = null)

    /** A raw option price from memory, before the strikes are picked and the IV is solved. */
    data class Raw(val strike: Double, val right: String, val ltp: Double, val oi: Long?, val ageMs: Long?, val src: String)

    data class Snapshot(
        val kind: Kind, val atMs: Long, val arm: String, val symbol: String, val underlying: String?, val side: String?,
        val qty: Int?, val price: Double?, val intended: Double?, val paper: Boolean, val exit: Boolean,
        val spot: Double?, val future: Double?, val vix: Double?, val expiry: LocalDate?, val chain: List<Leg>, val book: Book?,
        val timing: Timing, val note: String? = null,
    )

    data class Bar(val open: Double, val high: Double, val low: Double, val close: Double)

    data class Minute(
        val minute: LocalTime, val index: String, val bar: Bar?, val spot: Double?, val future: Double?, val vix: Double?,
        val expiry: LocalDate?, val chain: List<Leg>, val missing: List<String> = emptyList(),
    )

    // ---- the strikes and the IV ---------------------------------------------------------------------------------------

    /** The forward: put-call parity at the money from fresh prices, else spot * e^(rT). Null without a spot. */
    fun forward(raws: List<Raw>, spot: Double?, years: Double): Double? {
        val atm = MarketRecord.atm(raws.map { it.strike }.distinct(), spot ?: return null)
        if (atm != null) {
            fun px(r: String) = raws.firstOrNull { it.strike == atm && it.right == r && (it.ageMs ?: 0) <= FRESH_MS }?.ltp
            MarketRecord.forward(atm, px("CE"), px("PE"))?.let { return it }
        }
        return if (spot > 0 && years > 0) spot * kotlin.math.exp(RATE * years) else null
    }

    /** Calendar years from [now] to [expiry]'s 15:30 IST (0 at or past it). */
    fun years(now: LocalDateTime, expiry: LocalDate): Double {
        val secs = java.time.Duration.between(now, expiry.atTime(15, 30)).seconds
        return if (secs <= 0) 0.0 else secs / (365.0 * 86_400)
    }

    /** IV (decimal) of one option at [ltp]; null where it cannot be solved (no forward, expired, below intrinsic). */
    fun iv(ltp: Double, right: String, forward: Double?, strike: Double, now: LocalDateTime, expiry: LocalDate): Double? =
        runCatching { MarketRecord.iv(ltp, right == "CE", forward, strike, now, expiry) }.getOrNull()?.takeIf { it.isFinite() && it > 0 }

    /**
     * The chain kept: the [each]-either-side strikes around [spot] (both rights), sorted by strike then right, with each
     * option's IV and its OI change from [firstOi] (the first OI seen today for "strike|right", and when).
     */
    fun chain(
        raws: List<Raw>, spot: Double?, now: LocalDateTime, expiry: LocalDate?, each: Int = EACH_SIDE,
        firstOi: (Double, String) -> Pair<Long, String>? = { _, _ -> null },
    ): List<Leg> {
        if (spot == null || spot <= 0 || raws.isEmpty()) return emptyList()
        val keep = MarketRecord.around(raws.map { it.strike }, spot, each).toSet()
        val inside = raws.filter { it.strike in keep && it.ltp > 0 }
        val fwd = expiry?.let { forward(inside, spot, years(now, it)) }
        return inside.distinctBy { it.strike to it.right }.sortedWith(compareBy({ it.strike }, { it.right })).map { r ->
            val base = r.oi?.let { firstOi(r.strike, r.right) }
            Leg(r.strike, r.right, r.ltp, r.oi, base?.let { b -> r.oi?.minus(b.first) }, expiry?.let { iv(r.ltp, r.right, fwd, r.strike, now, it) },
                r.ageMs, r.src, base?.second)
        }
    }

    /** Futures minus spot (null without both). */
    fun basis(future: Double?, spot: Double?): Double? = future?.let { MarketRecord.basis(it, spot) }

    /** "BANKNIFTY26OCT52000CE" / "BANKNIFTY-TEST-52000CE" -> (BANKNIFTY, 52000.0, CE); null for anything else (a future, MCX). */
    fun parseOption(symbol: String): Triple<String, Double, String>? {
        val s = symbol.uppercase(Locale.ENGLISH).replace(" ", "")
        val right = when { s.endsWith("CE") -> "CE"; s.endsWith("PE") -> "PE"; else -> return null }
        val name = Regex("^[A-Z]+").find(s)?.value ?: return null
        val und = INDEX_NAMES.filter { name.startsWith(it) }.maxByOrNull { it.length } ?: return null
        // The strike: the digits right before CE/PE, after the expiry's code (YYMDD, YYMON or a separator).
        val body = s.removeSuffix(right)
        val digits = Regex("(\\d+(?:\\.\\d+)?)$").find(body)?.value ?: return null
        // Weekly codes run the expiry's day into the strike (26O2052000): the strike is the trailing digits a multiple of 50 or 100.
        val strike = strikeOf(digits) ?: return null
        return Triple(und, strike, right)
    }

    private val INDEX_NAMES = listOf("MIDCPNIFTY", "FINNIFTY", "BANKNIFTY", "NIFTY", "SENSEX", "BANKEX")

    private fun strikeOf(digits: String): Double? {
        if (digits.contains('.')) return digits.toDoubleOrNull()
        // A monthly symbol's digits are the strike; a weekly's run its expiry code (YYMDD, or the day after a letter month) into it.
        for (cut in 0..5) {
            if (digits.length - cut < 4) break
            val v = digits.substring(cut).toLongOrNull() ?: continue
            if (v % 50 == 0L && v in 1_000..200_000) return v.toDouble()
        }
        return null
    }

    // ---- the lines ----------------------------------------------------------------------------------------------------

    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** A file's first line: the schema, the kind of file, the day and the conventions a reader needs. */
    fun header(file: String, day: LocalDate, app: String = ""): String = obj(
        "t" to "H", "schema" to SCHEMA, "file" to file, "day" to day.toString(), "tz" to "Asia/Kolkata", "app" to app,
        "iv" to "Black-76 on the ATM put-call parity forward (else spot*e^(${RATE}T)); calendar time to expiry 15:30 IST",
        "oiChange" to "from the first OI this app saw today (oiFrom)", "eachSide" to EACH_SIDE,
    )

    fun snapshot(s: Snapshot): String = obj(
        "t" to s.kind.code, "at" to s.atMs, "arm" to s.arm, "symbol" to s.symbol, "und" to s.underlying, "side" to s.side,
        "qty" to s.qty, "price" to s.price, "intended" to s.intended, "paper" to s.paper, "exit" to s.exit,
        "spot" to s.spot, "fut" to s.future, "basis" to basis(s.future, s.spot), "vix" to s.vix, "expiry" to s.expiry?.toString(),
        "decided" to s.timing.decidedMs, "sent" to s.timing.sentMs, "filled" to s.timing.filledMs, "exch" to s.timing.exchMs,
        "book" to s.book?.let { book(it) }, "chain" to Json(legs(s.chain)), "note" to s.note,
    )

    fun minute(m: Minute): String = obj(
        "t" to "M", "m" to m.minute.format(HM), "ix" to m.index,
        "o" to m.bar?.open, "h" to m.bar?.high, "l" to m.bar?.low, "c" to m.bar?.close,
        "spot" to m.spot, "fut" to m.future, "basis" to basis(m.future, m.spot), "vix" to m.vix, "expiry" to m.expiry?.toString(),
        "chain" to Json(legs(m.chain)), "missing" to m.missing.takeIf { it.isNotEmpty() }?.let { l -> Json(l.joinToString(",", "[", "]") { str(it) }) },
    )

    /** A gap: what could not be recorded and why (words only). */
    fun gap(atMs: Long, what: String, why: String): String = obj("t" to "G", "at" to atMs, "what" to what, "why" to why)

    private fun book(b: Book): Json = Json(obj(
        "ltp" to b.ltp, "bid" to b.bid, "ask" to b.ask, "bidQty" to b.bidQty, "askQty" to b.askQty, "age" to b.ageMs,
        "bids" to Json(levels(b.bids)), "asks" to Json(levels(b.asks)),
    ))

    private fun levels(l: List<Level>) = l.take(5).joinToString(",", "[", "]") { "[${num(it.price)},${it.qty},${it.orders}]" }

    /** The chain as compact arrays: [strike, right, ltp, oi, oiChange, iv, ageMs, src]. */
    private fun legs(l: List<Leg>) = l.joinToString(",", "[", "]") {
        "[${num(it.strike)},${str(it.right)},${num(it.ltp)},${it.oi ?: "null"},${it.oiChange ?: "null"},${it.iv?.let { v -> num(v, 4) } ?: "null"}," +
            "${it.ageMs ?: "null"},${str(it.src)}]"
    }

    /** Already-JSON text, written as is ([TickZone] writes its rows with it too). */
    internal class Json(val json: String)

    internal fun obj(vararg kv: Pair<String, Any?>): String = kv.filter { it.second != null }.joinToString(",", "{", "}") { (k, v) ->
        str(k) + ":" + when (v) {
            is Json -> v.json
            is String -> str(v)
            is Boolean, is Int, is Long -> v.toString()
            is Double -> num(v)
            else -> str(v.toString())
        }
    }

    fun num(x: Double, places: Int = 2): String =
        if (!x.isFinite()) "null" else java.math.BigDecimal(x).setScale(places, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun str(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' -> b.append(String.format(Locale.ENGLISH, "\\u%04x", c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    /**
     * The order path's hand-off: [offer] is a short synchronized add (never a wait, never I/O); past [cap] the oldest is
     * dropped and counted. The writer takes everything with [drain].
     */
    class Queue<T>(private val cap: Int = QUEUE_CAP) {
        private val items = ArrayDeque<T>()
        @Volatile var dropped = 0L
            private set

        fun offer(x: T) {
            synchronized(items) {
                items.addLast(x)
                while (items.size > cap) { items.removeFirst(); dropped++ }
            }
        }

        fun drain(): List<T> = synchronized(items) { ArrayList(items).also { items.clear() } }
        fun size(): Int = synchronized(items) { items.size }
    }

    // ---- the folder ----------------------------------------------------------------------------------------------------

    data class DayDir(val day: LocalDate, val bytes: Long)
    data class Plan(val delete: List<LocalDate>, val keptBytes: Long)

    /**
     * What goes: every day older than [keepDays] days before [today] (today counts as one), then, over [cap], the oldest
     * days until under it. Today's folder never goes.
     */
    fun rotate(days: List<DayDir>, today: LocalDate, keepDays: Int, cap: Long = TOTAL_CAP_BYTES): Plan {
        val keepFrom = today.minusDays((keepDays.coerceAtLeast(1) - 1).toLong())
        val sorted = days.sortedBy { it.day }
        val delete = sorted.filter { it.day.isBefore(keepFrom) }.map { it.day }.toMutableList()
        var kept = sorted.filter { it.day !in delete }
        var bytes = kept.sumOf { it.bytes }
        while (bytes > cap) {
            val oldest = kept.firstOrNull { it.day != today } ?: break
            delete += oldest.day; kept = kept - oldest; bytes -= oldest.bytes
        }
        return Plan(delete.sorted(), bytes)
    }

    /** The day's folder is full: minute rows stop for the day (a trade's snapshot is still written). */
    fun overDayBudget(dayBytes: Long): Boolean = dayBytes >= DAY_BUDGET_BYTES

    /** A retention choice read back: one of [KEEP_CHOICES], else the default. */
    fun keepDays(saved: Int?): Int = saved?.takeIf { it in KEEP_CHOICES } ?: DEFAULT_KEEP_DAYS

    // ---- health -------------------------------------------------------------------------------------------------------

    /**
     * Recording stopped: NSE open, the watch running and recording on, and nothing written for [STALL_MS] since the later of
     * the last write and when this process began recording ([sinceMs]).
     */
    fun stalled(nseOpen: Boolean, watchAlive: Boolean, on: Boolean, lastWriteMs: Long?, sinceMs: Long, nowMs: Long): Boolean =
        nseOpen && watchAlive && on && nowMs - maxOf(lastWriteMs ?: 0L, sinceMs) > STALL_MS

    // ---- the card ------------------------------------------------------------------------------------------------------

    data class Status(val days: Int, val bytes: Long, val lastWriteMs: Long?, val keepDays: Int, val dropped: Long = 0)

    fun card(s: Status, nowMs: Long): List<Pair<String, String>> = listOf(
        "Days kept" to "${s.days} (keeps ${s.keepDays} days)",
        "Size" to MarketRecord.size(s.bytes),
        "Last written" to (s.lastWriteMs?.let { ago(nowMs - it) } ?: "never"),
    ) + if (s.dropped > 0) listOf("Dropped (queue full)" to "${s.dropped}") else emptyList()

    private fun ago(ms: Long): String {
        val m = (ms.coerceAtLeast(0) / 60_000)
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / 1440} days ago"
        }
    }

    /** The export's README (no secret, account figure or token is ever in a recording). */
    val README: String = """
        IraAlgo live recordings (schema $SCHEMA)
        One folder a day (YYYY-MM-DD). Times are epoch milliseconds ("at") or IST HH:mm ("m").
        market.jsonl.gz       plain gzip, JSON lines: H header, M minute rows (index OHLC, futures, basis, VIX,
                              nearest-expiry chain ATM +/-$EACH_SIDE as [strike,right,ltp,oi,oiChange,iv,ageMs,src]), G gaps.
        trades.jsonl.gz.vault Boss's own records: each line base64(AES-256-GCM(gzip(JSON lines))) with a key that never
                              leaves the phone's Keystore; readable only by the app on that phone. S signal, O order sent,
                              F fill (paper or live), each with the market snapshot at that moment.
        IV: Black-76 on the ATM put-call parity forward (else spot*e^(${RATE}T)), calendar time to expiry 15:30 IST.
        OI change: from the first OI the app saw that day (oiFrom).
    """.trimIndent()

    /**
     * The README of the PIN-confirmed export (12 Oct): as [README], but Boss's trade files are written decrypted, as plain
     * JSON lines ("trades.jsonl"), into the file Boss picked.
     */
    val DECRYPTED_README: String = """
        IraAlgo live recordings (schema $SCHEMA), exported after your PIN: your trades are DECRYPTED in this file.
        Keep it private. One folder a day (YYYY-MM-DD). Times are epoch milliseconds ("at") or IST HH:mm ("m").
        market.jsonl.gz       plain gzip, JSON lines: H header, M minute rows (index OHLC, futures, basis, VIX,
                              nearest-expiry chain ATM +/-$EACH_SIDE as [strike,right,ltp,oi,oiChange,iv,ageMs,src]), G gaps.
        trades.jsonl          Boss's own records, plain JSON lines (on the phone they stay encrypted): H header, S signal,
                              O order sent, F fill (paper or live), each with the market snapshot at that moment. A line
                              that could not be decrypted is a G gap line.
        IV: Black-76 on the ATM put-call parity forward (else spot*e^(${RATE}T)), calendar time to expiry 15:30 IST.
        OI change: from the first OI the app saw that day (oiFrom).
    """.trimIndent()
}
