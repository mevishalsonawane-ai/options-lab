package com.optionslab.ira

import java.util.Locale

/**
 * Battery, round 1 (Boss, 5 Oct: "make the app use less battery"): the battery saver for listening. Boss's own switch,
 * OFF by default - off, listening is exactly as before. On, Jarvis's ears rest between turns only while ALL of these
 * hold: the screen is off, the market is shut, it is not one of the hours Boss talks to him ([TalkHours], once learned),
 * the room has been quiet a long while ([EmptyTurns.SLOW_AFTER] empty turns in a row, about three minutes) and nobody is
 * expected to answer (after "Yes, Boss?", a follow-up or a yes / no asked - never then). Resting means the next turn
 * starts a few seconds later: he still listens for "Jarvis", only less of the time, so a "Jarvis" said in the rest may
 * need saying again. The screen coming on ends the rest at once. Nothing here touches a safety alert, a stop or an
 * order: those never wait on listening. Pure.
 */
object ListenSaver {
    /** The rest between quiet turns with the screen off, the market shut, outside Boss's hours. */
    const val REST_MS = 4_000L
    /** ...at night (22:00 to 07:00). */
    const val NIGHT_MS = 8_000L
    /** Never longer than this, whatever the battery. */
    const val MAX_MS = 15_000L

    data class Now(
        /** Boss's switch: battery saver for listening. */
        val on: Boolean,
        val screenOn: Boolean,
        val marketOpen: Boolean,
        /** In (or near) one of the hours Boss talks to Jarvis - false while none is learned. */
        val talkHour: Boolean,
        /** 22:00 to 07:00. */
        val night: Boolean,
        val emptyInRow: Int,
        /** Boss is expected to speak (awake, a follow-up window, a yes or no asked, Jarvis talking). */
        val expected: Boolean,
        /** The phone's battery is low and not charging ([BatterySaver.saving]). */
        val batteryLow: Boolean,
    )

    /** Is listening resting now? */
    fun resting(n: Now): Boolean = n.on && !n.expected && !n.screenOn && !n.marketOpen && !n.talkHour &&
        n.emptyInRow >= EmptyTurns.SLOW_AFTER

    /** The wait before the next turn: [base] as before, or the rest while [resting]. */
    fun gap(base: Long, n: Now): Long {
        if (!resting(n)) return base
        val rest = if (n.night) NIGHT_MS else REST_MS
        return minOf(MAX_MS, maxOf(base, if (n.batteryLow) rest * 2 else rest))
    }

    /**
     * Battery, round 4: the voice check and the morning check say this once while Jarvis listens with the saver off -
     * words only: the switch stays Boss's, never turned on by itself.
     */
    const val HINT = "Battery saver for listening is off: turning it on (the Jarvis page) rests my ears at night when the screen is off " +
        "and the room is quiet. I won't mention it again."

    /** [HINT] when it is to be said: listening runs, the saver is off and it was not said before. */
    fun hint(listening: Boolean, saverOn: Boolean, told: Boolean): String? = HINT.takeIf { listening && !saverOn && !told }

    /** For the switch's note and the battery answer. */
    const val WHAT = "When the screen is off, the market is shut and the room has been quiet a few minutes (not in the hours you " +
        "usually talk to me), I listen in short rests - a \"Jarvis\" said in a rest may need saying again. Off: I listen as before."
}

/**
 * "Why is the app using battery?", "battery kyun kha raha hai": what of the app runs in the background now, the biggest
 * cost first, and what can be switched - from the app's own state, no account figure, nothing acted on. Also the
 * diagnostics' "Battery:" line. The order watch is named but never offered slower: it guards stops, targets, exits and
 * the expiry square-off. Pure.
 */
