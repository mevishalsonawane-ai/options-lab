package com.optionslab.ira

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * What changed since this morning (reasoning, round 15): "what changed since this morning?", "what's different since the
 * open?", "anything new since 9:45?", "now versus this morning", "subah se kya badla". The morning is one mark, [MARK] -
 * the first half hour done, when the morning's facts have settled (the structure measure needs 30 minutes) - and each of
 * the morning's facts is set against now, ranked by how big the change is against a plain step:
 *
 *  - each index asked (Nifty and BankNifty when none is): its price then and now ([MOVE_STEP] a step), the opening gap
 *    from the last close open at the mark and filled since (or still open), the day's structure read then and now by
 *    [Structure]'s own measure (trend-like up or down, range-like, in between), a new high or low of the day beyond the
 *    morning's range; India VIX then and now ([VIX_STEP] a step);
 *  - the option chain: Jarvis's first chain read of the day against his newest - max pain, the biggest call and put open
 *    interest strikes (the walls), in strikes moved, and the put-call ratio ([PCR_STEP] a step); a first read long after
 *    the mark is said with its time;
 *  - the news: the themes in today's headlines up to the mark against the themes new since ([NewsDesk.tags]; index names
 *    are not themes);
 *  - Boss's open positions, on an unlocked phone only and only when the app noted them that morning: legs opened, closed
 *    or resized since. On a locked phone they are left out, and said so. Zerodha's legs are set side by side only when
 *    both reads read them (round 16): a morning read the broker did not answer is said plainly, never taken as no legs.
 *
 * The [TOP] biggest are said in order, with the facts that did not change after them. Facts from the phone's own data,
 * never a cause, a forecast or advice; nothing here acts. Pure.
 */
object SinceMorning {
    /** The morning mark: the first half hour of the session done. */
    val MARK: LocalTime = LocalTime.of(9, 45)
    /** At most this many changes are said; the rest are counted. */
    const val TOP = 5
    /** A change scoring under this is "little changed", not ranked. */
    const val SMALL = 0.5
    /** One step of the index's move from the mark, in percent. */
    const val MOVE_STEP = 0.3
    /** One step of India VIX's change from the mark, in percent. */
    const val VIX_STEP = 3.0
    /** One step of the put-call ratio. */
    const val PCR_STEP = 0.15
    /** A gap smaller than this (percent of the last close) is no gap. */
    const val GAP_MIN = 0.15
    /** A first chain read this long after the mark is said with its time (minutes). */
    const val LATE_CHAIN = 45L
    /** A change in Boss's own positions counts this much (his own book ranks high). */
    const val POSITION_SCORE = 2.5

    enum class Kind { MOVE, GAP, STRUCTURE, EXTREME, VIX, MAX_PAIN, CALL_WALL, PUT_WALL, PCR, NEWS, POSITIONS }

    /** One fact that changed since the mark, with its [score] (1.0 is one plain step). */
    data class Change(val kind: Kind, val text: String, val score: Double)

    /** What came out of one reader: the [changes], the facts that held (short, said after) and [notes] always said. */
    data class Found(val changes: List<Change> = emptyList(), val held: List<String> = emptyList(), val notes: List<String> = emptyList()) {
        operator fun plus(o: Found) = Found(changes + o.changes, held + o.held, notes + o.notes)
    }

    /** One open leg of Boss's: [where] "Paper" or "Zerodha", its [symbol] and signed [qty]. */
    data class Held(val where: String, val symbol: String, val qty: Int) {
        val key: String get() = "$where|$symbol"
    }

    /**
     * Whether Zerodha's legs were read: [READ]; [FAILED] - logged in, but the broker did not answer in time (or the read
     * failed); [LOGGED_OUT] - not logged in to Zerodha, so its legs could not be read at all.
     */
    enum class Zerodha { READ, FAILED, LOGGED_OUT }

    /**
     * Boss's open legs at the mark ([morning]; null: not noted that morning) and [now], with whether Zerodha's part of each
     * was read ([morningZerodha], [nowZerodha]). Zerodha's legs are compared only when both were read: a leg the morning's
     * read missed is never called opened since, nor a leg now's read missed closed.
     */
    data class Positions(val morning: List<Held>?, val now: List<Held>, val morningZerodha: Zerodha = Zerodha.READ, val nowZerodha: Zerodha = Zerodha.READ,
                         /** When the morning's Zerodha legs were read (null: not known); a read after [ZERODHA_LATE] is said with its time. */
                         val morningZerodhaAt: LocalTime? = null)

