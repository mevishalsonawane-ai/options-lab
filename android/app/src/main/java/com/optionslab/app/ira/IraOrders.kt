package com.optionslab.app.ira

import com.optionslab.engine.Right
import com.optionslab.ira.OrderRequest
import java.time.LocalDate
import java.util.Locale

/**
 * An order asked of Ira, turned into one listed contract for the app's own order paths. Nothing here sends anything:
 * Paper places only after the owner's Confirm on the Ira screen, Live opens the order review (swipe and PIN).
 */
object IraOrders {
    /** One listed option: what both contract lists (Zerodha's in Live, the paper master) are reduced to. */
    data class Listed(val expiry: LocalDate, val strike: Double, val lotSize: Int)

    /** The contract to review: the nearest expiry, the asked (or at-the-money) strike. */
    data class Ticket(val underlying: String, val expiry: LocalDate, val strike: Double, val right: Right, val lots: Int,
                      val buy: Boolean, val lotSize: Int) {
        val title: String get() = "${if (buy) "BUY" else "SELL"} $lots lot${if (lots > 1) "s" else ""} $underlying " +
            "${expiry.dayOfMonth} ${expiry.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).uppercase()} " +
            "${com.optionslab.engine.fmtG(strike)} ${right.name}"
    }

    /**
     * Pure: [o] against the [listed] options of its index and right. The nearest expiry from [today]; a named strike
     * must be listed for it (never moved to another), ATM is the listed strike nearest [spot]; lots within [maxLots].
     */
    fun pick(o: OrderRequest, listed: List<Listed>, spot: Double?, today: LocalDate, maxLots: Int): Result<Ticket> = runCatching {
        o.refusal?.let { error(it) }
        require(o.missing.isEmpty()) { "I still need: ${o.missing.joinToString(", ")}." }
        val lots = o.lots!!
        require(lots >= 1) { "At least one lot." }
        require(maxLots <= 0 || lots <= maxLots) { "$lots lots is over your limit of $maxLots an order (Risk settings)." }
        val name = o.market!!.name
        val right = if (o.right == "CE") Right.CE else Right.PE
        val expiry = listed.map { it.expiry }.filter { !it.isBefore(today) }.minOrNull() ?: error("No $name options are listed right now.")
        val strikes = listed.filter { it.expiry == expiry }
        val got = if (o.atm) {
            val s = spot ?: error("I don't have the $name price yet to find the ATM strike; name the strike.")
            strikes.minByOrNull { kotlin.math.abs(it.strike - s) }!!
        } else {
            strikes.firstOrNull { it.strike == o.strike!!.toDouble() } ?: run {
                val near = strikes.map { it.strike }.sortedBy { kotlin.math.abs(it - o.strike!!) }.take(2).sorted()
                error("${o.strike} is not a listed $name strike for the $expiry expiry. Nearest: ${near.joinToString(" and ") { com.optionslab.engine.fmtG(it) }}.")
            }
        }
        Ticket(name, expiry, got.strike, right, lots, o.buy, got.lotSize)
    }

    /** Test seam (DEBUG): the listed options instead of Zerodha's or the paper master. */
    @Volatile internal var testListed: ((underlying: String, right: Right, live: Boolean) -> List<Listed>)? = null

    /** Reads the listed options (Zerodha's in Live, the paper master otherwise) and picks. Blocking: call off the main thread. */
    suspend fun prepare(o: OrderRequest, live: Boolean, maxLots: Int, spot: Double?): Result<Ticket> {
        o.refusal?.let { return Result.failure(IllegalStateException(it)) }
        if (o.missing.isNotEmpty() || o.market == null) return pick(o, emptyList(), spot, com.optionslab.app.data.Market.today(), maxLots)
        val name = o.market!!.name
        val right = if (o.right == "CE") Right.CE else Right.PE
        val listed = try {
            testListed?.takeIf { com.optionslab.app.BuildConfig.DEBUG }?.invoke(name, right, live) ?: if (live)
                com.optionslab.app.data.Broker.instruments().filter { it.name == name && it.right == right }.map { Listed(it.expiry, it.strike, it.lotSize) }
            else
                com.optionslab.app.data.Market.contracts().filter { it.underlying == name && it.right == right }.map { Listed(it.expiry, it.strike, it.lotSize) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { return Result.failure(IllegalStateException("Could not read the contract list: ${e.message}")) }
        return pick(o, listed, spot, com.optionslab.app.data.Market.today(), maxLots)
    }
}
