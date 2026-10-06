package com.optionslab.ira

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.pow

/**
 * Boss's routine with Jarvis (learning round 7, 2026-10-05): beyond the hour counts of [Habits], the questions Boss
 * asks on most trading days around the same time ("how is BankNifty" at about 09:20), on one weekday (Mondays, the
 * week's events) or just after one of his trades closed at a loss (his P&L). Each pattern is found from a small log of
 * what was asked and when - the question's key only, never his words - and fades: an ask counts half after
 * [HALF_LIFE_DAYS] (a weekday's after twice that), and one older than the window counts not at all.
 *
 * Proactive in WORDS only: a pattern is put to Boss ("You usually ask about BankNifty's levels around 9:20, Boss -
 * shall I tell you then?") and kept only on his yes ([Kept]); then, at its time, the answer to that very question is
 * said - a spoken answer to a question, never a command, an order or anything that acts ([safe], checked when offered,
 * on the yes and when said). A kept one lapses [KEEP_DAYS] after the yes (or after Boss last asked it himself) and is
 * offered again only if the habit is still there. The account's questions (his P&L) follow the locked-phone rules in
 * the app. Pure: the log and what was kept live in the app.
 */
object Routine {
    /** One question Boss asked: its key ([key]), when, and whether one of his trades had just closed at a loss. */
    data class Seen(val key: String, val at: LocalDateTime, val afterLoss: Boolean = false)

    enum class Kind { DAILY, WEEKDAY, AFTER_LOSS }

    /** A pattern found: [minute] of the day it is asked around (-1 after a loss), [days] on which it was, [weight] faded. */
    data class Pattern(val key: String, val kind: Kind, val minute: Int, val day: DayOfWeek?, val days: Int, val weight: Double, val last: LocalDate) {
        /** Stable across small drifts of the time: offered again only after [OFFER_AGAIN_DAYS] whatever Boss answered. */
        val slot: String get() = listOf(kind.name, key, day?.name ?: "-", if (minute < 0) "-" else (minute / 60).toString()).joinToString("/")
    }

    /** A pattern Boss said yes to: its answer said at its time until [until]; [since]: the day of his yes (null: kept before days were noted). */
    data class Kept(val key: String, val kind: Kind, val minute: Int, val day: DayOfWeek?, val until: LocalDate, val since: LocalDate? = null) {
        val id: String get() = listOf(kind.name, key, day?.name ?: "-", minute.toString()).joinToString("/")
    }

    /** Within this many days an ask counts (a weekday's within twice as many). */
    const val WINDOW_DAYS = 28L
    const val HALF_LIFE_DAYS = 14.0
    /** On at least this many different days, and with this much weight after the fade, before it is a habit. */
    const val MIN_DAYS = 3
    const val MIN_WEIGHT = 2.0
    /** A question asked within this many minutes after one of Boss's own trades closed at a loss is "after a loss". */
    const val AFTER_LOSS_MINUTES = 30L
    /** A kept routine lasts this long after the yes (or Boss's own last ask of it). */
    const val KEEP_DAYS = 45L
    /** A pattern put to Boss is not put again for this long, whatever he answered. */
    const val OFFER_AGAIN_DAYS = 30L
    /** The log is kept small: this many asks at most. */
    const val LOG_MAX = 300

    // ---- what is counted --------------------------------------------------------------------------------------------

    private val NEVER = setOf(Topic.COMMAND, Topic.ORDER, Topic.OFF_TOPIC, Topic.HELP, Topic.GREETING, Topic.BACKTEST, Topic.SUGGEST, Topic.TRADE_CHECK, Topic.ADVICE)
    private val PNL = Regex(" (p ?& ?l|p and l|pnl|mtm|m2m|am i up|am i down|how am i doing|my profit|my loss) ")
    private val EVENTS = Regex(" (events?|calendar) ")

    private fun norm(text: String) = " " + text.lowercase().replace("&", " & ").replace(rx("[^a-z0-9&]+"), " ").replace(rx("\\s+"), " ").trim() + " "

