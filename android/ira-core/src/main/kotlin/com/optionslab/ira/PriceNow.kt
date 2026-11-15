package com.optionslab.ira

/**
 * 15 Nov 2026 (Boss: "Yes fix it", after R182's look at R51 / R72's paper trades): which price may stand for "the price now" when a
 * held option's stop, lock or target is judged between minutes.
 *
 * Why: five FINNIFTY paper trades (5, 11 and 13 Nov) were bought near 430 - 460 and sold seconds later at 68.70, 265.00 and 354.40
 * "by their stop" while the index had hardly moved - Rs 52.8k of R51 and R72's Rs 68k paper loss in those weeks. A thin monthly option
 * often has not traded yet at 09:20. Its "last price" is then a trade from an earlier day (the index was somewhere else), and with no
 * last price at all the quote's price is its bid, which in an empty book can be a stray order far below the ask. Neither is a price
 * the option traded at since the entry, and an exchange stop triggers on trades only.
 *
 * So, for a position entered in the minute starting [entryMinuteSec] (epoch seconds):
 *  - a last price counts only when its trade is from that minute or later ([lastCounts]; a packet with no trade time is taken as
 *    before, since nothing can be told);
 *  - a quote with no last price gives its bid only on a real two-sided book (both sides, not crossed, no stray bid) ([bookBid]);
 *  - a candle close counts only from a minute at or after the entry minute ([candleCounts]).
 * The same test guards the stream's price path ([newTrade]: a resting paper stop fills on new trades only), the resting paper stops
 * themselves ([com.optionslab.app.data.Paper]: held while the only price is not one) and the live stops' quotes ([stopLast]).
 * With none of them there is no price now: the stop is judged again on the next look. The clock's exits (time stop, square-off) and
 * the minutes' own walk are not affected, and a live trade's stop rests at Zerodha as before. Pure: no clock, disk or network.
 */
object PriceNow {
    /** A trade stamped this many seconds before the entry minute still counts (the phone's clock against the exchange's). */
    const val SLACK_SEC = 60L

    /**
     * Whether [last], whose trade was at [lastTradeSec] (epoch seconds; null or 0: the packet carries none), may be judged as the
     * price now for a position entered in the minute starting [entryMinuteSec].
     */
    fun lastCounts(last: Double?, lastTradeSec: Long?, entryMinuteSec: Long): Boolean {
        if (last == null || !last.isFinite() || last <= 0) return false
        if (lastTradeSec == null || lastTradeSec <= 0) return true
        return lastTradeSec >= entryMinuteSec - SLACK_SEC
    }

    /**
     * Whether [last] cannot be told to be a trade since the entry (its packet carries no trade time: [lastTradeSec] null or 0) and
     * lies more than [FAR] outside a sane book ([bid] / [ask]) - then the book, not that last price, is the price now (review: a
     * packet with no trade time must not let an old last price through).
     */
    fun farFromBook(last: Double?, lastTradeSec: Long?, bid: Double?, ask: Double?): Boolean {
        if (lastTradeSec != null && lastTradeSec > 0) return false
        val l = last?.takeIf { it.isFinite() && it > 0 } ?: return false
        val b = bookBid(bid, ask) ?: return false
        val a = ask ?: return false
        return l < b * (1 - FAR) || l > a * (1 + FAR)
    }

    /** [farFromBook]: a last price this share outside the book is not the price now. */
    const val FAR = 0.10

    /**
     * What a held long is worth on a book with no last price: its [bid], when the book is a real two-sided one - both sides there,
     * not crossed, and the bid within [STRAY] of the ask. Review: wider than an ENTRY's test ([BookPrice.judge], 0.5%) on purpose - a
     * stop must still act on a wide but real book (95 / 96, or 60 / 66 after a fall); only a stray bid far under the ask (68.70 under
     * 460.75) is no price. Null otherwise.
     */
    fun bookBid(bid: Double?, ask: Double?): Double? {
        val b = bid?.takeIf { it.isFinite() && it > 0 } ?: return null
        val a = ask?.takeIf { it.isFinite() && it > 0 } ?: return null
        return b.takeIf { a >= b && b >= a * (1 - STRAY) }
    }

    /** [bookBid]: a bid more than this share under the ask is a stray order, not what the option is worth. */
    const val STRAY = 0.15

    /**
     * The price a stop, target or lock of a LIVE position is judged on, from a quote's [last], [bid] and [ask] (no trade time is
     * known there): the last price, unless it lies far outside a sane book ([farFromBook]) - then the book's bid. So an old last
     * price of a thin option neither sells a live trade nor moves its stop.
     */
    fun stopLast(last: Double, bid: Double?, ask: Double?): Double =
        if (farFromBook(last, null, bid, ask)) bookBid(bid, ask) ?: last else last

    /**
     * Whether a stream packet's [last] price, traded at [tradeSec] (epoch seconds; null or 0: the packet carries none), is a NEW
     * trade for the price path a resting stop is filled from - not the same old trade sent again with a changed book. [prevLast] /
     * [prevTradeSec]: the packet before it ([hadPrev] false: none; or it had no trade time - then only a trade of the last
     * [RECENT_SEC] before [nowSec] counts, so the first packet of a contract that last traded another day adds nothing). No trade time: taken as before.
     */
    fun newTrade(last: Double, tradeSec: Long?, hadPrev: Boolean, prevLast: Double?, prevTradeSec: Long?, nowSec: Long): Boolean {
        if (!(last.isFinite() && last > 0)) return false
        if (tradeSec == null || tradeSec <= 0) return true
        val pt = prevTradeSec?.takeIf { it > 0 }
        // Nothing to compare with (no packet before, or one with no trade - the option had no last price yet): only a recent trade.
        if (!hadPrev || pt == null) return tradeSec >= nowSec - RECENT_SEC
        return tradeSec != pt || prevLast == null || kotlin.math.abs(last - prevLast) > 1e-9
    }

    /** [newTrade]: how old a contract's first packet's trade may be to count. */
    const val RECENT_SEC = 120L

    /** A last price traded more than this long before its packet's own stamp is another session's: never a candle ([anotherSession]). */
    const val SESSION_SEC = 6 * 3600L

    /** Whether a packet stamped [stampSec] carries a last price of another session (its trade at [tradeSec]; null or 0: not known, false). */
    fun anotherSession(tradeSec: Long?, stampSec: Long): Boolean = tradeSec != null && tradeSec > 0 && tradeSec < stampSec - SESSION_SEC

    /** Whether a candle that started at [candleSec] (epoch seconds; null: none known) is from the entry minute or later. */
    fun candleCounts(candleSec: Long?, entryMinuteSec: Long): Boolean = candleSec != null && candleSec >= entryMinuteSec

    /**
     * The price now from a quote read as the last resort: [ltp] with [noLast] (its price is the book's bid), its [bid] and [ask].
     * [staleLast]: the stream's last price that did NOT count just now (null: none) - a quote that only repeats it is no price
     * either. [candleSec]: the start of the newest candle the quote may have been read from (null: none known). Null: no price now.
     */
    fun ofQuote(ltp: Double?, noLast: Boolean, bid: Double?, ask: Double?, staleLast: Double?, candleSec: Long?, entryMinuteSec: Long): Double? {
        if (noLast) return bookBid(bid, ask)
        val p = ltp?.takeIf { it.isFinite() && it > 0 } ?: return null
        if (staleLast != null && kotlin.math.abs(p - staleLast) < 1e-9) return null
        return p.takeIf { candleCounts(candleSec, entryMinuteSec) }
    }
}
