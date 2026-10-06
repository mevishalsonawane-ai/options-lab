package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Morning cues from the market recorder (Boss, 06 Oct 2026): what the two feeds it reads outside market hours
 * ([RecorderFeeds]) say about the day ahead, in plain words - for the 09:00 morning check and when asked.
 *
 *  - GIFT Nifty's pre-open gap: the last GIFT Nifty reading against Nifty's previous close - "GIFT Nifty points to a
 *    gap-up of ~60 pts (+0.26%)", with the reading's time. "GIFT Nifty kya bol raha hai", "what's GIFT Nifty saying".
 *  - FII positioning from NSE's participant-wise open interest: the FIIs' index-futures long share and its change
 *    against the previous trade date on the phone, and their index calls and puts long against short - "FIIs are net
 *    short index futures, 8% long; more short than the session before". "What are FIIs doing", "FII position".
 *
 * Read from what the recorder already keeps (its last GIFT reading, its "P" rows); nothing is fetched here, and the
 * app's own reads are unchanged. Facts and their dates only - a missing reading or file is said as missing, never
 * guessed; never a trade suggestion; nothing acts. Pure.
 */
object MorningCues {
    enum class Ask { GIFT, FII, BOTH }

    /** A session's close: [day] and the last 1-minute candle's close. */
    data class Close(val day: LocalDate, val close: Double)

    /** The FIIs' index positions on [date] (contracts): futures long / short, calls long / short, puts long / short. */
    data class Fii(val date: LocalDate, val futLong: Long, val futShort: Long, val callLong: Long, val callShort: Long,
        val putLong: Long, val putShort: Long) {
        /** The futures' long share, % of long + short (null when there are none). */
        val longPct: Double? get() = (futLong + futShort).takeIf { it > 0 }?.let { futLong * 100.0 / it }
    }

    /** A GIFT Nifty reading older than this (hours) is said as old, and left out of the morning check. */
    const val GIFT_OLD_HOURS = 18L
    /** A participant file older than this (days) is left out of the morning check (the answer still says it, dated). */
    const val FII_OLD_DAYS = 5L
    /** A gap smaller than this % is "roughly flat". */
    const val FLAT_PCT = 0.1
    /** The long share moving less than this many points is "about the same". */
    const val SAME_PTS = 1.0

    const val GIFT_NOTE = "GIFT Nifty is a future, so it often trades a little above the index - read the gap as rough, not a forecast of the day."
    const val NO_GIFT = "I have no GIFT Nifty reading on the phone yet, Boss - the market recorder reads it from 06:30 on trading days."
    const val NO_FII = "I have no NSE participant OI file on the phone yet, Boss - NSE publishes it after 18:30 and the market recorder reads it then."

    private val CLOSE: LocalTime = LocalTime.of(15, 30)

    // ---- the question ---------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

    private val GIFT = Regex(" (gift|sgx|gif) ?(nifty|nifti|niftee|nifity) | gift city ")
    private val GIFT_NOT = Regex(" (mean|means|meaning|define|explain|what is a|record|history|how often|chart|alert|alarm|remind|my|i|me|mera) ")
    private val FII = Regex(" (fii|fiis|fpi|fpis|foreign investors|foreign institutions|foreign institutional investors|foreign funds) ")
    private val POSITION = Regex(" (position|positions|positioning|long|longs|short|shorts|futures|future|oi|open interest|participant|ratio|doing|" +
        "kar rahe|kar rahi|kar raha|stance|bets|lean|leaning|bias) ")
    /** The cash market's flows (buying and selling in crore, FII/DII figures), news, a definition, Boss's own: not this. */
    private val FII_NOT = Regex(" (buy|bought|buying|sell|sold|selling|becha|bech|beche|kharida|kharide|kharid|cash|crore|crores|flow|flows|news|data|" +
        "dii|diis|yesterday|kal|week|month|my|i|me|mera|meri|alert|remind|mean|means|meaning|define|explain|what is a|what is an|who are|kaun) ")
    private val CUES = Regex(" (morning cues|pre open cues|preopen cues|pre market cues|premarket cues|opening cues|subah ke cues) ")

    /** What was asked: GIFT Nifty, the FIIs' positioning, or the morning's cues (both); null otherwise. */
    fun asked(text: String): Ask? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Ask?>(64)