object BatteryUse {
    data class Snapshot(
        /** Jarvis's ears running now. */
        val listening: Boolean,
        /** Battery saver for listening switched on. */
        val listenSaver: Boolean,
        /** Listening is resting right now ([ListenSaver.resting]). */
        val resting: Boolean,
        /** The order watch's service is running. */
        val watch: Boolean,
        /** Its check pace now in seconds (15 with a position open, 60 otherwise, 30 before the open), or null. */
        val watchStepSec: Int?,
        /** Zerodha's live price stream: OFF, CONNECTING, LIVE or RETRYING. */
        val stream: String,
        /** Instruments the stream follows. */
        val streamTokens: Int,
        /** The on-device AI model in memory. */
        val modelLoaded: Boolean,
        val marketOpen: Boolean,
        val batteryPercent: Int?,
        val charging: Boolean,
        /** Jarvis's words lane in the order watch: true at the quiet pace ([WordsPace.quiet]), false every round, null not running lately. */
        val wordsQuiet: Boolean? = null,
        /** Listening's cost now: minutes it has run since it started, null not known. */
        val listenMinutes: Long? = null,
        /** ...and the speech recognizer's turns in the last hour (each one wakes the microphone and the recognizer). */
        val turnsLastHour: Int? = null,
        /** Boss asked about the news today: the quiet pace reads it every [WordsPace.QUIET_NEWS_MS], else [WordsPace.UNASKED_NEWS_MS]. */
        val newsAskedToday: Boolean = false,
    )

    /** The quiet pace's news read in words: what [WordsPace.newsDueUnasked] does now ("news every 20 min until you ask about it today"). */
    fun quietNews(s: Snapshot): String =
        if (s.newsAskedToday) "news every ${WordsPace.QUIET_NEWS_MS / 60_000} min"
        else "news every ${WordsPace.UNASKED_NEWS_MS / 60_000} min until you ask about it today"