    /**
     * The key of a question Boss may have a routine for: a market question ([Habits.key]), his P&L ("ACCOUNT|pnl") or the
     * events ahead ("ACCOUNT|events"); never a command, an order, a trade idea or anything else.
     */
    fun key(text: String): String? {
        // "When do I usually check my P&L?" / "stop getting my P&L ready": about Jarvis's read-ahead ([CheckTimes]), never a
        // P&L ask - not logged, so asking or undoing it never counts toward it.
        if (runCatching { CheckTimes.asked(text) != null }.getOrDefault(false)) return null
        // "What have you learned about my conditional orders?" / its undo ([CondNeeds]): about Jarvis, never an orders ask.
        if (runCatching { CondNeeds.asked(text) != null }.getOrDefault(false)) return null
        Habits.key(text)?.let { return it }
        val q = Ask.parse(text)
        if (q.command != null || q.order != null || q.topics.any { it in NEVER }) return null
        val t = norm(text)
        if (Secrets.redact(text) != text) return null
        return when {
            EVENTS.containsMatchIn(t) -> "ACCOUNT|events"
            PNL.containsMatchIn(t) -> "ACCOUNT|pnl"
            else -> null
        }
    }

    /** The question a key stands for, as Jarvis asks it of himself. */
    fun question(key: String): String? = when (key) {
        "ACCOUNT|pnl" -> "what is my P&L today"
        "ACCOUNT|events" -> "what are the events this week"
        else -> Habits.question(key)
    }

    /** Is [key] about Boss's account (said only by the locked-phone rules)? */
    fun account(key: String): Boolean = key == "ACCOUNT|pnl"

    /**
     * Only a spoken answer to a question is ever said from a routine: [key]'s question must parse as no command, no
     * order, nothing that acts - and be of the fixed kinds above.
     */
    fun safe(key: String): Boolean {
        val q = question(key) ?: return false
        val p = Ask.parse(q)
        if (p.command != null || p.order != null || p.topics.any { it in NEVER }) return false
        return this.key(q) == key
    }

    /** "BankNifty's levels", "your P&L": what the question is about, in Jarvis's words. */
    fun about(key: String): String? {
        if (key == "ACCOUNT|pnl") return "your P&L"
        if (key == "ACCOUNT|events") return "the week's events"
        val (mn, kind) = key.split("|").takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return null
        val m = runCatching { Market.valueOf(mn) }.getOrNull()?.label ?: return null
        return when (kind) {
            "levels" -> "$m's levels"
            "trend" -> "$m's trend"
            "patterns" -> "the patterns on $m"
            "news" -> "the news on $m"
            "volatility" -> "how volatile $m is"
            "why" -> "why $m is moving"
            "overview" -> "how $m is doing"
            else -> null
        }
    }

    // ---- the log ----------------------------------------------------------------------------------------------------

    /** [seen] added to [log]: the same question again within ten minutes is one ask; older than the window, dropped. */
    fun add(log: List<Seen>, seen: Seen): List<Seen> {
        val from = seen.at.toLocalDate().minusDays(WINDOW_DAYS * 2)
        val again = log.any { it.key == seen.key && !it.at.isAfter(seen.at) && Duration.between(it.at, seen.at).toMinutes() < 10 }
        val kept = log.filter { !it.at.toLocalDate().isBefore(from) }
        return (if (again) kept else kept + seen).sortedBy { it.at }.takeLast(LOG_MAX)
    }

    fun encode(s: Seen): String = "${s.at.withSecond(0).withNano(0)}#${s.key}#${if (s.afterLoss) 1 else 0}"

    /**
     * One line of the log as written by [encode], or null. Kept by the line ([com.optionslab.ira.Kept], speed round 8):
     * the app decodes the whole log (up to [LOG_MAX] lines, about 3.5 us each on a desktop, many times that on a phone)
     * several times per answer - the next-question offer read it twice before the answer was shown - and the lines barely
     * change between questions. A line's reading depends on nothing but the line (no clock, no day, nothing of the
     * account), so a kept one is exactly what reading it again would give; it holds a question's kind and minute only,
     * never Boss's words.
     */
    fun decode(line: String): Seen? = decoded.of(line) { decodeFresh(line) }

    /** The last lines read (twice the log, so a whole log read in order always fits). */
    private val decoded = com.optionslab.ira.Kept<Seen?>(LOG_MAX * 2)

    /** How many decoded lines are kept (tests). */
    internal val decodedKept: Int get() = decoded.size

    /** Every decoded line forgotten (tests). */
    internal fun forgetDecoded() = decoded.clear()

    private fun decodeFresh(line: String): Seen? = runCatching {
        val p = line.split("#")
        if (p.size != 3 || question(p[1]) == null) return null
        Seen(p[1], LocalDateTime.parse(p[0]), p[2] == "1")
    }.getOrNull()

    fun encode(k: Kept): String = "${k.kind.name}#${k.key}#${k.day?.name ?: "-"}#${k.minute}#${k.until}" + (k.since?.let { "#$it" } ?: "")

