package com.optionslab.engine.risk

import com.optionslab.engine.Kite
import com.optionslab.engine.NseHours
import com.optionslab.engine.mcx.Mcx
import com.optionslab.engine.mcx.McxSession
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * The live backup for a bot's resting stop (08 Oct, Boss's item 6): a Zerodha GTT (single leg, a SELL) beside the SL order
 * an ORB arm or a Pine script rests at Zerodha, so a stop the exchange did not fill (the price ran through its limit, the
 * order was refused) is still sold.
 *
 * The GTT's trigger is the SL's own LIMIT price, not its trigger: at the same trigger both would fire together and sell
 * twice what is held; under the limit the SL can no longer fill, which is exactly when the backup is needed. It moves up
 * with the stop (the profit lock), never down. When either fires, the other is taken out. Pure: the app places, moves and
 * deletes the orders, behind the live gates.
 */
object LiveBackup {
    private const val EPS = 1e-9

    /**
     * The GTT's trigger for an SL resting at [slTrigger] with its limit at [slLimit] (null or not under the trigger: the
     * limit the bots use, 5% and at least 2 points under it), on the [tick]; null when that is not a price (at or under one
     * tick).
     */
    fun trigger(slTrigger: Double, slLimit: Double?, tick: Double): Double? {
        if (!(slTrigger.isFinite() && slTrigger > 0 && tick > 0)) return null
        val limit = slLimit?.takeIf { it.isFinite() && it > 0 && it < slTrigger - EPS }
            ?: (slTrigger - maxOf(2.0, slTrigger * 0.05))
        val t = Kite.onTick(limit, tick, Kite.Side.SELL)
        return t.takeIf { it > tick + EPS }
    }

    /** Whether a GTT resting at [current] (null: none) is moved to [wanted]: only ever up (or placed when there is none). */
    fun moves(current: Double?, wanted: Double?): Boolean = wanted != null && (current == null || wanted > current + EPS)

    /** What to do with a stop and its backup GTT. */
    enum class Step { KEEP, DROP_GTT, CANCEL_STOP }

    /**
     * [stopStatus]: the SL's status at Zerodha ("TRIGGER PENDING", "OPEN", "COMPLETE", "CANCELLED", ...; null: not found);
     * [gttStatus]: the GTT's ("active", "triggered", ...; null: not found). The GTT fired while the stop still works: the
     * stop comes out. The stop is done (filled, cancelled, gone): the GTT comes out (the position is closed or no longer
     * the bot's). Else both stay.
     */
    fun step(stopStatus: String?, gttStatus: String?): Step {
        val working = stopStatus?.uppercase() in WORKING
        val gtt = gttStatus?.lowercase()
        return when {
            gtt == "triggered" && working -> Step.CANCEL_STOP
            !working && gtt == "active" -> Step.DROP_GTT
            else -> Step.KEEP
        }
    }

    private val WORKING = setOf("OPEN", "TRIGGER PENDING", "MODIFY PENDING", "VALIDATION PENDING", "PUT ORDER REQ RECEIVED", "OPEN PENDING")
}

/**
 * The missed-lock sweeper (08 Oct, Boss's item 7): a position whose price is already at or under the stop or lock resting
 * for it, still open, is sold now - the resting order itself filled at the price (paper) or turned into a sell at a
 * protected limit just under the price (Zerodha), so there is never a second sell in flight. Pure.
 */
object MissedLock {
    private const val EPS = 1e-9

    /** True when a price [ltp] is at or under a stop's [trigger] (an unknown or broken price never is). */
    fun missed(ltp: Double?, trigger: Double?): Boolean =
        ltp != null && trigger != null && ltp.isFinite() && ltp > 0 && trigger.isFinite() && ltp <= trigger + EPS

    /** A sell's protected limit at [ltp]: 3% (at least 1 point) under it, on the [tick], never under one tick. */
    fun limit(ltp: Double, tick: Double): Double =
        Kite.onTick(ltp - maxOf(1.0, ltp * 0.03), tick, Kite.Side.SELL).coerceAtLeast(tick)

    /** "Lock missed at 327.50, sold at 320.00" (Boss's words); [symbol] in front when given. */
    fun say(trigger: Double, sold: Double, symbol: String? = null): String =
        (symbol?.let { "$it: " } ?: "") + "Lock missed at %.2f, sold at %.2f".format(Locale.ENGLISH, trigger, sold)
}

/**
 * The no-price failsafe (08 Oct, Boss's item 8): while a position is open, a price stream silent for more than
 * [REST_AFTER_MS] means prices are read from Zerodha's REST quote instead; no price at all for more than [ALERT_AFTER_MS]
 * is a loud warning: "Positions not protected: no prices since HH:MM". Pure.
 */
object PriceWatch {
    const val REST_AFTER_MS = 30_000L
    const val ALERT_AFTER_MS = 120_000L

