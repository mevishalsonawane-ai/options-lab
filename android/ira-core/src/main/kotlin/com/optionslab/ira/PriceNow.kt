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
 *  - a quote with no last price gives its bid only on a sane book ([BookPrice.judge]: both sides, not crossed, tight) ([bookBid]);
 *  - a candle close counts only from a minute at or after the entry minute ([candleCounts]).
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
        val v = BookPrice.judge(bid, ask)
        if (!v.ok) return false
        val b = v.bid ?: return false; val a = v.ask ?: return false
        return l < b * (1 - FAR) || l > a * (1 + FAR)
    }

    /** [farFromBook]: a last price this share outside the book is not the price now. */
    const val FAR = 0.10

    /** What a held long is worth on a book with no last price: its [bid], only when the book is sane; null otherwise. */
    fun bookBid(bid: Double?, ask: Double?): Double? = BookPrice.judge(bid, ask).takeIf { it.ok }?.bid

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
