package com.optionslab.engine.signals

import com.optionslab.engine.IST
import com.optionslab.engine.NseHours
import com.optionslab.engine.Right
import com.optionslab.engine.orb.MiniJson
import com.optionslab.engine.signals.NtfyParser.Expiry
import com.optionslab.engine.signals.NtfyParser.StrikeRule
import com.optionslab.engine.signals.NtfyParser.Trig
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * ntfy alerts (25 Oct 2026, Boss: "add a new feature which will read message from ntfy and every message should be read only once,
 * and before buying or selling, we should read the message and buy the option of the index on the basis of the message"): the
 * rules, pure. The app's arm (NtfyArm) and reader (NtfyReader) carry them out.
 *
 * SIGNAL SOURCE ([SIGNAL]): an external message posted to Boss's ntfy topic, traded on the index option by the strike rule. It is
 * NOT a researched or proven strategy ([NOTE]); paper by default, live only through the arms' live door with its own switch.
 *
 * Per message ([NtfyParser] reads it): read once ([ReadOnce]); a message older than [Settings.staleMin] minutes or from an
 * earlier day is "stale, not traded" ([stale]); outside the market (a holiday, a weekend, before 09:15, after the square-off)
 * not traded ([marketRefusal]). The entry: at the market, or when the premium trades within 2% of the message's "@" level (at or
 * above an "above" level), inside the stale window ([phase], [triggered]). The stop is mandatory: the message's (below the
 * fill), else [Settings.ourStop] under the fill ("our stop", [stop]); the target the message's (above the fill), else
 * [DEFAULT_TARGET] over it ([target]); square-off 15:15, or 15:05 on the option's expiry day ([squareOff]); MIS. At most
 * [Settings.maxPerDay] trades a day and a pause for the day at the paper loss cap ([refusal]); the lots the message asks for,
 * never more than [Settings.lots] ([lotsFor]).
 */
object NtfyRules {
    const val LABEL = "ntfy alerts"
    /** What Jarvis and every screen say of it. */
    const val NOTE = "an external message - not a researched or proven strategy"
    /** The owner tag on its paper orders. */
    const val OWNER = "ntfy alerts (paper)"
    /** CLAUDE.md: every rule states its signal source. */
    const val SIGNAL = "an external ntfy message (not researched or proven), traded on the index option chosen by the strike rule"

    val SQ: LocalTime = LocalTime.of(15, 15)
    val OPEN: LocalTime = LocalTime.of(9, 15)
    /** Our stop when the message gives none: 20% under the fill. */
    const val OUR_STOP = 0.20
    /** Our target when the message gives none: 40% over the fill. */
    const val DEFAULT_TARGET = 0.40
    /** "Near X": within 2% of X. */
    const val ZONE = 0.02
    const val DEFAULT_SERVER = "https://ntfy.sh"
    /** Message ids remembered (a busy topic posts a few dozen a day). */
    const val KEEP_IDS = 5_000

    /** How an open trade exits (kept for every trade the book already holds): all at the target, half then the stop to cost, or the last target. */
    enum class Exit(val key: String, val words: String) {
        T1_ALL("t1", "book all at the target"), HALF_COST("half", "half at T1, then the stop to cost"), HOLD_LAST("last", "hold to the last target");
        companion object { fun of(k: String?): Exit = entries.firstOrNull { it.key == k } ?: T1_ALL }
    }

    /**
     * Boss's settings: at most [lots] lots a trade; a message older than [staleMin] minutes is stale; [maxPerDay] trades a day; the
     * day pauses at a [lossCap] paper loss; [strikeRule] when the message names no strike; [ourStop] the stop when none is given.
     */
    data class Settings(val lots: Int = 1, val staleMin: Int = 5, val maxPerDay: Int = 3, val lossCap: Double = 3_000.0,
                        val strikeRule: StrikeRule = StrikeRule.ATM, val ourStop: Double = OUR_STOP, val exit: Exit = Exit.T1_ALL,
                        /** 25 Oct (Boss: "add normal stocks"): NSE F&O stock signals' options bought too (on by Boss's ask). */
                        val stocks: Boolean = true) {
        fun clean(): Settings = copy(lots = lots.coerceIn(1, 5), staleMin = staleMin.coerceIn(1, 60), maxPerDay = maxPerDay.coerceIn(1, 10),
            lossCap = lossCap.coerceIn(500.0, 50_000.0))
    }

