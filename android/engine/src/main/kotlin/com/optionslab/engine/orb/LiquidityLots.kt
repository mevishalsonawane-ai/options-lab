package com.optionslab.engine.orb

/**
 * Liquidity 15+5's size (Boss's choice, 06 Oct: run only Liquidity, at 2-3 lots): how many lots each NEW entry of its four
 * books buys - 1, 2 or 3 - each book's quantity being that many of its own contract's lot (BANKNIFTY's, FINNIFTY's). An open
 * position keeps the quantity it was bought with; its 15% resting stop and every exit always cover all of it.
 *
 *  - A book saved before the setting existed takes [DEFAULT] (2, Boss's 06 Oct choice), said once in the arm log ([MIGRATED]).
 *  - Paper has no further limit. Live (only when Boss arms it live with his PIN) still goes through the account guard: when
 *    the Bot settings' max lots is below the setting, the entry is refused by name ([liveRefusal]) - never sent smaller.
 *  - Raising it is Boss's alone, asked and confirmed ([raises]); lowering may apply at once.
 *  - A restore never raises it above what this phone's book has: the backup's higher value waits for Boss ([restored]).
 *
 * Pure: no clock, no storage.
 */
object LiquidityLots {
    /** The choices on the row and by voice. */
    val CHOICES: List<Int> = listOf(1, 2, 3)
    /** Boss's 06 Oct choice: the size of a book saved before the setting existed. */
    const val DEFAULT = 2
    /** The one-time change's key in the arms' book. */
    const val MIGRATION = "liquidity_lots_2026_10_06"
    /** What the arm log says, once, when a book takes [DEFAULT]. */
    const val MIGRATED = "Liquidity 15+5: size set to $DEFAULT lots a trade (Boss's 06 Oct choice; no size was saved). " +
        "New entries buy $DEFAULT lots of each contract's lot; an open position keeps its own quantity."

    fun valid(lots: Int): Boolean = lots in CHOICES

    /** A saved value, or null when it is not one of the [CHOICES] (then the book takes [DEFAULT]). */
    fun of(saved: Int?): Int? = saved?.takeIf { valid(it) }

    /** The quantity a new entry buys: [lots] of a contract whose lot is [lotSize] units. */
    fun qty(lots: Int, lotSize: Int): Int {
        require(valid(lots)) { "Liquidity 15+5 trades 1, 2 or 3 lots, not $lots" }
        require(lotSize > 0) { "a lot size must be positive, not $lotSize" }
        return lots * lotSize
    }

    /** Whether going from [old] to [new] adds size (Boss confirms it; lowering may apply at once). */
    fun raises(old: Int, new: Int): Boolean = new > old

    /**
     * Why a LIVE entry at [lots] may not be sent while the Bot settings allow [maxLots] lots (0 or less: no cap), or null when
     * it may. Said plainly, never traded smaller.
     */
    fun liveRefusal(lots: Int, maxLots: Int): String? =
        if (maxLots in 1 until lots) "refused: Bot settings allow $maxLots lot${if (maxLots == 1) "" else "s"}; Liquidity is set to $lots. " +
            "Nothing was bought - raise the max lots in Bot settings or lower Liquidity's lots." else null

    /**
     * A restore: the book here has [current] lots (null: no book yet, so [DEFAULT]); the backup had [backup] (null: none saved,
     * so [DEFAULT]). The size kept, and the backup's higher size waiting for Boss (null: nothing waits).
     */
    fun restored(current: Int?, backup: Int?): Pair<Int, Int?> {
        val cur = of(current) ?: DEFAULT
        val b = of(backup) ?: DEFAULT
        return if (b <= cur) b to null else cur to b
    }

    /** Per lot: [net] rupees of a trade of [lots] lots (a trade before the setting, or not known: 1 lot). */
    fun perLot(net: Double, lots: Double): Double = if (lots > 0) net / lots else net

    /** "2 lots", "1 lot". */
    fun words(lots: Int): String = "$lots lot${if (lots == 1) "" else "s"}"

    /** The row's line: the size and what it means for each book's quantity ([lotSizes]: underlying -> its lot, when known). */
    fun line(lots: Int, lotSizes: Map<String, Int>): String =
        "Size: ${words(lots)} a trade" + (if (lotSizes.isEmpty()) "" else " (" + lotSizes.entries.joinToString(", ") { (u, l) -> "$u ${lots * l}" } + " qty)") +
            " · an open position keeps its own"
}