    fun decodeKept(line: String): Kept? = runCatching {
        val p = line.split("#")
        if (p.size !in 5..6 || question(p[1]) == null) return null
        Kept(p[1], Kind.valueOf(p[0]), p[3].toInt(), if (p[2] == "-") null else DayOfWeek.valueOf(p[2]), LocalDate.parse(p[4]),
            p.getOrNull(5)?.let { LocalDate.parse(it) })
    }.getOrNull()

    // ---- the patterns -----------------------------------------------------------------------------------------------

    private fun fade(d: LocalDate, today: LocalDate, halfLife: Double): Double =
        0.5.pow(Duration.between(d.atStartOfDay(), today.atStartOfDay()).toDays().coerceAtLeast(0) / halfLife)

    private fun minuteOf(t: LocalDateTime) = t.hour * 60 + t.minute

    /** The best half hour (two neighbouring quarters) of [seen]: its asks, by the faded weight of their days. */
    private fun bestHalfHour(seen: List<Seen>, today: LocalDate, halfLife: Double): List<Seen> {
        if (seen.isEmpty()) return emptyList()
        fun weight(s: List<Seen>) = s.map { it.at.toLocalDate() }.distinct().sumOf { fade(it, today, halfLife) }
        return (0 until 96).map { b -> seen.filter { minuteOf(it.at) / 15 in b..b + 1 } }.maxByOrNull { weight(it) }.orEmpty()
    }

    /** The time it is asked around: the middle of each day's first ask, to five minutes. */
    private fun around(seen: List<Seen>): Int {
        val firsts = seen.groupBy { it.at.toLocalDate() }.values.map { d -> d.minOf { minuteOf(it.at) } }.sorted()
        return firsts[firsts.size / 2] / 5 * 5
    }

    /** Boss's routines as of [today], strongest first (at most 12). */
    fun patterns(log: List<Seen>, today: LocalDate): List<Pattern> {
        val out = ArrayList<Pattern>()
        log.filter { !it.at.toLocalDate().isAfter(today) }.groupBy { it.key }.forEach { (key, all) ->
            val month = all.filter { !it.at.toLocalDate().isBefore(today.minusDays(WINDOW_DAYS)) }
            // Just after a loss: on enough different days.
            val lossDays = month.filter { it.afterLoss }.map { it.at.toLocalDate() }.distinct()
            val lw = lossDays.sumOf { fade(it, today, HALF_LIFE_DAYS) }
            if (lossDays.size >= MIN_DAYS && lw >= MIN_WEIGHT) out += Pattern(key, Kind.AFTER_LOSS, -1, null, lossDays.size, lw, lossDays.max())
            // Around the same time on most days.
            val timed = month.filter { !it.afterLoss }
            val best = bestHalfHour(timed, today, HALF_LIFE_DAYS)
            val days = best.map { it.at.toLocalDate() }.distinct()
            val w = days.sumOf { fade(it, today, HALF_LIFE_DAYS) }
            if (days.size >= MIN_DAYS && w >= MIN_WEIGHT && days.map { it.dayOfWeek }.distinct().size >= 2) {
                out += Pattern(key, Kind.DAILY, around(best), null, days.size, w, days.max())
                return@forEach
            }
            // Or on one weekday: most of the days it was asked at all, over twice the window.
            val two = all.filter { !it.afterLoss && !it.at.toLocalDate().isBefore(today.minusDays(WINDOW_DAYS * 2)) }
            val allDays = two.map { it.at.toLocalDate() }.distinct()
            DayOfWeek.values().forEach { dow ->
                val on = bestHalfHour(two.filter { it.at.dayOfWeek == dow }, today, HALF_LIFE_DAYS * 2)
                val d = on.map { it.at.toLocalDate() }.distinct()
                val dw = d.sumOf { fade(it, today, HALF_LIFE_DAYS * 2) }
                if (d.size >= MIN_DAYS && dw >= MIN_WEIGHT && d.size * 3 >= allDays.size * 2)
                    out += Pattern(key, Kind.WEEKDAY, around(on), dow, d.size, dw, d.max())
            }
        }
        return out.filter { safe(it.key) }.sortedByDescending { it.weight }.take(12)
    }

    // ---- offering and keeping ---------------------------------------------------------------------------------------

    private fun same(p: Pattern, k: Kept) = p.key == k.key && p.kind == k.kind && p.day == k.day &&
        (p.kind == Kind.AFTER_LOSS || kotlin.math.abs(p.minute - k.minute) <= 30)

    /** Kept routines still in force on [today]. */
    fun live(kept: List<Kept>, today: LocalDate): List<Kept> = kept.filter { !today.isAfter(it.until) && safe(it.key) }

