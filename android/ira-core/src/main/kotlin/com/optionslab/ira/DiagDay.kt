package com.optionslab.ira

import com.optionslab.engine.strategy.Json
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * One day's diagnostics for the cloud research sessions (TODO G3, 13 Oct): the evening GitHub upload carries one small file
 * per day, `diag-YYYY-MM-DD.json.gz`, in the release `diag-YYYY-MM` of the private data repository (never options-lab).
 * Pure: the app gathers the inputs off the main thread ([Input]); this builds the report, scrubs every text and caps its size.
 *
 * What it holds (schema [SCHEMA], documented in MAINTENANCE.md and research/data/read_diagnostics.py):
 *  1. Each arm's day: armed / paused / parked, its last verdict, its signals taken or skipped with the reason's kind
 *     ([reasonKind]: guard, lock, day lock, flow, liquidity, pre-trade checks, event pause, cap, ...), entries and exits with
 *     times, premiums and reasons, paper or live, the order's source label. Counts are labelled "today" and "all_days".
 *     An arm's P&L is in points and percent of the premium only, never rupees.
 *  2. App health: the day's error counts by kind, the order-speed and slippage lines, stream drops, the workers' lines and
 *     the GitHub sync's failed or skipped runs.
 *  3. The Autopilot change timeline of the day, Boss's own switches (the trail, the parks) included.
 *
 * Privacy (13 Oct, tested in DiagDayTest): every text goes through [scrub] - GitHub tokens ([CloudSync.redact]), the owner's
 * secret rules ([Secrets.redact]: digit runs of 9+, phone numbers, e-mail, PAN), JWTs (eyJ...), long letter-and-digit runs
 * (keys), client-id shapes (AB1234, ab1234), rupee amounts, the figures of every clause with a money word (margin, required,
 * available, cash, funds, balance, P&L, MTM, realised, net, charges, ...; [hideMoney]), lakh-grouped figures, and the
 * personal words the app hands over (the account holder's name and each of its words, the user id, the GitHub login). No
 * order id or account id is ever an input. 14 Oct review: the other arms' lines lose their rupee net ([otherArmLine]), an
 * order's message keeps only its reason ([orderReason]: Kite's RMS text names the margins), and a live order has no fill
 * price (avg null: with the quantities it would give the live account's rupees). It is NOT sealed (13 Oct decision):
 * nothing personal is left in it, the repository is private, and the research sessions have no passphrase; the recordings
 * and backups, which hold Boss's trades, stay sealed.
 */
object DiagDay {
    const val SCHEMA = 1
    const val KIND = "iraalgo-diagnostics"
    const val EXT = ".json.gz"
    /** The report's most bytes before gzip (the lists are cut, newest kept, until it fits). */
    const val MAX_BYTES = 256 * 1024
    const val MAX_DECISIONS = 400
    const val MAX_TRADES = 60
    const val MAX_ORDERS = 150
    const val MAX_CHANGES = 120
    const val MAX_LINES = 80
    const val MAX_TEXT = 300

    fun tag(day: LocalDate): String = "diag-%04d-%02d".format(Locale.ROOT, day.year, day.monthValue)
    fun assetName(day: LocalDate): String = "diag-$day$EXT"

    private val NAME = Regex("^diag-(\\d{4}-\\d{2}-\\d{2})\\.json\\.gz$")
    fun dayOf(name: String): LocalDate? = NAME.find(name)?.let { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }

    // ---- inputs ---------------------------------------------------------------------------------------------------------

    /** One trade of an arm: premiums and times only ([lots], never the quantity's rupees). [exit] null: still open. */
    data class Trade(
        val arm: String, val live: Boolean, val right: String?, val entryAt: LocalDateTime, val entry: Double,
        val exitAt: LocalDateTime? = null, val exit: Double? = null, val why: String? = null, val lots: Double = 1.0,
        /** Who placed it, as the order screens say ("Strategy: ORB"). */
        val source: String = "",
        val unconfirmed: Boolean = false,
        /** Entered under Boss's paper give-back trail (R19b/c): kept apart from the plain-rules record. */
        val trail: Boolean = false,
    ) {
        val points: Double? get() = exit?.let { it - entry }
        val pct: Double? get() = points?.let { p -> if (entry > 0) p / entry * 100.0 else null }
    }

    /** One arm now: its switch, park and last verdict, today's trades and every closed trade kept ([closed]: for "all_days"). */
    data class Arm(
        val key: String, val label: String, val armed: Boolean, val automatic: Boolean = true, val liveCleared: Boolean = false,
        val parked: Boolean = false, val parkedIndices: List<String> = emptyList(), val status: String = "", val pending: Boolean = false,
        val today: List<Trade> = emptyList(), val closed: List<Trade> = emptyList(),
        /** Other names its decisions carry (Liquidity 15+5's books: "Liquidity BANKNIFTY 15-min", their keys). */
        val aliases: List<String> = emptyList(),
    )

    /** One decision an arm made ([arm]: its label), with the bar it decided on when known. */
    data class Decision(val at: LocalDateTime, val arm: String, val verdict: String, val bar: LocalTime? = null)

    /**
     * One order of the day (no id): [live] Zerodha, else paper; [source] the label the order screens show. The file keeps
     * [avg] for paper orders only, and [message]'s reason only ([orderReason]).
     */
    data class Order(
        val at: LocalDateTime, val live: Boolean, val source: String, val symbol: String, val side: String, val qty: Int, val filled: Int,
        val status: String, val type: String = "", val avg: Double = 0.0, val message: String = "",
    )

    data class Input(
        val day: LocalDate, val generatedAt: LocalDateTime, val app: String,
        /** The app's switches (states only). */
        val mode: Map<String, Boolean> = emptyMap(),
        val arms: List<Arm> = emptyList(),
        /** The other paper arms' diagnostics lines (Night, VIX divergence, MCX, forge ideas). */
        val otherArms: List<String> = emptyList(),
        val decisions: List<Decision> = emptyList(),
        /** When the in-memory decision record began (the app's start): decisions before it are not known. */
        val decisionsSince: LocalDateTime? = null,
        val orders: List<Order> = emptyList(),
        val changes: List<ChangeTimeline.Item> = emptyList(),
        /** The diagnostics diary's lines as kept ("MM-dd HH:mm:ss [area] text"); only [day]'s are read. */
        val diary: List<String> = emptyList(),
        /** The day's error counts from the diary's own tally, when it still holds the day (else counted from [diary]). */
        val errorTally: DiagErrors.Day? = null,
        /** Named one-line summaries (order_speed, slippage, stream, app_health, order_review, github, ...). */
        val health: Map<String, String> = emptyMap(),
        val workers: List<String> = emptyList(),
        /** The personal words to take out wherever they appear (name, user id, login, e-mail). */
        val personal: Collection<String?> = emptyList(),
    )

    // ---- the reasons ----------------------------------------------------------------------------------------------------

    /** What an arm's verdict did: taken, skipped (a signal not taken), waiting (no signal), holding, error. */
    fun outcome(kind: String): String = when (kind) {
        "taken" -> "taken"
        "holding" -> "holding"
        "error" -> "error"
        in WAITING -> "waiting"
        else -> "skipped"
    }

    private val WAITING = setOf("no_signal", "awaiting_approval", "outside_hours", "loading", "no_data", "other")

    /**
     * A verdict's reason, in kinds the research reads: taken, holding, guard, lock, day_lock, kill_switch, flow, liquidity,
     * pretrade, event, cap, exposure, approval lapsed or skipped by Boss, refused, order_refused, manager_done, no_contract,
     * no_quote, error, and the waiting ones (no_signal, awaiting_approval, outside_hours, loading, no_data, other).
     */
    fun reasonKind(verdict: String): String {
        val v = verdict.trim().lowercase(Locale.ROOT)
        return when {
            v.startsWith("entered") -> "taken"
            v == "holding" -> "holding"
            v.startsWith("guard_refused") || v.startsWith("refused") -> refusal(v)
            v == "stopped_for_today" || v.contains("day lock") || v.contains("day_lock") -> "day_lock"
            v.startsWith("skipped_by_flow") || v.contains("order flow") -> "flow"
            v == "liquidity_no_room" -> "liquidity"
            v.startsWith("side_cap_reached") || v == "day_limit_reached" -> "cap"
            v.startsWith("opposite_position_open") || v.contains("exposure") || v.contains("one-index") -> "exposure"
            v == "approval_lapsed" || v.contains("lapsed") -> "lapsed"
            v == "skipped_by_you" -> "skipped_by_boss"
            v.startsWith("order_refused") -> "order_refused"
            v.startsWith("account_changed") -> "refused"
            v == "done_after_trade_manager_exit" -> "manager_done"
            v == "no_contract" -> "no_contract"
            v.startsWith("error") -> "error"
            v == "awaiting_approval" -> "awaiting_approval"
            v == "flat_after_square_off" || v.contains("outside_entry_hours") || v == "no_decision_bar" -> "outside_hours"
            v == "liquidity_history_loading" -> "loading"
            v.startsWith("no_") && v.endsWith("_bars") || v.startsWith("no_index") -> "no_data"
            v.startsWith("hero_") && (v.contains("stale") || v.contains("stood_down")) -> "no_data"
            v.contains("pre-trade") || v.contains("pretrade") || v.contains("pre_trade") -> "pretrade"
            v.contains("event") -> "event"
            v.startsWith("waiting") || v in NO_SIGNAL || v.startsWith("hero_") -> "no_signal"
            else -> "other"
        }
    }

    private val NO_SIGNAL = setOf("inside_range", "no_sweep", "not_at_the_edge", "no_range", "not_a_fresh_break", "cooling_down_after_exit",
        "no_liquidity_break")

    /** A refusal's kind from its words ("guard_refused: day lock: ...", "refused: no quote"). */
    private fun refusal(v: String): String = when {
        v.contains("no quote") -> "no_quote"
        v.contains("kill") -> "kill_switch"
        v.contains("day lock") || v.contains("day_lock") || v.contains("daylock") -> "day_lock"
        v.contains("lock") -> "lock"
        v.contains("pre-trade") || v.contains("pretrade") || v.contains("pre_trade") || v.contains("preflight") -> "pretrade"
        v.contains("event") -> "event"
        v.contains("flow") -> "flow"
        v.contains("liquidity") || v.contains("spread") -> "liquidity"
        v.contains("cap") || v.contains("limit") || v.contains("max ") -> "cap"
        v.contains("exposure") || v.contains("opposite") -> "exposure"
        v.startsWith("guard_refused") -> "guard"
        else -> "refused"
    }

    // ---- the scrub ------------------------------------------------------------------------------------------------------

    private val JWT = Regex("eyJ[A-Za-z0-9_\\-.=]*")
    private val LONG_RUN = Regex("[A-Za-z0-9+/=_\\-]{16,}")
    /** A derivative's trading symbol (BANKNIFTY25OCT51200CE, NIFTY2510725000PE, CRUDEOIL25NOVFUT): kept. */
    private val SYMBOL = Regex("^[A-Z][A-Z&]{1,19}\\d{2}(?:[A-Z]{3}|[0-9OND]\\d{2})(?:\\d+(?:\\.\\d+)?)?(?:CE|PE|FUT)$")
    /** A client id's shape: AB1234, and ab1234 too (Kite's texts are not always upper case; 14 Oct review). */
    private val CLIENT_ID = Regex("(?i)\\b[A-Z]{2,3}\\d{3,6}\\b")
    private val RUPEES_BEFORE = Regex("(?i)(₹|\\brs\\.?(?=\\s*[-+−]?\\d)|\\binr\\b)\\s*[-+−]?\\s*\\d[\\d,]*(?:\\.\\d+)?(?:\\s*(?:k|lakhs?|cr|crores?)\\b)?")
    private val RUPEES_AFTER = Regex("(?i)[-+−]?\\d[\\d,]*(?:\\.\\d+)?\\s*(?:k\\s+|lakhs?\\s+|crores?\\s+)?(?:rupees?|₹|\\brs\\b\\.?)")
    private val MONEY_WORD = Regex("(?i)\\b(margins?|funds?|balance|utili[sz]ed|p&l|pnl|net worth|collateral|payout)\\b([^0-9\\n]{0,24}?)([-+−]?\\d[\\d,]*(?:\\.\\d+)?)")
    /** The app's own codes (refused_premium_at_or_under_40, park_orb_family_2026_10_13): lower case and underscores, kept. */
    private val CODE = Regex("^[a-z0-9]+(?:_[a-z0-9]+)+$")

    /**
     * The money words of a clause (14 Oct review of G3; [hideMoney]). Whole words only, so the app's codes
     * (refused_premium_at_or_under_40) and longer words (network, losses) are not money words.
     */
    private val MONEY_WORDS = Regex("(?i)(?<![A-Za-z0-9_])(?:margins?|required|available|balance|cash|collateral|funds?|profit|loss|" +
        "p&l|pnl|mtm|m2m|drawdown|(?:un)?reali[sz]ed|net|charges|brokerage|premium\\s+(?:paid|received)|rs|inr|rupees?|" +
        "utili[sz]ed|payout)(?![A-Za-z0-9_])|₹")
    /**
     * A clause's end: "·", ";", "|", ", " and ". " (the comma or point inside a figure, 1,200 or 95536.25, has no space
     * after it, so it never ends a clause). Not a bracket: "P&L 1,200 (2,300)" is one clause, and the scrub's own "[hidden]"
     * must never split a money clause in two.
     */
    private val CLAUSE_END = Regex("·|;|\\||,\\s|\\.\\s")
    /**
     * A figure: 7, -2,340, 1,23,456, 95536.25, 12k, 1.2 lakh. Never one inside a word or a symbol (AB1234, R14,
     * BANKNIFTY25OCT51200CE) or the tail of another figure, and never a time (15:30, 10:00:05: a figure next to ":" and a
     * digit). One after a comma or colon that follows a word ("Exceeds,32112.60", "Required:95536.25") is a figure.
     */
    private val FIGURE = Regex("(?i)(?<![A-Za-z0-9_])(?<!\\d[.,:])[-+−]?\\d(?:[\\d,]*\\d)?(?:\\.\\d+)?" +
        "(?:\\s?(?:k|lakhs?|cr|crores?)(?![A-Za-z0-9_]))?(?![A-Za-z0-9_])(?!:\\d)")
    /**
     * What makes a figure in a money clause not money: a unit right after it - percent, points, ticks ("P&L +12.5 pts"),
     * a time ("Safety net ...: every 30 s"), a size ("available 312 MB"), lots, trades or orders.
     */
    private val UNIT_AFTER = Regex("(?i)^\\s*(?:%|(?:pts?|points?|ticks?|ms|s|secs?|seconds?|mins?|minutes?|h|hrs?|hours?|days?|" +
        "[kmg]b|bytes?|lots?|trades?|orders?)(?![A-Za-z0-9_]))")
    /** Lakh grouping (1,23,456; 12,34,56,789): only rupees are written this way. Hidden wherever it is. */
    private val LAKH_GROUPED = Regex("(?<![\\d,.])[-+−]?\\d{1,2},(?:\\d{2},)+\\d{3}(?:\\.\\d+)?(?![\\d,])")

    /**
     * A figure before a money word that looks like money (14 Oct review): signed (-2,340), comma-grouped (1,200), with
     * decimals (95536.25), of 3 digits or more (540), or with k, lakh or crore. A plain count of one or two digits before the
     * word ("3 required", "last 7 closed") is kept; after a money word every figure goes.
     */
    private fun moneyLike(f: String): Boolean =
        f.first() in "-+−" || ',' in f || '.' in f || f.count { it.isDigit() } >= 3 || f.any { it.isLetter() }

    /**
     * The money clauses' figures hidden (14 Oct review of G3: Kite's "RMS:Margin Exceeds,Required:95536.25, Available:32112.60"
     * kept its second figure, and "P&L 1,200 (2,300 realised)" its second). In each clause that holds a money word, every
     * figure after the first money word and every money-like figure before it ([moneyLike]) is hidden; a percent, points or
     * ticks stay, and so do times. A clause that follows a money clause after ", " ("Margin 1,200, 2,300") is a list of the
     * same: its money-like figures go too. Other clauses are left as they are (index levels, ranges, bars).
     */
    fun hideMoney(text: String): String {
        val out = StringBuilder(text.length)
        /** One clause written out; true: it was a money clause. */
        fun clause(c: String, inherit: Boolean): Boolean {
            val first = MONEY_WORDS.find(c)
            if (first == null && !inherit) { out.append(c); return false }
            var last = 0
            var hid = false
            for (m in FIGURE.findAll(c)) {
                val after = first != null && m.range.first > first.range.last
                if (!(after || moneyLike(m.value)) || UNIT_AFTER.containsMatchIn(c.substring(m.range.last + 1))) continue
                out.append(c, last, m.range.first).append(Secrets.HIDDEN)
                last = m.range.last + 1
                hid = true
            }
            out.append(c, last, c.length)
            return first != null || hid
        }
        var from = 0
        var money = false
        var comma = false
        for (e in CLAUSE_END.findAll(text)) {
            money = clause(text.substring(from, e.range.first), money && comma)
            comma = e.value.startsWith(",")
            out.append(e.value)
            from = e.range.last + 1
        }
        clause(text.substring(from), money && comma)
        return out.toString()
    }

    /**
     * The personal words taken out: one of 4 letters or more wherever it is (a login inside a path, the name in a sentence);
     * a shorter one (a short word of the holder's name, "Om", "Ali"; 14 Oct review) only as a whole word, case-insensitively,
     * so it never cuts other words apart ("Ali" inside "realised" would also break the money word). The longest first, so
     * the whole name goes before its words.
     */
    private fun hidePersonal(text: String, personal: Collection<String?>): String {
        var t = text
        for (w in personal.mapNotNull { it?.trim() }.filter { it.length >= 2 }.distinct().sortedByDescending { it.length }) {
            t = if (w.length >= 4) t.replace(w, Secrets.HIDDEN, ignoreCase = true)
            else Regex("(?i)(?<![A-Za-z0-9_])" + Regex.escape(w) + "(?![A-Za-z0-9_])").replace(t, Secrets.HIDDEN)
        }
        return t
    }

    /**
     * An order's message as the file keeps it (14 Oct review of G3): Kite's RMS text carries the account's margins ("RMS:Margin
     * Exceeds,Required:95536.25, Available:32112.60 for entity account-AB1234 ..."), so only its reason is kept: the words
     * before the first figure, at most [ORDER_REASON] characters. A digit inside a word or a code (AB1234,
     * refused_premium_at_or_under_40) does not end it; [scrub] still runs on what is left.
     */
    fun orderReason(message: String): String {
        val m = message.replace('\n', ' ').replace('\r', ' ')
        val cut = ORDER_FIGURE.find(m)?.range?.first ?: m.length
        return m.substring(0, cut).take(ORDER_REASON).trimEnd(' ', ':', ',', '.', '-', '=', '(', '₹').trim()
    }

    const val ORDER_REASON = 80
    private val ORDER_FIGURE = Regex("(?<![A-Za-z0-9_])[-+−₹]?\\d")

    /** The rupee net in the other arms' lines: Night's, VIX divergence's and MCX's "-2,340 after charges", GrammarArm's "4 trades -1,250". */
    private val NET_AFTER_CHARGES = Regex("(?i)[-+−]?\\d(?:[\\d,]*\\d)?(?:\\.\\d+)?\\s*after charges")
    private val NET_AFTER_TRADES = Regex("(?i)(?<![A-Za-z0-9_.,])(\\d+ trades?)\\s+[-+−]?\\d(?:[\\d,]*\\d)?(?:\\.\\d+)?")

    /**
     * One of the other paper arms' lines without its rupee net (14 Oct review of G3: their diagLine(), made for Copy
     * diagnostics, says "last 7 closed -2,340 after charges"; the file keeps the count, never the amount). [scrub] runs after.
     */
    fun otherArmLine(line: String): String =
        NET_AFTER_TRADES.replace(NET_AFTER_CHARGES.replace(line, "(net hidden)")) { m -> m.groupValues[1] }

    /**
     * Every text in the report goes through this, last: GitHub tokens, the owner's secret rules, JWTs, keys, client ids (any
     * case), rupee amounts, the figures of money clauses ([hideMoney]), lakh-grouped figures, and [personal] words. Cut to
     * [MAX_TEXT] after the scrub (so a cut can never leave part of a secret). The personal words go after the money rules
     * (14 Oct review): a name word taken out of the middle of a money word ("Ali" in "realised") must not hide that word
     * from them.
     */
    fun scrub(text: String, personal: Collection<String?> = emptyList()): String {
        var t = text.replace('\n', ' ').replace('\r', ' ')
        t = CloudSync.redact(t)
        t = JWT.replace(t, Secrets.HIDDEN)
        t = LONG_RUN.replace(t) { m ->
            val s = m.value
            val mixed = s.any { it.isDigit() } && s.any { it.isLetter() }
            if (mixed && !SYMBOL.matches(s) && !CODE.matches(s)) Secrets.HIDDEN else s
        }
        t = RUPEES_BEFORE.replace(t, "₹${Secrets.HIDDEN}")
        t = RUPEES_AFTER.replace(t, "${Secrets.HIDDEN} rupees")
        t = MONEY_WORD.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + Secrets.HIDDEN }
        t = hideMoney(t)
        t = LAKH_GROUPED.replace(t, Secrets.HIDDEN)
        t = hidePersonal(t, personal)
        t = CLIENT_ID.replace(t, Secrets.HIDDEN)
        t = Secrets.redact(t)
        return if (t.length > MAX_TEXT) t.take(MAX_TEXT - 1) + "…" else t
    }

    // ---- the report -----------------------------------------------------------------------------------------------------

    private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    /** A time on [day] as "HH:mm:ss", or the full "yyyy-MM-ddTHH:mm:ss" when it is another day (a trade held overnight). */
    private fun at(t: LocalDateTime, day: LocalDate): String = if (t.toLocalDate() == day) HMS.format(t) else STAMP.format(t)

    private fun r2(x: Double): Double = Math.round(x * 100.0) / 100.0

    private val LINE = Regex("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2}) \\[([\\w-]+)\\] (.*)$")

    /** One diary line of [day], read: its time, area and text (null: another day, or not a diary line). */
    data class Line(val at: LocalDateTime, val area: String, val text: String)

    fun linesOf(diary: List<String>, day: LocalDate): List<Line> = diary.mapNotNull { raw ->
        val m = LINE.find(raw.trim()) ?: return@mapNotNull null
        val (mo, d, h, mi, s, area, text) = m.destructured
        if (mo.toInt() != day.monthValue || d.toInt() != day.dayOfMonth) return@mapNotNull null
        runCatching { Line(day.atTime(h.toInt(), mi.toInt(), s.toInt()), area, text) }.getOrNull()
    }

    /** The arms' areas in the diary: each "Label: verdict" line there is a decision. */
    val ARM_AREAS = setOf("orb", "night", "vix-div", "mcx-arms", "forge-paper", "flow")

    private val BAR = Regex("· bar (\\d{2}):(\\d{2})")
    /** A verdict as the arms write it: a code first ("guard_refused: ...", "entered", "no_sweep · range ..."). */
    private val VERDICT = Regex("^[a-z][a-z0-9_]*(?=$|:| ·)")

    /** The diary's arm lines of [day] as decisions ("ORB: guard_refused: ... · range ... · bar 10:05 close ..."). */
    fun diaryDecisions(lines: List<Line>): List<Decision> = lines.filter { it.area in ARM_AREAS }.mapNotNull { l ->
        val i = l.text.indexOf(": ")
        if (i !in 1..40) return@mapNotNull null
        // Only verdicts: the arms' notices in the same area ("the trade manager's lock raised the paper stop", "parked ...")
        // are not decisions.
        if (!VERDICT.containsMatchIn(l.text.substring(i + 2))) return@mapNotNull null
        val bar = BAR.find(l.text)?.let { runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull() }
        Decision(l.at, l.text.substring(0, i).trim(), l.text.substring(i + 2).substringBefore(" · range").substringBefore(" · bar").trim(), bar)
    }

    /** The day's errors by kind, from the diary's [lines] ([DiagErrors.kindOf]), when the diary's own tally no longer holds it. */
    fun errorsOf(lines: List<Line>): DiagErrors.Day? {
        val t = DiagErrors.Tally()
        val day = lines.firstOrNull()?.at?.toLocalDate() ?: return null
        lines.forEach { t.add(day, it.area, it.text) }
        return t.of(day)
    }

    /** The report as a JSON tree (maps, lists, strings, numbers, booleans): every text scrubbed, every list capped. */
    fun build(i: Input): Map<String, Any?> {
        val p = i.personal
        fun s(x: String?): String = scrub(x.orEmpty(), p)
        val lines = linesOf(i.diary, i.day)
        val cut = LinkedHashMap<String, Int>()
        fun <T> capped(name: String, xs: List<T>, max: Int): List<T> {
            if (xs.size > max) cut[name] = (cut[name] ?: 0) + xs.size - max
            return xs.takeLast(max)
        }

        // An arm whose decisions the app keeps in memory (Liquidity's books, Hero) is read from there from when that record
        // began ([Input.decisionsSince], the app's start): its diary lines then would count the same decisions twice. Its
        // diary lines from before (the app restarted during the day) are kept.
        val kept = i.decisions.map { it.arm }.toSet()
        val since = i.decisionsSince
        val fromDiary = diaryDecisions(lines).filter { it.arm !in kept || (since != null && it.at.isBefore(since)) }
        val decisions = (i.decisions + fromDiary).filter { it.at.toLocalDate() == i.day }
            .distinctBy { Triple(it.at.withNano(0), it.arm, it.verdict) }.sortedBy { it.at }
        val byArm = decisions.groupBy { it.arm }

        val arms = i.arms.map { a ->
            val mine = (listOf(a.label, a.key) + a.aliases).distinct().flatMap { byArm[it].orEmpty() }.sortedBy { it.at }
            val kinds = mine.map { reasonKind(it.verdict) }
            val skips = kinds.filter { outcome(it) == "skipped" }.groupingBy { it }.eachCount().toSortedMap()
            val todayTrades = a.today.sortedBy { it.entryAt }
            val closedToday = todayTrades.filter { it.exit != null }
            val all = a.closed.filter { it.exit != null }
            val state = when {
                a.parked -> "parked"
                a.armed && a.pending -> "awaiting_approval"
                a.armed -> "armed"
                else -> "off"
            }
            linkedMapOf(
                "key" to s(a.key), "label" to s(a.label), "state" to state, "automatic" to a.automatic, "live_cleared" to a.liveCleared,
                "parked_indices" to a.parkedIndices.map { s(it) }, "status" to s(a.status), "status_kind" to reasonKind(a.status),
                "today" to linkedMapOf(
                    "decisions" to mine.size,
                    "signals" to kinds.count { it == "taken" || outcome(it) == "skipped" },
                    "taken" to kinds.count { it == "taken" },
                    "skipped" to kinds.count { outcome(it) == "skipped" },
                    "skips" to skips,
                    "entries" to todayTrades.size,
                    "exits" to closedToday.size,
                    "paper" to todayTrades.count { !it.live }, "live" to todayTrades.count { it.live },
                ) + perf(closedToday),
                "all_days" to linkedMapOf<String, Any?>(
                    "trades" to all.size,
                    "since" to all.minOfOrNull { it.entryAt.toLocalDate() }?.toString(),
                ) + perf(all),
                "trades" to capped("trades:${a.key}", todayTrades, MAX_TRADES).map { t -> trade(t, i.day, p) },
            )
        }

        val stream = StreamHealth.day(i.diary, i.day.atTime(23, 59, 59))
        val errors = i.errorTally?.takeIf { it.day == i.day && (it.errors.isNotEmpty() || it.unreadable.isNotEmpty()) } ?: errorsOf(lines)
        val github = lines.filter { it.area == "github" || it.area == "sync" }
        // A run skipped because a check failed is a skip, not a failed upload (counted once).
        val skippedSync = github.count { it.text.contains("skipped") }
        val failedSync = github.count { it.text.contains("failed") && !it.text.contains("skipped") }

        val report = linkedMapOf<String, Any?>(
            "schema" to SCHEMA, "kind" to KIND, "day" to i.day.toString(), "generated_at" to STAMP.format(i.generatedAt), "app" to s(i.app),
            "privacy" to "Redacted on the phone: no tokens, keys, PINs, account or client ids, names, phone or e-mail, order ids, " +
                "rupee figures (balances, margins, charges or P&L). Arm P&L is in points and percent of the premium; a live " +
                "order has no fill price, an order's message only its reason.",
            "mode" to i.mode.toSortedMap(),
            "arms" to arms,
            // The other arms' lines without their rupee net (14 Oct review): the count stays, the amount never.
            "other_arms" to capped("other_arms", i.otherArms, MAX_LINES).map { s(otherArmLine(it)) },
            "decisions_since" to i.decisionsSince?.let { at(it, i.day) },
            "decisions" to capped("decisions", decisions, MAX_DECISIONS).map { d ->
                val k = reasonKind(d.verdict)
                linkedMapOf("at" to at(d.at, i.day), "arm" to s(d.arm), "verdict" to s(d.verdict), "kind" to k, "outcome" to outcome(k),
                    "bar" to d.bar?.let { HM.format(it) })
            },
            // 14 Oct review: a live order's fill price is left out (null), so the live account's rupee P&L cannot be rebuilt
            // from its quantities and fills; a paper order keeps it. Live trades are read in points from the arms' trades.
            // A message keeps only its reason ([orderReason]): Kite's RMS text carries the account's margins.
            "orders" to capped("orders", i.orders.sortedBy { it.at }, MAX_ORDERS).map { o ->
                linkedMapOf("at" to at(o.at, i.day), "account" to if (o.live) "live" else "paper", "source" to s(o.source), "symbol" to s(o.symbol),
                    "side" to s(o.side), "qty" to o.qty, "filled" to o.filled, "status" to s(o.status), "type" to s(o.type),
                    "avg" to if (o.live) null else r2(o.avg), "message" to s(orderReason(o.message)))
            },
            "orders_by_source" to i.orders.groupingBy { (if (it.live) "live · " else "paper · ") + s(it.source) }.eachCount().toSortedMap(),
            "health" to linkedMapOf(
                "errors" to (errors?.errors ?: emptyMap()).entries.sortedByDescending { it.value }.associate { s(it.key) to it.value },
                "unreadable" to (errors?.unreadable ?: emptyMap()).entries.associate { s(it.key) to it.value },
                "stream" to linkedMapOf("drops" to stream.drops, "last_at" to stream.lastAt?.let { at(it, i.day) },
                    "whys" to stream.whys.entries.associate { s(it.key) to it.value }),
                "lines" to i.health.toSortedMap().mapValues { s(it.value) },
                "workers" to capped("workers", i.workers, MAX_LINES).map { s(it) },
                "sync" to linkedMapOf("failed" to failedSync, "skipped" to skippedSync,
                    "lines" to capped("sync", github, 30).map { "${HMS.format(it.at)} ${s(it.text)}" }),
                "diary_lines" to lines.size,
            ),
            "timeline" to capped("timeline", i.changes.sortedBy { it.atMs }, MAX_CHANGES).map { c ->
                linkedMapOf("at" to at(LocalDateTime.ofInstant(Instant.ofEpochMilli(c.atMs), com.optionslab.engine.IST), i.day),
                    "source" to c.source.name.lowercase(Locale.ROOT), "agent" to s(c.agent), "what" to s(c.what), "why" to s(c.why),
                    "undo" to c.undo.name.lowercase(Locale.ROOT), "scope" to s(c.scope), "own" to c.own)
            },
        )
        report["truncated"] = cut
        return fit(report)
    }

    /** Points and percent only: the count, wins and losses, the points summed and the average percent of the premium. */
    private fun perf(ts: List<Trade>): Map<String, Any?> {
        val pts = ts.mapNotNull { it.points }
        val pct = ts.mapNotNull { it.pct }
        return linkedMapOf(
            "wins" to pts.count { it > 0 }, "losses" to pts.count { it < 0 },
            "points" to r2(pts.sum()),
            "avg_pct" to if (pct.isEmpty()) null else r2(pct.average()),
            "sum_pct" to r2(pct.sum()),
        )
    }

    private fun trade(t: Trade, day: LocalDate, personal: Collection<String?>): Map<String, Any?> = linkedMapOf(
        "account" to if (t.live) "live" else "paper", "source" to scrub(t.source, personal), "right" to t.right,
        "entry_at" to at(t.entryAt, day), "entry" to r2(t.entry),
        "exit_at" to t.exitAt?.let { at(it, day) }, "exit" to t.exit?.let { r2(it) },
        "why" to t.why?.let { scrub(it, personal) }, "points" to t.points?.let { r2(it) }, "pct" to t.pct?.let { r2(it) },
        "lots" to r2(t.lots), "open" to (t.exit == null), "unconfirmed" to t.unconfirmed, "trail" to t.trail,
    )

    /**
     * The report cut to [MAX_BYTES]: while too big, the longest of the decisions, orders, timeline and workers' lists loses
     * its oldest half (each cut counted under "truncated").
     */
    @Suppress("UNCHECKED_CAST")
    private fun fit(r: LinkedHashMap<String, Any?>): Map<String, Any?> {
        val cut = r["truncated"] as LinkedHashMap<String, Int>
        repeat(40) {
            if (Json.write(r).toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return r
            val health = r["health"] as MutableMap<String, Any?>
            val lists = listOf("decisions" to r, "orders" to r, "timeline" to r, "other_arms" to r, "workers" to health)
                .map { (k, m) -> Triple(k, m, (m[k] as List<*>)) }.filter { it.third.isNotEmpty() }
            val big = lists.maxByOrNull { Json.write(it.third).length }
            if (big == null) {
                // Nothing left to cut but the arms' trades: each arm keeps its newest ten.
                (r["arms"] as List<MutableMap<String, Any?>>).forEach { a ->
                    val ts = a["trades"] as List<*>
                    if (ts.size > 10) { cut["trades:${a["key"]}"] = (cut["trades:${a["key"]}"] ?: 0) + ts.size - 10; a["trades"] = ts.takeLast(10) }
                }
                return r
            }
            val (k, m, l) = big
            val keep = l.size / 2
            cut[k] = (cut[k] ?: 0) + l.size - keep
            m[k] = l.takeLast(keep)
        }
        return r
    }

    /** The report's bytes: JSON (UTF-8), gzip-compressed. */
    fun encode(i: Input): ByteArray = gzip(Json.write(build(i)).toByteArray(Charsets.UTF_8))

    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
}
