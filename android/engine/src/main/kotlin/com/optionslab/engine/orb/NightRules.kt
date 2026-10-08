package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * "Night (R3)": research/hunt/x2/PREREG.md rule R3 (NIGHT-STACK), exactly as pre-registered and run by
 * research/hunt/x2/run.py. PAPER ONLY and not proven: about Rs 207 a day before the holdout and Rs 150 a day in it, at
 * 1 lot of each index, the holdout part not significant (X2_CONNECTIONS.md). Nothing here is cleared for Zerodha.
 *
 *  - Indices and contracts: NIFTY's nearest weekly and BANKNIFTY's nearest (monthly) expiry, 1 lot each, the strike one
 *    step in the money (ATM from the 15:19 close, floor(x / step + 0.5) x step; a call one step below, a put one above).
 *  - Decided on the 15:19 close ([DECIDE]), bought at 15:20 ([ENTRY_UNTIL] the last minute it may still buy), sold at the
 *    next session's 09:16 ([EXIT_AT]).
 *  - The side is h31's R17 "all agree" strong close ([strongClose]): close location in the day's range >= 0.6, the day up
 *    from the previous close and breadth (the share of the F&O stocks up on their previous close) >= 50% buys the call;
 *    the mirror (<= 0.4, down, <= 50%) the put.
 *  - Taken only when the day-end option OI build-up agrees ([side]): its 15:19 value against the same minute's previous 60
 *    sessions as a z-score ([z]), side x z >= 0.5. The build-up is h26's BUopen: over ATM +-2 of the nearest series, each
 *    strike's call OI change since 09:15 signed by its price change, less the put's, over their open interest ([buildUp]).
 *  - Never on an expiry day of the index, never held into the contract's expiry ([expiry]); skipped when the contract
 *    barely trades (the app's thin-option gate). Pure: no clock, no network, no orders.
 */
object NightRules {
    const val SOURCE = "night_r3"
    const val LABEL = "Night (R3)"
    val UNDERLYINGS: List<String> = listOf("NIFTY", "BANKNIFTY")

    /** The strike step: NIFTY 50, BANKNIFTY 100. */
    fun step(underlying: String): Int = if (underlying == "BANKNIFTY") 100 else 50

    /** The decision reads the 15:19 minute's close: made from 15:20. */
    val DECIDE: LocalTime = LocalTime.of(15, 20)
    /** The buy is placed before this (the 15:20 minute and two more if the watch was a moment late); else skipped. */
    val ENTRY_UNTIL: LocalTime = LocalTime.of(15, 23)
    /** The next session's sale: at 09:16 (the research's X0916 exit). */
    val EXIT_AT: LocalTime = LocalTime.of(9, 16)
    /** The last minute the decision reads (15:19, h31's S1519 column). */
    val LAST_MINUTE: LocalTime = LocalTime.of(15, 19)

    const val LOC_UP = 0.6
    const val LOC_DOWN = 0.4
    const val BREADTH_MID = 0.5
    const val BUZ_MIN = 0.5
    const val Z_WINDOW = 60
    const val Z_MIN = 20
    /** Breadth needs at least this many stocks priced (h31's minute breadth). */
    const val BREADTH_MIN = 100

    /** The research numbers Boss sees: rupees a day at 1 lot of each index (X2, after the real spread). */
    const val PRE_PER_DAY = 207
    const val HOLD_PER_DAY = 150
    const val RECORD = "research X2: about Rs 207 a day before the holdout and Rs 150 a day in it, 1 lot each - not proven"
    const val RULES = "NIFTY weekly + BANKNIFTY monthly, 1-ITM, 1 lot each · buys at 15:20 on a strong close the day-end OI agrees with · " +
        "sells at 09:16 next session · paper only"

    /** Where the 15:19 close [x] sits in the day's range [lo]..[hi], 0 at the low, 1 at the high; 0.5 with no range. */
    fun loc(x: Double, hi: Double, lo: Double): Double = if (hi > lo) (x - lo) / (hi - lo) else 0.5

    /** The day's move: the 15:19 close against the previous session's close. */
    fun dayMove(x: Double, prevClose: Double): Double = x / prevClose - 1

    /** h31 R17: +1 call, -1 put, 0 nothing. Breadth not known (null): nothing. */
    fun strongClose(loc: Double, dayMove: Double, breadth: Double?): Int = when {
        breadth == null || !breadth.isFinite() -> 0
        loc >= LOC_UP && dayMove >= 0 && breadth >= BREADTH_MID -> 1
        loc <= LOC_DOWN && dayMove <= 0 && breadth <= BREADTH_MID -> -1
        else -> 0
    }

    /** R3: the strong close's side when the build-up's z agrees (side x z >= 0.5); an unknown z counts as 0. */
    fun side(strong: Int, buz: Double?): Int {
        val z = buz?.takeIf { it.isFinite() } ?: 0.0
        return if (strong != 0 && strong * z >= BUZ_MIN) strong else 0
    }

    /** One strike's call or put: its close and open interest at 09:15 ([close0], [oi0]) and at 15:19 ([closeT], [oiT]). */
    data class Leg(val close0: Double?, val closeT: Double?, val oi0: Double?, val oiT: Double?)

    /** One strike of ATM +-2: its call and its put. */
    data class Strike(val ce: Leg, val pe: Leg)

    private fun ok(x: Double?) = x != null && x.isFinite()

    /**
     * h26's BUopen at 15:19: over the strikes, each one's (sign of the call's price change x the call's OI change, less the
     * same for the put), over the strikes' call + put OI now. A strike missing any of its eight figures is left out of the
     * sum (numpy's nansum of a NaN term), and one missing an OI now out of the total. Null when the total is not positive.
     */
    fun buildUp(strikes: List<Strike>): Double? {
        var num = 0.0
        var den = 0.0
        for (s in strikes) {
            val c = s.ce; val p = s.pe
            if (listOf(c.close0, c.closeT, c.oi0, c.oiT, p.close0, p.closeT, p.oi0, p.oiT).all(::ok))
                num += sign(c.closeT!! - c.close0!!) * abs(c.oiT!! - c.oi0!!) - sign(p.closeT!! - p.close0!!) * abs(p.oiT!! - p.oi0!!)
            if (ok(c.oiT) && ok(p.oiT)) den += c.oiT!! + p.oiT!!
        }
        return if (den > 0) num / den else null
    }

    /** [value]'s z-score against the last [Z_WINDOW] of [history] (oldest first); null under [Z_MIN] values or no spread. */
    fun z(value: Double, history: List<Double>): Double? {
        val h = history.filter { it.isFinite() }.takeLast(Z_WINDOW)
        if (h.size < Z_MIN || !value.isFinite()) return null
        val mean = h.average()
        val sd = sqrt(h.sumOf { (it - mean) * (it - mean) } / (h.size - 1))
        return if (sd > 0) (value - mean) / sd else null
    }

    /** The share of stocks up on their previous close: (last, previous close) pairs; null under [BREADTH_MIN] priced. */
    fun breadth(quotes: List<Pair<Double, Double>>): Double? {
        val ok = quotes.filter { (l, p) -> l.isFinite() && p.isFinite() && l > 0 && p > 0 }
        if (ok.size < BREADTH_MIN) return null
        return ok.count { (l, p) -> l > p }.toDouble() / ok.size
    }

    /** The 1-ITM strike for [side] (+1 call, -1 put) from the 15:19 close [x]. */
    fun strike(x: Double, underlying: String, side: Int): Int {
        val st = step(underlying)
        return OrbRules.atmStrike(x, st) - side * st
    }

    /**
     * The contract's expiry: the nearest listed on or after [today], or null when it may not be traded tonight - today is
     * an expiry day of the index (a listed expiry), or the nearest expires on or before the next session [nextSession].
     */
    fun expiry(today: LocalDate, nextSession: LocalDate, listed: Collection<LocalDate>): LocalDate? {
        if (today in listed) return null
        val near = OrbRules.expiryOnOrAfter(today, listed) ?: return null
        return near.takeIf { it.isAfter(nextSession) }
    }

    /** A decision and why, in a few words for the arm's log. */
    data class Decision(val side: Int, val why: String)

    /** The decision from the day's figures ([breadth] and [buz] null when they could not be read). */
    fun decide(loc: Double, dayMove: Double, breadth: Double?, buz: Double?): Decision {
        if (breadth == null) return Decision(0, "night_no_breadth")
        val strong = strongClose(loc, dayMove, breadth)
        if (strong == 0) return Decision(0, "night_no_strong_close")
        val s = side(strong, buz)
        if (s == 0) return Decision(0, if (buz == null) "night_no_oi_history" else "night_oi_disagrees")
        return Decision(s, if (s > 0) "night_buy_ce" else "night_buy_pe")
    }

    /** Whether [now] is in the buying minutes (15:20 to before 15:23). */
    fun entryWindow(now: LocalTime): Boolean = !now.isBefore(DECIDE) && now.isBefore(ENTRY_UNTIL)

    /** Whether a position bought on [entryDay] is due for its 09:16 sale at [now] (any later session). */
    fun exitDue(entryDay: LocalDate, now: LocalDateTime): Boolean = now.toLocalDate().isAfter(entryDay) && !now.toLocalTime().isBefore(EXIT_AT)

    /**
     * The F&O stocks breadth is read from (the research's 214), as Zerodha's NSE symbols ("M&M", "GVT&D"). A stock not
     * found is left out; under [BREADTH_MIN] priced, breadth is not known.
     */
    val BREADTH_UNIVERSE: List<String> = (
        "360ONE ABB ABCAPITAL ADANIENSOL ADANIENT ADANIGREEN ADANIPORTS ADANIPOWER ALKEM AMBER AMBUJACEM ANANDRATHI ANGELONE " +
        "APLAPOLLO APOLLOHOSP ASHOKLEY ASIANPAINT ASTRAL ATHERENERG AUBANK AUROPHARMA AXISBANK BAJAJ-AUTO BAJAJFINSV BAJAJHFL " +
        "BAJAJHLDNG BAJFINANCE BANDHANBNK BANKBARODA BANKINDIA BDL BEL BHARATFORG BHARTIARTL BHEL BIOCON BLUESTARCO BOSCHLTD BPCL " +
        "BRITANNIA BSE CAMS CANBK CDSL CGPOWER CHOLAFIN CIPLA COALINDIA COCHINSHIP COFORGE COLPAL CONCOR CROMPTON CUMMINSIND DABUR " +
        "DELHIVERY DIVISLAB DIXON DLF DMART DRREDDY EICHERMOT ENRIN ETERNAL FEDERALBNK FORCEMOT FORTIS GAIL GLENMARK GMRAIRPORT " +
        "GODFRYPHLP GODREJCP GODREJPROP GRASIM GVT&D HAL HAVELLS HCLTECH HDFCAMC HDFCBANK HDFCLIFE HEROMOTOCO HINDALCO HINDPETRO " +
        "HINDUNILVR HINDZINC HYUNDAI ICICIBANK ICICIGI ICICIPRULI IDEA IDFCFIRSTB IEX INDHOTEL INDIANB INDIGO INDUSINDBK INDUSTOWER " +
        "INFY INOXWIND IOC IREDA IRFC ITC JINDALSTEL JIOFIN JSWENERGY JSWSTEEL JUBLFOOD KALYANKJIL KAYNES KEI KFINTECH KOTAKBANK " +
        "KPITTECH LAURUSLABS LICHSGFIN LICI LODHA LT LTF LTM LUPIN MAHABANK MANAPPURAM MANKIND MARICO MARUTI MAXHEALTH MAZDOCK MCX " +
        "MFSL MOTHERSON MOTILALOFS MPHASIS MUTHOOTFIN M&M NAM-INDIA NATIONALUM NAUKRI NBCC NESTLEIND NHPC NMDC NTPC NYKAA OBEROIRLTY " +
        "OFSS OIL ONGC PAGEIND PATANJALI PAYTM PERSISTENT PETRONET PFC PGEL PHOENIXLTD PIDILITIND PIIND PNB PNBHOUSING POLICYBZR " +
        "POLYCAB POWERGRID POWERINDIA PREMIERENE PRESTIGE RADICO RBLBANK RECLTD RELIANCE RVNL SAGILITY SAIL SBICARD SBILIFE SBIN " +
        "SHREECEM SHRIRAMFIN SIEMENS SOLARINDS SONACOMS SRF SUNPHARMA SUPREMEIND SUZLON SWIGGY TATACONSUM TATAELXSI TATAPOWER " +
        "TATASTEEL TCS TECHM TIINDIA TITAN TMPV TORNTPHARM TRENT TVSMOTOR UJJIVANSFB ULTRACEMCO UNIONBANK UNITDSPR UNOMINDA UPL VBL " +
        "VEDL VMM VOLTAS WAAREEENER WIPRO YESBANK ZYDUSLIFE").split(' ')

    /**
     * The day-end build-up's history the app starts from: h26's BUopen at 15:19 for the 60 sessions 10 Jul - 5 Oct 2026
     * (oldest first), from the research data. The app adds each session's own after that ([z] reads the last 60).
     */
    val SEED: Map<String, List<Double>> = mapOf(
        "NIFTY" to ("0.399158,0.372798,-0.118142,-0.436071,-0.071250,0.545429,0.000000,-0.193969,-0.594287,-0.065036,0.527068," +
            "0.552327,-0.098244,0.646224,0.288453,0.142118,0.424367,-0.470586,-0.389766,0.382806,-0.116463,0.156389,-0.185605," +
            "-0.588814,0.345763,0.413036,0.484518,-0.065867,-0.275495,0.496183,-0.416030,-0.483541,0.331934,-0.404511,-0.460288," +
            "0.395112,0.479258,-0.690918,0.501707,-0.384361,0.209246,-0.680038,-0.619747,-0.565804,-0.359270,0.373461,-0.727996," +
            "-0.425795,0.322408,0.175248,0.641289,-0.322574,0.446707,-0.520770,0.372032,-0.708347,-0.127984,-0.441717,-0.675012,0.109688")
            .split(',').map { it.toDouble() },
        "BANKNIFTY" to ("0.180034,0.085334,-0.259362,0.077437,-0.211617,0.097755,0.000000,-0.218330,-0.407055,-0.538646,0.394075," +
            "-0.033008,-0.229207,0.085945,0.191086,-0.167838,0.406057,-0.200224,-0.097907,0.151703,-0.008069,-0.231408,-0.078741," +
            "0.110356,-0.060038,-0.076882,0.159484,-0.263070,0.000349,-0.013841,0.227749,-0.499350,0.070582,0.233796,-0.086102," +
            "0.095144,0.048776,-0.080495,0.175391,-0.044006,0.018807,-0.232830,-0.164380,-0.321219,-0.271013,0.108601,-0.230387," +
            "0.197531,-0.034562,0.131697,0.034131,-0.219793,0.260810,-0.535907,0.182211,-0.641415,-0.062594,0.107403,-0.283450,-0.119814")
            .split(',').map { it.toDouble() },
    )
    /** The last session in [SEED]. */
    val SEED_UNTIL: LocalDate = LocalDate.of(2026, 10, 5)
}
