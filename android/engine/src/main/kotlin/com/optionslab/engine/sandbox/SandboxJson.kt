package com.optionslab.engine.sandbox

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * SandboxState to and from JSON, hand-written so the engine keeps no
 * dependency. Amounts are written as strings ("9950000.00") so they come back
 * as the exact BigDecimal - a JSON number would pass through a double and
 * could not promise that. Timestamps are ISO IST wall clock.
 *
 * [decode] rejects a document it cannot fully read rather than filling
 * defaults: a silently half-restored account (say, orders without their
 * blocked margin) would corrupt every figure derived from it.
 */
object SandboxJson {
    const val VERSION = 1

    fun encode(s: SandboxState): String = buildString {
        val w = Writer(this)
        w.obj {
            field("version") { num(VERSION.toLong()) }
            field("funds") { funds(s.funds) }
            field("orders") { arr(s.orders) { order(it) } }
            field("trades") { arr(s.trades) { trade(it) } }
            field("positions") { arr(s.positions) { position(it) } }
            field("holdings") { arr(s.holdings) { holding(it) } }
            field("orderSeq") { num(s.orderSeq) }
            field("tradeSeq") { num(s.tradeSeq) }
        }
    }

    fun decode(text: String): SandboxState {
        val root = Json.parse(text).asObj()
        val version = root.int("version")
        require(version == VERSION) { "unsupported sandbox state version $version" }
        return SandboxState(
            funds = root.obj("funds").let { f ->
                Funds(
                    f.dec("totalCapital"), f.dec("availableBalance"), f.dec("usedMargin"), f.dec("realizedPnl"),
                    f.dec("todayRealizedPnl"), f.dec("unrealizedPnl"), f.dec("totalPnl"), f.time("lastResetDate"),
                    f.int("resetCount"), f.time("updatedAt"),
                )
            },
            orders = root.list("orders").map { it.asObj() }.map { o ->
                Order(
                    o.str("orderId"), o.strOrNull("strategy"), o.str("symbol"), o.str("exchange"), o.str("action"),
                    o.int("quantity"), o.decOrNull("price"), o.decOrNull("triggerPrice"), o.str("priceType"),
                    o.str("product"), o.str("status"), o.decOrNull("averagePrice"), o.int("filledQuantity"),
                    o.int("pendingQuantity"), o.strOrNull("rejectionReason"), o.dec("marginBlocked"),
                    o.time("orderTimestamp"), o.time("updateTimestamp"),
                )
            },
            trades = root.list("trades").map { it.asObj() }.map { t ->
                Trade(
                    t.str("tradeId"), t.str("orderId"), t.str("symbol"), t.str("exchange"), t.str("action"),
                    t.int("quantity"), t.dec("price"), t.str("product"), t.strOrNull("strategy"), t.time("timestamp"),
                    (if (t.containsKey("charges")) t.decOrNull("charges") else null) ?: java.math.BigDecimal.ZERO,
                )
            },
            positions = root.list("positions").map { it.asObj() }.map { p ->
                Position(
                    p.str("symbol"), p.str("exchange"), p.str("product"), p.int("quantity"), p.dec("averagePrice"),
                    p.decOrNull("ltp"), p.dec("pnl"), p.dec("pnlPercent"), p.dec("accumulatedRealizedPnl"),
                    p.dec("todayRealizedPnl"), p.dec("marginBlocked"), p.time("createdAt"), p.time("updatedAt"),
                )
            },
            holdings = root.list("holdings").map { it.asObj() }.map { h ->
                Holding(
                    h.str("symbol"), h.str("exchange"), h.int("quantity"), h.dec("averagePrice"), h.decOrNull("ltp"),
                    h.dec("pnl"), h.dec("pnlPercent"), LocalDate.parse(h.str("settlementDate")), h.time("createdAt"),
                    h.time("updatedAt"),
                )
            },
            orderSeq = root.long("orderSeq"),
            tradeSeq = root.long("tradeSeq"),
        )
    }