    enum class Phase { OPEN, LATE }

    /** The square-off of a day: 15:15, or 15:05 when the option expires that day. */
    fun squareOff(expiryDay: Boolean): LocalTime = if (expiryDay) LocalTime.of(NseHours.EXPIRY_SQUARE_OFF / 60, NseHours.EXPIRY_SQUARE_OFF % 60) else SQ

    /** Where a message sent at [msgAt] stands at [now]: in its entry window, or past it (or past the square-off, or another day). */
    fun phase(msgAt: LocalDateTime, now: LocalDateTime, s: Settings, expiryDay: Boolean): Phase {
        val from = maxOf(msgAt, msgAt.toLocalDate().atTime(OPEN))
        val end = minOf(from.plusMinutes(s.staleMin.toLong()), msgAt.toLocalDate().atTime(squareOff(expiryDay)))
        return if (!now.toLocalDate().isEqual(msgAt.toLocalDate()) || !now.isBefore(end)) Phase.LATE else Phase.OPEN
    }

    /**
     * The premium has traded at the entry: [ltp] now and [high] / [low] of the minutes since the message (null: unknown). "Above":
     * at or above it; "near": within [ZONE] of it; at the market: any price.
     */
    fun triggered(trig: Trig, entry: Double?, ltp: Double?, high: Double?, low: Double?): Boolean {
        if (trig == Trig.MARKET || entry == null) return ltp != null && ltp > 0
        if (trig == Trig.UPTO) return ltp != null && ltp > 0 && ltp <= entry * (1 + NtfyParser.UPTO)
        return when (trig) {
            Trig.ABOVE -> (ltp != null && ltp >= entry) || (high != null && high >= entry)
            else -> {
                val lo = entry * (1 - ZONE); val hi = entry * (1 + ZONE)
                (ltp != null && ltp in lo..hi) || (high != null && low != null && high >= lo && low <= hi)
            }
        }
    }

    /** [x] down to the 0.05 tick. */
    fun tickDown(x: Double): Double = floor(x / 0.05 + 1e-9) * 0.05

    fun round2(x: Double) = Math.round(x * 100) / 100.0

    /**
     * The stop for a fill at [fill]: the message's [given] level when below the fill, else its [pct] under the fill, else
     * [Settings.ourStop] under it ("our stop": true). Never at or above the fill, never below one tick.
     */
    fun stop(given: Double?, pct: Double?, fill: Double, s: Settings): Pair<Double, Boolean> {
        given?.takeIf { it > 0 && it < fill }?.let { return it to false }
        pct?.takeIf { it > 0 && it < 100 }?.let { return round2(tickDown(fill * (1 - it / 100))).coerceAtLeast(0.05) to false }
        return round2(tickDown(fill * (1 - s.ourStop))).coerceAtLeast(0.05) to true
    }

    /** The target for a fill at [fill]: the message's [given] when above the fill, else [DEFAULT_TARGET] over it. */
    fun target(given: Double?, fill: Double): Double = given?.takeIf { it > fill } ?: round2(tickDown(fill * (1 + DEFAULT_TARGET)) + 0.05)

    /** The lots sold at T1 under [Exit.HALF_COST] (0 for 1 lot: then only the stop moves to cost). */
    fun halfLots(lots: Int): Int = lots / 2

    /** The lots of a trade: what the message asks (1 when it names none), never more than [Settings.lots]. */
    fun lotsFor(asked: Int?, s: Settings): Int = (asked ?: 1).coerceIn(1, s.lots.coerceAtLeast(1))