    /** What the app keeps for the morning: the [held] legs, whether Zerodha's were read ([zerodha]) and when ([zerodhaAt]). */
    data class Noted(val held: List<Held>, val zerodha: Zerodha, val zerodhaAt: LocalTime? = null)

    const val ZERODHA = "Zerodha"

    /** The morning note's reads count only in this window around the mark (09:40 to 10:15). */
    val WINDOW_FROM: LocalTime = MARK.minusMinutes(5)
    val WINDOW_TO: LocalTime = MARK.plusMinutes(30)
    /** A morning Zerodha read after this (Boss could have opened legs since the mark) is said with its time. */
    val ZERODHA_LATE: LocalTime = MARK.plusMinutes(5)

    fun inWindow(at: LocalTime): Boolean = !at.isBefore(WINDOW_FROM) && !at.isAfter(WINDOW_TO)

    /**
     * The morning's note after one more read near the mark, made at [at]: the first read kept, except that Zerodha's legs,
     * when the kept note could not read them and this read could, are taken from this read (its Paper legs stay the first
     * read's, nearer the mark), with the time it was read. A read that again could not read Zerodha changes nothing kept;
     * a read outside [WINDOW_FROM]-[WINDOW_TO] (one that finished late) changes nothing either - no note is made from it.
     */
    fun renote(kept: Noted?, legs: List<Held>, zerodha: Zerodha, at: LocalTime): Noted? {
        if (!inWindow(at)) return kept
        val read = legs.filter { it.qty != 0 }
        val t = at.withSecond(0).withNano(0)
        if (kept == null) return Noted(read, zerodha, if (zerodha == Zerodha.READ) t else null)
        if (kept.zerodha == Zerodha.READ || zerodha != Zerodha.READ) return kept
        return Noted(kept.held.filter { it.where != ZERODHA } + read.filter { it.where == ZERODHA }, Zerodha.READ, t)
    }

    /** True while the morning's note still wants a read: none kept, or Zerodha's legs not read yet. */
    fun wantsRead(kept: Noted?): Boolean = kept == null || kept.zerodha != Zerodha.READ

    private fun hhmm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    /** The note as kept on the phone: the day (with Zerodha's state when not read, or when read its time), then one leg a line. */
    fun encode(day: LocalDate, n: Noted): String {
        val head = day.toString() + when {
            n.zerodha != Zerodha.READ -> "|" + n.zerodha.name
            n.zerodhaAt != null -> "|" + Zerodha.READ.name + "|" + hhmm(n.zerodhaAt)
            else -> ""
        }
        return (listOf(head) + n.held.map { "${it.where}|${it.symbol}|${it.qty}" }).joinToString("\n")
    }

    /** [today]'s note from what is kept, or null (none, or another day's). A note kept before Zerodha's state was is read as read. */
    fun decode(text: String, today: LocalDate): Noted? {
        val lines = text.lines().filter { it.isNotBlank() }
        val head = lines.firstOrNull()?.split("|") ?: return null
        if (head[0] != today.toString()) return null
        val z = head.getOrNull(1)?.let { w -> Zerodha.values().firstOrNull { it.name == w } } ?: Zerodha.READ
        val at = head.getOrNull(2)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        return Noted(lines.drop(1).mapNotNull { l ->
            val p = l.split("|")
            if (p.size != 3) null else p[2].toIntOrNull()?.let { Held(p[0], p[1], it) }
        }, z, if (z == Zerodha.READ) at else null)
    }

    // ---- the question ---------------------------------------------------------------------------------------------

    private fun norm(text: String) = Spaced.words(text)

