package com.optionslab.ira

import java.time.Duration
import java.time.LocalDateTime

/**
 * Jarvis noticing the questions he answered with the wrong thing (learning round 15, 2026-10-05; Boss's diagnostics:
 * "Words I could not place: open youtube", and "why was my last order canceled" asked again and again for the wrong
 * answer). Two signs that an answer missed what Boss asked:
 *
 *  - [Why.REPEAT]: Boss asks the same question again within [MAX_GAP_S] of it being answered (the same kind of question
 *    taken the same way, or most of the same words) - not the same words heard twice within [MIN_GAP_S];
 *  - [Why.SAID]: Boss says so - "that's not what I asked", "that was wrong" (the existing mark-wrong command, noted here
 *    too), "galat jawab", "ye nahi poocha", "you answered the wrong thing" - within [SAID_MS] of the question.
 *
 * Each is noted by the question's KIND ([kind]: what Boss's words were about - his orders, why an order was cancelled,
 * opening another app, the levels...) and the WAY JARVIS TOOK IT ([took]: which feature's kind of answer it got - his
 * account's sections, why the market moved, words he could not place...), when, and which sign - never the words. One note
 * a question at most. The market reads Boss asks again are [AskedAgain]'s record (a read asked again is often wanted
 * fresher, not wrong), and a P&L or a price asked again is wanting it fresh: neither is noted here as a repeat.
 *
 * A RECORD only, to fix the routing by: it is shown in the Learnings ledger ([Learnings.Area.WRONG_THING], "Questions I
 * answered with the wrong thing") and answers "what did you get wrong today?" ([asked], [say]). Nothing learned here
 * acts or re-routes a question by itself: a wording is read another way only through Boss's own "learn" with yes
 * ([Corrections]). Only the last [WINDOW_DAYS] days count. Pure: the app keeps the log, and the last question (its kind,
 * the way taken and its words as a set, in memory only) to compare the next one with.
 */
object WrongThing {
    /** Only the last this many days count. */
    const val WINDOW_DAYS = 30L
    /** Asked again this soon (seconds) is the same question; later is a fresh one. */
    const val MAX_GAP_S = 120L
    /** Sooner than this (seconds) is the same words heard twice, not Boss asking again. */
    const val MIN_GAP_S = 8L
    /** Boss's "that's not what I asked" counts within this long (ms) of the question. */
    const val SAID_MS = 180_000L
    /** Two questions sharing this share of their words are the same question. */
    const val SAME_WORDS = 0.6
    /** A kind taken one way shows in the ledger at this many notes (or at once, when Boss said so). */
    const val MIN_SHOW = 2
    /** Events kept at most. */
    const val KEEP = 300
    /** Pairs named at most. */
    const val SHOW = 3

    /** Why an answer was noted as missing: asked again, or Boss said so. */
    enum class Why { REPEAT, SAID }

    /** One answer that missed: the question's kind, the way it was taken, when and why. No words are kept. */
    data class Event(val kind: String, val took: String, val at: LocalDateTime, val why: Why)

    /** The misses noted. */
    data class Log(val events: List<Event> = emptyList())

    /** The question asked last (in memory only): its kind, the way it was taken, its words as a set, when, and whether noted. */
    data class Last(val kind: String, val took: String, val words: Set<String>, val at: LocalDateTime, val noted: Boolean = false)

    // ---- the question's kind (what Boss's words were about) ---------------------------------------------------------

    private class K(val key: String, val phrase: String, val re: Regex)

    private fun k(key: String, phrase: String, re: String) = K(key, phrase, rx(re))

