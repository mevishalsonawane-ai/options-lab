package com.optionslab.engine

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

/**
 * Expiry-day short put. Port of `strategy/expiry_put.py`.
 *
 *     On a NIFTY expiry day, at 11:00 IST, sell one put at the nearest listed
 *     strike to F x (1 - otm_pct), where F is the put-call-parity forward.
 *     Hold to cash settlement. Never buy it back.
 *
 * The record is the statement "NIFTY did not fall 1% between 11:00 and the
 * close on 170 consecutive expiries" - a property of a 2023-2026 bull market,
 * not of the payoff. The loss is unbounded; size accordingly.
 */
object ExpiryPut {
    const val DEFAULT_OTM_PCT = 0.0075
    const val DEFAULT_N_STRIKES = 10
    const val SETTLE_FROM = 15 * 60          // NSE settles on the last-30-minute average
    const val SETTLE_TO = 15 * 60 + 30
    const val DEFAULT_ENTRY = 11 * 60
    const val DEFAULT_HOLDOUT_SESSIONS = 30

    class ThinChain(msg: String) : IllegalArgumentException(msg)
    class NotASnapshot(msg: String) : IllegalArgumentException(msg)
    class SessionSkipped(msg: String) : Exception(msg)

    /** One contract's price at one moment - a row of a snapshot. */
    data class Quote(val strike: Double, val right: Right, val close: Double)

    /** F = median over the n nearest strikes of (K + CE - PE). Read off the OPTIONS, never the future. */
    fun parityForward(snapshot: List<Quote>, nStrikes: Int = DEFAULT_N_STRIKES): Double {
        val seen = HashMap<Pair<Double, Right>, Int>()
        for (q in snapshot) seen.merge(q.strike to q.right, 1, Int::plus)
        val dupes = seen.filter { it.value > 1 }
        if (dupes.isNotEmpty()) {
            val offenders = dupes.keys.map { it.first }.distinct().sorted().take(3)
            throw NotASnapshot(
                "chain is not a snapshot: strikes $offenders appear more than once (${dupes.values.sum()} duplicated rows). " +
                    "Reduce to one timestamp per contract before computing the forward.")
        }
        val ce = LinkedHashMap<Double, Double>()
        val pe = LinkedHashMap<Double, Double>()
        for (q in snapshot) when (q.right) {
            Right.CE -> ce[q.strike] = q.close
            Right.PE -> pe[q.strike] = q.close
            Right.IX -> {}
        }
        val usable = ce.keys.filter { it in pe }.sorted().filter { ce.getValue(it) > 0 && pe.getValue(it) > 0 }
        if (usable.size < nStrikes) throw ThinChain("${usable.size} strikes quote both sides; need $nStrikes")
        val implied = usable.map { k -> k to (k + ce.getValue(k) - pe.getValue(k)) }
        val centre = Stats.median(implied.map { it.second })
        val nearest = implied.sortedBy { abs(it.first - centre) }.take(nStrikes)
        return Stats.median(nearest.map { it.second })
    }

    /** The NEAREST listed strike to the target; ties break to the lower strike. */
    fun selectStrike(strikes: Collection<Double>, forward: Double, otmPct: Double = DEFAULT_OTM_PCT): Double {
        require(strikes.isNotEmpty()) { "empty strike ladder" }
        val target = forward * (1.0 - otmPct)
        return strikes.sorted().minWith(compareBy<Double>({ abs(it - target) }, { it }))
    }

    /** NSE settles on the average of spot over 15:00-15:29, not the 15:29 print. */
    fun settlementPrice(spot: Map<Int, Double>): Double {
        val window = spot.filterKeys { it in SETTLE_FROM until SETTLE_TO }.values
        if (window.isEmpty()) throw IllegalArgumentException("no bars in the settlement window")
        return window.average()
    }

    data class Trade(
        val session: LocalDate?,
        val strike: Double,
        val credit: Double,          // net credit per unit
        val settlement: Double,
        val intrinsic: Double,
        val lotSize: Int,
        val lots: Int,
        val qty: Int,
        val wingStrike: Double?,
        val wingDebit: Double,
        val wingIntrinsic: Double,
        val maxLoss: Double?,
        val grossPnl: Double,
        val cost: Double,
        val netPnl: Double,
        val forward: Double = Double.NaN,
        val otmRealised: Double = Double.NaN,
    ) {
        val won: Boolean get() = netPnl > 0
    }

    /** P&L of one short put - naked, or spread against a bought wing. */
    fun settleTrade(
        strike: Double, credit: Double, settlement: Double, lotSize: Int, lots: Int = 1,
        regime: String = "quoted", wingStrike: Double? = null, wingDebit: Double = 0.0,
    ): Trade {
        val qty = lotSize * lots
        val shortIntrinsic = max(strike - settlement, 0.0)
        var cost = Costs.sellToSettle(credit, lotSize, lots, regime).total
        var wingIntrinsic = 0.0
        if (wingStrike != null) {
            wingIntrinsic = max(wingStrike - settlement, 0.0)
            cost += Costs.buyToSettle(wingDebit, lotSize, lots, regime, wingIntrinsic).total
        }
        val netCredit = credit - wingDebit
        val intrinsic = shortIntrinsic - wingIntrinsic
        val gross = (netCredit - intrinsic) * qty
        val net = gross - cost
        return Trade(
            session = null, strike = strike, credit = netCredit, settlement = settlement,
            intrinsic = intrinsic, lotSize = lotSize, lots = lots, qty = qty,
            wingStrike = wingStrike, wingDebit = wingDebit, wingIntrinsic = wingIntrinsic,
            maxLoss = if (wingStrike == null) null else (strike - wingStrike - netCredit) * qty + cost,
            grossPnl = gross, cost = cost, netPnl = net,
        )
    }