    private fun Writer.funds(f: Funds) = obj {
        field("totalCapital") { dec(f.totalCapital) }
        field("availableBalance") { dec(f.availableBalance) }
        field("usedMargin") { dec(f.usedMargin) }
        field("realizedPnl") { dec(f.realizedPnl) }
        field("todayRealizedPnl") { dec(f.todayRealizedPnl) }
        field("unrealizedPnl") { dec(f.unrealizedPnl) }
        field("totalPnl") { dec(f.totalPnl) }
        field("lastResetDate") { str(f.lastResetDate.toString()) }
        field("resetCount") { num(f.resetCount.toLong()) }
        field("updatedAt") { str(f.updatedAt.toString()) }
    }

    private fun Writer.order(o: Order) = obj {
        field("orderId") { str(o.orderId) }
        field("strategy") { str(o.strategy) }
        field("symbol") { str(o.symbol) }
        field("exchange") { str(o.exchange) }
        field("action") { str(o.action) }
        field("quantity") { num(o.quantity.toLong()) }
        field("price") { dec(o.price) }
        field("triggerPrice") { dec(o.triggerPrice) }
        field("priceType") { str(o.priceType) }
        field("product") { str(o.product) }
        field("status") { str(o.status) }
        field("averagePrice") { dec(o.averagePrice) }
        field("filledQuantity") { num(o.filledQuantity.toLong()) }
        field("pendingQuantity") { num(o.pendingQuantity.toLong()) }
        field("rejectionReason") { str(o.rejectionReason) }
        field("marginBlocked") { dec(o.marginBlocked) }
        field("orderTimestamp") { str(o.orderTimestamp.toString()) }
        field("updateTimestamp") { str(o.updateTimestamp.toString()) }
    }

    private fun Writer.trade(t: Trade) = obj {
        field("tradeId") { str(t.tradeId) }
        field("orderId") { str(t.orderId) }
        field("symbol") { str(t.symbol) }
        field("exchange") { str(t.exchange) }
        field("action") { str(t.action) }
        field("quantity") { num(t.quantity.toLong()) }
        field("price") { dec(t.price) }
        field("product") { str(t.product) }
        field("strategy") { str(t.strategy) }
        field("timestamp") { str(t.timestamp.toString()) }
        if (t.charges.signum() != 0) field("charges") { dec(t.charges) }
    }

    private fun Writer.position(p: Position) = obj {
        field("symbol") { str(p.symbol) }
        field("exchange") { str(p.exchange) }
        field("product") { str(p.product) }
        field("quantity") { num(p.quantity.toLong()) }
        field("averagePrice") { dec(p.averagePrice) }
        field("ltp") { dec(p.ltp) }
        field("pnl") { dec(p.pnl) }
        field("pnlPercent") { dec(p.pnlPercent) }
        field("accumulatedRealizedPnl") { dec(p.accumulatedRealizedPnl) }
        field("todayRealizedPnl") { dec(p.todayRealizedPnl) }
        field("marginBlocked") { dec(p.marginBlocked) }
        field("createdAt") { str(p.createdAt.toString()) }
        field("updatedAt") { str(p.updatedAt.toString()) }
    }

    private fun Writer.holding(h: Holding) = obj {
        field("symbol") { str(h.symbol) }
        field("exchange") { str(h.exchange) }
        field("quantity") { num(h.quantity.toLong()) }
        field("averagePrice") { dec(h.averagePrice) }
        field("ltp") { dec(h.ltp) }
        field("pnl") { dec(h.pnl) }
        field("pnlPercent") { dec(h.pnlPercent) }
        field("settlementDate") { str(h.settlementDate.toString()) }
        field("createdAt") { str(h.createdAt.toString()) }
        field("updatedAt") { str(h.updatedAt.toString()) }
    }