    private const val SINCE = "(since|from) (this |the |today s |today )?(morning|open|opening|market open|start of (the )?(day|session)|" +
        "9 ?15|9 ?30|9 ?45|morning brief|brief|9 am|9 am brief|nine)"
    private const val WHAT = "(what|anything|what s|whats|what all|tell me what|has anything|is anything|what else|what has|what have)"
    private val ASKED = rx(
        " $WHAT (has |have |s |is )?(really |actually )?(changed|different|new|happened)( in| for| with| on| about)?( [a-z]+){0,2} $SINCE |" +
        " (changes|what changed|what s changed|whats changed|what s new|whats new|what s different|whats different) $SINCE |" +
        " $SINCE (what|anything) (has |s |is )?(changed|different|new) |" +
        " (what s|whats|what is|how is) (today |the market |the day |it |now |nifty |banknifty )?(different|changed) (from|than|since|vs|versus) (this |the )?morning |" +
        // Round 14: "kya badla subah se", "kya change hua subah se".
        " (kya|kya kya) (badla|badal gaya|change hua|change hui|naya hai|alag hai) subah se |" +
        " (diff|difference|changes|update) (since|from|vs|versus|against|with) (this |the )?(morning|morning brief|9 am brief|brief) |" +
        " now (vs|versus|against|compared to|compared with) (this |the )?morning |" +
        " (this |the )?morning (vs|versus) now |" +
        // Hinglish: "subah se kya badla", "subah se ab tak kya change hua", "subah aur ab mein kya fark hai".
        " subah se (ab tak |abhi tak )?(kya|kya kya|kuch) (badla|badal gaya|badli|change hua|change hui|change|naya|alag) |" +
        " subah (aur|se) (ab|abhi) (mein|me|tak) (kya )?(fark|farak|badla|change) "
    )
    /** The chain, the news, prices alone, forecasts, advice and Jarvis's own ways are other questions. */
    private val NOT = rx(" (oi|open interest|max pain|maxpain|wall|walls|pcr|put call|chain|option chain|premium|premiums|iv|news|headline|headlines|" +
        "should|will|would|tomorrow|forecast|predict|expect|buy|sell|you learned|learn|learnt|how you work|your mind|my bot|my bots|rule|rules) ")

    fun asked(text: String): Boolean = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(text: String): Boolean {
        val t = norm(text)
        return !NOT.containsMatchIn(t) && ASKED.containsMatchIn(t)
    }

    /** The indices to set against the morning: those asked, else Nifty and BankNifty. */
    fun indices(markets: List<Market>): List<Market> =
        markets.filter { it in SharpMove.INDICES }.distinct().ifEmpty { listOf(Market.NIFTY, Market.BANKNIFTY) }

    // ---- the readers ----------------------------------------------------------------------------------------------

    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun k(x: Double) = if (x % 1.0 == 0.0) "%,.0f".format(Locale.ENGLISH, x) else "%,.1f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun pct(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun two(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private val markWord = "%02d:%02d".format(Locale.ENGLISH, MARK.hour, MARK.minute)

    private fun kindWord(r: Structure.Read): String = when (r.dayKind) {
        1 -> "trend-like up"; -1 -> "trend-like down"; 0 -> "range-like"; else -> "in between"
    }

