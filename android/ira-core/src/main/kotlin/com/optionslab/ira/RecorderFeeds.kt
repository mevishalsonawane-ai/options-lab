package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The market recorder's two feeds read OUTSIDE its 09:00-15:35 window (Boss, 06 Oct 2026), pure: their sources, their
 * parsers, when each is due and when the off-hours alarm next fires. The app fetches and writes
 * ([com.optionslab.app.data.MarketRecorder]); nothing here touches the network, a file or an order.
 *
 *  - NSE's participant-wise open interest (and trading volume) in equity derivatives: Client / DII / FII / Pro, published
 *    once a trading day after the close. Read from [OI_FROM] (18:30 IST) on the trade date, retried at most every
 *    [OI_RETRY_MIN] minutes that evening and the next morning until 09:00 of the next trading day; then, if still missing,
 *    one gap. Rows: "P" records, the trade date first.
 *  - GIFT Nifty (NSE IX near-month NIFTY future): last, change and the exchange's time, every [GIFT_PRE_EVERY] minutes from
 *    06:30 to 09:15, every [GIFT_HOURS_EVERY] minutes in market hours and once from [GIFT_NIGHT] (23:30), trading days
 *    only. Rows: "I" records.
 *
 *  P,tradeDate,fetched,file,client,futIdxLong,futIdxShort,futStkLong,futStkShort,optIdxCallLong,optIdxPutLong,
 *    optIdxCallShort,optIdxPutShort,optStkCallLong,optStkPutLong,optStkCallShort,optStkPutShort,totalLong,totalShort
 *                                                       (file: oi = open interest, vol = trading volume; contracts)
 *  I,time,symbol,expiry,last,change,pctChange,contracts,exchangeTime   GIFT Nifty as NSE IX's own page shows it
 */
object RecorderFeeds {
    // ---- the sources (probed 06 Oct 2026 from a server: both answered without a login, cookie or key) --------------------

