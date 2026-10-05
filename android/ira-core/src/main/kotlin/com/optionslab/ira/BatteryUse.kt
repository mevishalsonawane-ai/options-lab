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
    )

    /** The diagnostics' line: what runs in the background now. */
    fun line(s: Snapshot): String = "Battery: " + listOf(
        "listening " + (if (!s.listening) "off" else (if (s.resting) "resting" else "on") + " (battery saver for listening ${if (s.listenSaver) "on" else "off"})"),
        "order watch " + (if (!s.watch) "not running" else s.watchStepSec?.let { "every $it s" } ?: "running") + when (s.wordsQuiet.takeIf { s.watch }) {
            null -> ""
            true -> " (Jarvis's words quiet: slow checks every ${WordsPace.QUIET_SLOW_MS / 60_000} min, news every ${WordsPace.QUIET_NEWS_MS / 60_000} min)"
            false -> " (Jarvis's words every round)"
        },
        "live stream ${s.stream}" + if (s.stream != "OFF") " (${s.streamTokens} instruments)" else "",
        "AI model " + if (s.modelLoaded) "loaded" else "not loaded",
        "phone " + (s.batteryPercent?.let { "$it%" } ?: "battery unknown") + if (s.charging) ", charging" else ", not charging",
    ).joinToString(" · ")

    /** The spoken answer, the biggest background cost first. */
    fun answer(s: Snapshot): String {
        val parts = ArrayList<String>()
        if (s.listening) parts += "Listening for \"Jarvis\" - the microphone and the phone's speech recognizer, all the time; that is usually the biggest. " +
            if (s.listenSaver) "Battery saver for listening is on" + (if (s.resting) ", and I'm resting between turns now." else ": I rest between turns when the screen is off, the market is shut and the room is quiet.")
            else "Battery saver for listening is off: switch it on in the Jarvis page and I listen in short rests when the screen is off, the market is shut and the room is quiet."
        if (s.stream != "OFF") parts += "Zerodha's live price stream (${s.streamTokens} instrument${if (s.streamTokens == 1) "" else "s"}): it stops by itself when nothing needs live prices for a few minutes."
        if (s.watch) parts += "The order watch" + (s.watchStepSec?.let { ", every $it seconds" } ?: "") +
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
        LEAD + "(app |jarvis |phone |iraalgo )?battery (usage|use|drain|draining|report|consumption)" + TAIL + "|" +
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
 * [QUIET_NEWS_MS] instead of every 5 minutes. The safety words (a position eating the loss limit, MIS at 15:10, the
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