    /** In order: the first that matches is the kind. */
    private val KINDS: List<K> = listOf(
        k("APP", "opening another app", "^ (please )?(open|launch|start|play|kholo|khol do|chalao|chala do) (the )?(youtube|whatsapp|gmail|chrome|camera|maps|google maps|spotify|instagram|facebook|twitter|telegram|netflix|music|gallery|calculator|calendar app|settings app|play store|[a-z]+ app)( app)? |" +
            "^ (youtube|whatsapp|gmail|chrome|camera|spotify|instagram|facebook|telegram|netflix|gallery|calculator) (kholo|khol do|open karo|chalao|chala do|open|kar do) "),
        k("ORDER_WHY", "why an order was cancelled or rejected", " (why|kyu|kyun|kyon|reason) .*(order|trade)s? .*(cancel|cancelled|canceled|cancelling|reject|rejected|rejection|not (placed|filled|executed)|fail|failed) |" +
            " (order|trade)s? .*(cancel|cancelled|canceled|reject|rejected|fail|failed) .*(why|kyu|kyun|kyon|reason) "),
        k("ORDERS", "your orders", " (order|orders|fills|filled|executed|pending orders?) "),
        k("POSITIONS", "your positions", " (position|positions|holdings?) "),
        k("PNL", "your P&L", " (p l|pnl|profit|loss|losses|mtm|kitna kamaya|kitna gaya) "),
        k("FUNDS", "your funds and margin", " (funds?|margin|margins|balance|buying power) "),
        k("STRATEGIES", "your strategies and bots", " (strategy|strategies|bot|bots|algo|algos|orb|pine|arm|arms) "),
        k("LIMITS", "your limits", " (kill switch|loss limit|daily loss|limits?|guard|guards) "),
        k("ZERODHA", "your Zerodha login", " (zerodha|kite|logged out|log out|logout|login|logged in|session) "),
        k("SETTINGS", "the app's settings", " (settings?|live mode|paper mode|biometric|fingerprint|pin) "),
        k("CHAIN", "the option chain", " (oi|open interest|pcr|max pain|option chain|chain|call writing|put writing) "),
        k("CALENDAR", "expiry and holidays", " (expiry|expiries|holiday|holidays|market open today|trading day) "),
        k("NEWS", "the news", " (news|headline|headlines|khabar) "),
        k("MOVE", "why the market moved", " (why|kyu|kyun|kyon) .*(fall|fell|falling|drop|dropped|down|rise|rose|rising|up|rally|move|moved|crash|crashed|gir|badh) "),
        k("LEVELS", "the levels", " (support|resistance|levels?) "),
        k("VIX", "volatility", " (vix|volatility|volatile) "),
        k("TERM", "what a term means", " (what is|what s|whats|what does|meaning of|define|matlab) "),
        k("JARVIS", "you yourself", " (you|your|yourself|jarvis) "),
    )

    /** "OTHER" when none: a question of another kind. */
    const val OTHER = "OTHER"

    private fun spaced(text: String): String = " " + runCatching { Ask.reading(text) }.getOrDefault(text).lowercase()
        .replace("p&l", "p l").replace("'", "").replace("’", "").replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    /** What [text] was about (a kind key; [OTHER] when none fits). */
    fun kind(text: String): String {
        val t = spaced(text)
        return KINDS.firstOrNull { it.re.containsMatchIn(t) }?.key ?: OTHER
    }

    fun kindPhrase(key: String): String = KINDS.firstOrNull { it.key == key }?.phrase ?: "a question of another kind"

    // ---- the way Jarvis took it (which feature's kind of answer it got) -----------------------------------------------

    private val TOOK_TOPIC = listOf(
        Topic.WHY to "why the market moved", Topic.LEVELS to "the levels", Topic.TREND to "the trend", Topic.PATTERNS to "patterns",
        Topic.NEWS to "the news", Topic.VOLATILITY to "volatility", Topic.ADVICE to "what to do", Topic.BACKTEST to "a backtest",
        Topic.TRADE_CHECK to "a trade check", Topic.SUGGEST to "a trade idea", Topic.EXPLAIN to "explaining a term",
        Topic.HELP to "what I can do")

