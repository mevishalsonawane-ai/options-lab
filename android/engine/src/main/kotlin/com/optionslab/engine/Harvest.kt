package com.optionslab.engine

import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** IST, the zone every NSE date and minute in this app is expressed in. */
val IST: ZoneId = ZoneOffset.ofHoursMinutes(5, 30)

/**
 * The units firewall (port of `units.py`). Upstox publishes OI and volume
 * lot-multiplied; the store keeps CONTRACTS. A value that is not a whole
 * number of lots means the convention was misidentified - a hard error.
 */
object Units {
    class UnitsMismatch(msg: String) : IllegalArgumentException(msg)
    class NoLotSize(msg: String) : IllegalArgumentException(msg)

    fun toContracts(values: LongArray, lotSize: Int?): LongArray {
        if (lotSize == null || lotSize == 0) throw NoLotSize("lot size required to convert lot-multiplied units")
        val neg = values.filter { it < 0 }
        if (neg.isNotEmpty()) throw UnitsMismatch(
            "negative quantities ${neg.take(3)}; open interest and volume cannot be negative, so the upstream data is corrupt")
        val bad = values.filter { it % lotSize != 0L }
        if (bad.isNotEmpty()) throw UnitsMismatch(
            "values ${bad.take(3)} are not whole multiples of lot_size $lotSize; the source convention is not 'units'")
        return LongArray(values.size) { values[it] / lotSize }
    }
}

/** Session manifest scope (port of `harvest/manifest.py`). */
object Manifest {
    const val SAME_DAY = "same_day"
    const val BACKFILL = "backfill"

    class ScopeMismatch(msg: String) : IllegalArgumentException(msg)

    data class Entry(val session: LocalDate, val nExpiries: Int, val nContracts: Int, val scope: String, val collectedOn: LocalDate) {
        init {
            require(scope == SAME_DAY || scope == BACKFILL) { "unknown scope '$scope'" }
            if (scope == SAME_DAY && collectedOn != session) throw ScopeMismatch(
                "session $session was collected on $collectedOn; that is 'backfill', not 'same_day' - the chain as traded that day is gone")
        }
    }

    /** Understating to backfill is the safe direction throughout. */
    fun scopeFor(day: LocalDate, today: LocalDate, failures: Int, nContracts: Int = 1): String =
        if (day == today && failures == 0 && nContracts > 0) SAME_DAY else BACKFILL

    fun parseCsv(text: String): List<Entry> = text.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
        val c = line.split(",")
        Entry(LocalDate.parse(c[0]), c[1].toInt(), c[2].toInt(), c[3], LocalDate.parse(c[4]))
    }.toList()

    fun toCsv(entries: List<Entry>): String = buildString {
        append("session,n_expiries,n_contracts,scope,collected_on\n")
        for (e in entries.sortedBy { it.session }) append("${e.session},${e.nExpiries},${e.nContracts},${e.scope},${e.collectedOn}\n")
    }

    fun upsert(entries: List<Entry>, e: Entry): List<Entry> = (entries.filter { it.session != e.session } + e).sortedBy { it.session }
}

/**
 * Provenance for the bundled expiry chains (port of `provenance.py`). Facts are
 * recomputed from the data, never taken from the filesystem, so a truncated or
 * edited chain is detected rather than silently moving every result.
 */
object Provenance {
    data class Fingerprint(val session: LocalDate, val nRows: Int, val nStrikes: Int, val nSettlementBars: Int)
    data class Recorded(val session: LocalDate, val nRows: Int, val nStrikes: Int, val nSettlementBars: Int, val oiLot: Int?, val source: String)
    data class Drift(val kind: String, val session: LocalDate, val why: String)

    fun fingerprint(s: Session): Fingerprint {
        val opts = s.options
        val settle = opts.flatMap { se -> se.minutes.filter { it in 900 until 930 }.asIterable() }.toSet().size
        return Fingerprint(s.day, s.rowCount, opts.map { it.strike }.toSet().size, settle)
    }

    fun parseCsv(text: String): List<Recorded> = text.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
        val c = line.split(",")
        Recorded(LocalDate.parse(c[0]), c[3].toInt(), c[4].toInt(), c[7].toInt(), c[8].toIntOrNull(), c[10])
    }.toList()

    fun verify(sessions: List<Session>, recorded: List<Recorded>): List<Drift> {
        val rec = recorded.associateBy { it.session }
        val have = sessions.associateBy { it.day }
        val drift = ArrayList<Drift>()
        for ((day, r) in rec) {
            val s = have[day]
            if (s == null) { drift += Drift("missing", day, "$day is in the manifest but not in the data; every result since was over a smaller sample"); continue }
            val fp = fingerprint(s)
            if (fp.nRows != r.nRows || fp.nStrikes != r.nStrikes || fp.nSettlementBars != r.nSettlementBars) {
                drift += Drift("changed", day, "$day no longer matches its recorded shape (rows ${r.nRows}->${fp.nRows}, strikes ${r.nStrikes}->${fp.nStrikes}, settlement bars ${r.nSettlementBars}->${fp.nSettlementBars})")
            }
        }
        for (day in have.keys) if (day !in rec) drift += Drift("unrecorded", day, "$day is in the data but was never recorded; its origin is undeclared")
        return drift.sortedWith(compareBy({ it.session }, { it.kind }))
    }
}

