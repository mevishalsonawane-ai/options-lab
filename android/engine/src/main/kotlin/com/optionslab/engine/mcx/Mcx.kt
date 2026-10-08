package com.optionslab.engine.mcx

import com.optionslab.engine.IST
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.fmtG
import com.optionslab.engine.strategy.Json
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MCX (commodities) for IraAlgo: the contracts Zerodha lists, their lot multipliers and ticks, near and next futures,
 * the near-month option chain and each option's underlying future (research/MCX_GUIDE.md). Pure: no clock, no network.
 *
 * Units: the app's paper account and every P&L figure keep quantities in UNITS (lots x [McxContract.multiplier]), the
 * same way NFO is kept; Zerodha takes an MCX order's quantity in LOTS ([Kite.LOT_QUOTED]). [McxContract.kiteQuantity]
 * turns one into the other at the Zerodha boundary.
 *
 * Where the numbers come from:
 *  - the contracts, expiries, strikes and ticks: Zerodha's instrument list (GET /instruments/MCX, segments MCX-FUT and
 *    MCX-OPT). Its lot_size column is always 1 on MCX (Zerodha orders MCX in lots), so it does not say a lot's size;
 *  - each contract's multiplier (units in one lot): Upstox's public MCX list (qty_multiplier), joined on the exchange
 *    token both lists carry ([parseUpstoxMultipliers]);
 *  - when neither is at hand, [COMMODITIES]: the fallback table, as listed on [TABLE_AS_OF].
 */
object Mcx {
    const val EXCHANGE = "MCX"

    /** The day [COMMODITIES] was read off the Kite list, Upstox's list and Zerodha's margin page. */
    val TABLE_AS_OF: LocalDate = LocalDate.of(2026, 10, 8)

    /**
     * One commodity. [multiplier]: units in a lot (price x multiplier = rupees per lot); [tick]: the futures' tick and
     * [optionTick] the options' (null: no options listed); [physical]: settled by delivery (Zerodha does not allow it, so
     * the future is closed before the tender period); [marginPerLot]: Zerodha's NRML margin per lot of the near future
     * (MIS is the same on MCX) on [TABLE_AS_OF] - a fallback only: it moves daily.
     */
    data class Commodity(
        val name: String, val label: String, val multiplier: Int, val tick: Double, val optionTick: Double?,
        val physical: Boolean, val marginPerLot: Double, val unit: String,
    )

    /** Fallback table (research/MCX_GUIDE.md section 1a, read 8 Oct 2026). The live lists win whenever they are read. */
    val COMMODITIES: List<Commodity> = listOf(
        Commodity("CRUDEOIL", "Crude oil", 100, 1.0, 0.10, false, 267_290.0, "bbl"),
        Commodity("CRUDEOILM", "Crude oil mini", 10, 1.0, 0.05, false, 26_729.0, "bbl"),
        Commodity("NATURALGAS", "Natural gas", 1250, 0.10, 0.05, false, 64_842.0, "mmBtu"),
        Commodity("NATGASMINI", "Natural gas mini", 250, 0.10, 0.05, false, 12_968.0, "mmBtu"),
        Commodity("GOLD", "Gold (1 kg)", 100, 1.0, 0.50, true, 1_379_178.0, "10 g"),
        Commodity("GOLDM", "Gold mini (100 g)", 10, 1.0, 0.50, true, 136_807.0, "10 g"),
        Commodity("GOLDTEN", "Gold ten (10 g)", 1, 1.0, null, true, 13_715.0, "10 g"),
        Commodity("GOLDGUINEA", "Gold guinea (8 g)", 1, 1.0, null, true, 11_014.0, "8 g"),
        Commodity("GOLDPETAL", "Gold petal (1 g)", 1, 1.0, null, true, 1_377.0, "g"),
        Commodity("SILVER", "Silver (30 kg)", 30, 1.0, 0.50, true, 855_135.0, "kg"),
        Commodity("SILVERM", "Silver mini (5 kg)", 5, 1.0, 0.50, true, 143_930.0, "kg"),
        Commodity("SILVERMIC", "Silver micro (1 kg)", 1, 1.0, null, true, 28_793.0, "kg"),
        Commodity("COPPER", "Copper", 2500, 0.05, 0.01, true, 329_357.0, "kg"),
        Commodity("ZINC", "Zinc", 5000, 0.05, 0.01, true, 196_225.0, "kg"),
        Commodity("ZINCMINI", "Zinc mini", 1000, 0.05, null, true, 39_245.0, "kg"),
        Commodity("ALUMINIUM", "Aluminium", 5000, 0.05, null, true, 156_228.0, "kg"),
        Commodity("ALUMINI", "Aluminium mini", 1000, 0.05, null, true, 31_256.0, "kg"),
        Commodity("LEAD", "Lead", 5000, 0.05, null, true, 72_209.0, "kg"),
        Commodity("LEADMINI", "Lead mini", 1000, 0.05, null, true, 14_438.0, "kg"),
        Commodity("NICKEL", "Nickel", 250, 0.10, null, true, 35_824.0, "kg"),
    )

