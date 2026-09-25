package com.optionslab.engine.sandbox

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Replays the scenarios in resources/sandbox/ - each produced by driving
 * IraAlgo's Python sandbox (gen_fixtures.py) - and requires the Kotlin engine
 * to reproduce, after every step, the call's result and the full stored state:
 * funds, every order, trade, position and holding, to the paisa, including
 * the timestamps the session logic reads.
 */
class SandboxParityTest {
    private val scenarios = listOf(
        "market_mis_round_trip", "stale_quote_and_fallback", "limit_orders_and_reconcile", "stop_orders",
        "futures_reversal", "mis_squareoff", "option_expiry_ltp", "option_expiry_zero_next_day",
        "insufficient_margin", "modify_cancel", "cnc_t1_holdings", "validation", "catch_up_and_daily_reset",
        "weekly_reset", "session_views", "cancel_reducing_orders",
    )

    @Test fun `every scenario matches the Python sandbox step for step`() {
        val failures = scenarios.flatMap { replay(it) }
        if (failures.isNotEmpty()) fail("${failures.size} mismatches:\n" + failures.take(60).joinToString("\n"))
    }

    @Test fun `the fixtures cover every scripted step`() {
        val steps = scenarios.sumOf { (load(it)["steps"] as List<*>).size }
        assertEquals(true, steps >= 200, "only $steps steps")
    }

    private fun load(name: String): Map<String, Any?> {
        val text = javaClass.getResource("/sandbox/$name.json")?.readText() ?: error("missing fixture $name")
        @Suppress("UNCHECKED_CAST")
        return Json.parse(text) as Map<String, Any?>
    }

