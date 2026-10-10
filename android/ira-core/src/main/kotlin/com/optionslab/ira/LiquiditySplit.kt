package com.optionslab.ira

/**
 * Liquidity 15+5 judged per index (08 Oct, research X1 change 3, Boss's yes): BANKNIFTY carries the arm's edge, while
 * FINNIFTY and MIDCPNIFTY add about zero after the real spread and double the drawdown (h24, h36, h44). One mixed record
 * hid both on 8 Oct, so the paper test, the bots' health and the weekly review count "Liquidity 15+5 BANKNIFTY" on its
 * own, apart from "Liquidity 15+5 FINNIFTY" and "Liquidity 15+5 MIDCPNIFTY". The switch stays one; the paper test's
 * thresholds are unchanged. Pure.
 */
object LiquiditySplit {
    /** The indices Liquidity trades, BANKNIFTY first. */
    val INDICES: List<String> = listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")

    /** The index of an option [symbol] ("BANKNIFTY28OCT2656000CE" -> BANKNIFTY); null for another index or none. */
    fun indexOf(symbol: String): String? {
        val s = symbol.trim().uppercase(java.util.Locale.ENGLISH)
        return INDICES.sortedByDescending { it.length }.firstOrNull { s.startsWith(it) }
    }

    /** "Liquidity 15+5 BANKNIFTY". */
    fun name(index: String): String = "${ArmOwners.LIQUIDITY} $index"

    /** Every per-index name, BANKNIFTY first. */
    val NAMES: List<String> get() = INDICES.map(::name)

    /**
     * Who a trade belongs to for judging: a Liquidity 15+5 trade (any of its books' names) by its index, from its
     * [symbol]; any other [owner] as it is. A Liquidity trade whose index cannot be read keeps the arm's name.
     */
    fun owner(owner: String, symbol: String): String =
        if (ArmOwners.arm(owner) == ArmOwners.LIQUIDITY) indexOf(symbol)?.let(::name) ?: ArmOwners.LIQUIDITY else owner
}