    /**
     * The way Jarvis's routing takes [text] - the feature whose kind of answer it gets, as the parse reads it (null: a
     * command or an order, never noted here). "account:ORDERS" (his account's sections), "topic:WHY", "missed"...
     */
    fun took(text: String): String? {
        val q = runCatching { Ask.parse(text) }.getOrNull() ?: return null
        if (q.command != null || q.order != null || runCatching { Commands.parse(text) }.getOrNull() != null) return null
        if (Topic.ORDER in q.topics || Topic.COMMAND in q.topics) return null
        if (runCatching { Bundle.acts(text) }.getOrDefault(true)) return null
        if (runCatching { ZerodhaSession.asked(text) != null }.getOrDefault(false)) return "zerodha"
        if (runCatching { Causes.asked(text) != null }.getOrDefault(false)) return "topic:WHY"
        if (Topic.ACCOUNT in q.topics) {
            val s = runCatching { AppAnswers.sections(text) }.getOrDefault(emptySet())
            return "account:" + s.take(2).joinToString("+") { it.name }
        }
        TOOK_TOPIC.firstOrNull { it.first in q.topics }?.let { return "topic:${it.first.name}" }
        if (Topic.OFF_TOPIC in q.topics) return when {
            runCatching { Chat.smallTalk(text, 0) != null }.getOrDefault(false) -> "chat"
            runCatching { Suggest.closest(text) != null }.getOrDefault(false) -> "guess"
            else -> "missed"
        }
        if (q.topics == setOf(Topic.GREETING)) return "chat"
        return "topic:OVERVIEW"
    }

    /** The account answer's sections that are the app's or the market's facts, not Boss's own. */
    private val APP_FACTS = setOf(Section.STATUS, Section.SETTINGS, Section.HOWTO, Section.EVENTS, Section.CHAIN, Section.FLOWS,
        Section.STUDY, Section.REGIME, Section.MISTAKES, Section.ACTIVITY, Section.CHANGES)

    fun tookPhrase(key: String): String = when {
        key == "zerodha" -> "your Zerodha session"
        key == "chat" -> "small talk"
        key == "guess" -> "a guess at what you meant"
        key == "missed" -> "words I could not place"
        key.startsWith("account:") -> {
            val s = key.substringAfter(':').split('+').mapNotNull { n -> runCatching { Section.valueOf(n) }.getOrNull() }
            val names = s.map { it.title }
            (if (s.isNotEmpty() && s.all { it in APP_FACTS }) "the app's facts" else "your account") + if (names.isEmpty()) "" else " (" + names.joinToString(" and ") + ")"
        }
        key == "topic:OVERVIEW" -> "how the market is doing"
        key.startsWith("topic:") -> TOOK_TOPIC.firstOrNull { "topic:${it.first.name}" == key }?.second ?: "a market read"
        else -> "another answer"
    }

    // ---- what Boss says --------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + text.lowercase().replace("'", "").replace("’", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private val STOP = setOf("the", "a", "an", "is", "was", "my", "me", "i", "you", "jarvis", "please", "boss", "hey", "ok", "okay", "to", "of", "and", "so", "tell")

    private fun words(text: String): Set<String> = norm(text).trim().split(' ').filter { it.isNotBlank() && it !in STOP }.toSet()

    private val OBJECTED = rx("^ (hey |ok |okay )?(jarvis )?(no |nahi |nahin )?(" +
        "galat (jawab|jawaab|answer|reply)( (diya|de diya|hai|tha|mila))?|(ye|yeh|ya|wo|woh) galat (jawab|jawaab|answer) (hai|tha)|" +
        "(maine |main |mai )?(ye|yeh|ya|wo|woh) (nahi|nahin|nai) (poocha|pucha|puchha|poochha)( tha)?( maine)?|" +
        "(that s|thats|that is|this is) not what i (meant|asked|asked for|wanted)|not what i (meant|asked for|wanted)|" +
        "you answered the wrong (thing|question)|(that s|thats|that is|that was) the wrong (answer|thing)|wrong thing|you (got|took) (it|that|me) wrong|" +
        "you answered something else|(that s|thats|that is) not my question|i asked something else" +
        ")( jarvis| boss| please)* $")

    /** "Galat jawab", "ye nahi poocha", "you answered the wrong thing": Boss saying the last answer missed (a note only). */
    fun objected(text: String): Boolean = OBJECTED.containsMatchIn(norm(text))

    /** The result of one question: the log (with a miss noted, or as it was), the question now last, and the miss noted. */
    data class Step(val log: Log, val last: Last?, val noted: Event?)

    private fun add(log: Log, e: Event): Log {
        val from = e.at.minusDays(WINDOW_DAYS)
        return Log((log.events.filter { it.at.isAfter(from) } + e).takeLast(KEEP))
    }