/**
 * Upstox v3 candle URLs (port of `harvest/upstox.py`). Unauthenticated as of
 * 2026-09-07 - undocumented behaviour, so an auth failure is fatal, never "no data".
 */
object Upstox {
    const val BASE = "https://api.upstox.com/v3/historical-candle"
    const val MASTER_URL = "https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz"
    const val MAX_WINDOW_DAYS = 30L
    val INDEX_KEYS = linkedMapOf(
        "NIFTY" to "NSE_INDEX|Nifty 50",
        "BANKNIFTY" to "NSE_INDEX|Nifty Bank",
        "INDIAVIX" to "NSE_INDEX|India VIX",
    )

    class UpstoxError(msg: String) : RuntimeException(msg)

    /** Python's urllib.parse.quote(key, safe=""). */
    fun quote(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    /** Path order is /{to}/{from}; reversing it returns an empty candle list. */
    fun candleUrl(key: String, to: LocalDate, frm: LocalDate): String = "$BASE/${quote(key)}/minutes/1/$to/$frm"

    /** Bars for the CURRENT session, which the dated endpoint cannot return. */
    fun intradayUrl(key: String): String = "$BASE/intraday/${quote(key)}/minutes/1"

    fun monthChunks(start: LocalDate, end: LocalDate): List<Pair<LocalDate, LocalDate>> {
        require(!end.isBefore(start)) { "end $end is before start $start; the range is reversed" }
        val out = ArrayList<Pair<LocalDate, LocalDate>>()
        var lo = start
        while (!lo.isAfter(end)) {
            val hi = minOf(lo.plusDays(MAX_WINDOW_DAYS), end)
            out += lo to hi
            lo = hi.plusDays(1)
        }
        return out
    }

    data class Bar(val epochSecond: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long, val oi: Long) {
        val istDate: LocalDate get() = java.time.Instant.ofEpochSecond(epochSecond).atZone(IST).toLocalDate()
        val istMinute: Int get() = java.time.Instant.ofEpochSecond(epochSecond).atZone(IST).let { it.hour * 60 + it.minute }
    }

    /** A listed contract, identified by (underlying, expiry, strike, right). */
    data class Contract(val underlying: String, val expiry: LocalDate, val strike: Double, val right: Right,
                        val lotSize: Int, val instrumentKey: String, val tradingSymbol: String) {
        val contractId: String get() = "$underlying|$expiry|${fmtG(strike)}|$right"
        fun isExpired(on: LocalDate) = on.isAfter(expiry)
    }

    fun contractsToRefresh(contracts: List<Contract>, today: LocalDate) = contracts.filter { !it.isExpired(today) }

    /**
     * Bars (ascending, de-duplicated) -> a [Series]. Upstox is lot-multiplied;
     * with [contracts] the quantities pass the firewall into CONTRACTS, as the
     * harvest store keeps them. The expiry cache keeps Upstox units instead,
     * as the PC's does, because its OI moves are what name the lot.
     */
    fun toSeries(c: Contract?, bars: List<Bar>, day: LocalDate, contracts: Boolean = true): Series {
        val todays = bars.filter { it.istDate == day }.associateBy { it.istMinute }.toSortedMap().values.toList()
        val lot = c?.lotSize ?: 1
        val vol = LongArray(todays.size) { todays[it].volume }
        val oi = LongArray(todays.size) { todays[it].oi }
        val isIndex = c == null
        return Series(
            expiry = c?.expiry, strike = c?.strike ?: 0.0, right = c?.right ?: Right.IX, lot = lot,
            minutes = IntArray(todays.size) { todays[it].istMinute },
            close = DoubleArray(todays.size) { todays[it].close },
            open = DoubleArray(todays.size) { todays[it].open },
            high = DoubleArray(todays.size) { todays[it].high },
            low = DoubleArray(todays.size) { todays[it].low },
            volume = if (isIndex || !contracts) vol else Units.toContracts(vol, lot),
            oi = if (isIndex || !contracts) oi else Units.toContracts(oi, lot),
        )
    }
}