    /** Why no new trade may be taken today ([tradesToday] taken, [netToday] the day's closed paper rupees), or null. */
    fun refusal(tradesToday: Int, netToday: Double, s: Settings): String? = when {
        netToday <= -s.lossCap -> "paused for today: the paper loss reached the ₹%,.0f cap".format(Locale.ENGLISH, s.lossCap)
        tradesToday >= s.maxPerDay -> "${s.maxPerDay} trades today already (the day's limit)"
        else -> null
    }

    /** Why a message sent at [msgAt] is stale at [now] (from an earlier day, or more than [staleMin] minutes old), or null. */
    fun stale(msgAt: LocalDateTime, now: LocalDateTime, staleMin: Int): String? = when {
        msgAt.toLocalDate().isBefore(now.toLocalDate()) -> "stale, not traded: sent on an earlier day (${msgAt.toLocalDate()})"
        msgAt.isAfter(now.plusMinutes(2)) -> "not traded: its time is in the future (the phone's clock or the server's is wrong)"
        msgAt.plusMinutes(staleMin.toLong()).isBefore(now) ->
            "stale, not traded: ${java.time.Duration.between(msgAt, now).toMinutes()} minutes old (the limit is $staleMin)"
        else -> null
    }

    /** Why nothing new may be bought at [now] on a day that is [tradingDay] (expiring today: [expiryDay]), or null. */
    fun marketRefusal(now: LocalDateTime, tradingDay: Boolean, expiryDay: Boolean): String? = when {
        !tradingDay -> "market closed today (a weekend or a market holiday)"
        now.toLocalTime().isBefore(OPEN) -> "before the market opens (09:15)"
        !now.toLocalTime().isBefore(squareOff(expiryDay)) -> "after the square-off (${squareOff(expiryDay)})"
        else -> null
    }

    /**
     * The strike for [right] at [spot] among the listed [strikes] by [rule]: the nearest one (ATM; the lower on a tie), or the next
     * one in the money (a CE's below, a PE's above) or out of it. Null when none is listed or the spot is unknown.
     */
    fun strikeFor(strikes: Collection<Double>, spot: Double?, right: Right, rule: StrikeRule): Double? {
        val sorted = strikes.filter { it > 0 }.distinct().sorted()
        if (sorted.isEmpty() || spot == null || spot <= 0) return null
        val atm = sorted.indices.minWith(compareBy<Int>({ abs(sorted[it] - spot) }, { sorted[it] }))
        val step = when (rule) { StrikeRule.ATM -> 0; StrikeRule.ITM1 -> if (right == Right.PE) 1 else -1; StrikeRule.OTM1 -> if (right == Right.PE) -1 else 1 }
        return sorted.getOrNull(atm + step)
    }

    /** The expiry [e] asks for among the [listed] ones on [today]: the nearest not past, the one after it, or that date if listed. */
    fun expiryFor(listed: Collection<LocalDate>, today: LocalDate, e: Expiry): LocalDate? {
        val live = listed.filter { !it.isBefore(today) }.distinct().sorted()
        return when (e) {
            Expiry.Nearest -> live.firstOrNull()
            Expiry.Next -> live.getOrNull(1)
            is Expiry.On -> e.date.takeIf { it in live }
        }
    }

    /** The text after Boss's [prefix] (ignoring case and spaces before it); the whole text when no prefix is set; null when it lacks it. */
    fun afterPrefix(text: String, prefix: String): String? {
        val p = prefix.trim()
        if (p.isEmpty()) return text
        val t = text.trimStart()
        return if (t.length >= p.length && t.regionMatches(0, p, 0, p.length, ignoreCase = true)) t.substring(p.length) else null
    }

    // ---- reading once ---------------------------------------------------------------------------------------------------