    /** A P&L or a price asked again is wanting it fresh; a market read asked again is [AskedAgain]'s record. */
    private fun freshWanted(kind: String, took: String, text: String): Boolean =
        kind == "PNL" || took == "topic:OVERVIEW" && kind == OTHER ||
            runCatching { AskedAgain.read(text) is AskedAgain.Read.Reading }.getOrDefault(false)

    /**
     * Boss asked [text] at [at], with [last] the question asked before (null: none). The same question again within the
     * window, after the first was answered, notes that answer as a miss (once a question). A command, an order or Boss's
     * own "that was wrong" is passed over (the question stays last).
     */
    fun heard(log: Log, last: Last?, text: String, at: LocalDateTime): Step {
        // Boss's "that was wrong" and "what did you get wrong?" are about the answers, not questions to note.
        if (objected(text) || asked(text) != null) return Step(log, last, null)
        val took = took(text) ?: return Step(log, last, null)
        val kind = kind(text)
        val now = Last(kind, took, words(text), at)
        if (last == null) return Step(log, now, null)
        val gap = Duration.between(last.at, at).seconds
        if (gap < 0 || gap > MAX_GAP_S) return Step(log, now, null)
        val same = (kind != OTHER && kind == last.kind && took == last.took) || overlap(now.words, last.words) >= SAME_WORDS
        if (!same) return Step(log, now, null)
        // The same words heard twice: the question stays as first asked.
        if (gap < MIN_GAP_S) return Step(log, last, null)
        if (last.noted || freshWanted(kind, took, text)) return Step(log, now.copy(noted = last.noted), null)
        val e = Event(last.kind, last.took, at, Why.REPEAT)
        return Step(add(log, e), now.copy(noted = true), e)
    }