    private val at = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Suppress("UNCHECKED_CAST")
    private fun replay(name: String): List<String> {
        val doc = load(name)
        val cfg = (doc["config"] as Map<String, Any?>).mapValues { it.value.toString() }
        val instruments = (doc["instruments"] as List<Map<String, Any?>>).map { i ->
            Instrument(
                symbol = i["symbol"] as String, exchange = i["exchange"] as String,
                instrumentType = i["instrumenttype"] as? String ?: "",
                lotSize = (i["lotsize"] as? BigDecimal)?.toInt() ?: 1,
                expiry = (i["expiry"] as? String)?.let(LocalDate::parse),
                strike = (i["strike"] as? BigDecimal)?.toDouble(),
            )
        }
        val sandbox = Sandbox(SandboxConfig.fromConfigMap(cfg), InstrumentMaster.of(instruments))
        var state: SandboxState? = null
        val ids = mutableListOf<String>()
        val out = mutableListOf<String>()

        for ((n, step) in (doc["steps"] as List<Map<String, Any?>>).withIndex()) {
            val now = ZonedDateTime.of(LocalDateTime.parse(step["at"] as String, at), SandboxRules.IST)
            val quotes = quotesOf(step["quotes"])
            val where = "$name#$n ${step["op"]}@${step["at"]}"
            var s = state
            val result: Any? = when (step["op"]) {
                "init" -> { s = sandbox.newState(now); mapOf("ok" to true) }
                "place" -> {
                    val before = s!!.orderSeq
                    val o = sandbox.place(s, request(step["order"] as Map<String, Any?>), quoteOf(step["quote"]), now)
                    s = o.state
                    if (s.orderSeq > before) ids += s.orders.last().orderId
                    orderResult(o.result)
                }
                "modify", "modify_id" -> {
                    val id = if (step["op"] == "modify") ids[(step["ref"] as BigDecimal).toInt()] else step["orderid"] as String
                    val d = step["data"] as Map<String, Any?>
                    val o = sandbox.modify(s!!, id, OrderChange(
                        quantity = (d["quantity"] as? BigDecimal)?.toInt(),
                        price = (d["price"] as? BigDecimal)?.toDouble(),
                        triggerPrice = (d["trigger_price"] as? BigDecimal)?.toDouble(),
                    ), now)
                    s = o.state; orderResult(o.result)
                }
                "cancel", "cancel_id" -> {
                    val id = if (step["op"] == "cancel") ids[(step["ref"] as BigDecimal).toInt()] else step["orderid"] as String
                    val o = sandbox.cancel(s!!, id, now)
                    s = o.state; orderResult(o.result)
                }
                "tick" -> { s = sandbox.onQuotes(s!!, quotes, now).state; null }
                "squareoff" -> { s = sandbox.squareOffDue(s!!, now, quotes).state; null }
                "settle_expiries" -> { s = sandbox.settleExpiries(s!!, now).state; null }
                "t1" -> { val o = sandbox.settleT1(s!!, now); s = o.state; o.result }
                "catch_up" -> { s = sandbox.catchUp(s!!, now).state; null }
                "daily_pnl_reset" -> { s = sandbox.resetDailyPnl(s!!, now).state; null }
                "funds" -> { val o = sandbox.funds(s!!, now); s = o.state; fundsView(o.result) }
                "positions" -> { val o = sandbox.positionBook(s!!, now, quotes); s = o.state; positionsView(o.result) }
                "orderbook" -> orderBookView(sandbox.orderBook(s!!, now))
                "tradebook" -> mapOf("status" to "success", "data" to sandbox.tradeBook(s!!, now).map(::tradeRow), "mode" to "analyze")
                "holdings" -> { val o = sandbox.holdings(s!!, now, quotes); s = o.state; holdingsView(o.result) }
                else -> error("unknown op ${step["op"]}")
            }
            state = s

            val expected = step["result"]
            if (step["op"] == "t1") {
                // "Settled N positions" / "No positions to settle": compare the count.
                val msg = (expected as Map<*, *>)["message"] as String
                val want = Regex("""Settled (\d+) positions""").find(msg)?.groupValues?.get(1)?.toInt() ?: 0
                if (result != want) out += "$where settled $result != $want ($msg)"
            } else {
                compare("$where result", expected, result, out)
            }
            compare("$where state", step["state"], stateView(s!!), out)
            // The persisted form must carry everything: round-trip every step.
            val back = SandboxJson.decode(SandboxJson.encode(s))
            if (back != s) out += "$where JSON round trip changed the state"
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun quotesOf(v: Any?): Map<String, Quote> =
        (v as? Map<String, Any?>)?.entries?.associate { (k, q) ->
            val (sym, ex) = k.split("|")
            Sandbox.key(sym, ex) to quoteOf(q)!!
        } ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun quoteOf(v: Any?): Quote? {
        val q = v as? Map<String, Any?> ?: return null
        fun d(k: String) = (q[k] as? BigDecimal)?.toDouble() ?: 0.0
        return Quote(ltp = d("ltp"), bid = d("bid"), ask = d("ask"), high = d("high"), low = d("low"))
    }

    private fun request(o: Map<String, Any?>) = OrderRequest(
        symbol = o["symbol"] as String?, exchange = o["exchange"] as String?, action = o["action"] as String?,
        quantity = (o["quantity"] as? BigDecimal)?.toInt(), priceType = o["price_type"] as String?,
        product = o["product"] as String?, price = (o["price"] as? BigDecimal)?.toDouble(),
        triggerPrice = (o["trigger_price"] as? BigDecimal)?.toDouble(), strategy = o["strategy"] as? String ?: "",
    )

    private fun orderResult(r: OrderResult): Map<String, Any?> {
        val body = linkedMapOf<String, Any?>("status" to if (r.ok) "success" else "error")
        r.orderId?.let { body["orderid"] = it }
        r.message?.let { body["message"] = it }
        body["mode"] = "analyze"
        return mapOf("ok" to r.ok, "code" to r.httpStatus, "body" to body)
    }

    private fun fundsView(f: FundsView) = mapOf(
        "availablecash" to f.availableCash, "collateral" to f.collateral, "m2munrealized" to f.m2mUnrealized,
        "m2mrealized" to f.m2mRealized, "total_realized_pnl" to f.totalRealizedPnl, "today_realized_pnl" to f.todayRealizedPnl,
        "utiliseddebits" to f.utilisedDebits, "grossexposure" to f.grossExposure, "totalpnl" to f.totalPnl,
        "last_reset" to f.lastReset, "reset_count" to f.resetCount,
    )

    private fun positionsView(b: PositionBook) = mapOf(
        "status" to "success",
        "data" to b.positions.map {
            mapOf(
                "symbol" to it.symbol, "exchange" to it.exchange, "product" to it.product, "quantity" to it.quantity,
                "average_price" to it.averagePrice, "ltp" to it.ltp, "pnl" to it.pnl, "pnlpercent" to it.pnlPercent,
                "unrealized_pnl" to it.unrealizedPnl, "today_realized_pnl" to it.todayRealizedPnl,
                "total_pnl_today" to it.totalPnlToday, "lot_size" to it.lotSize,
            )
        },
        "total_pnl" to b.totalPnl, "total_unrealized_pnl" to b.totalUnrealizedPnl,
        "total_today_realized_pnl" to b.totalTodayRealizedPnl, "total_pnl_today" to b.totalPnlToday, "mode" to "analyze",
    )

    private fun orderBookView(b: OrderBook) = mapOf(
        "status" to "success",
        "data" to mapOf(
            "orders" to b.orders.map {
                mapOf(
                    "orderid" to it.orderId, "symbol" to it.symbol, "exchange" to it.exchange, "action" to it.action,
                    "quantity" to it.quantity, "price" to it.price, "trigger_price" to it.triggerPrice,
                    "pricetype" to it.priceType, "product" to it.product, "order_status" to it.status,
                    "average_price" to it.averagePrice, "filled_quantity" to it.filledQuantity,
                    "pending_quantity" to it.pendingQuantity, "rejection_reason" to it.rejectionReason,
                    "timestamp" to it.timestamp, "strategy" to it.strategy,
                )
            },
            "statistics" to with(b.statistics) {
                mapOf(
                    "total_buy_orders" to totalBuyOrders, "total_sell_orders" to totalSellOrders,
                    "total_completed_orders" to totalCompletedOrders, "total_open_orders" to totalOpenOrders,
                    "total_rejected_orders" to totalRejectedOrders, "total_trigger_pending_orders" to totalTriggerPendingOrders,
                )
            },
        ),
        "mode" to "analyze",
    )

    private fun tradeRow(t: TradeRow) = mapOf(
        "tradeid" to t.tradeId, "orderid" to t.orderId, "symbol" to t.symbol, "exchange" to t.exchange,
        "action" to t.action, "quantity" to t.quantity, "average_price" to t.averagePrice, "price" to t.price,
        "trade_value" to t.tradeValue, "product" to t.product, "strategy" to t.strategy, "timestamp" to t.timestamp,
    )

    private fun holdingsView(b: HoldingsBook) = mapOf(
        "status" to "success",
        "data" to mapOf(
            "holdings" to b.holdings.map {
                mapOf(
                    "symbol" to it.symbol, "exchange" to it.exchange, "product" to it.product, "quantity" to it.quantity,
                    "average_price" to it.averagePrice, "ltp" to it.ltp, "pnl" to it.pnl, "pnlpercent" to it.pnlPercent,
                    "current_value" to it.currentValue, "settlement_date" to it.settlementDate,
                )
            },
            "statistics" to mapOf(
                "totalholdingvalue" to b.totalHoldingValue, "totalinvvalue" to b.totalInvValue,
                "totalprofitandloss" to b.totalProfitAndLoss, "totalpnlpercentage" to b.totalPnlPercentage,
            ),
        ),
        "mode" to "analyze",
    )

    private fun m(d: BigDecimal?, places: Int = 2) = d?.setScale(places, java.math.RoundingMode.HALF_EVEN)?.toPlainString()
    private fun t(x: LocalDateTime) = SandboxRules.ts(x)

    private fun stateView(s: SandboxState) = mapOf(
        "funds" to with(s.funds) {
            mapOf(
                "total_capital" to m(totalCapital), "available_balance" to m(availableBalance), "used_margin" to m(usedMargin),
                "realized_pnl" to m(realizedPnl), "today_realized_pnl" to m(todayRealizedPnl),
                "unrealized_pnl" to m(unrealizedPnl), "total_pnl" to m(totalPnl), "last_reset_date" to t(lastResetDate),
                "reset_count" to resetCount, "updated_at" to t(updatedAt),
            )
        },
        "orders" to s.orders.map {
            mapOf(
                "orderid" to it.orderId, "strategy" to it.strategy, "symbol" to it.symbol, "exchange" to it.exchange,
                "action" to it.action, "quantity" to it.quantity, "price" to m(it.price), "trigger_price" to m(it.triggerPrice),
                "price_type" to it.priceType, "product" to it.product, "order_status" to it.status,
                "average_price" to m(it.averagePrice), "filled_quantity" to it.filledQuantity,
                "pending_quantity" to it.pendingQuantity, "rejection_reason" to it.rejectionReason,
                "margin_blocked" to m(it.marginBlocked), "order_timestamp" to t(it.orderTimestamp),
            )
        },
        "trades" to s.trades.map {
            mapOf(
                "tradeid" to it.tradeId, "orderid" to it.orderId, "symbol" to it.symbol, "exchange" to it.exchange,
                "action" to it.action, "quantity" to it.quantity, "price" to m(it.price), "product" to it.product,
                "strategy" to it.strategy, "trade_timestamp" to t(it.timestamp),
            )
        },
        "positions" to s.positions.map {
            mapOf(
                "symbol" to it.symbol, "exchange" to it.exchange, "product" to it.product, "quantity" to it.quantity,
                "average_price" to m(it.averagePrice), "ltp" to m(it.ltp), "pnl" to m(it.pnl),
                "pnl_percent" to m(it.pnlPercent, 4), "accumulated_realized_pnl" to m(it.accumulatedRealizedPnl),
                "today_realized_pnl" to m(it.todayRealizedPnl), "margin_blocked" to m(it.marginBlocked),
                "created_at" to t(it.createdAt), "updated_at" to t(it.updatedAt),
            )
        },
        "holdings" to s.holdings.map {
            mapOf(
                "symbol" to it.symbol, "exchange" to it.exchange, "quantity" to it.quantity,
                "average_price" to m(it.averagePrice), "ltp" to m(it.ltp), "pnl" to m(it.pnl),
                "pnl_percent" to m(it.pnlPercent, 4), "settlement_date" to it.settlementDate.toString(),
            )
        },
    )

    /** Structural compare; numbers as doubles (exact), "-0.00" equal to "0.00". */
    private fun compare(path: String, want: Any?, got: Any?, out: MutableList<String>) {
        when {
            want is Map<*, *> && got is Map<*, *> -> {
                if (want.keys != got.keys) out += "$path keys: ${got.keys} != ${want.keys}"
                for (k in want.keys) if (k in got.keys) compare("$path.$k", want[k], got[k], out)
            }
            want is List<*> && got is List<*> -> {
                if (want.size != got.size) { out += "$path size ${got.size} != ${want.size}: got $got want $want"; return }
                for (i in want.indices) compare("$path[$i]", want[i], got[i], out)
            }
            want is BigDecimal && got is Number -> if (want.toDouble() != got.toDouble()) out += "$path: $got != $want"
            want is String && got is String -> if (want != got && want.removePrefix("-") != got.removePrefix("-").takeIf { want.trim('-', '0', '.').isEmpty() }) {
                out += "$path: '$got' != '$want'"
            }
            else -> if (want != got) out += "$path: $got != $want"
        }
    }
}