    /**
     * Just after Boss asked [key]: a pattern of it to put to him, or null - one not kept already and not put to him in
     * the last [OFFER_AGAIN_DAYS] ([offered]: slot to the day it was).
     */
    fun toOffer(patterns: List<Pattern>, key: String, kept: List<Kept>, offered: Map<String, LocalDate>, today: LocalDate): Pattern? =
        patterns.firstOrNull { p -> p.key == key && safe(p.key) && live(kept, today).none { same(p, it) } &&
            offered[p.slot]?.let { it.isBefore(today.minusDays(OFFER_AGAIN_DAYS)) } != false }

    private fun clock(minute: Int): String {
        val h = minute / 60
        val m = minute % 60
        return "${if (h > 12) h - 12 else h}:${m.toString().padStart(2, '0')}"
    }

    private fun dayName(d: DayOfWeek) = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    internal fun whenSaid(kind: Kind, minute: Int, day: DayOfWeek?): String = when (kind) {
        Kind.DAILY -> "around ${clock(minute)} on trading days"
        Kind.WEEKDAY -> "on ${dayName(day ?: DayOfWeek.MONDAY)}s around ${clock(minute)}"
        Kind.AFTER_LOSS -> "just after a losing trade"
    }

    /** Starts every offer (the app keeps it apart from the answer). */
    const val OFFER = "Boss, you usually ask"

    /** "Boss, you usually ask about BankNifty's levels around 9:20 on trading days. Shall I tell you then?" */
    fun offer(p: Pattern): String =
        "$OFFER about ${about(p.key)} ${whenSaid(p.kind, p.minute, p.day)}. Shall I tell you then, in words only?"

    /** The yes: kept from [today] for [KEEP_DAYS]. Null when it would not be a plain question's answer. */
    fun keep(p: Pattern, today: LocalDate): Kept? =
        if (!safe(p.key)) null else Kept(p.key, p.kind, p.minute, p.day, today.plusDays(KEEP_DAYS), since = today)

    fun kept(k: Kept): String = "Done, Boss: ${whenSaid(k.kind, k.minute, k.day)} I'll tell you ${about(k.key)} - in words only, never an action. " +
        "Say \"forget my routine\" to stop."

    /** At most this many routines are kept (lapsed ones go first, then the oldest): a lapsed one is otherwise never dropped. */
    const val KEPT_MAX = 24

    /** [kept] with [k] in place of any of the same routine (and at most [KEPT_MAX] in all). */
    fun put(kept: List<Kept>, k: Kept): List<Kept> {
        val all = kept.filter { it.key != k.key || it.kind != k.kind || it.day != k.day } + k
        if (all.size <= KEPT_MAX) return all
        // [k] was kept today, for [KEEP_DAYS]: the routines lapsed by then are dropped first, the longest lapsed first.
        val today = k.until.minusDays(KEEP_DAYS)
        val lapsed = all.filter { today.isAfter(it.until) }.sortedBy { it.until }.take(all.size - KEPT_MAX).toSet()
        return all.filter { it !in lapsed }.takeLast(KEPT_MAX)
    }

    /** Boss asked [key] himself on [today]: his kept routines of it last another [KEEP_DAYS] from today. */
    fun renew(kept: List<Kept>, key: String, today: LocalDate): List<Kept> =
        kept.map { if (it.key == key && it.until.isBefore(today.plusDays(KEEP_DAYS))) it.copy(until = today.plusDays(KEEP_DAYS)) else it }

    // ---- at its time ------------------------------------------------------------------------------------------------

    /** Once a day each (and once a loss for the after-loss ones): what the app remembers as told. */
    fun token(k: Kept, lossAt: LocalDateTime?): String = if (k.kind == Kind.AFTER_LOSS) "${k.id}@${lossAt?.withSecond(0)?.withNano(0)}" else k.id

    /**
     * The kept routine due [now], or null: from five minutes before its time to five after (on its weekday), or within
     * [AFTER_LOSS_MINUTES] after [lossAt] (Boss's own trade closed at a loss today). Not when told already ([told]: its
     * [token]s) or when Boss asked it himself in the last half hour ([log]).
     */
    fun due(kept: List<Kept>, now: LocalDateTime, told: Set<String>, lossAt: LocalDateTime?, log: List<Seen> = emptyList()): Kept? {
        val m = minuteOf(now)
        return live(kept, now.toLocalDate()).firstOrNull { k ->
            val asked = log.any { it.key == k.key && !it.at.isAfter(now) && Duration.between(it.at, now).toMinutes() < 30 }
            val time = when (k.kind) {
                Kind.DAILY -> m in k.minute - 5..k.minute + 5
                Kind.WEEKDAY -> now.dayOfWeek == k.day && m in k.minute - 5..k.minute + 5
                Kind.AFTER_LOSS -> lossAt != null && lossAt.toLocalDate() == now.toLocalDate() && !lossAt.isAfter(now) &&
                    Duration.between(lossAt, now).toMinutes() <= AFTER_LOSS_MINUTES
            }
            time && !asked && token(k, lossAt) !in told
        }
    }