    /** [m]'s price, gap, structure and day's extremes at the mark against now, from its 1-minute [bars]. */
    fun index(m: Market, bars: List<Candle>, today: LocalDate): Found {
        val mark = today.atTime(MARK)
        val day = bars.filter { it.t.toLocalDate() == today }.sortedBy { it.t }
        val am = day.filter { it.t.isBefore(mark) }
        val pm = day.filter { !it.t.isBefore(mark) }
        if (am.isEmpty() || pm.isEmpty()) return Found()
        val then = am.last().c; val now = day.last().c
        val move = (now - then) / then * 100
        val changes = ArrayList<Change>(); val held = ArrayList<String>()
        if (m == Market.VIX) {
            val s = abs(move) / VIX_STEP
            if (s >= SMALL) changes += Change(Kind.VIX, "India VIX is ${two(now)} against ${two(then)} at $markWord (${pct(move)}).", s)
            else held += "India VIX near its $markWord level (${two(now)})"
            return Found(changes, held)
        }
        val s = abs(move) / MOVE_STEP
        if (s >= SMALL) changes += Change(Kind.MOVE, "${m.label} is ${if (move > 0) "up" else "down"} ${pts(now - then)} points (${pct(move)}) since $markWord, " +
            "at ${n(now)} against ${n(then)}.", s)
        else held += "${m.label} within ${pts(now - then)} points of its $markWord price"

        // The opening gap from the last close: open at the mark and filled since, or still open.
        val prev = bars.filter { it.t.toLocalDate().isBefore(today) }.maxByOrNull { it.t }?.c
        if (prev != null && prev > 0) {
            val gap = (day.first().o - prev) / prev * 100
            if (abs(gap) >= GAP_MIN) {
                val up = gap > 0
                fun filled(c: Candle) = if (up) c.l <= prev else c.h >= prev
                val way = if (up) "up" else "down"
                if (am.none(::filled)) {
                    val at = pm.firstOrNull(::filled)
                    if (at != null) changes += Change(Kind.GAP, "${m.label}'s ${pct(gap)} gap $way from the last close (${n(prev)}) was still open at $markWord; " +
                        "it was filled at ${hm(at.t)}.", 2.0)
                    else held += "${m.label}'s gap $way (${pct(gap)}) still open"
                } else held += "${m.label}'s gap $way already filled before $markWord"
            }
        }

        // The day's structure, by the same measure then and now.
        val r0 = runCatching { Structure.read(m, bars.filter { it.t.isBefore(mark) }, today) }.getOrNull()
        val r1 = runCatching { Structure.read(m, bars, today) }.getOrNull()
        if (r0 != null && r1 != null) {
            val w0 = kindWord(r0); val w1 = kindWord(r1)
            if (w0 != w1) changes += Change(Kind.STRUCTURE, "${m.label}'s day read $w0 at $markWord and reads $w1 now (the net move from the open " +
                "${(r1.share * 100).toInt()}% of the day's range, against ${(r0.share * 100).toInt()}% then).",
                if (r0.dayKind != null && r1.dayKind != null) 2.0 else 1.5)
            else held += "${m.label}'s day still $w1, as at $markWord"
        }

        // A new high or low of the day beyond the morning's range.
        val amHi = am.maxOf { it.h }; val amLo = am.minOf { it.l }
        val amRange = (amHi - amLo).takeIf { it > 0 } ?: (then * MOVE_STEP / 100)
        val hi = pm.maxBy { it.h }; val lo = pm.minBy { it.l }
        if (hi.h > amHi) changes += Change(Kind.EXTREME, "${m.label} made a new high of the day since $markWord: ${n(hi.h)} at ${hm(hi.t)}, " +
            "${pts(hi.h - amHi)} points above the morning's high of ${n(amHi)}.", min(3.0, (hi.h - amHi) / amRange))
        if (lo.l < amLo) changes += Change(Kind.EXTREME, "${m.label} made a new low of the day since $markWord: ${n(lo.l)} at ${hm(lo.t)}, " +
            "${pts(amLo - lo.l)} points below the morning's low of ${n(amLo)}.", min(3.0, (amLo - lo.l) / amRange))
        if (hi.h <= amHi && lo.l >= amLo) held += "${m.label} inside its morning range (${n(amLo)} to ${n(amHi)})"
        return Found(changes, held)
    }

    private fun label(u: String) = when (u) { "NIFTY" -> "Nifty"; "BANKNIFTY" -> "BankNifty"; "FINNIFTY" -> "FinNifty"; "SENSEX" -> "Sensex"; else -> u }

    /** The first chain read of the day against the newest ([reads] of one underlying, today's). */
    fun chain(reads: List<ChainIntel.Read>, today: LocalDate): Found {
        val day = reads.filter { it.at.toLocalDate() == today }.sortedBy { it.at }
        if (day.size < 2) return Found()
        val first = day.first(); val last = day.last()
        if (!last.at.isAfter(first.at)) return Found()
        val name = label(first.underlying)
        val a = ChainDrift.point(first); val b = ChainDrift.point(last)
        val step = first.strikes.map { it.strike }.filter { it > 0 }.distinct().sorted().zipWithNext { x, y -> y - x }.filter { it > 0 }.minOrNull() ?: 50.0
        val then = if (first.at.isAfter(today.atTime(MARK).plusMinutes(LATE_CHAIN))) "at ${hm(first.at)} (my first chain read today was only then)" else "at ${hm(first.at)}"
        val changes = ArrayList<Change>(); val held = ArrayList<String>()
        fun strike(kind: Kind, what: String, x: Double?, y: Double?, weight: Double) {
            if (x == null || y == null) return
            if (x == y) { held += "$name's $what still ${k(y)}"; return }
            val steps = abs(y - x) / step
            changes += Change(kind, "$name's $what is ${k(y)} now against ${k(x)} $then, ${k(abs(y - x))} points ${if (y > x) "higher" else "lower"}.",
                min(3.0, steps * weight))
        }
        strike(Kind.MAX_PAIN, "max pain", a.maxPain, b.maxPain, 0.5)
        strike(Kind.CALL_WALL, "biggest call open interest", a.callWall, b.callWall, 0.6)
        strike(Kind.PUT_WALL, "biggest put open interest", a.putWall, b.putWall, 0.6)
        if (a.pcr != null && b.pcr != null) {
            val s = abs(b.pcr - a.pcr) / PCR_STEP
            if (s >= SMALL) changes += Change(Kind.PCR, "$name's put-call ratio is ${two(b.pcr)} now against ${two(a.pcr)} $then.", s)
            else held += "$name's put-call ratio near ${two(b.pcr)}"
        }
        return Found(changes, held)
    }