    /** A minimal streaming writer: objects, arrays, strings, integers, null. */
    private class Writer(val out: StringBuilder) {
        private var first = true
        fun obj(body: Writer.() -> Unit) { out.append('{'); first = true; body(); out.append('}'); first = false }
        fun <T> arr(items: List<T>, each: Writer.(T) -> Unit) {
            out.append('[')
            items.forEachIndexed { i, it -> if (i > 0) out.append(','); first = true; each(it) }
            out.append(']'); first = false
        }
        fun field(name: String, value: Writer.() -> Unit) {
            if (!first) out.append(',')
            first = false
            Json.quote(name, out); out.append(':')
            val saved = first
            value()
            first = saved
        }
        fun str(s: String?) { if (s == null) out.append("null") else Json.quote(s, out) }
        fun dec(d: BigDecimal?) = str(d?.toPlainString())
        fun num(n: Long) { out.append(n) }
    }

    private fun Map<String, Any?>.need(k: String): Any? {
        require(containsKey(k)) { "sandbox state is missing '$k'" }
        return get(k)
    }
    private fun Map<String, Any?>.str(k: String): String = need(k) as? String ?: error("'$k' is not a string")
    private fun Map<String, Any?>.strOrNull(k: String): String? = need(k)?.let { it as? String ?: error("'$k' is not a string") }
    private fun Map<String, Any?>.dec(k: String): BigDecimal = BigDecimal(str(k))
    private fun Map<String, Any?>.decOrNull(k: String): BigDecimal? = strOrNull(k)?.let(::BigDecimal)
    private fun Map<String, Any?>.long(k: String): Long = (need(k) as? BigDecimal ?: error("'$k' is not a number")).longValueExact()
    private fun Map<String, Any?>.int(k: String): Int = (need(k) as? BigDecimal ?: error("'$k' is not a number")).intValueExact()
    private fun Map<String, Any?>.time(k: String): LocalDateTime = LocalDateTime.parse(str(k))
    private fun Map<String, Any?>.obj(k: String): Map<String, Any?> = need(k).asObj()
    private fun Map<String, Any?>.list(k: String): List<Any?> = need(k) as? List<Any?> ?: error("'$k' is not an array")
    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObj(): Map<String, Any?> = this as? Map<String, Any?> ?: error("expected an object")
}

/**
 * A small strict JSON reader: objects become LinkedHashMap (key order kept),
 * arrays List, numbers BigDecimal (exact), plus String, Boolean and null.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "trailing characters at ${p.i}" }
        return v
    }

    internal fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
        out.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun fail(what: String): Nothing = throw IllegalArgumentException("bad JSON at $i: $what")
        fun value(): Any? {
            ws()
            if (i >= s.length) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }
        fun lit(word: String, v: Any?): Any? { if (!s.startsWith(word, i)) fail("expected $word"); i += word.length; return v }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s.getOrNull(i) == '}') { i++; return m }
            while (true) {
                ws(); if (s.getOrNull(i) != '"') fail("expected key")
                val k = str(); ws()
                if (s.getOrNull(i) != ':') fail("expected ':'")
                i++
                m[k] = value(); ws()
                when (s.getOrNull(i)) { ',' -> i++; '}' -> { i++; return m }; else -> fail("expected ',' or '}'") }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s.getOrNull(i) == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                when (s.getOrNull(i)) { ',' -> i++; ']' -> { i++; return l }; else -> fail("expected ',' or ']'") }
            }
        }
        fun str(): String {
            val b = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        when (val e = s.getOrNull(i++) ?: fail("bad escape")) {
                            '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c'); 'n' -> b.append('\n')
                            'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> { if (i + 4 > s.length) fail("bad \\u"); b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> fail("bad escape '$e'")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }
        fun num(): BigDecimal {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            if (start == i) fail("unexpected '${s[i]}'")
            return s.substring(start, i).toBigDecimalOrNull() ?: fail("bad number")
        }
    }
}