    /**
     * The ntfy message ids already read, oldest first, and the newest id read ([last]): kept in the app's encrypted store and
     * written BEFORE a message is acted on. A seen id is never read again: after a restart, a reconnect, a duplicate delivery,
     * a poll that overlaps the stream, or an app update.
     */
    class ReadOnce(ids: Collection<String> = emptyList(), var last: String? = null, private val keep: Int = KEEP_IDS) {
        private val seen = LinkedHashSet(ids)
        val ids: List<String> get() = seen.toList()
        fun seen(id: String): Boolean = id in seen
        /** [id] marked read: true the first time, false when it was already read. */
        fun mark(id: String): Boolean {
            if (id.isBlank() || !seen.add(id)) return false
            last = id
            while (seen.size > keep) seen.remove(seen.first())
            return true
        }
    }

    // ---- the ntfy wire --------------------------------------------------------------------------------------------------

    /** One line of ntfy's JSON stream: [event] "message", "open", "keepalive" or "poll_request"; [time] in epoch seconds. */
    data class Event(val id: String, val time: Long, val event: String, val message: String?, val title: String?) {
        /** Only a "message" event is a message (open and keepalive events are the connection's own). */
        val isMessage: Boolean get() = event == "message" && id.isNotBlank()
        fun at(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(time), IST)
    }

    /** One stream or poll line read, or null when it is not ntfy's JSON. */
    fun event(line: String): Event? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val o = runCatching { MiniJson.parse(t) }.getOrNull() as? Map<*, *> ?: return null
        val id = o["id"] as? String ?: return null
        val time = (o["time"] as? Number)?.toLong() ?: return null
        val ev = o["event"] as? String ?: return null
        return Event(id, time, ev, o["message"] as? String, o["title"] as? String)
    }

    /** ntfy's topic names: 1-64 letters, digits, "-" or "_". */
    fun topicOk(topic: String): Boolean = Regex("[A-Za-z0-9_-]{1,64}").matches(topic)

    /** The server, cleaned ("https://ntfy.sh"), or null when it is not an https address with a host (the app allows https only). */
    fun server(raw: String): String? {
        val s = raw.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER }
        val m = Regex("https://([A-Za-z0-9.-]+)(:\\d{1,5})?(/[A-Za-z0-9._~/-]*)?").matchEntire(s) ?: return null
        return if (m.groupValues[1].contains('.') || m.groupValues[1] == "localhost") s else null
    }

    /** The streaming address: every message after [since] (an id, or epoch seconds), then each new one as it comes. */
    fun streamUrl(server: String, topic: String, since: String): String = "$server/$topic/json?since=$since"

    /** The catch-up address: the messages after [since], and the connection closes. */
    fun pollUrl(server: String, topic: String, since: String): String = "$server/$topic/json?poll=1&since=$since"

    /** Where to read from: after the last id read, else [staleMin] minutes back from [nowEpoch] (older ones would be stale anyway). */
    fun since(last: String?, nowEpoch: Long, staleMin: Int): String = last ?: (nowEpoch - staleMin * 60L).toString()

    /** The wait before reconnect [attempt] (0 the first): 1 s, 2 s, 4 s ... at most 60 s. */
    fun backoffMs(attempt: Int): Long = (1_000L shl attempt.coerceIn(0, 6)).coerceAtMost(60_000L)

    /**
     * The index crossed a signal's own level ([idxStop] / [idxTarget], the index's): "index stop" or "index target", else null.
     * A CE's stop is below and target above the index; a PE's the other way round.
     */
    fun indexExit(right: Right, spot: Double?, idxStop: Double?, idxTarget: Double?): String? {
        val x = spot?.takeIf { it > 0 } ?: return null
        val up = right != Right.PE
        return when {
            idxStop != null && (if (up) x <= idxStop else x >= idxStop) -> "index stop"
            idxTarget != null && (if (up) x >= idxTarget else x <= idxTarget) -> "index target"
            else -> null
        }
    }

    /** A topic anyone could guess (short, or plain letters): the screen warns and recommends a long random one or a token. */
    fun weakTopic(topic: String): Boolean = topic.length < 20 || topic.all { it.isLetter() }

    /** What the diagnostics say of the topic: never the topic itself. */
    fun masked(topic: String?): String = if (topic.isNullOrBlank()) "no topic set" else "topic set (****)"
}
