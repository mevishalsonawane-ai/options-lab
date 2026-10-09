package com.optionslab.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kite Connect's WebSocket market data (wss://ws.kite.trade), decoded.
 *
 * A binary message is: a 2-byte packet count, then for each packet a 2-byte
 * length and the packet. All integers are big-endian; prices are in paise
 * (x100) except currency (x10,000,000) and BSE currency (x10,000). A 1-byte
 * message is the server's heartbeat. The packet length says what it is:
 *
 *    8  LTP            token, last price
 *   28  index quote    token, ltp, high, low, open, close, change
 *   32  index full     ... + exchange timestamp
 *   44  quote          token, ltp, last qty, avg price, volume, buy qty, sell qty, open, high, low, close
 *  184  full           ... + last trade time, OI, OI high, OI low, exchange timestamp, 5+5 depth
 *
 * Pure: no socket, no Android. The app's KiteStream owns the connection.
 */
object KiteTicks {
    data class Tick(
        val token: Long,
        val last: Double,
        val open: Double = 0.0,
        val high: Double = 0.0,
        val low: Double = 0.0,
        /** Previous day's close. */
        val close: Double = 0.0,
        val volume: Long = 0,
        val oi: Long = 0,
        val bid: Double? = null,
        val ask: Double? = null,
        /** Seconds since the epoch of the exchange's stamp, when the packet carries one. */
        val exchangeTime: Long? = null,
        val tradable: Boolean = true,
        /** Quantity at the best bid and the best offer (full mode only; null when the packet has no depth). */
        val bidQty: Long? = null,
        val askQty: Long? = null,
        /** Total quantity bid and offered across the whole book (quote and full mode; null in an LTP or index packet). */
        val buyQty: Long? = null,
        val sellQty: Long? = null,
        /** The last trade's quantity (quote and full mode; null in an LTP or index packet). */
        val lastQty: Long? = null,
        /** Seconds since the epoch of the last trade (full mode only). */
        val lastTradeTime: Long? = null,
        /** The five best bids and offers (full mode only; null otherwise): the order flow's book. */
        val depth: Depth? = null,
    ) {
        val changePct: Double get() = if (close > 0) (last - close) / close else 0.0
    }

    /** One level of the book: its price, the quantity resting there and how many orders make it up. */
    data class Level(val price: Double, val qty: Long, val orders: Int)

    /** Five bids (best first) and five offers (best first), empty levels (price 0) left out. */
    data class Depth(val bids: List<Level>, val asks: List<Level>) {
        val bidQty: Long get() = bids.sumOf { it.qty }
        val askQty: Long get() = asks.sumOf { it.qty }
    }

    private const val SEG_CDS = 3
    private const val SEG_BCD = 6
    private const val SEG_INDICES = 9

    fun divisor(token: Long): Double = when ((token and 0xff).toInt()) {
        SEG_CDS -> 10_000_000.0
        SEG_BCD -> 10_000.0
        else -> 100.0
    }

    /** Every tick in one binary message; a heartbeat or anything malformed gives none. */
    fun parse(message: ByteArray): List<Tick> {
        if (message.size < 2) return emptyList()
        val b = ByteBuffer.wrap(message).order(ByteOrder.BIG_ENDIAN)
        val count = b.short.toInt() and 0xffff
        val out = ArrayList<Tick>(count)
        repeat(count) {
            if (b.remaining() < 2) return out
            val len = b.short.toInt() and 0xffff
            if (b.remaining() < len) return out
            val packet = ByteArray(len).also { b.get(it) }
            packet(packet)?.let(out::add)
        }
        return out
    }

    fun packet(p: ByteArray): Tick? {
        val b = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN)
        fun int(at: Int) = b.getInt(at).toLong() and 0xffffffffL
        fun signed(at: Int) = b.getInt(at).toLong()
        if (p.size < 8) return null
        val token = int(0)
        val d = divisor(token)
        fun px(at: Int) = signed(at) / d
        val index = (token and 0xff).toInt() == SEG_INDICES
        return when {
            p.size == 8 -> Tick(token, px(4), tradable = !index)
            index && (p.size == 28 || p.size == 32) -> Tick(
                token, last = px(4), high = px(8), low = px(12), open = px(16), close = px(20),
                exchangeTime = if (p.size == 32) int(28) else null, tradable = false,
            )
            p.size == 44 || p.size == 184 -> {
                var bid: Double? = null
                var ask: Double? = null
                var bidQty: Long? = null
                var askQty: Long? = null
                var depth: Depth? = null
                if (p.size == 184) {
                    // Each entry: qty (4), price (4), orders (2), padding (2).
                    fun level(at: Int) = Level(px(at + 4), int(at), b.getShort(at + 8).toInt() and 0xffff)
                    depth = Depth((0 until 5).map { level(64 + it * 12) }.filter { it.price > 0 },
                        (0 until 5).map { level(64 + 60 + it * 12) }.filter { it.price > 0 })
                    // Depth from byte 64: 5 bids then 5 offers, 12 bytes each (qty, price, orders, padding).
                    bid = px(64 + 4).takeIf { it > 0 }
                    ask = px(64 + 5 * 12 + 4).takeIf { it > 0 }
                    bidQty = int(64).takeIf { bid != null }
                    askQty = int(64 + 5 * 12).takeIf { ask != null }
                }
                Tick(
                    token, last = px(4), volume = int(16), open = px(28), high = px(32), low = px(36), close = px(40),
                    oi = if (p.size == 184) int(48) else 0, bid = bid, ask = ask,
                    exchangeTime = if (p.size == 184) int(60) else null, bidQty = bidQty, askQty = askQty,
                    buyQty = int(20), sellQty = int(24), lastQty = int(8),
                    lastTradeTime = if (p.size == 184) int(44) else null, depth = depth,
                )
            }
            else -> null
        }
    }

    /** The subscribe and mode messages Kite expects, as JSON text. */
    fun subscribe(tokens: Collection<Long>) = """{"a":"subscribe","v":[${tokens.joinToString(",")}]}"""
    fun unsubscribe(tokens: Collection<Long>) = """{"a":"unsubscribe","v":[${tokens.joinToString(",")}]}"""
    fun mode(mode: String, tokens: Collection<Long>) = """{"a":"mode","v":["$mode",[${tokens.joinToString(",")}]]}"""
}
