package com.optionslab.engine.signals

import com.optionslab.engine.Right
import java.time.LocalDate
import java.util.Locale

/**
 * The Signal tester's reader, stage (a) (15 Oct 2026, Boss: "There is no format - we have to read the message and understand
 * it"): a tolerant, rule-based reading of one Telegram message from a tips channel. SHADOW ONLY: what it reads is recorded and
 * scored, never traded ([NOTE]).
 *
 * It finds, in any order of lines, with hashtags, emojis, odd spacing and Hinglish / Hindi words around them:
 *  - each option named in the message: a strike with CE / PE (or call / put) right after it ("73100 CE", "25000ce", "ce 25000");
 *    the index or stock before it (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, BANKEX by their usual nicknames - "bnf",
 *    "bank nifty", "nifty bank", "midcap nifty" - or a stock symbol, which is checked later against the phone's lists); a
 *    message naming several options is several calls;
 *  - the entry: "above / abv / buy above / upar / cross" (bought when the premium trades at or above it), "near / around / @ /
 *    at / buy / entry / range 280-285 / ke paas" (bought when it trades in that zone), "cmp" (bought at the market);
 *  - the stop: "sl / s/l / stoploss / stop loss / stop / exit below / below", a level or a percentage; missing stays missing;
 *  - the targets: "target / targets / tgt / tp / t1 t2 t3 / lakshya / 🎯" and the list after it ("310-345-400+++", "310/345",
 *    "t1 310 t2 345");
 *  - the expiry when written ("16 Oct", "oct 16", "exp 16/10"); else none (the nearest listed one is used later);
 *  - follow-ups ("T1 done", "sl hit", "book half", "exit all", "trail sl to cost", "hold"), linked later to that channel's open
 *    call;
 *  - and what is not a call, with the reason: chatter, adverts, results posts, sell (writing) calls, futures, a hidden
 *    notification preview.
 * Each reading carries a confidence and the reasons it is unsure; an unsure one goes to the language model (stage b,
 * [CallModel]) and then, still unsure, to Boss's "Needs review" list. Nothing is ever invented: a stop not in the message stays
 * "none given". Pure.
 */
object CallParser {
    const val NOTE = "shadow — never trades"
    /** A reading at or above this confidence counts without the model (stage b) or Boss. */
    const val SURE = 0.8

    /** How the entry is read: at or above the level, in a zone around it, or at the market. */
    enum class Trig(val key: String, val words: String) { ABOVE("above", "above"), ZONE("near", "near"), CMP("cmp", "at market");
        companion object { fun of(k: String?): Trig = entries.firstOrNull { it.key == k } ?: ABOVE } }

    /** A call as far as it was read: every field may be missing. [stopPct]: a stop written as a percentage of the entry. */
    data class Draft(
        val underlying: String? = null, val strike: Double? = null, val right: Right? = null, val expiry: LocalDate? = null,
        val trig: Trig = Trig.ABOVE, val trigger: Double? = null, val triggerHigh: Double? = null,
        val stop: Double? = null, val stopPct: Double? = null, val targets: List<Double> = emptyList(),
    ) {
        val complete: Boolean get() = underlying != null && strike != null && right != null
        /** The stop's level: as written, or the percentage of the entry; null when none was given. */
        val stopLevel: Double? get() = stop ?: stopPct?.let { p -> trigger?.let { round2(it * (1 - p / 100)) } }
        /** "SENSEX 73100 CE above 280, stop none given, targets 310 / 345 / 400". */
        fun line(): String = listOf(underlying ?: "?", strike?.let { num(it) } ?: "?", right?.name ?: "?").joinToString(" ") +
            (expiry?.let { " ($it)" } ?: "") + when {
                trig == Trig.CMP -> " at market" + (trigger?.let { " (cmp ${num(it)})" } ?: "")
                trigger == null -> ", no entry"
                else -> " ${trig.words} ${num(trigger)}" + (triggerHigh?.let { "-${num(it)}" } ?: "")
            } + ", stop " + (stop?.let { num(it) } ?: stopPct?.let { "${num(it)}%" } ?: "none given") +
            ", targets " + (if (targets.isEmpty()) "none given" else targets.joinToString(" / ") { num(it) })
    }