    /** Listening's cost in words ("running 7 h 20 min, 212 recognizer turns in the last hour"), null when not known. */
    fun listenCost(s: Snapshot): String? {
        if (!s.listening || (s.listenMinutes == null && s.turnsLastHour == null)) return null
        val run = s.listenMinutes?.let { m -> "running " + (if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min") }
        val turns = s.turnsLastHour?.let { t -> "$t recognizer turn${if (t == 1) "" else "s"} in the last hour" + if (t >= 6) " (about one every ${3600 / t} s)" else "" }
        return listOfNotNull(run, turns).joinToString(", ")
    }

    /** The diagnostics' line: what runs in the background now. */
    fun line(s: Snapshot): String = "Battery: " + listOf(
        "listening " + (if (!s.listening) "off" else (if (s.resting) "resting" else "on") + " (battery saver for listening ${if (s.listenSaver) "on" else "off"}" +
            (listenCost(s)?.let { "; $it" } ?: "") + ")"),
        "order watch " + (if (!s.watch) "not running" else s.watchStepSec?.let { "every $it s" } ?: "running") + when (s.wordsQuiet.takeIf { s.watch }) {
            null -> ""
            true -> " (Jarvis's words quiet: slow checks every ${WordsPace.QUIET_SLOW_MS / 60_000} min, ${quietNews(s)})"
            false -> " (Jarvis's words every round)"
        },
        "live stream ${s.stream}" + if (s.stream != "OFF") " (${s.streamTokens} instruments)" else "",
        "AI model " + if (s.modelLoaded) "loaded" else "not loaded",
        "phone " + (s.batteryPercent?.let { "$it%" } ?: "battery unknown") + if (s.charging) ", charging" else ", not charging",
    ).joinToString(" · ")

    /**
     * The spoken answer, the biggest background cost first. [locked]: a locked phone - the order watch's pace (every 15
     * seconds means something is held) and the stream's instrument count are left out, so nothing hints at a position.
     */
    fun answer(s: Snapshot, locked: Boolean = false): String {
        val parts = ArrayList<String>()
        if (s.listening) parts += "Listening for \"Jarvis\" - the microphone and the phone's speech recognizer, all the time; that is usually the biggest" +
            (listenCost(s)?.let { " ($it)" } ?: "") + ". " +
            if (s.listenSaver) "Battery saver for listening is on" + (if (s.resting) ", and I'm resting between turns now." else ": I rest between turns when the screen is off, the market is shut and the room is quiet.")
            else "Battery saver for listening is off: switch it on in the Jarvis page and I listen in short rests when the screen is off, the market is shut and the room is quiet."
        if (s.stream != "OFF") parts += "Zerodha's live price stream" + (if (locked) "" else " (${s.streamTokens} instrument${if (s.streamTokens == 1) "" else "s"})") +
            ": it stops by itself when nothing needs live prices for a few minutes."
        if (s.watch) parts += "The order watch" + (s.watchStepSec?.takeIf { !locked }?.let { ", every $it seconds" } ?: "") +
            ": it guards your stops, targets, exits and the expiry square-off, so I never slow it; it ends at the close."
        if (s.modelLoaded) parts += "The AI model is in memory: it leaves by itself after a few minutes unused."
        val battery = when {
            s.batteryPercent == null -> null
            s.charging -> "The phone is charging (${s.batteryPercent}%)."
            BatterySaver.saving(s.batteryPercent, false) -> "Battery at ${s.batteryPercent}%: my own refreshes already run less often (stops are not affected)."
            else -> null
        }
        if (parts.isEmpty()) return Address.boss("Very little of mine runs in the background now: not listening, no live stream, no order watch" +
            (if (s.marketOpen) "" else " (the market is shut)") + ". " + (battery ?: "")).trim()
        val head = if (parts.size == 1) "One thing of mine runs in the background now, Boss: " else "Here's what runs in the background now, biggest first, Boss: "
        return (head + parts.joinToString(" ") + (battery?.let { " $it" } ?: "")).trim()
    }

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("'", "").replace("’", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| today| so fast| so quickly| lately| these days| bhai| yaar)* $"
    private const val WHO = "(the |this |my )?(app|iraalgo|ira algo|jarvis|you|phone)"
    private const val EAT = "(using|eating|draining|consuming|killing|burning|taking|use|eat|drain|consume|kill|burn|take)"
    private const val MUCH = "( so much| this much| a lot of| lots of| too much| more| my| the| up( my| the)?)*"
    private const val KYUN = "(kyun|kyu|kyon|kyoon|kaise)"
    private const val KHA = "(kha|khaa|kha raha|khaa raha|kha rahi|khaa rahi|kha rha|ja raha|jaa raha|ja rahi|jaa rahi|ud raha|udd raha|gir raha|gir rahi|kam ho raha|kam ho rahi|jaldi khatam ho raha|jaldi khatam ho rahi|drain ho raha|drain ho rahi|khatam ho raha|khatam ho rahi|kha jata|kha jaata|khata)"

    private val ASKED = rx(
        LEAD + "why (is|does|do|are) $WHO( keep)? $EAT$MUCH (battery|power|charge)" + TAIL + "|" +
        LEAD + "(what|whats|what is) (is )?$EAT$MUCH (battery|power)" + TAIL + "|" +
        LEAD + "(is|are) $WHO $EAT$MUCH (battery|power)" + TAIL + "|" +
        LEAD + "why (is|does) (my |the )?(phone |phones )?battery (keep )?(draining|drain|going down|go down|dropping|drop|dying|die|running out|run out)( so fast| so quickly| fast| quickly)?" + TAIL + "|" +
        LEAD + "how much battery (do|does) $WHO (use|eat|take|drain)" + TAIL + "|" +
        LEAD + "(what|whats|what is|what all is|what all) (is )?(running|working|on) in (the )?background" + TAIL + "|" +
        LEAD + "(app |jarvis |phone |iraalgo )?battery (usage|use|drain|draining|report|consumption)( report)?" + TAIL + "|" +
        LEAD + "(app |jarvis |phone |iraalgo )?(itni |itna |zyada |jyada |bahut |kitni |kitna )?battery $KYUN( itni| itna| zyada| jyada| bahut)? $KHA( hai| he| ho| hain)?" + TAIL + "|" +
        LEAD + "$KYUN (app |jarvis |phone |iraalgo )?(itni |itna |zyada |jyada |bahut )?battery( itni| itna| zyada| jyada| bahut)? $KHA( hai| he| ho| hain)?" + TAIL + "|" +
        LEAD + "(app|jarvis|iraalgo|phone) (kitni|kitna|itni|itna|zyada|jyada|bahut) battery (kha|khaa|le|leta|leti|kha raha|kha rahi|khata|khati)( hai| he| raha hai| rahi hai)?" + TAIL + "|" +
        LEAD + "background (me|mein|main) (kya|kya kya) (chal raha|chal rahi|chalta) (hai|he)" + TAIL)

    fun asked(text: String): Boolean = ASKED.containsMatchIn(norm(text))
}

/**
 * Battery, round 2: the pace of Jarvis's words-only checks in the order watch's own lane. While the screen is off and
 * nothing is held or armed (no position, no open ticket, no strategy run, no ORB arm) - [quiet] - the slow group (the
 * goals, Boss's own rules, the paper tests, the day target, stale trades, the bots' switches: bookkeeping and remarks,
 * none bound to the minute) runs every [QUIET_SLOW_MS] instead of every round, and the news is read every
 * [QUIET_NEWS_MS] instead of every 5 minutes ([UNASKED_NEWS_MS] until Boss asks about the news that day, round 6). The safety words (a position eating the loss limit, MIS at 15:10, the
 * position health check, the expiry heads-up, the feed or relay stopped, overtrading, a bot misbehaving, the login nudge)
 * and everything time-bound (the candle expert's closes, the agenda's items, the gap, the day plan, Boss's usual
 * question, a sharp move) run every round as before; nothing here touches a stop, a target, an exit or an order. Pure.
 */
object WordsPace {
    const val QUIET_SLOW_MS = 3 * 60_000L
    const val QUIET_NEWS_MS = 10 * 60_000L

    /** Quiet: screen off, nothing held, and nothing known to be armed (unknown counts as armed: the pace stays). */
    fun quiet(screenOn: Boolean, held: Boolean, armed: Boolean?): Boolean = !screenOn && !held && armed == false

    /** Does the slow group run this round? Always when not [quiet]; never run yet, or a clock that went back, runs it too. */
    fun slowDue(quiet: Boolean, nowMs: Long, lastMs: Long): Boolean =
        !quiet || lastMs <= 0L || nowMs < lastMs || nowMs - lastMs >= QUIET_SLOW_MS

    /** Is the news looked at this round ([lastNewsMs] = the last read, null = never)? Its own 5-minute gate still applies. */
    fun newsDue(quiet: Boolean, nowMs: Long, lastNewsMs: Long?): Boolean =
        !quiet || lastNewsMs == null || nowMs < lastNewsMs || nowMs - lastNewsMs >= QUIET_NEWS_MS

    /**
     * Battery (round 6): while [quiet] (the screen off, nothing held, nothing armed) and Boss has not asked about the
     * news today, the feeds are read every [UNASKED_NEWS_MS]. A news question reads them afresh itself when they are
     * over 5 minutes old, so his answer is never older for this.
     */
    const val UNASKED_NEWS_MS = 20 * 60_000L

    /**
     * [newsDue], slower while [quiet] and the news not asked about today ([askedToday]). Anything held keeps the 5-minute
     * read (the news on a held index and the policy news it may bring are told at once): not quiet, so as before.
     */
    fun newsDueUnasked(quiet: Boolean, askedToday: Boolean, nowMs: Long, lastNewsMs: Long?): Boolean =
        if (!quiet || askedToday) newsDue(quiet, nowMs, lastNewsMs)
        else lastNewsMs == null || nowMs < lastNewsMs || nowMs - lastNewsMs >= UNASKED_NEWS_MS
}

/**
 * Battery (round 7): the price a trailing paper stop is moved on ([Protection.next] only ever tightens it). The stream's
 * tick when there is one (read now, no network); else the candle price the watch pass's own paper tick read for that very
 * symbol under [TICK_PRICE_MS] ago - the price the stop orders were just checked against; anything else (older, read by
 * someone other than the tick, a clock that went back, or a stream tick at the tick's read) a fresh read, as before
 * round 6. Never a price from an earlier pass: each pass replaces the tick's reads whole, and passes are 15 s apart. Pure.
 */
object StopPrice {
    enum class Source { STREAM, TICK_READ, FRESH }

    /** How old the tick's own candle read may be (the same pass's next step; the candle feed moves once a minute). */
    const val TICK_PRICE_MS = 2_000L

    /**
     * Which price the stop takes. [hasStream]: a stream tick for the symbol now. [tickReadAtMs]: when the tick read this
     * symbol's candles itself (null: it did not, or it was handed the stream's tick then).
     */
    fun source(hasStream: Boolean, tickReadAtMs: Long?, nowMs: Long): Source = when {
        hasStream -> Source.STREAM
        tickReadAtMs != null && nowMs - tickReadAtMs in 0L until TICK_PRICE_MS -> Source.TICK_READ
        else -> Source.FRESH
    }

    /**
     * Does the tick hand on its read of a symbol? Only when the quote the tick priced with ([tickQuote]) is the very candle
     * read the feed last kept for it ([lastCandleRead], the same object): a stream tick, or a candle read made by another
     * caller in between, is never handed on.
     */
    fun handedOn(tickQuote: Any?, lastCandleRead: Any?): Boolean = tickQuote != null && tickQuote === lastCandleRead
}

/**
 * Battery (round 7): the Options tab's chain priced in the background at app start reuses a chain this process read in
 * the last [MAX_AGE_MS] (the 15-minute Jarvis pass, a question, the tab itself) instead of pricing it again - about 50
 * downloads of a contract's whole day of candles without a Zerodha session. Only the same index, the same strike count and
 * the same mode (paper or Live); the tab still prices afresh when it is opened. Pure.
 */
object StartChain {
    const val MAX_AGE_MS = 5 * 60_000L

    fun reuse(readAtMs: Long?, nowMs: Long, sameMode: Boolean): Boolean =
        sameMode && readAtMs != null && nowMs - readAtMs in 0L until MAX_AGE_MS
}

/**
 * Battery (round 6): the pace of the day's chain record in Jarvis's 15-minute background pass (each read of an index's
 * chain without a Zerodha session is about 50 downloads of a contract's whole day of candles). With the nightly harvest
 * off, every pass as before. With it on - it stores the whole day's chain after the close - hourly, plus one read from
 * [LAST_READ] so the record holds the afternoon even if the harvest cannot run. A chain the pass read anyway (the OI
 * watch) is kept every pass at no cost. Market data only; nothing here touches the OI watch, a question, a stop or an
 * order. Pure.
 */
object ChainKeepPace {
    /** With the harvest on: at least this long between reads (a few minutes short of an hour: the passes drift). */
    const val HARVEST_ON_MINUTES = 55L
    val LAST_READ: java.time.LocalTime = java.time.LocalTime.of(15, 15)

    fun due(last: java.time.LocalDateTime?, now: java.time.LocalDateTime, harvestOn: Boolean): Boolean = when {
        !harvestOn || last == null -> true
        last.toLocalDate() != now.toLocalDate() || now.isBefore(last) -> true
        !now.toLocalTime().isBefore(LAST_READ) && last.toLocalTime().isBefore(LAST_READ) -> true
        else -> java.time.Duration.between(last, now).toMinutes() >= HARVEST_ON_MINUTES
    }
}

/**
 * Battery, round 3: the hourly night read of the news ([OVERNIGHT_HOURS] of headlines feed the 9 AM brief) is made only
 * when the next session opens within those hours - a weekday night as before, but not the whole weekend or a holiday,
 * when a Friday-evening read is cut from Monday's brief anyway. No next session found counts as due (the read as before).
 * Nothing here touches the market-hours news, a stop or an order. Pure.
 */
object NightNewsPace {
    const val OVERNIGHT_HOURS = 18L

    /** The next session's open (09:15) at or after [now], over at most three weeks of [tradingDay]; null when none is found. */
    fun nextOpen(now: java.time.LocalDateTime, tradingDay: (java.time.LocalDate) -> Boolean): java.time.LocalDateTime? {
        val open = java.time.LocalTime.of(9, 15)
        var d = now.toLocalDate()
        if (!now.toLocalTime().isBefore(open)) d = d.plusDays(1)
        repeat(21) {
            if (runCatching { tradingDay(d) }.getOrDefault(true)) return d.atTime(open)
            d = d.plusDays(1)
        }
        return null
    }

    fun due(now: java.time.LocalDateTime, nextOpen: java.time.LocalDateTime?): Boolean =
        nextOpen == null || !now.isBefore(nextOpen.minusHours(OVERNIGHT_HOURS))
}

/**
 * Battery, round 4: the pace of Jarvis's hourly study job (the night's news, the study after each close, Saturday's report
 * card). Hourly as before whenever any of its work can fall due within [SLOW_HOURS]; every [SLOW_HOURS] only through the
 * dead stretch of a weekend or a holiday (the study made twice since the last close, Saturday's report card given, the next
 * open more than [NightNewsPace.OVERNIGHT_HOURS] + [SLOW_HOURS] away). And the study itself is not made again when no
 * session closed since it last ran twice: the candles are the same. Weekday nights are exactly as before (a study after the
 * close and one twelve hours later). Unknown (no session found) reads as before. Nothing here touches the order watch, a
 * stop, a target or a safety alert. Pure.
 */
object StudyPace {
    const val SLOW_HOURS = 6L
    /** The study's second run after a close comes at least this long after it (the 12-hour gap the study keeps). */
    const val SECOND_AFTER_HOURS = 12L
    private val CLOSE = java.time.LocalTime.of(15, 30)

    /** The latest session close (15:30) at or before [now], over at most three weeks of [tradingDay]; null when none is found. */
    fun lastClose(now: java.time.LocalDateTime, tradingDay: (java.time.LocalDate) -> Boolean): java.time.LocalDateTime? {
        var d = now.toLocalDate()
        if (now.toLocalTime().isBefore(CLOSE)) d = d.minusDays(1)
        repeat(21) {
            if (runCatching { tradingDay(d) }.getOrDefault(true)) return d.atTime(CLOSE)
            d = d.minusDays(1)
        }
        return null
    }

    /** Is the study to be made (its own hour and 12-hour gates still apply)? Not when it already ran twice since the last close. */
    fun studyDue(lastStudy: java.time.LocalDateTime?, lastClose: java.time.LocalDateTime?): Boolean =
        lastStudy == null || lastClose == null || lastStudy.isBefore(lastClose.plusHours(SECOND_AFTER_HOURS))

    /** Hours between the study job's runs: 1 as before, or [SLOW_HOURS] while nothing of its work can fall due sooner. */
    fun everyHours(now: java.time.LocalDateTime, nextOpen: java.time.LocalDateTime?, lastStudy: java.time.LocalDateTime?,
                   lastClose: java.time.LocalDateTime?, reportCardDone: Boolean): Long = when {
        nextOpen == null -> 1L
        !now.isBefore(nextOpen.minusHours(NightNewsPace.OVERNIGHT_HOURS + SLOW_HOURS)) -> 1L
        studyDue(lastStudy, lastClose) -> 1L
        now.dayOfWeek == java.time.DayOfWeek.SATURDAY && !reportCardDone -> 1L
        else -> SLOW_HOURS
    }
}

/**
 * Battery (round 8): IraGoldAlgo's 5-minute alarm pass (24x5, an exact wake-up even while idle) read the gold feed -
 * today's 1-minute candles and the 1-hour history - with no arm armed and nothing held too, only to refresh a price no
 * one was watching (up to 288 wake-ups with a download a day). It now reads only when there is something to watch: an
 * arm armed, a trade held, the Trend arm's wait for the trend to turn down and up again after a profit-lock sale (a pass
 * may clear it, so it keeps its passes), or the saved books not read yet (fail open: an armed arm or a held buy is never
 * left unwatched). The alarm still sets the next one and still starts the always-on service when it is needed; the Gold
 * screen reads the price itself while open. Buys, exits and targets run as before whenever there is an arm or a trade. Pure.
 */
object GoldPass {
    fun due(booksRead: Boolean, anyArmed: Boolean, anyHeld: Boolean, trendWaitsFlip: Boolean): Boolean =
        !booksRead || anyArmed || anyHeld || trendWaitsFlip
}

/**
 * Battery (round 9): while Jarvis listens in market hours, [warm] kept a fresh trade check ready every 30 s - Nifty's
 * and BankNifty's bars, the day's P&L and each arm's record read again - so Solo's gate (and a trade idea's confidence)
 * finds one under a minute old instead of working one out inside its pass. When nothing can use it that fast - Solo off
 * and its setups not offered as ideas, Jarvis not taking paper trades alone, nothing held or waiting to fill (and, in
 * Live with a Zerodha session, always treated as possibly held) - it is kept ready every 2 minutes instead (8 checks in
 * 4 minutes become 2). A gate never uses an old check: [tradeCheckFast] works one out afresh whenever the one
 * kept is a minute old or more, so this only decides how often a check is worked out ahead; with anything that can
 * place an entry it stays every 30 s. Any doubt (a read failing) keeps the 30 s pace. Pure.
 */
object CheckWarmPace {
    /** How often the check is worked out ahead when nothing can enter or is held. */
    const val SLOW_MS = 120_000L

    /** Something can use a check within the minute: Solo can reach its gate, Jarvis acts alone on paper, or something is held. */
    fun fast(soloCanEnter: Boolean, actsAlone: Boolean, held: Boolean): Boolean = soloCanEnter || actsAlone || held

    /** Work the check out on this warm pass? [sinceLastMs]: since the last one was kept (null: none yet). */
    fun due(sinceLastMs: Long?, fast: Boolean): Boolean =
        fast || sinceLastMs == null || sinceLastMs < 0 || sinceLastMs >= SLOW_MS
}

/**
 * Battery (round 10): Jarvis's prices for words (every index's and gold's 1-minute candles, six downloads, and the news
 * when due) were read again by two keepers that did not look at each other: the listening loop every minute in market
 * hours, screen on or off, and the Ira page every minute while open - two full reads a minute with both. A keeper now
 * reads only when the last full read is [SHARED_MS] old or more (one read a minute, shared), and with the screen off
 * and nothing held or armed (the words lane's quiet, [WordsPace]) only when it is [QUIET_MS] old: the order watch's
 * feed check reads at 2 minutes anyway, an answer re-reads in the background past 2 minutes, and prices up to 3 minutes
 * old count as fresh in the answers. These prices are words only: stops, targets, the loss limit, Solo's gate, the arms
 * and every safety alert read their own. Any doubt (no read yet, a clock oddity) reads. Pure.
 */
object LiveReadPace {
    /** A full read this recent is shared by every keeper. */
    const val SHARED_MS = 50_000L
    /** Screen off and nothing held or armed: how old the last read may get before a keeper reads again. */
    const val QUIET_MS = 120_000L

    /** Read again now? [sinceLastMs]: since the last full read came in (null: none yet). */
    fun due(sinceLastMs: Long?, quiet: Boolean): Boolean =
        sinceLastMs == null || sinceLastMs < 0 || sinceLastMs >= if (quiet) QUIET_MS else SHARED_MS
}

/**
 * Battery (round 11): while Jarvis listens in market hours, the account answers asked most (status, P&L, positions,
 * orders, funds, risk, protections, strategies, alarms) were read afresh every 30 s to keep them ready - with a Zerodha
 * session that is three Zerodha requests (orders, the position book, funds) besides a fresh paper snapshot, about 750
 * reads a session. With the screen off and nothing held or armed (the words lane's quiet, [WordsPace]) they are now
 * kept ready about every 2 minutes ([QUIET_MS], the fourth 30 s pass): about 188 reads. Words only - an answer past its
 * 30 s freshness is read afresh when asked, as on a low battery; something changing (an order, a command) clears the
 * kept answers and the next pass reads at once. Stops, targets, the loss limit, the guard and every alert read their
 * own. Any doubt (nothing kept since a change, a clock oddity) reads. Pure.
 */
object AccountWarmPace {
    /**
     * Screen off and quiet: how old the kept answers may get before a pass reads them again. Just under 2 minutes, so
     * the fourth pass of a 30 s loop reads even when its timer runs a little early.
     */
    const val QUIET_MS = 115_000L

    /** Read the account ahead on this pass? [sinceLastMs]: since it was last read ahead (null: not since a change). */
    fun due(sinceLastMs: Long?, quiet: Boolean): Boolean =
        !quiet || sinceLastMs == null || sinceLastMs < 0 || sinceLastMs >= QUIET_MS
}

/**
 * Battery (round 17): outside market hours the listening loop (a pass every 30 s) read Boss's account ahead - the
 * Zerodha orders, position book and funds with a session, a paper snapshot (each held contract's day of candles) and
 * the trade check - every 5 minutes with the screen on and every 30 minutes with it off: about 35 reads through a
 * weeknight and 96 through a weekend day, screen off, with nothing in the account able to move. Now, market shut and
 * the screen off: no read ahead (a question then reads afresh, as on a low battery). Screen on: every 5 minutes as
 * before, and at once on the first pass after the screen comes on - so the Ira page's globe and the first question
 * after unlocking find figures at least as fresh as before. Market hours: every pass, as before ([AccountWarmPace]
 * then sets the pace). Words only - stops, targets, the loss limit, the guard, the order watch and every alert read
 * their own. Unknown screen state counts as on. Pure.
 */
object OffHoursWarmPace {
    /** Market shut, screen on: one read ahead every this many 30 s passes (5 minutes), as before. */
    const val SCREEN_ON_EVERY = 10

    /**
     * Read the account ahead on listening pass [pass]? [open]: market hours on a trading day. [screenOn]: now.
     * [wasOn]: the screen on the last pass (null: no pass has looked yet).
     */
    fun due(open: Boolean, screenOn: Boolean, pass: Int, wasOn: Boolean?): Boolean = when {
        open -> true
        !screenOn -> false
        else -> pass % SCREEN_ON_EVERY == 0 || wasOn == false
    }
}

/**
 * Battery (round 12): while the "Open" home-screen widget shows Zerodha orders still working, the live watch read
 * Zerodha's order book once a pass only to redraw it - once a minute, and every 15 s while something is held: up to
 * 4 requests a minute (about 1,500 a session), each waking the radio, screen on or off. The widget is display only and
 * cannot be seen with the screen off, so then the book is read for it only when its orders are [SCREEN_OFF_MS] old
 * (the fifth 60 s pass, the twentieth 15 s one): about 75 requests a session. Screen on: every pass, as before. Any
 * read of the order book (an order placed, the app open) counts as fresh. Never an order path: stops, targets, the
 * guard, the order watch and every alert read their own. Any doubt (no read yet, a clock oddity) reads. Pure.
 */
object WidgetOrdersPace {
    /** Screen off: how old the widget's orders may get before the watch reads the book for it (just under 5 minutes). */
    const val SCREEN_OFF_MS = 295_000L

    /** Read the order book for the widget on this pass? [sinceLastMs]: since the book was last read (null: not yet). */
    fun due(sinceLastMs: Long?, screenOn: Boolean): Boolean =
        screenOn || sinceLastMs == null || sinceLastMs < 0 || sinceLastMs >= SCREEN_OFF_MS

    /**
     * Review (battery 12): the screen just came on ([wasOn] false on the last pass, [screenOn] now; [wasOn] null when
     * no pass has looked yet). The first pass after it reads the order book for the widget whatever the widget still
     * shows, so orders that filled, were cancelled or were placed while the screen was off are not left stale.
     */
    fun woke(wasOn: Boolean?, screenOn: Boolean): Boolean = wasOn == false && screenOn
}

/**
 * Battery (round 13): the echo canceller on Jarvis's own microphone capture only while he talks. With a taught voice,
 * every listening turn - all day and all night, screen off, market shut - hears through our own capture
 * ([CutIn.ownCapture]), and the phone's echo canceller was switched on for the whole of each turn, working through
 * every 10 ms of audio with nothing playing to cancel. Now it is made with the capture but switched on only while
 * Jarvis speaks ([on]) and off again when he stops; a turn opened in silence starts with it off. And a cut-in turn
 * opened on the call microphone (no taught voice: the phone's full call processing, echo cancelling included) was
 * kept open after he stopped talking until the recognizer ended it (up to 25 s, 45 s when stuck); it now gives way at
 * once to a plain turn on the recognizer's own microphone ([plainAfter]) - only when nothing was heard in it, so Boss
 * speaking into it is never cut. "Jarvis" is listened for throughout: only the processing changes. Pure.
 */
object CaptureEcho {
    /** The echo canceller on our own capture: on only while Jarvis is [speaking]. */
    fun on(speaking: Boolean): Boolean = speaking

    /**
     * Jarvis just stopped talking: end the open turn and start a plain one? Only a [listening] turn opened while he
     * talked ([inSpeech]) on the call microphone ([callSource]), in which no speech began ([speechBegan]) and no words
     * were read ([partial]) - so nothing Boss may be saying is lost.
     */
    fun plainAfter(listening: Boolean, inSpeech: Boolean, callSource: Boolean, speechBegan: Boolean, partial: Boolean): Boolean =
        listening && inSpeech && callSource && !speechBegan && !partial
}

/**
 * Battery (round 16): the order watch's index levels in Live mode. Each pass (once a minute, screen on or off) read
 * every index it has a reader for - the widget's NIFTY and BANKNIFTY, an open ticket's underlying, an alarm on an
 * index - from Zerodha as a quote AND the day's 1-minute candles so far, the candles only for the day's high, low and
 * sparkline. The watch reads none of those: the ticket's cushion, the alarms and the widget use the last price and the
 * change from the open, both in the quote. So the watch now asks for the quote alone (not [wanted]): with the widget
 * placed, 2 Kite requests a minute instead of 4 (about 750 fewer historical-candle reads a session). Screens that draw
 * the spark still read the candles, once a minute per index as before. Every check reads the same quote as before. Pure.
 */
object IndexSpark {
    /**
     * Read the day's candles for an index quote? Only when the caller [wanted] the spark and none is kept for this
     * [minute] ([keptMinute]: the minute the kept one was read, null: none; [keptEmpty]: that read had no candles).
     */
    fun candles(wanted: Boolean, keptMinute: Int?, minute: Int, keptEmpty: Boolean): Boolean =
        wanted && (keptMinute != minute || keptEmpty)

    /** Kite requests one index quote costs: the quote, and the candles when [candles] says so. */
    fun requests(wanted: Boolean, keptMinute: Int?, minute: Int, keptEmpty: Boolean): Int =
        1 + if (candles(wanted, keptMinute, minute, keptEmpty)) 1 else 0
}