    /** What Jarvis says before the answer at its time. */
    fun lead(k: Kept): String = "Boss, as you asked, ${about(k.key)} ${whenSaid(k.kind, k.minute, k.day)}: "

    /**
     * When the last of Boss's own trades closed at a loss within [AFTER_LOSS_MINUTES] before [now] (today), else null:
     * a win closed after it means it is no longer "just after a loss".
     */
    fun lossAt(trips: List<Insights.Trip>, now: LocalDateTime): LocalDateTime? {
        val last = trips.filter { !it.closedAt.isAfter(now) && it.closedAt.toLocalDate() == now.toLocalDate() }.maxByOrNull { it.closedAt } ?: return null
        return last.closedAt.takeIf { last.net < 0 && Duration.between(it, now).toMinutes() <= AFTER_LOSS_MINUTES }
    }

    // ---- asked about ------------------------------------------------------------------------------------------------

    private val HABITS_ASKED = Regex("^(jarvis )?(what do i (usually|normally|mostly|always|often) ask( you)?( about)?|what do i ask you (the )?(most|usually|every day|often)|" +
        "what (are|is) my (habits?|routine) with you|(what s|whats) my (trading )?routine|what is my (trading )?routine|what have you (learned|noticed|learnt) about my (routine|habits)|" +
        "do i have a routine( with you)?|what is my usual routine|" +
        // Hinglish (routing audit, round 7): "mera routine kya hai", "main usually kya poochta hoon".
        "(mera|meri) (routine|usual routine) (kya|kia) (hai|he|h)|(main|mai|me) (usually|aksar|zyada tar|zyadatar|mostly|roz|rozana) (tumse |aapse |tumhe |aapko )?(kya|kia) (poochta|puchta|pucchta|poochti|puchti|pucchti) (hoon|hu|hun|hoo))( jarvis)?$")
    private val FORGET_ASKED = Regex("^(jarvis )?(forget|drop|clear|stop) (my|the) (routines?|habits)( with you)?( please)?$")

    private fun plain(text: String) = text.lowercase().replace(rx("[^a-z ]"), " ").replace(rx("\\s+"), " ").trim()

    /**
     * "What do I usually ask?" / "what are my habits with you?" (not his trading habits, which the reviews tell). "What is
     * my trading routine" is this routine (routing audit, round 8: it fell to a market answer).
     */
    fun asked(text: String): Boolean = HABITS_ASKED.matches(plain(text)) &&
        !rx("\\b(trade|trades|losing|money)\\b|\\btrading\\b(?! routine\\b)").containsMatchIn(plain(text))

    /** "Forget my routine": every routine learned and kept is dropped. */
    fun forgetAsked(text: String): Boolean = FORGET_ASKED.matches(plain(text))

    /** The answer to [asked]: the routines found, which are kept (said at their time) and until when. */
    fun describe(patterns: List<Pattern>, kept: List<Kept>, today: LocalDate): String {
        val live = live(kept, today)
        val lines = patterns.take(5).map { p ->
            val k = live.firstOrNull { same(p, it) }
            "${about(p.key)} ${whenSaid(p.kind, p.minute, p.day)} (${p.days} days)" + (if (k != null) " - I tell you then, until ${k.until.dayOfMonth} ${month(k.until)}" else "")
        } + live.filter { k -> patterns.none { same(it, k) } }.map { k ->
            "${about(k.key)} ${whenSaid(k.kind, k.minute, k.day)} - I tell you then, until ${k.until.dayOfMonth} ${month(k.until)}"
        }
        if (lines.isEmpty()) return "I haven't noticed a routine yet, Boss: ask me the same things at about the same times for a few days and I'll learn it."
        return "Here's what you usually ask me, Boss: " + lines.joinToString("; ") + ". Only questions are learned, and anything I say at its time is " +
            "words only. Say \"forget my routine\" to clear it."
    }

    private fun month(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    const val FORGOT = "Done, Boss: I've forgotten your routine. I'll learn it again only from what you ask, and say nothing at its time unless you say yes again."
}