    /** True when the stream's last tick ([lastTickMs]; null: none) is too old to trust at [nowMs]: read REST quotes. */
    fun useRest(lastTickMs: Long?, nowMs: Long): Boolean = lastTickMs == null || nowMs - lastTickMs > REST_AFTER_MS

    /**
     * True when the positions have had no price for too long: the last price ([lastPriceMs]; null: none yet) or, before
     * any, since they were first seen open ([openSinceMs]) is more than [ALERT_AFTER_MS] before [nowMs].
     */
    fun alert(lastPriceMs: Long?, openSinceMs: Long, nowMs: Long): Boolean = nowMs - maxOf(lastPriceMs ?: openSinceMs, openSinceMs) > ALERT_AFTER_MS

    /** "Positions not protected: no prices since 14:11". */
    fun say(since: LocalTime): String = "Positions not protected: no prices since %02d:%02d".format(Locale.ENGLISH, since.hour, since.minute)

    /** An open position as the failsafe sees it: its price [key], its [exchange] ("NFO", "MCX") and its option's [expiry] when known. */
    data class Held(val key: String, val exchange: String, val expiry: LocalDate? = null)

    /**
     * 15 Oct (Boss saw "Positions not protected" before 09:15): whether a position on [exchange] is judged at [at] (IST) -
     * only while its own market trades. MCX on its calendar ([mcx]: 09:00 to its close, its own holidays); NSE's F&O as
     * the app's "Market open" says ([nseOpen] (day, minute of the day): a trading day on the app's holiday calendar, 09:15
     * to its close). Before the open, after the close and on a holiday no price comes (the paper feed has no candle of the
     * day before 09:15), so the silence is no fault; from 09:00 the MCX pass ran this check with an overnight NSE position
     * in the paper book and warned at 09:02. An option past its [expiry] day is settled, never priced again. Only the
     * warning is judged here: the positions' stops and the sweep itself run as before, whatever the hour.
     */
    fun inSession(exchange: String, at: LocalDateTime, nseOpen: (LocalDate, Int) -> Boolean, mcx: McxSession.Calendar,
                  expiry: LocalDate? = null): Boolean {
        val day = at.toLocalDate()
        if (expiry != null && expiry.isBefore(day)) return false
        if (exchange.equals(Mcx.EXCHANGE, ignoreCase = true)) return mcx.isOpen(at)
        return nseOpen(day, at.hour * 60 + at.minute)
    }

    /** [inSession]'s NSE rule from a trading-day calendar: F&O's hours ([NseHours.foOpen]) on a trading day. */
    fun nseOpen(tradingDay: (LocalDate) -> Boolean): (LocalDate, Int) -> Boolean = { d, minute -> tradingDay(d) && NseHours.foOpen(d, minute) }

    /**
     * One pass's input to [Failsafe.look]: (holding, priced) over the [held] positions whose market trades at [at] only
     * ([inSession]). [quoted]: the keys that had a price this pass. Priced when any of them had one, or with [all] when
     * every one did (Zerodha's REST quotes: one read for all).
     */
    fun judge(held: List<Held>, quoted: Set<String>, at: LocalDateTime, nseOpen: (LocalDate, Int) -> Boolean,
              mcx: McxSession.Calendar, all: Boolean = false): Pair<Boolean, Boolean> {
        val open = held.filter { inSession(it.exchange, at, nseOpen, mcx, it.expiry) }
        if (open.isEmpty()) return false to false
        return true to (if (all) open.all { it.key in quoted } else open.any { it.key in quoted })
    }

    /**
     * The books (the paper account, Zerodha) together, each one's [judge]: holding when any holds in its session, priced
     * only when every one that holds is priced - a priced paper book never hides a live position with no price (15 Oct).
     */
    fun combine(books: List<Pair<Boolean, Boolean>>): Pair<Boolean, Boolean> {
        val holding = books.any { it.first }
        return holding to (holding && books.all { !it.first || it.second })
    }

    /**
     * The failsafe's memory (15 Oct; it was three fields of the sweeper): when the positions were first seen open in their
     * session, the last pass that priced them, and the silence already said. Thread-safe.
     */
    class Failsafe {
        private var openSince: Long? = null
        private var lastPrice: Long? = null
        private var alertedFor: Long? = null

        /**
         * One pass at [nowMs]. [holding]: a position whose market trades now is open (nothing held, or nothing in its
         * session: the clock is forgotten). [priced]: every book holding one had a price ([combine]). Returns when the
         * silence began (epoch ms) when it is to be said now (once per silence); else null.
         */
        @Synchronized fun look(holding: Boolean, priced: Boolean, nowMs: Long): Long? {
            if (!holding) { openSince = null; alertedFor = null; return null }
            val since = openSince ?: nowMs.also { openSince = it }
            if (priced) { lastPrice = nowMs; alertedFor = null; return null }
            if (!alert(lastPrice, since, nowMs)) return null
            val from = maxOf(lastPrice ?: since, since)
            if (alertedFor == from) return null
            alertedFor = from
            return from
        }
    }
}
