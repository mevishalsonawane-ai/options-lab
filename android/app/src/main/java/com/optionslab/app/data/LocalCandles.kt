package com.optionslab.app.data

import com.optionslab.engine.Upstox
import com.optionslab.ira.OrderSpeed

/**
 * Local candles for the arms (Boss, 9 Oct, round 2): the candle feed's 1-minute candles of an instrument, with the minutes
 * the feed has not published yet built from Zerodha's stream ([KiteStream.candles], by the exchange's stamp) - so a minute
 * arm (Night, VIX divergence, the MCX paper arms) or a bar-close entry decides at the first tick after the boundary, not
 * a few seconds later when the feed catches up.
 *
 * The feed stays the source of truth: each read lays the feed's finished minutes over the local ones (its values win, and
 * a minute the stream missed is backfilled), and local minutes are added only when they follow the feed's last minute
 * with no gap and each is whole ([com.optionslab.ira.LiveCandles.overlay]); otherwise the feed's candles alone are used,
 * as before. Which one the decision used is told to the order speed card ([OrderTiming.candles]).
 *
 * No network, no vault: an in-memory map. An instrument read here is kept subscribed on the stream (a 3-minute touch,
 * renewed by every read), so the arms' indices, India VIX and MCX futures stream while an arm reads them.
 */
object LocalCandles {
    /** Zerodha's instrument token for the paper feed's key [feedKey] (an index, India VIX, an MCX contract), or null. */
    fun tokenOf(feedKey: String): Long? = runCatching {
        val index = (Upstox.INDEX_KEYS.entries + com.optionslab.engine.orb.LiquidityRules.INDEX_KEYS.entries)
            .firstOrNull { it.value == feedKey }?.key
        if (index != null) return@runCatching Broker.indexToken(index)
        if (feedKey.startsWith("MCX_FO|")) return@runCatching McxMarket.cached().firstOrNull { it.upstoxKey == feedKey }?.token?.takeIf { it > 0 }
        null
    }.getOrNull()

    /**
     * [feed] (the feed's 1-minute candles of [feedKey], finished or not) with the stream's finished minutes after its last
     * (see the class note), at [nowSec]. Tells the current run where its candles came from. Never throws.
     */
    suspend fun overlay(feedKey: String, feed: List<Upstox.Bar>, nowSec: Long): List<Upstox.Bar> {
        val token = tokenOf(feedKey)
        val out = runCatching {
            if (token == null) return@runCatching null
            KiteStream.touch(listOf(token))
            // Only the feed's finished minutes are laid over the local ones (a minute in progress is not the truth yet).
            KiteStream.candles.reconcile(token, feed.filter { it.epochSecond + 60 <= nowSec })
            KiteStream.candles.overlay(token, feed, nowSec)
        }.getOrNull()
        val local = out != null && out.source == com.optionslab.ira.LiveCandles.Source.LOCAL
        runCatching { OrderTiming.candles(if (local) OrderSpeed.Source.LOCAL_CANDLE else OrderSpeed.Source.FEED) }
        return if (local && out != null) out.bars else feed
    }

    /** Whether the local candles can stand for the feed for [token] now: it streams and its candles are being built. */
    fun building(token: Long): Boolean = KiteStream.candles.latest(token) != null && KiteStream.tick(token, 60_000) != null
}