    private fun askedFresh(text: String): Ask? {
        val t = norm(text)
        val gift = GIFT.containsMatchIn(t) && !GIFT_NOT.containsMatchIn(t)
        val fii = FII.containsMatchIn(t) && POSITION.containsMatchIn(t) && !FII_NOT.containsMatchIn(t)
        return when {
            gift && fii || CUES.containsMatchIn(t) && !GIFT_NOT.containsMatchIn(t) -> Ask.BOTH
            gift -> Ask.GIFT
            fii -> Ask.FII
            else -> null
        }
    }

    // ---- words ----------------------------------------------------------------------------------------------------

    private fun day(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun px(x: Double) = "%,.1f".format(Locale.ENGLISH, x)
    private fun n(x: Long) = "%,d".format(Locale.ENGLISH, x)
    private fun signed(x: Double, places: Int) = (if (x >= 0) "+" else "-") + "%.${places}f".format(Locale.ENGLISH, abs(x))

    /** "08:45 today", "23:30 yesterday", "22:02 on 5 Oct". */
    private fun whenSaid(t: LocalDateTime, now: LocalDateTime): String = hm(t.toLocalTime()) + when (t.toLocalDate()) {
        now.toLocalDate() -> " today"
        now.toLocalDate().minusDays(1) -> " yesterday"
        else -> " on ${day(t.toLocalDate())}"
    }

    // ---- GIFT Nifty -----------------------------------------------------------------------------------------------

    /** Nifty's last close before [at], from its 1-minute [bars]: a session of an earlier day, or [at]'s own from 15:30. */
    fun prevClose(bars: List<Candle>, at: LocalDateTime): Close? =
        MarketStory.sessions(bars).lastOrNull { s ->
            s.bars.isNotEmpty() && (s.day.isBefore(at.toLocalDate()) || s.day == at.toLocalDate() && !at.toLocalTime().isBefore(CLOSE))
        }?.let { Close(it.day, it.close) }

    /**
     * GIFT Nifty in sentences, the first the brief's: the last reading [g] (taken at [readAt]; the exchange's own time
     * when it gave one) against Nifty's [prev] close, at [now]. The gap first ("points to a gap-up of ~60 pts (+0.26%)"),
     * then the figures it rests on and how old the reading is.
     */
    fun gift(g: RecorderFeeds.Gift?, readAt: LocalDateTime?, prev: Close?, now: LocalDateTime): List<String> {
        if (g == null || readAt == null) return listOf(NO_GIFT)
        val at = g.at ?: readAt
        val old = Duration.between(at, now).toHours() >= GIFT_OLD_HOURS
        val oldNote = if (old) " That reading is old - there is no newer one on the phone." else ""
        if (prev == null || prev.close <= 0)
            return listOf("GIFT Nifty was ${px(g.last)} at ${whenSaid(at, now)}, but I don't have Nifty's previous close on the phone to set it against.$oldNote")
        val gap = g.last - prev.close
        val pct = gap / prev.close * 100
        val pts = abs(gap).roundToLong()
        val lead = if (abs(pct) < FLAT_PCT) "GIFT Nifty points to a roughly flat open (${signed(gap, 0)} pts, ${signed(pct, 2)}%)"
            else "GIFT Nifty points to a gap-${if (gap > 0) "up" else "down"} of ~$pts pts (${signed(pct, 2)}%)"
        return listOf("$lead, read at ${whenSaid(at, now)}.",
            "That is ${px(g.last)} against Nifty's ${px(prev.close)} close on ${day(prev.day)}.$oldNote", GIFT_NOTE)
    }

    /** The morning check's GIFT line, or null when there is no reading, no close or only an old reading. */
    fun giftBrief(g: RecorderFeeds.Gift?, readAt: LocalDateTime?, prev: Close?, now: LocalDateTime): String? {
        if (g == null || readAt == null || prev == null) return null
        if (Duration.between(g.at ?: readAt, now).toHours() >= GIFT_OLD_HOURS) return null
        return gift(g, readAt, prev, now).first().removeSuffix(".")
    }

    // ---- FII positioning ------------------------------------------------------------------------------------------

    /** The FIIs' row of NSE's participant-wise OI [p], or null when it has none. */
    fun fii(p: RecorderFeeds.Participants?): Fii? {
        val r = p?.rows?.firstOrNull { it.client == "FII" } ?: return null
        return Fii(p.date, r.of("Future Index Long"), r.of("Future Index Short"), r.of("Option Index Call Long"), r.of("Option Index Call Short"),
            r.of("Option Index Put Long"), r.of("Option Index Put Short"))
    }

    /**
     * The participant-wise OI files in the recorder's "P" rows ([lines]: any records, other kinds skipped; the volume
     * file's rows too), one per trade date, newest first. A row that does not read is skipped, never guessed.
     */
    fun participants(lines: List<String>): List<RecorderFeeds.Participants> {
        val by = LinkedHashMap<LocalDate, MutableMap<String, RecorderFeeds.Participant>>()
        for (l in lines) {
            if (!l.startsWith("P,")) continue
            val f = l.split(',')
            if (f.size < 5 + RecorderFeeds.COLUMNS.size || f[3] != "oi") continue
            val d = runCatching { LocalDate.parse(f[1]) }.getOrNull() ?: continue
            val nums = f.subList(5, 5 + RecorderFeeds.COLUMNS.size).map { it.toLongOrNull() }
            if (nums.any { it == null }) continue
            by.getOrPut(d) { LinkedHashMap() }[f[4]] = RecorderFeeds.Participant(f[4], nums.map { it!! })
        }
        return by.entries.sortedByDescending { it.key }.map { (d, rows) -> RecorderFeeds.Participants(d, rows.values.toList()) }
    }

    /**
     * The FIIs' positioning in sentences, the first the brief's: [cur] (the newest trade date on the phone) against
     * [prev] (the one before it, when kept). Futures first, then the index options' calls and puts.
     */
    fun fiiSay(cur: Fii?, prev: Fii?): List<String> {
        if (cur == null) return listOf(NO_FII)
        val lp = cur.longPct ?: return listOf("NSE's participant OI for ${day(cur.date)} shows no FII index futures, Boss.")
        val short = lp < 50
        val side = if (short) "net short" else "net long"
        val pct = "%.0f".format(Locale.ENGLISH, lp)
        val change = prev?.takeIf { it.date.isBefore(cur.date) }?.longPct?.let { pp ->
            val d = lp - pp
            val than = "the session before (${day(prev.date)}: ${"%.0f".format(Locale.ENGLISH, pp)}% long)"
            when {
                abs(d) < SAME_PTS -> "about the same as $than"
                short && d < 0 -> "more short than $than"
                short -> "less short than $than"
                d > 0 -> "more long than $than"
                else -> "less long than $than"
            }
        }
        val first = "FIIs are $side index futures, $pct% long" + (change?.let { "; $it" } ?: "") + "."
        val detail = "That is ${n(cur.futLong)} long against ${n(cur.futShort)} short contracts in NSE's participant OI for ${day(cur.date)}" +
            (if (change == null) " (no earlier day on the phone to compare)." else ".")
        val calls = cur.callLong - cur.callShort
        val puts = cur.putLong - cur.putShort
        val callWord = if (calls >= 0) "net long ${n(calls)} calls" else "net short ${n(-calls)} calls"
        val putWord = if (puts >= 0) "net long ${n(puts)} puts" else "net short ${n(-puts)} puts"
        // Long calls or short puts lean up; short calls or long puts lean down.
        val callUp = calls >= 0; val putUp = puts < 0
        val lean = when {
            callUp && putUp -> "both lean bullish"
            !callUp && !putUp -> "both lean bearish"
            else -> "they pull different ways"
        }
        val options = "In index options they are $callWord and $putWord - $lean."
        return listOf(first, detail, options, "Positions as of that day's close, not a forecast.")
    }

    /** The morning check's FII line, or null when there is no file, or only one older than [FII_OLD_DAYS] days before [today]. */
    fun fiiBrief(cur: Fii?, prev: Fii?, today: LocalDate): String? {
        if (cur == null || cur.date.isBefore(today.minusDays(FII_OLD_DAYS))) return null
        return fiiSay(cur, prev).first().removeSuffix(".") + " (NSE participant OI, ${day(cur.date)})"
    }

    // ---- the answer -----------------------------------------------------------------------------------------------

    /** The answer to [ask] from the GIFT sentences [gift] and the FII sentences [fii]: each part's lead first. */
    fun answer(ask: Ask, gift: List<String>, fii: List<String>): String = when (ask) {
        Ask.GIFT -> gift.joinToString(" ")
        Ask.FII -> fii.joinToString(" ")
        Ask.BOTH -> (listOfNotNull(gift.firstOrNull(), fii.firstOrNull()) + gift.drop(1) + fii.drop(1)).joinToString(" ")
    }
}