    private val BY_NAME = COMMODITIES.associateBy { it.name }

    /** The commodities the app lists, in the table's order. */
    val NAMES: List<String> = COMMODITIES.map { it.name }

    /** The option books worth a chain (research/MCX_GUIDE.md 1b: liquid near month); others listed are shown too. */
    val CHAIN_FIRST: List<String> = listOf("CRUDEOIL", "NATURALGAS", "GOLDM", "SILVERM")

    fun commodity(name: String): Commodity? = BY_NAME[name.uppercase(Locale.ROOT)]

    fun isMcxName(name: String): Boolean = name.uppercase(Locale.ROOT) in BY_NAME

    /** Settled by delivery (gold, silver, base metals); crude and natural gas settle in cash. Unknown: treated as physical (safer). */
    fun physical(name: String): Boolean = commodity(name)?.physical ?: true

    /** The fallback multiplier (units per lot), or null for a name not in the table. */
    fun fallbackMultiplier(name: String): Int? = commodity(name)?.multiplier
}

/** A future or an option on MCX. [right] is CE / PE for an option and null for a future. */
data class McxContract(
    val token: Long, val exchangeToken: Long, val tradingSymbol: String, val name: String, val expiry: LocalDate,
    val strike: Double, val right: Right?, val tick: Double, val multiplier: Int,
) {
    val isOption: Boolean get() = right != null
    val isFuture: Boolean get() = right == null
    /** Zerodha's quote key ("MCX:CRUDEOIL26OCTFUT"). */
    val kiteKey: String get() = "${Mcx.EXCHANGE}:$tradingSymbol"
    /** Upstox's candle key ("MCX_FO|580470"): the paper feed's (public 1-minute candles). */
    val upstoxKey: String get() = "MCX_FO|$exchangeToken"
    /** The paper account's symbol: name, expiry DDMMMYY, then strike and right, or FUT (the engine reads the expiry from it). */
    val paperSymbol: String get() = McxInstruments.paperSymbol(name, expiry, strike, right)
    /** Zerodha's order quantity (lots) for [units]; the remainder is dropped (a whole lot is always ordered). */
    fun kiteQuantity(units: Int): Int = if (multiplier > 0) units / multiplier else 0
    /** "CRUDEOIL 15 OCT 8550 CE" / "CRUDEOIL 19 OCT FUT", for a screen or a notice. */
    val label: String get() = name + " " + expiry.format(DAY_MON).uppercase(Locale.ENGLISH) + (right?.let { " ${fmtG(strike)} ${it.name}" } ?: " FUT")

    private companion object { val DAY_MON: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH) }
}