    /** The latest bar per (strike, right) at or before [minute]. */
    fun snapshot(chain: List<Series>, minute: Int): List<Quote> = chain.mapNotNull { s ->
        if (s.right == Right.IX) return@mapNotNull null
        val i = s.lastAtOrBefore(minute)
        if (i < 0) null else Quote(s.strike, s.right, s.close[i])
    }.sortedWith(compareBy({ it.strike }, { it.right.ordinal }))

    fun minuteSlice(chain: List<Series>, minute: Int): List<Quote> = chain.mapNotNull { s ->
        if (s.right == Right.IX) return@mapNotNull null
        val i = s.indexOf(minute)
        if (i < 0) null else Quote(s.strike, s.right, s.close[i])
    }

    /** "dated": each session's own lot - the chain first, the NSE table as fallback. */
    fun datedLot(session: Session, underlying: String = "NIFTY"): Int =
        session.lotHint ?: Lots.lotFromChain(session.series) ?: Lots.lotSizeOn(underlying, session.day)

    sealed interface LotChoice {
        data class Pinned(val lot: Int) : LotChoice
        data object Dated : LotChoice
    }

    data class Params(
        val otmPct: Double = DEFAULT_OTM_PCT,
        val entryMinute: Int = DEFAULT_ENTRY,
        val lots: Int = 1,
        val regime: String = "quoted",
        val wingPct: Double? = null,
        val lot: LotChoice = LotChoice.Pinned(65),
        val underlying: String = "NIFTY",
    )

    /** One expiry session -> one trade. Throws SessionSkipped with a reason. */
    fun runSession(day: LocalDate, chain: List<Series>, lotSize: Int, p: Params): Trade {
        val opts = chain.filter { it.right != Right.IX }
        if (opts.none { s -> s.size > 0 && s.minutes[0] <= p.entryMinute }) {
            throw SessionSkipped("no bars at or before ${minuteText(p.entryMinute)}")
        }
        val snap = snapshot(opts, p.entryMinute)
        val forward = parityForward(snap)
        val strikes = snap.map { it.strike }.distinct()
        val strike = selectStrike(strikes, forward, p.otmPct)
        val leg = snap.firstOrNull { it.strike == strike && it.right == Right.PE }
        if (leg == null || leg.close <= 0) throw SessionSkipped("no PE quote at strike ${fmtG(strike)}")
        val credit = leg.close

        val windowMinutes = opts.flatMap { s -> s.minutes.filter { it in SETTLE_FROM until SETTLE_TO } }.toSortedSet()
        if (windowMinutes.isEmpty()) throw SessionSkipped("no bars in the settlement window")
        val forwards = ArrayList<Double>()
        for (m in windowMinutes) {
            try { forwards += parityForward(minuteSlice(opts, m)) } catch (_: ThinChain) {} catch (_: NotASnapshot) {}
        }
        if (forwards.isEmpty()) throw SessionSkipped("settlement window too thin to price")

        var wingStrike: Double? = null
        var wingDebit: Double? = null
        if (p.wingPct != null) {
            require(p.wingPct >= 0) {
                "wing_pct must be positive; ${p.wingPct} would buy a put ABOVE the short strike, which is not a hedge"
            }
            val ws = selectStrike(strikes, forward, p.otmPct + p.wingPct)
            if (ws >= strike) throw SessionSkipped(
                "wing at ${fmtG(ws)} is not below the short strike ${fmtG(strike)}; a zero-width spread is two orders and no hedge")
            val wl = snap.firstOrNull { it.strike == ws && it.right == Right.PE }
            if (wl == null || wl.close <= 0) throw SessionSkipped("no PE quote at wing strike ${fmtG(ws)}")
            wingStrike = ws
            wingDebit = wl.close
        }
        val t = settleTrade(strike, credit, forwards.average(), lotSize, p.lots, p.regime, wingStrike, wingDebit ?: 0.0)
        return t.copy(session = day, forward = forward, otmRealised = (forward - strike) / forward)
    }

    data class Skip(val day: LocalDate, val why: String)

    /** Run every session. Skips are never silent. */
    fun runBacktest(sessions: List<Session>, p: Params): Pair<List<Trade>, List<Skip>> {
        val trades = ArrayList<Trade>()
        val skipped = ArrayList<Skip>()
        for (s in sessions) {
            try {
                val lot = when (val l = p.lot) {
                    is LotChoice.Pinned -> l.lot
                    LotChoice.Dated -> datedLot(s, p.underlying)
                }
                trades += runSession(s.day, s.series, lot, p)
            } catch (e: SessionSkipped) {
                skipped += Skip(s.day, (e.message ?: "").take(80))
            } catch (e: ThinChain) {
                skipped += Skip(s.day, (e.message ?: "").take(80))
            } catch (e: NotASnapshot) {
                skipped += Skip(s.day, (e.message ?: "").take(80))
            } catch (e: Lots.NoLotSize) {
                skipped += Skip(s.day, (e.message ?: "").take(80))
            }
        }
        return trades to skipped
    }

    /** Seal the most recent `holdout` sessions. Chronological, never random. */
    fun splitSessions(days: List<LocalDate>, holdout: Int = DEFAULT_HOLDOUT_SESSIONS): Pair<List<LocalDate>, List<LocalDate>> {
        val ordered = days.toSortedSet().toList()
        require(holdout < ordered.size) {
            "holdout of $holdout leaves no training sessions out of ${ordered.size}; a split needs data on both sides"
        }
        return ordered.dropLast(holdout) to ordered.takeLast(holdout)
    }
}