    /** Index names and India VIX are not news themes. */
    private val NOT_THEMES = setOf(NewsDesk.Tag.NIFTY, NewsDesk.Tag.BANKNIFTY, NewsDesk.Tag.FINNIFTY, NewsDesk.Tag.SENSEX, NewsDesk.Tag.VIX)

    /** The news themes new since the mark: in today's headlines after it, not in any up to it. */
    fun news(news: List<Headline>, zone: ZoneId, today: LocalDate, now: Instant): Found {
        val start = today.atStartOfDay(zone).toInstant(); val mark = today.atTime(MARK).atZone(zone).toInstant()
        val dated = news.filter { val at = it.at; at != null && !at.isBefore(start) && !at.isAfter(now) }
        val before = dated.filter { !it.at!!.isAfter(mark) }.flatMap { NewsDesk.tags(it.title) }.toSet() - NOT_THEMES
        val after = dated.filter { it.at!!.isAfter(mark) }
        val fresh = LinkedHashMap<NewsDesk.Tag, MutableList<Headline>>()
        for (h in after.sortedBy { it.at }) for (t in NewsDesk.tags(h.title)) if (t !in NOT_THEMES && t !in before) fresh.getOrPut(t) { ArrayList() } += h
        val changes = fresh.map { (t, hs) ->
            val first = LocalDateTime.ofInstant(hs.first().at!!, zone)
            Change(Kind.NEWS, "New in the news since $markWord: ${t.label} (${hs.size} headline${if (hs.size == 1) "" else "s"}, the first at ${hm(first)}).",
                min(2.5, 1.0 + 0.25 * (hs.size - 1)))
        }
        val held = if (before.isNotEmpty() && fresh.isEmpty() && dated.isNotEmpty())
            listOf("no new news theme since $markWord (the morning's: ${before.map { it.label }.sorted().joinToString(", ")})") else emptyList()
        return Found(changes, held)
    }

    private fun lots(q: Int) = "${if (q > 0) "long" else "short"} ${abs(q)}"

    private fun zLegs(k: Int) = "$k Zerodha leg${if (k == 1) "" else "s"}"

    /** Boss's open legs at the mark against now. Never called for a locked phone. */
    fun positions(p: Positions): Found {
        val allNow = p.now.filter { it.qty != 0 }
        val allMorning = p.morning?.filter { it.qty != 0 }
            ?: return Found(held = listOf("your positions not noted this morning; you hold ${allNow.size} open leg${if (allNow.size == 1) "" else "s"} now"))
        // Zerodha's legs are set side by side only when both reads read them; otherwise they are left out, and said so plainly.
        val both = p.morningZerodha == Zerodha.READ && p.nowZerodha == Zerodha.READ
        val notes = ArrayList<String>()
        if (!both) {
            val zNow = allNow.count { it.where == ZERODHA }
            val zMorning = allMorning.count { it.where == ZERODHA }
            when {
                p.nowZerodha != Zerodha.READ -> if (zMorning > 0 || p.nowZerodha == Zerodha.FAILED)
                    notes += (if (p.nowZerodha == Zerodha.FAILED) "Zerodha didn't answer in time just now" else "You're not logged in to Zerodha now") +
                        ", so your Zerodha legs are left out of this" + (if (zMorning > 0) " (this morning you held ${zLegs(zMorning)})" else "") + "."
                p.morningZerodha == Zerodha.FAILED -> notes += "This morning's Zerodha legs weren't read - Zerodha didn't answer in time when I noted them - " +
                    (if (zNow > 0) "so I can't say which of the ${zLegs(zNow)} you hold now are new since then." else "so a Zerodha leg closed since wouldn't show here.")
                zNow > 0 -> notes += "You weren't logged in to Zerodha when I noted this morning's legs, so I can't say which of the ${zLegs(zNow)} you hold now are new since then."
            }
        }
        // Read well after the mark, the morning's Zerodha legs may include ones Boss opened since: said with the read's time.
        val zAt = p.morningZerodhaAt
        if (both && zAt != null && zAt.isAfter(ZERODHA_LATE))
            notes += "This morning's Zerodha legs were read at ${hhmm(zAt)}, not at $markWord, " +
                "so a Zerodha leg opened before ${hhmm(zAt)} counts here as held this morning."
        val now = if (both) allNow else allNow.filter { it.where != ZERODHA }
        val morning = if (both) allMorning else allMorning.filter { it.where != ZERODHA }
        val was = morning.associateBy { it.key }; val is_ = now.associateBy { it.key }
        val changes = ArrayList<Change>()
        for (h in now) {
            val w = was[h.key]
            if (w == null) changes += Change(Kind.POSITIONS, "You opened ${h.where} ${h.symbol} (${lots(h.qty)}) since the morning.", POSITION_SCORE)
            else if (w.qty != h.qty) changes += Change(Kind.POSITIONS, "Your ${h.where} ${h.symbol} went from ${lots(w.qty)} to ${lots(h.qty)}.", POSITION_SCORE)
        }
        for (w in morning) if (is_[w.key] == null) changes += Change(Kind.POSITIONS, "Your ${w.where} ${w.symbol} (${lots(w.qty)}) from the morning is closed.", POSITION_SCORE)
        val held = if (changes.isEmpty()) listOf((if (both) "your positions" else "your Paper positions") +
            " as they were this morning (${now.size} open leg${if (now.size == 1) "" else "s"})") else emptyList()
        return Found(changes, held, notes)
    }