    /** Boss said the last answer was wrong at [at] ("galat jawab", "that's not what I asked"): noted against [last], once. */
    fun said(log: Log, last: Last?, at: LocalDateTime): Step {
        if (last == null || last.noted) return Step(log, last, null)
        val ms = Duration.between(last.at, at).toMillis()
        if (ms < 0 || ms > SAID_MS) return Step(log, last, null)
        val e = Event(last.kind, last.took, at, Why.SAID)
        return Step(add(log, e), last.copy(noted = true), e)
    }

    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return (a intersect b).size.toDouble() / (a union b).size
    }

    // ---- the record ------------------------------------------------------------------------------------------------

    /** One kind taken one way: how often it missed (asked again, and said wrong) and when last. */
    data class Record(val kind: String, val took: String, val repeats: Int, val said: Int, val newest: LocalDateTime) {
        val times: Int get() = repeats + said
        fun what(): String = "${kindPhrase(kind)}, answered as ${tookPhrase(took)}"
        fun how(): String = listOfNotNull(
            repeats.takeIf { it > 0 }?.let { if (it == 1) "you asked again once" else "you asked again $it times" },
            said.takeIf { it > 0 }?.let { if (it == 1) "you said once it was not what you asked" else "you said $it times it was not what you asked" },
        ).joinToString(" and ")
    }

    /** Every kind-and-way that missed between [from] and [now], most missed first. */
    fun records(log: Log, now: LocalDateTime, from: LocalDateTime = now.minusDays(WINDOW_DAYS)): List<Record> =
        log.events.filter { it.at.isAfter(from) && !it.at.isAfter(now) }
            .groupBy { it.kind to it.took }
            .map { (k, es) -> Record(k.first, k.second, es.count { it.why == Why.REPEAT }, es.count { it.why == Why.SAID }, es.maxOf { it.at }) }
            .sortedWith(compareByDescending<Record> { it.times }.thenByDescending { it.newest })

    /** The ones shown in the ledger: missed [MIN_SHOW] times, or once when Boss said so. */
    fun shown(log: Log, now: LocalDateTime): List<Record> = records(log, now).filter { it.times >= MIN_SHOW || it.said > 0 }

    // ---- asked -----------------------------------------------------------------------------------------------------

    /** "What did you get wrong today?" ([TODAY]) or over the record ([ALL]). */
    enum class Request { TODAY, ALL }

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis)* $"
    private const val WHEN = "( today| so far today| this week| lately| recently| this month)"
    private val ASKED = rx(LEAD + "what (did|have) you (get|got|gotten|answer|answered) wrong$WHEN" + TAIL + "|" +
        LEAD + "(which|what) (of my )?(questions|things) (did|have) you (get|got|gotten|answer|answered|take|took|taken) (wrong|wrongly|the wrong way)$WHEN?" + TAIL + "|" +
        // (Routing round 13: "what did you answer wrongly today", "what did you misunderstand today", "what mistakes did you make today".)
        LEAD + "what (did|have) you (answer|answered) wrongly$WHEN?" + TAIL + "|" +
        LEAD + "what (did|have) you (misunderstand|misunderstood)$WHEN?" + TAIL + "|" +
        LEAD + "what mistakes (did|have) you (make|made)$WHEN?" + TAIL + "|" +
        LEAD + "(aaj )?(aapne |apne |tumne |tum ne |aap ne )?(aaj )?(kya|kaun se|kon se|kaunse) (sawal |sawaal |question |questions )?galat (samjha|samjhe|samjhi|suna)( aaj)?" + TAIL + "|" +
        LEAD + "(which|what) (questions|things) (did|have) you answer(ed)? with the wrong (thing|answer)$WHEN?" + TAIL + "|" +
        LEAD + "(which|what) questions (did|have) you (misunderstand|misunderstood|misread|mishear|misheard|misroute|misrouted)$WHEN?" + TAIL + "|" +
        LEAD + "(where|when) (did|have) you (misunderstand|misunderstood|misread|mishear|misheard) me$WHEN?" + TAIL + "|" +
        LEAD + "(show me |list )?(the )?questions you answered (with the )?wrong( thing)?$WHEN?" + TAIL + "|" +
        LEAD + "(what|which) (did|have) you (route|send|routed|sent) (to the )?wrong( place| feature)?$WHEN?" + TAIL + "|" +
        LEAD + "(aaj )?(aapne |apne |tumne |tum ne |aap ne )?(aaj )?(kya|kaun se|kon se|kaunse) (sawal|sawaal|question|questions|cheez|cheezein)? ?(ka |ke |mein |main )?galat (jawab|jawaab|answer) (diya|diye|kiya|kiye)( aaj)?" + TAIL)

    fun asked(text: String): Request? {
        val t = norm(text)
        if (!ASKED.containsMatchIn(t)) return null
        return if (rx(" (today|aaj) ").containsMatchIn(t)) Request.TODAY else Request.ALL
    }

    const val ONLY_RECORD = "A record only, for fixing how I route questions: nothing I learn re-routes a question or acts by itself. " +
        "If a wording should go elsewhere, say it your way after \"that was wrong\" and answer my \"learn\" question with yes."

    /** "What did you get wrong today?" / "which questions did you answer with the wrong thing?". */
    fun say(log: Log, request: Request, now: LocalDateTime): String {
        val today = request == Request.TODAY
        val from = if (today) now.toLocalDate().atStartOfDay().minusNanos(1) else now.minusDays(WINDOW_DAYS)
        val all = records(log, now, from)
        val span = if (today) "today" else "in the last $WINDOW_DAYS days"
        if (all.isEmpty()) return "Nothing I noticed $span, Boss: you didn't ask a question again within ${MAX_GAP_S / 60} minutes of my answer, " +
            "or tell me it was not what you asked. When you do, I note what the question was about and how I took it - never your words. $ONLY_RECORD"
        val n = all.sumOf { it.times }
        val list = all.take(SHOW).joinToString("; ") { "${it.what()} (${it.how()})" } + if (all.size > SHOW) "; and ${all.size - SHOW} more" else ""
        return "Boss, ${if (n == 1) "one answer" else "$n answers"} missed what you asked $span: $list. $ONLY_RECORD"
    }

    /** The ledger's line for [r]. */
    fun ledgerWhat(r: Record): String = r.what()
    fun ledgerWhy(r: Record): String = "${r.how()}, in the last $WINDOW_DAYS days - a sign I took it the wrong way; a record to fix my routing by, it re-routes nothing"
}