    /** A follow-up's kind. */
    enum class Follow(val key: String, val words: String) {
        TARGET("target", "target hit"), STOP("stop", "stop-loss hit"), BOOK("book", "book profit"), EXIT("exit", "exit"),
        TRAIL("trail", "trail the stop"), HOLD("hold", "hold");
        companion object { fun of(k: String?): Follow? = entries.firstOrNull { it.key == k } }
    }

    sealed class Reading {
        /** A new call; [unsure]: why it is below [SURE] (empty when sure). */
        data class NewCall(val draft: Draft, val confidence: Double, val unsure: List<String>) : Reading() {
            val sure: Boolean get() = confidence >= SURE && unsure.isEmpty()
        }
        /** A follow-up: [targetNo] (1, 2, 3; 99 all) for a target, [level] a price named, [partial] half a booking, [ref] the option named. */
        data class FollowUp(val kind: Follow, val targetNo: Int? = null, val level: Double? = null, val partial: Boolean = false,
                            val ref: Draft? = null) : Reading()
        /** Not a call, and why. [maybe]: it looks partly like one (the model is asked). */
        data class NotACall(val reason: String, val maybe: Boolean = false) : Reading()
    }

    // ---- normalising ---------------------------------------------------------------------------------------------------

    private fun rx(p: String) = Regex(p)

    /** Hindi (Devanagari) words to the English keywords, and the emojis that carry meaning. */
    private val WORDS = listOf(
        "बैंक निफ्टी" to " banknifty ", "बैंकनिफ्टी" to " banknifty ", "फिन निफ्टी" to " finnifty ", "निफ्टी" to " nifty ", "सेंसेक्स" to " sensex ",
        "ऊपर" to " above ", "उपर" to " above ", "के ऊपर" to " above ", "लक्ष्य" to " target ", "टारगेट" to " target ",
        "स्टॉप लॉस" to " sl ", "स्टॉपलॉस" to " sl ", "स्टॉप" to " sl ", "खरीदें" to " buy ", "खरीदो" to " buy ", "ख़रीदें" to " buy ",
        "बाहर" to " exit ", "निकल" to " exit ", "बुक" to " book ", "प्रॉफिट" to " profit ", "होल्ड" to " hold ", "के पास" to " near ",
        "🎯" to " target ", "✅" to " done ", "✔️" to " done ", "✔" to " done ", "☑️" to " done ", "🛑" to " sl ", "⛔" to " sl ",
        "→" to " to ", "➡️" to " to ", "➡" to " to ", "👉" to " ", "₹" to " ",
    )

    private val JOINS = listOf(
        rx("\\bs\\s?/\\s?l\\b") to "sl", rx("\\bstop\\s?-?\\s?loss\\b") to "sl", rx("\\bstoploss\\b") to "sl", rx("\\bsl\\s?-\\s?(?=\\d)") to "sl ",
        rx("\\bbank\\s?-?\\s?nifty\\b") to "banknifty", rx("\\bnifty\\s?-?\\s?bank\\b") to "banknifty",
        rx("\\bfin\\s?-?\\s?nifty\\b") to "finnifty", rx("\\bnifty\\s?fin(?:ancial)?(?:\\s?services?)?\\b") to "finnifty",
        rx("\\b(?:mid\\s?cap|midcp|midcap)\\s?nifty\\b") to "midcpnifty", rx("\\bnifty\\s?mid\\s?(?:cap)?\\s?(?:select)?\\b") to "midcpnifty",
        rx("\\bbse\\s?sensex\\b") to "sensex", rx("\\bnifty\\s?-?\\s?50\\b(?=\\s*\\d{4,})") to "nifty", rx("\\bnifty50\\b") to "nifty",
        rx("\\bbuy\\s+(?:only\\s+)?(?:above|abv|abov|abve)\\b") to "above", rx("\\bexit\\s+below\\b") to "sl",
        rx("\\bke\\s+(?:upar|uper|upr|oopar)\\b") to "above", rx("\\bke\\s+(?:paas|pass|pas)\\b") to "near",
        rx("\\bcost\\s+to\\s+cost\\b") to "ctc", rx("\\bsq(?:uare)?\\s?-?\\s?off\\b") to "exit",
    )

