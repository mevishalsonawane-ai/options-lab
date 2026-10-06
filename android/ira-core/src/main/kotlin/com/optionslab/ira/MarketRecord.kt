package com.optionslab.ira

import com.optionslab.engine.options.OptionMath
import com.optionslab.engine.options.OptionType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The market recorder's record (Boss's approval, 06 Oct 2026): from today, the data that could explain the market's
 * direction and that the historical store does not keep - the news as the app first saw it, the scheduled events, the
 * index futures with their basis, the order book at the money, a wider option chain, the FII/DII figures - written as
 * it happens, one file a trading day, so a study in 3-6 months can test it. Pure: the formats, the window, the
 * encrypted frames' layout, rotation and the size cap, the no-secrets scrub, the IV, the status and Jarvis's answer.
 * The app reads its own feeds and writes the files ([com.optionslab.app.data.MarketRecorder]); nothing here touches
 * the network, a file or an order.
 *
 * One record a line, comma separated, the first field its kind (README in every export says the same):
 *  H,v1,date                                            the file's header (first frame of each writer start)
 *  N,firstSeen,source,published,tone,toneWord,markets,matters,also,title,link   a headline or official notice
 *  E,day,time,name,owner                                a scheduled event (time blank: the calendar has none)
 *  X,date,who,buy,sell,net                              FII/DII cash figures (Rs crore) as NSE gives them
 *  S,time,index,last                                    an index's level that minute
 *  F,time,name,symbol,expiry,last,volume,oi,spot,basis  an index future that minute (volume: the day's so far)
 *  B,minute,name,symbol,open,high,low,close,volume,oi   an index future's 1-minute candle (read after the close)
 *  D,time,what,last,bid,bidQty,ask,askQty,buyQty,sellQty,spread,src   the book's best and totals (src s=stream, r=quote)
 *  C,time,underlying,expiry,strike,right,last,volume,oi,iv,bid,ask   the option chain, every 5 minutes
 *  L,time,underlying,expiry,strike,right,last,bid,ask,bidQty,askQty  an option at Rs 1-5 (the Hero spread question)
 *  G,time,what,why                                      a gap: something that could not be recorded, and why
 */
object MarketRecord {
    const val VERSION = "v1"

    /** The recorder writes only between these, on trading days (IST). */
    val FROM: LocalTime = LocalTime.of(9, 0)
    val UNTIL: LocalTime = LocalTime.of(15, 35)

    /** Inside the recording window: a trading day, 09:00-15:35 IST. */
    fun inWindow(tradingDay: Boolean, t: LocalTime): Boolean = tradingDay && !t.isBefore(FROM) && !t.isAfter(UNTIL)

    /** Months of days kept; older day files are deleted. */
    const val KEEP_MONTHS = 12L
    /** Above this the card and Jarvis warn (a day is about 0.6-1 MB: a year of trading days is about 250 MB). */
    const val BUDGET_BYTES = 500_000_000L
    /** Never more than this: the oldest days go first (never today's). */
    const val CAP_BYTES = 1_000_000_000L

    /** The chain snapshot's strikes either side of the money, and how often (minutes). */
    const val CHAIN_STRIKES = 15
    const val CHAIN_EVERY_MIN = 5
    /** The order book's strikes either side of the money, every minute. */
    const val DEPTH_STRIKES = 2
    /** The cheap options (Rs 1-5) the Hero spread question needs: those inside the chain snapshot (no extra read). */
    const val CHEAP_MIN = 1.0
    const val CHEAP_MAX = 5.0

    /** The indices whose futures are recorded, by the app's names. */
    val FUTURES = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")
    /** The chain's and the book's indices. */
    val CHAINS = listOf("NIFTY", "BANKNIFTY")

    private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    // ---- the fields --------------------------------------------------------------------------------------------------

    /** A text field: quoted when it holds a comma, a quote or a line break (line breaks become spaces). */
    fun esc(s: String): String {
        val one = s.replace('\r', ' ').replace('\n', ' ')
        return if (one.any { it == ',' || it == '"' }) "\"" + one.replace("\"", "\"\"") + "\"" else one
    }

    /** A number, short: blank for none, no decimals when whole, else at most [places] (trailing zeros dropped). */
    fun num(x: Double?, places: Int = 2): String {
        if (x == null || !x.isFinite()) return ""
        if (x == Math.rint(x) && kotlin.math.abs(x) < 1e15) return x.toLong().toString()
        return String.format(Locale.ENGLISH, "%.${places}f", x).trimEnd('0').trimEnd('.')
    }

    /** A record's fields back ([esc]'s quoting undone). */
    fun fields(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** The story key ([News.key]) of a written N record, else null. */
    fun newsKey(line: String): String? {
        if (!line.startsWith("N,")) return null
        val f = fields(line)
        if (f.size < 11) return null
        return f[10].ifBlank { f[9] }.trim()
    }

    private fun n(x: Long?) = x?.toString() ?: ""
    fun hms(t: LocalTime): String = t.format(HMS)
    fun hm(t: LocalTime): String = t.format(HM)
    private fun line(vararg f: String) = f.joinToString(",")

    // ---- the records -------------------------------------------------------------------------------------------------

    fun header(day: LocalDate): String = line("H", VERSION, day.toString())

    /** A headline as the app first saw it: [firstSeen] to the second, its own tone, markets and "matters" tags. */
    data class News(
        val firstSeen: LocalTime, val source: String, val published: Instant?, val title: String, val link: String,
        val tone: Double, val toneWord: String, val markets: List<String>, val matters: Boolean, val also: List<String> = emptyList(),
    ) {
        /** One story once a day: its link, else its words. */
        val key: String get() = link.ifBlank { title }.trim()
    }

    fun news(n: News): String = line("N", hms(n.firstSeen), esc(n.source),
        n.published?.atZone(IST)?.toLocalDateTime()?.withNano(0)?.toString() ?: "", num(n.tone), n.toneWord,
        n.markets.joinToString(";"), if (n.matters) "1" else "0", esc(n.also.joinToString(";")), esc(n.title), esc(n.link))

    data class Event(val day: LocalDate, val time: LocalTime?, val name: String, val owner: Boolean)

    fun event(e: Event): String = line("E", e.day.toString(), e.time?.let { hm(it) } ?: "", esc(e.name), if (e.owner) "1" else "0")

    fun flow(date: String, who: String, buy: Double, sell: Double, net: Double): String =
        line("X", esc(date), esc(who), num(buy), num(sell), num(net))

    /** One instrument's quote as read (from the stream or Kite's quote call). */
    data class Quote(
        val last: Double, val volume: Long? = null, val oi: Long? = null,
        val bid: Double? = null, val bidQty: Long? = null, val ask: Double? = null, val askQty: Long? = null,
        val buyQty: Long? = null, val sellQty: Long? = null, val stream: Boolean = false,
    ) {
        val spread: Double? get() = if (bid != null && ask != null && bid > 0 && ask > 0) ask - bid else null
    }

    fun spot(t: LocalTime, index: String, last: Double): String = line("S", hms(t), index, num(last))

    /** Basis: the future less the index, blank without the index's level. */
    fun basis(future: Double, spot: Double?): Double? = if (spot == null || spot <= 0 || future <= 0) null else future - spot

    fun future(t: LocalTime, name: String, symbol: String, expiry: LocalDate, q: Quote, spot: Double?): String =
        line("F", hms(t), name, esc(symbol), expiry.toString(), num(q.last), n(q.volume), n(q.oi), num(spot), num(basis(q.last, spot)))

    fun bar(minute: LocalTime, name: String, symbol: String, open: Double, high: Double, low: Double, close: Double, volume: Long, oi: Long): String =
        line("B", hm(minute), name, esc(symbol), num(open), num(high), num(low), num(close), volume.toString(), oi.toString())

    fun depth(t: LocalTime, what: String, q: Quote): String =
        line("D", hms(t), esc(what), num(q.last), num(q.bid), n(q.bidQty), num(q.ask), n(q.askQty), n(q.buyQty), n(q.sellQty),
            num(q.spread), if (q.stream) "s" else "r")

    fun chain(t: LocalTime, underlying: String, expiry: LocalDate, strike: Double, right: String, q: Quote, iv: Double?): String =
        line("C", hm(t), underlying, expiry.toString(), num(strike), right, num(q.last), n(q.volume), n(q.oi), num(iv?.let { it * 100 }),
            num(q.bid), num(q.ask))

    fun cheap(t: LocalTime, underlying: String, expiry: LocalDate, strike: Double, right: String, q: Quote): String =
        line("L", hm(t), underlying, expiry.toString(), num(strike), right, num(q.last), num(q.bid), num(q.ask), n(q.bidQty), n(q.askQty))

    /** Is [last] one of the Rs 1-5 options the Hero spread question needs? */
    fun isCheap(last: Double): Boolean = last in CHEAP_MIN..CHEAP_MAX

    /** A gap: [what] could not be recorded; [why] is a reason in the app's words or an exception's class, never a message. */
    fun gap(t: LocalTime, what: String, why: String): String = line("G", hms(t), esc(what), esc(why))

    /** What a failure is called in a gap: its class's short name only (a message can carry a URL, a key or a token). */
    fun why(e: Throwable): String = e.javaClass.simpleName.ifBlank { "Error" }

    // ---- no secrets --------------------------------------------------------------------------------------------------

    /** A credential's shape: "access_token=...", "api_key: ...", an Authorization header's value, a long bearer token. */
    private val SECRETISH = Regex("(?i)\\b(access_token|api_key|api_secret|request_token|enctoken)\\s*[=:]\\s*[^,\\s\"&]*" +
        "|\\bauthorization\\s*:\\s*[^,\"]*|\\bbearer\\s+[a-z0-9._~+/=-]{16,}")

    /**
     * [line] with every [secrets] value (the session token, the API key; anything 6+ characters) and anything shaped like
     * a credential ("access_token=...", "Authorization: ...") replaced: no record may carry one, whatever a feed returned.
     */
    fun scrub(line: String, secrets: List<String?>): String {
        var out = line
        for (s in secrets) if (s != null && s.length >= 6 && out.contains(s)) out = out.replace(s, "***")
        return if (SECRETISH.containsMatchIn(out)) SECRETISH.replace(out, "***") else out
    }

    // ---- the strikes --------------------------------------------------------------------------------------------------

    /** The [each]-either-side strikes around [spot] from the listed [strikes] (sorted, distinct; the money's own first among equals). */
    fun around(strikes: Collection<Double>, spot: Double, each: Int): List<Double> {
        val s = strikes.distinct().sorted()
        if (s.isEmpty()) return emptyList()
        val atm = s.indices.minBy { kotlin.math.abs(s[it] - spot) }
        return s.subList(maxOf(0, atm - each), minOf(s.size, atm + each + 1))
    }

    /** The listed strike nearest [spot]. */
    fun atm(strikes: Collection<Double>, spot: Double): Double? = strikes.minByOrNull { kotlin.math.abs(it - spot) }

    /** The chain's forward from put-call parity at the money: strike + call - put (null without both). */
    fun forward(strike: Double, call: Double?, put: Double?): Double? =
        if (call == null || put == null || call <= 0 || put <= 0) null else strike + call - put

    /**
     * Implied volatility (decimal) of an option at [last], from the chain's [forward], to its expiry's 15:30 IST from
     * [now] (calendar time). Null without a forward, at or after the expiry, or where the solver has none.
     */
    fun iv(last: Double, call: Boolean, forward: Double?, strike: Double, now: LocalDateTime, expiry: LocalDate): Double? {
        if (forward == null || last <= 0) return null
        val secs = Duration.between(now, expiry.atTime(15, 30)).seconds
        if (secs <= 60) return null
        return OptionMath.impliedVol(last, if (call) OptionType.CE else OptionType.PE, forward, strike, secs / (365.0 * 86_400))
    }

    // ---- the encrypted frames -----------------------------------------------------------------------------------------

    /** No frame is bigger than this; a length past it is a broken file, not a frame. */
    const val MAX_FRAME = 8 shl 20

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            d.setInput(b); d.finish()
            val out = ByteArrayOutputStream(b.size / 3 + 64)
            val buf = ByteArray(8192)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        } finally { d.end() }
    }

    private fun inflate(b: ByteArray): ByteArray {
        val i = Inflater(true)
        try {
            i.setInput(b)
            val out = ByteArrayOutputStream(b.size * 4 + 64)
            val buf = ByteArray(8192)
            while (!i.finished()) {
                val got = i.inflate(buf)
                if (got == 0 && (i.needsInput() || i.needsDictionary())) break
                out.write(buf, 0, got)
                if (out.size() > MAX_FRAME * 8) throw IllegalStateException("frame too large")
            }
            return out.toByteArray()
        } finally { i.end() }
    }

    /**
     * One append: [lines] packed (deflate) and sealed by [seal] (the app's Keystore AES-GCM), behind a 4-byte length.
     * Each append stands alone, so a power cut mid-write loses that append only ([read] skips a torn tail).
     */
    fun frame(lines: List<String>, seal: (ByteArray) -> ByteArray): ByteArray {
        val sealed = seal(deflate(lines.joinToString("\n").toByteArray(Charsets.UTF_8)))
        return ByteBuffer.allocate(4 + sealed.size).putInt(sealed.size).put(sealed).array()
    }

    /** What a file held: its lines in order, and the frames that could not be opened (torn, tampered, another key). */
    data class Read(val lines: List<String>, val bad: Int)

    fun read(bytes: ByteArray, open: (ByteArray) -> ByteArray): Read {
        val out = ArrayList<String>()
        var bad = 0
        var at = 0
        while (at < bytes.size) {
            if (bytes.size - at < 4) { bad++; break }
            val len = ByteBuffer.wrap(bytes, at, 4).int
            if (len <= 0 || len > MAX_FRAME || at + 4 + len > bytes.size) { bad++; break }
            val sealed = bytes.copyOfRange(at + 4, at + 4 + len)
            at += 4 + len
            val text = runCatching { inflate(open(sealed)).toString(Charsets.UTF_8) }.getOrNull()
            if (text == null) { bad++; continue }
            if (text.isNotEmpty()) out += text.split('\n')
        }
        return Read(out, bad)
    }

    // ---- rotation and the cap -----------------------------------------------------------------------------------------

    data class DayFile(val day: LocalDate, val bytes: Long)

    /** Which day files to delete: older than [keepMonths], then the oldest while over [cap] (never [today]'s). */
    data class Plan(val delete: List<LocalDate>, val keptBytes: Long, val overBudget: Boolean)

    fun rotate(files: List<DayFile>, today: LocalDate, keepMonths: Long = KEEP_MONTHS, cap: Long = CAP_BYTES, budget: Long = BUDGET_BYTES): Plan {
        val cut = today.minusMonths(keepMonths)
        val sorted = files.sortedBy { it.day }
        val delete = sorted.filter { it.day.isBefore(cut) }.map { it.day }.toMutableList()
        var total = sorted.filter { it.day !in delete }.sumOf { it.bytes }
        for (f in sorted) {
            if (total <= cap) break
            if (f.day in delete || f.day == today) continue
            delete += f.day; total -= f.bytes
        }
        return Plan(delete, total, total > budget)
    }

    // ---- gaps kept per day ("2026-10-06=3;2026-10-07=0") ----------------------------------------------------------------

    fun gapsDecode(s: String?): Map<LocalDate, Int> = s.orEmpty().split(';').mapNotNull { p ->
        val d = runCatching { LocalDate.parse(p.substringBefore('=')) }.getOrNull() ?: return@mapNotNull null
        d to (p.substringAfter('=', "").toIntOrNull() ?: return@mapNotNull null)
    }.toMap()

    /** The newest [keep] days only. */
    fun gapsEncode(m: Map<LocalDate, Int>, keep: Int = 400): String =
        m.entries.sortedBy { it.key }.takeLast(keep).joinToString(";") { "${it.key}=${it.value}" }

    // ---- the status, the card and Jarvis ------------------------------------------------------------------------------

    data class Status(
        val on: Boolean, val days: Int, val bytes: Long, val first: LocalDate?, val last: LocalDate?,
        val lastWrite: LocalDateTime?, val gapsToday: Int, val gapsTotal: Int, val overBudget: Boolean,
    )

    fun size(b: Long): String = when {
        b >= 1_000_000_000L -> String.format(Locale.ENGLISH, "%.2f GB", b / 1e9)
        b >= 1_000_000L -> String.format(Locale.ENGLISH, "%.1f MB", b / 1e6)
        b >= 1_000L -> String.format(Locale.ENGLISH, "%.0f KB", b / 1e3)
        else -> "$b bytes"
    }

    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.year}"

    /** "14:32:05 today" / "15:34:58 on 5 Oct 2026". */
    fun whenText(t: LocalDateTime, today: LocalDate): String =
        hms(t.toLocalTime()) + if (t.toLocalDate() == today) " today" else " on ${date(t.toLocalDate())}"

    /** The card's lines, label to value. */
    fun card(s: Status, today: LocalDate): List<Pair<String, String>> = listOf(
        "Storage used" to size(s.bytes) + if (s.overBudget) " (over the ${size(BUDGET_BYTES)} budget)" else "",
        "Days recorded" to (if (s.days == 0) "none yet" else "${s.days}" + (s.first?.let { " (since ${date(it)})" } ?: "")),
        "Last write" to (s.lastWrite?.let { whenText(it, today) } ?: "none yet"),
        "Gaps" to "${s.gapsToday} today, ${s.gapsTotal} in all",
    )

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

    /** The recorder named, or data said with recording. */
    private val RECORDER = Regex(" market (data )?recorder ")
    private val DATA = Regex(" data ")
    private val RECORDED = Regex(" (recorded|recording|record kiya|record kiye|record hua|record hue|record ho|record kar) ")
    /** "How much / how many days / is it on / status": a question about it, not a change to it. */
    private val ASK = Regex(" (how much|how many|kitna|kitne|kitni|is the|is it|is your|status|working|running|chal raha|chal rahi|what|which|when|kab|kya|size|space|storage|days|gaps|last) ")
    /** A change or something else recorded (a voice, a call, the screen, a price record): not this question. */
    private val NOT = Regex(" (delete|remove|wipe|erase|clear|export|send|share|turn|switch|disable|enable|pause|resume|voice|audio|call recording|screen|video|mic|microphone|record high|record low|all time) ")

    fun asked(text: String): Boolean = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(text: String): Boolean {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return false
        return (RECORDER.containsMatchIn(t) || DATA.containsMatchIn(t) && RECORDED.containsMatchIn(t)) && ASK.containsMatchIn(t)
    }

    /** Jarvis's answer: how much is recorded, since when, the last write and the gaps (his own data; nothing acts). */
    fun answer(s: Status, today: LocalDate): String {
        if (!s.on && s.days == 0) return "The market recorder is off, Boss, and nothing is recorded yet. Its switch is in More, Data & Harvest."
        if (s.days == 0) return "Nothing recorded yet, Boss: the market recorder writes on trading days from 9:00 to 15:35 while the market watch runs."
        val since = s.first?.let { " since ${date(it)}" } ?: ""
        val last = s.lastWrite?.let { ", last written at ${whenText(it, today)}" } ?: ""
        val gaps = when {
            s.gapsTotal == 0 -> " No gaps so far."
            s.gapsToday > 0 -> " ${s.gapsTotal} gap${if (s.gapsTotal == 1) "" else "s"} in all, ${s.gapsToday} today: parts that could not be read, skipped without slowing the trading."
            else -> " ${s.gapsTotal} gap${if (s.gapsTotal == 1) "" else "s"} in all, none today."
        }
        val budget = if (s.overBudget) " It is over its ${size(BUDGET_BYTES)} budget: the oldest days go first." else ""
        val off = if (!s.on) " The recorder is switched off now." else ""
        return "${s.days} trading day${if (s.days == 1) "" else "s"} of market data recorded$since, ${size(s.bytes)} on this phone$last.$gaps$budget$off " +
            "Export it from More, Data & Harvest, Boss."
    }

    /** The README written into every export. */
    val README: String = """
        IraAlgo market recorder export ($VERSION). One CSV per trading day (IST), one record per line; the first field is the kind:
        H,version,date
        N,firstSeen,source,published,tone,toneWord,markets,matters,also,title,link   (news and official notices; firstSeen = when the app first had it)
        E,day,time,name,owner                                (scheduled events; time blank when the calendar has none)
        X,date,who,buy,sell,net                              (FII/DII cash market, Rs crore, as NSE publishes it)
        S,time,index,last                                    (index level that minute)
        F,time,name,symbol,expiry,last,volume,oi,spot,basis  (index future that minute; volume = the day's so far)
        B,minute,name,symbol,open,high,low,close,volume,oi   (index future 1-minute candles, read after the close)
        D,time,what,last,bid,bidQty,ask,askQty,buyQty,sellQty,spread,src   (order book; src s = live stream, r = quote call)
        C,time,underlying,expiry,strike,right,last,volume,oi,iv,bid,ask    (option chain every 5 min, ATM +/-$CHAIN_STRIKES; iv in %, from parity)
        L,time,underlying,expiry,strike,right,last,bid,ask,bidQty,askQty   (options at Rs 1-5 inside the chain snapshot)
        G,time,what,why                                      (a gap: what could not be recorded and why)
        Times are IST. Blank = not available.
    """.trimIndent()
}