    // ---- the answer -----------------------------------------------------------------------------------------------

    const val LOCKED_NOTE = "Your positions are left out on a locked phone, Boss."

    /**
     * The answer at [now] (the market's clock): [indices] from [bars] (1-minute, with India VIX's when kept), [chains] by
     * underlying (today's reads), today's [news] in [zone], and Boss's [positions] (null: left out; [locked]: said so).
     */
    fun answer(indices: List<Market>, bars: Map<Market, List<Candle>>, chains: Map<String, List<ChainIntel.Read>>, news: List<Headline>,
               zone: ZoneId, positions: Positions?, locked: Boolean, now: LocalDateTime): String {
        val today = now.toLocalDate()
        if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY)
            return "There is no session today, Boss, so there is no morning to set now against."
        val open = Market.NIFTY.open ?: LocalTime.of(9, 15)
        if (now.toLocalTime().isBefore(MARK))
            return if (now.toLocalTime().isBefore(open)) "The session hasn't opened yet, Boss. I set now against $markWord, the first half hour done - ask me after that."
            else "It's only ${hm(now)}, Boss. I set now against $markWord, the first half hour done - ask me after that."
        val todays = indices.filter { m -> bars[m].orEmpty().any { it.t.toLocalDate() == today && !it.t.toLocalTime().isBefore(MARK) } }
        if (todays.isEmpty())
            return "I have no candles from today's session after $markWord on the phone, Boss, so I can't set now against the morning."
        var found = Found()
        for (m in todays) found += index(m, bars[m].orEmpty(), today)
        found += index(Market.VIX, bars[Market.VIX].orEmpty(), today)
        for (m in todays) found += chain(chains[m.name].orEmpty(), today)
        found += news(news, zone, today, now.atZone(zone).toInstant())
        if (!locked && positions != null) found += positions(positions)
        val asOf = todays.mapNotNull { m -> bars[m].orEmpty().lastOrNull { it.t.toLocalDate() == today }?.t }.maxOrNull()
        return say(found, asOf, locked)
    }

    /** The changes ranked, the [TOP] said, the rest counted, then what held. */
    fun say(found: Found, asOf: LocalDateTime?, locked: Boolean): String {
        val ranked = found.changes.filter { it.score >= SMALL }.sortedByDescending { it.score }
        val head = "Since $markWord this morning" + (asOf?.let { ", to ${hm(it.plusMinutes(1))}" } ?: "") + ", Boss"
        val out = StringBuilder()
        if (ranked.isEmpty()) out.append("$head, nothing changed by more than a small step.")
        else {
            out.append("$head, the biggest change first: ")
            out.append(ranked.take(TOP).mapIndexed { i, c -> "${i + 1}. ${c.text}" }.joinToString(" "))
            val more = ranked.size - TOP
            if (more > 0) out.append(" And $more smaller change${if (more == 1) "" else "s"}.")
        }
        val held = found.held.take(4)
        if (held.isNotEmpty()) out.append(" Unchanged: ").append(held.joinToString("; ")).append(".")
        for (note in found.notes) out.append(" ").append(note)
        if (locked) out.append(" ").append(LOCKED_NOTE)
        out.append(" Facts from the phone's own data, not a forecast.")
        return out.toString()
    }
}