    /** [raw] lower-cased, Hindi and emojis read, numbers joined ("73,100" -> 73100), times and stray symbols dropped. */
    internal fun normalize(raw: String): String {
        var s = raw
        for ((a, b) in WORDS) s = s.replace(a, b)
        s = s.lowercase(Locale.ROOT)
        // Indian and Western digit grouping: 73,100 and 1,00,000.
        repeat(3) { s = s.replace(rx("(\\d),(\\d{2,3})(?!\\d)"), "$1$2") }
        // A clock time is never a price; ordinals lose their suffix ("1st target", "16th oct").
        s = s.replace(rx("\\b\\d{1,2}[:.]\\d{2}(?::\\d{2})?\\s?(?:am|pm)\\b"), " ").replace(rx("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b"), " ")
        s = s.replace(rx("\\b(\\d+)(?:st|nd|rd|th)\\b"), "$1")
        s = s.replace(rx("\\brs\\.?\\s?(?=\\d)"), " ")
        for ((a, b) in JOINS) s = s.replace(a, b)
        // T1 / TGT2 / Target-3 as one marker before digits are split from letters.
        s = s.replace(rx("\\b(?:t|tgt|tg|target|trgt|tp)\\s?-?\\s?([1-5])(?!\\d|\\.\\d)"), " tgtno$1 ")
        s = s.replace(rx("[^a-z0-9.+\\-/@%\\s]"), " ")
        // Digits and letters apart ("73100ce", "abv280", "16oct"), the T-markers kept.
        s = s.replace(rx("(\\d)([a-z])"), "$1 $2").replace(rx("(?<!tgtno)(?<=[a-z])(\\d)"), " $1")
        s = s.replace(rx("tgtno (\\d)"), "tgtno$1")
        return s.replace(rx("\\s+"), " ").trim()
    }

    // ---- tokens --------------------------------------------------------------------------------------------------------

    internal data class Tok(val t: String, val n: Double? = null) { val num: Boolean get() = n != null }

    private val TOKEN = rx("tgtno[1-5]|[a-z]+|\\d+(?:\\.\\d+)?|[@%/+\\-]")

    internal fun tokens(norm: String): List<Tok> = TOKEN.findAll(norm).map { m ->
        val v = m.value
        if (v[0].isDigit()) Tok(v, v.toDouble()) else Tok(v)
    }.toList()