    private val DDMMYYYY = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ENGLISH)

    /** NSE's participant-wise OI file for [d] (CSV; 404 until published). */
    fun oiUrl(d: LocalDate): String = "https://nsearchives.nseindia.com/content/nsccl/fao_participant_oi_${d.format(DDMMYYYY)}.csv"
    /** NSE's participant-wise trading volume file for [d] (CSV, same layout). */
    fun volUrl(d: LocalDate): String = "https://nsearchives.nseindia.com/content/nsccl/fao_participant_vol_${d.format(DDMMYYYY)}.csv"
    /** NSE IX's home-page ticker: the GIFT Nifty near-month future (JSON, public; a browser User-Agent is required). */
    const val GIFT_URL = "https://www.nseix.com/api/market-rate?type=derivative"

    // ---- participant-wise OI / volume ---------------------------------------------------------------------------------

    /** The 14 figures, in the file's (and the record's) order. */
    val COLUMNS = listOf(
        "Future Index Long", "Future Index Short", "Future Stock Long", "Future Stock Short",
        "Option Index Call Long", "Option Index Put Long", "Option Index Call Short", "Option Index Put Short",
        "Option Stock Call Long", "Option Stock Put Long", "Option Stock Call Short", "Option Stock Put Short",
        "Total Long Contracts", "Total Short Contracts",
    )
    /** The participants kept (and TOTAL), as NSE names them. */
    val CLIENTS = listOf("Client", "DII", "FII", "Pro", "TOTAL")

    data class Participant(val client: String, val figures: List<Long>) {
        fun of(column: String): Long = figures[COLUMNS.indexOf(column)]
        val futIdxNet: Long get() = figures[0] - figures[1]
    }

    data class Participants(val date: LocalDate, val rows: List<Participant>)

    private val AS_ON = Regex("(?i)as on\\s+([A-Za-z]{3})[a-z]*\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /**
     * NSE's participant-wise CSV: a title line ("... as on Oct 05, 2026"), a header ("Client Type,Future Index Long,...")
     * and one row per participant. Columns found by name (spaces trimmed), so a reordering does not misfile a figure.
     * Throws [IllegalArgumentException] for anything that is not that file (an HTML error page, a cut-off answer).
     */
    fun parseParticipants(csv: String): Participants {
        val lines = csv.replace("﻿", "").split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        val date = lines.firstNotNullOfOrNull { l -> AS_ON.find(l) }?.let { m ->
            val mon = MONTHS.indexOf(m.groupValues[1].lowercase(Locale.ENGLISH)) + 1
            require(mon > 0) { "unknown month" }
            LocalDate.of(m.groupValues[3].toInt(), mon, m.groupValues[2].toInt())
        } ?: throw IllegalArgumentException("no date")
        val hi = lines.indexOfFirst { it.trim().startsWith("Client Type", ignoreCase = true) }
        require(hi >= 0) { "no header" }
        val head = MarketRecord.fields(lines[hi]).map { it.trim().lowercase(Locale.ENGLISH) }
        val idx = COLUMNS.map { c -> head.indexOf(c.lowercase(Locale.ENGLISH)).also { require(it > 0) { "missing column" } } }
        val rows = ArrayList<Participant>()
        for (l in lines.drop(hi + 1)) {
            val f = MarketRecord.fields(l).map { it.trim() }
            val who = CLIENTS.firstOrNull { it.equals(f[0], ignoreCase = true) } ?: continue
            val nums = idx.map { i -> f.getOrNull(i)?.replace(",", "")?.toLongOrNull() ?: throw IllegalArgumentException("bad figure") }
            rows += Participant(who, nums)
        }
        require(rows.count { it.client != "TOTAL" } == 4) { "not four participants" }
        return Participants(date, rows)
    }

    fun participant(p: Participants, fetched: LocalDateTime, file: String, row: Participant): String =
        (listOf("P", p.date.toString(), fetched.withNano(0).toString(), file, row.client) + row.figures.map { it.toString() }).joinToString(",")

    // ---- GIFT Nifty ---------------------------------------------------------------------------------------------------

    data class Gift(val symbol: String, val expiry: LocalDate?, val last: Double, val change: Double?, val pct: Double?,
        val contracts: Long?, val at: LocalDateTime?)

    private val DMY = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH)
    private val DMY_HMS = DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss", Locale.ENGLISH)

    private fun month(s: String) = s.split('-').let { p -> if (p.size == 3 && p[1].length >= 3) p[0] + "-" + p[1].substring(0, 1).uppercase() + p[1].substring(1, 3).lowercase() + "-" + p[2] else s }

    /**
     * NSE IX's ticker JSON ({"data":[{"INSTRUMENTTYPE":"FUTIDX","SYMBOL":"NIFTY","EXPIRYDATE":"27-Oct-2026",
     * "LASTPRICE":"22804.50","DAYCHANGE":"30.50","PERCHANGE":".13","CONTRACTSTRADED":56491,"TIMESTMP":"06-Oct-2026 22:02:01"}]}):
     * the NIFTY index future with the nearest expiry. Null when there is none (or no price).
     */
    fun parseGift(json: String): Gift? {
        val out = ArrayList<Gift>()
        for (m in Regex("\\{[^{}]*\\}").findAll(json)) {
            val o = m.value
            fun f(k: String) = Regex("\"$k\"\\s*:\\s*(?:\"([^\"]*)\"|([^,}\\s]*))").find(o)?.groupValues?.let { it[1].ifEmpty { it[2] } }?.trim()
            if (f("INSTRUMENTTYPE")?.equals("FUTIDX", true) != true || f("SYMBOL")?.equals("NIFTY", true) != true) continue
            val last = f("LASTPRICE")?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 } ?: continue
            out += Gift("NIFTY", f("EXPIRYDATE")?.let { runCatching { LocalDate.parse(month(it), DMY) }.getOrNull() }, last,
                f("DAYCHANGE")?.replace(",", "")?.toDoubleOrNull(), f("PERCHANGE")?.replace(",", "")?.toDoubleOrNull(),
                f("CONTRACTSTRADED")?.toLongOrNull(),
                f("TIMESTMP")?.let { s -> runCatching { LocalDateTime.parse(s.substringBefore(' ').let(::month) + " " + s.substringAfter(' '), DMY_HMS) }.getOrNull() })
        }
        return out.minByOrNull { it.expiry ?: LocalDate.MAX }
    }

    fun gift(t: LocalTime, g: Gift): String = listOf("I", MarketRecord.hms(t), g.symbol, g.expiry?.toString() ?: "", MarketRecord.num(g.last),
        MarketRecord.num(g.change), MarketRecord.num(g.pct), g.contracts?.toString() ?: "", g.at?.toString() ?: "").joinToString(",")

    /** The card's and Jarvis's words for a reading: "22804.5 (+30.5) at 22:02:01 today". */
    fun giftText(g: Gift, read: LocalDateTime, today: LocalDate): String {
        val ch = g.change?.let { " (" + (if (it >= 0) "+" else "") + MarketRecord.num(it) + ")" } ?: ""
        return MarketRecord.num(g.last) + ch + " at " + MarketRecord.whenText(g.at ?: read, today)
    }

    /** Kept in the app's settings for the card: "readAt|last|change|exchangeTime|expiry". */
    fun giftEncode(g: Gift, read: LocalDateTime): String =
        listOf(read.withNano(0).toString(), MarketRecord.num(g.last), MarketRecord.num(g.change), g.at?.toString() ?: "", g.expiry?.toString() ?: "").joinToString("|")

    fun giftDecode(s: String?): Pair<LocalDateTime, Gift>? {
        val p = s?.split('|') ?: return null
        if (p.size < 5) return null
        val read = runCatching { LocalDateTime.parse(p[0]) }.getOrNull() ?: return null
        val last = p[1].toDoubleOrNull() ?: return null
        return read to Gift("NIFTY", runCatching { LocalDate.parse(p[4]) }.getOrNull(), last, p[2].toDoubleOrNull(), null, null,
            runCatching { LocalDateTime.parse(p[3]) }.getOrNull())
    }

    // ---- when each is due ---------------------------------------------------------------------------------------------

    val GIFT_FROM: LocalTime = LocalTime.of(6, 30)
    val GIFT_PRE_UNTIL: LocalTime = LocalTime.of(9, 15)
    val GIFT_HOURS_UNTIL: LocalTime = LocalTime.of(15, 30)
    val GIFT_NIGHT: LocalTime = LocalTime.of(23, 30)
    /** The night reading is taken from here (an alarm a little early or late still counts). */
    val GIFT_NIGHT_FROM: LocalTime = LocalTime.of(23, 15)
    const val GIFT_PRE_EVERY = 15L
    const val GIFT_HOURS_EVERY = 5L
    val OI_FROM: LocalTime = LocalTime.of(18, 30)
    const val OI_RETRY_MIN = 55L
    /** The evening's tries after [OI_FROM] (while still missing), and the morning's on a closed day. */
    val OI_EVENING: List<LocalTime> = listOf(LocalTime.of(19, 30), LocalTime.of(20, 30), LocalTime.of(21, 30), LocalTime.of(22, 30))
    val OI_CLOSED_MORNING: LocalTime = LocalTime.of(8, 0)
    /** A failed read writes a gap at most this often (a broken page is one gap an hour, not one every 5 minutes). */
    const val GAP_EVERY_MIN = 60L

    /** A slot's interval with 30 s of slack, so a pass a little early still counts. */
    private fun waited(last: LocalDateTime?, now: LocalDateTime, minutes: Long) =
        last == null || last.isAfter(now) || Duration.between(last, now).seconds >= minutes * 60 - 30

    /**
     * Is a GIFT Nifty reading due at [now] (a trading day: [tradingDay]), the last one taken at [last]? Every 15 minutes
     * from 06:30 to 09:15, every 5 in market hours (09:15-15:30), once from 23:15; never on a closed day.
     */
    fun giftDue(tradingDay: Boolean, now: LocalDateTime, last: LocalDateTime?): Boolean {
        if (!tradingDay) return false
        val t = now.toLocalTime()
        return when {
            t.isBefore(GIFT_FROM) -> false
            t.isBefore(GIFT_PRE_UNTIL) -> waited(last, now, GIFT_PRE_EVERY)
            !t.isAfter(GIFT_HOURS_UNTIL) -> waited(last, now, GIFT_HOURS_EVERY)
            !t.isBefore(GIFT_NIGHT_FROM) -> last == null || last.isBefore(now.toLocalDate().atTime(GIFT_NIGHT_FROM)) || last.isAfter(now)
            else -> false
        }
    }

    private fun prevTrading(d: LocalDate, trading: (LocalDate) -> Boolean): LocalDate? {
        var x = d.minusDays(1)
        repeat(14) { if (trading(x)) return x; x = x.minusDays(1) }
        return null
    }

    private fun nextTrading(d: LocalDate, trading: (LocalDate) -> Boolean): LocalDate {
        var x = d.plusDays(1)
        repeat(14) { if (trading(x)) return x; x = x.plusDays(1) }
        return x
    }

    /**
     * The trade date whose participant file should be read at [now]: today from 18:30 on a trading day, else the last
     * trading day - until 09:00 of the trading day after it. Null outside that.
     */
    fun oiTradeDate(now: LocalDateTime, trading: (LocalDate) -> Boolean): LocalDate? {
        val today = now.toLocalDate()
        if (trading(today) && !now.toLocalTime().isBefore(OI_FROM)) return today
        val prev = prevTrading(today, trading) ?: return null
        return prev.takeIf { now.isBefore(nextTrading(prev, trading).atTime(MarketRecord.FROM)) }
    }

    /** The trade date to read now, or null: none pending ([recorded] already has it) or tried within [OI_RETRY_MIN] minutes. */
    fun oiDue(now: LocalDateTime, trading: (LocalDate) -> Boolean, recorded: LocalDate?, lastTry: LocalDateTime?): LocalDate? {
        val d = oiTradeDate(now, trading) ?: return null
        if (recorded != null && !recorded.isBefore(d)) return null
        return d.takeIf { waited(lastTry, now, OI_RETRY_MIN) }
    }

    /**
     * From 09:00 on a trading day: the last trading day's file was tried ([lastTry], from its 18:30) but never read
     * ([recorded] is older), and its gap not yet written ([missed]) - the one gap for it. Null otherwise (a phone that
     * was off all evening tried nothing: no gap is invented for it).
     */
    fun oiMissed(now: LocalDateTime, trading: (LocalDate) -> Boolean, recorded: LocalDate?, lastTry: LocalDateTime?, missed: LocalDate?): LocalDate? {
        val today = now.toLocalDate()
        if (!trading(today) || now.toLocalTime().isBefore(MarketRecord.FROM)) return null
        val prev = prevTrading(today, trading) ?: return null
        if (lastTry == null || lastTry.isBefore(prev.atTime(OI_FROM))) return null
        if (recorded != null && !recorded.isBefore(prev)) return null
        if (missed != null && !missed.isBefore(prev)) return null
        return prev
    }

    /**
     * The off-hours alarm's next time after [now]: on trading days 06:30-09:00 every 15 minutes (GIFT Nifty, and the
     * missing participant file), 18:30, the evening's retries while [oiPending], and 23:30; on a closed day 08:00 while
     * [oiPending]. Market hours are the market watch's own (the recorder's pass in it reads GIFT Nifty every 5 minutes).
     */
    fun nextSlot(now: LocalDateTime, trading: (LocalDate) -> Boolean, oiPending: Boolean): LocalDateTime {
        for (i in 0L..14L) {
            val d = now.toLocalDate().plusDays(i)
            val slots = ArrayList<LocalTime>()
            if (trading(d)) {
                var t = GIFT_FROM
                while (t.isBefore(GIFT_PRE_UNTIL)) { slots += t; t = t.plusMinutes(GIFT_PRE_EVERY) }
                slots += OI_FROM
                if (oiPending && i == 0L) slots += OI_EVENING
                slots += GIFT_NIGHT
            } else if (oiPending) slots += OI_CLOSED_MORNING
            slots.sorted().map { d.atTime(it) }.firstOrNull { it.isAfter(now) }?.let { return it }
        }
        return now.toLocalDate().plusDays(1).atTime(GIFT_FROM)
    }

    /** May a failure write its gap now (the last written at [lastGap])? At most one every [GAP_EVERY_MIN] minutes. */
    fun gapAllowed(lastGap: LocalDateTime?, now: LocalDateTime): Boolean = waited(lastGap, now, GAP_EVERY_MIN)

    // ---- the README's lines -------------------------------------------------------------------------------------------

    val README: String = """
        P,tradeDate,fetched,file,client,futIdxLong,futIdxShort,futStkLong,futStkShort,optIdxCallLong,optIdxPutLong,optIdxCallShort,optIdxPutShort,optStkCallLong,optStkPutLong,optStkCallShort,optStkPutShort,totalLong,totalShort
                                                             (NSE participant-wise OI (file=oi) and trading volume (file=vol), contracts; read after 18:30, in the trade date's file)
        I,time,symbol,expiry,last,change,pctChange,contracts,exchangeTime   (GIFT Nifty near-month future from NSE IX: 06:30-09:15 every 15 min, market hours every 5, 23:30)
    """.trimIndent()
}