object McxInstruments {
    private val DDMMMYY = DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH)
    private val YYMMM = DateTimeFormatter.ofPattern("yyMMM", Locale.ENGLISH)

    fun paperSymbol(name: String, expiry: LocalDate, strike: Double, right: Right?): String =
        name.uppercase(Locale.ROOT) + expiry.format(DDMMMYY).uppercase(Locale.ENGLISH) + (right?.let { fmtG(strike) + it.name } ?: "FUT")

    /** Zerodha's own symbol for an MCX contract (CRUDEOIL26OCTFUT, CRUDEOIL26OCT8550CE): the fallback when its list is not read. */
    fun kiteSymbol(name: String, expiry: LocalDate, strike: Double, right: Right?): String =
        name.uppercase(Locale.ROOT) + expiry.format(YYMMM).uppercase(Locale.ENGLISH) + (right?.let { fmtG(strike) + it.name } ?: "FUT")

    /**
     * The MCX futures and options of [names] (all when null) in a GET /instruments/MCX dump. A row with a missing token,
     * expiry or type is skipped, never guessed. The multiplier comes from [multipliers] (exchange token -> units per lot),
     * else the fallback table; a contract of a name in neither is skipped.
     */
    fun parseKite(lines: Sequence<String>, names: Set<String>? = null, multipliers: Map<Long, Int> = emptyMap()): List<McxContract> {
        val out = ArrayList<McxContract>()
        var head: Map<String, Int>? = null
        for (line in lines) {
            if (line.isBlank()) continue
            val c = Kite.splitCsv(line)
            if (head == null) { head = c.withIndex().associate { it.value.trim() to it.index }; continue }
            fun col(n: String) = c.getOrNull(head[n] ?: -1)?.trim() ?: ""
            val seg = col("segment")
            if (seg != "MCX-FUT" && seg != "MCX-OPT") continue
            val name = col("name").uppercase(Locale.ROOT)
            if (names != null && name !in names) continue
            val right = when (col("instrument_type")) { "FUT" -> null; "CE" -> Right.CE; "PE" -> Right.PE; else -> continue }
            if ((seg == "MCX-FUT") != (right == null)) continue
            val token = col("instrument_token").toLongOrNull() ?: continue
            val xt = col("exchange_token").toLongOrNull() ?: continue
            val expiry = runCatching { LocalDate.parse(col("expiry")) }.getOrNull() ?: continue
            val mult = multipliers[xt] ?: Mcx.fallbackMultiplier(name) ?: continue
            val tick = col("tick_size").toDoubleOrNull()?.takeIf { it > 0 }
                ?: Mcx.commodity(name)?.let { if (right == null) it.tick else it.optionTick } ?: 0.05
            out += McxContract(token, xt, col("tradingsymbol"), name, expiry, col("strike").toDoubleOrNull() ?: 0.0, right, tick, mult)
        }
        return out
    }

    /** One contract as Upstox's public MCX list gives it. */
    data class UpstoxRow(val exchangeToken: Long, val name: String, val expiry: LocalDate, val strike: Double, val right: Right?,
                         val multiplier: Int, val tick: Double)

    /**
     * Upstox's public MCX list (assets.upstox.com .../exchange/MCX.json.gz, unzipped): every MCX_FO future and option with
     * its exchange token, multiplier (qty_multiplier: units per lot) and tick (Upstox gives it in paise). The commodity is
     * the first word of its trading symbol ("CRUDEOILM 12700 CE 15 OCT 26"): Upstox's own name field groups the minis with
     * their big contract. Rows that do not read are skipped.
     */
    fun parseUpstox(json: String): List<UpstoxRow> {
        val arr = Json.parse(json) as? List<*> ?: return emptyList()
        val out = ArrayList<UpstoxRow>()
        for (r in arr) {
            val m = r as? Map<*, *> ?: continue
            if (m["segment"] != "MCX_FO") continue
            val right = when (m["instrument_type"]) { "FUT" -> null; "CE" -> Right.CE; "PE" -> Right.PE; else -> continue }
            val xt = (m["exchange_token"] as? String)?.toLongOrNull() ?: (m["exchange_token"] as? Number)?.toLong() ?: continue
            val name = (m["trading_symbol"] as? String)?.trim()?.substringBefore(' ')?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: continue
            val mult = (m["qty_multiplier"] as? Number)?.toDouble()?.takeIf { it >= 1 && it == Math.rint(it) }?.toInt() ?: continue
            val expMs = (m["expiry"] as? Number)?.toLong() ?: continue
            val tick = ((m["tick_size"] as? Number)?.toDouble() ?: 0.0) / 100.0
            out += UpstoxRow(xt, name, Instant.ofEpochMilli(expMs).atZone(IST).toLocalDate(), (m["strike_price"] as? Number)?.toDouble() ?: 0.0,
                right, mult, tick)
        }
        return out
    }

    /** Exchange token -> units per lot, from [parseUpstox]'s rows. */
    fun multipliers(rows: List<UpstoxRow>): Map<Long, Int> = rows.associate { it.exchangeToken to it.multiplier }

    /**
     * Contracts from Upstox's list alone (no Zerodha list read, e.g. never logged in): enough for paper and charts. The
     * Zerodha token is 0 (no live stream for them) and the symbol is Zerodha's naming rule ([kiteSymbol]).
     */
    fun fromUpstox(rows: List<UpstoxRow>, names: Set<String>? = null): List<McxContract> = rows
        .filter { names == null || it.name in names }
        .map { r ->
            val tick = r.tick.takeIf { it > 0 } ?: Mcx.commodity(r.name)?.let { if (r.right == null) it.tick else it.optionTick } ?: 0.05
            McxContract(0L, r.exchangeToken, kiteSymbol(r.name, r.expiry, r.strike, r.right), r.name, r.expiry, r.strike, r.right, tick, r.multiplier)
        }

    /** The next [n] futures of [name] expiring on or after [day], nearest first (near and next month). */
    fun futures(all: List<McxContract>, name: String, day: LocalDate, n: Int = 2): List<McxContract> =
        all.filter { it.isFuture && it.name == name && !it.expiry.isBefore(day) }.sortedBy { it.expiry }.take(n)

    /**
     * The future an option devolves into, which is also the chain's underlying: the nearest future of the same name
     * expiring on or after the option (crude's October options -> October future; gold's October options -> the December
     * future; GOLDM's October options -> the November future).
     */
    fun underlyingFuture(all: List<McxContract>, option: McxContract): McxContract? =
        all.filter { it.isFuture && it.name == option.name && !it.expiry.isBefore(option.expiry) }.minByOrNull { it.expiry }

    /** The nearest option expiry of [name] on or after [day], or null when it has no options listed. */
    fun nearOptionExpiry(all: List<McxContract>, name: String, day: LocalDate): LocalDate? =
        all.filter { it.isOption && it.name == name && !it.expiry.isBefore(day) }.minOfOrNull { it.expiry }

    /** The near-month chain of [name] (every strike, calls and puts), by strike; empty when none is listed. */
    fun nearChain(all: List<McxContract>, name: String, day: LocalDate): List<McxContract> {
        val e = nearOptionExpiry(all, name, day) ?: return emptyList()
        return all.filter { it.isOption && it.name == name && it.expiry == e }.sortedWith(compareBy({ it.strike }, { it.right }))
    }

    /** The names that have options listed on or after [day], [Mcx.CHAIN_FIRST] first, then the table's order. */
    fun chainNames(all: List<McxContract>, day: LocalDate): List<String> {
        val listed = all.filter { it.isOption && !it.expiry.isBefore(day) }.map { it.name }.toSet()
        return (Mcx.CHAIN_FIRST + Mcx.NAMES).distinct().filter { it in listed }
    }

    /** [all] by trading symbol and by paper symbol, for lookups. */
    fun find(all: List<McxContract>, symbol: String): McxContract? =
        all.firstOrNull { it.tradingSymbol.equals(symbol, true) } ?: all.firstOrNull { it.paperSymbol.equals(symbol, true) }
}