    /** Nicknames of the indices (after [normalize] joined the two-word ones). */
    val ALIASES: Map<String, String> = linkedMapOf(
        "nifty" to "NIFTY", "nf" to "NIFTY", "banknifty" to "BANKNIFTY", "bnf" to "BANKNIFTY", "bn" to "BANKNIFTY", "bankn" to "BANKNIFTY",
        "finnifty" to "FINNIFTY", "finn" to "FINNIFTY", "fn" to "FINNIFTY", "midcpnifty" to "MIDCPNIFTY", "midcp" to "MIDCPNIFTY",
        "midcap" to "MIDCPNIFTY", "sensex" to "SENSEX", "sx" to "SENSEX", "bankex" to "BANKEX",
    )
    val INDICES: Set<String> = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "BANKEX")

    private val ABOVE = setOf("above", "abv", "abov", "abve", "upar", "uper", "upr", "oopar", "cross", "crosses", "crossing", "break", "breaks", "brk", "breakout", "over")
    private val ZONE = setOf("near", "around", "nr", "at", "buy", "entry", "range", "between", "paas", "zone", "add", "enter", "le", "lo", "lena")
    private val STOPW = setOf("sl", "stop", "stp", "below", "stoplos")
    private val TARGETW = setOf("target", "targets", "tgt", "tgts", "tg", "trgt", "tp", "lakshya", "tar", "targt")
    private val RIGHTS = mapOf("ce" to Right.CE, "call" to Right.CE, "calls" to Right.CE, "pe" to Right.PE, "put" to Right.PE, "puts" to Right.PE)
    /** After a Hindi "pe" (= "at"), a verb: "280 pe buy karo" is not a 280 put. */
    private val HINDI_PE_NEXT = setOf("lo", "le", "lena", "kharido", "karo", "kar", "kr", "ho", "se", "tak", "hai", "h", "aa", "aaye", "aaya",
        "aane", "jaye", "jaane", "tha", "rakho", "lagao", "milega", "mile", "chalega")
    private val SELL = setOf("sell", "short", "write", "writing", "shorting")
    private val NOT_STOCK = ABOVE + ZONE + STOPW + TARGETW + RIGHTS.keys + SELL + setOf("only", "for", "and", "the", "cmp", "exp", "expiry", "weekly",
        "monthly", "today", "trade", "intraday", "hero", "zero", "jackpot", "lot", "lots", "qty", "risky", "safe", "strong", "sure", "shot", "level",
        "option", "options", "index", "or", "of", "in", "on", "to", "is", "it", "now", "new", "call", "fresh", "big", "move", "next", "jan", "feb",
        "mar", "apr", "may", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec", "done", "hit", "tgtno", "bhai", "ji", "plz", "pls", "please",
        "loss", "profit", "book", "exit", "hold", "high", "low", "premium", "strike", "atm", "itm", "otm", "rs", "inr", "aaj", "kal", "abhi", "hai")

    // ---- the expiry ----------------------------------------------------------------------------------------------------

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MON = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"
    private val DAY_MON = rx("\\b(\\d{1,2}) ?-?$MON(?: ?-?(\\d{4}))?\\b")
    private val MON_DAY = rx("\\b$MON ?-?(\\d{1,2})(?!\\d|\\.\\d)\\b")
    private val EXP_DM = rx("\\b(?:exp|expiry)\\s?(\\d{1,2})\\s?[/-]\\s?(\\d{1,2})(?!\\d)")

    /** The expiry written in [norm] (and the text without it), the year from [on] (a date over a week before it: next year's). */
    internal fun expiry(norm: String, on: LocalDate): Pair<LocalDate?, String> {
        fun date(d: Int, m: Int, y: Int?): LocalDate? = runCatching {
            val x = LocalDate.of(y ?: on.year, m, d)
            if (y == null && x.isBefore(on.minusDays(7))) x.plusYears(1) else x
        }.getOrNull()
        EXP_DM.find(norm)?.let { m -> date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null)?.let { return it to norm.removeRange(m.range) } }
        DAY_MON.find(norm)?.let { m ->
            date(m.groupValues[1].toInt(), MONTHS.indexOf(m.groupValues[2]) + 1, m.groupValues[3].ifEmpty { null }?.toInt())?.let { return it to norm.removeRange(m.range) }
        }
        MON_DAY.find(norm)?.let { m -> date(m.groupValues[2].toInt(), MONTHS.indexOf(m.groupValues[1]) + 1, null)?.let { return it to norm.removeRange(m.range) } }
        return null to norm
    }

    // ---- reading ----------------------------------------------------------------------------------------------------------

    private val PROMO = rx("\\b(?:join|vip|premium|paid|subscribe|subscription|whatsapp|telegram|link|http|https|www|offer|discount|dm|inbox|" +
        "contact|accuracy|course|webinar|membership|enrol|enroll|register|free trial|hurry|seats|limited)\\b")
    private val HIDDEN = rx("^(?:new message|new messages|\\d+ new messages?|you have a new message|message)$")
    private val BRAG = rx("\\b(?:boom|booom|jackpot|bumper|rocket|points|pts|profit of|made|smashed|blasted|zoom|flying|fly)\\b")
    private val DONE = "(?:done|hit|hits|achieved|achieve|reached|complete|completed|booked|ho gaya|hogaya|ho gya|hua|aa gaya|aagaya|triggered|cleared)"
    private val F_STOP = rx("\\bsl (?:hit|hits|triggered|trigger|done|ho gaya|hogaya|ho gya|hua|lag gaya|laga|lag gya)\\b|\\bstopped out\\b|\\bsl ke saath exit\\b")
    private val F_TARGET_N = rx("\\btgtno([1-5]) ?(?:target )?$DONE\\b|\\b([1-5]) target $DONE\\b|\\b(first|second|third|1|2|3) (?:tgt|target) $DONE\\b")
    private val F_TARGET_ALL = rx("\\ball (?:targets?|tgts?) $DONE\\b|\\b(?:targets?|tgts?) all $DONE\\b")
    private val F_TARGET = rx("\\b(?:target|targets|tgt|tgts|trgt) $DONE\\b|\\b(\\d+(?:\\.\\d+)?) $DONE\\b")
    private val F_BOOK = rx("\\bbook(?:ed|ing)? ?(?:half|partial|50 ?%?|some|part)\\b|\\b(?:half|partial|part) ?(?:profit )?book(?:ing)?\\b")
    private val F_BOOK_ALL = rx("\\bbook(?:ed|ing)? (?:full |all |your |the )?(?:profits?|it|karo|kar lo|kr lo|now)\\b|\\bprofit book(?:ed|ing)?\\b")
    private val F_EXIT = rx("\\bexit(?: all| now| it| from| karo| kar do| kr do| kar lo)?\\b|\\bclose (?:it|all|position)\\b|\\bnikal(?: jao| lo| jaao)\\b|\\bexit\\b")
    private val F_TRAIL = rx("\\btrail(?:ing)?(?: your)? (?:sl|stop)\\b|\\bsl (?:to|at|on) cost\\b|\\bctc\\b|\\bsl cost\\b|\\bmove sl\\b|\\bsl (?:trail|shift)\\b|\\bshift sl\\b")
    private val F_HOLD = rx("\\bhold(?:ing)?(?: it| for| tight| karo| rakho)?\\b")

    /**
     * [text] read: one reading per call it names (several calls: several readings), else one follow-up or not-a-call. [on]:
     * the message's day (the year of an expiry written without one). [known]: symbols the phone's lists hold (stocks).
     */
    fun read(text: String, on: LocalDate, known: Set<String> = emptySet()): List<Reading> {
        val raw = text.trim()
        if (raw.isEmpty()) return listOf(Reading.NotACall("no text"))
        val (exp, norm) = expiry(normalize(raw), on)
        if (norm.isBlank()) return listOf(Reading.NotACall("no words or numbers (emojis or a picture only)"))
        if (HIDDEN.matches(norm)) return listOf(Reading.NotACall("the notification hid the message (Telegram's message preview is off)"))
        val toks = tokens(norm)
        val anchors = anchors(toks)
        if (anchors.isEmpty()) return listOf(noOption(norm, toks))
        val hasEntry = toks.any { it.t in ABOVE || it.t in ZONE || it.t == "@" || it.t == "cmp" }
        follow(norm, toks)?.let { f -> if (!hasEntry) return listOf(f.copy(ref = refOf(toks, anchors.first(), null))) }
        if (!hasEntry && BRAG.containsMatchIn(norm)) return listOf(Reading.NotACall("a results post (no entry level)"))
        val out = ArrayList<Reading>()
        val starts = anchors.mapIndexed { k, a -> segStart(toks, a, if (k == 0) 0 else anchors[k - 1].right + 1) }
        val header = toks.firstOrNull { it.t in ALIASES }?.let { ALIASES[it.t] }
        for ((k, a) in anchors.withIndex()) {
            val from = if (k == 0) 0 else starts[k]
            val to = if (k + 1 < anchors.size) starts[k + 1] else toks.size
            out += segment(toks, from, to, a, exp, header, known)
        }
        return out
    }

    /** One option named: its strike's token, its CE / PE token. */
    internal data class Anchor(val strike: Int, val right: Int, val side: Right)

    internal fun anchors(t: List<Tok>): List<Anchor> {
        val out = ArrayList<Anchor>()
        for (i in t.indices) {
            val r = RIGHTS[t[i].t] ?: continue
            val before = t.getOrNull(i - 1)
            val after = t.getOrNull(i + 1)
            val k = when {
                before?.num == true && (before.n ?: 0.0) > 0 -> i - 1
                // "CE 25000" (the number not an entry: nothing like "above" before it)
                after?.num == true && (t[i].t == "ce" || t[i].t == "pe") && (after.n ?: 0.0) >= 100 && t.getOrNull(i - 1)?.t !in ABOVE -> i + 1
                else -> continue
            }
            if (t[i].t == "pe" && hindiPe(t, i, k, out)) continue
            // A number that is the entry of the call before ("... above 280 pe ...") is not a strike.
            if (k == i - 1 && t.getOrNull(k - 1)?.let { it.t in ABOVE || it.t in ZONE || it.t == "@" || it.t in STOPW || it.t in TARGETW } == true) continue
            out += Anchor(k, i, r)
        }
        return out
    }

    private fun hindiPe(t: List<Tok>, i: Int, k: Int, earlier: List<Anchor>): Boolean {
        if (t.getOrNull(i + 1)?.t in HINDI_PE_NEXT) return true
        val s = t[k].n ?: return false
        return earlier.any { (t[it.strike].n ?: 0.0) > s * 2 }
    }

    /** Where an option's part of the message begins: its index or stock name just before the strike, else the strike. */
    private fun segStart(t: List<Tok>, a: Anchor, floor: Int): Int {
        val first = minOf(a.strike, a.right)
        for (j in first - 1 downTo maxOf(floor, first - 3)) if (t[j].t in ALIASES || stockLike(t[j].t)) return j
        return maxOf(floor, first)
    }

    private fun stockLike(w: String) = w.length in 3..15 && w.all { it in 'a'..'z' } && w !in NOT_STOCK && w !in ALIASES

    private fun refOf(t: List<Tok>, a: Anchor, header: String?): Draft {
        val first = minOf(a.strike, a.right)
        val u = (first - 1 downTo maxOf(0, first - 3)).map { t[it].t }.firstOrNull { it in ALIASES }?.let { ALIASES[it] }
            ?: header ?: t.firstOrNull { it.t in ALIASES }?.let { ALIASES[it.t] }
        return Draft(underlying = u, strike = t[a.strike].n, right = a.side)
    }

    /** A number after position [j] (skipping filler words): its index, or -1. */
    private fun numAfter(t: List<Tok>, j: Int, to: Int): Int {
        var k = j + 1
        while (k < to && (t[k].t in FILLER)) k++
        return if (k < to && t[k].num) k else -1
    }
    /** A number just before position [j] ("280 above", "280 pe buy"), not already read: its index, or -1. */
    private fun numBefore(t: List<Tok>, j: Int, from: Int, used: Set<Int>): Int {
        var k = j - 1
        if (k >= from && t[k].t in setOf("pe", "par", "ke", "se", "@")) k--
        return if (k >= from && t[k].num && k !in used) k else -1
    }
    private val FILLER = setOf("only", "of", "at", "is", "rs", "inr", "price", "level", "near", "around", "pe", "par", "ke", "the", "a", "-", "@", "on", "se", "hai", "h", "buy", "upar", "above", "abv", "range", "between")

    private fun segment(t: List<Tok>, from: Int, to: Int, a: Anchor, exp: LocalDate?, header: String?, known: Set<String>): Reading {
        val unsure = ArrayList<String>()
        var conf = 1.0
        // The index or stock: just before the strike, else anywhere in this part, else the message's first index.
        val first = minOf(a.strike, a.right)
        var und: String? = null
        for (j in first - 1 downTo maxOf(from, first - 3)) {
            val al = ALIASES[t[j].t]
            if (al != null) { und = al; break }
            if (stockLike(t[j].t)) { und = t[j].t.uppercase(Locale.ROOT); break }
        }
        if (und == null) und = (from until to).map { t[it].t }.firstOrNull { it in ALIASES }?.let { ALIASES[it] } ?: header
        val u = und
        when {
            u == null -> { conf -= 0.5; unsure += "no index or stock named" }
            u !in INDICES && u !in known -> { conf -= 0.3; unsure += "$u is not a symbol the phone's lists hold" }
        }
        if ((from until to).any { t[it].t in SELL } && (from until to).none { t[it].t == "buy" })
            return Reading.NotACall("a sell (option-writing) call: only option buys are tested")
        var trig = Trig.ABOVE
        var trigger: Double? = null; var high: Double? = null
        var stop: Double? = null; var stopPct: Double? = null
        val targets = ArrayList<Double>()
        val used = HashSet<Int>().apply { add(a.strike); add(a.right) }
        var j = from
        while (j < to) {
            val w = t[j].t
            when {
                (w in ABOVE || w in ZONE || w == "@") && trigger == null -> {
                    // The level after the word ("above 280"), else just before it ("280 ke upar", "280 pe buy karo").
                    val k = numAfter(t, j, to).takeIf { it >= 0 && it !in used } ?: numBefore(t, j, from, used)
                    if (k >= 0 && k !in used) {
                        trig = if (w in ABOVE || (w == "buy" && t.getOrNull(j + 1)?.t in ABOVE)) Trig.ABOVE else Trig.ZONE
                        trigger = t[k].n; used += k
                        // A range: "280-285", "280 to 285" (the second above the first, within 30%).
                        val sep = t.getOrNull(k + 1)?.t
                        val k2 = if (sep == "-" || sep == "to" || sep == "/") k + 2 else -1
                        val h = if (k2 in (k + 1) until to) t[k2].n else null
                        if (h != null && h > t[k].n!! && h <= t[k].n!! * 1.3) { high = h; used += k2; trig = Trig.ZONE }
                        j = maxOf(k, k2)
                    }
                }
                w == "cmp" -> {
                    trig = Trig.CMP
                    val k = numAfter(t, j, to)
                    if (k >= 0 && k !in used && trigger == null) { trigger = t[k].n; used += k; j = k }
                }
                (w in STOPW) && stop == null && stopPct == null -> {
                    if (w == "below" && trigger == null) { j++; continue }
                    val k = numAfter(t, j, to)
                    if (k >= 0 && k !in used) {
                        if (t.getOrNull(k + 1)?.t == "%") stopPct = t[k].n else stop = t[k].n
                        used += k; j = k
                    }
                }
                w in TARGETW || w.startsWith("tgtno") -> {
                    var k = j + 1
                    while (k < to) {
                        val x = t[k]
                        when {
                            x.num && k !in used && t.getOrNull(k + 1)?.t != "%" -> { targets += x.n!!; used += k }
                            x.t in TGT_SEP || x.t.startsWith("tgtno") || x.t in TARGETW || x.t == "and" || x.t == "to" -> Unit
                            else -> break
                        }
                        k++
                    }
                    j = k - 1
                }
            }
            j++
        }
        // No entry word: a lone number right after the option is read as the entry, unsure.
        if (trigger == null && trig != Trig.CMP) {
            val k = maxOf(a.strike, a.right) + 1
            if (k < to && t[k].num && k !in used && t.getOrNull(k + 1)?.t != "%") {
                trigger = t[k].n; used += k; trig = Trig.ZONE
                conf -= 0.25; unsure += "the entry was read from a bare number"
            } else { conf -= 0.4; unsure += "no entry price" }
        }
        val left = (from until to).filter { t[it].num && it !in used && t.getOrNull(it + 1)?.t != "%" }
        if (left.isNotEmpty()) { conf -= 0.1; unsure += "numbers not understood: " + left.joinToString(", ") { num(t[it].n!!) } }
        if (targets.isEmpty()) conf -= 0.1
        val d = Draft(u, t[a.strike].n, a.side, exp, trig, trigger, high, stop, stopPct, targets.toList())
        return Reading.NewCall(d, round2(conf.coerceIn(0.0, 1.0)), unsure)
    }
    private val TGT_SEP = setOf("-", "/", "+", "@")

    /** A message naming no option: a follow-up, else not a call (and why). */
    private fun noOption(norm: String, toks: List<Tok>): Reading {
        follow(norm, toks)?.let { return it }
        val rightWord = toks.any { it.t in RIGHTS }
        val index = toks.any { it.t in ALIASES }
        val bigNum = toks.any { (it.n ?: 0.0) >= 1000 }
        return when {
            PROMO.containsMatchIn(norm) -> Reading.NotACall("an advert or promotion")
            BRAG.containsMatchIn(norm) -> Reading.NotACall("a results post (no entry level)")
            toks.any { it.t == "fut" || it.t == "futures" || it.t == "future" } -> Reading.NotACall("a futures call: only option buys are tested")
            rightWord && toks.none { it.num } -> Reading.NotACall("names CE / PE but no strike", maybe = true)
            index && bigNum -> Reading.NotACall("an index level but no CE / PE", maybe = true)
            else -> Reading.NotACall("chatter: no option (a strike with CE / PE) in it")
        }
    }

    /** The follow-up [norm] says, or null. */
    internal fun follow(norm: String, toks: List<Tok>): Reading.FollowUp? {
        if (F_STOP.containsMatchIn(norm)) return Reading.FollowUp(Follow.STOP)
        F_TARGET_ALL.find(norm)?.let { return Reading.FollowUp(Follow.TARGET, targetNo = 99) }
        F_TARGET_N.find(norm)?.let { m ->
            val g = m.groupValues.drop(1).first { it.isNotEmpty() }
            val n = when (g) { "first" -> 1; "second" -> 2; "third" -> 3; else -> g.toInt() }
            return Reading.FollowUp(Follow.TARGET, targetNo = n)
        }
        F_TARGET.find(norm)?.let { m -> return Reading.FollowUp(Follow.TARGET, level = m.groupValues[1].ifEmpty { null }?.toDouble()) }
        if (F_BOOK.containsMatchIn(norm)) return Reading.FollowUp(Follow.BOOK, partial = true)
        if (F_BOOK_ALL.containsMatchIn(norm)) return Reading.FollowUp(Follow.BOOK)
        if (F_EXIT.containsMatchIn(norm)) return Reading.FollowUp(Follow.EXIT)
        if (F_TRAIL.containsMatchIn(norm)) {
            val k = toks.indexOfFirst { it.t == "sl" || it.t == "stop" }
            val lvl = if (k >= 0) toks.drop(k + 1).take(3).firstOrNull { it.num }?.n else null
            return Reading.FollowUp(Follow.TRAIL, level = lvl)
        }
        if (F_HOLD.containsMatchIn(norm) && toks.size <= 12) return Reading.FollowUp(Follow.HOLD)
        return null
    }

    /** Every number written in [text] (as read after normalising): the model may use only these ([CallModel]). */
    fun numbers(text: String): Set<Double> = tokens(normalize(text)).mapNotNull { it.n }.toSet()

    /** The index named in [text] by any of its nicknames, else null. */
    fun indexIn(text: String): String? = tokens(normalize(text)).firstOrNull { it.t in ALIASES }?.let { ALIASES[it.t] }

    /** "280", "280.5". */
    fun num(x: Double): String = if (x == Math.floor(x) && !x.isInfinite()) x.toLong().toString() else String.format(Locale.ENGLISH, "%.2f", x).trimEnd('0').trimEnd('.')

    internal fun round2(x: Double) = Math.round(x * 100) / 100.0
}
